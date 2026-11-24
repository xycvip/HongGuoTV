// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.js

import com.hongguotv.core.CatalogPage
import com.hongguotv.core.ComicRanking
import com.hongguotv.core.ContentType
import com.hongguotv.core.Danmaku
import com.hongguotv.core.DanmakuWindow
import com.hongguotv.core.Detail
import com.hongguotv.core.JsContentGateway
import com.hongguotv.core.RankBoard
import com.hongguotv.core.Series
import com.hongguotv.core.SeriesSocial
import com.hongguotv.core.TheaterCatalogPage
import com.hongguotv.core.TheaterFilterGroup
import com.hongguotv.core.TheaterFilterOption
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/**
 * 内容接口的热更新 JS 门面实现（装在 core 的 JsContent 注册表上）。
 *
 * 首页/搜索/详情/榜单的数据与解析全部在 main.js 内完成；本类只负责：
 * 调脚本、把返回值映射成 core 模型、按官方 content_type 过滤搜索类型。
 * 映射逻辑是纯函数（companion 内），JVM 单测直接覆盖，不连网。
 *
 * 关键约定：
 *  - 剧集 ID 用官方数字 ID（drama.sourceId），与原生 Series.id 一致；
 *  - 搜索类型过滤：官方响应 content_type 1004=短剧、1=漫剧（AI 剧归漫剧口径，与原生一致）；
 *  - 榜单：RankBoard.jsGroup 映射到官方榜单分组，优先「热播榜」，分组内找不到就取第一个。
 */
