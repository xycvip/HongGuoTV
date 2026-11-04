// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThirdSourcePlaybackSessionStateTest {

    @Test
    fun `排队的起播指令只允许消费一次`() {
        val state = ThirdSourcePlaybackSessionState()
        state.queuePlay(group = 2, episode = 4, startMs = 12_500L)

        assertEquals(2, state.group)
        assertEquals(4, state.episode)
        assertEquals(12_500L to true, state.consumePendingPlay())
        assertNull(state.consumePendingPlay())
    }

    @Test
    fun `起播位置原样保留且支持非自动播放指令`() {
        val state = ThirdSourcePlaybackSessionState()
        state.queuePlay(group = 0, episode = 0, startMs = -1L, autoplay = false)

        assertEquals(-1L to false, state.consumePendingPlay())
    }

    @Test
    fun `暂停位置只消费一次并恢复为非负值`() {
        val state = ThirdSourcePlaybackSessionState()
        state.lastPositionMs = 8_000L

        assertEquals(8_000L, state.consumeLastPosition())
        assertEquals(-1L, state.lastPositionMs)
        assertEquals(0L, state.consumeLastPosition())
    }

    @Test
    fun `备用线路尝试记录可以标记并在新影片时清空`() {
        val state = ThirdSourcePlaybackSessionState()
        state.markFallbackAttempt(1)
        state.markFallbackAttempt(3)
        assertTrue(state.attemptedFallbackGroups.containsAll(listOf(1, 3)))

        state.resetFallbackAttempts()

        assertTrue(state.attemptedFallbackGroups.isEmpty())
    }
}
