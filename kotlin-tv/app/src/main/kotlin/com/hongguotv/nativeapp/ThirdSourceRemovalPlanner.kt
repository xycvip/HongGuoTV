// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

/** 片源删除规则：纯数据变换，不执行存储、备份或 UI 操作。 */
internal object ThirdSourceRemovalPlanner {

    internal data class Plan(
        val remaining: List<ThirdSource>,
        val removedIds: Set<String>,
        val nextCurrentId: String?
    )

    fun plan(
        sources: List<ThirdSource>,
        removeIds: Set<String>,
        currentId: String?,
        maxSources: Int
    ): Plan {
        val removed = sources.filter { it.id in removeIds }
        val remaining = sources.filterNot { it.id in removeIds }.take(maxSources.coerceAtLeast(0))
        val nextCurrent = currentId
            ?.takeIf { id -> remaining.any { it.id == id } }
            ?: remaining.firstOrNull()?.id
        return Plan(
            remaining = remaining,
            removedIds = removed.mapTo(LinkedHashSet()) { it.id },
            nextCurrentId = nextCurrent
        )
    }
}