class HongguoJsContent(
    private val js: HongguoJs,
) : JsContentGateway {
    override fun home(page: Int, type: ContentType): CatalogPage {
        val key = when (type) {
            ContentType.SHORT -> "short_play"
            ContentType.COMIC -> "comic_series"
            ContentType.AI -> "ai_series"
        }
        val data = call("catalogPage", page, key)
        return CatalogPage(data.optJSONArray("items").mapDramas(), data.optBoolean("hasMore"))
    }

    override fun search(keyword: String, page: Int, type: ContentType?): CatalogPage {
        val data = call("search", page, keyword)
        val expected = expectedContentType(type)
        val items = data.optJSONArray("items").mapDramas { drama ->
            expected == null || drama.optLong("contentType") == expected
        }
        return CatalogPage(items, data.optBoolean("hasMore"))
    }

    override fun suggest(query: String): List<String> {
        val data = call("suggestions", query)
        val rows = data.optJSONArray("items") ?: return emptyList()
        return (0 until rows.length()).map { rows.optString(it) }.filter { it.isNotBlank() }
    }

    override fun detail(id: String): Detail {
        val data = call("detail", id)
        val drama = data.optJSONObject("drama") ?: throw IOException("详情缺少剧集信息")
        val series = dramaToSeries(drama) ?: throw IOException("详情剧集 ID 无效")
        val chapters = data.optJSONArray("chapters") ?: JSONArray()
        val episodes = (0 until chapters.length()).mapNotNull { index ->
            chapters.optJSONObject(index)?.optString("videoUrl")
                ?.removePrefix("hongguo-cenc://")
                ?.takeIf { it.isNotBlank() }
        }
        if (episodes.isEmpty()) throw IOException("详情没有返回分集")
        return Detail(
            series = series,
            episodes = episodes,
            related = emptyList(),
            sameName = emptyList(),
            social = SeriesSocial(),
        )
    }

    override fun seasons(sourceId: String): List<Series> {
        val data = call("seasons", sourceId)
        return data.optJSONArray("items").mapDramas().filter { it.id != sourceId }
    }

    override fun rankingPage(board: RankBoard, page: Int): CatalogPage {
        val boards = call("rankingBoards").optJSONArray("items") ?: JSONArray()
        val boardId = resolveBoardId(boards, board.jsGroup)
            ?: throw IOException("官方榜单缺少${board.label}分组")
        val data = call("rankingPage", boardId, page)
        val description = boardDescription(boards, boardId)
        return CatalogPage(
            items = data.optJSONArray("items").mapDramas(),
            hasMore = data.optBoolean("hasMore"),
            ranking = ComicRanking(positions = emptyMap(), totalPages = 0, updatedText = description),
        )
    }

    override fun danmakuWindow(seriesId: String, vid: String, durationMs: Long, startMs: Long): DanmakuWindow {
        val data = call("danmaku", seriesId, vid, startMs.toInt(), durationMs.toInt())
        return danmakuWindowOf(data, startMs)
    }

    override fun theater(page: Int, type: ContentType?, selected: Map<String, String>): TheaterCatalogPage {
        val typeName = type?.name // SHORT / COMIC / AI，与 JS 的 theaterScene/theaterGenre 对齐
        val data = call("theater", page, typeName, selected)
        return theaterPageOf(data)
    }

    override fun theaterFilters(type: ContentType): List<TheaterFilterGroup> {
        val data = call("theaterFilters", type.name)
        return parseTheaterFilters(data.optJSONArray("items"))
    }

    private fun call(function: String, vararg args: Any?): JSONObject {
        val result = runBlockingCall(function, *args)
        if (!result.ok) {
            throw IOException(result.error ?: "脚本调用失败（${result.code}）")
        }
        return JSONObject(result.data ?: "{}")
    }

    private fun runBlockingCall(function: String, vararg args: Any?): JsCallResult =
        kotlinx.coroutines.runBlocking { js.call(function, *args) }

    companion object {
        private const val WINDOW_MS = 30_000L

        internal const val CONTENT_TYPE_SHORT = 1004L
        internal const val CONTENT_TYPE_COMIC = 1L

        /** 官方 content_type：1004=短剧，1=漫剧；null=不过滤（综合搜索）。 */
        internal fun expectedContentType(type: ContentType?): Long? = when (type) {
            null -> null
            ContentType.SHORT -> CONTENT_TYPE_SHORT
            ContentType.COMIC, ContentType.AI -> CONTENT_TYPE_COMIC
        }

        /** drama JSON → core Series；ID 取官方数字 ID（sourceId），与原生一致。 */
        internal fun dramaToSeries(drama: JSONObject): Series? {
            val id = drama.optString("sourceId")
                .ifBlank { drama.optString("id").substringAfterLast(':') }
            if (!id.matches(Regex("[0-9]{1,30}"))) return null
            val tags = drama.optJSONArray("tags") ?: JSONArray()
            return Series(
                id = id,
                title = drama.optString("title").ifBlank { drama.optString("name") },
                cover = drama.optString("cover").ifBlank { drama.optString("coverUrl") },
                description = drama.optString("intro").ifBlank { drama.optString("desc") },
                badge = drama.optString("remark").ifBlank { drama.optString("badge") },
                tags = (0 until tags.length()).map { tags.optString(it) }.filter { it.isNotBlank() }
                    .joinToString(" · "),
            )
        }

        /** drama 数组 → Series 列表；[accept] 在映射前按官方字段过滤（如搜索类型）。 */
        internal fun JSONArray?.mapDramas(accept: (JSONObject) -> Boolean = { true }): List<Series> {
            if (this == null) return emptyList()
            val out = ArrayList<Series>(length())
            for (index in 0 until length()) {
                val drama = optJSONObject(index) ?: continue
                if (!accept(drama)) continue
                dramaToSeries(drama)?.let(out::add)
            }
            return out
        }

        /** 榜单分组内优先「热播榜」，分组调整时退到第一个，保证四个页签永远有数。 */
        internal fun resolveBoardId(boards: JSONArray, group: String): String? {
            val prefix = "hongguo-app:$group:"
            var fallback: String? = null
            for (index in 0 until boards.length()) {
                val board = boards.optJSONObject(index) ?: continue
                val id = board.optString("id")
                if (!id.startsWith(prefix)) continue
                if (id.endsWith("hot_play")) return id
                if (fallback == null) fallback = id
            }
            return fallback
        }

        internal fun boardDescription(boards: JSONArray, boardId: String): String {
            for (index in 0 until boards.length()) {
                val board = boards.optJSONObject(index) ?: continue
                if (board.optString("id") == boardId) return board.optString("description")
            }
            return ""
        }

        internal fun parseColor(value: String): Int? {
            val hex = value.removePrefix("#")
            if (hex.length != 6 || !hex.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) return null
            return (0xFF000000L or hex.toLong(16)).toInt()
        }

        /** 剧院筛选面板 JSON → core TheaterFilterGroup 列表。 */
        internal fun parseTheaterFilters(rows: JSONArray?): List<TheaterFilterGroup> {
            if (rows == null) return emptyList()
            val groups = ArrayList<TheaterFilterGroup>(rows.length())
            for (i in 0 until rows.length()) {
                val row = rows.optJSONObject(i) ?: continue
                val type = row.optString("type")
                if (type.isBlank()) continue
                val options = row.optJSONArray("options") ?: JSONArray()
                val opts = ArrayList<TheaterFilterOption>(options.length())
                for (j in 0 until options.length()) {
                    val option = options.optJSONObject(j) ?: continue
                    val id = option.optString("id")
                    val label = option.optString("label")
                    if (id.isNotBlank() && label.isNotBlank()) opts.add(TheaterFilterOption(id, label))
                }
                groups.add(TheaterFilterGroup(type, row.optString("label").ifBlank { type }, opts))
            }
            return groups
        }

        /** theater 导出返回 → core TheaterCatalogPage，字段与 JS 适配层逐一对齐。 */
        internal fun theaterPageOf(page: JSONObject): TheaterCatalogPage {
            // JS theater 导出用 id/title/cover/description/badge/tags（与原生 Series 同名），
            // 不同于 drama 的 sourceId/intro/remark，所以这里单独映射，不走 dramaToSeries。
            val rows = page.optJSONArray("items") ?: JSONArray()
            val items = ArrayList<Series>(rows.length())
            for (i in 0 until rows.length()) {
                val row = rows.optJSONObject(i) ?: continue
                val id = row.optString("id")
                if (!id.matches(Regex("[0-9]{1,30}"))) continue
                items.add(Series(
                    id = id,
                    title = row.optString("title"),
                    cover = row.optString("cover"),
                    description = row.optString("description"),
                    badge = row.optString("badge"),
                    tags = row.optString("tags"),
                ))
            }
            val hasMore = page.optBoolean("hasMore")
            val filters = parseTheaterFilters(page.optJSONArray("filters"))
            val nextOffset = page.opt("nextOffset")?.let { if (it is Number) it.toInt() else null }
            return TheaterCatalogPage(
                catalog = CatalogPage(items, hasMore),
                filters = filters,
                nextOffset = nextOffset,
                sessionId = page.optString("sessionId"),
            )
        }

        /**
         * danmaku 导出页面 → 弹幕窗口。下一窗起点以脚本返回的 nextMs 为准，
         * 缺失或回退时按 30 秒窗宽推进，与 DanmakuLoader 的窗宽一致。
         */
        internal fun danmakuWindowOf(page: JSONObject, startMs: Long): DanmakuWindow {
            val rows = page.optJSONArray("items") ?: JSONArray()
            val comments = (0 until rows.length()).mapNotNull { index ->
                val row = rows.optJSONObject(index) ?: return@mapNotNull null
                val text = row.optString("text")
                if (text.isBlank()) {
                    null
                } else {
                    Danmaku(
                        id = row.optString("id"),
                        offsetMs = row.optLong("timeMs"),
                        text = text,
                        diggCount = 0,
                        color = parseColor(row.optString("color")),
                    )
                }
            }
            val next = page.optLong("nextMs")
            return DanmakuWindow(comments, if (next > startMs) next else startMs + WINDOW_MS)
        }
    }
}
