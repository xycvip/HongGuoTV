// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThirdSourceSearchResultReducerTest {
    private fun source(id: String, api: String = "https://$id/api") =
        ThirdSource(id, id, api)

    private fun detail(id: String, name: String, episodes: Int = 1) = ThirdDetail(
        ThirdVideo(id, name, "", ""),
        null,
        null,
        null,
        null,
        listOf("线路" to (1..episodes).map { ThirdEpisode("第${it}集", "https://video/$id/$it") }),
    )

    @Test
    fun `聚合结果按现有 CMS 规则去重并保留当前源命中`() {
        val current = source("current")
        val other = source("other")
        val existing = listOf(ThirdSearchHit(other, detail("v1", "《示例》", 5)))

        val result = ThirdSourceSearchResultReducer.mergeAggregate(
            existing = existing,
            source = current,
            incoming = listOf(detail("v2", "新片"), detail("v3", "示例", 2)),
            preferredApi = current.api,
        )

        assertEquals(listOf("v3", "v2"), result.hits.map { it.detail.video.id })
        assertEquals(current.id, result.hits.first().source.id)
        assertEquals(result.hits.map { it.detail }, result.details)
        assertEquals(2, result.hitByVideoId.size)
        assertTrue(result.hitByVideoId.containsKey("current|v3"))
        assertTrue(result.hitByVideoId.containsKey("current|v2"))
    }

    @Test
    fun `分页追加过滤已有影片并保留本页返回顺序`() {
        val current = source("current")
        val existing = listOf(ThirdSearchHit(current, detail("v1", "旧片")))

        val result = ThirdSourceSearchResultReducer.appendPage(
            existing,
            current,
            listOf(detail("v1", "重复片"), detail("v2", "新片"), detail("v2", "重复新片")),
        )

        assertEquals(listOf("v1", "v2", "v2"), result.hits.map { it.detail.video.id })
        assertEquals(listOf("旧片", "新片", "重复新片"), result.details.map { it.video.name })
        assertEquals(2, result.hitByVideoId.size)
        assertTrue(result.hitByVideoId.containsKey("current|v1"))
        assertFalse(result.hitByVideoId.containsKey("current|v3"))
    }

    @Test
    fun `不同片源的相同影片 ID 使用独立复合键`() {
        val first = source("first")
        val second = source("second")

        val result = ThirdSourceSearchResultReducer.mergeAggregate(
            existing = listOf(ThirdSearchHit(first, detail("same", "甲"))),
            source = second,
            incoming = listOf(detail("same", "乙")),
            preferredApi = null,
        )

        assertEquals(2, result.hitByVideoId.size)
        assertTrue(result.hitByVideoId.containsKey("first|same"))
        assertTrue(result.hitByVideoId.containsKey("second|same"))
    }
}
