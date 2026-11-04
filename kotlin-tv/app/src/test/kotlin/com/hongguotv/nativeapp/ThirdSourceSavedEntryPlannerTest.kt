// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThirdSourceSavedEntryPlannerTest {
    private val first = ThirdSource("a", "源 A", "https://a.example/api")
    private val second = ThirdSource("b", "源 B", "https://b.example/api")

    @Test
    fun `按保存记录中的接口地址解析原片源`() {
        val result = ThirdSourceSavedEntryPlanner.resolve(second.api, listOf(first, second), first.id)

        assertEquals(second, result.source)
        assertTrue(result.shouldSwitch)
    }

    @Test
    fun `当前片源相同时不触发切源`() {
        val result = ThirdSourceSavedEntryPlanner.resolve(first.api, listOf(first, second), first.id)

        assertEquals(first, result.source)
        assertFalse(result.shouldSwitch)
    }

    @Test
    fun `片源已删除时返回空且不触发切源`() {
        val result = ThirdSourceSavedEntryPlanner.resolve("https://gone.example/api", listOf(first), first.id)

        assertNull(result.source)
        assertFalse(result.shouldSwitch)
    }
}
