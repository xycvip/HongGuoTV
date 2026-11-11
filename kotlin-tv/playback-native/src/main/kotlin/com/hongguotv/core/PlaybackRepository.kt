// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONObject
import org.json.JSONArray
import java.io.IOException
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private class PlaybackHttpException(val statusCode: Int, message: String): IOException(message)

class PlaybackRepository(
    private val http: OkHttpClient,
    private val clock: () -> Long,
    private val playbackSession: PlaybackDeviceSession,
    private val signer: Signer,
    private val site: String,
    private val requestText: (String, Signer.Request?) -> String
) {
    private val playbackHosts = listOf("api5-normal-sinfonlineb.fqnovel.com")
    private val playbackLog = java.util.logging.Logger.getLogger("com.hongguotv.core.playback")

    private fun tabVideoModelResponse(id: String, host: String): String? {
        // N3urda currentStream uses the content-device profile, not the separate registered
        // PlayerDevice identity used by the two legacy POST routes.
        val values = VendorConstants.device.toMutableMap().apply {
            // Keep Native's endpoint-specific query fields; share Phoenix's app/device profile.
            put("version_code", "71332")
            put("manifest_version_code", "71332")
            put("update_version_code", "71332")
            put("pv_player", "71332")
            put("version_name", "7.1.3.32")
            put("video_id", id)
            put("video_id_type", "0")
            put("device_level", "1")
            put("need_all_video_definition", "1")
            put("need_mp4_align", "0")
            put("use_os_player", "0")
            put("video_platform", "1024")
            put("source", "0")
            put("from_video_id", "")
        }
        val signed = signer.sign(
            "https://$host/reading/bookapi/video_tab/multi_video_model/v1/",
            values,
            null,
        )
        val request = Request.Builder()
            .url(signed.url)
            .header("Accept-Language", "zh-CN,zh;q=0.9")
            .apply {
                signed.headers.forEach { (key, value) -> header(key, value) }
            }
            .get()
            .build()
        val raw = RequestScope.execute(http.newCall(request)) { response ->
            if (!response.isSuccessful) throw PlaybackHttpException(
                response.code, "新版播放接口请求失败（HTTP ${response.code}）"
            )
            val body = response.body ?: return@execute ""
            val limit = 16L * 1024 * 1024
            if (body.contentLength() > limit) throw IOException("新版播放接口响应过大")
            val input = body.byteStream()
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (out.size() <= limit) {
                val count = input.read(buffer)
                if (count < 0) break
                out.write(buffer, 0, count)
            }
            val bytes = out.toByteArray()
            if (bytes.size > limit) throw IOException("新版播放接口响应过大")
            String(bytes, Charsets.UTF_8)
        }
        if (raw.isBlank()) return null
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        if (root.optInt("code", root.optInt("Code", -1)) != 0) return null
        val data = root.optJSONObject("data") ?: return null
        val rows = data.optJSONObject("video_model_data") ?: data
        val row = rows.optJSONObject(id)
            ?: rows.keys().asSequence().mapNotNull { rows.optJSONObject(it) }.firstOrNull()
            ?: return null
        val rawModel = row.opt("video_model") ?: return null
        val model = when (rawModel) {
            is JSONObject -> rawModel
            is String -> runCatching { JSONObject(rawModel) }.getOrNull()
            else -> null
        } ?: return null
        val list = model.opt("video_list")
        val hasVideoRows = when (list) {
            is JSONObject -> list.length() > 0
            is JSONArray -> list.length() > 0
            else -> false
        }
        if (!hasVideoRows) return null
        return raw
    }

    /** Fetch a direct MP4 URL for one episode.
     *  The preferred video_tab GET uses the content-device identity plus N3urda Signer (including
     *  X-Medusa). If it fails, the bounded shortplay/Phoenix POST ladder uses a separate registered
     *  PlayerDeviceSource identity and N3urda PlayerSigner (Argus/Ladon/Gorgon).
     */
    fun stream(id: String, maxQuality: Int = 1080): StreamInfo {
        require(id.matches(Regex("[0-9]{1,30}")))
        // Match N3urda registeredStream: registered player POST ladder, then signed video_tab GET.
        return streamMultiVideoModel(id, maxQuality)
    }

    /** Primary App playback channel. The scheduler invokes the public MP4 fallback only on failure. */
    private fun streamMultiVideoModel(id: String, maxQuality: Int): StreamInfo {
        require(id.matches(Regex("[0-9]{1,30}")))
        // Match N3urda's registeredStream order: shortplay PlayerSigner, Phoenix PlayerSigner,
        // then the newer video_tab GET signed by the content Signer as a compatibility fallback.
        var responseText: String? = null
        var playerFailure: IOException? = null
        try {
            responseText = playbackResponse(id)
            playbackLog.info("PlayerSigner 播放阶梯命中")
        } catch (error: java.io.InterruptedIOException) {
            throw error
        } catch (error: IOException) {
            playerFailure = error
            playbackLog.warning("PlayerSigner 播放阶梯不可用，准备回退 video_tab GET：${error.message}")
        }
        if (responseText == null) {
            var getResponse: String? = null
            var getFailure: IOException? = null
            for (host in playbackHosts) {
                RequestScope.ensureActive()
                try {
                    getResponse = tabVideoModelResponse(id, host)
                    if (getResponse != null) {
                        playbackLog.info("GET video_tab 兼容接口命中域名 $host")
                        break
                    }
                    getFailure = IOException("video_tab 接口返回空响应或无效模型")
                } catch (error: java.io.InterruptedIOException) {
                    throw error
                } catch (error: IOException) {
                    getFailure = error
                    playbackLog.warning("GET video_tab 接口域名 $host 失败：${error.message}")
                    if (!isRetryablePlaybackFailure(error)) break
                }
            }
            responseText = getResponse ?: throw IOException(
                "PlayerSigner 播放阶梯和 video_tab GET 均不可用",
                getFailure ?: playerFailure,
            )
        }
        val response=JSONObject(responseText ?: throw IOException("播放接口未返回响应"))
        val code=response.optInt("Code",response.optInt("code",0))
        if(code!=0) throw IOException("播放接口暂不可用（错误码 $code）")
        val data=response.optJSONObject("data") ?: throw IOException("播放信息为空")
        // New GET responses wrap entries in data.video_model_data; legacy POST responses
        // use data[videoId] or a direct video_model wrapper.
        val map=data.optJSONObject("video_model_data") ?: data
        val entry=map.optJSONObject(id)
            ?: map.keys().asSequence().mapNotNull { map.optJSONObject(it) }.firstOrNull()
            ?: map
        val modelValue=entry.opt("video_model") ?: map.opt("video_model") ?: data.opt("video_model")
        val model=when(modelValue) {
            is JSONObject -> modelValue
            is String -> runCatching { JSONObject(modelValue) }.getOrElse { throw IOException("播放模型格式异常") }
            else -> when {
                entry.has("video_list") || entry.has("fallback_api") -> entry
                map.has("video_list") || map.has("fallback_api") -> map
                else -> throw IOException("播放信息为空")
            }
        }
        val rawFallback=model.opt("fallback_api")
        val fallback=when(rawFallback) {
            is JSONObject -> rawFallback.str("fallback_api").ifEmpty { rawFallback.str("url") }
            is JSONArray -> rawFallback.optString(0)
            is String -> rawFallback
            else -> ""
        }.let { value ->
            if(value.startsWith("{")) runCatching { JSONObject(value).str("fallback_api") }.getOrDefault(value) else value
        }
        var info=if(fallback.startsWith("https://") || fallback.startsWith("http://")) {
            val outer=JSONObject(fetchFallback(fallback))
            outer.optJSONObject("video_info")?.optJSONObject("data")
                ?: outer.optJSONObject("data")
                ?: outer.optJSONObject("video_info")
                ?: outer
        } else model
        var videoList=when(val raw=info.opt("video_list") ?: model.opt("video_list")) {
            is JSONObject -> raw
            is JSONArray -> JSONObject().apply { for(i in 0 until raw.length()) raw.optJSONObject(i)?.let { put(i.toString(),it) } }
            else -> throw IOException("没有可用清晰度")
        }
        val offered=videoList.keys().asSequence().mapNotNull { videoList.optJSONObject(it) }.toList()
        if (fallback.startsWith("https://") && offered.isNotEmpty() && offered.all { row ->
                val codec=row.optJSONObject("video_meta")?.str("codec_type").orEmpty()
                    .ifEmpty { row.str("codec_type") }.lowercase(Locale.ROOT)
                row.str("main_url").isNotBlank() && codec in setOf("bytevc2","bvc2")
            }) {
            // Match N3urda: only request the codec override when every offered rendition is ByteVC2.
            val compatible=fallback.toHttpUrl().newBuilder()
                .setQueryParameter("codec_type","0")
                .setQueryParameter("force_codec","h264")
                .build().toString()
            val forced=JSONObject(fetchFallback(compatible))
            info=forced.optJSONObject("video_info")?.optJSONObject("data")
                ?: forced.optJSONObject("data")
                ?: forced.optJSONObject("video_info")
                ?: forced
            videoList=info.optJSONObject("video_list") ?: throw IOException("强制兼容编码后没有可用清晰度")
        }
        // Nested video_meta is authoritative on newer responses; retain the flat-field
        // fallback for older responses. H.264/AVC1 is preferred when any such variant exists.
        val rows=videoList.keys().asSequence().mapNotNull { k -> videoList.optJSONObject(k)?.let { k to it } }
            .filter { it.second.str("main_url").isNotEmpty() }
            .map { (k,o) ->
                val meta=o.optJSONObject("video_meta")
                    ?: o.str("video_meta").takeIf { it.startsWith("{") }?.let { runCatching { JSONObject(it) }.getOrNull() }
                    ?: JSONObject()
                val codec=meta.str("codec_type").ifEmpty { o.str("codec_type") }.lowercase(Locale.ROOT)
                val definition=meta.str("definition").ifEmpty { o.str("definition") }
                val height=meta.str("vheight").ifEmpty { o.str("vheight") }.toIntOrNull() ?: 0
                val width=meta.str("vwidth").ifEmpty { o.str("vwidth") }.toIntOrNull() ?: 0
                val quality=playbackQualityNumber(definition).takeIf { it>0 }
                    ?: if(width>0 && height>0) (if(width<height) width else height) else playbackQualityNumber(o.str("quality_desc").ifEmpty { o.str("height").ifEmpty { o.str("vheight").ifEmpty { o.str("quality").ifEmpty { k } } } })
                Triple(quality,codec,o)
            }.sortedByDescending { it.first }.toList()
        val playable=rows.filter { it.second !in setOf("bytevc2","bvc2") }
        val h264=playable.filter { it.second=="h264" || it.second=="avc1" }
        // 高清优先按分辨率排序，编码仅用于同清晰度时的优先级；兼容优先继续优先 H.264。
        val pool=if(maxQuality==PlaybackQualityMode.AUTO_HIGH) {
            playable.sortedWith(compareByDescending<Triple<Int,String,JSONObject>> { it.first }
                .thenBy { if(it.second=="h264" || it.second=="avc1") 0 else 1 })
        } else if(h264.isNotEmpty()) h264 else playable
        val portrait=rows.firstNotNullOfOrNull { row ->
            val meta=row.third.optJSONObject("video_meta") ?: JSONObject()
            val width=meta.str("vwidth").ifEmpty { row.third.str("vwidth") }.toIntOrNull() ?: 0
            val height=meta.str("vheight").ifEmpty { row.third.str("vheight") }.toIntOrNull() ?: 0
            if(width>0 && height>0) height>width else null
        }
        val selectionLimit=PlaybackQualityMode.limit(maxQuality,portrait)
        val selected=pool.firstOrNull { it.first in 1..selectionLimit }
            ?: pool.lastOrNull()
            ?: throw UnplayableVideoException("本集片源暂不兼容，请播放下一集")
        val item=selected.third
        val itemMeta=item.optJSONObject("video_meta") ?: JSONObject()
        var url=cleanUrl(item.str("main_url"))
        val seed=info.str("key_seed").ifEmpty { model.str("key_seed") }
        if(seed.isNotEmpty()) url=MediaCrypto.decryptUrl(url,decode64(seed))
        if(!url.startsWith("https://") && !url.startsWith("http://")) throw IOException("播放地址无效")
        val declaredMime=item.str("mime_type").ifEmpty { item.str("mime") }
            .ifEmpty { itemMeta.str("mime_type") }.lowercase(Locale.ROOT)
        val urlPath=runCatching { java.net.URI(url).path.orEmpty().lowercase(Locale.ROOT) }.getOrDefault("")
        // RemoteVideo performs MP4 sample parsing/decryption before ExoPlayer sees the bytes.
        // Do not mislabel an HLS playlist or another container as MP4: that path needs a different
        // data source and media-source pipeline, so fail clearly until that pipeline is implemented.
        if(declaredMime.contains("mpegurl") || declaredMime.contains("m3u8") || urlPath.endsWith(".m3u8")) {
            throw UnsupportedVideoFormatException("当前片源为 HLS 格式，现有加密分段播放链路暂不支持")
        }
        val unsupportedExtension=listOf(".mpd",".ts",".webm",".mkv",".avi").firstOrNull { urlPath.endsWith(it) }
        if(unsupportedExtension!=null) {
            throw UnsupportedVideoFormatException("当前片源容器 $unsupportedExtension 暂不支持，现有播放链路需要 MP4 资源")
        }
        if(declaredMime.isNotBlank() && !declaredMime.contains("mp4") && !declaredMime.contains("octet-stream")) {
            throw UnsupportedVideoFormatException("当前片源格式暂不支持：$declaredMime")
        }
        val mimeType="video/mp4"
        val encryption=item.optJSONObject("encrypt_info")
            ?: item.str("encrypt_info").takeIf { it.startsWith("{") }?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?: JSONObject()
        val spade=item.str("spade_a").ifEmpty { encryption.str("spade_a") }.ifEmpty { itemMeta.str("spade_a") }
        val key=spade.takeIf { it.isNotEmpty() }?.let(MediaCrypto::deriveKey)
        val quality=if(selected.first>0) "${selected.first}P"+(if(selected.first>selectionLimit) "（兼容资源）" else "") else "自动"
        return StreamInfo(url,key,quality,mimeType)
    }
    private fun fetchFallback(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", PlayerDeviceSource.FALLBACK_USER_AGENT)
            .header("Accept-Language", "zh-CN,zh;q=0.9")
            .get()
            .build()
        return RequestScope.execute(http.newCall(request)) { response ->
            if (!response.isSuccessful) throw IOException("fallback_api 请求失败（HTTP ${response.code}）")
            val body = response.body ?: throw IOException("fallback_api 响应为空")
            if (body.contentLength() > 16L * 1024 * 1024) throw IOException("fallback_api 响应过大")
            val bytes = body.bytes()
            if (bytes.size > 16 * 1024 * 1024) throw IOException("fallback_api 响应过大")
            String(bytes, Charsets.UTF_8)
        }
    }

    private data class PlayerRoute(
        val url: String,
        val body: ByteArray,
        val headers: Map<String, String>,
    )

    /**
     * Keep the two registered-player requests byte-for-byte compatible in shape with N3urda:
     * shortplay first, then Phoenix. P/phoenix playback module is not modified by this path.
     */
    private fun playerRoutes(id: String, device: PlayerDevice): List<PlayerRoute> = listOf(
        PlayerRoute(
            PlayerDeviceSource.substituteDevice(PlayerDeviceSource.SHORTPLAY_URL, device),
            PlayerDeviceSource.shortplayBody(id),
            mapOf(
                "User-Agent" to "com.dragon.read",
                "Content-Type" to "application/json; charset=utf-8",
            ),
        ),
        PlayerRoute(
            PlayerDeviceSource.substituteDevice(PlayerDeviceSource.PHOENIX_URL, device),
            PlayerDeviceSource.phoenixBody(id),
            mapOf(
                "User-Agent" to VendorConstants.VIDEO_UA,
                "Accept" to "application/json; charset=utf-8,application/x-protobuf",
                "Content-Type" to "application/json",
                "x-xs-from-web" to "0",
                "sdk-version" to "2",
            ),
        ),
    )

    private fun playerRequest(route: PlayerRoute): Request {
        val query = route.url.substringAfter('?', "")
        val signedHeaders = PlayerSigner.headers(query, route.body)
        val builder = Request.Builder()
            .url(route.url)
            .header("Accept-Language", "zh-CN,zh;q=0.9")
        route.headers.forEach { (key, value) -> builder.header(key, value) }
        signedHeaders.forEach { (key, value) -> builder.header(key, value) }
        return builder.post(route.body.toRequestBody("application/json; charset=UTF-8".toMediaType())).build()
    }

    private fun playerIdentityRejected(raw: String): Boolean = runCatching {
        val root = JSONObject(raw)
        root.optInt("code", root.optInt("Code", 0)) in setOf(10001, 10002, 110001)
    }.getOrDefault(false)

    private fun hasUsablePlayerModel(raw: String, id: String): Boolean = runCatching {
        val root = JSONObject(raw)
        if (root.optInt("code", root.optInt("Code", 0)) != 0) return@runCatching false
        val data = root.optJSONObject("data") ?: return@runCatching false
        val map = data.optJSONObject("video_model_data") ?: data
        val entry = map.optJSONObject(id)
            ?: map.keys().asSequence().mapNotNull { map.optJSONObject(it) }.firstOrNull()
            ?: map
        val rawModel = entry.opt("video_model") ?: map.opt("video_model") ?: data.opt("video_model")
        val model = when (rawModel) {
            is JSONObject -> rawModel
            is String -> runCatching { JSONObject(rawModel) }.getOrNull()
            else -> null
        } ?: entry
        model.has("video_list") || model.has("fallback_api") ||
            entry.has("video_list") || entry.has("fallback_api")
    }.getOrDefault(false)

    /**
     * N3urda's bounded legacy player ladder: shortplay first, then Phoenix multi_video_model.
     * Each route uses its own registered player device and Argus/Ladon/Gorgon signature.
     */
    private fun playbackResponse(id: String): String {
        var device = PlayerDeviceSource.obtain(http)
        var lastFailure: IOException? = null
        var sawEmptyOrRejected = false
        for (round in 0..1) {
            for (route in playerRoutes(id, device)) {
                RequestScope.ensureActive()
                try {
                    val raw = RequestScope.execute(http.newCall(playerRequest(route))) { response ->
                        if (!response.isSuccessful) {
                            if (response.code == 401 || response.code == 403) {
                                sawEmptyOrRejected = true
                            }
                            throw PlaybackHttpException(
                                response.code,
                                "兼容播放器接口请求失败（HTTP ${response.code}）",
                            )
                        }
                        response.body?.string().orEmpty()
                    }
                    if (raw.isBlank()) {
                        sawEmptyOrRejected = true
                        lastFailure = EmptyPlaybackResponseException()
                        playbackLog.warning("兼容播放器路由返回空响应")
                        continue
                    }
                    if (playerIdentityRejected(raw)) {
                        sawEmptyOrRejected = true
                        lastFailure = IOException("兼容播放器设备身份被上游拒绝")
                        continue
                    }
                    if (hasUsablePlayerModel(raw, id)) {
                        playbackLog.info("兼容播放器路由命中，PlayerSigner 请求成功")
                        return raw
                    }
                    lastFailure = IOException("兼容播放器路由返回了无可用模型的响应")
                    playbackLog.warning("兼容播放器路由响应缺少可用 video_model")
                } catch (error: java.io.InterruptedIOException) {
                    throw error
                } catch (error: IOException) {
                    lastFailure = error
                    playbackLog.warning("兼容播放器路由失败：${error.message}")
                }
            }
            if (round == 0 && sawEmptyOrRejected) {
                playbackLog.info("兼容播放器身份可能失效，重新注册一次独立 player 设备")
                PlayerDeviceSource.invalidate()
                device = PlayerDeviceSource.obtain(http)
                sawEmptyOrRejected = false
            } else break
        }
        throw lastFailure ?: EmptyPlaybackResponseException()
    }

    private fun isRetryablePlaybackFailure(error: IOException): Boolean = when (error) {
        is java.io.InterruptedIOException -> false
        is PlaybackHttpException -> error.statusCode == 408 || error.statusCode == 429 || error.statusCode >= 500
        is java.net.SocketTimeoutException,
        is java.net.ConnectException,
        is java.net.UnknownHostException -> true
        else -> error !is PlaybackHttpException
    }

    companion object {
    }
}


private fun playbackQualityNumber(value: String): Int =
    Regex("2160|1440|1080|720|576|540|480|360").find(value)?.value?.toInt()
        ?: mapOf("1920" to 1080, "1280" to 720, "1024" to 576, "854" to 480, "640" to 360)[value]
        ?: value.toIntOrNull() ?: 0
