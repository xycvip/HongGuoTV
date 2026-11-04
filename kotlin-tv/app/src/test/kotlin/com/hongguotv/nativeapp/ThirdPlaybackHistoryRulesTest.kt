// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThirdPlaybackHistoryRulesTest {

    @Test
    fun `播放到结尾五秒内视为完成`() {
        assertTrue(ThirdPlaybackHistoryRules.isCompleted(595_000L, 600_000L))
        assertFalse(ThirdPlaybackHistoryRules.isCompleted(594_999L, 600_000L))
        assertFalse(ThirdPlaybackHistoryRules.isCompleted(0L, 0L))
    }

    @Test
    fun `完成记录使用独立键避免被普通播放节流吞掉`() {
        assertEquals(
            "api|video|1|2",
            ThirdPlaybackHistoryRules.historyKey("api", "video", 1, 2, false)
        )
        assertEquals(
            "api|video|1|2|done",
            ThirdPlaybackHistoryRules.historyKey("api", "video", 1, 2, true)
        )
    }

    @Test
    fun `相同历史键三十秒内节流不同键立即写入`() {
        assertFalse(ThirdPlaybackHistoryRules.shouldWriteHistory("k", "k", 29_999L, 0L))
        assertTrue(ThirdPlaybackHistoryRules.shouldWriteHistory("k", "k", 30_000L, 0L))
        assertTrue(ThirdPlaybackHistoryRules.shouldWriteHistory("new", "old", 1L, 0L))
    }

    @Test
    fun `备份五分钟节流但播放完成立即备份`() {
        assertFalse(ThirdPlaybackHistoryRules.shouldBackup(299_999L, 0L, false))
        assertTrue(ThirdPlaybackHistoryRules.shouldBackup(300_000L, 0L, false))
        assertTrue(ThirdPlaybackHistoryRules.shouldBackup(1L, 0L, true))
    }
}
