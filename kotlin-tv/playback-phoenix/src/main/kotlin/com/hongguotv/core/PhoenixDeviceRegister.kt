// SPDX-License-Identifier: GPL-3.0-only
// Device registration aligned to RedGuoAPI modules/hongguo/main.js.
package com.hongguotv.core

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream
import okhttp3.CookieJar
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** RedGuoAPI-compatible anonymous playback device registration. */
object PhoenixDeviceRegister {
    private const val REGISTER_URL = "https://log.snssdk.com/service/2/device_register/"
    private const val REGISTER_UA = "com.phoenix.read/73532 (Linux; U; Android 16; zh_CN; 25053RT47C; Build/BP2A.250605.031.A3; Cronet/TTNetVersion:04657795 2026-01-23 QuicVersion:c67e9834 2025-09-08)"
    private val MAGIC = hex("746305100000")
    private val KDF_SALT = hex("4dd4c2e6b83162090e52b3c7a6733ba41cb2462b829ab58a196b39db57177524f49baf7f08e8d68d26a72e37c1a95a2f1f05a51892aef2949732b62a38aadd58")
    private val random = SecureRandom()
    private const val FAILURE_COOLDOWN_MS = 10_000L
    private val failureLock = Any()
    @Volatile private var lastFailure: IOException? = null
    @Volatile private var lastFailureAt = 0L
    private val allowedCookies = setOf("install_id", "ttreq", "store-region", "store-region-src")

