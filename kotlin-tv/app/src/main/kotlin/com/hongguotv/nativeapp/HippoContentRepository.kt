// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import android.util.Base64
import android.util.Log
import com.hongguotv.core.*
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * 河马剧场的 ContentRepository 适配层。
 * UI 继续使用现有 Series/CatalogPage/Detail 模型，但所有数据请求均由独立 HippoPortalApi 完成。
 */
internal class HippoContentRepository(context: Context, http: OkHttpClient = OkHttpClient(), private val watchRecords: () -> List<String> = { emptyList() }) : ContentRepository(http) {
    private val api = HippoPortalApi(context, http)
    private val homeCursors = ConcurrentHashMap<String, String>()
    private val seriesCache = ConcurrentHashMap<String, Series>()
    private val lockedEpisodeIds = ConcurrentHashMap.newKeySet<String>()
    private val theatreCursors = ConcurrentHashMap<String, JSONObject>()

    override fun home(page: Int, type: ContentType, genre: Genre?): CatalogPage {
        require(page in 1..100)
        api.ensureInitialized()
        val key = "home:${type.name}:${genre?.slug.orEmpty()}"
        val cursor = if (page <= 1) "" else homeCursors[key].orEmpty()
        val params = JSONObject().put("pageFlag", cursor).put("watchRecords", JSONArray().apply { watchRecords().forEach { put(it) } })
        val data = api.request("1113", params)
        val rows = findList(data, "dataList")
        val items = rows.mapNotNull(::series).filter { it.id.isNotBlank() }
        homeCursors[key] = data.optString("pageFlag")
        return CatalogPage(items, data.optBoolean("hasMore"))
    }

    override fun theater(page: Int, type: ContentType?, selected: Map<String, String>): TheaterCatalogPage {
        api.ensureInitialized()
        val key = selected["channelGroupId"].orEmpty() + ":" + selected["channelId"].orEmpty()
        val previous = theatreCursors[key]
        val params = JSONObject()
        selected["channelGroupId"]?.takeIf { it.isNotBlank() }?.let { params.put("channelGroupId", it) }
        selected["channelId"]?.takeIf { it.isNotBlank() }?.let { params.put("channelId", it.toIntOrNull() ?: it) }
        if (page > 1 && previous != null) {
            listOf("pageFlag", "fromColumnId", "fromItemId", "lastColumnStyle").forEach { field ->
                if (previous.has(field) && !previous.isNull(field)) params.put(field, previous.opt(field))
            }
        }
        val data = try { api.request("1125", params) } catch (first: Exception) {
            params.put("recSwitch", true).put("playListFlag", true)
            try { api.request("1121", params) } catch (second: Exception) {
                second.addSuppressed(first); throw second
            }
        }
        val rawGroups = findList(data, "channelGroupData")
        val seenBookGroups = mutableSetOf<String>()
        val groups = rawGroups.filter { group ->
            val isBookGroup = value(group, "channelGroupType") == "2"
            !isBookGroup || seenBookGroups.add(value(group, "channelGroupName", "title").trim())
        }
        val selectedGroupId = selected["channelGroupId"]
            ?: groups.firstOrNull { it.optBoolean("checkedFlag") }?.let { value(it, "channelGroupId", "id") }
            ?: groups.firstOrNull()?.let { value(it, "channelGroupId", "id") }.orEmpty()
        val selectedGroup = groups.firstOrNull { value(it, "channelGroupId", "id") == selectedGroupId }
            ?: groups.firstOrNull()
        val groupOptions = groups.mapNotNull { group ->
            val id = value(group, "channelGroupId", "id")
            val label = value(group, "channelGroupName", "title")
            if (id.isBlank() || label.isBlank()) null else TheaterFilterOption(id, label)
        }
        val channelRows = (selectedGroup?.optJSONArray("channelData") ?: JSONArray()).objects()
        val channelOptions = channelRows.mapNotNull { channel ->
            val id = value(channel, "channelId", "id")
            val label = value(channel, "channelName", "title")
            if (id.isBlank() || label.isBlank()) null else TheaterFilterOption(id, label)
        }
        val selectedChannelId = selected["channelId"]
            ?: channelRows.firstOrNull { it.optBoolean("checkedFlag") }?.let { value(it, "channelId", "id") }
            ?: channelOptions.firstOrNull()?.id
        val filters = buildList {
            if (groupOptions.isNotEmpty()) add(TheaterFilterGroup("channelGroupId", "分类", groupOptions))
            if (channelOptions.isNotEmpty()) add(TheaterFilterGroup("channelId", selectedGroup?.let { value(it, "channelGroupName", "title") } ?: "频道", channelOptions))
        }
        val columns = findList(data, "columnData")
        theatreCursors[key] = JSONObject().also { cursor ->
            listOf("pageFlag", "fromColumnId", "fromItemId").forEach { f -> if (data.has(f)) cursor.put(f, data.opt(f)) }
            columns.lastOrNull()?.optInt("styleType")?.takeIf { it > 0 }?.let { cursor.put("lastColumnStyle", it) }
        }
        val items = columns.flatMap { column -> findList(column, "videoData") }.mapNotNull(::series)
        return TheaterCatalogPage(CatalogPage(items, data.optBoolean("hasMore")), filters,
            if (data.optBoolean("hasMore")) page + 1 else null, "hippo:$key")
    }

