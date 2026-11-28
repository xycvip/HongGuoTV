// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import okhttp3.OkHttpClient

/**
 * 全应用唯一的数据入口。
 *
 * 首页/搜索/详情/榜单/剧院的数据全部来自热更新脚本（main.js，见 [JsContentGateway]），
 * 原生实现已随迁移全部删除。网络与解析在 IO 线程完成，页面只订阅不可变状态。
 */
open class ContentRepository(
    /** 共享的 HTTP 客户端；传输层（局域网投送等）仍需要直接发请求，数据接口本身全部走 JS。 */
    val http: OkHttpClient = NetworkTuning.client(),
) {
    // ---------------------------------------------------- 热更新 JS 门面 ----
    private val js: JsContentGateway get() = JsContent.required()

    open fun home(page: Int = 1, type: ContentType = ContentType.SHORT): CatalogPage {
        require(page in 1..100)
        return js.home(page, type)
    }

    /** 首页信息流（无限刷 feed）：启动自动播放从中选取推荐内容。 */
    open fun feed(page: Int = 1, scope: String = "all"): CatalogPage {
        require(page in 1..100)
        return js.feed(page, scope)
    }

    fun comicRanking(page: Int = 1)=ranking(RankBoard.COMIC,page)
    fun realRanking(page: Int = 1)=ranking(RankBoard.REAL,page)

    /** [kind] 默认热播：首页货架用的是「正在火」的口径，不传就是它。 */
    open fun ranking(board: RankBoard, page: Int = 1, kind: RankKind = RankKind.HEAT): CatalogPage {
        require(page in 1..100)
        return js.rankingPage(board, kind, page)
    }

    fun search(keyword: String, page: Int = 1, type: ContentType = ContentType.SHORT): CatalogPage =
        js.search(keyword, page, type)

    /** 官方统一搜索：短剧与漫剧混合，按官方相关度排序。 */
    fun searchCombined(keyword: String, page: Int = 1): CatalogPage =
        js.search(keyword, page, null)

    fun suggest(query: String): List<String> = js.suggest(query)

    /** 接口健康探测：剧场条目数即探测结果。 */
    fun probeTheater(): Int = theater().catalog.items.size

    fun detail(id: String): Detail = js.detail(id)

    /** 同名义的其他季（官方多季数据），含当前季，按季序号升序。 */
    fun seasons(sourceId: String): List<SeasonEntry> = js.seasons(sourceId)

    /** 相关推荐：官方推荐位同类型剧集，剔除当前剧。 */
    fun related(seriesId: String, genre: String = "short_play", limit: Int = 12): List<Series> =
        js.related(seriesId, genre, limit)

    /** 演员参演作品：offset 用上一页的 nextOffset 原样回传。 */
    fun actorWorks(actorId: String, offset: Int = 0, count: Int = 24): ActorWorksPage =
        js.actorWorks(actorId, offset, count)

    /**
     * 演员榜：剧星榜 / 星光榜。
     * 首屏 [sessionId] 传空，翻页原样回传上一页返回的值，否则上游会重放第一页。
     */
    fun actorRank(board: ActorRankBoard, offset: Int = 0, sessionId: String = ""): ActorRankPage =
        js.actorRank(board, offset, sessionId)

    // ---------------------------------------------------------- 剧院 ----
    open fun theater(page:Int=1,type:ContentType?=null,selected:Map<String,String> = emptyMap()):TheaterCatalogPage =
        js.theater(page,type,selected)
    /** 剧院筛选面板（第 1 页），来自官方 landpage 接口。 */
    open fun theaterFilters(type:ContentType):List<TheaterFilterGroup> = js.theaterFilters(type)

    companion object {
        fun qualityNumber(value: String): Int = Regex("2160|1440|1080|720|576|540|480|360").find(value)?.value?.toInt() ?: mapOf("1920" to 1080,"1280" to 720,"1024" to 576,"854" to 480,"640" to 360)[value] ?: value.toIntOrNull() ?: 0
    }
}