    data class Registered(
        val deviceId: String,
        val installId: String,
        val cdid: String,
        val cookies: Map<String, String>
    ) {
        val cookie: String get() = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    fun register(http: OkHttpClient): Registered {
        val now = System.currentTimeMillis()
        synchronized(failureLock) {
            val failure = lastFailure
            if (failure != null && now - lastFailureAt < FAILURE_COOLDOWN_MS) throw failure
        }
        return try {
            registerNow(http, now).also {
                synchronized(failureLock) { lastFailure = null; lastFailureAt = 0L }
            }
        } catch (error: InterruptedIOException) {
            if (Thread.currentThread().isInterrupted) throw error
            val failure = IOException("匿名播放设备注册失败，请检查网络后重试", error)
            synchronized(failureLock) { lastFailure = failure; lastFailureAt = now }
            throw failure
        } catch (error: IOException) {
            val failure = IOException("匿名播放设备注册失败，请检查网络后重试", error)
            synchronized(failureLock) { lastFailure = failure; lastFailureAt = now }
            throw failure
        } catch (error: Exception) {
            val failure = IOException("匿名播放设备注册失败，请检查网络后重试", error)
            synchronized(failureLock) { lastFailure = failure; lastFailureAt = now }
            throw failure
        }
    }

    private fun registerNow(http: OkHttpClient, now: Long): Registered {
        RequestScope.ensureActive()
        val cdid = UUID.randomUUID().toString()
        val query = java.util.TreeMap<String, String>().apply {
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
            put("req_id", UUID.randomUUID().toString())
            put("_rticket", now.toString())
        }
        val queryText = query.entries.joinToString("&") { (key, value) ->
            java.net.URLEncoder.encode(key, "UTF-8").replace("+", "%20") + "=" +
                java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")
        }
        val body = registrationPayload(now, cdid)
            .toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url("$REGISTER_URL?$queryText")
            .header("User-Agent", REGISTER_UA)
            .header("Accept", "application/json")
            .header("Content-Type", "application/json; charset=utf-8")
            .post(body)
            .build()
        val transport = http.newBuilder()
            .cookieJar(CookieJar.NO_COOKIES)
            .followRedirects(true)
            .callTimeout(20, TimeUnit.SECONDS)
            .build()
        return RequestScope.execute(transport.newCall(request)) { response ->
            if (response.code >= 500) throw IOException("红果设备注册 HTTP ${response.code}")
            val responseBody = response.body ?: throw IOException("红果设备注册响应为空")
            if (responseBody.contentLength() > 64L * 1024L) throw IOException("红果设备注册响应过大")
            val raw = responseBody.string()
            val json = runCatching { org.json.JSONObject(raw) }
                .getOrElse { throw IOException("红果设备注册收到的不是接口数据（HTTP ${response.code}）", it) }
            if (response.code != 200) throw IOException("红果设备注册 HTTP ${response.code}")
            fun responseId(name: String): String {
                val preferred = json.opt(name + "_str")
                val value = if (preferred != null && preferred.toString().isNotBlank()) preferred else json.opt(name)
                return value?.toString()?.trim().orEmpty()
            }
            val deviceId = responseId("device_id")
            val installId = responseId("install_id")
            val validId = Regex("[1-9][0-9]{0,29}")
            if (!validId.matches(deviceId) || !validId.matches(installId)) {
                throw IOException("红果设备注册未返回有效设备号")
            }
            Registered(deviceId, installId, cdid, emptyMap())
        }
    }

    private fun registrationPayload(now: Long, cdid: String): String {
        val header = org.json.JSONObject()
            .put("display_name", "红果免费短剧")
            .put("aid", 8662)
            .put("app_version", "7.3.5.32")
            .put("version_code", 73532)
            .put("manifest_version_code", 73532)
            .put("update_version_code", 73532)
            .put("channel", "update_64")
            .put("package", "com.phoenix.read")
            .put("app_language", "zh")
            .put("os", "Android")
            .put("os_version", "16")
            .put("os_api", 36)
            .put("device_model", "25053RT47C")
            .put("device_brand", "Redmi")
            .put("device_manufacturer", "Xiaomi")
            .put("cpu_abi", "arm64-v8a")
            .put("density_dpi", 520)
            .put("display_density", "xxhdpi")
            .put("resolution", "2772x1280")
            .put("language", "zh")
            .put("timezone", 8)
            .put("access", "wifi")
            .put("openudid", ByteArray(8).also(random::nextBytes).hex())
            .put("clientudid", UUID.randomUUID().toString())
            .put("cdid", cdid)
            .put("region", "CN")
            .put("tz_name", "Asia/Shanghai")
            .put("tz_offset", 28800)
            .put("sim_region", "cn")
            .put("device_platform", "android")
            .put("not_request_sender", 0)
            .put("is_system_app", 0)
            .put("sdk_flavor", "china")
            .put("rom", "OS2.0")
            .put("rom_version", "BP2A.250605.031.A3")
        return org.json.JSONObject()
            .put("magic_tag", "ss_app_log")
            .put("header", header)
            .put("_gen_time", now)
            .toString()
    }

    private fun gzip(data: ByteArray): ByteArray = ByteArrayOutputStream().also { out ->
        GZIPOutputStream(out).use { it.write(data) }
    }.toByteArray()

    internal fun registrationUrlForTest(): String = REGISTER_URL

    private fun ttEncrypt(data: ByteArray): ByteArray =
        ttEncrypt(data, ByteArray(32).also(random::nextBytes))

    internal fun ttEncrypt(data: ByteArray, rnd: ByteArray): ByteArray {
        require(rnd.size == 32)
        val inner = digest("SHA-512", rnd)
        val derived = digest("SHA-512", inner + KDF_SALT).copyOfRange(0, 32)
        val plain = digest("SHA-512", data) + data
        val pad = 16 - plain.size % 16
        val padded = plain + ByteArray(pad) { pad.toByte() }
        val encrypted = javax.crypto.Cipher.getInstance("AES/CBC/NoPadding").run {
            init(javax.crypto.Cipher.ENCRYPT_MODE,
                javax.crypto.spec.SecretKeySpec(derived.copyOfRange(0, 16), "AES"),
                javax.crypto.spec.IvParameterSpec(derived.copyOfRange(16, 32)))
            doFinal(padded)
        }
        return MAGIC + rnd + encrypted
    }
}
