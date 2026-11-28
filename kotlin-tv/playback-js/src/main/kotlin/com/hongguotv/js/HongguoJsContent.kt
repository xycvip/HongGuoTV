// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.js

import com.hongguotv.core.Actor
import com.hongguotv.core.ActorRankBoard
import com.hongguotv.core.ActorRankEntry
import com.hongguotv.core.ActorRankPage
import com.hongguotv.core.ActorWorksPage
import com.hongguotv.core.CatalogPage
import com.hongguotv.core.ComicRanking
import com.hongguotv.core.ContentType
import com.hongguotv.core.Danmaku
import com.hongguotv.core.DanmakuWindow
import com.hongguotv.core.Detail
import com.hongguotv.core.ImageUrls
import com.hongguotv.core.JsContentGateway
import com.hongguotv.core.RankBoard
import com.hongguotv.core.RankKind
import com.hongguotv.core.SeasonEntry
import com.hongguotv.core.Series
import com.hongguotv.core.seasonNumber
import com.hongguotv.core.SeriesSocial
import com.hongguotv.core.TheaterCatalogPage
import com.hongguotv.core.TheaterFilterGroup
import com.hongguotv.core.TheaterFilterOption
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.LinkedHashMap

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
 *  - 榜单：(RankBoard 内容类型 × RankKind 榜单口径) 经固定 id 表映射到官方榜单，
 *    查不到时按「分组 + 榜名」兜底，不再按 id 后缀猜（官方命名不统一）。
 */
