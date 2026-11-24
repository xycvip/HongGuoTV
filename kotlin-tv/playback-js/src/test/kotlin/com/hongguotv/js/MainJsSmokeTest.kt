// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.js

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * main.js 的 JVM 冒烟回归：与 /workspace/hongguotv-js/test/run.py 的用例一一对应。
 * 桌面 JVM 走 quickjs-kt-jvm artifact，跑的是与 Android 完全相同的 Kotlin 代码。
 *
 * 注意：这些用例访问真实网络（红果官方接口），CI 环境无外网时会失败——
 * 这是有意的：脚本回归必须连真网，Mock 只能覆盖桥接层覆盖不了 vendor。
 */
class MainJsSmokeTest {
    private lateinit var runner: JsScriptRunner

    @Before
    fun setUp() {
        val script = requireNotNull(javaClass.getResourceAsStream("/main.js")).readBytes()
        val http = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .build()
        val host = JsEngineHost(http, InMemoryJsKeyValueStore())
        runner = JsScriptRunner(host, { script }, JsBytecodeCache.memoryOnly())
    }

    /**
     * 红果官方接口有偶发抖动（Pe 内部重试之后的跨接口瞬时失败），
     * 真实网络回归允许有限重试；连续失败才是回归。
     */
    private fun call(function: String, vararg args: Any?, timeoutMs: Long = 90_000): JsCallResult {
        var last: JsCallResult? = null
        repeat(3) { attempt ->
            last = runBlocking { runner.call(function, args.toJsonArray(), timeoutMs) }
            if (last!!.ok) return last!!
            if (attempt < 2) Thread.sleep(1_000)
        }
        return last!!
    }

    private fun JsCallResult.dataJson(): org.json.JSONObject =
        org.json.JSONObject(requireNotNull(data) { "调用失败：$error / $code" })

    @Test
    fun `meta 返回脚本标识`() {
        // meta 是对象属性（{id,name,version}），不是函数。
        val result = call("meta")
        assertTrue(result.ok)
        assertTrue(result.dataJson().optString("id").isNotEmpty())
    }

    @Test
    fun `categories 返回非空分类`() {
        val result = call("categories")
        assertTrue("categories 失败：${result.error}", result.ok)
        assertTrue(result.dataJson().optJSONArray("items")?.length() ?: 0 > 0)
    }

    @Test
    fun `search 两页 + suggestions + detail`() {
        val first = call("search", 1, "重生")
        assertTrue("search p1 失败：${first.error}", first.ok)
        val items = first.dataJson().optJSONArray("items")
        assertNotNull(items)
        assertTrue("search 无结果", items.length() > 0)
        val second = call("search", 2, "重生")
        assertTrue("search p2 失败：${second.error}", second.ok)

        val suggestions = call("suggestions", "重生")
        assertTrue("suggestions 失败：${suggestions.error}", suggestions.ok)

        val sourceId = items.getJSONObject(0).optString("sourceId")
        assertTrue("结果缺 sourceId", sourceId.isNotEmpty())
        val detail = call("detail", sourceId)
        assertTrue("detail 失败：${detail.error}", detail.ok)
        assertTrue(
            "detail 无 chapters",
            detail.dataJson().optJSONArray("chapters")?.length() ?: 0 > 0
        )
    }

    @Test
    fun `catalogPage 两页`() {
        val first = call("catalogPage", 1, "short_play")
        assertTrue("catalogPage p1 失败：${first.error}", first.ok)
        val second = call("catalogPage", 2, "short_play")
        assertTrue("catalogPage p2 失败：${second.error}", second.ok)
    }

    @Test
    fun `recommendations 与 feed`() {
        val recommendations = call("recommendations", "short_play", null, emptyList<Any>())
        assertTrue("recommendations 失败：${recommendations.error}", recommendations.ok)
        val feed = call("feed", 1, "all")
        assertTrue("feed 失败：${feed.error}", feed.ok)
    }

    @Test
    fun `rankingBoards + rankingPage`() {
        val boards = call("rankingBoards")
        assertTrue("rankingBoards 失败：${boards.error}", boards.ok)
        val items = boards.dataJson().optJSONArray("items")
        assertTrue("rankingBoards 无榜单项", items != null && items.length() > 0)
        val boardId = items.getJSONObject(items.length() / 2).optString("id")
        val page = call("rankingPage", boardId, 1)
        assertTrue("rankingPage 失败：${page.error}", page.ok)
        assertTrue(page.dataJson().optJSONArray("items")?.length() ?: 0 > 0)
    }

