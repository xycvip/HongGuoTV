// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import com.hongguotv.core.Series
import org.junit.Assert.assertEquals
import org.junit.Test

class AccountLocalFavoriteSyncPlannerTest {
    @Test
    fun `只返回云端不存在且编号有效的本地收藏`() {
        val local = listOf(Series("100", "A"), Series("200", "B"), Series("100", "A duplicate"), Series("not-numeric", "Invalid"))
        assertEquals(listOf("100"), AccountLocalFavoriteSyncPlanner.missingFromAccount(local, setOf("200")).map { it.id })
    }

    @Test
    fun `最多处理五百条且不触碰云端已有记录`() {
        val local = (1..501).map { Series(it.toString(), "Series $it") }
        val result = AccountLocalFavoriteSyncPlanner.missingFromAccount(local, setOf("1", "2"))
        assertEquals(499, result.size)
        assertEquals("3", result.first().id)
        assertEquals("501", result.last().id)
    }

    @Test
    fun `云端已有记录不会再次加入`() {
        val local = listOf(Series("1", "Local"))
        assertEquals(emptyList<String>(), AccountLocalFavoriteSyncPlanner.missingFromAccount(local, setOf("1")).map { it.id })
    }
}
