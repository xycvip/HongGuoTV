// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import org.json.JSONObject
import java.io.IOException

data class Series(val id: String, val title: String, val cover: String = "", val description: String = "", val badge: String = "", val tags: String = "") {
    fun toJson() = JSONObject().put("id",id).put("title",title).put("cover",cover).put("description",description).put("badge",badge).put("tags",tags)
    companion object { fun fromJson(o: JSONObject) = Series(o.str("id"),o.str("title"),o.str("cover"),o.str("description"),o.str("badge"),o.str("tags")) }
}
/**
 * 详情页演员：来自官方 details 响应的 celebrities。
 * 漫剧/AI 剧没有真人演员，[Detail.actors] 为空，UI 按需隐藏整块。
 */
data class Actor(
    val id: String,
    val name: String,
    val role: String = "",
    val avatar: String = "",
    val intro: String = "",
    /**
     * 来源补充：榜单里是官方给的两段说明（「32部作品 · 双周追剧161万人」），
     * 详情页的演职员没有这个字段，保持空串。弹窗用它替代「共 N 部作品」那行。
     */
    val meta: String = "",
) {
    /** 官方演员作品接口要求纯数字 celebrity_id；缺 id 时整张卡不可进作品页。 */
    val worksCapable: Boolean get() = id.matches(Regex("[0-9]{1,30}"))
}

/**
 * 演员作品分页。
 *
 * 官方接口的游标语义是「已消耗行数」而不是页码：[nextOffset] 原样回传，
 * 不要用 page × count 反推——上游返回的条目里偶有畸形行被丢弃，页码会越翻越偏。
 */
data class ActorWorksPage(
    val items: List<Series>,
    val hasMore: Boolean,
    val nextOffset: Int,
    val totalCount: Int,
)

/**
 * 演员榜口径。
 *
 * [scriptId] 是官方 `bookmall/cell/change/v` 的子榜 id，固定写死而不是按名字猜：
 * 两个榜的语义完全不同（剧星看参演剧，星光看粉丝互动），
 * 官方改 id 时宁可报错，也不能把两个榜的数据混在一起。
 */
enum class ActorRankBoard(val label: String, val tagline: String, val scriptId: String) {
    DRAMA("剧星榜", "按参演剧集热度排序", "ranklist_series_actor"),
    STAR("星光榜", "按粉丝互动与动态排序", "ranklist_actor_fans"),
}

/**
 * 演员榜的一条。
 *
 * [changeType] 是官方 `rank_change_type`：实测 1=上升、2=下降、3=持平；
 * [changePlaces] 是变动名次，持平时为 0。两榜都按「位置」从 1 开始连续编号。
 */
data class ActorRankEntry(
    val actor: Actor,
    val rank: Int,
    val popularity: String = "",
    val details: List<String> = emptyList(),
    val changeType: Int = 0,
    val changePlaces: Int = 0,
) {
    val trendUp: Boolean get() = changeType == 1 && changePlaces > 0
    val trendDown: Boolean get() = changeType == 2 && changePlaces > 0

    /** 名次变动文案：上升/下降带位数，持平显示一条短横，不留空白。 */
    val changeText: String
        get() = when {
            trendUp -> "↑$changePlaces"
            trendDown -> "↓$changePlaces"
            else -> "—"
        }

    /** 卡片副标题：官方给的两段说明优先，拼不出来退回人气值。 */
    val note: String
        get() = details.filter { it.isNotBlank() }.joinToString(" · ").ifBlank { popularity }
}

/**
 * 演员榜分页。
 *
 * [sessionId] 是官方排行榜会话：首屏不带，第 2 页起必须原样回传，
 * 否则上游会重放第一页（实测第 2 页与第 1 页完全重合）。
 */
data class ActorRankPage(
    val items: List<ActorRankEntry>,
    val nextOffset: Int,
    val hasMore: Boolean,
    val sessionId: String = "",
)

/** 详情页附带的社交数据，缺失时各字段保持默认空值，调用方按需隐藏。 */
data class SeriesSocial(val rating: Double = 0.0, val ratingCount: String = "", val favoriteText: String = "", val heatText: String = "") {
    val isEmpty get() = rating<=0.0 && favoriteText.isBlank() && heatText.isBlank()
}
/**
 * 系列剧的一条：官方多季接口给出的季序号 + 该季剧集。
 * 标题里的「第二季」常带着完整剧名，列表里统一显示 [label]。
 */
data class SeasonEntry(val series: Series, val number: Int) {
    val label: String get() = seasonLabel(number)
}
data class Detail(val series: Series, val episodes: List<String>, val related: List<Series> = emptyList(), val sameName: List<Series> = emptyList(), val social: SeriesSocial = SeriesSocial(), val actors: List<Actor> = emptyList()) {
    /** Same-name entries with a finite season number beyond the current one (untitled season counts as 1). */
    val newerSeasons: List<Series> get() {
        val current=seasonNumber(series.title).let { if(it==Int.MAX_VALUE) 1 else it }
        return sameName.filter { val n=seasonNumber(it.title); n!=Int.MAX_VALUE && n>current }
    }
    /** The immediately following season, so auto-play continues in order instead of jumping to the latest. */
    val nextSeason: Series? get() = newerSeasons.minByOrNull { seasonNumber(it.title) }
}
data class RankPosition(val rank: Int?, val heat: String)
data class ComicRanking(val positions: Map<String,RankPosition>, val totalPages: Int, val updatedText: String)
data class CatalogPage(val items: List<Series>, val hasMore: Boolean, val ranking: ComicRanking? = null)
data class TheaterFilterOption(val id: String, val label: String)
data class TheaterFilterGroup(val type: String, val label: String, val options: List<TheaterFilterOption>)
data class TheaterCatalogPage(val catalog: CatalogPage, val filters: List<TheaterFilterGroup>, val nextOffset: Int?, val sessionId: String)
class SearchSessionExpiredException: IOException("搜索结果已过期，请从第 1 页重新搜索")
/** 平台对该集只产出了 ByteVC2 等电视无法解码的编码，重试与换清晰度都不会成功。 */
/** 播放信息接口返回了 HTTP 200 空体；已按有界退避重读仍未成功。 */
