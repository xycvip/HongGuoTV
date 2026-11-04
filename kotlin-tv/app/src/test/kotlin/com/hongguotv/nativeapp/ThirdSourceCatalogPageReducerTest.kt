// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThirdSourceCatalogPageReducerTest {
    private fun video(id: String) = ThirdVideo(id, "剧$id", "", "")

    private fun page(videos: List<ThirdVideo>, hasMore: Boolean = true) =
        ThirdListPage(
            categories = emptyList(),
            videos = videos,
            page = 9,
            pageCount = 9,
            hasMore = hasMore,
            total = videos.size,
        )

    @Test
    fun `首屏替换从第一页开始并保留服务端分页状态`() {
        val result = ThirdSourceCatalogPageReducer.replace(page(listOf(video("a"), video("b"))))
        assertEquals(listOf("a", "b"), result.videos.map { it.id })
        assertEquals(0, result.insertAt)
        assertEquals(1, result.page)
        assertTrue(result.hasMore)
    }

    @Test
    fun `追加页按影片 ID 去重并保留原有顺序`() {
        val result = ThirdSourceCatalogPageReducer.append(
            current = listOf(video("a"), video("b")),
            incoming = page(listOf(video("b"), video("c"), video("a"), video("d"))),
            requestedPage = 2,
        )
        assertEquals(listOf("a", "b", "c", "d"), result.videos.map { it.id })
        assertEquals(2, result.freshVideos.size)
        assertEquals(2, result.insertAt)
        assertEquals(2, result.page)
        assertTrue(result.hasMore)
    }

    @Test
    fun `服务端返回空页时即使声明还有更多也停止自动续页`() {
        val result = ThirdSourceCatalogPageReducer.append(
            current = listOf(video("a")),
            incoming = page(emptyList(), hasMore = true),
            requestedPage = 3,
        )
        assertEquals(listOf("a"), result.videos.map { it.id })
        assertFalse(result.hasMore)
    }

    @Test
    fun `首屏空结果不会错误地保留 hasMore`() {
        val result = ThirdSourceCatalogPageReducer.replace(page(emptyList(), hasMore = true))
        assertFalse(result.hasMore)
    }
}
