// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp


internal object ThirdSourceSearchPlanner {
    fun candidates(
        current: ThirdSource,
        sources: List<ThirdSource>,
        aggregate: Boolean,
        probes: Map<String, ThirdSourceProbeState>,
        limit: Int,
    ): List<ThirdSource> {
        if (!aggregate || limit <= 1) return listOf(current)
        val others = sources.filter { source ->
            source.id != current.id && probes[source.id]?.dot != ThirdSourceProbeDot.DEAD
        }
        return (listOf(current) + others).take(limit.coerceAtLeast(1))
    }
}

internal data class ThirdSourceSearchSnapshot(
    val hits: List<ThirdSearchHit>,
    val hitByVideoId: Map<String, ThirdSearchHit>,
) {
    val details: List<ThirdDetail>
        get() = hits.map { it.detail }
}

/** 聚合搜索去重、单源分页去重及复合 ID 映射规则。网络请求与 UI 更新仍由页面控制器负责。 */
internal object ThirdSourceSearchResultReducer {

    /** 聚合多源首屏结果：保留原有顺序，并复用 CMS 的当前源优先/集数更多优先规则。 */
    fun mergeAggregate(
        existing: List<ThirdSearchHit>,
        source: ThirdSource,
        incoming: List<ThirdDetail>,
        preferredApi: String?,
    ): ThirdSourceSearchSnapshot {
        val merged = ThirdCms.dedupeHits(
            existing + incoming.map { ThirdSearchHit(source, it) },
            preferredApi,
        )
        return snapshot(merged)
    }

    /** 单源分页追加：按影片 ID 去重，保持已有结果在前、新结果在后。 */
    fun appendPage(
        existing: List<ThirdSearchHit>,
        source: ThirdSource,
        incoming: List<ThirdDetail>,
    ): ThirdSourceSearchSnapshot {
        val knownIds = existing.mapTo(HashSet()) { it.detail.video.id }
        val fresh = incoming
            .filter { it.video.id !in knownIds }
            .map { ThirdSearchHit(source, it) }
        return snapshot(existing + fresh)
    }

    private fun snapshot(hits: List<ThirdSearchHit>): ThirdSourceSearchSnapshot {
        val mapping = LinkedHashMap<String, ThirdSearchHit>()
        hits.forEach { hit ->
            mapping[ThirdSourceSearchFilters.compositeVideoId(hit.source, hit.detail.video.id)] = hit
        }
        return ThirdSourceSearchSnapshot(hits, mapping)
    }
}
