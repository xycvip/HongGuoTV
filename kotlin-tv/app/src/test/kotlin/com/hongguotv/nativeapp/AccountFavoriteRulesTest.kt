// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import org.junit.Assert.assertEquals
import org.junit.Test

class AccountFavoriteRulesTest {
    @Test
    fun `未登录时显示登录提示`() {
        assertEquals(
            "登录后加入账户收藏",
            AccountFavoriteRules.actionLabel(false, false, false, false),
        )
    }

    @Test
    fun `加载中优先于待刷新和收藏状态`() {
        assertEquals(
            "账户收藏处理中…",
            AccountFavoriteRules.actionLabel(true, true, true, true),
        )
    }

    @Test
    fun `待确认状态优先于已收藏状态`() {
        assertEquals(
            "刷新账户收藏状态",
            AccountFavoriteRules.actionLabel(true, false, true, true),
        )
    }

    @Test
    fun `已收藏显示取消入口`() {
        assertEquals(
            "取消账户收藏",
            AccountFavoriteRules.actionLabel(true, false, false, true),
        )
    }
}
