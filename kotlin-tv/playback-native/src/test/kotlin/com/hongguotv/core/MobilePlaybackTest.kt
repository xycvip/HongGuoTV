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
    private val device = ThlDeviceRegister.profile() +
        linkedMapOf("iid" to "654321", "device_id" to "123456", "cdid" to "test-device")

    private fun model(id: String = "123", typed: Boolean = false, stringModel: Boolean = false): String {
        val value = JSONObject("""{"video_list":[{"video_meta":{"codec_type":"bytevc2","vwidth":720,"vheight":1280},"main_url":"https://cdn.fqnovelvod.com/unsupported"},{"video_meta":{"codec_type":"h264","vwidth":720,"vheight":1280},"main_url":"https://cdn.fqnovelvod.com/video"}]}""")
        val entries = JSONObject().put(id, JSONObject().put("video_model", if (stringModel) value.toString() else value))
        return JSONObject().put("code", 0).put("data", if (typed) JSONObject().put("1004", entries) else entries).toString()
    }

    private fun http(body: String, inspect: (okhttp3.Request) -> Unit = {}) =
        OkHttpClient.Builder().addInterceptor { chain ->
            inspect(chain.request())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body(body.toResponseBody()).build()
        }.build()

    private fun mockSigned(url: String, identity: Map<String, String>, now: Long, headers: Map<String, String> = emptyMap()): MobileSignedRequest {
        val query = LinkedHashMap(identity).apply {
            put("ts", (now / 1000).toString())
            put("_rticket", now.toString())
        }
        return MobileSignedRequest("$url?${Signer.encode(query)}", headers)
    }

    private val signer = MobilePlaybackSigner { url, _, identity, now -> mockSigned(url, identity, now) }

    @Test fun supportsBothLiveResponseShapesWithoutSelectingOtherEpisodes() {
        for (typed in listOf(false, true)) for (stringModel in listOf(false, true)) {
            val rows = MobilePlayback.ladder("123", device, http(model(typed=typed, stringModel=stringModel)), signer, 1000).renditions
            assertEquals(1, rows.size)
            assertEquals(720, rows.single().quality)
            assertEquals("https://cdn.fqnovelvod.com/video", rows.single().url)
        }
        assertThrows(PlaybackSourceException::class.java) {
            MobilePlayback.ladder("123", device, http(model("456")), signer, 1000)
        }
    }

    @Test fun sendsTHLRegisteredProfileAndPlainJsonModelRequest() {
        var signed: ByteArray? = null
        var signedUrl: String? = null
        val signing = MobilePlaybackSigner { target, bytes, identity, now ->
            assertEquals(device, identity)
            signed = bytes.copyOf()
            val result = mockSigned(target, identity, now, mapOf("X-Medusa" to "test-signature"))
            signedUrl = result.url
            result
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
            assertEquals(ThlDeviceRegister.USER_AGENT, request.header("User-Agent"))
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

    @Test fun mobileV5PlaybackSigningKeepsTheFullTHLSignatureHeaderSet() {
        val signed = Signer(mobileV5 = true, mobileV5QueryDeviceBrand = true).signPlayback(
            "https://api5-normal-sinfonlineb.fqnovel.com/novel/player/multi_video_model/v1/",
            device,
            """{"biz_param":{"video_platform":1024}}""".toByteArray(),
            now = 1_000L,
        )
        for (header in listOf("x-gorgon", "x-khronos", "x-ladon", "x-argus", "x-helios", "x-medusa")) {
            assertTrue("missing THL signature header: $header", signed.headers.keys.any { it.equals(header, true) })
        }
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
        assertEquals("https://cdn.fqnovelvod.com/video", rows.single().url)
        assertEquals(2, calls.size)
        assertEquals("api5-normal-sinfonlineb.fqnovel.com/novel/player/multi_video_model/v1/", calls[0])
        assertEquals("reading.snssdk.com/novel/player/multi_video_model/v1/", calls[1])
    }

    @Test fun singleEpisodeFallbackAcceptsDirectVideoModelShape() {
        val calls = mutableListOf<String>()
        val direct = JSONObject("""{"video_list":[{"video_meta":{"codec_type":"h264","vwidth":720,"vheight":1280},"main_url":"https://cdn.fqnovelvod.com/single.mp4"}]}""")
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
        assertEquals("https://cdn.fqnovelvod.com/single.mp4", rows.single().url)
        assertTrue(calls[2].endsWith("/novel/player/video_model/v1/"))
    }

    @Test fun followsFallbackApiWhenModelHasNoDirectVideoUrls() {
        val calls = mutableListOf<String>()
        val fallbackModel = JSONObject("""{"video_list":[{"video_meta":{"codec_type":"h264","vwidth":720,"vheight":1280},"main_url":"https://cdn.fqnovelvod.com/fallback.mp4"}]}""")
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            calls += request.url.toString()
            val body = if (request.url.encodedPath == "/fallback/video") {
                assertNull(request.url.queryParameter("force_fids"))
                assertEquals("mp4", request.url.queryParameter("format_type"))
                assertEquals("1", request.url.queryParameter("codec_type"))
                JSONObject().put("code", 0)
                    .put("video_info", JSONObject().put("code", 0).put("data", fallbackModel)).toString()
            } else {
                JSONObject().put("code", 0).put("data", JSONObject().put("123",
                    JSONObject().put("video_model", JSONObject().put("fallback_api",
                        "https://api5-normal.fqnovel.com/fallback/video?force_fids=1")))).toString()
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body(body.toResponseBody()).build()
        }.build()
        val rows = MobilePlayback.ladder("123", device, client, signer, 1000).renditions
        assertEquals("https://cdn.fqnovelvod.com/fallback.mp4", rows.single().url)
        assertEquals(2, calls.size)
    }

    @Test fun readsBackupUrlListWhenPrimaryAddressIsMissing() {
        val source = JSONObject("""{"video_list":[{"video_meta":{"codec_type":"h264","vwidth":720,"vheight":1280},"backup_url_list":["https://cdn.fqnovelvod.com/backup.mp4"]}]}""")
        val rows = PlaybackModels.renditions(source)
        assertEquals("https://cdn.fqnovelvod.com/backup.mp4", rows.single().url)
    }

    @Test fun rejectsMediaUrlsOutsideTheTHLHostAllowlist() {
        val source = JSONObject("""{"video_list":[{"video_meta":{"codec_type":"h264","vwidth":720,"vheight":1280},"main_url":"https://example.org/video.mp4"}]}""")
        assertTrue(PlaybackModels.renditions(source).isEmpty())
        val insecure = JSONObject("""{"video_list":[{"video_meta":{"codec_type":"h264","vwidth":720,"vheight":1280},"main_url":"http://cdn.fqnovelvod.com/video.mp4"}]}""")
        assertTrue(PlaybackModels.renditions(insecure).isEmpty())
    }

    @Test fun rejectsFallbackUrlsOutsideTheTHLHostAllowlist() {
        val source = JSONObject("""{"fallback_api":"https://evil.example.org/fallback"}""")
        assertNull(PlaybackModels.fallback(source))
    }

    @Test fun followsFallbackGetRedirectsOnlyWithinTHLHosts() {
        val fallbackModel = """{"video_list":[{"video_meta":{"codec_type":"h264","vwidth":720,"vheight":1280},"main_url":"https://cdn.fqnovelvod.com/fallback.mp4"}]}"""
        val calls = mutableListOf<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            calls += request.url.toString()
            val response = when {
                request.method == "POST" -> """{"code":0,"data":{"123":{"video_model":{"fallback_api":"https://api5-normal.fqnovel.com/fallback/video?force_fids=1"}}}}"""
                request.url.encodedPath == "/fallback/video" -> null
                else -> """{"code":0,"video_info":{"code":0,"data":$fallbackModel}}"""
            }
            if (request.method == "GET" && request.url.encodedPath == "/fallback/video") {
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                    .code(302).message("Found")
                    .header("Location", "https://edge.fqnovel.com/fallback/final")
                    .body("".toResponseBody()).build()
            } else {
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                    .code(200).message("OK").body((response ?: "{}").toResponseBody()).build()
            }
        }.build()
        val rows = MobilePlayback.ladder("123", device, client, signer, 1000).renditions
        assertEquals("https://cdn.fqnovelvod.com/fallback.mp4", rows.single().url)
        assertEquals(3, calls.size)
        assertTrue(calls[2].startsWith("https://edge.fqnovel.com/"))
    }

    @Test fun blocksFallbackRedirectsOutsideTHLHosts() {
        val calls = mutableListOf<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            calls += request.url.toString()
            val response = if (request.method == "POST") {
                """{"code":0,"data":{"123":{"video_model":{"fallback_api":"https://api5-normal.fqnovel.com/fallback/video"}}}}"""
            } else ""
            if (request.method == "GET") {
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                    .code(302).message("Found")
                    .header("Location", "https://evil.example.org/fallback")
                    .body("".toResponseBody()).build()
            } else {
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                    .code(200).message("OK").body(response.toResponseBody()).build()
            }
        }.build()
        val error = assertThrows(PlaybackSourceException::class.java) {
            MobilePlayback.ladder("123", device, client, signer, 1000)
        }
        assertEquals(PlaybackSourceException.Reason.REJECTED, error.reason)
        assertEquals(2, calls.size)
    }

    @Test fun rejectsSignedPostRedirectsWithoutFollowingThem() {
        val calls = mutableListOf<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            calls += request.url.toString()
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(302).message("Found")
                .header("Location", "https://api5-normal.fqnovel.com/redirect")
                .body("".toResponseBody()).build()
        }.build()
        val error = assertThrows(PlaybackSourceException::class.java) {
            MobilePlayback.ladder("123", device, client, signer, 1000)
        }
        assertEquals(PlaybackSourceException.Reason.REJECTED, error.reason)
        assertEquals(1, calls.size)
    }

    @Test fun retriesOnlyTHLRecoverableHttpStatuses() {
        val success = """{"code":0,"data":{"123":{"video_model":{"video_list":[{"video_meta":{"codec_type":"h264","vwidth":720,"vheight":1280},"main_url":"https://cdn.fqnovelvod.com/episode.mp4"}]}}}}"""
        var retryCalls = 0
        val retryClient = OkHttpClient.Builder().addInterceptor { chain ->
            retryCalls++
            val request = chain.request()
            if (retryCalls == 1) {
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                    .code(404).message("Not Found").body("".toResponseBody()).build()
            } else {
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                    .code(200).message("OK").body(success.toResponseBody()).build()
            }
        }.build()
        val recovered = MobilePlayback.ladder("123", device, retryClient, signer, 1000)
        assertEquals("https://cdn.fqnovelvod.com/episode.mp4", recovered.renditions.single().url)
        assertEquals(2, retryCalls)

        var terminalCalls = 0
        val terminalClient = OkHttpClient.Builder().addInterceptor { chain ->
            terminalCalls++
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(429).message("Too Many Requests").body("".toResponseBody()).build()
        }.build()
        val error = assertThrows(PlaybackSourceException::class.java) {
            MobilePlayback.ladder("123", device, terminalClient, signer, 1000)
        }
        assertEquals(PlaybackSourceException.Reason.HTTP_REJECTED, error.reason)
        assertEquals(429, error.upstreamCode)
        assertEquals(1, terminalCalls)
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
