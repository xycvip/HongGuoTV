// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import java.io.IOException
import org.json.JSONArray
import org.json.JSONObject

/** 云端收藏、预约和观看历史响应解析；与 HTTP 请求生命周期分离。 */
internal object CloudAccountParsers {

    fun episodeBadge(row: JSONObject): String {
        val count =
            listOf("episode_cnt", "episode_count", "drama_chapter_number")
                .map { row.optInt(it) }
                .firstOrNull { it > 0 } ?: 0
        return if (count > 0) "全 $count 集" else ""
        }

    fun parseFavorites(raw: JSONObject): List<CloudFavorite> {
        val data = raw.optJSONObject("data") ?: throw IOException("账户收藏数据格式已变化")
        if (!data.has("video_shelf_info")) throw IOException("账户收藏列表格式已变化")
        val rows =
            if (data.isNull("video_shelf_info")) JSONArray()
            else data.optJSONArray("video_shelf_info") ?: throw IOException("账户收藏列表格式已变化")
        val result =
            rows.objects().mapNotNull { row ->
                val video = row.optJSONObject("video_data") ?: row
                val id =
                    video
                        .str("series_id")
                        .ifBlank { row.str("series_id") }
                        .ifBlank { row.str("book_id") }
                val title =
                    row.str("series_title")
                        .ifBlank { video.str("title") }
                        .ifBlank { row.str("book_name") }
                if (!id.matches(Regex("[0-9]{1,30}")) || title.isBlank()) return@mapNotNull null
                val cover =
                    row.str("series_cover_url")
                        .ifBlank { video.str("cover") }
                        .ifBlank { video.str("cover_url") }
                        .ifBlank { video.str("thumb_url") }
                        .ifBlank { row.str("thumb_url") }
                val count =
                    video.optInt(
                        "episode_cnt",
                        video.optInt("episode_count", row.optInt("episode_cnt")),
                    )
                val tags =
                    runCatching {
                            JSONArray(row.str("category_schema"))
                                .objects()
                                .map { it.str("name") }
                                .filter(String::isNotBlank)
                                .take(5)
                                .joinToString(" · ")
                        }
                        .getOrDefault("")
                        .ifBlank { video.str("category") }
                val stamp =
                    row.optLong("collect_time").coerceAtLeast(0).let {
                        if (it in 1..9999999999L) it * 1000 else it
                    }
                CloudFavorite(
                    Series(
                        id,
                        title,
                        cleanUrl(cover),
                        row.str("series_intro")
                            .ifBlank { video.str("video_desc") }
                            .ifBlank { row.str("abstract") },
                        if (count > 0) "全 $count 集" else "",
                        tags,
                    ),
                    stamp,
                )
            }
        if (rows.length() > 0 && result.isEmpty()) throw IOException("账户收藏条目格式已变化")
        return result.sortedByDescending { it.collectedAt }.distinctBy { it.series.id }
        }

    fun parseReservations(raw: JSONObject, online: Boolean): List<CloudReservation> {
        val data = raw.optJSONObject("data") ?: throw IOException("预约数据格式已变化，请稍后重试")
        // A null list is the upstream's empty category; missing/non-array data is an error.
        if (!data.has("subscribe_items")) throw IOException("预约列表格式已变化，请稍后重试")
        val rows =
            if (data.isNull("subscribe_items")) JSONArray()
            else data.optJSONArray("subscribe_items") ?: throw IOException("预约列表格式已变化，请稍后重试")
        val result =
            rows
                .objects()
                .mapNotNull { row ->
                    val video =
                        row.optJSONObject("subscribe_data")
                            ?: row.optJSONObject("video_data")
                            ?: row
                    val seriesId =
                        video
                            .str("series_id")
                            .ifBlank { row.str("series_id") }
                            .ifBlank { row.str("book_id") }
                    // Unpublished entries use a flat reservation item, without a playable
                    // series ID.
                    // Namespace that key so it can never be mistaken for a playback ID.
                    val id =
                        if (seriesId.matches(Regex("[0-9]{1,30}"))) seriesId
                        else if (
                            !online &&
                                video === row &&
                                row.str("item_id").matches(Regex("[0-9]{1,30}"))
                        )
                            "reservation:" + row.str("item_id")
                        else return@mapNotNull null
                    val title =
                        row.str("series_title")
                            .ifBlank { video.str("title") }
                            .ifBlank { video.str("book_name") }
                            .ifBlank { row.str("name") }
                    if (title.isBlank()) return@mapNotNull null
                    if (row.has("is_online") && row.optBoolean("is_online") != online)
                        return@mapNotNull null
                    val cover =
                        row.str("series_cover_url")
                            .ifBlank { video.str("cover") }
                            .ifBlank { video.str("cover_url") }
                            .ifBlank { video.str("thumb_url") }
                    val stamp =
                        row.optLong("schedule_publish_time").coerceAtLeast(0).let {
                            if (it in 1..9999999999L) it * 1000 else it
                        }
                    val series =
                        Series(
                            id,
                            title,
                            cleanUrl(cover),
                            video
                                .str("video_desc")
                                .ifBlank { row.str("abstract") }
                                .ifBlank { row.str("item_desc") },
                            if (online)
                                episodeBadge(video)
                                    .ifBlank { episodeBadge(row) }
                                    .ifBlank { "已上线" }
                            else "待上线",
                            video.str("category"),
                        )
                    CloudReservation(series, online, stamp, row.str("expected_rec_text"))
                }
                .distinctBy { it.series.id }
        if (rows.length() > 0 && result.isEmpty()) throw IOException("预约条目格式已变化，请稍后重试")
        return result
        }

    fun parseProgress(rows: JSONArray): List<CloudProgress> =
        rows
            .objects()
            .mapNotNull { row ->
                val id = row.str("series_id").ifEmpty { row.str("book_id") }
                val episode = row.str("vid")
                if (!id.matches(Regex("[0-9]{1,30}")) || !episode.matches(Regex("[0-9]{1,30}")))
                    return@mapNotNull null
                val stamp =
                    row.optLong("last_watch_time").let {
                        if (it in 1..9999999999L) it * 1000 else it
                    }
                CloudProgress(
                    Series(id, ""),
                    episode,
                    (row.optInt("vid_index", 1) - 1).coerceAtLeast(0),
                    row.optLong("current_play_time").coerceAtLeast(0),
                    row.optLong("current_total_time").coerceAtLeast(0),
                    stamp,
                    row.optInt("episode_count").coerceAtLeast(0),
                )
            }
            .distinctBy { it.series.id }
            .sortedByDescending { it.updatedAt }
}
