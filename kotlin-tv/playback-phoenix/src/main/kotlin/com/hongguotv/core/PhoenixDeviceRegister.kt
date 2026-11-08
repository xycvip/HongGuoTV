// Adapted from charlie901208/HGTV feat/phone-login (GPL-3.0-only).
// SPDX-License-Identifier: GPL-3.0-only.
package com.hongguotv.core

import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.zip.GZIPOutputStream

/**
 * 设备注册（`POST /service/2/device_register/`）。
 *
 * 平台的内容接口按设备放行，认的是「服务端注册过的 `device_id` + `install_id` 这一对」：
 * 未注册设备的播放接口一律返回 `HTTP 200 + 0 字节`，注册后返回 `code 0` 与完整播放模型。
 * 注册本身是匿名的，不要账号、不要签名头。
 *
 * 三个实测容易踩的坑：
 * 1. body 是 `ttEncrypt(gzip(JSON))` 的原始二进制，不是 base64 文本。
 * 2. 不手动设 `Accept-Encoding`，交给 OkHttp 自己加并解压。
 * 3. 不要加 `x-gorgon` / `x-argus` 等签名头，带了会被判异常客户端。
 */
object PhoenixDeviceRegister {
    private const val HOST = "https://log5-applog-lq.fqnovel.com"
    private const val PATH = "/service/2/device_register/"

    /** ttEncrypt 的固定前缀（`libEncryptor.so` 的魔数）。 */
    private val MAGIC = byteArrayOf(0x74, 0x63, 0x05, 0x10, 0x00, 0x00)

    /** PhoenixReadApi TtEncrypt 固定前缀与 KDF 常量。 */
    private val KDF_SALT = hex(
        "4dd4c2e6b83162090e52b3c7a6733ba41cb2462b829ab58a196b39db57177524f49baf7f08e8d68d26a72e37c1a95a2f1f05a51892aef2949732b62a38aadd58"
    )
    private val TT_ENCRYPT_PREFIX = hex(
        "3176c9b6d71579d7e82179c9e7cbde787b54d541bad3b15f2e8fed92293fa25a"
    )
    private val TT_ENCRYPT_KEY_IV = digest(
        "SHA-512",
        digest("SHA-512", TT_ENCRYPT_PREFIX) + KDF_SALT
    )

    /** 注册结果：平台下发的设备身份 + 需要回写的那几个 Cookie。 */
    data class Registered(
        val deviceId: String,
        val installId: String,
        val deviceToken: String = "",
        /** `new_user=1` 表示平台这次新建了一台设备。 */
        val newUser: Boolean = false,
        val klinkEgdi: String = "",
        /** `Set-Cookie` 里可直接回写的那几项（`install_id` / `ttreq` / `store-region` …）。 */
        val cookies: Map<String, String> = emptyMap(),
    )

    /**
     * ttEncrypt：`MAGIC || rnd[32] || AES-128-CBC(SHA512(SHA512(rnd)+KDF_SALT) 前 32 字节,
     * PKCS7(SHA512(data)||data))`。
     */
    fun ttEncrypt(data: ByteArray): ByteArray {
        // PhoenixReadApi TtEncrypt.Encrypt: SHA512(data) || data, AES-128-CBC/PKCS7,
        // prefixed by the fixed tt-data magic and 32-byte prefix.
        val plain = digest("SHA-512", data) + data
        val padding = 16 - (plain.size % 16)
        val padded = plain + ByteArray(padding) { padding.toByte() }
        val encrypted = javax.crypto.Cipher.getInstance("AES/CBC/NoPadding").run {
            init(
                javax.crypto.Cipher.ENCRYPT_MODE,
                javax.crypto.spec.SecretKeySpec(TT_ENCRYPT_KEY_IV.copyOfRange(0, 16), "AES"),
                javax.crypto.spec.IvParameterSpec(TT_ENCRYPT_KEY_IV.copyOfRange(16, 32)),
            )
            doFinal(padded)
        }
        return MAGIC + TT_ENCRYPT_PREFIX + encrypted
    }

    private fun gzip(data: ByteArray): ByteArray =
        ByteArrayOutputStream()
            .also { out -> GZIPOutputStream(out).use { it.write(data) } }
            .toByteArray()

