// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import com.hongguotv.core.AccountCredentials
import com.hongguotv.core.CloudProgress
import com.hongguotv.core.CloudProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountHistoryCoordinatorTest {
    @Test
    fun `未登录时不暴露历史记录`() {
        val coordinator = createCoordinator(signedIn = false)
        assertFalse(coordinator.ready)
        assertTrue(coordinator.rows().isEmpty())
        assertNull(coordinator.progress("id"))
    }

    @Test
    fun `重置会清空已加载标记和历史`() {
        val coordinator = createCoordinator(signedIn = true)
        coordinator.refresh(showResult = false)
        assertTrue(coordinator.ready)
        coordinator.addProgress(progress("id"), 3)
        assertEquals(1, coordinator.rows().size)
        coordinator.reset()
        assertFalse(coordinator.ready)
        assertTrue(coordinator.rows().isEmpty())
    }

    private fun createCoordinator(signedIn: Boolean) = AccountHistoryCoordinator(
        loadHistory = { CloudProfile("account", "测试账号") to emptyList() },
        taskCoordinator = object : AccountTaskRunner {
            override fun <T> run(
                action: () -> T,
                success: (T) -> Unit,
                failure: (Throwable) -> Unit,
            ) {
                runCatching(action).fold(success, failure)
            }
        },
        account = { if(signedIn) AccountCredentials("sessionid=test") else null },
        isSignedIn = { signedIn },
        isClosed = { false },
        isBusy = { false },
        setStatus = {},
        onProfileLoaded = {},
        onChanged = {},
        toast = {},
        safeError = { it.message.orEmpty() },
    )

    private fun progress(id: String) = CloudProgress(
        series = com.hongguotv.core.Series(id, "剧集"),
        episodeId = "ep1",
        episodeIndex = 0,
        position = 1000L,
        duration = 2000L,
        updatedAt = 1L,
    )
}
