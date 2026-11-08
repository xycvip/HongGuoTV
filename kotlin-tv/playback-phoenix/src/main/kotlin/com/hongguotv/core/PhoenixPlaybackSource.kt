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
 * 仅处理公开、未加密的 MP4 媒体；受保护媒体不会在此模块解密。
 * 同一清晰度的 CDN 备用地址由 RemoteVideo 在读取失败时依次尝试。
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
    private val signer: PhoenixRequestSigner,
    private val onDiagnostic: (String) -> Unit = {},
) : StreamSource {
    override val name: String = "phoenix-video-model"

    override fun stream(id: String, maxQuality: Int): StreamInfo = resolve(id, maxQuality)

    /** Diagnostics must never be allowed to change playback behavior. */
    private fun emitDiagnostic(message: String) {
        runCatching { onDiagnostic(message) }
    }

    /**
     * 独立诊断：先获取 Phoenix 播放地址，再用 Range GET 读取媒体头，
     * 验证 CDN 是否实际返回媒体数据。不会记录完整 URL、Cookie 或签名参数。
     */
    fun diagnose(id: String, maxQuality: Int = 1080): PhoenixPlaybackDiagnostic {
        val info = resolve(id, maxQuality)
        val host = runCatching { java.net.URI(info.url).host.orEmpty() }.getOrDefault("")
        val started = System.nanoTime()
        val remote = RemoteVideo(http, info)
        return try {
            // 直接走播放器相同的 MP4 头解析和媒体读取，不再只测 CDN 是否回 1 KiB。
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
                info.quality, host, remote.lastHttpStatus, remote.lastContentType,
                remote.lastBytesRead.takeIf { it > 0 } ?: firstBytes.size, elapsed, detail,
                keyPresent, sampleCount, headerValid
            )
        } catch (error: java.io.InterruptedIOException) {
            throw error
        } catch (error: Exception) {
            if (error is java.lang.InterruptedException || Thread.currentThread().isInterrupted) {
                throw java.io.InterruptedIOException("播放诊断已取消").apply { initCause(error) }
            }
            val elapsed = (System.nanoTime() - started) / 1_000_000
            PhoenixPlaybackDiagnostic(
                info.quality, host, remote.lastHttpStatus, remote.lastContentType,
                remote.lastBytesRead, elapsed,
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
            .put("biz_param", JSONObject()
                .put("detail_page_version", 0)
                .put("device_level", 3)
                .put("disable_digg_stat", false)
                .put("need_all_video_definition", true)
                .put("need_mp4_align", false)
                .put("use_os_player", false)
                .put("use_server_dns", false)
                .put("video_platform", 1024))
            .toString()
            .toByteArray(Charsets.UTF_8)

        val hosts = listOf(
            "api5-normal-sinfonlinea.fqnovel.com",
            "api5-normal-sinfonlineb.fqnovel.com"
        )
        var last: IOException? = null
        var rotations = 0
        for (host in hosts) {
            while (true) {
                RequestScope.ensureActive()
                try {
                    // 使用当前注册会话的设备身份，版本及设备指纹统一取近期实测的 7.1.3.32 配置。
                    val values = VendorConstants.device.toMutableMap().apply {
                        putAll(identity.values.filterKeys { it in setOf("device_id", "iid", "cdid", "ac") })
                        put("device_id", identity.values["device_id"].orEmpty())
                        put("iid", identity.values["iid"].orEmpty())
                        put("cdid", identity.values["cdid"].orEmpty())
                        put("ac", identity.values["ac"]?.ifBlank { "wifi" } ?: "wifi")
                    }
                    if (values["device_id"].isNullOrBlank() || values["iid"].isNullOrBlank()) {
                        throw PhoenixPlaybackDeviceRegistrationException(IOException("设备注册身份不完整"))
                    }
                    emitDiagnostic("Phoenix：正在生成播放请求签名，host=" + host)
                    val signed = signer.signPlayback(
                        "https://$host/novel/player/video_model/v1/",
                        values,
                        payload,
                        VendorConstants.VIDEO_UA
                    )
                    emitDiagnostic("Phoenix：请求签名完成，开始发送 video_model/v1 POST")
                    val request = Request.Builder()
                        .url(signed.url)
                        .apply {
                            signed.headers.forEach { (key, value) -> header(key, value) }
                            if (identity.cookie.isNotBlank()) header("Cookie", identity.cookie)
                        }
                        .post(payload.toRequestBody("application/json; charset=UTF-8".toMediaType()))
                        .build()
                    data class ResponseBody(val raw: String, val status: Int, val contentType: String, val size: Int)
                    val result = RequestScope.execute(http.newCall(request)) { response ->
                        val body = response.body
                        val bytes = body?.let { readBounded(it, 16L * 1024 * 1024) } ?: ByteArray(0)
                        ResponseBody(
                            String(bytes, Charsets.UTF_8),
                            response.code,
                            response.header("Content-Type").orEmpty(),
                            bytes.size
                        )
                    }
                    emitDiagnostic("Phoenix：播放接口响应 HTTP " + result.status + "，Content-Type=" + result.contentType.take(60) + "，正文=" + result.size + " bytes")
                    if ((result.size == 0 || result.status == 403) && rotations < 2) {
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
                        throw IOException(
                            "${error.message}；HTTP=${result.status}，Content-Type=${result.contentType.take(80)}，正文长度=${result.size}",
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

        val fallbackUrl = try {
            PhoenixMediaResolver.fallbackUrl(raw)
        } catch (_: IOException) {
            ""
        }
        if (fallbackUrl.isBlank()) throw primaryError
        if (!isSafeFallbackUrl(fallbackUrl)) {
            primaryError.addSuppressed(IOException("Phoenix fallback_api 地址不符合安全访问规则"))
            throw primaryError
        }

        val fallbackRaw = try {
            val request = Request.Builder()
                .url(fallbackUrl)
                .header("User-Agent", PHOENIX_UA)
                .header("Referer", "https://novel.snssdk.com/")
                .header("Accept", "application/json, */*")
                .header("Accept-Encoding", "identity")
                .apply { if (cookie.isNotBlank()) header("Cookie", cookie) }
                .get()
                .build()
            RequestScope.execute(http.newCall(request)) { response ->
                if (!response.isSuccessful) {
                    throw IOException("Phoenix fallback_api 请求失败（HTTP ${response.code}）")
                }
                val body = response.body ?: throw IOException("Phoenix fallback_api 正文为空")
                String(readBounded(body, 16L * 1024 * 1024), Charsets.UTF_8)
            }
        } catch (error: java.io.InterruptedIOException) {
            throw error
        } catch (error: IOException) {
            primaryError.addSuppressed(error)
            throw primaryError
        }

        return try {
            PhoenixMediaResolver.parse(raw, maxQuality, fallbackRaw)
        } catch (fallbackParseError: IOException) {
            fallbackParseError.addSuppressed(primaryError)
            throw fallbackParseError
        }
    }

    companion object {
        private const val PHOENIX_UA =
            "com.phoenix.read/73532 (Linux; U; Android 16; zh_CN; 25053RT47C; Build/BP2A.250605.031.A3; Cronet/TTNetVersion:04657795 2026-01-23 QuicVersion:c67e9834 2025-09-08)"

        internal fun parseResponse(raw: String, maxQuality: Int = 1080): StreamInfo =
            PhoenixMediaResolver.parse(raw, maxQuality)

        private fun readBounded(body: okhttp3.ResponseBody, limit: Long): ByteArray {
            if (body.contentLength() > limit) throw IOException("Phoenix 响应过大")
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var total = 0L
            body.byteStream().use { input ->
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > limit) throw IOException("Phoenix 响应过大")
                    output.write(buffer, 0, count)
                }
            }
            return output.toByteArray()
        }

        private fun isSafeFallbackUrl(raw: String): Boolean {
            val uri = runCatching { java.net.URI(raw) }.getOrNull() ?: return false
            if (!uri.scheme.equals("https", true) || uri.userInfo != null) return false
            if (uri.port != -1 && uri.port != 443) return false
            val host = uri.host?.lowercase(Locale.ROOT)?.trimEnd('.') ?: return false
            if (host.isBlank() || host == "localhost" || host.endsWith(".localhost") ||
                host.endsWith(".local") || host.endsWith(".internal")) return false

            // Reject literal private, loopback, link-local and multicast IPs without
            // resolving arbitrary hostnames or introducing DNS rebinding behavior.
            if (host.matches(Regex("[0-9.]+"))) {
                val parts = host.split(".").mapNotNull { it.toIntOrNull() }
                if (parts.size != 4 || parts.any { it !in 0..255 }) return false
                val a = parts[0]; val b = parts[1]
                if (a == 0 || a == 10 || a == 127 || a >= 224 ||
                    (a == 169 && b == 254) || (a == 172 && b in 16..31) ||
                    (a == 192 && b == 168) || (a == 100 && b in 64..127)) return false
            } else if (host.contains(':')) {
                val address = runCatching { java.net.InetAddress.getByName(host) }.getOrNull() ?: return false
                if (address.isAnyLocalAddress || address.isLoopbackAddress ||
                    address.isLinkLocalAddress || address.isSiteLocalAddress ||
                    address.isMulticastAddress) return false
            }
            return true
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
