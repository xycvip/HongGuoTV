// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.UUID

/**
 * Read-only cross-repository detail endpoint probe.
 * Each candidate uses the request shape documented by its source implementation.
 */
internal object DetailApiProbe {
    private const val API_HOST = "api5-normal-sinfonlinea.fqnovel.com"
    private const val IMAGE_SHRINK = "W3siaW1hZ2VfdHlwZSI6MywiaW1hZ2Vfd2lkdGgiOjkwMCwic2hyaW5rX3R5cGUiOjN9LHsiaW1hZ2VfdHlwZSI6NCwiaW1hZ2Vfd2lkdGgiOjU0LCJzaHJpbmtfdHlwZSI6NH1d"

    private data class Endpoint(val label: String, val path: String, val body: (String) -> JSONObject)

    private val zhangBody: (String) -> JSONObject = { sid ->
        JSONObject()
            .put("series_id", sid)
            .put("biz_param", JSONObject()
                .put("detail_page_version", 0)
                .put("disable_digg_stat", false)
                .put("disable_video_relate_book", false)
                .put("image_shrink_datas_str", IMAGE_SHRINK)
                .put("need_all_video_definition", false)
                .put("need_mp4_align", false)
                .put("screen_width_px", "900")
                .put("source", 7)
                .put("use_os_player", false)
                .put("use_server_dns", false))
    }

    private val playerBody: (String) -> JSONObject = { sid ->
        JSONObject()
            .put("series_id", sid)
            .put("dr_scene", "player")
            .put("biz_param", JSONObject()
                .put("detail_page_version", 0)
                .put("need_all_video_definition", false)
                .put("need_mp4_align", false)
                .put("source", 7)
                .put("use_os_player", false)
                .put("use_server_dns", false))
    }

    private val endpoints = listOf(
        Endpoint("legacy signer video_detail/v1", "/novel/player/video_detail/v1/", { sid -> JSONObject().put("series_id", sid) }),
        Endpoint("zhangbaio multi_video_detail/v1", "/novel/player/multi_video_detail/v1/", zhangBody),
        Endpoint("zhangbaio preload", "/novel/player/multi_video_detail/preload/v1", { sid -> zhangBody(sid).put("dr_scene", "preload") }),
        Endpoint("openhongguo player scene", "/novel/player/multi_video_detail/player/v1", playerBody),
        Endpoint("dynamic_data scene", "/novel/player/multi_video_detail/dynamic_data/v1/", { sid -> playerBody(sid).put("dr_scene", "dynamic_data") }),
        Endpoint("detail scene", "/novel/player/multi_video_detail/detail/v1/", { sid -> playerBody(sid).put("dr_scene", "detail") })
    )

    fun run(repository: ContentRepository, specialId: String, expectedTitle: String, controlId: String) {
        println("PROBE_MATRIX|special=$specialId|expectedTitle=$expectedTitle|control=$controlId")
        val targets = listOf(specialId to "SPECIAL", controlId to "CONTROL").distinctBy { it.first }
        for ((sid, kind) in targets) {
            println("TARGET|kind=$kind|series_id=$sid")
            probePhoenix(repository, sid, expectedTitle, kind)
            for (endpoint in endpoints) probeLegacy(repository, sid, expectedTitle, kind, endpoint)
            probeWebsite(repository, sid, expectedTitle, kind, "/detail?series_id=${URLEncoder.encode(sid, "UTF-8")}")
            probeWebsite(repository, sid, expectedTitle, kind, "/player/${URLEncoder.encode(sid, "UTF-8")}")
        }
        println("PROBE_MATRIX|completed=true")
    }

    private fun probePhoenix(repository: ContentRepository, sid: String, expectedTitle: String, kind: String) {
        val started = System.currentTimeMillis()
        try {
            val method = ContentRepository::class.java.getDeclaredMethod("phoenixDetail", String::class.java)
            method.isAccessible = true
            val data = method.invoke(repository, sid) as JSONObject
            report("Phoenix video_detail/v1", kind, sid, expectedTitle, data, System.currentTimeMillis() - started)
        } catch (error: Throwable) {
            val cause = error.cause ?: error
            println("ENDPOINT|name=Phoenix video_detail/v1|kind=$kind|id=$sid|status=FAILED|reason=${safe(cause.message ?: cause.javaClass.simpleName)}|ms=${System.currentTimeMillis()-started}")
        }
    }

