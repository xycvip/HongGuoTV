// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThirdPlaybackSkipRulesTest {

    @Test
    fun `不同片源同一影片 ID 使用不同设置键`() {
        assertEquals("https://a.example/api|123", ThirdPlaybackSkipRules.storageKey("https://a.example/api", "123"))
        assertEquals("https://b.example/api|123", ThirdPlaybackSkipRules.storageKey("https://b.example/api", "123"))
    }

    @Test
    fun `片头只在当前位置尚未超过设定秒数时跳转`() {
        assertEquals(60_000L, ThirdPlaybackSkipRules.introTargetMs(60))
        assertTrue(ThirdPlaybackSkipRules.shouldSkipIntro(20_000L, 60))
        assertFalse(ThirdPlaybackSkipRules.shouldSkipIntro(60_000L, 60))
        assertFalse(ThirdPlaybackSkipRules.shouldSkipIntro(0L, 0))
    }

    @Test
    fun `片尾需要播放中且进入片尾窗口并且只触发一次`() {
        assertTrue(ThirdPlaybackSkipRules.shouldSkipOutro(true, 600_000L, 570_000L, 30, false))
        assertFalse(ThirdPlaybackSkipRules.shouldSkipOutro(false, 600_000L, 590_000L, 30, false))
        assertFalse(ThirdPlaybackSkipRules.shouldSkipOutro(true, 600_000L, 590_000L, 0, false))
        assertFalse(ThirdPlaybackSkipRules.shouldSkipOutro(true, 0L, 0L, 30, false))
        assertFalse(ThirdPlaybackSkipRules.shouldSkipOutro(true, 600_000L, 590_000L, 30, true))
    }
}
