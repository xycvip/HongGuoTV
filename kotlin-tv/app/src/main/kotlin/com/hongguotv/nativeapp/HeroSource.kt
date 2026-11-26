package com.hongguotv.nativeapp

/**
 * 首页大海报（Hero）的取数来源。
 *
 * 所有来源都从「第 2 页」取：首页货架固定消费第 1 页（每页 6 部），
 * 从第 2 页取大图可以做到与货架零重叠，既保证首屏不出现「大图 + 下面就是它自己」
 * 的重复感，也不占用货架的推荐位。
 *
 * - [MIXED]：三类各取 2 部，共 6 部，兼顾内容广度；
 * - [SHORT] / [COMIC] / [AI]：只取对应分类的第 2 页 6 部，适合只想主推某一类的场景。
 */
enum class HeroSource(val storedValue: String, val label: String) {
    MIXED("mixed", "综合"),
    SHORT("short", "真人剧"),
    COMIC("comic", "漫剧"),
    AI("ai", "AI 剧"),
    ;

    companion object {
        fun fromStored(value: String?): HeroSource =
            entries.firstOrNull { it.storedValue == value } ?: MIXED
    }
}
