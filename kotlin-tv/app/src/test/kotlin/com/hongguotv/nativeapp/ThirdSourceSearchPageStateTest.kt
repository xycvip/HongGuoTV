// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ThirdSourceSearchPageStateTest {

    @Test
    fun `联想失效会递增序号并清空旧建议`() {
        val state = ThirdSourceSearchPageState()
        state.suggestions = listOf("旧建议")
        val before = state.suggestionSequence

        state.invalidateSuggestions()

        assertEquals(before + 1, state.suggestionSequence)
        assertEquals(emptyList<String>(), state.suggestions)
    }

    @Test
    fun `重置搜索结果不清空用户输入和联想序号`() {
        val state = ThirdSourceSearchPageState()
        state.input = "测试关键词"
        state.suggestions = listOf("建议")
        state.suggestionSequence = 7
        state.loading = true
        state.loadingMore = true
        state.requested = true
        state.page = 4
        state.hasMore = true
        state.total = 80

        state.resetResults()

        assertEquals("测试关键词", state.input)
        assertEquals(listOf("建议"), state.suggestions)
        assertEquals(7, state.suggestionSequence)
        assertFalse(state.loading)
        assertFalse(state.loadingMore)
        assertFalse(state.requested)
        assertEquals(1, state.page)
        assertFalse(state.hasMore)
        assertEquals(0, state.total)
    }
}
