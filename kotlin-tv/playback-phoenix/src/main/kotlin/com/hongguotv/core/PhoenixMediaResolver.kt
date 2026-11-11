// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.Locale

/**
 * Phoenix two-stage response parser.
 * Response-shape compatibility is adapted in part from Xanerix/guoapp (GPL-3.0).
 */
internal object PhoenixMediaResolver {
    fun fallbackUrl(raw: String): String {
        val root = json(raw, "Phoenix 播放响应不是有效 JSON")
        val data = root.optJSONObject("data") ?: return ""
        val model = modelFrom(data)
        return extractFallbackUrl(model.opt("fallback_api"))
    }

    fun needsFallback(raw: String, maxQuality: Int): Boolean {
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return true
        val data = root.optJSONObject("data") ?: return true
        val model = runCatching { modelFrom(data) }.getOrNull() ?: return true
        val inlineRows = rows(model.opt("video_list"))
        if (inlineRows.isEmpty()) return true
        val allByteVc2 = inlineRows.all { (_, item) ->
            val meta = objectOrString(item.opt("video_meta"))
            val codec = item.str("codec_type").ifEmpty { meta.str("codec_type") }.lowercase(Locale.ROOT)
            codec in setOf("bytevc2", "bvc2")
        }
        val undeclaredEncryption = inlineRows.any { (_, item) ->
            val encryption = objectOrString(item.opt("encrypt_info"))
            val spade = item.str("spade_a").ifEmpty { encryption.str("spade_a") }
            spade.isBlank() && !encryption.has("encrypt")
        }
        val portrait = inlineRows.firstNotNullOfOrNull { (_, item) ->
            val meta = objectOrString(item.opt("video_meta"))
            val width = item.optInt("vwidth", item.optInt("width", meta.optInt("vwidth", meta.optInt("width", 0))))
            val height = item.optInt("vheight", item.optInt("height", meta.optInt("vheight", meta.optInt("height", 0))))
            if (width > 0 && height > 0) height > width else null
        }
        val hasUsableDeclaredCandidate = inlineRows.any { (_, item) ->
            hasUsableDeclaredCandidate(item, maxQuality, portrait)
        }
        return allByteVc2 || (undeclaredEncryption && !hasUsableDeclaredCandidate)
    }

    fun needsCodecOverride(fallbackRaw: String): Boolean {
        val root = runCatching { JSONObject(fallbackRaw) }.getOrNull() ?: return false
        val videoInfo = root.optJSONObject("video_info") ?: return false
        val upperCode = root.opt("Code") ?: 0
        val lowerCode = root.opt("code") ?: 0
        val infoCode = videoInfo.opt("Code") ?: 0
        if (upperCode.toString().toIntOrNull() != 0 ||
            lowerCode.toString().toIntOrNull() != 0 ||
            infoCode.toString().toIntOrNull() != 0) return false
        val info = videoInfo.optJSONObject("data") ?: return false
        val offered = rows(info.opt("video_list")).map { it.second }
        return offered.isNotEmpty() && offered.all { row ->
            val meta = objectOrString(row.opt("video_meta"))
            val codec = row.str("codec_type").ifEmpty { meta.str("codec_type") }.lowercase(Locale.ROOT)
            codec in setOf("bytevc2", "bvc2")
        }
    }

