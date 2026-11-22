// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp


internal object ThirdSourceBatchSelection {
    fun toggle(selected: Set<String>, id: String): Set<String> =
        if(id in selected) selected - id else selected + id

    fun toggleAll(selected: Set<String>, allIds: Collection<String>): Set<String> =
        if(allIds.isNotEmpty() && selected.size == allIds.size && selected.containsAll(allIds)) {
            emptySet()
        } else {
            allIds.toCollection(LinkedHashSet())
        }

    fun allSelected(selected: Set<String>, allIds: Collection<String>): Boolean =
        allIds.isNotEmpty() && selected.size == allIds.size && selected.containsAll(allIds)
}

internal class ThirdSourceRegistryState {
    var sources: List<ThirdSource> = emptyList()
    var currentId: String? = null
    val probes: MutableMap<String, ThirdSourceProbeState> = HashMap()
    /** 最近一次测速耗时（毫秒），-1 表示该源不可用；不落盘。 */
    val latencyMs: MutableMap<String, Long> = HashMap()
    var speedTesting: Boolean = false
    var batchMode: Boolean = false
    val selected: MutableSet<String> = mutableSetOf()

    fun currentSource(): ThirdSource? = sources.firstOrNull { it.id == currentId }
}
