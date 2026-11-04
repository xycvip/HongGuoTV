// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import org.junit.Assert.assertEquals
import org.junit.Test

class ThirdSourceRemovalPlannerTest {

    private val sources = listOf(
        ThirdSource("a", "A", "https://a.example/api"),
        ThirdSource("b", "B", "https://b.example/api"),
        ThirdSource("c", "C", "https://c.example/api")
    )

    @Test
    fun `删除当前源后顺延到第一个剩余源`() {
        val plan = ThirdSourceRemovalPlanner.plan(sources, setOf("a"), "a", 10)
        assertEquals(listOf("b", "c"), plan.remaining.map { it.id })
        assertEquals(setOf("a"), plan.removedIds)
        assertEquals("b", plan.nextCurrentId)
    }

    @Test
    fun `删除非当前源时保留当前源`() {
        val plan = ThirdSourceRemovalPlanner.plan(sources, setOf("b"), "c", 10)
        assertEquals("c", plan.nextCurrentId)
        assertEquals(listOf("a", "c"), plan.remaining.map { it.id })
    }

    @Test
    fun `删除全部源后当前源为空`() {
        val plan = ThirdSourceRemovalPlanner.plan(sources, sources.map { it.id }.toSet(), "b", 10)
        assertEquals(emptyList<ThirdSource>(), plan.remaining)
        assertEquals(null, plan.nextCurrentId)
    }
}
