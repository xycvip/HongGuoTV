// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import org.junit.Assert.assertEquals
import org.junit.Test

class ThirdSourceSearchPlannerTest {
    private fun source(id: String) = ThirdSource(id, "源$id", "https://example.invalid/$id")

    @Test
    fun `当前源始终排第一且聚合数量受限`() {
        val current = source("current")
        val sources = listOf(source("a"), current, source("b"), source("c"))
        val result = ThirdSourceSearchPlanner.candidates(
            current, sources, true, emptyMap(), limit = 3
        )
        assertEquals(listOf("current", "a", "b"), result.map { it.id })
    }

    @Test
    fun `跳过明确探活失败的其他源但保留未知状态源`() {
        val current = source("current")
        val sources = listOf(current, source("dead"), source("unknown"), source("ok"))
        val probes = mapOf(
            "dead" to ThirdSourceProbeState(ThirdSourceProbeDot.DEAD, 0, "连接失败"),
            "ok" to ThirdSourceProbeState(ThirdSourceProbeDot.OK, 12),
        )
        val result = ThirdSourceSearchPlanner.candidates(
            current, sources, true, probes, limit = 6
        )
        assertEquals(listOf("current", "unknown", "ok"), result.map { it.id })
    }

    @Test
    fun `关闭聚合时只搜索当前源`() {
        val current = source("current")
        val result = ThirdSourceSearchPlanner.candidates(
            current, listOf(current, source("other")), false, emptyMap(), limit = 6
        )
        assertEquals(listOf(current), result)
    }

    @Test
    fun `候选数量限制至少保留当前源`() {
        val current = source("current")
        val result = ThirdSourceSearchPlanner.candidates(
            current, listOf(current, source("other")), true, emptyMap(), limit = 0
        )
        assertEquals(listOf(current), result)
    }
}
