// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThirdSourceBrowseSearchStateTest {
    @Test
    fun `开始新请求时序号递增且结果清空不影响全源覆盖选项`() {
        val state = ThirdSourceBrowseSearchState()
        state.details = listOf(ThirdDetail(ThirdVideo("v1", "影片", "", ""), null, null, null, null, emptyList()))
        state.hits = listOf(ThirdSearchHit(ThirdSource("s1", "源", "https://source/api"), state.details.single()))
        state.hitByVideoId["s1|v1"] = state.hits.single()
        state.failures["https://failed/api"] = "超时"
        state.allSourcesOverride = true
        state.type = "电影"
        state.year = "2024"
        state.area = "大陆"
        state.aggregate = true
        state.aggregating = true
        state.sourcesDone = 1
        state.sourceTotal = 2

        val sequence = state.nextSequence()
        state.clearResults()

        assertEquals(1, sequence)
        assertTrue(state.details.isEmpty())
        assertTrue(state.hits.isEmpty())
        assertTrue(state.hitByVideoId.isEmpty())
        assertTrue(state.failures.isEmpty())
        assertFalse(state.aggregate)
        assertFalse(state.aggregating)
        assertEquals(0, state.sourcesDone)
        assertEquals(0, state.sourceTotal)
        assertTrue(state.allSourcesOverride)
        assertEquals("电影", state.type)
        assertEquals("2024", state.year)
        assertEquals("大陆", state.area)
    }

    @Test
    fun `清空筛选条件不影响搜索结果`() {
        val state = ThirdSourceBrowseSearchState()
        state.type = "电影"
        state.year = "2024"
        state.area = "大陆"

        state.clearFilters()

        assertEquals(null, state.type)
        assertEquals(null, state.year)
        assertEquals(null, state.area)
    }

    @Test
    fun `完整重置额外清除全源搜索覆盖选项`() {
        val state = ThirdSourceBrowseSearchState()
        state.allSourcesOverride = true
        state.nextSequence()

        state.reset()

        assertFalse(state.allSourcesOverride)
        assertEquals(1, state.sequence)
    }
}