class HongguoJsContent(
    private val js: HongguoJs,
) : JsContentGateway {

    /**
     * 脚本结果短缓存。
     *
     * 每一次脚本调用都要新建 QuickJS 运行时并跑完整份 main.js，代价远高于一次纯
     * HTTP 请求。联想、系列剧、影片详情、榜单分组这几类「同一参数反复问」的接口
     * 缓存几百秒后，输入联想和来回切页几乎不再等待；首页/翻页这类必须新鲜的接口
     * 不进缓存。
     */
    private val cache = TtlCache(160)

    override fun home(page: Int, type: ContentType): CatalogPage {
        val key = when (type) {
            ContentType.SHORT -> "short_play"
            ContentType.COMIC -> "comic_series"
            ContentType.AI -> "ai_series"
        }
        val data = call("catalogPage", page, key)
        return CatalogPage(
            data.optJSONArray("items").mapDramas(typeOverride = type),
            data.optBoolean("hasMore"),
        )
    }

    override fun search(keyword: String, page: Int, type: ContentType?): CatalogPage {
        val data = callCached("search", SEARCH_TTL_MS, page, keyword)
        val expected = expectedContentType(type)
        val items = data.optJSONArray("items").mapDramas(
            accept = { drama -> expected == null || drama.optLong("contentType") == expected },
            // 指定类型搜索时，综合接口 contentType 无法区分漫剧/AI，直接按用户所选类型标记。
            typeOverride = type,
        )
        return CatalogPage(items, data.optBoolean("hasMore"))
    }

    override fun suggest(query: String): List<String> {
        val key = "suggestions|$query"
        cache.get(key)?.let { return decodeSuggestions(it) }
        val result = runBlockingCall("suggestions", query)
        if (!result.ok) return emptyList()
        val raw = result.data?.trim().orEmpty()
        if (raw.isEmpty() || raw == "null") return emptyList()
        cache.put(key, raw, SUGGEST_TTL_MS)
        return decodeSuggestions(raw)
    }

    private fun decodeSuggestions(raw: String): List<String> {
        // main.js 的 suggestions 直接 return 一个字符串数组（不是 { items: [] }）。
        // 之前无条件用 JSONObject 解析，遇到 "[" 开头就抛异常、被上层 runCatching 吞掉，
        // 于是联想永远是空的。这里两种结构都接受。
        val rows = if (raw.trimStart().startsWith("[")) {
            runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        } else {
            runCatching { JSONObject(raw).optJSONArray("items") }.getOrNull() ?: return emptyList()
        }
        return (0 until rows.length()).map { rows.optString(it) }.filter { it.isNotBlank() }
    }

    override fun detail(id: String): Detail {
        val data = callCached("detail", DETAIL_TTL_MS, id)
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
            // 脚本 2026.11.26 起在 detail 里下发 actors；旧脚本没有该字段，
            // 解析成空列表即可，UI 会自动隐藏演员栏。
            actors = data.optJSONArray("actors").mapActors(),
        )
    }

    override fun seasons(sourceId: String): List<SeasonEntry> {
        val key = "seasons|$sourceId"
        cache.get(key)?.let { return parseSeasons(it) }
        val result = runBlockingCall("seasons", sourceId)
        if (!result.ok) return emptyList()
        val raw = result.data ?: ""
        cache.put(key, raw, SEASONS_TTL_MS)
        return parseSeasons(raw)
    }

    override fun related(seriesId: String, genre: String, limit: Int): List<Series> {
        if (limit <= 0) return emptyList()
        // 通用推荐位是分类级 feed，所有详情页都拿到同一批；JS 侧会先按剧名找同类，
        // 再用按剧 ID 偏移的推荐页补足，这里直接取结果并剔掉当前剧。
        val data = runCatching { callCached("related", RELATED_TTL_MS, seriesId, genre, limit) }.getOrNull() ?: return emptyList()
        return data.optJSONArray("items").mapDramas()
            .filter { it.id != seriesId }
            .take(limit)
    }

    override fun rankingPage(board: RankBoard, kind: RankKind, page: Int): CatalogPage {
        // 榜单分组是全局常量：以前每次翻页都先问一遍分组，白白多一次脚本调用 + 网络请求。
        val boards = callCached("rankingBoards", BOARDS_TTL_MS).optJSONArray("items") ?: JSONArray()
        val boardId = resolveBoardId(boards, board, kind)
            ?: throw IOException("官方榜单缺少「${board.label}·${kind.label}」")
        // Java 层再兜一层缓存：脚本内部虽有 60s KV 缓存，但每次调用仍要新建 QuickJS runtime
        // 并 evaluate 整份 main.js。命中这层就直接返回解析好的 CatalogPage，连运行时都不建。
        // callCached 的 key = 函数名 + 参数，天然按 boardId+page 隔离，不会串榜串页。
        val data = callCached("rankingPage", RANK_PAGE_TTL_MS, boardId, page)
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

    override fun feed(page: Int, scope: String): CatalogPage {
        val data = call("feed", page, scope)
        return CatalogPage(
            data.optJSONArray("items").mapDramas(),
            data.optBoolean("hasMore"),
        )
    }

    override fun theater(page: Int, type: ContentType?, selected: Map<String, String>): TheaterCatalogPage {
        val typeName = type?.name // SHORT / COMIC / AI，与 JS 的 theaterScene/theaterGenre 对齐
        val data = call("theater", page, typeName, selected)
        return theaterPageOf(data, type)
    }

    override fun theaterFilters(type: ContentType): List<TheaterFilterGroup> {
        val data = call("theaterFilters", type.name)
        return parseTheaterFilters(data.optJSONArray("items"))
    }

    override fun actorWorks(actorId: String, offset: Int, count: Int): ActorWorksPage {
        // 脚本的游标语义是「上游已消耗行数」，不是页码：每页大小固定后逐页累加会漏条目。
        // 这里只把 offset 原样传给脚本，并原样带回它算出的 nextOffset。
        val options = JSONObject()
            .put("actorId", actorId)
            .put("offset", offset.coerceAtLeast(0))
            .put("pageSize", count.coerceIn(1, 50))
        val data = callCached("actorWorks", ACTOR_WORKS_TTL_MS, options)
        return ActorWorksPage(
            items = data.optJSONArray("items").mapDramas(),
            hasMore = data.optBoolean("hasMore"),
            nextOffset = data.optInt("nextOffset", offset + count),
            totalCount = data.optInt("totalCount", 0),
        )
    }

    /**
     * 演员榜（剧星榜 / 星光榜）。
     *
     * 会话游标：首屏 offset=0 且不带 sessionId，脚本内部用第一段的 tab_type；
     * 之后必须把上一页返回的 sessionId 原样带回去，脚本才会切到翻页用的 tab_type。
     * 实测不带 session 翻第 2 页会拿到与第 1 页完全相同的 20 个人。
     *
     * 缓存键包含 offset + sessionId，命中即回放同一页，不会串页。
     */
    override fun actorRank(board: ActorRankBoard, offset: Int, sessionId: String): ActorRankPage {
        val options = JSONObject()
            .put("offset", offset.coerceAtLeast(0))
            .put("subBoard", board.scriptId)
        if (sessionId.isNotBlank()) options.put("sessionId", sessionId)
        val data = callCached("actorRanking", ACTOR_RANK_TTL_MS, options)
        val rows = data.optJSONArray("items")
        return ActorRankPage(
            items = rows.mapActorRanks(offset),
            nextOffset = data.optInt("nextOffset", offset + (rows?.length() ?: 0)),
            hasMore = data.optBoolean("hasMore"),
            sessionId = data.optString("sessionId"),
        )
    }

    private fun call(function: String, vararg args: Any?): JSONObject {
        val result = runBlockingCall(function, *args)
        if (!result.ok) {
            throw IOException(result.error ?: "脚本调用失败（${result.code}）")
        }
        return JSONObject(result.data ?: "{}")
    }

    /** 带 TTL 的脚本调用：只在成功时写入缓存，失败照常抛异常让上层展示错误。 */
    private fun callCached(function: String, ttlMs: Long, vararg args: Any?): JSONObject {
        val key = function + "|" + args.joinToString("\u0001")
        cache.get(key)?.let { return JSONObject(it) }
        val data = call(function, *args)
        cache.put(key, data.toString(), ttlMs)
        return data
    }

    private fun runBlockingCall(function: String, vararg args: Any?): JsCallResult =
        kotlinx.coroutines.runBlocking { js.call(function, *args) }

    companion object {
        private const val WINDOW_MS = 30_000L

        /** 联想词几分钟内不会变，缓存后连续输入同一个前缀直接命中。 */
        private const val SUGGEST_TTL_MS = 5 * 60 * 1000L
        /** 详情里的分集与剧集信息短时间稳定；2 分钟足够覆盖「详情 → 返回 → 再进」。 */
        private const val DETAIL_TTL_MS = 2 * 60 * 1000L
        private const val SEASONS_TTL_MS = 10 * 60 * 1000L
        private const val RELATED_TTL_MS = 10 * 60 * 1000L
        /** 演员作品列表变动很慢，翻回上一页时直接命中，不必重跑一次脚本运行时。 */
        private const val ACTOR_WORKS_TTL_MS = 5 * 60 * 1000L
        /** 演员榜按人气实时变动，但 3 分钟内的重复进出没必要重拉。 */
        private const val ACTOR_RANK_TTL_MS = 3 * 60 * 1000L
        private const val BOARDS_TTL_MS = 10 * 60 * 1000L
        /** 榜单分页：脚本内已有 60s KV 缓存，这层主要省掉「新建 runtime + 求值整份 main.js」的固定开销。 */
        private const val RANK_PAGE_TTL_MS = 3 * 60 * 1000L
        /** 搜索结果 60 秒内复用，重复提交同一个关键词不再等一次完整请求。 */
        private const val SEARCH_TTL_MS = 60 * 1000L

        internal const val CONTENT_TYPE_SHORT = 1004L
        internal const val CONTENT_TYPE_COMIC = 1L

        /** 官方 content_type：1004=短剧，1=漫剧；null=不过滤（综合搜索）。 */
        internal fun expectedContentType(type: ContentType?): Long? = when (type) {
            null -> null
            ContentType.SHORT -> CONTENT_TYPE_SHORT
            ContentType.COMIC, ContentType.AI -> CONTENT_TYPE_COMIC
        }

        /**
         * drama JSON → core Series；ID 取官方数字 ID（sourceId），与原生一致。
         * [typeOverride] 为调用入口已知的内容类型（如 home 传入的 SHORT/COMIC/AI），
         * 优先于 drama 字段——官方 contentType 把漫剧与 AI 剧都报成 1，无法自行区分。
         */
        internal fun dramaToSeries(drama: JSONObject, typeOverride: ContentType? = null): Series? {
            val id = drama.optString("sourceId")
                .ifBlank { drama.optString("id").substringAfterLast(':') }
            if (!id.matches(Regex("[0-9]{1,30}"))) return null
            val tags = drama.optJSONArray("tags") ?: JSONArray()
            val resolvedType = typeOverride ?: runCatching {
                ContentType.entries.firstOrNull { it.searchTab == drama.optLong("contentType").toInt() }
            }.getOrNull() ?: ContentType.SHORT
            // feed 直出的分集 vid（启动选剧层拿剧即可起播）。其他来源无此字段，得到空集。
            val episodesJson = drama.optJSONArray("episodes") ?: JSONArray()
            val episodes = (0 until episodesJson.length()).mapNotNull { i ->
                episodesJson.optString(i).takeIf { it.isNotBlank() }
            }
            return Series(
                id = id,
                title = drama.optString("title").ifBlank { drama.optString("name") },
                cover = drama.optString("cover").ifBlank { drama.optString("coverUrl") },
                description = drama.optString("intro").ifBlank { drama.optString("desc") },
                badge = drama.optString("remark").ifBlank { drama.optString("badge") },
                tags = (0 until tags.length()).map { tags.optString(it) }.filter { it.isNotBlank() }
                    .joinToString(" · "),
                contentType = resolvedType,
                episodes = episodes,
            )
        }

        /**
         * main.js 的 seasons 导出直接返回 `[{ number, drama }, …]`（数组，不是 `{ items: [] }`）。
         * 旧实现用 JSONObject 解析数组会抛异常、被上层当成「没有系列剧」吞掉——
         * 这就是详情页一直没有「第一季/第二季」的原因。
         */
        internal fun parseSeasons(raw: String?): List<SeasonEntry> {
            val text = raw?.trim().orEmpty()
            if (text.isEmpty() || text == "null" || !text.startsWith("[")) return emptyList()
            val rows = runCatching { JSONArray(text) }.getOrNull() ?: return emptyList()
            val out = ArrayList<SeasonEntry>(rows.length())
            for (index in 0 until rows.length()) {
                val row = rows.optJSONObject(index) ?: continue
                val drama = row.optJSONObject("drama") ?: continue
                val series = dramaToSeries(drama) ?: continue
                val parsed = seasonNumber(series.title).takeIf { it != Int.MAX_VALUE }
                val number = row.optInt("number", 0).takeIf { it > 0 } ?: parsed ?: (index + 1)
                out.add(SeasonEntry(series = series, number = number))
            }
            return out.distinctBy { it.series.id }.sortedBy { it.number }
        }

        /** drama 数组 → Series 列表；[accept] 在映射前按官方字段过滤（如搜索类型）。 */        internal fun JSONArray?.mapDramas(
            accept: (JSONObject) -> Boolean = { true },
            typeOverride: ContentType? = null,
        ): List<Series> {
            if (this == null) return emptyList()
            val out = ArrayList<Series>(length())
            for (index in 0 until length()) {
                val drama = optJSONObject(index) ?: continue
                if (!accept(drama)) continue
                dramaToSeries(drama, typeOverride)?.let(out::add)
            }
            return out
        }

        /**
         * actors JSON → Actor。字段名同时兼容脚本的短名（id/name/role）和官方原名
         * （celebrity_id/nickname/role_name）——脚本若直接透传 celebrities 也能解析，
         * 不必两边约定同一套命名。头像由脚本过一遍 cleanCoverUrl（heic → JPEG）。
         */
        internal fun JSONArray?.mapActors(): List<Actor> {
            if (this == null) return emptyList()
            val out = ArrayList<Actor>(length())
            for (index in 0 until length()) {
                val row = optJSONObject(index) ?: continue
                val name = row.optString("name").ifBlank { row.optString("nickname") }.trim()
                if (name.isBlank()) continue
                out.add(
                    Actor(
                        id = row.optString("id").ifBlank { row.optString("celebrity_id") }.trim(),
                        name = name,
                        role = row.optString("role").ifBlank { row.optString("role_name") }.trim(),
                        avatar = ImageUrls.normalize(
                            row.optString("avatar").ifBlank { row.optString("avatarUrl") },
                        ),
                        intro = row.optString("intro").trim(),
                    ),
                )
            }
            return out
        }

        /**
         * 演员榜 JSON → ActorRankEntry。
         *
         * 官方字段：actorId / name / avatar / popularity（「881万人气」）/ intro /
         * details（两段说明）/ position（1 起的连续名次）/ changeType / changePlaces。
         * intro 在两个榜语义不同——剧星榜是作品名串，星光榜是最新动态，
         * 一律放进 Actor.intro 让弹窗原样展示，不在这里改写。
         */
        internal fun JSONArray?.mapActorRanks(baseOffset: Int): List<ActorRankEntry> {
            if (this == null) return emptyList()
            val out = ArrayList<ActorRankEntry>(length())
            for (index in 0 until length()) {
                val row = optJSONObject(index) ?: continue
                val name = row.optString("name").trim()
                if (name.isBlank()) continue
                val details = row.optJSONArray("details")?.let { array ->
                    (0 until array.length()).map { array.optString(it).trim() }.filter { it.isNotBlank() }
                }.orEmpty()
                val fallbackRank = baseOffset + index + 1
                out.add(
                    ActorRankEntry(
                        actor = Actor(
                            id = row.optString("actorId").trim(),
                            name = name,
                            // 旧脚本可能把头放在 avatarUrl / avatar_url；详情页那套早就三种都认，
                            // 演员榜之前只读 avatar，字段一换整页头像全空。
                            // 再过一遍归一化：裸 novel-pic / heic 在盒子上解不出来，会表现成空白。
                            avatar = ImageUrls.normalize(
                                row.optString("avatar")
                                    .ifBlank { row.optString("avatarUrl") }
                                    .ifBlank { row.optString("avatar_url") },
                            ),
                            intro = row.optString("intro").trim(),
                            meta = details.joinToString(" · "),
                        ),
                        rank = row.optInt("position", fallbackRank).takeIf { it > 0 } ?: fallbackRank,
                        popularity = row.optString("popularity").trim(),
                        details = details,
                        changeType = row.optInt("changeType", 0),
                        changePlaces = row.optInt("changePlaces", 0),
                    ),
                )
            }
            return out
        }

        /**
         * 榜单 id 解析：先查固定表，查不到再按榜名兜底。
         *
         * 三段式而不是「按后缀猜」——官方 id 命名不统一：综合推荐是 `ranklist_hot_sc`，
         * 漫剧推荐却是 `comic_series_hot_rank`，综合热播又多了 `_sc` 后缀。
         * 旧实现用 `endsWith("hot_play")` 挑热播，综合组匹配不上就退回列表首项，
         * 于是「综合」长期显示的是推荐榜。固定表 + 名字兜底不会错位，
         * 官方未来换 id 也能靠「真人剧·热播榜」这种名字自愈。
         */
        internal fun resolveBoardId(boards: JSONArray, board: RankBoard, kind: RankKind): String? {
            val prefix = "hongguo-app:${board.jsGroup}:"
            // 1) 固定表命中且确实存在于当前返回里
            kind.boardId(board.jsGroup)?.let { pinned ->
                val full = prefix + pinned
                for (index in 0 until boards.length()) {
                    if (boards.optJSONObject(index)?.optString("id") == full) return full
                }
            }
            // 2) 名字兜底：同一分组下，名字以榜名结尾（"真人剧·热播榜" / "热播榜"）
            for (index in 0 until boards.length()) {
                val item = boards.optJSONObject(index) ?: continue
                val id = item.optString("id")
                if (!id.startsWith(prefix)) continue
                if (item.optString("name").endsWith(kind.label)) return id
            }
            return null
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
        internal fun theaterPageOf(page: JSONObject, typeOverride: ContentType? = null): TheaterCatalogPage {
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
                    contentType = typeOverride ?: ContentType.SHORT,
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

/** 简单的线程安全 LRU + TTL 缓存，值为脚本返回的原始 JSON 文本。 */
private class TtlCache(private val maxEntries: Int) {
    private class Entry(val payload: String, val expireAt: Long)

    private val map = object : LinkedHashMap<String, Entry>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>): Boolean =
            size > maxEntries
    }

    @Synchronized
    fun get(key: String): String? {
        val entry = map[key] ?: return null
        if (entry.expireAt <= System.currentTimeMillis()) {
            map.remove(key)
            return null
        }
        return entry.payload
    }

    @Synchronized
    fun put(key: String, payload: String, ttlMs: Long) {
        map[key] = Entry(payload, System.currentTimeMillis() + ttlMs)
    }
}
