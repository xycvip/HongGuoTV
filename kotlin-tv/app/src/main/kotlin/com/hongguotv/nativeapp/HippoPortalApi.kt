// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** 河马门户独立客户端；注册身份与协议状态不复用红果。调用必须在 IO worker 执行。 */
internal class HippoPortalApi(context: Context, private val http: OkHttpClient) {
    private val prefs = context.applicationContext.getSharedPreferences("hippo-portal-v1", Context.MODE_PRIVATE)
    private val domains = listOf("freevideo.zqqds.cn", "freevideo.dzkjk.cn")
    private val deviceId = prefs.getString("utdid", null) ?: UUID.randomUUID().toString().replace("-", "").also { prefs.edit().putString("utdid", it).apply() }
    private val tempId = prefs.getString("utdidTmp", null) ?: ("A" + java.text.SimpleDateFormat("yyyyMMddHHmmssSSS", java.util.Locale.US).format(java.util.Date()) + UUID.randomUUID().toString().take(6)).also { prefs.edit().putString("utdidTmp", it).apply() }
    private val installTime = prefs.getLong("installTime", 0L).takeIf { it > 0L } ?: System.currentTimeMillis().also { prefs.edit().putLong("installTime", it).apply() }
    private var userId = prefs.getString("userId", "").orEmpty()
    private var initialized = prefs.getBoolean("initialized", false)

    @Synchronized fun ensureInitialized() {
        if (initialized) return
        val data = request("1103", JSONObject().put("oaid", "").put("userAgent", "HongGuoTV/Android")
            .put("requestType", 1).put("activeType", 1).put("isFirstLaunch", true)
            .put("todayLaunchNums", 1).put("launchTime", installTime))
        val user = data.optJSONObject("userInfoVo")
        userId = user?.optString("userId").orEmpty().ifBlank { data.optString("userId") }
        prefs.edit().putString("userId", userId).putBoolean("initialized", true).apply()
        initialized = true
        Log.i("HongguoTV-Hippo", "河马设备初始化完成，userId=${userId.isNotBlank()}")
    }

    fun request(api: String, params: JSONObject = JSONObject()): JSONObject {
        val errors = mutableListOf<String>()
        for (domain in domains) for (encrypted in listOf(false, true)) {
            try {
                val headersJson = headers().toString()
                val payload = params.toString()
                val body = if (encrypted) encrypt(payload) else payload
                val builder = Request.Builder().url("https://$domain/free-video-portal/portal/$api")
                    .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .header("Accept", "application/json").header("Content-Type", "application/json")
                    .header("datas", if (encrypted) encrypt(headersJson) else headersJson)
                if (!encrypted) builder.header("wetruwtty", "mhdfiheowjfcslkjfwojo636")
                http.newCall(builder.build()).execute().use { response ->
                    val raw = response.body?.string().orEmpty()
                    if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                    val envelope = JSONObject(raw.ifBlank { "{}" })
                    val code = envelope.optInt("code", 0)
                    if (code != 0 && code != 200) throw IOException("业务 code=$code: ${envelope.optString("msg")}")
                    val value = envelope.opt("data")
                    if (value is JSONObject) return value
                    if (value is String && value.isNotBlank()) return JSONObject(if (encrypted) decrypt(value) else value)
                    return envelope
                }
            } catch (error: Exception) {
                errors += "$domain/${if (encrypted) "AES" else "JSON"}: ${error.message}"
            }
        }
        throw IOException("河马接口 $api 请求失败：${errors.takeLast(4).joinToString("；")}")
    }

    private fun headers() = JSONObject()
        .put("version", "1.4.0").put("pname", "com.dz.hmjc").put("channelCode", "Google")
        .put("utdidTmp", tempId).put("token", prefs.getString("token", "").orEmpty())
        .put("utdid", deviceId).put("os", "android").put("osv", android.os.Build.VERSION.RELEASE)
        .put("brand", android.os.Build.BRAND).put("model", android.os.Build.MODEL)
        .put("manu", android.os.Build.MANUFACTURER).put("userId", userId)
        .put("launch", "shortcut").put("mchid", "Google").put("nchid", "Google")
        .put("session1", deviceId).put("session2", System.currentTimeMillis().toString())
        .put("oaid", "").put("imei", "").put("startScene", "shortcut").put("installTime", installTime).put("p", 9)

    private fun encrypt(value: String): String = crypt(value.toByteArray(Charsets.UTF_8), Cipher.ENCRYPT_MODE).joinToString("") { "%02x".format(it) }
    private fun decrypt(value: String): String {
        require(value.length % 2 == 0) { "河马加密响应长度错误" }
        val bytes = ByteArray(value.length / 2) { index -> value.substring(index * 2, index * 2 + 2).toInt(16).toByte() }
        return String(crypt(bytes, Cipher.DECRYPT_MODE), Charsets.UTF_8)
    }
    private fun crypt(value: ByteArray, mode: Int): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(mode, SecretKeySpec("dzkjgfyxgshylgzm".toByteArray(), "AES"), IvParameterSpec("apiupdownedcrypt".toByteArray()))
        return cipher.doFinal(value)
    }
}
