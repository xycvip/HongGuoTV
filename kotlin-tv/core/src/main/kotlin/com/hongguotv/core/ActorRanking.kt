// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import org.json.JSONObject
import java.io.IOException

/** A single person in the official short-drama actor ranking. */
data class ActorRankEntry(
    val userId: String,
    val actorId: String,
    val name: String,
    val avatar: String,
    val popularity: String,
    val intro: String,
    val details: List<String>,
    val position: Int,
    val changeType: Int = 0,
    val changePlaces: Int = 0,
)

/** One page returned by cell/change; the session and offset must travel together. */
data class ActorRankPage(
    val items: List<ActorRankEntry>,
    val nextOffset: Int,
    val hasMore: Boolean,
    val sessionId: String,
    val version: String,
)

/** Reuse the catalog grid's visual card model without treating an actor as a playable series. */
fun ActorRankEntry.toSeries() = Series(
    id = userId,
    title = name,
    cover = avatar,
    description = intro,
    badge = popularity,
    tags = details.joinToString(" · "),
)

internal object ActorRankParser {
    fun parse(raw: String, requestedOffset: Int): ActorRankPage {
        try {
            val root = JSONObject(raw)
            val code = root.opt("code")?.toString().orEmpty()
            if (code.isNotBlank() && code != "0") {
                throw IOException("演员榜接口返回错误（code=$code）")
            }
            val data = root.optJSONObject("data") ?: throw IOException("演员榜响应缺少 data")
            val view = data.optJSONObject("cell_view") ?: throw IOException("演员榜响应缺少 cell_view")
            val cells = view.optJSONArray("cell_data") ?: throw IOException("演员榜响应缺少 cell_data")
            val items = mutableListOf<ActorRankEntry>()
            for (cellIndex in 0 until cells.length()) {
                val cell = cells.optJSONObject(cellIndex) ?: continue
                val users = cell.optJSONArray("ugc_user_data") ?: continue
                for (userIndex in 0 until users.length()) {
                    val user = users.optJSONObject(userIndex) ?: continue
                    val info = user.optJSONObject("user_info") ?: continue
                    val base = info.optJSONObject("base_info") ?: continue
                    val userId = base.opt("user_id")?.toString()?.trim().orEmpty()
                    val actorId = base.opt("actor_id_str")?.toString()?.takeIf { it.isNotBlank() }
                        ?: base.opt("actor_id")?.toString().orEmpty()
                    val name = base.optString("user_name").trim()
                    if (!userId.matches(Regex("[0-9]{1,30}")) || name.isBlank()) continue
                    val detailsArray = user.optJSONArray("sub_title_list")
                    val details = buildList {
                        if (detailsArray != null) for (i in 0 until detailsArray.length()) {
                            val value = detailsArray.optJSONObject(i)?.optString("content").orEmpty().trim()
                            if (value.isNotBlank()) add(value)
                        }
                    }
                    val change = user.optJSONObject("rank_change_data")
                    items += ActorRankEntry(
                        userId = userId,
                        actorId = actorId,
                        name = name,
                        avatar = cleanUrl(base.optString("user_avatar")),
                        popularity = user.optJSONObject("rec_text_item")?.optString("RecommendText").orEmpty(),
                        intro = user.optString("intro").trim(),
                        details = details,
                        position = requestedOffset + items.size + 1,
                        changeType = change?.optInt("rank_change_type") ?: 0,
                        changePlaces = change?.optInt("ChangeNum") ?: 0,
                    )
                }
            }
            if (requestedOffset == 0 && items.isEmpty()) throw IOException("演员榜暂无数据或接口返回格式已变化")
            val reportedOffset = data.optInt("next_offset", requestedOffset + items.size)
            val nextOffset = if (reportedOffset > requestedOffset) reportedOffset else requestedOffset + items.size
            return ActorRankPage(
                items = items,
                nextOffset = nextOffset,
                hasMore = data.optBoolean("has_more", false) && items.isNotEmpty() && nextOffset > requestedOffset,
                sessionId = data.optString("session_id"),
                version = data.optString("rank_version"),
            )
        } catch (error: IOException) {
            throw error
        } catch (error: Exception) {
            throw IOException("演员榜数据解析失败", error)
        }
    }
}
