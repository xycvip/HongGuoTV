// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.graphics.Color

/**
 * 三方片源页状态文案与状态颜色规则。
 * 只接收页面状态快照，不持有 Activity、View 或网络客户端。
 */
internal object ThirdSourceStatusText {
    fun searchSummary(
        word: String,
        filteredCount: Int,
        detailsCount: Int,
        loading: Boolean,
        aggregate: Boolean,
        sourcesDone: Int,
        sourceTotal: Int,
        failureCount: Int,
        loadFailed: Boolean,
        total: Int
    ): String = when {
        loading && aggregate -> "正在聚合搜索「$word」…（$sourcesDone/$sourceTotal 个源已返回）"
        loading -> "正在搜索「$word」…"
        aggregate -> buildString {
            append("搜索「$word」 · 聚合 $sourceTotal 个源 · 共 $detailsCount 条")
            if (failureCount > 0) append("（$failureCount 个源无响应）")
            if (filteredCount != detailsCount) append(" · 筛选后 $filteredCount 条")
        }
        detailsCount == 0 && loadFailed -> "搜索「$word」失败 · $failureCount 个源无响应"
        detailsCount == 0 -> "搜索「$word」 · 暂无结果"
        total > detailsCount -> "搜索「$word」 · $filteredCount/$detailsCount 条（共 $total 条）"
        else -> "搜索「$word」 · $filteredCount/$detailsCount 条"
    }

    fun gridStatus(
        searchActive: Boolean,
        aggregating: Boolean,
        sourcesDone: Int,
        sourceTotal: Int,
        loadingMoreSearch: Boolean,
        searchDetailsCount: Int,
        aggregate: Boolean,
        failureCount: Int,
        searchHasMore: Boolean,
        searchTotal: Int,
        loadingMoreCatalog: Boolean,
        catalogHasMore: Boolean,
        catalogVideosCount: Int
    ): String = when {
        searchActive -> when {
            aggregating -> "正在聚合搜索…（$sourcesDone/$sourceTotal 个源已返回）"
            loadingMoreSearch -> "正在加载更多…"
            searchDetailsCount == 0 -> ""
            aggregate -> buildString {
                append("聚合 $sourceTotal 个源 · 共 $searchDetailsCount 条")
                if (failureCount > 0) append(" · $failureCount 个源无响应")
            }
            searchHasMore -> "继续下滑加载更多"
            searchTotal > searchDetailsCount -> "已加载 $searchDetailsCount / $searchTotal 条"
            else -> "已加载全部 $searchDetailsCount 条"
        }
        loadingMoreCatalog -> "正在加载更多…"
        catalogHasMore -> "继续下滑加载更多"
        catalogVideosCount == 0 -> ""
        else -> "已加载全部 $catalogVideosCount 条"
    }

    fun probeSummary(
        sources: List<ThirdSource>,
        probes: Map<String, ThirdSourceProbeState>
    ): String {
        if (sources.isEmpty()) return ""
        val ready = sources.count { probes[it.id]?.dot == ThirdSourceProbeDot.OK }
        val probing = sources.count { probes[it.id]?.dot == ThirdSourceProbeDot.RUNNING }
        return buildString {
            append("探活 $ready/${sources.size} 就绪")
            if (probing > 0) append(" · 其余后台继续")
        }
    }

    fun dotColor(dot: ThirdSourceProbeDot?): Int = when (dot) {
        ThirdSourceProbeDot.OK -> Color.rgb(76, 175, 80)
        ThirdSourceProbeDot.DEAD -> Color.rgb(120, 128, 140)
        else -> Color.rgb(255, 180, 80)
    }
}
