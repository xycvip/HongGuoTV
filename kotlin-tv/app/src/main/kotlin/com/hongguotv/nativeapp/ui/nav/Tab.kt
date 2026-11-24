// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp.ui.nav

/** 顶层导航。顶栏与账号页共用同一份顺序。 */
enum class Tab(val label: String, val inTopBar: Boolean = true) {
    HOME("首页"),
    THEATER("剧场"),
    RANK("排行榜"),
    LIBRARY("追剧"),
    SETTINGS("设置"),
    // 账号不再占一个导航位：入口统一收到顶栏右侧的头像胶囊，避免两个入口指向同一页。
    ACCOUNT("账号", inTopBar = false),
}

sealed interface Route {
    data object Main : Route
    data object Search : Route
    /** 投播：扫码把手机里的分享文案推到电视播放。 */
    data object Cast : Route
    data class Detail(val seriesId: String, val seed: com.hongguotv.core.Series, val autoplay: Boolean = false) : Route
    data class Player(
        val series: com.hongguotv.core.Series,
        val episodes: List<String>,
        val episodeIndex: Int,
        val positionMs: Long = 0L,
    ) : Route
    data class Collection(val kind: CollectionKind) : Route
}

/** 「查看全部」的去向：本机片单，或某一类内容的完整目录。 */
enum class CollectionKind(val title: String) {
    RESUME("接着看"),
    UPDATES("收藏有更新"),
    LATER("稍后看"),
    FAVORITES("我的追剧"),
    HISTORY("观看历史"),
    HOT_SHORT("热门短剧"),
    HOT_COMIC("热门漫剧"),
    HOT_AI("热门 AI 剧"),
}
