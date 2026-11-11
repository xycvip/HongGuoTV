// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.InterruptedIOException
import java.util.Locale

/** App detail is authoritative; the website SSR remains a compatibility fallback. */
internal class DetailRepository(
    private val site: String,
    private val requestText: (String) -> String,
    private val requestAppDetail: (String) -> JSONObject,
    private val card: (JSONObject) -> Series,
    private val valid: (List<Series>) -> List<Series>,
    private val searchRepository: SearchRepository
) {
    fun detail(id: String): Detail {
        require(id.matches(Regex("[0-9]{1,30}")))
        var appFailure: Exception? = null
        try {
            return parseAppDetail(requestAppDetail(id), id)
        } catch (error: InterruptedIOException) {
            throw error
        } catch (error: Exception) {
            appFailure = error
        }

        // App-native detail is the primary source. Only an unusable App response falls back to SSR.
        try {
            return parseWebsiteDetail(id)
        } catch (error: InterruptedIOException) {
            throw error
        } catch (webFailure: Exception) {
            throw IOException(
                "详情暂不可用（App：${appFailure?.message ?: "未知错误"}；网页：${webFailure.message ?: "未知错误"}）",
                webFailure
            )
        }
    }

    private fun parseAppDetail(data: JSONObject, requestedId: String): Detail {
        val o = data.optJSONObject("video_data") ?: throw IOException("App 详情缺少 video_data")
        val returnedId = first(o.str("series_id_str"), o.str("series_id"), o.str("book_id"))
        if (returnedId != requestedId) throw IOException("App 返回的剧集 ID 与请求不一致")

        val title = first(o.str("series_title"), o.str("series_name"), o.str("title"), o.str("name"))
        if (title.isBlank()) throw IOException("App 详情缺少剧名")
        val tags = tagNames(o)
        val count = maxOf(
            o.optInt("episode_cnt", 0),
            o.optInt("episode_count", 0),
            o.optInt("drama_chapter_number", 0),
            o.optInt("chapter_number", 0),
            o.optJSONObject("video_detail")?.optInt("episode_cnt", 0) ?: 0
        )
        val remark = o.str("episode_right_text").ifBlank { if (count > 0) "共${count}集" else "" }
        val cover = first(o.str("series_cover"), o.str("cover"), o.str("cover_url"), o.str("thumb_url"), o.str("expand_thumb_url"), o.optJSONObject("video_detail")?.str("cover_url").orEmpty())
        val series = Series(
            requestedId, title, cleanUrl(cover),
            first(o.str("series_intro"), o.str("video_desc"), o.str("abstract"), o.str("intro"), o.str("description")),
            remark, tags.take(5).joinToString(" · ")
        )

        val rawEpisodes = o.optJSONArray("video_list") ?: throw IOException("App 详情缺少 video_list")
        val episodes = (0 until rawEpisodes.length()).map { index ->
            val item = rawEpisodes.optJSONObject(index) ?: throw IOException("App 分集数据格式异常")
            val videoId = item.str("vid").trim()
            val number = item.optInt("vid_index", 0)
            if (!videoId.matches(Regex("[0-9]{1,30}")) || number < 1) throw IOException("App 分集编号或 VID 无效")
            val episodeSeriesId = item.str("series_id").trim()
            if (episodeSeriesId.isNotBlank() && episodeSeriesId != requestedId) throw IOException("App 返回了其他剧集的分集")
            DetailEpisode(
                videoId = videoId,
                number = number,
                title = item.str("title").ifBlank { "第${number}集" },
                durationSeconds = item.optDouble("duration", 0.0).coerceAtLeast(0.0),
                playable = !item.optBoolean("disable_play", false),
                vertical = item.optBoolean("vertical", false),
                newlyUpdated = item.optBoolean("is_newly_update", false),
                cover = cleanUrl(first(item.str("cover"), item.str("episode_cover"))),
                commentCount = item.optLong("comment_count", 0L),
                diggCount = item.optLong("digged_count", 0L)
            )
        }.sortedBy { it.number }

        if (episodes.isEmpty() ||
            episodes.map { it.videoId }.distinct().size != episodes.size ||
            episodes.map { it.number }.distinct().size != episodes.size ||
            episodes.withIndex().any { (index, episode) -> episode.number != index + 1 } ||
            count > episodes.size
        ) throw IOException("App 未返回完整且连续的分集")

        val rating = first(o.str("final_score_v2"), o.str("final_score"), o.str("score")).toDoubleOrNull() ?: 0.0
        val favorite = compactCount(first(o.str("followed_cnt"), o.str("series_favorite_count")))
        val heat = first(o.optJSONObject("hot_score_data")?.str("text").orEmpty(), o.str("hot_score"))
        val social = SeriesSocial(
            rating = rating,
            ratingCount = compactCount(o.str("rating_count")),
            favoriteText = if (favorite.isBlank()) "" else "${favorite}收藏",
            heatText = heat
        )
        val novel = o.optJSONObject("video_relate_book")?.optJSONObject("book_info")
        val credits = mutableListOf<String>()
        listOf("main_creator_users", "celebrities").forEach { key ->
            o.optJSONArray(key)?.objects()?.forEach { person ->
                first(person.str("name"), person.optJSONObject("base_info")?.str("name").orEmpty())
                    .takeIf { it.isNotBlank() }?.let(credits::add)
            }
        }
        val metadata = DetailMetadata(
            category = first(o.str("category_name"), o.str("categoryName"), o.str("category"), tags.firstOrNull().orEmpty()),
            remark = remark,
            episodeCount = maxOf(count, episodes.size),
            releaseStatus = first(o.str("series_status"), o.str("status")),
            heat = heat,
            onlineDate = o.str("first_visible_time"),
            isMultiSeason = o.optBoolean("is_multi_season", false) || first(o.str("related_album_id"), o.str("album_id")).isNotBlank(),
            seasonIndex = o.optInt("season_index", 1).coerceAtLeast(1),
            albumId = first(o.str("related_album_id"), o.str("album_id")),
            novelBookId = novel?.str("book_id").orEmpty(),
            novelTitle = first(novel?.str("book_name").orEmpty(), novel?.str("book_short_name").orEmpty()),
            playCount = first(o.str("series_play_cnt"), o.str("play_cnt")).toLongOrNull() ?: 0L,
            followCount = o.optLong("followed_cnt", 0L),
            diggCount = o.optLong("digg_cnt", 0L),
            credits = credits.distinct()
        )
        return Detail(series, episodes.map { it.videoId }, emptyList(), emptyList(), social, metadata, episodes)
    }

    private fun parseWebsiteDetail(id: String): Detail {
        val html = requestText("$site/detail?series_id=$id")
        val loader = ContentRepository.extractRouter(html).getJSONObject("loaderData")
        val o = loader.optJSONObject("detail_page")?.optJSONObject("seriesDetail") ?: throw IOException("剧集详情暂不可用")
        val raw = o.optJSONArray("vid_list") ?: throw IOException("分集暂不可用")
        val episodes = (0 until raw.length()).map { raw.getString(it) }
        if (episodes.isEmpty() || episodes.any { !it.matches(Regex("[0-9]{1,30}")) } || episodes.distinct().size != episodes.size) throw IOException("分集数据异常")
        val series = card(o).copy(id = id)
        val related = related(html, series)
        val social = social(loader.optJSONObject("detail_page")?.optJSONObject("seriesSocialInfo"))
        return Detail(
            series, episodes, related, emptyList(), social,
            DetailMetadata(remark = series.badge, episodeCount = episodes.size),
            episodes.mapIndexed { index, vid -> DetailEpisode(vid, index + 1, "第${index + 1}集") }
        )
    }

    private fun tagNames(o: JSONObject): List<String> {
        val rows = o.optJSONArray("tags") ?: o.optJSONArray("category_list") ?: JSONArray()
        return (0 until rows.length()).mapNotNull { index ->
            val item = rows.opt(index)
            val value = if (item is JSONObject) first(item.str("name"), item.str("content")) else item?.toString().orEmpty()
            value.trim().takeIf { it.isNotBlank() }
        }.distinct()
    }

    private fun first(vararg values: String): String = values.firstOrNull { it.isNotBlank() }?.trim().orEmpty()

    private fun compactCount(raw: String): String {
        val value = raw.trim().toLongOrNull() ?: return ""
        if (value < 1) return ""
        fun trim(number: Double, unit: String) = String.format(Locale.CHINA, "%.1f", number).removeSuffix(".0") + unit
        return when {
            value >= 100_000_000 -> trim(value / 100_000_000.0, "亿")
            value >= 10_000 -> trim(value / 10_000.0, "万")
            else -> value.toString()
        }
    }

    // 评分与收藏数只在详情页 SSR 里下发，卡片数据源没有这些字段，因此只读取不追加请求。
    private fun social(o: JSONObject?): SeriesSocial {
        if (o == null) return SeriesSocial()
        val favorite = compactCount(o.str("series_favorite_count"))
        return SeriesSocial(
            rating = o.optDouble("rating", 0.0).takeIf { it > 0 } ?: 0.0,
            ratingCount = compactCount(o.str("rating_count")),
            favoriteText = if (favorite.isBlank()) "" else "${favorite}收藏",
            heatText = o.optJSONObject("hot_score_data")?.str("text").orEmpty()
        )
    }

    // The detail page has no dedicated same-name group, so other seasons are collected from its related
    // payload and filled with the same website/suggestion pipeline used by search.
    private fun sameName(current: Series, related: List<Series>): List<Series> {
        val base = baseName(current.title)
        if (base.isBlank()) return emptyList()
        val siblings = related.filter { it.id != current.id && baseName(it.title) == base }.distinctBy { it.id }
        val extra = searchRepository.collectSeasons(base, listOf(current) + siblings, null, listOf(base, base.take(4)), "detail|$base", 20)
        return (siblings + extra).distinctBy { it.id }.filter { it.id != current.id }
            .sortedBy { seasonNumber(it.title).let { n -> if (n == Int.MAX_VALUE) 1 else n } }.take(20)
    }

    /** Called by the UI after the basic detail page is already visible. */
    fun sameNameSeasons(current: Series, related: List<Series>): List<Series> = sameName(current, related)

    // The website embeds recommendation data in an HTML-escaped data-fn-args attribute.
    private fun related(html: String, current: Series): List<Series> {
        val marker = "data-fn-args=\""
        val start = html.indexOf(marker)
        if (start < 0) return emptyList()
        val from = start + marker.length
        val end = html.indexOf('"', from)
        if (end < 0) return emptyList()
        val args = runCatching {
            JSONArray(decodeEntities(html.substring(from, end)))
                .optJSONArray(1)?.optJSONObject(0)?.optJSONArray("routerDataFnArgs")?.optString(0).orEmpty()
        }.getOrNull() ?: return emptyList()
        if (args.isEmpty()) return emptyList()
        val groups = runCatching { JSONObject(args).optJSONObject("recommendationGroups") }.getOrNull() ?: return emptyList()
        val rows = groups.optJSONArray("related_list") ?: groups.optJSONArray("hot_list") ?: return emptyList()
        val items = valid(rows.objects().map(card)).filter { it.id != current.id }
        val key = current.title.trim()
        if (key.isEmpty()) return items
        return items.sortedBy { item ->
            when {
                item.title.equals(key, ignoreCase = true) -> 0
                item.title.startsWith(key) || key.startsWith(item.title) -> 1
                else -> 2
            }
        }
    }

    private fun decodeEntities(value: String) = value.replace("&quot;", "\"").replace("&lt;", "<").replace("&gt;", ">").replace("&#39;", "'").replace("&amp;", "&")
}
