// SPDX-License-Identifier: GPL-3.0-only
// Ported from OOIUa/hongguo-android PlaybackDevice (GPL-3.0-only).
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

/** OOIUa-compatible anonymous playback device registration. */
object PhoenixDeviceRegister {
    private const val REGISTER_URL = "https://log5-applog-lf.fqnovel.com/service/2/device_register/?tt_data=a&cronet_version=8d40f833_2026-03-03&ttnet_version=4.2.243.31-douyin&use_store_region_cookie=1"
    private const val REGISTER_UA = "com.phoenix.read/72232 (Linux; U; Android 16; zh_CN; 25053RT47C; Build/BP2A.250605.031.A3)"
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
        val payload = registrationPayload(now, cdid)
        val body = ttEncrypt(gzip(payload.toByteArray(Charsets.UTF_8)))
        val request = Request.Builder().url(REGISTER_URL)
            .header("User-Agent", REGISTER_UA)
            .header("Accept", "application/json")
            .header("Content-Type", "application/octet-stream;tt-data=a")
            .header("log-encode-type", "gzip")
            .header("x-ss-dp", "8662")
            .header("x-ss-req-ticket", now.toString())
            .header("x-vc-bdturing-sdk-version", "4.0.3.cn")
            .header("lc", "101")
            .header("sdk-version", "2")
            .header("passport-sdk-version", "5051452")
            .header("x-neptune", "-8|50:51:59:30:40:47:49:39")
            .header("x-ss-stub", digest("MD5", body).hex().uppercase())
            .post(body.toRequestBody("application/octet-stream;tt-data=a".toMediaType()))
            .build()
        val transport = http.newBuilder().cookieJar(CookieJar.NO_COOKIES).followRedirects(false).callTimeout(8, TimeUnit.SECONDS).build()
        return RequestScope.execute(transport.newCall(request)) { response ->
            if (response.code != 200) throw IOException("播放设备注册失败（HTTP ${response.code}）")
            val responseBody = response.body ?: throw IOException("播放设备注册响应为空")
            if (responseBody.contentLength() > 2L * 1024 * 1024) throw IOException("播放设备注册响应过大")
            val raw = responseBody.string()
            if (raw.isBlank()) throw IOException("播放设备注册返回空响应")
            val json = runCatching { org.json.JSONObject(raw) }.getOrElse {
                throw IOException("播放设备注册响应不是有效 JSON")
            }
            val code = json.opt("code") ?: json.opt("Code") ?: 0
            if (code.toString().toLongOrNull() != 0L) throw IOException("播放设备注册被拒绝")
            fun responseId(name: String): String {
                val preferred = json.opt(name + "_str")
                val value = if (preferred != null && preferred.toString().isNotBlank()) preferred else json.opt(name)
                val id = value?.toString()?.trim().orEmpty()
                return id.takeIf { it.matches(Regex("[0-9]{1,30}")) && it.any { ch -> ch != '0' } }.orEmpty()
            }
            val deviceId = responseId("device_id")
            val installId = responseId("install_id")
            if (deviceId.isEmpty() || installId.isEmpty()) throw IOException("设备注册响应缺少有效 device_id/install_id")
            val cookies = linkedMapOf<String, String>()
            response.headers("Set-Cookie").forEach { header ->
                val pair = header.substringBefore(';').trim()
                val split = pair.indexOf('=')
                if (split > 0) {
                    val name = pair.substring(0, split)
                    val value = pair.substring(split + 1)
                    if (name in allowedCookies && value.isNotEmpty()) cookies[name] = value
                }
            }
            Registered(deviceId, installId, cdid, cookies)
        }
    }

    private fun registrationPayload(now: Long, cdid: String): String {
        val openudid = ByteArray(8).also(random::nextBytes).hex()
        val header = org.json.JSONObject()
            .put("display_name", "红果免费短剧")
            .put("update_version_code", 73932).put("manifest_version_code", 73932)
            .put("app_version_minor", "7.3.9.32").put("aid", 8662)
            .put("channel", "update_64").put("package", "com.phoenix.read")
            .put("app_version", "7.3.9.32").put("version_code", 73932)
            .put("sdk_version", "3.7.0-rc.34-fanqie-xiaoshuo").put("sdk_target_version", 29)
            .put("git_hash", "3a88f4e").put("os", "Android").put("os_version", "16").put("os_api", 36)
            .put("device_model", "25053RT47C").put("device_brand", "Redmi").put("device_manufacturer", "Redmi")
            .put("cpu_abi", "arm64-v8a").put("release_build", "b5e187f")
            .put("density_dpi", 520).put("display_density", "mdpi").put("resolution", "1280x2772")
            .put("language", "zh").put("timezone", 8).put("access", "wifi").put("not_request_sender", 0)
            .put("carrier", "CHINA MOBILE").put("mcc_mnc", "46000")
            .put("rom", "eng.root.20231116.160340").put("rom_version", "W528JS release-keys")
            .put("cdid", cdid).put("sig_hash", "56a962410c494bbaf0b58dba20cae56f")
            .put("openudid", openudid).put("clientudid", UUID.randomUUID().toString())
            .put("ipv6_list", org.json.JSONArray()).put("region", "CN").put("tz_name", "Asia/Shanghai")
            .put("tz_offset", 28800).put("sim_region", "cn").put("sim_serial_number", org.json.JSONArray())
            .put("oaid_may_support", false).put("req_id", UUID.randomUUID().toString())
            .put("device_platform", "android")
        header.put("custom", org.json.JSONObject().put("account_region_src", "fallback")
            .put("account_region", "cn").put("host_bit", 64).put("dragon_device_type", "phone"))
        header.put("pre_installed_channel", "").put("apk_first_install_time", now)
            .put("is_system_app", 0).put("sdk_flavor", "china").put("guest_mode", 0)
        return org.json.JSONObject().put("magic_tag", "ss_app_log").put("header", header)
            .put("_gen_time", now).toString()
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