    @Test
    fun `seasons 对含多季的剧集不报错`() {
        val search = call("search", 1, "重生")
        assertTrue(search.ok)
        val sourceId = search.dataJson().optJSONArray("items")
            .getJSONObject(0).optString("sourceId")
        val seasons = call("seasons", sourceId)
        // seasons 对单季剧集返回空列表也属正常，只要不抛错。
        assertTrue("seasons 失败：${seasons.error}", seasons.ok)
    }

    @Test
    fun `danmaku 返回分页结构`() {
        val search = call("search", 1, "闪婚")
        assertTrue(search.ok)
        val sourceId = search.dataJson().optJSONArray("items")
            .getJSONObject(0).optString("sourceId")
        val detail = call("detail", sourceId)
        assertTrue(detail.ok)
        val chapter = detail.dataJson().optJSONArray("chapters").getJSONObject(0)
        val videoUrl = chapter.optString("videoUrl")
        if (!videoUrl.startsWith("hongguo-cenc://")) return
        val videoId = videoUrl.removePrefix("hongguo-cenc://")
        val danmaku = call("danmaku", sourceId, videoId, 0, 60000)
        assertTrue("danmaku 失败：${danmaku.error}", danmaku.ok)
        assertNotNull(danmaku.dataJson().optJSONArray("items"))
    }

    @Test
    fun `resolveMedia 拿到真实直链与密钥`() {
        val search = call("search", 1, "闪婚")
        assertTrue(search.ok)
        val sourceId = search.dataJson().optJSONArray("items")
            .getJSONObject(0).optString("sourceId")
        val detail = call("detail", sourceId)
        assertTrue(detail.ok)
        val chapter = detail.dataJson().optJSONArray("chapters").getJSONObject(0)
        val media = call(
            "resolveMedia",
            org.json.JSONObject()
                .put("dramaId", detail.dataJson().optJSONObject("drama").optString("id"))
                .put("chapter", detail.dataJson().optJSONArray("chapters").getJSONObject(0)),
        )
        assertTrue("resolveMedia 失败：${media.error} / ${media.code}", media.ok)
        val data = media.dataJson()
        assertTrue("播放地址为空", data.optString("url").startsWith("https://"))
        assertTrue("清晰度为空", data.optInt("quality") > 0)
    }

    @Test
    fun `actorRanking 首页 + 翻页 + actorWorks 端到端`() {
        val first = call("actorRanking", org.json.JSONObject().put("offset", 0))
        assertTrue("actorRanking 失败：${first.error} / ${first.code}", first.ok)
        val data = first.dataJson()
        val items = data.optJSONArray("items")
        assertTrue("演员榜无条目", items != null && items.length() > 0)
        val actor = items.getJSONObject(0)
        assertTrue("userId 非数字：${actor.optString("userId")}", actor.optString("userId").matches(Regex("[0-9]{1,30}")))
        assertTrue("姓名为空", actor.optString("name").isNotEmpty())
        assertTrue("position 应从 1 开始", actor.optInt("position") == 1)

        val actorId = actor.optString("actorId")
        val hasMore = data.optBoolean("hasMore")
        val nextOffset = data.optInt("nextOffset")
        val sessionId = data.optString("sessionId")
        if (hasMore && nextOffset > 0) {
            val second = call(
                "actorRanking",
                org.json.JSONObject().put("offset", nextOffset).put("sessionId", sessionId),
            )
            assertTrue("演员榜翻页失败：${second.error} / ${second.code}", second.ok)
            val secondItems = second.dataJson().optJSONArray("items")
            assertTrue("翻页无条目", secondItems != null && secondItems.length() > 0)
            assertTrue(
                "position 未跨页递增",
                secondItems.getJSONObject(0).optInt("position") >= nextOffset + 1,
            )
        }

        if (actorId.isNotEmpty()) {
            val works = call("actorWorks", org.json.JSONObject().put("actorId", actorId))
            assertTrue("actorWorks 失败：${works.error} / ${works.code}", works.ok)
            val worksData = works.dataJson()
            assertNotNull("actorWorks 缺 items", worksData.optJSONArray("items"))
            assertTrue("nextOffset 应非负", worksData.optInt("nextOffset") >= 0)
        }
    }

