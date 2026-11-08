// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.Locale

/** Phoenix two-stage response parser, sharing the existing RemoteVideo CENC contract. */
internal object PhoenixMediaResolver {
    fun fallbackUrl(raw: String): String {
        val root = json(raw, "Phoenix 播放响应不是有效 JSON")
        val data = root.optJSONObject("data") ?: return ""
        val model = modelFrom(data)
        return extractFallbackUrl(model.opt("fallback_api"))
            .ifBlank { extractFallbackUrl(data.opt("fallback_api")) }
    }

    fun parse(raw: String, maxQuality: Int, fallbackRaw: String? = null): StreamInfo {
        val root = json(raw, "Phoenix 播放响应不是有效 JSON")
        val baseResp = root.optJSONObject("BaseResp")
        val code = (root.opt("code") ?: root.opt("Code") ?: baseResp?.opt("StatusCode") ?: 0)
            .toString().toIntOrNull() ?: -1
        if (code != 0) {
            val message = root.str("message").ifEmpty { baseResp?.str("StatusMessage").orEmpty() }
            throw IOException("Phoenix 播放接口业务错误：$code ${message.take(100)}")
        }
        val data = root.optJSONObject("data") ?: throw IOException("Phoenix 播放数据为空")
        val model = modelFrom(data)
        val fallbackRoot = if (!fallbackRaw.isNullOrBlank()) {
            json(fallbackRaw, "Phoenix fallback_api 返回不是有效 JSON")
        } else null
        val fallbackVideoInfo = fallbackRoot?.optJSONObject("video_info")
        val fallbackData = fallbackVideoInfo?.optJSONObject("data")
            ?: fallbackRoot?.optJSONObject("data")
            ?: fallbackVideoInfo
        val source = fallbackData ?: fallbackRoot ?: model
        // 空的 video_list 经常与 dynamic_video 同时出现；不能因为字段存在但为空，
        // 就提前停止回退，否则真实动态视频资源会被误报成“没有清晰度”。
        val listCandidates = listOf(
            source.opt("video_list"),
            source.optJSONObject("dynamic_video")?.opt("dynamic_video_list"),
            model.opt("video_list"),
            model.optJSONObject("dynamic_video")?.opt("dynamic_video_list"),
            data.opt("video_list"),
            data.optJSONObject("dynamic_video")?.opt("dynamic_video_list")
        )
        val rows = listCandidates.asSequence().map(::rows).firstOrNull { it.isNotEmpty() }.orEmpty()
        if (rows.isEmpty()) throw IOException("Phoenix 未返回视频清晰度（fallback_api=${!fallbackRaw.isNullOrBlank()}）")
        // key_seed 在不同版本中可能位于 fallback 的顶层、video_info 或 video_info.data。
        // 逐层查找，避免 URL 已经拿到、却因密钥字段层级变化而把加密媒体误当成普通 MP4。
        val seedText = sequenceOf(
            source.str("key_seed"),
            fallbackVideoInfo?.str("key_seed").orEmpty(),
            fallbackRoot?.str("key_seed").orEmpty(),
            model.str("key_seed"),
            data.str("key_seed")
        ).firstOrNull { it.isNotBlank() }.orEmpty()
        val seed = if (seedText.isNotBlank()) {
            try { decode64(seedText) } catch (_: Exception) { throw IOException("Phoenix key_seed 格式异常") }
        } else null

        data class Candidate(val quality: Int, val codec: String, val url: String, val key: ByteArray?)
        val candidates = mutableListOf<Candidate>()
        var encryptedSkipped = 0
        var unsupportedSkipped = 0
        for ((name, item) in rows) {
            val meta = objectOrString(item.opt("video_meta"))
            val encryption = objectOrString(item.opt("encrypt_info"))
            val codec = meta.str("codec_type").ifEmpty { item.str("codec_type") }.lowercase(Locale.ROOT)
            if (codec in setOf("bytevc2", "bvc2") ||
                item.str("gear_des_key").contains("bytevc2", ignoreCase = true)) {
                unsupportedSkipped++; continue
            }
            val method = encryption.str("encryption_method").ifEmpty { item.str("encryption_method") }
            val spade = encryption.str("spade_a").ifEmpty { item.str("spade_a") }
                .ifEmpty { meta.str("spade_a") }.ifEmpty { item.str("spade_a") }
            val encrypted = encryption.optBoolean("encrypt") || item.optBoolean("encrypt") ||
                method.equals("cenc-aes-ctr", true) || spade.isNotBlank()
            val key = if (spade.isNotBlank()) {
                try { MediaCrypto.deriveKey(spade) } catch (_: Exception) { null }
            } else null
            if (encrypted && key == null) { encryptedSkipped++; continue }

            val definition = meta.str("definition").ifEmpty { item.str("definition") }
            val height = qualityNumber(definition).takeIf { it > 0 }
                ?: meta.optInt("vheight", item.optInt("vheight",
                    item.optInt("height", qualityNumber(item.str("quality_desc").ifEmpty { name }))))
            val rawUrls = stringValues(item.opt("main_url")) + stringValues(item.opt("backup_url")) +
                stringValues(item.opt("backup_url_1")) + stringValues(item.opt("backup_url_2")) +
                stringValues(item.opt("backup_urls")) + stringValues(item.opt("url_list"))
            for (rawUrl in rawUrls.distinct()) {
                val cleaned = cleanUrl(rawUrl)
                val url = if (seed != null) {
                    try { MediaCrypto.decryptUrl(cleaned, seed) } catch (_: Exception) { normalizeUrl(cleaned) }
                } else normalizeUrl(cleaned)
                if (!url.startsWith("https://") && !url.startsWith("http://")) continue
                val path = runCatching { java.net.URI(url).path.orEmpty().lowercase(Locale.ROOT) }.getOrDefault("")
                if (path.endsWith(".m3u8") || path.endsWith(".mpd") || path.endsWith(".ts") ||
                    path.endsWith(".mkv") || path.endsWith(".webm") || path.endsWith(".avi")) {
                    unsupportedSkipped++
                    continue
                }
                candidates += Candidate(height, codec, url, key)
            }
        }
        if (candidates.isEmpty()) {
            throw UnplayableVideoException(
                "Phoenix 无可用 MP4：不兼容编码 $unsupportedSkipped 项，未能解密/不支持的加密资源 $encryptedSkipped 项"
            )
        }
        val withinLimit = candidates.filter { it.quality in 1..maxQuality }
        val chosenQuality = if (withinLimit.isNotEmpty()) {
            withinLimit.maxOf { it.quality }
        } else {
            candidates.map { it.quality }.filter { it > 0 }.minOrNull() ?: 0
        }
        val sameQuality = candidates.filter { it.quality == chosenQuality }
        val selected = sameQuality.maxByOrNull {
            if (it.codec in setOf("h264", "avc1")) 1 else 0
        } ?: candidates.first()
        val quality = if (selected.quality > 0) "${selected.quality}P" else "自动"
        return StreamInfo(selected.url, selected.key, quality, "video/mp4")
    }

