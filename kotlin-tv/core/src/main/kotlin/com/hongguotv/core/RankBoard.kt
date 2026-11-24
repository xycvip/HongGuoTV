// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

/**
 * 排行榜分组。原生网站榜单解析已随热更新迁移删除；
 * [jsGroup] 是官方发现接口里的榜单分组名，由 JS 门面映射到具体榜单。
 */
enum class RankBoard(
    val label: String,
    val buttonLabel: String = "${label}榜",
    val sourceText: String = "来源：红果${label}热播榜",
    val jsGroup: String,
) {
    TOTAL("综合", "综合榜", "来源：红果热播榜", "all"),
    REAL("短剧", sourceText = "来源：红果短剧热播榜", jsGroup = "human"),
    AI("AI剧", jsGroup = "ai_playlet"),
    COMIC("漫剧", jsGroup = "comic_series_rank"),
}
