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
        emitDiagnostic("Phoenix：开始获取设备注册身份")
        var identity = try {
            session.profile(http)
        } catch (error: java.io.InterruptedIOException) {
            throw error
        } catch (error: IOException) {
            emitDiagnostic("Phoenix：设备注册/会话失败，异常类型=" + error.javaClass.simpleName)
            throw error
        }
        emitDiagnostic("Phoenix：设备身份已就绪（身份字段已校验，敏感值不记录）")
        val payload = JSONObject()
            .put("video_id", id)
            .put("content_type", 1)
            .put("biz_param", JSONObject()
                .put("need_all_video_definition", true)
                .put("video_platform", 3))
            .toString()
            .toByteArray(Charsets.UTF_8)

        val hosts = listOf(
            "api5-normal-sinfonlineb.fqnovel.com",
            "api5-normal-sinfonlinea.fqnovel.com"
        )
        var last: IOException? = null
        var rotations = 0
        for (host in hosts) {
            while (true) {
                RequestScope.ensureActive()
                try {
                    // 使用注册会话下发的设备 ID，并与上游 7.3.5.32 播放请求参数保持一致。
                    val values = linkedMapOf(
                        "aid" to "8662",
                        "app_name" to "novelread",
                        "version_code" to "73532",
                        "version_name" to "7.3.5.32",
                        "manifest_version_code" to "73532",
                        "update_version_code" to "73532",
                        "channel" to "update_64",
                        "device_platform" to "android",
                        "os" to "android",
                        "ssmix" to "a",
                        "device_type" to "25053RT47C",
                        "device_brand" to "Redmi",
                        "language" to "zh",
                        "os_api" to "36",
                        "os_version" to "16",
                        "resolution" to "1280*2772",
                        "dpi" to "520",
                        "ac" to (identity.values["ac"]?.ifBlank { "wifi" } ?: "wifi"),
                        "device_id" to identity.values["device_id"].orEmpty(),
                        "iid" to identity.values["iid"].orEmpty()
                    )
                    if (values["device_id"].isNullOrBlank() || values["iid"].isNullOrBlank()) {
                        throw PhoenixPlaybackDeviceRegistrationException(IOException("设备注册身份不完整"))
                    }
                    val now = System.currentTimeMillis()
                    val query = encodeQuery(values + mapOf("_rticket" to now.toString()))
                    emitDiagnostic("Phoenix：正在生成上游播放器请求签名，host=" + host)
                    val headers = PhoenixPlayerSigner.headers(
                        query, payload, values["device_id"].orEmpty(), now, PHOENIX_APP_UA
                    )
                    emitDiagnostic("Phoenix：播放器签名完成，开始发送 video_model/v1 POST")
                    val request = Request.Builder()
                        .url("https://$host/novel/player/video_model/v1/?$query")
                        .apply {
                            headers.forEach { (key, value) -> header(key, value) }
                            if (identity.cookie.isNotBlank()) header("Cookie", identity.cookie)
                        }
                        .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
                        .build()
                    data class ResponseBody(val raw: String, val status: Int, val contentType: String, val size: Int)
                    val result = RequestScope.execute(http.newCall(request)) { response ->
                        val body = response.body
                        val bytes = body?.bytes() ?: ByteArray(0)
                        if (bytes.size > 16 * 1024 * 1024) throw IOException("Phoenix 播放响应过大")
                        ResponseBody(
                            String(bytes, Charsets.UTF_8),
                            response.code,
                            response.header("Content-Type").orEmpty(),
                            bytes.size
                        )
                    }
                    emitDiagnostic("Phoenix：播放接口响应 HTTP " + result.status + "，Content-Type=" + result.contentType.take(60) + "，正文=" + result.size + " bytes")
                    if ((result.size == 0 || result.status == 403) && rotations < 3) {
                        rotations++
                        emitDiagnostic("Phoenix：响应为空或 403，执行第 " + rotations + " 次设备会话轮换")
                        identity = session.renew(http)
                        emitDiagnostic("Phoenix：设备会话轮换完成")
                        continue
                    }
                    if (result.status !in 200..299) {
                        throw IOException("Phoenix 播放接口 HTTP ${result.status}，Content-Type=${result.contentType.take(80)}，正文长度=${result.size}")
                    }
                    if (result.raw.isBlank()) {
                        throw IOException("Phoenix 播放接口返回空正文（HTTP ${result.status}，Content-Type=${result.contentType.take(80)}）")
                    }
                    return try {
                        resolveResponse(result.raw, maxQuality, identity.cookie).also { emitDiagnostic("Phoenix：播放模型解析成功，清晰度=" + it.quality + "，MIME=" + it.mimeType) }
                    } catch (error: IOException) {
                        val preview = result.raw
                            .replace(Regex("[\\r\\n\\t]+"), " ")
                            .take(180)
                        throw IOException(
                            "${error.message}；HTTP=${result.status}，Content-Type=${result.contentType.take(80)}，正文长度=${result.size}，响应摘要=${preview}",
                            error
                        )
                    }
                } catch (error: java.io.InterruptedIOException) {
                    throw error
                } catch (error: IOException) {
                    last = error
                    emitDiagnostic("Phoenix：host=" + host + " 请求失败，异常类型=" + error.javaClass.simpleName)
                    break
                }
            }
        }
        throw last ?: IOException("Phoenix 播放接口暂不可用")
    }

    /**
     * Resolve the optional second-stage fallback_api endpoint before parsing media.
     * This is a plain GET with the reader UA; credentials and signed query data are not logged.
     */
    private fun resolveResponse(raw: String, maxQuality: Int, cookie: String): StreamInfo {
        val primaryError = try {
            return PhoenixMediaResolver.parse(raw, maxQuality)
        } catch (error: IOException) {
            error
        }
        val fallbackUrl = runCatching { PhoenixMediaResolver.fallbackUrl(raw) }.getOrDefault("")
        if (fallbackUrl.isBlank()) throw primaryError
        if (!fallbackUrl.startsWith("https://") && !fallbackUrl.startsWith("http://")) {
            throw primaryError
        }
        val fallbackRaw = try {
            val request = Request.Builder()
                .url(fallbackUrl)
                .header("User-Agent", PHOENIX_APP_UA)
                .header("Referer", "https://novel.snssdk.com/")
                .apply { if (cookie.isNotBlank()) header("Cookie", cookie) }
                .header("Accept", "application/json, */*")
                .header("Accept-Encoding", "identity")
                .get()
                .build()
            RequestScope.execute(http.newCall(request)) { response ->
                if (!response.isSuccessful) throw IOException("Phoenix fallback_api 请求失败（HTTP ${response.code}）")
                val body = response.body ?: throw IOException("Phoenix fallback_api 正文为空")
                if (body.contentLength() > 16L * 1024 * 1024) throw IOException("Phoenix fallback_api 响应过大")
                val bytes = body.bytes()
                if (bytes.size > 16 * 1024 * 1024) throw IOException("Phoenix fallback_api 响应过大")
                String(bytes, Charsets.UTF_8)
            }
        } catch (error: java.io.InterruptedIOException) {
            throw error
        } catch (error: IOException) {
            primaryError.addSuppressed(error)
            throw primaryError
        }
        return try {
            PhoenixMediaResolver.parse(raw, maxQuality, fallbackRaw)
        } catch (error: IOException) {
            error.addSuppressed(primaryError)
            throw error
        }
    }

    companion object {
        /** Go url.Values.Encode() compatibility: sorted keys and QueryEscape values. */
        private fun encodeQuery(values: Map<String, String>): String =
            values.toSortedMap().entries.joinToString("&") { (key, value) ->
                fun escape(part: String) = java.net.URLEncoder.encode(part, "UTF-8")
                    .replace("*", "%2A")
                    .replace("%7E", "~")
                "${escape(key)}=${escape(value)}"
            }

        private const val PHOENIX_APP_UA =
            "com.phoenix.read/73532 (Linux; U; Android 16; zh_CN; 25053RT47C; Build/BP2A.250605.031.A3; Cronet/TTNetVersion:04657795 2026-01-23 QuicVersion:c67e9834 2025-09-08)"

        internal fun parseResponse(raw: String, maxQuality: Int = 1080): StreamInfo =
            PhoenixMediaResolver.parse(raw, maxQuality)

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
