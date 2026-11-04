// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

/** 三方片源搜索结果的复合标识与客户端筛选规则。 */
internal object ThirdSourceSearchFilters {
    fun compositeVideoId(source: ThirdSource, videoId: String): String = source.id + "|" + videoId

    fun filterHits(
        hits: List<ThirdSearchHit>,
        type: String?,
        year: String?,
        area: String?,
    ): List<ThirdSearchHit> = hits.filter { hit ->
        (type == null || hit.detail.typeName == type) &&
            (year == null || hit.detail.year == year) &&
            (area == null || hit.detail.area == area)
    }

    /** 将聚合搜索命中转换为网格卡片数据，使用源 ID 避免不同片源的影片 ID 冲突。 */
    fun displayHits(hits: List<ThirdSearchHit>, aggregate: Boolean): List<ThirdVideo> =
        hits.map { hit ->
            val sourceName = hit.source.name
            val remarks = if (aggregate) {
                listOf(sourceName, hit.detail.video.remarks)
                    .filter { it.isNotBlank() }
                    .joinToString(" · ")
            } else {
                hit.detail.video.remarks
            }
            hit.detail.video.copy(
                id = compositeVideoId(hit.source, hit.detail.video.id),
                remarks = remarks,
            )
        }

    fun filterDetails(
        details: List<ThirdDetail>,
        type: String?,
        year: String?,
        area: String?,
    ): List<ThirdDetail> = details.filter { item ->
        (type == null || item.typeName == type) &&
            (year == null || item.year == year) &&
            (area == null || item.area == area)
    }
}
