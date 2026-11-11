// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * 独立 Phoenix video_model/v1 通道。
 *
 * 请求成功后继续解析 fallback_api，并将 key_seed / spade_a 交给现有
 * MediaCrypto + RemoteVideo MP4 分段解密链路。该通道仍由独立 API 选择项控制，
 * 不会在失败时静默切换到其他播放接口。
 */
data class PhoenixPlaybackDiagnostic(
    val quality: String,
    val mediaHost: String,
    val httpStatus: Int?,
    val contentType: String,
    val bytesRead: Int,
    val elapsedMs: Long,
    val detail: String,
    val cencKeyPresent: Boolean = false,
    val cencSampleCount: Int = 0,
    val mp4HeaderValid: Boolean = false
) {
    val mediaReachable: Boolean get() = httpStatus != null && httpStatus in 200..299 && bytesRead > 0
    val mediaPrepared: Boolean get() = mediaReachable && mp4HeaderValid &&
        (!cencKeyPresent || cencSampleCount > 0)
}

class PhoenixPlaybackSource(
    private val http: OkHttpClient,
    private val session: PhoenixPlaybackDeviceSession,
    private val onDiagnostic: (String) -> Unit = {},
) : StreamSource {
    override val name: String = "phoenix-video-model"

    override fun stream(id: String, maxQuality: Int): StreamInfo = resolve(id, maxQuality)

    /** Diagnostics must never be allowed to change playback behavior. */
    private fun emitDiagnostic(message: String) {
        runCatching { onDiagnostic(message) }
    }

    /**
     * 独立诊断：先获取 Phoenix 播放地址，再用 Range GET 读取最多 1 KiB，
     * 验证 CDN 是否实际返回媒体数据。不会记录完整 URL、Cookie 或签名参数。
     */
    fun diagnose(id: String, maxQuality: Int = 1080): PhoenixPlaybackDiagnostic {
        val info = resolve(id, maxQuality)
        val host = runCatching { java.net.URI(info.url).host.orEmpty() }.getOrDefault("")
        val started = System.nanoTime()
        val remote = RemoteVideo(http, info)
        return try {
            // 直接走播放器相同的 MP4 头解析、CENC 准备和解密读取，不再只测 CDN 是否回 1 KiB。
            remote.prepare()
            val sampleLength = minOf(64 * 1024L, remote.total).toInt()
            val firstBytes = if (sampleLength > 0) remote.read(0L, sampleLength) else byteArrayOf()
            val headerValid = firstBytes.size >= 8 &&
                String(firstBytes, 4, 4, Charsets.US_ASCII) == "ftyp"
            val sampleCount = remote.preparedSampleCount
            val elapsed = (System.nanoTime() - started) / 1_000_000
            val keyPresent = info.key != null
            val detail = when {
                !headerValid -> "已取得媒体数据，但解密后的 MP4 文件头校验失败"
                keyPresent && sampleCount == 0 -> "已取得媒体数据，但 CENC 密钥未解析出加密样本"
                keyPresent -> "CDN、MP4 头解析及 CENC 样本准备均成功"
                else -> "CDN 与 MP4 头解析成功；该资源未携带 CENC 密钥"
            }
            PhoenixPlaybackDiagnostic(
                info.quality, host, 206, "video/mp4", firstBytes.size, elapsed, detail,
                keyPresent, sampleCount, headerValid
            )
        } catch (error: java.io.InterruptedIOException) {
            throw error
        } catch (error: IOException) {
            val elapsed = (System.nanoTime() - started) / 1_000_000
            PhoenixPlaybackDiagnostic(
                info.quality, host, null, "", 0, elapsed,
                "播放器媒体准备失败：${error.message ?: error.javaClass.simpleName}".take(160),
                info.key != null, remote.preparedSampleCount, false
            )
        } finally {
            remote.close()
        }
    }

    fun resolve(id: String, maxQuality: Int = 1080): StreamInfo {
        require(id.matches(Regex("[0-9]{1,30}"))) { "无效的分集 ID" }
        emitDiagnostic("Phoenix：按 OOIUa 流程注册匿名播放设备")
        val playbackHttp = http.newBuilder().callTimeout(8, TimeUnit.SECONDS).followRedirects(false).build()
        var identity = session.profile(http)
        val playbackStarted = System.currentTimeMillis()
        val singleBody = JSONObject()
            .put("video_id", id)
            .put("content_type", 1)
            .put("biz_param", JSONObject()
                .put("need_all_video_definition", true)
                .put("video_platform", 3))
            .toString().toByteArray(Charsets.UTF_8)

        var primaryFailure: IOException? = null
        try {
            val values = java.util.TreeMap(VendorConstants.device).apply {
                listOf("host_abi", "dragon_device_type", "pv_player", "compliance_status",
                    "need_personal_recommend", "player_so_load", "is_android_pad_screen").forEach(::remove)
                put("version_code", "73532")
                put("manifest_version_code", "73532")
                put("update_version_code", "73532")
                put("version_name", "7.3.5.32")
                put("device_id", identity.values["device_id"].orEmpty())
                put("iid", identity.values["iid"].orEmpty())
                put("_rticket", System.currentTimeMillis().toString())
            }
            if (values["device_id"].isNullOrBlank() || values["iid"].isNullOrBlank()) {
                throw PhoenixPlaybackDeviceRegistrationException(IOException("设备注册身份不完整"))
            }
            val query = values.entries.joinToString("&") { (key, value) ->
                java.net.URLEncoder.encode(key, "UTF-8").replace("+", "%20") + "=" +
                    java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")
            }
            val url = "https://api5-normal-sinfonlineb.fqnovel.com/novel/player/video_model/v1/?$query"
            val headers = PhoenixPlayerSigner.headers(
                query, singleBody, identity.values["device_id"].orEmpty(),
                values["_rticket"]!!.toLong(), VendorConstants.VIDEO_UA.replace("71332", "73532")
            )
            val request = Request.Builder().url(url).apply {
                headers.forEach { (key, value) -> header(key, value) }
                if (identity.cookie.isNotBlank()) header("Cookie", identity.cookie)
            }.post(singleBody.toRequestBody("application/json; charset=utf-8".toMediaType())).build()
            val raw = RequestScope.execute(playbackHttp.newCall(request)) { response ->
                if (response.code != 200) throw IOException("OOIUa 单集播放接口 HTTP ${response.code}")
                val body = response.body ?: throw IOException("OOIUa 单集播放接口响应为空")
                if (body.contentLength() > 16L * 1024 * 1024) throw IOException("播放响应过大")
                val bytes = body.bytes()
                if (bytes.size > 16 * 1024 * 1024) throw IOException("播放响应过大")
                String(bytes, Charsets.UTF_8)
            }
            if (raw.isBlank()) throw IOException("OOIUa 单集播放接口返回空响应")
            return resolveResponse(raw, maxQuality).also {
                emitDiagnostic("Phoenix：OOIUa 单集主线路命中，清晰度=${it.quality}")
            }
        } catch (error: java.io.InterruptedIOException) {
            throw error
        } catch (error: IOException) {
            primaryFailure = error
            emitDiagnostic("Phoenix：单集主线路失败，转入 OOIUa 备用播放阶梯")
        }

        val legacyFailure = tryLegacyRoutes(id, maxQuality, identity, playbackHttp, playbackStarted)
        if (legacyFailure.first != null) return legacyFailure.first!!
        throw legacyFailure.second ?: primaryFailure ?: IOException("Phoenix 播放接口暂不可用")
    }

    private fun tryLegacyRoutes(
        id: String,
        maxQuality: Int,
        initialIdentity: PhoenixPlaybackDeviceSession.Profile,
        playbackHttp: OkHttpClient,
        started: Long
    ): Pair<StreamInfo?, IOException?> {
        data class Route(val post: Boolean, val host: String, val renewIdentity: Boolean)
        val mainHost = "api5-normal-sinfonlineb.fqnovel.com"
        val routes = listOf(
            Route(false, mainHost, false),
            Route(true, mainHost, false),
            Route(false, mainHost, true),
            Route(true, "api5-normal-hl.fqnovel.com", false)
        )
        var identity = initialIdentity
        var modelFailure: IOException? = null
        var requestFailure: IOException? = null
        val legacyBody = JSONObject()
            .put("biz_param", JSONObject()
                .put("detail_page_version", 0)
                .put("device_level", 1)
                .put("disable_digg_stat", false)
                .put("need_all_video_definition", true)
                .put("need_mp4_align", false)
                .put("use_os_player", false)
                .put("use_server_dns", false)
                .put("video_platform", 1024))
            .put("mixed_video_id_map", JSONObject().put("1004", JSONArray().put(id)))
            .toString().toByteArray(Charsets.UTF_8)

        for ((index, route) in routes.withIndex()) {
            if (System.currentTimeMillis() - started >= 20_000L) break
            RequestScope.ensureActive()
            if (route.renewIdentity) {
                try {
                    identity = session.renew(http)
                } catch (error: java.io.InterruptedIOException) {
                    throw error
                } catch (error: IOException) {
                    requestFailure = error
                }
            }
            try {
                val values = linkedMapOf<String, String>().apply {
                    putAll(VendorConstants.device)
                    put("device_id", identity.values["device_id"].orEmpty())
                    put("iid", identity.values["iid"].orEmpty())
                    put("version_code", "73932")
                    put("manifest_version_code", "73932")
                    put("update_version_code", "73932")
                    put("pv_player", "73932")
                    put("version_name", "7.3.9.32")
                    put("cdid", identity.values["cdid"].orEmpty())
                    if (!route.post) {
                        put("video_id", id)
                        put("video_id_type", "0")
                        put("device_level", "1")
                        put("need_all_video_definition", "1")
                        put("need_mp4_align", "0")
                        put("use_os_player", "0")
                        put("video_platform", "1024")
                        put("source", "0")
                        put("from_video_id", "")
                    }
                }
                val base = if (route.post) {
                    "https://${route.host}/novel/player/multi_video_model/v1/"
                } else {
                    "https://${route.host}/reading/bookapi/video_tab/multi_video_model/v1/"
                }
                val body = if (route.post) legacyBody else null
                val signed = PhoenixLegacySigner().signPlayback(base, values, body, PLAYBACK_UA)
                val builder = Request.Builder().url(signed.url)
                signed.headers.forEach { (key, value) -> builder.header(key, value) }
                if (identity.cookie.isNotBlank()) builder.header("Cookie", identity.cookie)
                val request = if (body == null) builder.get().build()
                    else builder.post(body.toRequestBody("application/json; charset=utf-8".toMediaType())).build()
                val raw = RequestScope.execute(playbackHttp.newCall(request)) { response ->
                    if (response.code != 200) throw IOException("OOIUa 备用播放线路 HTTP ${response.code}")
                    val responseBody = response.body ?: throw IOException("备用播放响应为空")
                    if (responseBody.contentLength() > 16L * 1024 * 1024) throw IOException("备用播放响应过大")
                    val bytes = responseBody.bytes()
                    if (bytes.size > 16 * 1024 * 1024) throw IOException("备用播放响应过大")
                    String(bytes, Charsets.UTF_8)
                }
                val normalized = normalizeLegacyResponse(raw, id, route.post)
                if (normalized == null) {
                    modelFailure = IOException("OOIUa 备用线路未返回本集有效 video_model")
                    continue
                }
                return try {
                    resolveResponse(normalized, maxQuality) to null
                } catch (error: java.io.InterruptedIOException) {
                    throw error
                } catch (error: IOException) {
                    modelFailure = error
                    emitDiagnostic("Phoenix：备用线路 ${index + 1} 模型解析失败，继续下一线路")
                    continue
                }
            } catch (error: java.io.InterruptedIOException) {
                throw error
            } catch (error: IOException) {
                requestFailure = error
                emitDiagnostic("Phoenix：OOIUa 备用线路 ${index + 1} 请求失败")
            }
        }
        return null to (modelFailure ?: requestFailure)
    }

    private fun normalizeLegacyResponse(raw: String, id: String, post: Boolean): String? {
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val code = root.optInt("code", root.optInt("Code", -1))
        if (code != 0) return null
        val data = root.optJSONObject("data") ?: return null
        val rawModel = if (post) {
            val row = data.optJSONObject(id)
            row?.opt("video_model") ?: data.opt("video_model")
        } else {
            val rows = data.optJSONObject("video_model_data") ?: return null
            rows.optJSONObject(id)?.opt("video_model")
        } ?: return null
        val model = when (rawModel) {
            is JSONObject -> rawModel
            is String -> runCatching { JSONObject(rawModel) }.getOrNull()
            else -> null
        } ?: return null
        val list = model.opt("video_list")
        val hasRows = when (list) {
            is JSONObject -> list.length() > 0
            is JSONArray -> list.length() > 0
            else -> false
        }
        if (!hasRows && !model.has("fallback_api")) return null
        return JSONObject().put("code", 0).put("data", JSONObject().put("video_model", model)).toString()
    }

    /**
     * Resolve the optional second-stage fallback_api endpoint before parsing media.
     * This is a plain GET with the reader UA; credentials and signed query data are not logged.
     */
    private fun resolveResponse(raw: String, maxQuality: Int): StreamInfo {
        val fallbackUrl = PhoenixMediaResolver.fallbackUrl(raw)
        fun fetchFallback(url: String): String {
            val fallbackHttp = http.newBuilder().callTimeout(15, TimeUnit.SECONDS).followRedirects(false).build()
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", VendorConstants.VIDEO_UA)
                .get()
                .build()
            return RequestScope.execute(fallbackHttp.newCall(request)) { response ->
                if (!response.isSuccessful) throw IOException("Phoenix fallback_api 请求失败（HTTP ${response.code}）")
                val body = response.body ?: throw IOException("Phoenix fallback_api 正文为空")
                if (body.contentLength() > 16L * 1024 * 1024) throw IOException("Phoenix fallback_api 响应过大")
                val bytes = body.bytes()
                if (bytes.size > 16 * 1024 * 1024) throw IOException("Phoenix fallback_api 响应过大")
                String(bytes, Charsets.UTF_8)
            }
        }
        var fallbackRaw = if (fallbackUrl.startsWith("https://") || fallbackUrl.startsWith("http://")) {
            fetchFallback(fallbackUrl)
        } else null
        // OOIUa tries codec_type=1 first, then the older force_codec=h264 override.
        if (fallbackUrl.startsWith("https://") && !fallbackRaw.isNullOrBlank() &&
            PhoenixMediaResolver.needsCodecOverride(fallbackRaw!!)) {
            for (codecQuery in listOf("codec_type=1", "codec_type=0&force_codec=h264")) {
                val compatibleUrl = compatibleFallback(fallbackUrl, codecQuery)
                val compatibleRaw = fetchFallback(compatibleUrl)
                fallbackRaw = compatibleRaw
                if (!PhoenixMediaResolver.needsCodecOverride(compatibleRaw)) break
            }
        }
        return PhoenixMediaResolver.parse(raw, maxQuality, fallbackRaw, ::emitDiagnostic)
    }

    private fun compatibleFallback(fallback: String, codecQuery: String): String {
        val url = fallback.trim()
        val queryAt = url.indexOf('?')
        val out = StringBuilder(if (queryAt < 0) url else url.substring(0, queryAt)).append('?')
        if (queryAt >= 0) {
            url.substring(queryAt + 1).split('&').forEach { part ->
                val name = part.substringBefore('=')
                if (part.isNotEmpty() && name != "codec_type" && name != "force_codec") {
                    out.append(part).append('&')
                }
            }
        }
        return out.append(codecQuery).toString()
    }

    companion object {
        private const val PLAYBACK_UA =
            "com.phoenix.read/71532 (Linux; U; Android 9; SM-N9860; Build/PQ3A.190705.10241111;tt-ok/3.12.13.20)"
        internal fun parseResponse(raw: String, maxQuality: Int = 1080): StreamInfo {
            val root = try { JSONObject(raw) } catch (_: Exception) {
                throw IOException("Phoenix 播放响应不是有效 JSON")
            }
            val baseResp = root.optJSONObject("BaseResp")
            val codeText = root.opt("code")?.toString()
                ?: root.opt("Code")?.toString()
                ?: baseResp?.opt("StatusCode")?.toString()
                ?: "0"
            val code = codeText.toIntOrNull() ?: -1
            if (code != 0) {
                val message = root.optString("message", baseResp?.optString("StatusMessage").orEmpty())
                throw IOException("Phoenix 播放接口业务错误：$code ${message.take(100)}")
            }
            val data = root.optJSONObject("data") ?: throw IOException("Phoenix 播放数据为空")
            val rawModel = data.opt("video_model")
            val model = when (rawModel) {
                is JSONObject -> rawModel
                is String -> try { JSONObject(rawModel) } catch (_: Exception) {
                    throw IOException("Phoenix video_model 格式异常")
                }
                else -> data
            }
            val dynamic = model.optJSONObject("dynamic_video")
            val variants = asObjects(model.opt("video_list"))
                .ifEmpty { asObjects(dynamic?.opt("dynamic_video_list")) }
                .ifEmpty { asObjects(data.opt("video_list")) }
            if (variants.isEmpty()) throw IOException("Phoenix 未返回视频清晰度")

            data class Candidate(val quality: Int, val codec: String, val url: String)
            val candidates = mutableListOf<Candidate>()
            for (item in variants) {
                val meta = item.optJSONObject("video_meta") ?: JSONObject()
                val encryption = item.optJSONObject("encrypt_info") ?: JSONObject()
                val codec = meta.optString("codec_type").ifBlank { item.optString("codec_type") }
                    .lowercase(Locale.ROOT)
                if (codec in setOf("bytevc2", "bvc2")) continue

                // CENC/spade_a 等受保护媒体不在本验证通道中解密或尝试绕过。
                val method = encryption.optString("encryption_method")
                    .ifBlank { item.optString("encryption_method") }
                val spade = encryption.optString("spade_a").ifBlank { item.optString("spade_a") }
                if (encryption.optBoolean("encrypt") || item.optBoolean("encrypt") ||
                    method.equals("cenc-aes-ctr", true) || spade.isNotBlank()) continue

                val definition = meta.optString("definition").ifBlank { item.optString("definition") }
                val height = qualityNumber(definition).takeIf { it > 0 }
                    ?: meta.optInt("vheight", item.optInt("vheight", 0))
                val urls = (
                    stringValues(item.opt("main_url")) +
                    stringValues(item.opt("backup_url")) +
                    stringValues(item.opt("backup_url_1")) +
                    stringValues(item.opt("backup_url_2")) +
                    stringValues(item.opt("backup_urls")) +
                    stringValues(item.opt("url_list"))
                ).distinct()
                for (rawUrl in urls) {
                    val url = normalizeMediaUrl(rawUrl)
                    if (!url.startsWith("https://") && !url.startsWith("http://")) continue
                    val path = runCatching { java.net.URI(url).path.orEmpty().lowercase(Locale.ROOT) }
                        .getOrDefault("")
                    if (path.endsWith(".m3u8") || path.endsWith(".mpd") ||
                        path.endsWith(".ts") || path.endsWith(".mkv") || path.endsWith(".webm")) continue
                    candidates += Candidate(height, codec, url)
                }
            }
            if (candidates.isEmpty()) {
                throw UnplayableVideoException("Phoenix 返回的媒体为空、受保护或当前播放链路不支持")
            }
            val h264 = candidates.filter { it.codec == "h264" || it.codec == "avc1" }
            val pool = if (h264.isNotEmpty()) h264 else candidates
            val selected = pool.filter { it.quality <= maxQuality && it.quality > 0 }
                .maxByOrNull { it.quality }
                ?: pool.minByOrNull { if (it.quality > 0) it.quality else Int.MAX_VALUE }
                ?: throw IOException("Phoenix 没有可选媒体")
            val quality = if (selected.quality > 0) "${selected.quality}P" else "自动"
            return StreamInfo(selected.url, null, quality, "video/mp4")
        }

        private fun normalizeMediaUrl(value: String): String {
            val trimmed = value.trim()
            if (trimmed.startsWith("https://") || trimmed.startsWith("http://")) return trimmed
            return runCatching { String(decode64(trimmed), Charsets.UTF_8).trim() }
                .getOrDefault("")
        }

        private fun asObjects(value: Any?): List<JSONObject> = when (value) {
            is JSONObject -> value.keys().asSequence().mapNotNull { value.optJSONObject(it) }.toList()
            is JSONArray -> (0 until value.length()).mapNotNull { value.optJSONObject(it) }
            else -> emptyList()
        }

        private fun stringValues(value: Any?): List<String> = when (value) {
            is String -> listOf(value)
            is JSONArray -> (0 until value.length()).mapNotNull {
                value.optString(it).takeIf(String::isNotBlank)
            }
            else -> emptyList()
        }

        private fun qualityNumber(value: String): Int =
            Regex("(\\d{3,4})").find(value)?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }
}
