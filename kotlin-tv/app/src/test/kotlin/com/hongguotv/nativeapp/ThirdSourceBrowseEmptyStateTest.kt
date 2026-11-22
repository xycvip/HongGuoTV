// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import org.junit.Assert.assertEquals
import org.junit.Test

class ThirdSourceBrowseEmptyStateTest {
    private fun message(
        searchWord: String? = null,
        searchDetailsCount: Int = 0,
        aggregating: Boolean = false,
        sourcesDone: Int = 0,
        sourceTotal: Int = 0,
        searchLoading: Boolean = false,
        loadError: String? = null,
        loading: Boolean = false,
        sourceName: String? = null,
    ) = ThirdSourceBrowseEmptyState.message(
        searchWord, searchDetailsCount, aggregating, sourcesDone, sourceTotal,
        searchLoading, loadError, loading, sourceName
    )

    @Test
    fun `搜索筛选后无匹配结果优先提示调整筛选`() {
        assertEquals(
            "没有符合筛选条件的结果，调整筛选或清除筛选试试",
            message(searchWord = "星河", searchDetailsCount = 3, aggregating = true),
        )
    }

    @Test
    fun `聚合搜索显示已返回源数量`() {
        assertEquals(
            "正在聚合搜索「星河」…（2/5 个源已返回）",
            message(searchWord = "星河", aggregating = true, sourcesDone = 2, sourceTotal = 5),
        )
    }

    @Test
    fun `搜索加载失败与无结果文案区分`() {
        assertEquals(
            "搜索失败：网络超时，换个关键词或稍后重试",
            message(searchWord = "星河", loadError = "网络超时"),
        )
        assertEquals(
            "未找到与「星河」相关的内容，换个关键词试试",
            message(searchWord = "星河"),
        )
    }

    @Test
    fun `浏览态加载中与空分类文案正确`() {
        assertEquals("正在加载「甲片源」…", message(loading = true, sourceName = "甲片源"))
        assertEquals("该分类暂无内容", message())
    }
}
