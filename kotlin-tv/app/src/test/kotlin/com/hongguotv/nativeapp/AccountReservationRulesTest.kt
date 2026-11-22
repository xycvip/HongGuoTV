// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import org.junit.Assert.assertEquals
import org.junit.Test

class AccountReservationRulesTest {
    @Test
    fun `未登录状态优先显示登录入口`() {
        assertEquals("登录后预约", AccountReservationRules.label(false, true, true))
    }

    @Test
    fun `已预约优先于待确认状态`() {
        assertEquals("已预约", AccountReservationRules.label(true, true, true))
    }

    @Test
    fun `预约请求接受但未确认时提示刷新`() {
        assertEquals("刷新预约状态", AccountReservationRules.label(true, false, true))
    }
}
