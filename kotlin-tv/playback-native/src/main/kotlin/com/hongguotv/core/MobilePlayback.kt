// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit
import okhttp3.CookieJar
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/** Signing returns the final URL because v5 selects its digest branch through the request ticket. */
internal data class MobileSignedRequest(val url: String, val headers: Map<String, String>)

internal fun interface MobilePlaybackSigner {
    fun sign(url: String, body: ByteArray, device: Map<String, String>): MobileSignedRequest
}

internal object MobilePlayback {
    private const val USER_AGENT = DeviceRegister.USER_AGENT
    private const val ROUTE_TIMEOUT_MS = 3500L
    private const val RESPONSE_LIMIT = 2L * 1024 * 1024

    private data class ModelRoute(val url: String, val single: Boolean)

    private val routes = listOf(
        ModelRoute("https://api5-normal-sinfonlineb.fqnovel.com/novel/player/multi_video_model/v1/", false),
        ModelRoute("https://reading.snssdk.com/novel/player/multi_video_model/v1/", false),
        ModelRoute("https://reading.snssdk.com/novel/player/video_model/v1/", true),
        ModelRoute("https://api5-normal.fqnovel.com/novel/player/multi_video_model/v1/", false),
    )

    private val v5Signer = Signer(mobileV5 = true)
    val signer = MobilePlaybackSigner { url, body, _ ->
        val parsed = url.toHttpUrl()
        val values = parsed.queryParameterNames.associateWith { parsed.queryParameter(it)!! }
        val now = values.getValue("_rticket").toLong()
        val request = v5Signer.signPlayback(parsed.newBuilder().query(null).build().toString(), values, body, now)
        MobileSignedRequest(request.url, request.headers)
    }

    fun ladder(
        id: String,
        device: Map<String, String>,
        http: OkHttpClient,
        signer: MobilePlaybackSigner,
        now: Long,
    ): StreamLadder {
        require(id.matches(Regex("[0-9]{1,30}")))
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12)
        var lastError: IOException? = null

        for (route in routes) {
            RequestScope.ensureActive()
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) break
            try {
                val model = requestModel(id, device, http, signer, route, now, remaining)
                val rows = PlaybackModels.renditions(model)
                if (rows.isEmpty()) throw PlaybackSourceException(PlaybackSourceException.Reason.NO_PLAYABLE_SOURCE)
                return StreamLadder(rows)
            } catch (error: InterruptedIOException) {
                throw error
            } catch (error: IOException) {
                if (error is PlaybackSourceException &&
                    error.reason == PlaybackSourceException.Reason.REJECTED
                ) throw error
                if (error is PlaybackSourceException &&
                    error.reason == PlaybackSourceException.Reason.HTTP_REJECTED &&
                    error.upstreamCode in setOf(401, 403)
                ) throw error
                lastError = error
            }
        }
        throw lastError ?: IOException("官方播放接口请求超时")
    }

    private fun requestModel(
        id: String,
        device: Map<String, String>,
        http: OkHttpClient,
        signer: MobilePlaybackSigner,
        route: ModelRoute,
        now: Long,
        remainingNanos: Long,
    ): JSONObject {
        RequestScope.ensureActive()
        val queryKeys = listOf(
            "ac", "channel", "aid", "app_name", "version_code", "version_name",
            "device_platform", "os", "ssmix", "device_type", "device_brand",
            "language", "os_api", "os_version", "manifest_version_code",
            "resolution", "dpi", "update_version_code", "host_abi",
            "dragon_device_type", "pv_player", "compliance_status",
            "need_personal_recommend", "player_so_load", "is_android_pad_screen",
            "cdid", "iid", "device_id",
        )
        val query = linkedMapOf<String, String>()
        queryKeys.forEach { key -> device[key]?.let { query[key] = it } }
        query["ts"] = (now / 1000).toString()
        query["_rticket"] = now.toString()

        val url = route.url.toHttpUrl().newBuilder()
            .apply { query.forEach { (key, value) -> addQueryParameter(key, value) } }
            .build()
        val biz = JSONObject()
            .put("detail_page_version", 0)
            .put("device_level", 3)
            .put("disable_digg_stat", false)
            .put("need_all_video_definition", true)
            .put("need_mp4_align", false)
            .put("use_os_player", false)
            .put("use_server_dns", false)
            .put("video_platform", 1024)
        val payload = if (route.single) {
            JSONObject()
                .put("video_id", id)
                .put("content_type", 1)
                .put("biz_param", biz)
        } else {
            JSONObject()
                .put("biz_param", biz)
                .put("mixed_video_id_map", JSONObject().put("1004", org.json.JSONArray().put(id)))
        }
        val bytes = payload.toString().toByteArray(Charsets.UTF_8)
        val signed = signer.sign(url.toString(), bytes, device)
        val timeout = minOf(remainingNanos, TimeUnit.MILLISECONDS.toNanos(ROUTE_TIMEOUT_MS))
        if (timeout <= 0) throw InterruptedIOException("播放请求超时")
        val transport = http.newBuilder()
            .callTimeout(timeout, TimeUnit.NANOSECONDS)
            .cookieJar(CookieJar.NO_COOKIES)
            .build()
        val request = Request.Builder().url(signed.url).apply {
            signed.headers.forEach { (key, value) -> header(key, value) }
            header("User-Agent", USER_AGENT)
            header("Accept", "application/json; charset=utf-8,application/x-protobuf")
            header("Content-Type", "application/json; charset=UTF-8")
            header("x-ss-req-ticket", signed.url.toHttpUrl().queryParameter("_rticket").orEmpty())
            header("sdk-version", "2")
            header("x-xs-from-web", "0")
            header("x-tt-request-tag", "t=0;n=0")
            header("passport-sdk-version", "50561")
            header("x-vc-bdturing-sdk-version", "3.7.2.cn")
            header("x-tt-dt", "")
            post(bytes.toRequestBody("application/json; charset=utf-8".toMediaType()))
        }.build()

        return RequestScope.execute(transport.newCall(request)) { response ->
            if (!response.isSuccessful) {
                throw PlaybackSourceException(PlaybackSourceException.Reason.HTTP_REJECTED, response.code)
            }
            val body = response.body ?: throw PlaybackSourceException(PlaybackSourceException.Reason.INVALID_RESPONSE)
            if (body.contentLength() > RESPONSE_LIMIT) throw IOException("播放信息过大")
            val source = body.source()
            source.request(RESPONSE_LIMIT + 1)
            if (source.buffer.size > RESPONSE_LIMIT) throw IOException("播放信息过大")
            val text = source.readUtf8()
            if (text.isBlank()) throw EmptyPlaybackResponseException("stage=mobile-model")
            val root = try {
                JSONObject(text)
            } catch (_: Exception) {
                throw PlaybackSourceException(PlaybackSourceException.Reason.INVALID_RESPONSE)
            }
            PlaybackModels.checkStatus(root)
            val data = root.optJSONObject("data")
                ?: throw PlaybackSourceException(PlaybackSourceException.Reason.INVALID_RESPONSE)
            val entry = if (route.single) {
                data
            } else {
                data.optJSONObject("1004")?.optJSONObject(id) ?: data.optJSONObject(id)
            }
            PlaybackModels.objectValue(entry?.opt("video_model"))
                ?: throw PlaybackSourceException(PlaybackSourceException.Reason.NO_PLAYABLE_SOURCE)
        }
    }
}
