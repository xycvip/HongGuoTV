// SPDX-License-Identifier: GPL-3.0-only
// Player endpoint selection adapted from N3urda/hongguoTV (main).
package com.hongguotv.core

import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject

/**
 * Separate registered identity for the legacy player endpoints.
 * N3urda uses this identity independently from the content/video_tab device session.
 * The cache is process-wide so registration is not repeated for every episode.
 */
object PlayerDeviceSource {
    @Volatile private var cached: PlayerDevice? = null
    @Volatile private var store: PlayerDeviceStore? = null
    private val lock = Any()

    fun configureStore(value: PlayerDeviceStore?) {
        store = value
        cached?.let { runCatching { value?.save(it.toJson()) } }
    }

    fun obtain(http: OkHttpClient): PlayerDevice {
        cached?.let { return it }
        return synchronized(lock) {
            cached ?: run {
                val restored = runCatching { store?.load()?.let(PlayerDevice::fromJson) }.getOrNull()
                (restored ?: DeviceRegistrar().register(http)).also {
                    cached = it
                    runCatching { store?.save(it.toJson()) }
                }
            }
        }
    }

    fun invalidate() {
        synchronized(lock) {
            cached = null
            runCatching { store?.save(null) }
        }
    }

    const val SHORTPLAY_URL = "https://api3-normal-sinfonlinea.fqnovel.com/novel/player/video_model/v1/?iid={install_id}&device_id={device_id}&aid=1967&app_name=novelapp&version_code=72132&version_name=7.2.1.32&device_platform=android&device_brand=Xiaomi&os_version=13&cdid={cdid}"
    const val PHOENIX_URL = "https://api5-normal-sinfonlineb.fqnovel.com/novel/player/multi_video_model/v1/?iid={install_id}&device_id={device_id}&ac=wifi&channel=update_64&aid=8662&app_name=novelread&version_code=71332&version_name=7.1.3.32&device_platform=android&os=android&ssmix=a&device_type=25053RT47C&device_brand=Redmi&language=zh&os_api=36&os_version=16&manifest_version_code=71332&resolution=1280*2772&dpi=520&update_version_code=71332&host_abi=arm64-v8a&dragon_device_type=phone&pv_player=71332&compliance_status=0&need_personal_recommend=1&player_so_load=1&is_android_pad_screen=0"
    const val FALLBACK_USER_AGENT = "com.xs.fm/632 (Linux; U; Android 15; zh_CN; 23049RAD8C; Build/AQ3A.250226.002; Cronet/TTNetVersion:fc4cebd3 2024-12-10 QuicVersion:d9628e3d 2024-10-11)"

    fun substituteDevice(url: String, device: PlayerDevice): String = url
        .replace("{device_id}", device.deviceId)
        .replace("{install_id}", device.installId)
        .replace("{secret_key}", device.secretKey)
        .replace("{cdid}", device.cdid)
        .replace("{openudid}", device.openudid.ifEmpty { device.deviceId })

    fun shortplayBody(videoId: String): ByteArray = JSONObject()
        .put("video_id", videoId)
        .put("content_type", 1004)
        .put("biz_param", JSONObject()
            .put("detail_page_version", 0)
            .put("device_level", 3)
            .put("disable_digg_stat", false)
            .put("disable_video_relate_book", false)
            .put("from_video_id", "")
            .put("need_all_video_definition", true)
            .put("need_mp4_align", false)
            .put("source", 4)
            .put("use_os_player", false)
            .put("use_server_dns", false)
            .put("video_platform", 3))
        .toString().toByteArray(Charsets.UTF_8)

    fun phoenixBody(videoId: String): ByteArray = JSONObject()
        .put("biz_param", JSONObject()
            .put("detail_page_version", 0)
            .put("device_level", 3)
            .put("disable_digg_stat", false)
            .put("need_all_video_definition", true)
            .put("need_mp4_align", false)
            .put("use_os_player", false)
            .put("use_server_dns", false)
            .put("video_platform", 1024))
        .put("mixed_video_id_map", JSONObject().put("1004", JSONArray().put(videoId)))
        .toString().toByteArray(Charsets.UTF_8)
}
