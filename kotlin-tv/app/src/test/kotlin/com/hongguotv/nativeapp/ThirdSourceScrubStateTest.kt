// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThirdSourceScrubStateTest {

    @Test
    fun `开始预览时记录方向位置和起始时间`() {
        val state = ThirdSourceScrubState()
        state.start(direction = 1, positionMs = 12_000L, nowMs = 5_000L)

        assertTrue(state.active)
        assertEquals(1, state.direction)
        assertEquals(12_000L, state.positionMs)
        assertEquals(17_000L, state.advance(nowMs = 5_000L, durationMs = 60_000L))
    }

    @Test
    fun `方向改变时重置加速计时但保留当前预览位置`() {
        val state = ThirdSourceScrubState()
        state.start(direction = 1, positionMs = 20_000L, nowMs = 1_000L)
        state.advance(nowMs = 3_000L, durationMs = 120_000L)
        val beforeChange = state.positionMs

        state.changeDirection(direction = -1, nowMs = 8_000L)

        assertEquals(-1, state.direction)
        assertEquals(beforeChange, state.positionMs)
        assertEquals(beforeChange - 5_000L, state.advance(nowMs = 8_000L, durationMs = 120_000L))
    }

    @Test
    fun `预览位置沿用交互规则限制边界`() {
        val state = ThirdSourceScrubState()
        state.start(direction = -1, positionMs = 2_000L, nowMs = 0L)

        assertEquals(0L, state.advance(nowMs = 10_000L, durationMs = 30_000L))
    }

    @Test
    fun `停止后不再推进预览位置`() {
        val state = ThirdSourceScrubState()
        state.start(direction = 1, positionMs = 10_000L, nowMs = 0L)
        state.stop()

        assertFalse(state.active)
        assertEquals(10_000L, state.advance(nowMs = 20_000L, durationMs = 30_000L))
    }
}
