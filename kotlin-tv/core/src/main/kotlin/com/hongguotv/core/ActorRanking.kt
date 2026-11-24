// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

/**
 * 红果短剧排行榜中的演员榜条目。
 *
 * 数据已迁移到热更新脚本（main.js 的 actorRanking 导出，见 hongguotv-js/src/actor-rank.js），
 * 原生解析器随迁移删除；这里只保留宿主与脚本门面共用的数据模型。
 */
data class ActorRankEntry(
    val userId: String, val actorId: String, val name: String, val avatar: String,
    val popularity: String, val intro: String, val details: List<String>, val position: Int,
    val changeType: Int = 0, val changePlaces: Int = 0,
)

/** 演员榜一页；分页游标必须与同一次榜单会话（sessionId）配对。 */
data class ActorRankPage(
    val items: List<ActorRankEntry>, val nextOffset: Int, val hasMore: Boolean,
    val sessionId: String, val version: String,
)