    fun parse(raw: String, maxQuality: Int, fallbackRaw: String? = null, onDiagnostic: (String) -> Unit = {}): StreamInfo {
        // 诊断回调不得改变播放行为；只记录字段摘要，不记录完整URL或密钥。
        fun report(message: String) { runCatching { onDiagnostic(message) } }
        val root = json(raw, "Phoenix 播放响应不是有效 JSON")
        val code = (root.opt("code") ?: root.opt("Code") ?: -1).toString().toIntOrNull() ?: -1
        if (code != 0) {
            val message = root.str("message")
            throw IOException("Phoenix 播放接口业务错误：$code ${message.take(100)}")
        }
        val data = root.optJSONObject("data") ?: throw IOException("Phoenix 播放数据为空")
        val model = modelFrom(data)
        // OOIUa 把 fallback_api 的解析/业务错误视为备用线路失败；未声明加密的内联候选仍可进入 MP4 明文校验。
        val fallbackRoot = if (!fallbackRaw.isNullOrBlank()) {
            runCatching { JSONObject(fallbackRaw) }.getOrNull()
        } else null
        val fallbackVideoInfo = fallbackRoot?.optJSONObject("video_info")
        val fallbackCodesOk = fallbackRoot != null &&
            (fallbackRoot.opt("Code") ?: 0).toString().toIntOrNull() == 0 &&
            (fallbackRoot.opt("code") ?: 0).toString().toIntOrNull() == 0 &&
            (fallbackVideoInfo?.opt("Code") ?: 0).toString().toIntOrNull() == 0
        val fallbackData = if (fallbackCodesOk) fallbackVideoInfo?.optJSONObject("data") else null
        val source = fallbackData ?: model
        // OOIUa 只读取 video_model.video_list；未实际取得 fallback_api 时不能把内联列表冒充备用列表。
        val inlineRows = rows(model.opt("video_list"))
        val fallbackRows = fallbackData?.let { rows(it.opt("video_list")) }.orEmpty()
        val inlineOnlyByteVc2 = inlineRows.isNotEmpty() && inlineRows.all { (_, item) ->
            val meta = objectOrString(item.opt("video_meta"))
            val codec = item.str("codec_type").ifEmpty { meta.str("codec_type") }.lowercase(Locale.ROOT)
            codec in setOf("bytevc2", "bvc2")
        }
        val hasUsableDeclaredCandidate = inlineRows.any { (_, item) ->
            hasUsableDeclaredCandidate(item, maxQuality, inlinePortrait)
        }
        val inlineUndeclaredEncryption = !hasUsableDeclaredCandidate && inlineRows.any { (_, item) ->
            val encryption = objectOrString(item.opt("encrypt_info"))
            val spade = item.str("spade_a").ifEmpty { encryption.str("spade_a") }
            spade.isBlank() && !encryption.has("encrypt")
        }
        val referenceId = model.str("video_id").trim()
        val referenceDuration = model.opt("video_duration")?.toString()?.toDoubleOrNull() ?: 0.0
        val fallbackId = source.str("video_id").trim()
        val fallbackDuration = source.opt("video_duration")?.toString()?.toDoubleOrNull() ?: 0.0
        val inlinePortrait = inlineRows.firstNotNullOfOrNull { (_, item) ->
            val width = item.optInt("vwidth", item.optInt("width", 0))
            val height = item.optInt("vheight", item.optInt("height", 0))
            if (width > 0 && height > 0) height > width else null
        }
        val inlineTarget = PlaybackQualityMode.limit(maxQuality, inlinePortrait)
        val inlineTracks = inlineRows.mapNotNull { (_, item) ->
            val meta = objectOrString(item.opt("video_meta"))
            val width = item.optInt("vwidth", item.optInt("width", meta.optInt("vwidth", meta.optInt("width", 0))))
            val height = item.optInt("vheight", item.optInt("height", meta.optInt("vheight", meta.optInt("height", 0))))
            val codec = item.str("codec_type").ifEmpty { meta.str("codec_type") }.lowercase(Locale.ROOT)
            if (width > 0 && height > 0 && codec in setOf("h264", "avc", "avc1", "hevc", "h265", "hvc1", "hev1", "bytevc1")) {
                codec to minOf(width, height)
            } else null
        }
        val inlineMinimumPixels = if (maxQuality == PlaybackQualityMode.AUTO_COMPAT) {
            inlineTracks.filter { it.first in setOf("h264", "avc", "avc1") }.maxOfOrNull { it.second }
                ?: inlineTracks.maxOfOrNull { it.second } ?: 0
        } else {
            inlineTracks.map { it.second }.filter { it >= inlineTarget }.minOrNull() ?: 0
        }
        val verifiedUndeclaredFallback = !inlineOnlyByteVc2 && inlineUndeclaredEncryption &&
            fallbackRows.isNotEmpty() && inlineMinimumPixels > 0 &&
            referenceId.isNotEmpty() && fallbackId == referenceId &&
            referenceDuration > 0.0 && fallbackDuration > 0.0 &&
            kotlin.math.abs(referenceDuration - fallbackDuration) <= 0.5
        // OOIUa 优先内联；仅在内联缺失、全为 ByteVC2，或未声明加密且备用模型身份/时长/最低像素通过校验时使用 fallback。
        val useFallbackRows = inlineRows.isEmpty() ||
            (fallbackRows.isNotEmpty() && (inlineOnlyByteVc2 || verifiedUndeclaredFallback))
        if (inlineOnlyByteVc2 && fallbackRows.isNotEmpty()) confirmSameVideo(model, source)
        val rows = if (useFallbackRows) fallbackRows else inlineRows
        if (rows.isEmpty()) throw IOException("Phoenix 未返回视频清晰度（fallback_api=${!fallbackRaw.isNullOrBlank()}）")
        report("Phoenix资源筛选：发现 ${rows.size} 项候选，数据来源=${if (!fallbackRaw.isNullOrBlank()) "fallback" else "video_model"}")
        // key_seed 在不同版本中可能位于 fallback 的顶层、video_info 或 video_info.data。
        // 逐层查找，避免 URL 已经拿到、却因密钥字段层级变化而把加密媒体误当成普通 MP4。
        val seedText = if (useFallbackRows) source.str("key_seed") else model.str("key_seed")
        val seed = if (seedText.isNotBlank()) {
            try { decode64(seedText) } catch (_: Exception) { throw IOException("Phoenix key_seed 格式异常") }
        } else null

        data class Candidate(
            val quality: Int, val codec: String, val url: String, val key: ByteArray?,
            val width: Int, val height: Int, val shortSide: Int,
            val requirePlainMp4Validation: Boolean
        )
        val candidates = mutableListOf<Candidate>()
        var encryptedSkipped = 0
        var unsupportedSkipped = 0
        for ((name, item) in rows) {
            val meta = objectOrString(item.opt("video_meta"))
            val encryption = objectOrString(item.opt("encrypt_info"))
            val codec = item.str("codec_type").ifEmpty { meta.str("codec_type") }.ifEmpty { "unknown" }.lowercase(Locale.ROOT)
            val definition = item.str("quality_desc").ifEmpty { item.str("definition") }
                .ifEmpty { meta.str("definition") }.ifEmpty { item.str("height").ifEmpty { item.str("vheight") } }
            val dimensions = listOf(
                item.optInt("vwidth", 0) to item.optInt("vheight", 0),
                item.optInt("width", 0) to item.optInt("height", 0),
                meta.optInt("vwidth", 0) to meta.optInt("vheight", 0),
                meta.optInt("width", 0) to meta.optInt("height", 0)
            ).firstOrNull { it.first > 0 && it.second > 0 }
            val reportedWidth = dimensions?.first ?: 0
            val reportedHeight = dimensions?.second ?: 0
            val height = qualityNumber(definition).takeIf { it > 0 }
                ?: reportedHeight.takeIf { it > 0 }
            val mime = meta.str("mime_type").ifEmpty { item.str("mime_type") }.ifEmpty { "unknown" }
            report("Phoenix资源#$name：开始筛选 codec=$codec，mime=$mime，definition=${definition.ifBlank { "unknown" }}，height=${height.takeIf { it > 0 } ?: "unknown"}")
            if (codec !in setOf("h264", "avc", "avc1", "hevc", "h265", "hvc1", "hev1", "bytevc1")) {
                unsupportedSkipped++
                report("Phoenix资源#$name：拒绝，原因=不支持的编码($codec)")
                continue
            }
            val method = encryption.str("encryption_method").ifEmpty { item.str("encryption_method") }
            val spade = item.str("spade_a").ifEmpty { encryption.str("spade_a") }
            val undeclaredEncryption = spade.isBlank() && !encryption.has("encrypt")
            val encrypted = (!declaredPlain(encryption) && encryption.has("encrypt")) || spade.isNotBlank()
            val key = if (spade.isNotBlank()) {
                try { PhoenixSpade.deriveKey(spade) } catch (_: Exception) { null }
            } else null
            if (encrypted && key == null) { encryptedSkipped++; report("Phoenix资源#$name：拒绝，原因=加密资源缺少可用密钥；encryption_method=${method.ifBlank { "unknown" }}"); continue }
            if (!useFallbackRows && undeclaredEncryption && !inlineUndeclaredEncryption) {
                encryptedSkipped++
                report("Phoenix资源#$name：跳过未声明加密候选，优先保留已有密钥或明确明文声明的片源")
                continue
            }
            if (verifiedUndeclaredFallback && useFallbackRows &&
                spade.isBlank() && !declaredPlain(encryption)) {
                encryptedSkipped++
                report("Phoenix备用资源#$name：拒绝，原因=备用模型缺少明确的明文声明或有效密钥")
                continue
            }

            val rawUrls = listOf(item.opt("main_url"), item.opt("backup_url"))
                .filterIsInstance<String>().filter(String::isNotBlank) +
                stringValues(item.opt("backup_url_list"))
            if (rawUrls.isEmpty()) report("Phoenix资源#$name：拒绝，原因=没有可识别的媒体URL字段")
            for (rawUrl in rawUrls) {
                val cleaned = cleanUrl(rawUrl)
                val url = if (seed != null) {
                    try { MediaCrypto.decryptUrl(cleaned, seed) }
                    catch (error: Exception) { throw IOException("Phoenix 媒体 URL 解密失败", error) }
                } else normalizeUrl(cleaned)
                if (!isAllowedVodUrl(url)) { report("Phoenix资源#$name：拒绝一个URL，原因=不符合 OOIUa VOD_DOMAINS 校验"); continue }
                val path = runCatching { java.net.URI(url).path.orEmpty().lowercase(Locale.ROOT) }.getOrDefault("")
                if (path.endsWith(".m3u8") || path.endsWith(".mpd") || path.endsWith(".ts") ||
                    path.endsWith(".mkv") || path.endsWith(".webm") || path.endsWith(".avi")) {
                    unsupportedSkipped++
                    report("Phoenix资源#$name：拒绝URL，原因=当前链路不支持容器格式(${path.substringAfterLast('.', "unknown")})")
                    continue
                }
                val shortSide = if (reportedWidth > 0 && reportedHeight > 0) minOf(reportedWidth, reportedHeight) else height
                if (verifiedUndeclaredFallback && useFallbackRows && shortSide < inlineMinimumPixels) {
                    report("Phoenix备用资源#$name：拒绝，原因=清晰度低于内联候选最低像素要求")
                    continue
                }
                candidates += Candidate(
                    height, codec, url, key, reportedWidth, reportedHeight, shortSide,
                    key == null
                )
                val safeHost = runCatching { java.net.URI(url).host.orEmpty() }.getOrDefault("")
                report("Phoenix资源#$name：URL通过初筛，host=$safeHost，codec=$codec，height=${height.takeIf { it > 0 } ?: "unknown"}")
            }
        }
        report("Phoenix资源筛选汇总：候选项=${rows.size}，通过媒体URL=${candidates.size}，不兼容编码/容器=$unsupportedSkipped，加密密钥不可用=$encryptedSkipped")
        if (candidates.isEmpty() && inlineRows.isNotEmpty() && fallbackRows.isNotEmpty() &&
            inlineRows.all { (_, item) ->
                val meta = objectOrString(item.opt("video_meta"))
                val codec = meta.str("codec_type").ifEmpty { item.str("codec_type") }.lowercase(Locale.ROOT)
                codec in setOf("bytevc2", "bvc2")
            }) {
            confirmSameVideo(model, source)
            val fallbackEnvelope = JSONObject().put("code", 0)
                .put("data", JSONObject().put("video_model", source))
            return parse(fallbackEnvelope.toString(), maxQuality, null, onDiagnostic)
        }
        if (candidates.isEmpty() && verifiedUndeclaredFallback && useFallbackRows) {
            // 备用模型未通过密钥/明文声明/最低像素校验时，回到内联未声明候选；
            // 该候选随后必须通过 P 专用 MP4 明文索引检查，不能直接当作明文播放。
            return parse(raw, maxQuality, null, onDiagnostic)
        }
        if (candidates.isEmpty()) {
            throw UnplayableVideoException(
                "Phoenix 无可用 MP4：不兼容编码 $unsupportedSkipped 项，未能解密/不支持的加密资源 $encryptedSkipped 项"
            )
        }
        val portrait = candidates.firstNotNullOfOrNull { candidate ->
            if (candidate.width > 0 && candidate.height > 0) candidate.height > candidate.width else null
        }
        val selectionLimit = PlaybackQualityMode.limit(maxQuality, portrait)
        // 兼容优先：只要存在 H.264/AVC1，就选择其中最高分辨率；没有时才回退其他编码。
        // 高清优先及手动档位仍按分辨率选档，同档位优先 H.264。
        val pool = candidates.sortedWith(compareByDescending<Candidate> { it.quality }
            .thenBy { if (it.codec in setOf("h264", "avc", "avc1")) 0 else 1 })
        val h264 = candidates.filter { it.codec in setOf("h264", "avc", "avc1") }
        val selected = if (maxQuality == PlaybackQualityMode.AUTO_COMPAT) {
            h264.maxByOrNull { it.quality } ?: pool.first()
        } else {
            pool.filter { it.shortSide >= selectionLimit && it.shortSide > 0 }
                .minWithOrNull(compareBy<Candidate> { it.shortSide }
                    .thenBy { if (it.codec in setOf("h264", "avc", "avc1")) 0 else 1 })
                ?: throw UnplayableVideoException("Phoenix 未提供达到指定清晰度的片源")
        }
        val quality = if (selected.quality > 0) "${selected.quality}P" else "自动"
        val selectedHost = runCatching { java.net.URI(selected.url).host.orEmpty() }.getOrDefault("")
        report("Phoenix资源筛选结果：选中 codec=${selected.codec}，清晰度=$quality，host=$selectedHost；完整URL及敏感参数已隐藏")
        return StreamInfo(
            selected.url, selected.key, quality, "video/mp4",
            selected.requirePlainMp4Validation
        )
    }

