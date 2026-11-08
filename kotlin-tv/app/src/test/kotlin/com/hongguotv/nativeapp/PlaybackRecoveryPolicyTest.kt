// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackRecoveryPolicyTest {
    @Test
    fun `one episode retries current route then switches once then stops`() {
        val policy = PlaybackRecoveryPolicy()
        assertEquals(PlaybackRecoveryStep.RETRY_CURRENT_ROUTE, policy.next("series:episode:1080"))
        assertEquals(PlaybackRecoveryStep.SWITCH_ROUTE, policy.next("series:episode:1080"))
        assertEquals(PlaybackRecoveryStep.EXHAUSTED, policy.next("series:episode:1080"))
        assertTrue(policy.isExhaustedFor("series:episode:1080"))
    }

    @Test
    fun `new episode receives an independent recovery budget`() {
        val policy = PlaybackRecoveryPolicy()
        policy.next("series:episode-1:1080")
        policy.next("series:episode-1:1080")
        assertTrue(policy.isExhaustedFor("series:episode-1:1080"))
        assertFalse(policy.isExhaustedFor("series:episode-2:1080"))
        assertEquals(PlaybackRecoveryStep.RETRY_CURRENT_ROUTE, policy.next("series:episode-2:1080"))
    }

    @Test
    fun `quality change receives an independent recovery budget`() {
        val policy = PlaybackRecoveryPolicy()
        policy.next("series:episode:1080")
        assertEquals(PlaybackRecoveryStep.RETRY_CURRENT_ROUTE, policy.next("series:episode:720"))
    }
}