    override fun theaterFilters(type: ContentType): List<TheaterFilterGroup> = theater(1, type).filters

    override fun genres(type: ContentType): List<Genre> = theaterFilters(type).map { group ->
        Genre(group.type, group.label, group.options.map { it.id })
    }

    override fun ranking(board: RankBoard, page: Int): CatalogPage {
        api.ensureInitialized()
        val data = api.request("1801")
        val ranks = findList(data, "rankVos")
        val selected = ranks.firstOrNull { row ->
            val name = value(row, "rankName", "name")
            when (board) {
                RankBoard.AI -> name.contains("AI")
                RankBoard.COMIC -> name.contains("漫")
                RankBoard.REAL -> name.contains("短剧")
                else -> name.contains("热") || name.contains("综合")
            }
        } ?: ranks.firstOrNull()
        val dramas = selected?.let { findList(it, "recommendHots") } ?: emptyList()
        return CatalogPage(dramas.mapNotNull(::series), false)
    }

    override fun search(keyword: String, page: Int, type: ContentType): CatalogPage {
        if (keyword.isBlank()) return CatalogPage(emptyList(), false)
        api.ensureInitialized()
        val data = api.request("1803", JSONObject().put("keyword", keyword).put("page", page).put("size", 15)
            .put("searchSource", "搜索按钮").put("hotWordType", 2))
        val rows = listOf("searchVos", "guessLikes", "dataList").firstNotNullOfOrNull { key ->
            findList(data, key).takeIf { it.isNotEmpty() }
        }.orEmpty()
        val more = data.optBoolean("isMore") || data.optInt("isMore") == 1 || data.optBoolean("hasMore")
        return CatalogPage(rows.mapNotNull(::series), more)
    }

    override fun searchCombined(keyword: String, page: Int): CatalogPage = search(keyword, page, ContentType.SHORT)

    override fun probeSuggest(query: String): Int = suggest(query).size

    override fun sameNameSeasons(current: Series, related: List<Series>): List<Series> = emptyList()

    override fun suggest(query: String): List<String> {
        api.ensureInitialized()
        val data = api.request("1801")
        val wordVo = data.optJSONObject("searchWordVo")
        val words = wordVo?.optJSONArray("data") ?: JSONArray()
        return words.objects().map { value(it, "tags") }.filter { it.isNotBlank() }.distinct()
    }

    override fun detail(id: String): Detail {
        val (bookId, _) = decodeEpisodeId(id)
        api.ensureInitialized()
        val data = api.request("1132", JSONObject().put("bookId", bookId).put("chapterMin", 0).put("chapterMax", 100))
        val chapters = findList(data, "chapterList").sortedBy { it.optInt("chapterIndex", it.optInt("index", 0)) }
        val episodes = chapters.mapNotNull { chapter ->
            val chapterId = value(chapter, "chapterId", "chapter_id", "chapterid", "id")
            chapterId.takeIf { it.isNotBlank() }?.let {
                val encoded = encodeEpisodeId(bookId, it)
                if (chapter.optInt("isCharge", 0) == 1) lockedEpisodeIds.add(encoded)
                else lockedEpisodeIds.remove(encoded)
                encoded
            }
        }
        val info = findObject(data, setOf("bookInfoVo", "bookInfo", "bookVo"))
        val series = seriesCache[bookId] ?: Series(bookId, value(info, "bookName", "title").ifBlank { "河马短剧" },
            value(info, "coverWap", "coverCutWap", "coverUrl"), value(info, "introduction", "description", "bookDesc"),
            value(info, "finishStatusCn"), value(info, "bookTags", "tags"))
        return Detail(series, episodes)
    }

    override fun stream(id: String, maxQuality: Int): StreamInfo {
        if (lockedEpisodeIds.contains(id)) throw IOException("该集为付费内容，已按原平台规则阻止播放")
        val (bookId, chapterId) = decodeEpisodeId(id)
        if (chapterId.isBlank()) throw IOException("河马播放请求缺少 chapterId")
        api.ensureInitialized()
        val data = api.request("1133", JSONObject().put("bookId", bookId).put("chapterId", chapterId)
            .put("unClockType", "pay").put("confirmPay", 1).put("autoPayFlag", false))
        val rows = findList(data, "chapterInfo")
        val row = rows.firstOrNull { value(it, "chapterId", "chapter_id") == chapterId } ?: rows.firstOrNull() ?: data
        val urls = collectUrls(row)
        val url = urls.firstOrNull() ?: throw IOException("河马播放接口未返回可用地址")
        val mime = if (url.substringBefore('?').endsWith(".m3u8", true)) "application/x-mpegURL" else "video/mp4"
        return StreamInfo(url, null, if (url.contains("1080")) "1080P" else "720P", mime)
    }

    fun rememberSeries(series: Series) { seriesCache[series.id] = series }