    private fun confirmSameVideo(reference: JSONObject, other: JSONObject) {
        val id = reference.str("video_id").trim()
        val otherId = other.str("video_id").trim()
        val duration = reference.opt("video_duration")?.toString()?.toDoubleOrNull() ?: 0.0
        val otherDuration = other.opt("video_duration")?.toString()?.toDoubleOrNull() ?: 0.0
        if ((id.isNotEmpty() && otherId.isNotEmpty() && id != otherId) ||
            (duration > 0.0 && otherDuration > 0.0 && kotlin.math.abs(duration - otherDuration) > 0.5)) {
            throw IOException("备用片源编号或完整时长无法确认，已停止取址")
        }
    }

    /** OOIUa fallback_api 只接受 URL 字符串、首个数组元素、对象的 fallback_api 字段或 JSON 字符串。 */
    private fun extractFallbackUrl(value: Any?): String = when (value) {
        is String -> {
            val text = value.trim()
            if (text.startsWith("{")) {
                val nested = runCatching { JSONObject(text) }.getOrNull()
                if (nested != null) nested.opt("fallback_api")?.toString() ?: text else text
            } else text
        }
        is JSONObject -> value.opt("fallback_api")?.toString().orEmpty()
        is JSONArray -> if (value.length() > 0) value.opt(0)?.toString().orEmpty() else ""
        else -> ""
    }

