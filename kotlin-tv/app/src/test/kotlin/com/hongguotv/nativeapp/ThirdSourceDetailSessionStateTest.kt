// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ThirdSourceDetailSessionStateTest {
    @Test
    fun `清理详情会话时同时清除详情来源和分组`() {
        val state = ThirdSourceDetailSessionState()
        state.group = 3
        state.returnMode = ThirdSourceScreenMode.HISTORY
        state.synopsisExpanded = true

        state.clear()

        assertNull(state.detail)
        assertNull(state.source)
        assertEquals(0, state.group)
        assertEquals(ThirdSourceScreenMode.BROWSE, state.returnMode)
        assertEquals(false, state.synopsisExpanded)
    }
}
