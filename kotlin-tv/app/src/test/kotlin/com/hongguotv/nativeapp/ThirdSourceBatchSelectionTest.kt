// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThirdSourceBatchSelectionTest {

    @Test
    fun `切换单项选择不会修改原集合`() {
        val selected = setOf("a")
        assertEquals(setOf("a", "b"), ThirdSourceBatchSelection.toggle(selected, "b"))
        assertEquals(emptySet<String>(), ThirdSourceBatchSelection.toggle(selected, "a"))
        assertEquals(setOf("a"), selected)
    }

    @Test
    fun `全选与取消全选按当前源集合计算`() {
        val ids = listOf("a", "b", "c")
        assertEquals(setOf("a", "b", "c"), ThirdSourceBatchSelection.toggleAll(emptySet(), ids))
        assertEquals(emptySet<String>(), ThirdSourceBatchSelection.toggleAll(ids.toSet(), ids))
        assertTrue(ThirdSourceBatchSelection.allSelected(ids.toSet(), ids))
        assertFalse(ThirdSourceBatchSelection.allSelected(setOf("a"), ids))
        // 旧源残留 ID 不能让当前源列表误判为「全部已选」。
        assertFalse(ThirdSourceBatchSelection.allSelected(setOf("a", "b", "c", "stale"), ids))
        assertFalse(ThirdSourceBatchSelection.allSelected(emptySet(), emptyList()))
    }
}