    private fun modelFrom(data: JSONObject): JSONObject {
        val raw = data.opt("video_model")
        return when (raw) {
            is JSONObject -> raw
            is String -> {
                val text = raw.trim()
                val direct = runCatching { JSONObject(text) }.getOrNull()
                if (direct != null) direct else {
                    // 部分响应把 video_model 本体编码为 Base64 JSON。
                    val decoded = runCatching {
                        val bytes = try {
                            java.util.Base64.getDecoder().decode(text)
                        } catch (_: IllegalArgumentException) {
                            java.util.Base64.getUrlDecoder().decode(text)
                        }
                        JSONObject(String(bytes, Charsets.UTF_8))
                    }.getOrNull()
                    decoded ?: throw IOException("Phoenix video_model 格式异常")
                }
            }
            else -> JSONObject()
        }
    }

    private fun json(raw: String, message: String) =
        runCatching { JSONObject(raw) }.getOrElse { throw IOException(message) }

    /** OOIUa 先按清晰度/编码筛选，再优先返回已有有效密钥或明确声明明文的候选。 */
    private fun hasUsableDeclaredCandidate(item: JSONObject, maxQuality: Int, portrait: Boolean?): Boolean {
        val encryption = objectOrString(item.opt("encrypt_info"))
        val meta = objectOrString(item.opt("video_meta"))
        val codec = item.str("codec_type").ifEmpty { meta.str("codec_type") }.lowercase(Locale.ROOT)
        if (codec !in setOf("h264", "avc", "avc1", "hevc", "h265", "hvc1", "hev1", "bytevc1")) return false
        val width = item.optInt("vwidth", item.optInt("width", meta.optInt("vwidth", meta.optInt("width", 0))))
        val height = item.optInt("vheight", item.optInt("height", meta.optInt("vheight", meta.optInt("height", 0))))
        val definition = item.str("quality_desc").ifEmpty { item.str("definition") }
            .ifEmpty { meta.str("definition") }.ifEmpty { item.str("height").ifEmpty { item.str("vheight") } }
        val shortSide = if (width > 0 && height > 0) minOf(width, height) else qualityNumber(definition)
        val target = PlaybackQualityMode.limit(maxQuality, portrait)
        if (target != Int.MAX_VALUE && shortSide < target) return false
        val spade = item.str("spade_a").ifEmpty { encryption.str("spade_a") }
        if (spade.isNotBlank()) return runCatching { PhoenixSpade.deriveKey(spade) }.isSuccess
        return declaredPlain(encryption)
    }
    /** 与 OOIUa declaredPlain 一致：只看 encrypt_info.encrypt，顶层 encrypt 不参与判断。 */
    private fun declaredPlain(encryption: JSONObject): Boolean {
        val flag = encryption.opt("encrypt") ?: return false
        return when (flag) {
            is Boolean -> !flag
            is Number -> flag.toLong() == 0L
            is String -> flag.trim() == "0" || flag.trim().equals("false", ignoreCase = true)
            else -> false
        }
    }

