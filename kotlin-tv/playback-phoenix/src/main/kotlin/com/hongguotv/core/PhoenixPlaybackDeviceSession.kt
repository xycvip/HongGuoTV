// SPDX-License-Identifier: GPL-3.0-only
// Ported from OOIUa/hongguo-android PlaybackDevice identity lifecycle.
package com.hongguotv.core

import java.io.IOException
import java.io.InterruptedIOException
import okhttp3.OkHttpClient

class PhoenixPlaybackDeviceRegistrationException(cause: Throwable? = null) :
    IOException("播放设备注册失败，请稍后重试", cause)

/** A fresh anonymous OOIUa-compatible playback identity for each playback resolution. */
class PhoenixPlaybackDeviceSession(
    private val register: ((OkHttpClient) -> PhoenixDeviceRegister.Registered?)? = null
) {
    companion object {
        val shared = PhoenixPlaybackDeviceSession()
    }

    data class Profile(val values: Map<String, String>, val cookie: String)

    @Synchronized
    fun profile(http: OkHttpClient): Profile {
        if (Thread.currentThread().isInterrupted) throw InterruptedIOException("播放请求已取消")
        val registered = try {
            (register?.invoke(http) ?: if (register == null) PhoenixDeviceRegister.register(http) else null)
                ?: throw PhoenixPlaybackDeviceRegistrationException()
        } catch (error: InterruptedIOException) {
            throw error
        } catch (error: IOException) {
            throw PhoenixPlaybackDeviceRegistrationException(error)
        }
        val validId = Regex("[1-9][0-9]{0,29}")
        if (!validId.matches(registered.deviceId) || !validId.matches(registered.installId)) {
            throw PhoenixPlaybackDeviceRegistrationException(IOException("设备注册响应缺少有效 device_id/install_id"))
        }
        if (Thread.currentThread().isInterrupted) throw InterruptedIOException("播放请求已取消")
        return Profile(
            mapOf(
                "device_id" to registered.deviceId,
                "iid" to registered.installId,
                "cdid" to registered.cdid
            ),
            registered.cookie
        )
    }

    /** OOIUa retries with a newly registered identity after an empty or rejected response. */
    fun renew(http: OkHttpClient): Profile = profile(http)
}
