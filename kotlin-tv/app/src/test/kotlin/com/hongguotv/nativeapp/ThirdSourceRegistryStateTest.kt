// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ThirdSourceRegistryStateTest {
    @Test
    fun `当前源按 ID 从注册表中解析且未知 ID 返回空`() {
        val first = ThirdSource("first", "第一个源", "https://first.example/api")
        val second = ThirdSource("second", "第二个源", "https://second.example/api")
        val state = ThirdSourceRegistryState()
        state.sources = listOf(first, second)
        state.batchMode = true
        state.selected.add("second")
        assertEquals(true, state.batchMode)
        assertEquals(setOf("second"), state.selected)

        state.currentId = "second"
        assertEquals(second, state.currentSource())

        state.currentId = "missing"
        assertNull(state.currentSource())
    }
}
