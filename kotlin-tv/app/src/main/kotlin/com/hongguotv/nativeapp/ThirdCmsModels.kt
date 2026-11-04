// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

/** 三方片源数据模型：与红果内容体系完全隔离，不进 Library/收藏/历史/备份。 */
data class ThirdSource(val id: String, val name: String, val api: String)
data class ThirdCategory(val id: String, val name: String)
data class ThirdVideo(val id: String, val name: String, val cover: String, val remarks: String)
data class ThirdEpisode(val label: String, val url: String)
data class ThirdDetail(
    val video: ThirdVideo, val typeName: String?, val year: String?, val area: String?,
    val synopsis: String?, val groups: List<Pair<String, List<ThirdEpisode>>>
)
data class ThirdProgress(val episode: Int, val positionMs: Long, val group: Int)
data class ThirdListPage(
    val categories: List<ThirdCategory>, val videos: List<ThirdVideo>,
    val page: Int, val pageCount: Int, val hasMore: Boolean, val total: Int
)
/** 搜索结果分页：条目带封面与播放地址，page/hasMore/total 用于「继续下滑加载更多」。 */
data class ThirdSearchPage(
    val details: List<ThirdDetail>, val page: Int, val hasMore: Boolean, val total: Int
)
/** 搜索请求结果：page 为空时 error 给出可读原因（该片源未开启搜索 / 网络失败）。 */
data class ThirdSearchResult(val page: ThirdSearchPage?, val error: String?)
data class ThirdProbe(val ok: Boolean, val total: Int, val reason: String?)
data class ThirdTvboxSite(val name: String, val api: String)
data class ThirdTvboxParse(val candidates: List<ThirdTvboxSite>, val skipped: Int)
/** 聚合搜索命中：详情与来源源一起保留，跨源展示、换源与收藏都依赖它。 */
data class ThirdSearchHit(val source: ThirdSource, val detail: ThirdDetail)
