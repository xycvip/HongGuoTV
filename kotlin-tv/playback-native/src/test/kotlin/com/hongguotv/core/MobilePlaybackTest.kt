// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import java.io.IOException
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MobilePlaybackTest {
    private val device = DeviceRegister.profile("test-device", "test-openudid") +
        mapOf("device_id" to "123456", "iid" to "654321")

    private fun model(id: String = "123", typed: Boolean = false, stringModel: Boolean = false): String {
        val value = JSONObject("""{"video_list":[{"video_meta":{"codec_type":"bytevc2","vwidth":720,"vheight":1280},"main_url":"https://example.org/unsupported"},{"video_meta":{"codec_type":"h264","vwidth":720,"vheight":1280},"main_url":"https://example.org/video"}]}""")
        val entries = JSONObject().put(id, JSONObject().put("video_model", if (stringModel) value.toString() else value))
        return JSONObject().put("code", 0).put("data", if (typed) JSONObject().put("1004", entries) else entries).toString()
    }

    private fun http(body: String, inspect: (okhttp3.Request) -> Unit = {}) =
        OkHttpClient.Builder().addInterceptor { chain ->
            inspect(chain.request())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body(body.toResponseBody()).build()
        }.build()

    private val signer = MobilePlaybackSigner { url, _, _ -> MobileSignedRequest(url, emptyMap()) }

    @Test fun supportsBothLiveResponseShapesWithoutSelectingOtherEpisodes() {
        for (typed in listOf(false, true)) for (stringModel in listOf(false, true)) {
            val rows = MobilePlayback.ladder("123", device, http(model(typed=typed, stringModel=stringModel)), signer, 1000).renditions
            assertEquals(1, rows.size)
            assertEquals(720, rows.single().quality)
            assertEquals("https://example.org/video", rows.single().url)
        }
        assertThrows(PlaybackSourceException::class.java) {
            MobilePlayback.ladder("123", device, http(model("456")), signer, 1000)
        }
    }

    @Test fun sendsTHLRegisteredProfileAndPlainJsonModelRequest() {
        var signed: ByteArray? = null
        var signedUrl: String? = null
        val signing = MobilePlaybackSigner { target, bytes, identity ->
            assertEquals(device, identity)
            signed = bytes.copyOf()
            signedUrl = target
            MobileSignedRequest(target, mapOf("X-Medusa" to "test-signature"))
        }
        MobilePlayback.ladder("123", device, http(model()) { request ->
            val bytes = Buffer().apply { request.body!!.writeTo(this) }.readByteArray()
            assertArrayEquals(signed!!, bytes)
            assertEquals(signedUrl, request.url.toString())
            assertEquals("api5-normal-sinfonlineb.fqnovel.com", request.url.host)
            assertEquals("/novel/player/multi_video_model/v1/", request.url.encodedPath)
            assertEquals("123456", request.url.queryParameter("device_id"))
            assertEquals("654321", request.url.queryParameter("iid"))
            assertEquals("test-device", request.url.queryParameter("cdid"))
            assertEquals("25053RT47C", request.url.queryParameter("device_type"))
            assertEquals("Redmi", request.url.queryParameter("device_brand"))
            assertEquals("73932", request.url.queryParameter("version_code"))
            assertEquals("7.3.9.32", request.url.queryParameter("version_name"))
            assertEquals("16", request.url.queryParameter("os_version"))
            assertEquals("update_64", request.url.queryParameter("channel"))
            assertEquals("1", request.url.queryParameter("ts"))
            assertNull(request.header("Content-Encoding"))
            assertEquals(DeviceRegister.USER_AGENT, request.header("User-Agent"))
            assertEquals("test-signature", request.header("X-Medusa"))
            assertEquals("0", request.header("x-xs-from-web"))
            assertEquals("2", request.header("sdk-version"))
            assertNull(request.header("Cookie"))
            assertEquals(digest("MD5", bytes).hex().uppercase(), request.header("x-ss-stub"))
            val body = JSONObject(bytes.toString(Charsets.UTF_8))
            assertEquals("123", body.getJSONObject("mixed_video_id_map").getJSONArray("1004").getString(0))
            assertEquals(1024, body.getJSONObject("biz_param").getInt("video_platform"))
            assertFalse(body.has("dr_scene"))
        }, signing, 1000)
    }

    @Test fun retriesNextTHLModelHostWhenPrimaryRouteHasNoModel() {
        val calls = mutableListOf<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            calls += request.url.host + request.url.encodedPath
            val code = if (calls.size == 1) 404 else 200
            val body = if (calls.size == 1) "" else model()
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(code).message("mock").body(body.toResponseBody()).build()
        }.build()
        val rows = MobilePlayback.ladder("123", device, client, signer, 1000).renditions
        assertEquals("https://example.org/video", rows.single().url)
        assertEquals(2, calls.size)
        assertEquals("api5-normal-sinfonlineb.fqnovel.com/novel/player/multi_video_model/v1/", calls[0])
        assertEquals("reading.snssdk.com/novel/player/multi_video_model/v1/", calls[1])
    }

    @Test fun singleEpisodeFallbackAcceptsDirectVideoModelShape() {
        val calls = mutableListOf<String>()
        val direct = JSONObject("""{"video_list":[{"video_meta":{"codec_type":"h264","vwidth":720,"vheight":1280},"main_url":"https://example.org/single.mp4"}]}""")
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            calls += request.url.host + request.url.encodedPath
            val body = if (calls.size < 3) "" else JSONObject()
                .put("code", 0).put("data", JSONObject().put("video_model", direct)).toString()
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(if (calls.size < 3) 404 else 200).message("mock")
                .body(body.toResponseBody()).build()
        }.build()
        val rows = MobilePlayback.ladder("123", device, client, signer, 1000).renditions
        assertEquals("https://example.org/single.mp4", rows.single().url)
        assertTrue(calls[2].endsWith("/novel/player/video_model/v1/"))
    }

    @Test fun rejectsBusinessErrorsAndOversizeResponse() {
        val error = assertThrows(PlaybackSourceException::class.java) {
            MobilePlayback.ladder("123", device, http("""{"code":110001}"""), signer, 1000)
        }
        assertEquals(110001, error.upstreamCode)
        assertThrows(IOException::class.java) {
            MobilePlayback.ladder("123", device, http(" ".repeat(2 * 1024 * 1024 + 1)), signer, 1000)
        }
    }
}
