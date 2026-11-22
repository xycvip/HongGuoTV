// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ThirdSourceMatchRulesTest {

    private fun detail(name: String, year: String?) = ThirdDetail(
        video = ThirdVideo(name, name, "", ""),
        typeName = null,
        year = year,
        area = null,
        synopsis = null,
        groups = emptyList()
    )

    @Test
    fun `优先匹配同名同年份的影片`() {
        val wrong = detail("目标剧", "2023")
        val right = detail("目标剧", "2024")
        val result = ThirdSourceMatchRules.bestDetail(listOf(wrong, right), "目标剧", "2024")
        assertEquals(right, result)
    }

    @Test
    fun `无精确匹配时回退到首条候选`() {
        val first = detail("相近剧", "2022")
        val second = detail("其他剧", "2024")
        assertEquals(first, ThirdSourceMatchRules.bestDetail(listOf(first, second), "目标剧", "2024"))
    }

    @Test
    fun `候选为空时返回空`() {
        assertNull(ThirdSourceMatchRules.bestDetail(emptyList(), "目标剧", "2024"))
    }
}