    private fun series(row: JSONObject): Series? {
        val episodeId = value(row, "chapterId", "id")
        val bookId = value(row, "bookId", "book_id", "bookid").ifBlank { value(row, "id") }
        val id = bookId.ifBlank { episodeId }
        val title = value(row, "bookName", "title", "name")
        if (id.isBlank() || title.isBlank()) return null
        val count = row.optInt("updateNum", row.optInt("chapterCount", row.optInt("totalChapterNum", 0)))
        val tags = strings(row.opt("bookTags")).ifEmpty { strings(row.opt("tags") ) }.joinToString(" · ")
        val badge = value(row, "finishStatusCn", "chapterName", "coverBottomTips")
        val mapped = Series(id, title, value(row, "coverWap", "coverCutWap", "coverUrl", "chapterImg"),
            value(row, "introduction", "description", "bookDesc"), badge.ifBlank { if (count > 0) "全 $count 集" else "" }, tags)
        seriesCache[id] = mapped
        while (seriesCache.size > 500) {
            val oldest = seriesCache.keys.firstOrNull() ?: break
            seriesCache.remove(oldest)
        }
        return mapped
    }

    private fun findList(root: JSONObject, key: String): List<JSONObject> {
        if (root.opt(key) is JSONArray) return root.optJSONArray(key)!!.objects()
        val keys = root.keys()
        while (keys.hasNext()) {
            val value = root.opt(keys.next())
            val found = when (value) {
                is JSONObject -> findList(value, key)
                is JSONArray -> value.objects().firstNotNullOfOrNull { findList(it, key).takeIf { rows -> rows.isNotEmpty() } }.orEmpty()
                else -> emptyList()
            }
            if (found.isNotEmpty()) return found
        }
        return emptyList()
    }

    private fun findObject(root: JSONObject, names: Set<String>): JSONObject {
        names.firstNotNullOfOrNull { root.optJSONObject(it) }?.let { return it }
        val keys = root.keys()
        while (keys.hasNext()) when (val value = root.opt(keys.next())) {
            is JSONObject -> findObject(value, names).takeIf { it.length() > 0 }?.let { return it }
            is JSONArray -> value.objects().forEach { findObject(it, names).takeIf { o -> o.length() > 0 }?.let { return it } }
        }
        return JSONObject()
    }

    private fun collectUrls(root: JSONObject): List<String> {
        val priority = listOf(
            "m3u81080pUrl", "m3u81080p", "mp41080pUrl", "mp41080p",
            "m3u8720pUrl", "m3u8720p", "mp4720pUrl", "mp4720p",
            "mp4", "m3u8", "url",
            "m3u81080pSwitchUrl", "mp41080pSwitchUrl",
            "m3u8720pSwitchUrl", "mp4720pSwitchUrl"
        )
        val found = linkedMapOf<String, LinkedHashSet<String>>()
        fun visit(value: Any?) {
            when (value) {
                is JSONObject -> {
                    val iterator = value.keys()
                    while (iterator.hasNext()) {
                        val key = iterator.next()
                        val child = value.opt(key)
                        if (key in priority) {
                            when (child) {
                                is String -> if (child.startsWith("http")) found.getOrPut(key) { linkedSetOf() }.add(child)
                                is JSONArray -> for (index in 0 until child.length()) {
                                    val url = child.optString(index)
                                    if (url.startsWith("http")) found.getOrPut(key) { linkedSetOf() }.add(url)
                                }
                                else -> visit(child)
                            }
                        } else visit(child)
                    }
                }
                is JSONArray -> for (index in 0 until value.length()) visit(value.opt(index))
            }
        }
        visit(root)
        return priority.flatMap { found[it].orEmpty() }
    }

    private fun encodeEpisodeId(book: String, chapter: String) = Base64.encodeToString("$book\u0000$chapter".toByteArray(), Base64.NO_WRAP or Base64.URL_SAFE)
    private fun decodeEpisodeId(id: String): Pair<String, String> {
        val decoded = runCatching { String(Base64.decode(id, Base64.NO_WRAP or Base64.URL_SAFE)) }.getOrNull()
        val parts = decoded?.split('\u0000', limit = 2)
        // 详情入口传入原始 bookId；播放入口才传入编码后的 bookId/chapterId。
        if (parts == null) return id to ""
        if (parts.size != 2 || parts.any { it.isBlank() }) throw IOException("河马剧集标识无效")
        return parts[0] to parts[1]
    }
    private fun value(row: JSONObject, vararg keys: String): String = keys.firstNotNullOfOrNull { key ->
        row.opt(key)?.takeIf { it != JSONObject.NULL }?.toString()?.takeIf { it.isNotBlank() }
    }.orEmpty()
    private fun strings(raw: Any?): List<String> = when(raw) {
        is JSONArray -> (0 until raw.length()).mapNotNull { raw.opt(it)?.toString()?.takeIf(String::isNotBlank) }
        is String -> listOf(raw)
        else -> emptyList()
    }
    private fun JSONArray.objects() = (0 until length()).mapNotNull { optJSONObject(it) }
    private fun JSONArray.objectsOrStrings() = (0 until length()).mapNotNull { optString(it).takeIf(String::isNotBlank) }
}
