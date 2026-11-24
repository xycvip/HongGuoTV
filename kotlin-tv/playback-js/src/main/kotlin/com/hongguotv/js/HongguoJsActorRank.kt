// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.js

import com.hongguotv.core.ActorRankEntry
import com.hongguotv.core.ActorRankPage
import com.hongguotv.core.ActorWorksPage
import com.hongguotv.core.Series
import org.json.JSONObject
import java.io.IOException

/**
 * 演员榜 / 演员作品的热更新 JS 门面。
 *
 * 数据与解析全部在 main.js 内完成（src/actor-rank.js，走 vendor 的
 * fetchAppDiscoveryRaw：设备注册 + Ht 签名 + 白名单重试）。本类只做三件事：
 * 组参数、调脚本、把返回 JSON 映射成 core 的 ActorRankPage / ActorWorksPage，
 * 字段与原生实现逐项对齐，调用方无感切换。
 *
 * 分页语义（与原生一致）：
 *   actorRanking 首次 offset=0（tab_type=26），翻页带 sessionId（tab_type=18）；
 *   actorWorks   游标按上游消耗行数推进，畸形条目不会把游标钉死。
 */
class HongguoJsActorRank(
    private val js: HongguoJs,
) {
    suspend fun actorRanking(
        offset: Int = 0,
        sessionId: String = "",
        subBoard: String = "ranklist_series_actor",
    ): ActorRankPage {
        val payload = JSONObject()
            .put("offset", offset)
            .put("sessionId", sessionId)
            .put("subBoard", subBoard)
        val result = js.call("actorRanking", payload)
        if (!result.ok) throw mapScriptError(result)
        return parseRank(JSONObject(result.data ?: "{}"))
    }

    suspend fun actorWorks(
        actorId: String,
        offset: Int = 0,
        pageSize: Int = 20,
    ): ActorWorksPage {
        val payload = JSONObject()
            .put("actorId", actorId)
            .put("offset", offset)
            .put("pageSize", pageSize)
        val result = js.call("actorWorks", payload)
        if (!result.ok) throw mapScriptError(result)
        return parseWorks(JSONObject(result.data ?: "{}"))
    }

    private fun mapScriptError(result: JsCallResult): IOException = IOException(
        result.error ?: "演员接口调用失败（${result.code}）"
    )

    private fun parseRank(json: JSONObject): ActorRankPage {
        val rows = json.optJSONArray("items")
        val items = buildList {
            if (rows != null) {
                for (index in 0 until rows.length()) {
                    val row = rows.optJSONObject(index) ?: continue
                    add(
                        ActorRankEntry(
                            userId = row.optString("userId"),
                            actorId = row.optString("actorId"),
                            name = row.optString("name"),
                            avatar = row.optString("avatar"),
                            popularity = row.optString("popularity"),
                            intro = row.optString("intro"),
                            details = row.optJSONArray("details")?.let { details ->
                                (0 until details.length()).mapNotNull { details.optString(it).ifBlank { null } }
                            } ?: emptyList(),
                            position = row.optInt("position"),
                            changeType = row.optInt("changeType"),
                            changePlaces = row.optInt("changePlaces"),
                        )
                    )
                }
            }
        }
        return ActorRankPage(
            items = items,
            nextOffset = json.optInt("nextOffset"),
            hasMore = json.optBoolean("hasMore"),
            sessionId = json.optString("sessionId"),
            version = json.optString("version"),
        )
    }

    private fun parseWorks(json: JSONObject): ActorWorksPage {
        val rows = json.optJSONArray("items")
        val items = buildList {
            if (rows != null) {
                for (index in 0 until rows.length()) {
                    val row = rows.optJSONObject(index) ?: continue
                    add(
                        Series(
                            id = row.optString("id"),
                            title = row.optString("title"),
                            cover = row.optString("cover"),
                            description = row.optString("description"),
                            badge = row.optString("badge"),
                            tags = row.optString("tags"),
                        )
                    )
                }
            }
        }
        return ActorWorksPage(
            items = items,
            nextOffset = json.optInt("nextOffset"),
            hasMore = json.optBoolean("hasMore"),
            totalCount = json.optInt("totalCount"),
        )
    }
}