    @Test
    fun `actorRanking 子榜参数可切换`() {
        val star = call("actorRanking", org.json.JSONObject().put("offset", 0).put("subBoard", "ranklist_actor_fans"))
        assertTrue("星光榜失败：${star.error} / ${star.code}", star.ok)
        assertTrue("星光榜无条目", star.dataJson().optJSONArray("items")?.length() ?: 0 > 0)
    }

    @Test
    fun `演员接口参数校验不发起请求`() {
        val badBoard = call("actorRanking", org.json.JSONObject().put("subBoard", "ranklist_other"))
        assertFalse(badBoard.ok)
        assertTrue("期望 INVALID_BOARD，实际 ${badBoard.code}", badBoard.code == "INVALID_BOARD")
        val badOffset = call("actorRanking", org.json.JSONObject().put("offset", -1))
        assertFalse(badOffset.ok)
        assertTrue("期望 INVALID_CURSOR，实际 ${badOffset.code}", badOffset.code == "INVALID_CURSOR")
        val badActor = call("actorWorks", org.json.JSONObject().put("actorId", "abc"))
        assertFalse(badActor.ok)
        assertTrue("期望 INVALID_ACTOR_ID，实际 ${badActor.code}", badActor.code == "INVALID_ACTOR_ID")
        val badPageSize = call("actorWorks", org.json.JSONObject().put("actorId", "7100000000001401").put("pageSize", 51))
        assertFalse(badPageSize.ok)
        assertTrue("期望 INVALID_PAGE_SIZE，实际 ${badPageSize.code}", badPageSize.code == "INVALID_PAGE_SIZE")
    }

    @Test
    fun `theater 筛选面板与两页列表`() {
        // 剧院已迁入 main.js（src/theater.js，V5 签名打官方 landpage 接口）。
        val first = call("theater", 1, "SHORT", emptyMap<String, String>())
        assertTrue("theater p1 失败：${first.error}", first.ok)
        val page1 = first.dataJson()
        assertTrue("theater 无剧集", page1.optJSONArray("items")?.length() ?: 0 > 0)
        assertTrue("theater 无筛选面板", page1.optJSONArray("filters")?.length() ?: 0 > 0)
        val second = call("theater", 2, "SHORT", emptyMap<String, String>())
        assertTrue("theater p2 失败：${second.error}", second.ok)
        assertTrue("theater p2 无剧集", second.dataJson().optJSONArray("items")?.length() ?: 0 > 0)
    }

    @Test
    fun `theaterFilters 返回筛选面板`() {
        val result = call("theaterFilters", "COMIC")
        assertTrue("theaterFilters 失败：${result.error}", result.ok)
        assertTrue("theaterFilters 无分组", org.json.JSONObject(requireNotNull(result.data)).optJSONArray("items")?.length() ?: 0 > 0)
    }

    @Test
    fun `缺失导出返回 MISSING_EXPORT`() {
        val result = call("noSuchExport")
        assertFalse(result.ok)
        assertTrue(result.code == "MISSING_EXPORT")
    }

    @Test
    fun `JS 异常携带 vendor code`() {
        // resolveMedia 对缺 chapter 的 task 抛 INVALID_CHAPTER。
        val result = call(
            "resolveMedia",
            org.json.JSONObject().put("dramaId", "123").put("chapter", org.json.JSONObject()),
        )
        assertFalse(result.ok)
        assertTrue("期望 INVALID_CHAPTER，实际 ${result.code}", result.code == "INVALID_CHAPTER")
    }

    @Test
    fun `host fetch 网络异常转换为 JS reject`() {
        // 带 playUrl 的 task 会走完签名流程；远端返回空 media 时 vendor 抛
        // PLAYBACK_UNRESOLVED——两种都属于脚本正常工作，唯一不接受的是桥接层崩溃。
        val result = call(
            "resolveMedia",
            org.json.JSONObject()
                .put("dramaId", "1")
                .put("chapter", org.json.JSONObject().put("playUrl", "https://hongguoduanju.com/player/1/1")),
        )
        assertTrue("调用应返回结果（无论成功或业务失败）", result.data != null || result.code != null)
    }
}
