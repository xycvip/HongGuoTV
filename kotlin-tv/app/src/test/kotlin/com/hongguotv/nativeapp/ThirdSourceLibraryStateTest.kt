// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ThirdSourceLibraryStateTest {

    @Test
    fun `历史快照生成卡片并按源接口和影片 ID 反查`() {
        val entry = ThirdHistoryEntry(
            "https://source.example/api", "测试源", "vod-1", "测试影片", "cover",
            0, "第 1 集", 15_000L, 100_000L, 1L, false
        )
        val state = ThirdSourceLibraryState()

        state.loadHistory(listOf(entry), setOf(entry.sourceApi)) { "15 秒" }

        assertEquals(entry, state.historyEntry("https://source.example/api|vod-1"))
        assertNull(state.historyEntry("other|vod-1"))
        assertEquals("https://source.example/api|vod-1", state.historyVideos.single().id)
        assertEquals("第 1 集 · 15 秒", state.historyVideos.single().remarks)
    }

    @Test
    fun `源已移除的收藏仍保留卡片且可按复合 ID 反查`() {
        val entry = ThirdFavorite(
            "https://removed.example/api", "旧源", "vod-2", "收藏影片", "cover",
            1, "第 2 集", 0L, 2L
        )
        val state = ThirdSourceLibraryState()

        state.loadFavorites(listOf(entry), emptySet()) { "removed.example" }

        assertEquals(entry, state.favoriteEntry("https://removed.example/api|vod-2"))
        assertNull(state.favoriteEntry("other|vod-2"))
        assertEquals("源已移除", state.favoriteVideos.single().remarks)
    }
}