    /** 组装注册请求体：`ttEncrypt(gzip(JSON))`。`device_id` / `install_id` 由服务端下发。 */
    fun payload(device: Map<String, String>, now: Long = System.currentTimeMillis()): ByteArray {
        fun value(key: String, fallback: String) = device[key]?.takeIf { it.isNotBlank() } ?: fallback
        val json = org.json.JSONObject().put("magic_tag", "ss_app_log")
        json.put("header", org.json.JSONObject().apply {
            put("display_name", "红果免费短剧")
            put("aid", 8662)
            put("app_version", value("version_name", "7.1.3.32"))
            put("version_code", value("version_code", "71332").toIntOrNull() ?: 71332)
            put("manifest_version_code", value("manifest_version_code", value("version_code", "71332")).toIntOrNull() ?: 71332)
            put("update_version_code", value("update_version_code", value("version_code", "71332")).toIntOrNull() ?: 71332)
            put("channel", value("channel", "update_64"))
            put("package", "com.phoenix.read")
            put("app_language", "zh")
            put("os", "Android")
            put("os_version", value("os_version", "16"))
            put("os_api", value("os_api", "36").toIntOrNull() ?: 36)
            put("device_model", value("device_type", "25053RT47C"))
            put("device_brand", value("device_brand", "Redmi"))
            put("device_manufacturer", value("device_brand", "Redmi"))
            put("cpu_abi", value("host_abi", "arm64-v8a"))
            put("density_dpi", value("dpi", "520").toIntOrNull() ?: 520)
            put("display_density", when {
                (value("dpi", "520").toIntOrNull() ?: 520) >= 480 -> "xxxhdpi"
                (value("dpi", "520").toIntOrNull() ?: 520) >= 320 -> "xxhdpi"
                else -> "xhdpi"
            })
            put("resolution", value("resolution", "1280*2772").replace('*', 'x'))
            put("language", "zh")
            put("timezone", 8)
            put("access", "wifi")
            put("openudid", value("openudid", UUID.randomUUID().toString().replace("-", "").take(16)))
            put("clientudid", UUID.randomUUID().toString())
            put("cdid", value("cdid", UUID.randomUUID().toString()))
            put("region", "CN")
            put("tz_name", "Asia/Shanghai")
            put("tz_offset", 28800)
            put("sim_region", "cn")
            put("device_platform", "android")
            put("not_request_sender", 0)
            put("is_system_app", 0)
            put("sdk_flavor", "china")
            put("rom", "Android17")
            put("rom_version", "CP2A.260605.012")
        })
        json.put("_gen_time", now)
        return ttEncrypt(gzip(json.toString().toByteArray(Charsets.UTF_8)))
    }

    /** 注册请求的头，刻意不加签名头。 */
    fun headers(
        body: ByteArray,
        device: Map<String, String>,
        now: Long = System.currentTimeMillis(),
    ): Map<String, String> = linkedMapOf(
        "User-Agent" to VendorConstants.VIDEO_UA,
        "Accept" to "application/json",
        "log-encode-type" to "gzip",
        "sdk-version" to "2",
        "passport-sdk-version" to "5051452",
        "x-vc-bdturing-sdk-version" to "4.0.3.cn",
        "x-ss-dp" to "8662",
        "X-SS-STUB" to digest("MD5", body).hex().uppercase(),
    )

    /** PhoenixReadApi 的注册 query，req_id 和 ticket 每次注册重新生成。 */
    fun url(
        now: Long = System.currentTimeMillis(),
        device: Map<String, String> = VendorConstants.device,
    ): String {
        fun value(key: String, fallback: String) = device[key]?.takeIf { it.isNotBlank() } ?: fallback
        val values = linkedMapOf(
            "req_id" to UUID.randomUUID().toString(),
            "tt_data" to "a",
            "aid" to value("aid", "8662"),
            "app_name" to value("app_name", "novelread"),
            "version_code" to value("version_code", "71332"),
            "version_name" to value("version_name", "7.1.3.32"),
            "manifest_version_code" to value("manifest_version_code", value("version_code", "71332")),
            "update_version_code" to value("update_version_code", value("version_code", "71332")),
            "channel" to value("channel", "update_64"),
            "device_platform" to value("device_platform", "android"),
            "os" to value("os", "android"),
            "ssmix" to value("ssmix", "a"),
            "device_type" to value("device_type", "25053RT47C"),
            "device_brand" to value("device_brand", "Redmi"),
            "language" to value("language", "zh"),
            "os_api" to value("os_api", "36"),
            "os_version" to value("os_version", "16"),
            "resolution" to value("resolution", "1280*2772"),
            "dpi" to value("dpi", "520"),
            "ac" to value("ac", "wifi"),
            "_rticket" to now.toString(),
        )
        val query = values.entries.joinToString("&") { (key, value) ->
            java.net.URLEncoder.encode(key, "UTF-8").replace("+", "%20") + "=" +
                java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")
        }
        return "$HOST$PATH?$query"
    }

    /** 解析注册响应；`device_id` / `install_id` 数字或字符串都认，全为 0 表示被拒。 */
    fun parse(body: String, setCookies: List<String> = emptyList()): Registered? {
        val json = runCatching { org.json.JSONObject(body) }.getOrNull() ?: return null
        val device =
            json.opt("device_id_str")?.toString()?.takeIf { it.isNotBlank() }
                ?: json.opt("device_id")?.toString().orEmpty()
        val install =
            json.opt("install_id_str")?.toString()?.takeIf { it.isNotBlank() }
                ?: json.opt("install_id")?.toString().orEmpty()
        if (device.isBlank() || install.isBlank() || device == "0" || install == "0") return null
        val cookies = linkedMapOf<String, String>()
        setCookies.forEach { header ->
            header
                .split(';')
                .firstOrNull()
                ?.trim()
                ?.takeIf { it.contains('=') }
                ?.let { pair ->
                    val name = pair.substringBefore('=').trim()
                    val value = pair.substringAfter('=').trim()
                    if (name.isNotEmpty() && value.isNotEmpty()) cookies[name] = value
                }
        }
        return Registered(
            device,
            install,
            json.optString("device_token", ""),
            json.optInt("new_user", 0) == 1,
            json.optString("klink_egdi", ""),
            cookies,
        )
    }
}
