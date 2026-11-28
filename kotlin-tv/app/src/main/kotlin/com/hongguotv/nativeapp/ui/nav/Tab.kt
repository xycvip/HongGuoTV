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
    data class Detail(
        val seriesId: String,
        val seed: com.hongguotv.core.Series,
        val autoplay: Boolean = false,
        /** 来源路由：从演员榜点进作品时带回演员榜，而不是甩回排行榜。 */
        val from: Route? = null,
    ) : Route
    data class Player(
        val series: com.hongguotv.core.Series,
        val episodes: List<String>,
        val episodeIndex: Int,
        val positionMs: Long = 0L,
        /**
         * 启动自动播放的候选剧单（feed 信息流第一页）。非空表示该播放器处于「选剧第一层」：
         * 进入即自动播放当前剧，上下键在候选间切剧、确定键进入完整控制层（第二层）。
         * 手动点剧进来时为空，行为与原来完全一致。
         */
        val startupCandidates: List<com.hongguotv.core.Series> = emptyList(),
        val startupIndex: Int = 0,
    ) : Route
    data class Collection(val kind: CollectionKind) : Route

    /**
     * 演员榜（剧星榜 / 星光榜）。
     *
     * 不做成排行榜的第五个签：榜签换的是口径（热播/推荐/新剧/臻果），
     * 而这里的对象是「人」，第二行的内容签对它没有意义。
     */
    data object ActorRank : Route
}

/** 「查看全部」的去向：本机片单，或某一类内容的完整目录。 */
enum class CollectionKind(val title: String) {
    RESUME("接着看"),
    UPDATES("收藏有更新"),
    LATER("稍后看"),
    FAVORITES("我的收藏"),
    HISTORY("观看历史"),
    HOT_SHORT("热门短剧"),
    HOT_COMIC("热门漫剧"),
    HOT_AI("热门 AI 剧"),
}
