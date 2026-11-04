// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import org.junit.Assert.assertEquals
import org.junit.Test

class ThirdSourceSearchFiltersTest {
    private fun hit(sourceId: String, sourceName: String, videoId: String, remarks: String) =
        ThirdSearchHit(
            source = ThirdSource(sourceId, sourceName, "https://example.invalid/$sourceId"),
            detail = ThirdDetail(
                video = ThirdVideo(videoId, "剧名", "", remarks),
                typeName = "短剧",
                year = "2026",
                area = "中国",
                synopsis = null,
                groups = emptyList(),
            ),
        )

    @Test
    fun `聚合搜索卡片使用源与影片复合 ID 并添加源名角标`() {
        val rows = ThirdSourceSearchFilters.displayHits(
            listOf(
                hit("source-a", "甲片源", "same-id", "更新至10集"),
                hit("source-b", "乙片源", "same-id", ""),
            ),
            aggregate = true,
        )

        assertEquals(listOf("source-a|same-id", "source-b|same-id"), rows.map { it.id })
        assertEquals(listOf("甲片源 · 更新至10集", "乙片源"), rows.map { it.remarks })
    }

    @Test
    fun `单源搜索保留原备注但仍使用复合 ID`() {
        val rows = ThirdSourceSearchFilters.displayHits(
            listOf(hit("source-a", "甲片源", "video-1", "全24集")),
            aggregate = false,
        )

        assertEquals("source-a|video-1", rows.single().id)
        assertEquals("全24集", rows.single().remarks)
    }

    @Test
    fun `空备注聚合时只展示源名且不修改原始影片`() {
        val original = ThirdVideo("v1", "剧名", "", "")
        val rows = ThirdSourceSearchFilters.displayHits(
            listOf(
                ThirdSearchHit(
                    ThirdSource("s1", "甲片源", "https://example.invalid"),
                    ThirdDetail(original, "短剧", "2026", "中国", null, emptyList()),
                )
            ),
            aggregate = true,
        )

        assertEquals("甲片源", rows.single().remarks)
        assertEquals("v1", original.id)
        assertEquals("", original.remarks)
    }
}
