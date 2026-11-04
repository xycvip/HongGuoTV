// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ThirdSourceCatalogPageStateTest {
    @Test
    fun `重置分类选择时清空分类缓存与首屏请求守卫`() {
        val state = ThirdSourceCatalogPageState()
        state.categoryId = "movie"
        state.categoriesCached = true
        state.requested = true

        state.resetSelection()

        assertEquals(null, state.categoryId)
        assertFalse(state.categoriesCached)
        assertFalse(state.requested)
    }

    @Test
    fun `重置目录内容时清空分类和分页但不改动请求加载标记`() {
        val state = ThirdSourceCatalogPageState()
        state.categories = listOf(ThirdCategory("movie", "电影"))
        state.page = 5
        state.hasMore = true
        state.videos.add(ThirdVideo("v1", "影片", "", ""))
        state.loading = true
        state.loadingMore = true

        state.resetContent()

        assertEquals(emptyList<ThirdCategory>(), state.categories)
        assertEquals(1, state.page)
        assertFalse(state.hasMore)
        assertEquals(emptyList<ThirdVideo>(), state.videos)
        assertEquals(true, state.loading)
        assertEquals(true, state.loadingMore)
    }
}
