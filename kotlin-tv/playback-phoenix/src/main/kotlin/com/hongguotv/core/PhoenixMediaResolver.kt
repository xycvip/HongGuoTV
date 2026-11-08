// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.Locale

/**
 * Phoenix response parser.
 *
 * This module only accepts directly playable, unencrypted MP4 resources. Protected
 * CENC/key-seed material is deliberately not derived or decrypted here.
 */
internal object PhoenixMediaResolver {
    fun fallbackUrl(raw: String): String {
        val root = json(raw, "Phoenix 播放响应不是有效 JSON")
        val data = root.optJSONObject("data") ?: return ""
        val model = modelFrom(data)
        return extractFallbackUrl(model.opt("fallback_api")).ifBlank { extractFallbackUrl(data.opt("fallback_api")) }
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

        // The service has returned video_list at multiple levels across app versions.
        // Empty arrays/maps must not mask a populated list at another level.
        val listCandidates = listOf(
            source.opt("video_list"),
            source.optJSONObject("dynamic_video")?.opt("dynamic_video_list"),
            model.opt("video_list"),
            model.optJSONObject("dynamic_video")?.opt("dynamic_video_list"),
            data.opt("video_list"),
            data.optJSONObject("dynamic_video")?.opt("dynamic_video_list")
        )
        val rows = listCandidates.asSequence().map(::rows).firstOrNull { it.isNotEmpty() }.orEmpty()
        if (rows.isEmpty()) {
            throw IOException("Phoenix 未返回视频清晰度（fallback_api=${!fallbackRaw.isNullOrBlank()}）")
        }

        data class Candidate(val quality: Int, val codec: String, val url: String)
        val candidates = mutableListOf<Candidate>()
        var encryptedSkipped = 0
        var unsupportedSkipped = 0

        for ((name, item) in rows) {
            val meta = objectOrString(item.opt("video_meta"))
            val encryption = objectOrString(item.opt("encrypt_info"))
            val codec = meta.str("codec_type").ifEmpty { item.str("codec_type") }.lowercase(Locale.ROOT)
            if (codec in setOf("bytevc2", "bvc2")) {
                unsupportedSkipped++
                continue
            }

            // Do not derive spade_a, key_seed, or decrypt protected media URLs.
            val method = encryption.str("encryption_method").ifEmpty { item.str("encryption_method") }
            val protected = encryption.optBoolean("encrypt") || item.optBoolean("encrypt") ||
                method.isNotBlank() || encryption.str("spade_a").isNotBlank() ||
                item.str("spade_a").isNotBlank() || meta.str("spade_a").isNotBlank()
            if (protected) {
                encryptedSkipped++
                continue
            }

            val definition = meta.str("definition").ifEmpty { item.str("definition") }
            val quality = qualityNumber(definition).takeIf { it > 0 }
                ?: meta.optInt("vheight", item.optInt("vheight",
                    item.optInt("height", qualityNumber(item.str("quality_desc").ifEmpty { name }))))
            val rawUrls = stringValues(item.opt("main_url")) +
                stringValues(item.opt("backup_url")) +
                stringValues(item.opt("backup_url_1")) +
                stringValues(item.opt("backup_url_2")) +
                stringValues(item.opt("backup_urls")) +
                stringValues(item.opt("url_list"))

            for (rawUrl in rawUrls.distinct()) {
                val url = normalizeUrl(rawUrl)
                if (!url.startsWith("https://") && !url.startsWith("http://")) continue
                val path = runCatching { java.net.URI(url).path.orEmpty().lowercase(Locale.ROOT) }
                    .getOrDefault("")
                if (path.endsWith(".m3u8") || path.endsWith(".mpd") || path.endsWith(".ts") ||
                    path.endsWith(".mkv") || path.endsWith(".webm") || path.endsWith(".avi")) {
                    unsupportedSkipped++
                    continue
                }
                candidates += Candidate(quality, codec, url)
            }
        }

        val unique = candidates.distinctBy { Triple(it.quality, it.codec, it.url) }
        if (unique.isEmpty()) {
            throw UnplayableVideoException(
                "Phoenix 无可用 MP4：不兼容编码 $unsupportedSkipped 项，受保护媒体 $encryptedSkipped 项"
            )
        }

        // Choose the best quality within the user's cap. Codec preference is only
        // a tie-breaker, so an available 1080p rendition is not discarded merely
        // because a lower-quality H.264 rendition also exists.
        val withinLimit = unique.filter { it.quality in 1..maxQuality }
        val chosenQuality = if (withinLimit.isNotEmpty()) {
            withinLimit.maxOf { it.quality }
        } else {
            unique.map { it.quality }.filter { it > 0 }.minOrNull() ?: 0
        }
        val sameQuality = unique.filter { it.quality == chosenQuality }
            .sortedBy { if (it.codec in setOf("h264", "avc1")) 0 else 1 }
        val selected = sameQuality.firstOrNull() ?: unique.first()
        val qualityLabel = if (selected.quality > 0) "${selected.quality}P" else "自动"

        // Only same-rendition URLs are safe to swap inside RemoteVideo: different
        // renditions can have different byte lengths and MP4 sample layouts.
        val sameRenditionFallbacks = sameQuality.filter { it.codec == selected.codec && it.url != selected.url }.map { it.url }.distinct()
        return StreamInfo(
            url = selected.url,
            key = null,
            quality = qualityLabel,
            mimeType = "video/mp4",
            fallbackUrls = sameRenditionFallbacks
        )
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
                extractFallbackUrl(value.opt(key)).takeIf {
                    it.startsWith("https://") || it.startsWith("http://")
                }
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
                    ?: runCatching { JSONObject(String(decode64(text), Charsets.UTF_8)) }.getOrElse {
                        throw IOException("Phoenix video_model 格式异常")
                    }
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
        is JSONObject -> value.keys().asSequence()
            .mapNotNull { key -> value.optJSONObject(key)?.let { key to it } }.toList()
        is JSONArray -> (0 until value.length())
            .mapNotNull { i -> value.optJSONObject(i)?.let { i.toString() to it } }
        else -> emptyList()
    }

    private fun stringValues(value: Any?): List<String> = when (value) {
        is String -> listOf(value)
        is JSONArray -> (0 until value.length())
            .mapNotNull { value.optString(it).takeIf(String::isNotBlank) }
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
