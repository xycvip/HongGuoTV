// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

/**
 * 榜单的内容类型（官方榜单分组）。
 *
 * 声明顺序即页面上的内容签顺序：综合 → 真人剧 → 漫剧 → AI剧。
 * 真人剧排在最前是因为大盘里它的量最大，漫剧和 AI 剧属于扩展口味，放后面。
 */
enum class RankBoard(
    val label: String,
    val jsGroup: String,
) {
    TOTAL("综合", "all"),
    REAL("真人剧", "human"),
    COMIC("漫剧", "comic_series_rank"),
    AI("AI剧", "ai_playlet"),
    ;

    val buttonLabel: String get() = "${label}榜"
}

/**
 * 榜单口径：同一个内容类型下官方有 9 个榜，这里只留 4 个。
 *
 * 砍掉的 5 个（预约/热搜/追更/必看/收藏）不是数据重复，而是**语义重复**：
 * 对观众来说它们都是「火」，只是统计窗口不同，每次切换都要重新判断差别，
 * 这才是「榜太多」的真实成本。留下的 4 个各回答一个不同的问题：
 * 热播=大家在看什么，推荐=有什么没见过的，新剧=最近上了什么，臻果=哪部口碑好。
 *
 * [boardIds] 是固定 id 表，**不能按命名规则猜**：官方命名不统一
 * （综合推荐是 `ranklist_hot_sc`，漫剧推荐却是 `comic_series_hot_rank`），
 * 旧版本用 `endsWith("hot_play")` 匹配，综合栏因此错拿了推荐榜。
 *
 * [availableBoards] 决定内容签显示哪些类型：臻果榜在全站只有一个入口
 * （官方只给了 `hongguo-app:all:ranklist_prestige`，且只统计真人剧），
 * 此时内容签整行换成一句说明，而不是留一排点进去就是空页的死键。
 */
enum class RankKind(
    val label: String,
    val tagline: String,
    private val boardIds: Map<String, String>,
    /**
     * 只在这些内容类型下提供；null = 全部四类。
     *
     * 默认值不能直接写 `RankBoard.entries`：枚举常量的构造发生在 RankBoard 的
     * 静态初始化里，那时它的 entries 还没赋值，会拿到 null。所以延迟到运行时算。
     */
    private val restrictedBoards: Set<RankBoard>? = null,
    /** 内容行只显示一句话时的说明；为空表示这个榜覆盖全部类型。 */
    val scopeNote: String = "",
) {
    HEAT(
        label = "热播榜",
        tagline = "现在大家都在看",
        boardIds = mapOf(
            "all" to "ranklist_hot_play_sc",
            "human" to "human_hot_play",
            "comic_series_rank" to "comic_series_hot_play",
            "ai_playlet" to "ai_playlet_hot_play",
        ),
    ),
    RECOMMEND(
        label = "推荐榜",
        tagline = "按兴趣挑的，和首页不重样",
        boardIds = mapOf(
            "all" to "ranklist_hot_sc",
            "human" to "human_hot_sc",
            "comic_series_rank" to "comic_series_hot_rank",
            "ai_playlet" to "ai_playlet_hot_sc",
        ),
    ),
    FRESH(
        label = "新剧榜",
        tagline = "5 天内上架的新剧",
        boardIds = mapOf(
            "all" to "ranklist_new_rank_sc",
            "human" to "human_new_rank",
            "comic_series_rank" to "comic_series_new_rank",
            "ai_playlet" to "ai_playlet_new_rank",
        ),
    ),
    RATED(
        label = "臻果榜",
        tagline = "已开分真人剧的口碑排名",
        boardIds = mapOf("all" to "ranklist_prestige"),
        restrictedBoards = setOf(RankBoard.TOTAL),
        scopeNote = "仅真人剧参与评分",
    ),
    ;

    /** 该榜在某个分组下的官方 id；没有配置表示这组不提供这个榜。 */
    fun boardId(group: String): String? = boardIds[group]

    /** 该榜覆盖的内容类型。只有一个时页面把内容签那一行换成 [scopeNote]。 */
    val availableBoards: Set<RankBoard> by lazy { restrictedBoards ?: RankBoard.entries.toSet() }

    /** 切到这个榜时若当前类型不适用，落到哪一类（臻果→综合）。 */
    val defaultBoard: RankBoard get() = availableBoards.first()

    /** 页面副标题兜底：脚本没给描述时用「红果官方热播榜 · 实时更新」这类文案。 */
    val sourceText: String get() = "红果官方$label · $tagline"
}