    private fun extractFallbackUrl(value: Any?): String = when (value) {
        is String -> {
            val text = value.trim()
            if (text.startsWith("{")) {
                val nested = runCatching { JSONObject(text) }.getOrNull()
                if (nested != null) extractFallbackUrl(nested) else text
            } else if (text.startsWith("[")) {
                val nested = runCatching { JSONArray(text) }.getOrNull()
                if (nested != null) extractFallbackUrl(nested) else text
            } else text
        }
        is JSONObject -> {
            val preferred = listOf("fallback_api", "url", "uri", "api")
            preferred.firstNotNullOfOrNull { key ->
                if (value.has(key)) extractFallbackUrl(value.opt(key)).takeIf { it.isNotBlank() } else null
            } ?: value.keys().asSequence().mapNotNull { key ->
                extractFallbackUrl(value.opt(key)).takeIf { it.startsWith("https://") || it.startsWith("http://") }
            }.firstOrNull().orEmpty()
        }
        is JSONArray -> (0 until value.length()).asSequence()
            .map { extractFallbackUrl(value.opt(it)) }
            .firstOrNull { it.isNotBlank() }.orEmpty()
        else -> ""
    }

    private fun modelFrom(data: JSONObject): JSONObject {
        val raw = data.opt("video_model")
        return when (raw) {
            is JSONObject -> raw
            is String -> {
                val text = raw.trim()
                runCatching { JSONObject(text) }.getOrNull()
                    ?: runCatching { JSONObject(String(decode64(text), Charsets.UTF_8)) }
                        .getOrElse { throw IOException("Phoenix video_model 格式异常") }
            }
            else -> data
        }
    }

    private fun json(raw: String, message: String) =
        runCatching { JSONObject(raw) }.getOrElse { throw IOException(message) }

    private fun objectOrString(value: Any?): JSONObject = when (value) {
        is JSONObject -> value
        is String -> runCatching { JSONObject(value) }.getOrNull() ?: JSONObject()
        else -> JSONObject()
    }

    private fun rows(value: Any?): List<Pair<String, JSONObject>> = when (value) {
        is JSONObject -> value.keys().asSequence().mapNotNull { key -> value.optJSONObject(key)?.let { key to it } }.toList()
        is JSONArray -> (0 until value.length()).mapNotNull { i -> value.optJSONObject(i)?.let { i.toString() to it } }
        else -> emptyList()
    }

    private fun stringValues(value: Any?): List<String> = when (value) {
        is String -> listOf(value)
        is JSONArray -> (0 until value.length()).mapNotNull { value.optString(it).takeIf(String::isNotBlank) }
        else -> emptyList()
    }

    private fun normalizeUrl(value: String): String {
        val text = value.trim()
        if (text.startsWith("https://") || text.startsWith("http://")) return text
        return runCatching { String(decode64(text), Charsets.UTF_8).trim() }.getOrDefault("")
    }

    private fun qualityNumber(value: String): Int =
        Regex("(\\d{3,4})").find(value)?.groupValues?.get(1)?.toIntOrNull() ?: 0
}
