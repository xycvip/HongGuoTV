// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

/**
 * 一页演员参演作品。
 *
 * 数据已迁移到热更新脚本（main.js 的 actorWorks 导出，见 hongguotv-js/src/actor-rank.js），
 * 原生解析器随迁移删除；这里只保留宿主与脚本门面共用的数据模型。
 */
data class ActorWorksPage(
    val items: List<Series>,
    val nextOffset: Int,
    val hasMore: Boolean,
    val totalCount: Int,
)
