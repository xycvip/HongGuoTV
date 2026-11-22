// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import org.junit.Assert.assertEquals
import org.junit.Test

class AccountFavoriteChangePresentationTest {
    @Test
    fun `添加收藏且列表确认后返回成功文案`() {
        val result = AccountFavoriteChangePresentationRules.success(
            confirmed = true, add = true, wrote = true, rowCount = 12
        )
        assertEquals("已核对账户收藏，共读取 12 部", result.stateMessage)
        assertEquals("已加入账户收藏，并已在账户列表确认", result.completionMessage)
        assertEquals(true, result.confirmed)
    }

    @Test
    fun `取消收藏且列表确认后返回成功文案`() {
        val result = AccountFavoriteChangePresentationRules.success(
            confirmed = true, add = false, wrote = true, rowCount = 4
        )
        assertEquals("已取消账户收藏，并已从本次读取的列表移除", result.completionMessage)
    }

    @Test
    fun `服务端未确认时阻止用户误认为操作成功`() {
        val result = AccountFavoriteChangePresentationRules.success(
            confirmed = false, add = true, wrote = true, rowCount = 3
        )
        assertEquals("请求已提交，列表尚未确认，请刷新或在手机核对", result.stateMessage)
        assertEquals(
            "请求已提交，但账户列表尚未确认生效。请刷新或在手机红果核对，不要连续重复提交。",
            result.completionMessage,
        )
        assertEquals(false, result.confirmed)
    }

    @Test
    fun `无需写入的重复添加和重复取消给出对应提示`() {
        assertEquals(
            "这部剧已在账户收藏中，无需重复添加",
            AccountFavoriteChangePresentationRules.success(true, true, false, 2).completionMessage,
        )
        assertEquals(
            "本次读取的账户列表中已没有这部剧，无需重复取消",
            AccountFavoriteChangePresentationRules.success(true, false, false, 2).completionMessage,
        )
    }

    @Test
    fun `写入前失败与写入后未确认使用不同提示`() {
        val beforeWrite = AccountFavoriteChangePresentationRules.failure(false, "连接超时")
        assertEquals("读取失败：连接超时", beforeWrite.stateMessage)
        assertEquals("未修改账户收藏：连接超时", beforeWrite.completionMessage)

        val afterWrite = AccountFavoriteChangePresentationRules.failure(true, "连接超时")
        assertEquals("收藏操作未确认，请刷新或在手机核对", afterWrite.stateMessage)
        assertEquals("收藏操作未确认：连接超时。请先刷新或在手机核对。", afterWrite.completionMessage)
    }
}
