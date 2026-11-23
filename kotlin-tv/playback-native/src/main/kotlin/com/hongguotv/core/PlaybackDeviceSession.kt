// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import java.io.IOException
import java.io.InterruptedIOException
import okhttp3.CookieJar
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

interface PlaybackDeviceProfileStore {
    fun load(): String?
    fun save(value: String?)
}

class PlaybackDeviceRegistrationException(cause: Throwable? = null) :
    IOException("播放设备注册失败，请稍后重试", cause)

/** One registered anonymous identity shared by the Native N content and playback requests. */
class PlaybackDeviceSession(
    private val register: ((OkHttpClient) -> ThlDeviceRegister.Registered?)? = null
) {
    companion object {
        val shared = PlaybackDeviceSession()
    }

    data class Profile(val values: Map<String, String>, val cookie: String)

    private var cdid = ThlDeviceRegister.newCdid()
    private var openudid = ThlDeviceRegister.newOpenudid()
    private var ready: Profile? = null
    private var store: PlaybackDeviceProfileStore? = null
    private var restoreAttempted = false

    /** Restore the same anonymous THL device identity across app launches, as upstream does. */
    @Synchronized
    fun configureStore(value: PlaybackDeviceProfileStore?) {
        store = value
        if (value == null) return
        ready?.let { persist(it); return }
        if (restoreAttempted) return
        restoreAttempted = true
        val saved = runCatching { value.load()?.let(::JSONObject) }.getOrNull() ?: return
        val savedCdid = saved.optString("cdid")
        val savedOpenudid = saved.optString("openudid")
        val savedDeviceId = saved.optString("device_id")
        val savedInstallId = saved.optString("iid")
        val validId = Regex("[1-9][0-9]{9,23}")
        if (savedCdid.isBlank() || savedOpenudid.isBlank() ||
            !validId.matches(savedDeviceId) || !validId.matches(savedInstallId)
        ) {
            value.save(null)
            return
        }
        cdid = savedCdid
        openudid = savedOpenudid
        ready = Profile(
            ThlDeviceRegister.profile(cdid, openudid) + mapOf("device_id" to savedDeviceId, "iid" to savedInstallId),
            saved.optString("cookie"),
        )
    }

    private fun persist(profile: Profile) {
        val values = profile.values
        store?.save(JSONObject()
            .put("cdid", values["cdid"])
            .put("openudid", values["openudid"])
            .put("device_id", values["device_id"])
            .put("iid", values["iid"])
            .put("cookie", profile.cookie)
            .toString())
    }

    private fun registerDevice(http: OkHttpClient): ThlDeviceRegister.Registered? {
        register?.let { return it(http) }
        val body = ThlDeviceRegister.payload(cdid, openudid)
        val request = Request.Builder().url(ThlDeviceRegister.url())
        ThlDeviceRegister.headers().forEach { (key, value) -> request.header(key, value) }
        request.post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
        val transport = http.newBuilder()
            .cookieJar(CookieJar.NO_COOKIES)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
        return RequestScope.execute(transport.newCall(request.build())) { response ->
            ThlDeviceRegister.parse(readRegisterBody(response), response.headers("Set-Cookie"))
        }
    }

    @Synchronized
    fun profile(http: OkHttpClient): Profile {
        ready?.let { return it }
        if (Thread.currentThread().isInterrupted) throw InterruptedIOException("播放请求已取消")
        val device =
            try {
                registerDevice(http) ?: throw PlaybackDeviceRegistrationException()
            } catch (error: InterruptedIOException) {
                throw error
            } catch (error: IOException) {
                throw PlaybackDeviceRegistrationException(error)
            }
        val validId = Regex("[1-9][0-9]{9,23}")
        if (!validId.matches(device.deviceId) || !validId.matches(device.installId))
            throw PlaybackDeviceRegistrationException()
        if (Thread.currentThread().isInterrupted) throw InterruptedIOException("播放请求已取消")
        val values = ThlDeviceRegister.profile(cdid, openudid) +
            mapOf("device_id" to device.deviceId, "iid" to device.installId)
        return Profile(
            values,
            device.cookies
                .filterKeys { it in setOf("install_id", "ttreq", "store-region", "store-region-src") }
                .entries
                .joinToString("; ") { (name, value) -> "$name=$value" },
        ).also { ready = it; persist(it) }
    }

    /** Drop the in-process anonymous identity and register a fresh one. */
    @Synchronized
    fun renew(http: OkHttpClient): Profile {
        ready = null
        store?.save(null)
        cdid = ThlDeviceRegister.newCdid()
        openudid = ThlDeviceRegister.newOpenudid()
        return profile(http)
    }
}

private fun readRegisterBody(response: okhttp3.Response): String {
    if (!response.isSuccessful) throw IOException("播放设备注册失败（HTTP ${response.code}）")
    val body = response.body ?: throw IOException("播放设备注册响应为空")
    val limit = 2L * 1024 * 1024
    if (body.contentLength() > limit) throw IOException("播放设备注册响应过大")
    val input = body.byteStream()
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (out.size() <= limit) {
        val n = input.read(buffer)
        if (n < 0) break
        out.write(buffer, 0, n)
    }
    val bytes = out.toByteArray()
    if (bytes.size > limit) throw IOException("播放设备注册响应过大")
    val text = String(bytes, Charsets.UTF_8)
    if (text.isBlank()) throw IOException("播放设备注册返回空响应")
    return text
}
