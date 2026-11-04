// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import okhttp3.OkHttpClient
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

internal class PlaybackRepository(
    private val http: OkHttpClient,
    private val clock: () -> Long,
    private val playbackSession: PlaybackDeviceSession,
    private val signer: Signer,
    private val site: String,
    private val requestText: (String, Signer.Request?) -> String
) {
    private val playbackHosts = listOf(
        "api5-normal-sinfonlineb.fqnovel.com",
        "api5-normal-sinfonlinea.fqnovel.com"
    )
    private val playbackLog = java.util.logging.Logger.getLogger("com.hongguotv.core.playback")

    private fun tabVideoModelResponse(id: String, identity: PlaybackDeviceSession.Profile, host: String): String? {
        val query = Signer.encode(identity.values + ("_rticket" to clock().toString()))
        val extra = listOf(
            "video_id" to id,
            "video_id_type" to "0",
            "device_level" to "1",
            "need_all_video_definition" to "1",
            "need_mp4_align" to "0",
            "use_os_player" to "0",
            "video_platform" to "1024",
            "source" to "0",
            "from_video_id" to ""
        ).joinToString("&") { (key, value) -> "${Signer.encode(key)}=${Signer.encode(value)}" }
        val request = Request.Builder()
            .url("https://$host/reading/bookapi/video_tab/multi_video_model/v1/?$query&$extra")
            .header("User-Agent", PLAYBACK_UA)
            .header("Accept", "application/json; charset=utf-8")
            .apply { if (identity.cookie.isNotBlank()) header("Cookie", identity.cookie) }
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
     *  The player API only answers registered anonymous devices: an unregistered `device_id`/`install_id`
     *  pair gets `HTTP 200` with a zero-byte body. So the identity from [PlaybackDeviceSession] is written
     *  into the query together with `_rticket`, and the response's `Set-Cookie` values ride along as a
     *  `Cookie` header. No signature headers are involved in this call. */
    fun stream(id: String, maxQuality: Int = 1080): StreamInfo {
        require(id.matches(Regex("[0-9]{1,30}")))
        val primary=object: StreamSource {
            override val name="multi-video-model"
            override fun stream(id:String,maxQuality:Int)=streamMultiVideoModel(id,maxQuality)
        }
        return StreamScheduler(listOf(primary,PublicMp4StreamSource(http,VendorConstants.VIDEO_UA,site)))
            .stream(id,maxQuality)
    }

    /** Primary App playback channel. The scheduler invokes the public MP4 fallback only on failure. */
    private fun streamMultiVideoModel(id: String, maxQuality: Int): StreamInfo {
        require(id.matches(Regex("[0-9]{1,30}")))
        val payload="""{"biz_param":{"detail_page_version":0,"device_level":3,"disable_digg_stat":false,"need_all_video_definition":true,"need_mp4_align":false,"use_os_player":false,"use_server_dns":false,"video_platform":1024},"mixed_video_id_map":{"1004":["$id"]}}""".toByteArray()
        // Prefer the newer GET endpoint. If it is unavailable, rejected, empty or structurally
        // incomplete, fall back to the existing POST implementation and its identity recovery.
        val identity=playbackSession.profile(http)
        var getResponse: String? = null
        var getFailure: IOException? = null
        for (host in playbackHosts) {
            RequestScope.ensureActive()
            try {
                getResponse = tabVideoModelResponse(id, identity, host)
                if (getResponse != null) {
                    playbackLog.info("GET 播放接口命中域名 $host")
                    break
                }
                getFailure = IOException("新版播放接口在 $host 返回空响应或无效模型")
            } catch (error: java.io.InterruptedIOException) {
                throw error
            } catch (error: IOException) {
                getFailure = error
                playbackLog.warning("GET 播放接口域名 $host 失败：${error.message}")
                if (!isRetryablePlaybackFailure(error)) break
            }
        }
        if (getResponse == null && getFailure != null) {
            playbackLog.info("GET 播放接口不可用，切换旧 POST 兼容通道")
        }
        val responseText=getResponse ?: playbackResponse(id,payload)
        val response=JSONObject(responseText)
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
        val info=if(fallback.startsWith("https://") || fallback.startsWith("http://")) {
            val outer=JSONObject(requestText(fallback,null))
            outer.optJSONObject("video_info")?.optJSONObject("data")
                ?: outer.optJSONObject("data")
                ?: outer.optJSONObject("video_info")
                ?: outer
        } else model
        val videoList=when(val raw=info.opt("video_list") ?: model.opt("video_list")) {
            is JSONObject -> raw
            is JSONArray -> JSONObject().apply { for(i in 0 until raw.length()) raw.optJSONObject(i)?.let { put(i.toString(),it) } }
            else -> throw IOException("没有可用清晰度")
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
                val quality=ContentRepository.qualityNumber(definition).takeIf { it>0 }
                    ?: if(width>0 && height>0) (if(width<height) width else height) else ContentRepository.qualityNumber(o.str("quality_desc").ifEmpty { o.str("height").ifEmpty { o.str("vheight").ifEmpty { o.str("quality").ifEmpty { k } } } })
                Triple(quality,codec,o)
            }.sortedByDescending { it.first }.toList()
        val playable=rows.filter { it.second !in setOf("bytevc2","bvc2") }
        val h264=playable.filter { it.second=="h264" || it.second=="avc1" }
        val pool=if(h264.isNotEmpty()) h264 else playable
        val selected=pool.firstOrNull { it.first in 1..maxQuality }
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
        val quality=if(selected.first>0) "${selected.first}P"+(if(selected.first>maxQuality) "（兼容资源）" else "") else "自动"
        return StreamInfo(url,key,quality,mimeType)
    }
    private fun playerRequest(id: String,payload: ByteArray,identity: PlaybackDeviceSession.Profile,host: String): Request {
        val query=Signer.encode(identity.values+("_rticket" to clock().toString()))
        return Request.Builder()
            .url("https://$host/novel/player/multi_video_model/v1/?$query")
            .header("User-Agent",PLAYBACK_UA)
            .header("Accept","application/json; charset=utf-8,application/x-protobuf")
            .apply { if(identity.cookie.isNotBlank()) header("Cookie",identity.cookie) }
            .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
    }
    /**
     * 旧 POST 是只读的播放模型查询，不会创建订单或写入用户数据，因此允许有限重试。
     * 每轮最多尝试两个官方域名；只有空响应或明确身份失效（401/403）才重新注册一次。
     * 网络/超时/服务端故障只轮换域名，不反复刷新设备身份。
     */
    private fun playbackResponse(id: String,payload: ByteArray): String {
        var identity=playbackSession.profile(http)
        var lastFailure: IOException? = null
        var sawEmpty = false
        repeat(2) { round ->
            for (host in playbackHosts) {
                RequestScope.ensureActive()
                try {
                    val body=RequestScope.execute(http.newCall(playerRequest(id,payload,identity,host))) { response ->
                        if (!response.isSuccessful) throw PlaybackHttpException(
                            response.code, "播放接口请求失败（HTTP ${response.code}）"
                        )
                        response.body?.string().orEmpty()
                    }
                    if (body.isNotBlank()) {
                        playbackLog.info("POST 播放接口命中域名 $host")
                        return body
                    }
                    sawEmpty = true
                    lastFailure = EmptyPlaybackResponseException()
                    playbackLog.warning("POST 播放接口域名 $host 返回空响应")
                } catch (error: java.io.InterruptedIOException) {
                    throw error
                } catch (error: IOException) {
                    lastFailure = error
                    playbackLog.warning("POST 播放接口域名 $host 失败：${error.message}")
                    if (!isRetryablePlaybackFailure(error) && !isIdentityFailure(error)) throw error
                }
            }
            if (round == 0) {
                if (sawEmpty || lastFailure?.let(::isIdentityFailure) == true) {
                    playbackLog.info("播放身份可能失效，重新注册一次匿名设备身份")
                    identity = playbackSession.renew(http)
                    sawEmpty = false
                } else if (lastFailure != null) {
                    // 域名切换已完成；仅进行一次短退避后再试，不做无意义的身份重注册。
                    try {
                        Thread.sleep(250L)
                    } catch (error: InterruptedException) {
                        Thread.currentThread().interrupt()
                        throw java.io.InterruptedIOException("播放请求已取消")
                    }
                }
            }
        }
        throw lastFailure ?: EmptyPlaybackResponseException()
    }

    private fun isIdentityFailure(error: IOException): Boolean =
        error is PlaybackHttpException && error.statusCode in setOf(401, 403)

    private fun isRetryablePlaybackFailure(error: IOException): Boolean = when (error) {
        is java.io.InterruptedIOException -> false
        is PlaybackHttpException -> error.statusCode == 408 || error.statusCode == 429 || error.statusCode >= 500
        is java.net.SocketTimeoutException,
        is java.net.ConnectException,
        is java.net.UnknownHostException -> true
        else -> error !is PlaybackHttpException
    }

    companion object {
        // 播放接口认的客户端版本与内容接口不同，沿用抓包样本里 7.1.5.32 + tt-ok 的 UA。
        private const val PLAYBACK_UA="com.phoenix.read/71532 (Linux; U; Android 9; SM-N9860; Build/PQ3A.190705.10241111;tt-ok/3.12.13.20)"
    }
}
