// Adapted from Xanerix/guoapp native/core/provider_hongguo_device.go (GPL-3.0).
// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import java.util.UUID
import org.json.JSONObject

/**
 * Xanerix/guoapp 红果匿名设备注册协议。
 *
 * 注册请求是普通 JSON POST：不使用 ttEncrypt、不 gzip、不附加播放器签名。
 * device_id / install_id 由服务端返回，随后用于播放接口 query。
 */
object PhoenixDeviceRegister {
    private const val URL = "https://log.snssdk.com/service/2/device_register/"
    private const val USER_AGENT =
        "com.phoenix.read/73532 (Linux; U; Android 16; zh_CN; 25053RT47C; Build/BP2A.250605.031.A3; Cronet/TTNetVersion:04657795 2026-01-23 QuicVersion:c67e9834 2025-09-08)"

    data class Registered(
        val deviceId: String,
        val installId: String,
    )

    /** 与上游 registerHongguoDevice 的 header 字段和值保持一致。 */
    fun payload(now: Long = System.currentTimeMillis()): ByteArray {
        val header = JSONObject().apply {
            put("display_name", "红果免费短剧")
            put("aid", 8662)
            put("app_version", "7.3.5.32")
            put("version_code", 73532)
            put("manifest_version_code", 73532)
            put("update_version_code", 73532)
            put("channel", "update_64")
            put("package", "com.phoenix.read")
            put("app_language", "zh")
            put("os", "Android")
            put("os_version", "16")
            put("os_api", 36)
            put("device_model", "25053RT47C")
            put("device_brand", "Redmi")
            put("device_manufacturer", "Xiaomi")
            put("cpu_abi", "arm64-v8a")
            put("density_dpi", 520)
            put("display_density", "xxhdpi")
            put("resolution", "2772x1280")
            put("language", "zh")
            put("timezone", 8)
            put("access", "wifi")
            put("openudid", UUID.randomUUID().toString().replace("-", "").take(16))
            put("clientudid", UUID.randomUUID().toString())
            put("cdid", UUID.randomUUID().toString())
            put("region", "CN")
            put("tz_name", "Asia/Shanghai")
            put("tz_offset", 28800)
            put("sim_region", "cn")
            put("device_platform", "android")
            put("not_request_sender", 0)
            put("is_system_app", 0)
            put("sdk_flavor", "china")
            put("rom", "OS2.0")
            put("rom_version", "BP2A.250605.031.A3")
        }
        return JSONObject()
            .put("magic_tag", "ss_app_log")
            .put("header", header)
            .put("_gen_time", now)
            .toString()
            .toByteArray(Charsets.UTF_8)
    }

    /** 注册 query 不携带 device_id / iid，也不生成任何签名字段。 */
    fun url(now: Long = System.currentTimeMillis()): String {
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
            "ac" to "wifi",
            "req_id" to UUID.randomUUID().toString(),
            "_rticket" to now.toString(),
        )
        val query = values.toSortedMap().entries.joinToString("&") { (key, value) ->
            "${encode(key)}=${encode(value)}"
        }
        return "$URL?$query"
    }

    fun headers(): Map<String, String> = linkedMapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "application/json",
        "Content-Type" to "application/json; charset=utf-8",
    )

    /** 只接受服务端分配的有效数字身份；不使用本地伪造 ID 代替注册结果。 */
    fun parse(body: String): Registered? {
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val device = json.opt("device_id_str")?.toString()?.takeIf { it.isNotBlank() }
            ?: json.opt("device_id")?.toString().orEmpty()
        val install = json.opt("install_id_str")?.toString()?.takeIf { it.isNotBlank() }
            ?: json.opt("install_id")?.toString().orEmpty()
        val valid = Regex("[1-9][0-9]{0,29}")
        if (!valid.matches(device) || !valid.matches(install)) return null
        return Registered(device, install)
    }

    private fun encode(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}
