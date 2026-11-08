// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import java.io.IOException
import java.io.InterruptedIOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import okhttp3.CookieJar
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class PhoenixPlaybackDeviceRegistrationException(cause: Throwable? = null) :
    IOException("播放设备注册失败，请稍后重试", cause)

/**
 * 按 Xanerix/guoapp 的匿名设备注册机制建立播放身份：
 * - 普通 JSON POST 到 log.snssdk.com/service/2/device_register/；
 * - 不使用 ttEncrypt、gzip、注册签名或旧设备 ID；
 * - 仅使用服务端返回的 device_id_str + install_id_str；
 * - 成功身份在进程内复用；播放空响应时 renew 重新注册；
 * - 注册被拒绝后暂停 10 分钟，避免连续请求注册接口。
 */
class PhoenixPlaybackDeviceSession(
    private val register: ((OkHttpClient) -> PhoenixDeviceRegister.Registered?)? = null
) {
    companion object {
        val shared = PhoenixPlaybackDeviceSession()
        private const val REGISTER_PAUSE_MS = 10 * 60 * 1000L
        private const val REGISTER_TIMEOUT_SECONDS = 20L
    }

    data class Profile(val values: Map<String, String>, val cookie: String)

    private var ready: Profile? = null
    private var registerPausedUntil = 0L
    private var registerPauseCause: IOException? = null

    @Synchronized
    fun profile(http: OkHttpClient): Profile {
        ready?.let { return it }
        if (Thread.currentThread().isInterrupted) throw InterruptedIOException("播放请求已取消")
        if (System.currentTimeMillis() < registerPausedUntil) {
            throw PhoenixPlaybackDeviceRegistrationException(registerPauseCause)
        }

        val registered = try {
            if (register == null) registerPhoenix(http) else register.invoke(http)
        } catch (error: InterruptedIOException) {
            throw error
        } catch (error: IOException) {
            pauseRegistration(error)
            throw PhoenixPlaybackDeviceRegistrationException(error)
        } ?: run {
            val error = IOException("红果设备注册未返回有效 device_id/install_id")
            pauseRegistration(error)
            throw PhoenixPlaybackDeviceRegistrationException(error)
        }

        val validId = Regex("[1-9][0-9]{0,29}")
        if (!validId.matches(registered.deviceId) || !validId.matches(registered.installId)) {
            val error = IOException("设备注册响应缺少有效 device_id/install_id")
            pauseRegistration(error)
            throw PhoenixPlaybackDeviceRegistrationException(error)
        }
        if (Thread.currentThread().isInterrupted) throw InterruptedIOException("播放请求已取消")

        return Profile(
            VendorConstants.device + mapOf(
                "device_id" to registered.deviceId,
                "iid" to registered.installId,
                "cdid" to UUID.randomUUID().toString(),
            ),
            "",
        ).also {
            ready = it
            registerPausedUntil = 0L
            registerPauseCause = null
        }
    }

    /** 丢弃进程内匿名身份并重新注册；播放空响应时调用。 */
    @Synchronized
    fun renew(http: OkHttpClient): Profile {
        ready = null
        registerPausedUntil = 0L
        registerPauseCause = null
        return profile(http)
    }

    private fun pauseRegistration(error: IOException) {
        registerPausedUntil = System.currentTimeMillis() + REGISTER_PAUSE_MS
        registerPauseCause = error
    }

    private fun registerPhoenix(http: OkHttpClient): PhoenixDeviceRegister.Registered {
        val now = System.currentTimeMillis()
        val body = PhoenixDeviceRegister.payload(now)
        val request = Request.Builder()
            .url(PhoenixDeviceRegister.url(now))
            .apply { PhoenixDeviceRegister.headers().forEach { (key, value) -> header(key, value) } }
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        val transport = http.newBuilder()
            .cookieJar(CookieJar.NO_COOKIES)
            .callTimeout(REGISTER_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()

        return RequestScope.execute(transport.newCall(request)) { response ->
            if (!response.isSuccessful) {
                throw IOException("红果设备注册 HTTP ${response.code}")
            }
            val responseBody = response.body ?: throw IOException("红果设备注册响应为空")
            if (responseBody.contentLength() > 64L * 1024) {
                throw IOException("红果设备注册响应过大")
            }
            val bytes = responseBody.bytes()
            if (bytes.size > 64 * 1024) throw IOException("红果设备注册响应过大")
            val raw = String(bytes, Charsets.UTF_8)
            if (raw.isBlank()) throw IOException("红果设备注册返回空响应")
            PhoenixDeviceRegister.parse(raw)
                ?: throw IOException("红果设备注册未返回有效设备号")
        }
    }
}
