// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/** A page of dramas associated with one official celebrity profile. */
data class ActorWorksPage(
    val items: List<Series>,
    val nextOffset: Int,
    val hasMore: Boolean,
    val totalCount: Int,
)

internal object ActorWorksParser {
    fun parse(raw: String, actorId: String, requestedOffset: Int, pageSize: Int): ActorWorksPage {
        try {
            val root = JSONObject(raw)
            val code = root.opt("code")?.toString().orEmpty()
            if (code.isNotBlank() && code != "0") {
                val message = root.optString("message").ifBlank { root.optString("msg") }
                throw IOException("演员作品接口返回错误（code=$code" +
                    (if (message.isNotBlank()) ", $message" else "") + "）")
            }
            val data = root.optJSONObject("data")
                ?: throw IOException("演员作品响应缺少 data")
            val works = data.optJSONObject("works")
                ?: throw IOException("演员作品响应缺少 works")
            val actor = works.optJSONObject(actorId)
                ?: throw IOException("演员作品响应中没有当前演员（actor_id=$actorId）")
            val videos = actor.optJSONArray("video_list") ?: JSONArray()
            val total = actor.optInt("total_count", videos.length()).coerceAtLeast(videos.length())
            val start = if (videos.length() > pageSize) requestedOffset.coerceAtMost(videos.length()) else 0
            val end = minOf(videos.length(), start + pageSize)
            val items = mutableListOf<Series>()
            val seen = hashSetOf<String>()
            for (index in start until end) {
                val row = videos.optJSONObject(index) ?: continue
                val id = first(row, "series_id", "book_id", "id")
                val title = first(row, "title", "series_title", "series_name")
                if (!id.matches(Regex("[0-9]{1,30}")) || title.isBlank() || !seen.add(id)) continue
                val episodeCount = row.optInt("episode_cnt", 0)
                    .takeIf { it > 0 }
                    ?: Regex("(\\d+)\\s*集").find(first(row, "sub_title", "subtitle"))
                        ?.groupValues?.getOrNull(1)?.toIntOrNull()
                    ?: 0
                val subtitle = first(row, "sub_title", "subtitle", "episode_right_text")
                val badge = subtitle.ifBlank { if (episodeCount > 0) "全 $episodeCount 集" else first(row, "rec_text", "score") }
                items += Series(
                    id = id,
                    title = title,
                    cover = cleanUrl(first(row, "cover", "series_cover", "cover_url")),
                    description = first(row, "video_desc", "description", "series_intro"),
                    badge = badge,
                    tags = first(row, "rec_text", "score"),
                )
            }
            // Advance by consumed upstream rows, not only valid mapped rows, so malformed
            // entries cannot pin the cursor and cause the same page to be requested forever.
            val nextOffset = requestedOffset + (end - start)
            val hasMore = actor.optBoolean("has_more", false) || nextOffset < total ||
                (videos.length() > end)
            return ActorWorksPage(items, nextOffset, hasMore, total)
        } catch (error: IOException) {
            throw error
        } catch (error: Exception) {
            throw IOException("演员作品数据解析失败", error)
        }
    }

    private fun first(row: JSONObject, vararg keys: String): String {
        for (key in keys) {
            val value = row.opt(key)
            if (value is String && value.isNotBlank() && value != "null") return value.trim()
            if (value is Number && value.toLong() > 0L) return value.toString()
        }
        return ""
    }
}
