// SPDX-License-Identifier: GPL-3.0-only
// Adapted from never88gone/THLRedGuo assets/sources/hongguo.js for Native N only.
package com.hongguotv.core

import org.json.JSONObject
import java.util.UUID

object ThlDeviceRegister {
    private const val URL =
        "https://log.snssdk.com/service/2/device_register/?aid=8662&app_name=novelread"

    const val USER_AGENT =
        "com.phoenix.read/71332 (Linux; U; Android 16; zh_CN; 25053RT47C; Build/BP2A.250605.031.A3; Cronet/TTNetVersion:04657795 2026-01-23 QuicVersion:c67e9834 2025-09-08)"

    data class Registered(
        val deviceId: String,
        val installId: String,
        val deviceToken: String = "",
        val newUser: Boolean = false,
        val cookies: Map<String, String> = emptyMap(),
    )

    fun profile(): Map<String, String> = linkedMapOf(
        "ac" to "wifi",
        "channel" to "update_64",
        "aid" to "8662",
        "app_name" to "novelread",
        "version_code" to "73932",
        "version_name" to "7.3.9.32",
        "device_platform" to "android",
        "os" to "android",
        "ssmix" to "a",
        "device_type" to "25053RT47C",
        "device_brand" to "Redmi",
        "language" to "zh",
        "os_api" to "36",
        "os_version" to "16",
        "manifest_version_code" to "73932",
        "resolution" to "1280*2772",
        "dpi" to "520",
        "update_version_code" to "73932",
        "host_abi" to "arm64-v8a",
        "dragon_device_type" to "phone",
        "pv_player" to "73932",
        "compliance_status" to "0",
        "need_personal_recommend" to "1",
        "player_so_load" to "1",
        "is_android_pad_screen" to "0",
    )

    fun payload(cdid: String, openudid: String, now: Long = System.currentTimeMillis()): ByteArray {
        val header = JSONObject()
            .put("aid", 8662)
            .put("app_name", "novelread")
            .put("package", "com.phoenix.read")
            .put("app_version", "7.3.9.32")
            .put("version_code", 73932)
            .put("update_version_code", 73932)
            .put("manifest_version_code", 73932)
            .put("sdk_version", "6.18.0")
            .put("device_platform", "android")
            .put("os", "Android")
            .put("os_version", "16")
            .put("os_api", 36)
            .put("device_model", "25053RT47C")
            .put("device_brand", "Redmi")
            .put("device_manufacturer", "Xiaomi")
            .put("density_dpi", 520)
            .put("display_density", "mdpi")
            .put("resolution", "1280x2772")
            .put("language", "zh")
            .put("timezone", 8)
            .put("access", "wifi")
            .put("channel", "update_64")
            .put("cdid", cdid)
            .put("openudid", openudid)

        return JSONObject()
            .put("magic_tag", "ss_app_log")
            .put("header", header)
            .put("_gen_time", now)
            .toString()
            .toByteArray(Charsets.UTF_8)
    }

    fun headers(): Map<String, String> = linkedMapOf(
        "Content-Type" to "application/json; charset=utf-8",
        "User-Agent" to USER_AGENT,
    )

    fun url(): String = URL

    fun parse(body: String, setCookies: List<String> = emptyList()): Registered? {
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return null
        fun id(vararg keys: String): String {
            for (key in keys) {
                val value = json.opt(key)?.toString()?.trim().orEmpty()
                if (value.isNotEmpty()) return value
            }
            return ""
        }
        val device = id("device_id_str", "device_id")
        val install = id("install_id_str", "install_id")
        val valid = Regex("[1-9][0-9]{9,23}")
        if (!valid.matches(device) || !valid.matches(install)) return null
        val cookies = linkedMapOf<String, String>()
        setCookies.forEach { header ->
            header.substringBefore(';').trim().takeIf { it.contains('=') }?.let { pair ->
                val name = pair.substringBefore('=').trim()
                val value = pair.substringAfter('=').trim()
                if (name.isNotEmpty() && value.isNotEmpty()) cookies[name] = value
            }
        }
        return Registered(
            deviceId = device,
            installId = install,
            deviceToken = json.optString("device_token", ""),
            newUser = json.optInt("new_user", 0) == 1,
            cookies = cookies,
        )
    }

    fun newCdid(): String = UUID.randomUUID().toString()

    fun newOpenudid(): String {
        val bytes = ByteArray(8)
        java.security.SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
