// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

/** 浏览页搜索结果、筛选条件、来源命中映射及跨源搜索进度。 */
internal class ThirdSourceBrowseSearchState {
    var details: List<ThirdDetail> = emptyList()
    var hits: List<ThirdSearchHit> = emptyList()
    val hitByVideoId: MutableMap<String, ThirdSearchHit> = HashMap()
    var aggregating: Boolean = false
    var aggregate: Boolean = false
    var sourcesDone: Int = 0
    var sourceTotal: Int = 0
    val failures: MutableMap<String, String> = HashMap()
    var sequence: Int = 0
        private set
    var allSourcesOverride: Boolean = false
    var type: String? = null
    var year: String? = null
    var area: String? = null

    fun nextSequence(): Int {
        sequence += 1
        return sequence
    }

    /** 清空当前关键词的结果，不改写本次搜索的全源范围覆盖选项。 */
    fun clearResults() {
        details = emptyList()
        hits = emptyList()
        hitByVideoId.clear()
        aggregating = false
        aggregate = false
        sourcesDone = 0
        sourceTotal = 0
        failures.clear()
    }

    fun clearFilters() {
        type = null
        year = null
        area = null
    }

    fun reset() {
        clearResults()
        allSourcesOverride = false
    }
}
