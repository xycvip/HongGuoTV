// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import org.junit.Assert.assertEquals
import org.junit.Test

class ThirdSourceImportPlannerTest {

    @Test
    fun `规划时跳过已有源和同批重复源`() {
        val existing = listOf(ThirdSource("old", "已有", "https://old.example/api"))
        val candidates = listOf(
            ThirdTvboxSite("重复已有", "https://old.example/api"),
            ThirdTvboxSite("新源", "https://new.example/api"),
            ThirdTvboxSite("同批重复", "https://new.example/api")
        )

        val plan = ThirdSourceImportPlanner.plan(existing, candidates, 10)

        assertEquals(listOf("https://new.example/api"), plan.added.map { it.api })
        assertEquals(1, plan.importedCount)
        assertEquals(2, plan.duplicateCount)
        assertEquals(0, plan.overflowCount)
    }

    @Test
    fun `达到源数量上限后统计剩余候选`() {
        val existing = listOf(ThirdSource("old", "已有", "https://old.example/api"))
        val candidates = listOf(
            ThirdTvboxSite("新源一", "https://one.example/api"),
            ThirdTvboxSite("新源二", "https://two.example/api"),
            ThirdTvboxSite("新源三", "https://three.example/api")
        )

        val plan = ThirdSourceImportPlanner.plan(existing, candidates, 2)

        assertEquals(listOf("https://one.example/api"), plan.added.map { it.api })
        assertEquals(1, plan.importedCount)
        assertEquals(0, plan.duplicateCount)
        assertEquals(2, plan.overflowCount)
        assertEquals("成功导入 1 个源，超出上限跳过 2 个", plan.message)
    }

    @Test
    fun `容量已满时不新增且已有项仍优先记为重复`() {
        val existing = listOf(ThirdSource("old", "已有", "https://old.example/api"))
        val candidates = listOf(
            ThirdTvboxSite("重复", "https://old.example/api"),
            ThirdTvboxSite("新源", "https://new.example/api")
        )

        val plan = ThirdSourceImportPlanner.plan(existing, candidates, 1)

        assertEquals(0, plan.importedCount)
        assertEquals(1, plan.duplicateCount)
        assertEquals(1, plan.overflowCount)
    }
    @Test
    fun `容量已满时重复的新候选仍按原规则计为超限`() {
        val existing = listOf(ThirdSource("old", "已有", "https://old.example/api"))
        val candidates = listOf(
            ThirdTvboxSite("超限源", "https://full.example/api"),
            ThirdTvboxSite("重复超限源", "https://full.example/api")
        )

        val plan = ThirdSourceImportPlanner.plan(existing, candidates, 1)

        assertEquals(0, plan.importedCount)
        assertEquals(0, plan.duplicateCount)
        assertEquals(2, plan.overflowCount)
    }

}
