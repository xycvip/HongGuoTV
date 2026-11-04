// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThirdSourcePlaybackUiStateTest {
    @Test
    fun `方向键 seek 节流保持 120 毫秒间隔`() {
        val state = ThirdSourcePlaybackUiState()
        assertFalse(state.tryBeginSeek(100L))
        assertTrue(state.tryBeginSeek(120L))
        assertFalse(state.tryBeginSeek(239L))
        assertTrue(state.tryBeginSeek(240L))
    }

    @Test
    fun `新播放会话重置控制层和带宽状态`() {
        val state = ThirdSourcePlaybackUiState()
        state.overlayVisible = true
        state.lastSeekAt = 500L
        state.lastBandwidthBps = 1_000_000L

        state.resetSession()

        assertFalse(state.overlayVisible)
        assertEquals(0L, state.lastSeekAt)
        assertEquals(0L, state.lastBandwidthBps)
    }
}