    private fun objectOrString(value: Any?): JSONObject =
        (value as? JSONObject) ?: JSONObject()

    private fun rows(value: Any?): List<Pair<String, JSONObject>> = when (value) {
        // Keep the source object's iteration order, matching OOIUa map-value traversal.
        is JSONObject -> value.keys().asSequence()
            .mapNotNull { key -> value.optJSONObject(key)?.let { key to it } }
        is JSONArray -> (0 until value.length()).mapNotNull { i -> value.optJSONObject(i)?.let { i.toString() to it } }
        else -> emptyList()
    }

    private fun stringValues(value: Any?): List<String> = when (value) {
        is JSONArray -> (0 until value.length()).mapNotNull { value.optString(it).takeIf(String::isNotBlank) }
        else -> emptyList()
    }

    /** 与 OOIUa checkedUrl(value, VOD_DOMAINS) 一致：仅 HTTPS、默认/443 端口及白名单域名。 */
    private fun isAllowedVodUrl(value: String): Boolean {
        val uri = runCatching { java.net.URI(value) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        val host = uri.host?.lowercase(Locale.ROOT).orEmpty()
        val allowed = listOf("qznovelvod.com", "fqnovelvod.com", "douyinvod.com")
            .any { host == it || host.endsWith("." + it) }
        return scheme == "https" && uri.userInfo == null &&
            (uri.port == -1 || uri.port == 443) && allowed
    }

    private fun normalizeUrl(value: String): String {
        val text = value.trim()
        return text.takeIf { it.startsWith("https://") || it.startsWith("http://") }.orEmpty()
    }

    private fun qualityNumber(value: String): Int =
        Regex("(\\d{3,4})").find(value)?.groupValues?.get(1)?.toIntOrNull() ?: 0
}