    private fun probeLegacy(repository: ContentRepository, sid: String, expectedTitle: String, kind: String, endpoint: Endpoint) {
        val started = System.currentTimeMillis()
        try {
            VendorConstants.regenerate()
            val body = endpoint.body(sid).toString().toByteArray(Charsets.UTF_8)
            val values = LinkedHashMap(VendorConstants.device).apply { put("cdid", UUID.randomUUID().toString()) }
            val signed = Signer().sign("https://$API_HOST${endpoint.path}", values, body)
            val request = Request.Builder().url(signed.url).apply {
                signed.headers.forEach { (key, value) -> header(key, value) }
            }.post(body.toRequestBody("application/json; charset=UTF-8".toMediaType())).build()
            val result = RequestScope.execute(repository.http.newCall(request)) { response ->
                val bytes = response.body?.bytes() ?: ByteArray(0)
                Triple(response.code, bytes, response.header("content-type").orEmpty())
            }
            if (result.second.isEmpty()) {
                println("ENDPOINT|name=${endpoint.label}|kind=$kind|id=$sid|status=EMPTY|http=${result.first}|bytes=0|ms=${System.currentTimeMillis()-started}")
                return
            }
            val root = JSONObject(String(result.second, Charsets.UTF_8))
            val code = root.opt("code")?.toString().orEmpty()
            val data = root.optJSONObject("data") ?: JSONObject()
            val detail = data.optJSONObject(sid)?.optJSONObject("video_data")
                ?: data.optJSONObject("video_data")
                ?: data.optJSONObject("series_detail")
                ?: data
            val title = firstText(detail, "series_title", "series_name", "title", "name")
            val episodes = episodeCount(detail)
            val exact = expectedTitle.isBlank() || kind == "CONTROL" || title == expectedTitle
            val classification = when {
                title.isNotBlank() && episodes > 0 -> "FULL_DETAIL"
                title.isNotBlank() -> "METADATA_ONLY"
                episodes > 0 -> "EPISODES_ONLY"
                else -> "NO_DETAIL"
            }
            val fields = detail.keys().asSequence().toList().sorted().joinToString(",")
            println("ENDPOINT|name=${endpoint.label}|kind=$kind|id=$sid|status=HTTP_${result.first}|code=$code|bytes=${result.second.size}|title=${safe(title)}|exact=${if(exact) "YES" else "NO"}|episodes=$episodes|episodeCnt=${detail.optInt("episode_cnt", 0)}|class=$classification|fields=${safe(fields)}|ms=${System.currentTimeMillis()-started}")
        } catch (error: Throwable) {
            val cause = error.cause ?: error
            println("ENDPOINT|name=${endpoint.label}|kind=$kind|id=$sid|status=FAILED|reason=${safe(cause.message ?: cause.javaClass.simpleName)}|ms=${System.currentTimeMillis()-started}")
        }
    }

    private fun probeWebsite(repository: ContentRepository, sid: String, expectedTitle: String, kind: String, route: String) {
        val label = if (route.startsWith("/player/")) "Website player route" else "Website SSR detail"
        val started = System.currentTimeMillis()
        try {
            val request = Request.Builder().url("https://hongguoduanju.com$route")
                .header("User-Agent", VendorConstants.VIDEO_UA)
                .header("Accept-Language", "zh-CN,zh;q=0.9")
                .get().build()
            val html = RequestScope.execute(repository.http.newCall(request)) { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                response.body?.string() ?: throw IOException("empty body")
            }
            val loader = ContentRepository.extractRouter(html).getJSONObject("loaderData")
            val detail = loader.optJSONObject("detail_page")?.optJSONObject("seriesDetail")
                ?: loader.optJSONObject("player_(series_id)/page")?.optJSONObject("seriesDetail")
                ?: loader.optJSONObject("player_(series_id)/(vid)/page")?.optJSONObject("seriesDetail")
                ?: throw IOException("missing series detail in router data")
            val title = firstText(detail, "series_name", "series_title", "title", "name")
            val episodes = episodeCount(detail)
            val exact = expectedTitle.isBlank() || kind == "CONTROL" || title == expectedTitle
            val classification = if (title.isNotBlank() && episodes > 0) "FULL_DETAIL" else if (title.isNotBlank()) "METADATA_ONLY" else "NO_DETAIL"
            println("ENDPOINT|name=$label|kind=$kind|id=$sid|status=HTTP_200_ROUTER|bytes=${html.toByteArray().size}|title=${safe(title)}|exact=${if(exact) "YES" else "NO"}|episodes=$episodes|class=$classification|ms=${System.currentTimeMillis()-started}")
        } catch (error: Throwable) {
            val cause = error.cause ?: error
            println("ENDPOINT|name=$label|kind=$kind|id=$sid|status=FAILED|reason=${safe(cause.message ?: cause.javaClass.simpleName)}|ms=${System.currentTimeMillis()-started}")
        }
    }

    private fun report(name: String, kind: String, sid: String, expectedTitle: String, data: JSONObject, elapsed: Long) {
        val detail = data.optJSONObject("video_data") ?: data.optJSONObject(sid)?.optJSONObject("video_data") ?: data
        val title = firstText(detail, "series_title", "series_name", "title", "name")
        val episodes = episodeCount(detail)
        val exact = expectedTitle.isBlank() || kind == "CONTROL" || title == expectedTitle
        val classification = when {
            title.isNotBlank() && episodes > 0 -> "FULL_DETAIL"
            title.isNotBlank() -> "METADATA_ONLY"
            episodes > 0 -> "EPISODES_ONLY"
            else -> "NO_DETAIL"
        }
        println("ENDPOINT|name=$name|kind=$kind|id=$sid|status=JSON_DATA|title=${safe(title)}|exact=${if(exact) "YES" else "NO"}|episodes=$episodes|episodeCnt=${detail.optInt("episode_cnt", 0)}|class=$classification|fields=${safe(detail.keys().asSequence().toList().sorted().joinToString(","))}|ms=$elapsed")
    }

    private fun episodeCount(o: JSONObject): Int {
        for (key in listOf("video_list", "vid_list", "episode_list", "video_data_list")) {
            when (val value = o.opt(key)) {
                is org.json.JSONArray -> if (value.length() > 0) return value.length()
                is JSONObject -> if (value.length() > 0) return value.length()
            }
        }
        return 0
    }

    private fun firstText(o: JSONObject, vararg keys: String): String =
        keys.asSequence().map { o.optString(it).trim() }.firstOrNull { it.isNotBlank() && it != "null" }.orEmpty()

    private fun safe(value: String): String = value.replace('|', '/').replace('\n', ' ').take(140)
}
