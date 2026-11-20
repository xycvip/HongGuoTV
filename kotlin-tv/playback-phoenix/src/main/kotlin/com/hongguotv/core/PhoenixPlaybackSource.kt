// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
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
        emitDiagnostic("Phoenix：按 RedGuoAPI main.js 注册匿名播放设备")
        val playbackHttp = http.newBuilder()
            .callTimeout(8, TimeUnit.SECONDS)
            .followRedirects(false)
            .build()
        val started = System.currentTimeMillis()
        var identity = session.profile(http)
        var lastFailure: IOException? = null

        // 与 RedGuoAPI main.js 的 appMedia 顺序一致：先 GET video_tab，再 POST video_model。
        for (identityAttempt in 0..1) {
            if (identityAttempt > 0) {
                RequestScope.ensureActive()
                identity = session.renew(http)
                emitDiagnostic("Phoenix：主设备线路未取得播放模型，已更换注册身份")
            }
            for (tabRoute in listOf(true, false)) {
                if (System.currentTimeMillis() - started >= 20_000L) break
                RequestScope.ensureActive()
                try {
                    val raw = if (tabRoute) {
                        requestVideoTab(id, identity, playbackHttp)
                    } else {
                        requestVideoModel(id, identity, playbackHttp)
                    }
                    val normalized = normalizePlaybackResponse(raw, id, tabRoute)
                        ?: throw IOException("RedGuoAPI 播放接口未返回本集 video_model")
                    return try {
                        resolveResponse(normalized, maxQuality).also {
                            emitDiagnostic(
                                "Phoenix：RedGuoAPI ${if (tabRoute) "video_tab GET" else "video_model POST"} 命中，清晰度=${it.quality}"
                            )
                        }
                    } catch (error: UnplayableVideoException) {
                        throw error
                    } catch (error: UnsupportedVideoFormatException) {
                        throw error
                    } catch (error: java.io.InterruptedIOException) {
                        throw error
                    } catch (error: IOException) {
                        lastFailure = error
                        emitDiagnostic("Phoenix：播放模型解析失败，继续 RedGuoAPI 备用线路")
                    }
                } catch (error: UnplayableVideoException) {
                    throw error
                } catch (error: UnsupportedVideoFormatException) {
                    throw error
                } catch (error: java.io.InterruptedIOException) {
                    throw error
                } catch (error: IOException) {
                    lastFailure = error
                    emitDiagnostic("Phoenix：RedGuoAPI ${if (tabRoute) "video_tab GET" else "video_model POST"} 请求失败")
                }
            }
        }
        throw lastFailure ?: IOException("Phoenix 播放接口暂不可用")
    }

    private fun requestVideoTab(
        id: String,
        identity: PhoenixPlaybackDeviceSession.Profile,
        playbackHttp: OkHttpClient
    ): String {
        val values = java.util.TreeMap<String, String>().apply {
            put("aid", "8662")
            put("app_name", "novelread")
            put("version_code", "73532")
            put("version_name", "7.3.5.32")
            put("manifest_version_code", "73532")
            put("update_version_code", "73532")
            put("channel", "update_64")
            put("device_platform", "android")
            put("os", "android")
            put("ssmix", "a")
            put("device_type", "25053RT47C")
            put("device_brand", "Redmi")
            put("language", "zh")
            put("os_api", "36")
            put("os_version", "16")
            put("resolution", "1280*2772")
            put("dpi", "520")
            put("ac", "wifi")
            put("device_id", identity.values["device_id"].orEmpty())
            put("iid", identity.values["iid"].orEmpty())
            put("video_id", id)
            put("video_id_type", "0")
            put("device_level", "1")
            put("need_all_video_definition", "1")
            put("need_mp4_align", "0")
            put("use_os_player", "0")
            put("video_platform", "1024")
            put("source", "0")
            put("from_video_id", "")
            // Insert the ticket placeholder first in the sorted map; Signer replaces it in place.
            put("_rticket", "0")
        }
        return signedPlaybackRequest(
            "https://api5-normal-sinfonlineb.fqnovel.com/reading/bookapi/video_tab/multi_video_model/v1/",
            values,
            null,
            playbackHttp
        )
    }

    private fun requestVideoModel(
        id: String,
        identity: PhoenixPlaybackDeviceSession.Profile,
        playbackHttp: OkHttpClient
    ): String {
        val values = java.util.TreeMap<String, String>().apply {
            put("aid", "8662")
            put("app_name", "novelread")
            put("version_code", "74332")
            put("version_name", "7.4.3.32")
            put("channel", "vivo_8662_64")
            put("device_platform", "android")
            put("device_type", "PGT-AN10")
            put("os_version", "12")
            put("update_version_code", "74332")
            put("iid", identity.values["iid"].orEmpty())
            put("cdid", java.util.UUID.randomUUID().toString())
            put("device_id", identity.values["device_id"].orEmpty())
            put("_rticket", "0")
        }
        val body = JSONObject()
            .put("biz_param", JSONObject()
                .put("detail_page_version", 0)
                .put("device_level", 1)
                .put("disable_digg_stat", false)
                .put("disable_video_relate_book", false)
                .put("need_all_video_definition", true)
                .put("need_mp4_align", false)
                .put("use_os_player", false)
                .put("use_server_dns", false)
                .put("video_platform", 1024))
            .put("mixed_video_id_map", JSONObject().put("1004", JSONArray().put(id)))
            .put("dr_scene", "player")
            .toString()
            .toByteArray(Charsets.UTF_8)
        return signedPlaybackRequest(
            "https://api5-normal-sinfonlinea.fqnovel.com/novel/player/multi_video_model/player/v1",
            values,
            body,
            playbackHttp
        )
    }

    private fun signedPlaybackRequest(
        base: String,
        values: Map<String, String>,
        body: ByteArray?,
        playbackHttp: OkHttpClient
    ): String {
        if (values["device_id"].isNullOrBlank() || values["iid"].isNullOrBlank()) {
            throw PhoenixPlaybackDeviceRegistrationException(IOException("设备注册身份不完整"))
        }
        val now = System.currentTimeMillis()
        val signed = V5_SIGNER.signPlayback(base, values, body, now)
        val builder = Request.Builder()
            .url(signed.url)
            .header("User-Agent", PLAYBACK_UA)
            .header("Accept", "application/json")
        signed.headers.forEach { (key, value) -> builder.header(key, value) }
        val request = if (body == null) {
            builder.get().build()
        } else {
            builder.post(body.toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        }
        return RequestScope.execute(playbackHttp.newCall(request)) { response ->
            if (response.code != 200) throw IOException("RedGuoAPI 播放接口 HTTP ${response.code}")
            val responseBody = response.body ?: throw IOException("RedGuoAPI 播放接口响应为空")
            if (responseBody.contentLength() > 20L * 1024 * 1024) throw IOException("播放响应过大")
            val bytes = responseBody.bytes()
            if (bytes.size > 20 * 1024 * 1024) throw IOException("播放响应过大")
            val raw = String(bytes, Charsets.UTF_8)
            if (raw.isBlank()) throw IOException("RedGuoAPI 播放接口返回空响应")
            requireAppSuccess(raw)
            raw
        }
    }

    private fun requireAppSuccess(raw: String) {
        val root = runCatching { JSONObject(raw) }
            .getOrElse { throw IOException("RedGuoAPI 播放响应不是有效 JSON", it) }
        val baseResp = root.optJSONObject("BaseResp")
        val code = root.opt("code") ?: root.opt("Code") ?: root.opt("status_code") ?: baseResp?.opt("StatusCode")
        if (code != null && code.toString().isNotBlank() && code.toString() != "0") {
            throw IOException("RedGuoAPI 播放接口业务状态异常：${code.toString().take(20)}")
        }
    }

    private fun normalizePlaybackResponse(raw: String, id: String, tabRoute: Boolean): String? {
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val data = root.optJSONObject("data") ?: return null
        val entry = if (tabRoute) {
            data.optJSONObject("video_model_data")?.optJSONObject(id)
        } else {
            val grouped = data.optJSONObject("1004") ?: data
            grouped.optJSONObject(id)
        } ?: return null

        var model = entry.optJSONObject("video_model")
        if (model == null) {
            val encoded = entry.optString("video_model").trim()
            if (encoded.isNotEmpty()) {
                model = runCatching { JSONObject(encoded) }.getOrNull()
                if (model == null) {
                    model = runCatching {
                        val bytes = try {
                            java.util.Base64.getDecoder().decode(encoded)
                        } catch (_: IllegalArgumentException) {
                            java.util.Base64.getUrlDecoder().decode(encoded)
                        }
                        JSONObject(String(bytes, Charsets.UTF_8))
                    }.getOrNull()
                }
            }
        }
        if ((model == null || model.length() == 0) && entry.has("video_list")) {
            val list = entry.opt("video_list")
            val hasRows = when (list) {
                is JSONObject -> list.length() > 0
                is JSONArray -> list.length() > 0
                else -> false
            }
            if (hasRows) {
                model = JSONObject()
                    .put("video_list", list)
                    .put("video_duration", entry.opt("video_duration"))
                    .put("duration", entry.opt("duration"))
            }
        }
        if (model == null || model.length() == 0) return null
        return JSONObject()
            .put("code", 0)
            .put("data", JSONObject().put("video_model", model))
            .toString()
    }

    /**
     * Resolve the optional second-stage fallback_api endpoint before parsing media.
     * This is a plain GET with the reader UA; credentials and signed query data are not logged.
     */
    private fun resolveResponse(raw: String, maxQuality: Int): StreamInfo {
        val fallbackUrl = PhoenixMediaResolver.fallbackUrl(raw)
        fun fetchFallback(url: String): String {
            val fallbackHttp = http.newBuilder().callTimeout(15, TimeUnit.SECONDS).followRedirects(false).build()
            val parsed = runCatching { java.net.URI(url) }.getOrNull()
                ?: throw IOException("fallback_api URL 格式异常")
            val scheme = parsed.scheme?.lowercase(java.util.Locale.ROOT)
            val host = parsed.host.orEmpty().lowercase(java.util.Locale.ROOT)
            val allowedHost = listOf("fqnovel.com", "snssdk.com").any { host == it || host.endsWith("." + it) }
            if (scheme != "https" || parsed.userInfo != null ||
                (parsed.port != -1 && parsed.port != 443) || !allowedHost) {
                throw IOException("fallback_api URL 不符合 OOIUa API_DOMAINS 校验规则")
            }
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", VendorConstants.VIDEO_UA)
                .get()
                .build()
            return RequestScope.execute(fallbackHttp.newCall(request)) { response ->
                if (response.code != 200) throw IOException("Phoenix fallback_api 请求失败（HTTP ${response.code}）")
                val body = response.body ?: throw IOException("Phoenix fallback_api 正文为空")
                if (body.contentLength() > 16L * 1024 * 1024) throw IOException("Phoenix fallback_api 响应过大")
                val bytes = body.bytes()
                if (bytes.size > 16 * 1024 * 1024) throw IOException("Phoenix fallback_api 响应过大")
                String(bytes, Charsets.UTF_8)
            }
        }
        val shouldFetchFallback = PhoenixMediaResolver.needsFallback(raw, maxQuality)
        var fallbackRaw = if (shouldFetchFallback &&
            (fallbackUrl.startsWith("https://") || fallbackUrl.startsWith("http://"))) {
            try {
                fetchFallback(fallbackUrl)
            } catch (error: java.io.InterruptedIOException) {
                throw error
            } catch (error: IOException) {
                emitDiagnostic("Phoenix：fallback_api 请求失败，按 OOIUa 规则继续检查内联片源")
                null
            }
        } else null
        // OOIUa tries codec_type=1 first, then the older force_codec=h264 override.
        if (shouldFetchFallback && fallbackUrl.startsWith("https://") && !fallbackRaw.isNullOrBlank() &&
            PhoenixMediaResolver.needsCodecOverride(fallbackRaw!!)) {
            for (codecQuery in listOf("codec_type=1", "codec_type=0&force_codec=h264")) {
                try {
                    val compatibleUrl = compatibleFallback(fallbackUrl, codecQuery)
                    val compatibleRaw = fetchFallback(compatibleUrl)
                    fallbackRaw = compatibleRaw
                    if (!PhoenixMediaResolver.needsCodecOverride(compatibleRaw)) break
                } catch (error: java.io.InterruptedIOException) {
                    throw error
                } catch (error: IOException) {
                    emitDiagnostic("Phoenix：兼容编码备用请求失败，继续下一种编码参数")
                }
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
            "com.phoenix.read/73932 (Linux; U; Android 12; zh_CN_#Hans; PGT-AN10; Build/V417IR;tt-ok/3.12.13.20)"
        private val V5_SIGNER = Signer(mobileV5 = true, mobileV5QueryDeviceBrand = true)
    }
}
