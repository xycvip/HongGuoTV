// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import org.junit.Assert.assertEquals
import org.junit.Test

class ThirdSourceNavigationStateTest {
    @Test
    fun `页面代际令牌递增且导航模式独立保存`() {
        val state = ThirdSourceNavigationState()
        state.mode = ThirdSourceScreenMode.DETAIL

        assertEquals(1, state.nextGeneration())
        assertEquals(2, state.nextGeneration())
        assertEquals(ThirdSourceScreenMode.DETAIL, state.mode)
    }

    @Test
    fun `回退目标遵循页面导航层级`() {
        val state = ThirdSourceNavigationState()
        assertEquals(null, state.backTarget(hasSearchWord = false, detailReturnMode = ThirdSourceScreenMode.BROWSE))

        state.mode = ThirdSourceScreenMode.BROWSE
        assertEquals(ThirdSourceScreenMode.SEARCH, state.backTarget(true, ThirdSourceScreenMode.BROWSE))

        state.mode = ThirdSourceScreenMode.SEARCH
        assertEquals(ThirdSourceScreenMode.BROWSE, state.backTarget(false, ThirdSourceScreenMode.BROWSE))

        state.mode = ThirdSourceScreenMode.DETAIL
        assertEquals(ThirdSourceScreenMode.HISTORY, state.backTarget(false, ThirdSourceScreenMode.HISTORY))

        state.mode = ThirdSourceScreenMode.PLAY
        assertEquals(ThirdSourceScreenMode.DETAIL, state.backTarget(false, ThirdSourceScreenMode.BROWSE))

        state.mode = ThirdSourceScreenMode.FAVORITE
        assertEquals(ThirdSourceScreenMode.BROWSE, state.backTarget(false, ThirdSourceScreenMode.BROWSE))
    }
}
