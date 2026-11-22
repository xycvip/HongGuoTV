// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThirdPlaybackInteractionRulesTest {

    @Test
    fun `快进步长随按住时间增加并封顶`() {
        assertEquals(5_000L, ThirdPlaybackInteractionRules.scrubStep(0L))
        assertEquals(15_000L, ThirdPlaybackInteractionRules.scrubStep(1_000L))
        assertEquals(60_000L, ThirdPlaybackInteractionRules.scrubStep(100_000L))
    }

    @Test
    fun `预览位置限制在零和有效时长之间`() {
        assertEquals(0L, ThirdPlaybackInteractionRules.scrubPosition(2_000L, -1, 5_000L, 10_000L))
        assertEquals(10_000L, ThirdPlaybackInteractionRules.scrubPosition(8_000L, 1, 5_000L, 10_000L))
        assertEquals(15_000L, ThirdPlaybackInteractionRules.scrubPosition(10_000L, 1, 5_000L, 0L))
    }

    @Test
    fun `备用线路跳过当前线路已尝试线路和空线路`() {
        val next = ThirdPlaybackInteractionRules.nextFallbackGroup(4, 0, setOf(1)) { it != 2 }
        assertEquals(3, next)
        assertEquals(null, ThirdPlaybackInteractionRules.nextFallbackGroup(3, 0, setOf(1, 2)) { true })
    }

    @Test
    fun `缓冲文案按带宽显示并正确处理未知值`() {
        assertEquals("正在缓冲…", ThirdPlaybackInteractionRules.bufferText(0L))
        assertEquals("正在缓冲 · 128 KB/s", ThirdPlaybackInteractionRules.bufferText(1_048_576L))
        assertTrue(ThirdPlaybackInteractionRules.bufferText(16L * 1024 * 1024).contains("MB/s"))
    }
}
