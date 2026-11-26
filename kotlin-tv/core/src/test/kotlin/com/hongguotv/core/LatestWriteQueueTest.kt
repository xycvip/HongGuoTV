package com.hongguotv.core

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class LatestWriteQueueTest {
    @Test fun `close drains latest values for every partition without caller serialization`() {
        val committed = mutableListOf<Map<String, Int>>()
        val caller = Thread.currentThread()
        var writeThread: Thread? = null
        val queue = LatestWriteQueue<String, Int>("test-library", 60_000, write = {
            writeThread = Thread.currentThread(); committed += it; true
        })
        queue.submit(mapOf("progress" to 1, "favorites" to 5))
        queue.submit(mapOf("progress" to 2))
        queue.close()
        assertTrue(queue.awaitClosed(5, TimeUnit.SECONDS))
        assertEquals(listOf(mapOf("progress" to 2, "favorites" to 5)), committed)
        assertNotSame(caller, writeThread)
    }

    @Test fun `flush executes pending writes before coalescing delay`() {
        val committed = CountDownLatch(1)
        val queue = LatestWriteQueue<String, Int>("test-library", 60_000, write = { committed.countDown(); true })
        queue.submit(mapOf("progress" to 1))
        queue.flush()
        assertTrue(committed.await(5, TimeUnit.SECONDS))
        queue.close()
        assertTrue(queue.awaitClosed(5, TimeUnit.SECONDS))
    }

    @Test fun `failed in flight old checkpoint never replaces newer value during close`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val committed = mutableListOf<Map<String, Int>>()
        var attempts = 0
        val queue = LatestWriteQueue<String, Int>("test-library", 60_000, write = { values ->
            attempts++
            if (attempts == 1) {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                false
            } else { committed += values; true }
        })
        queue.submit(mapOf("progress" to 1, "favorites" to 8))
        queue.flush()
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        queue.submit(mapOf("progress" to 2))
        queue.close()
        release.countDown()
        assertTrue(queue.awaitClosed(5, TimeUnit.SECONDS))
        assertEquals(listOf(mapOf("progress" to 2, "favorites" to 8)), committed)
    }

    @Test fun `snapshot includes in flight values and lets newer pending values win`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val committed = mutableListOf<Map<String, Int>>()
        val queue = LatestWriteQueue<String, Int>("test-library", 60_000, write = { values ->
            if (committed.isEmpty()) {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
            committed += values
            true
        })

        queue.submit(mapOf("progress" to 1, "favorites" to 4))
        queue.flush()
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        queue.submit(mapOf("progress" to 2))
        assertEquals(
            mapOf("progress" to 2, "favorites" to 4),
            queue.snapshotPending(),
        )

        release.countDown()
        queue.flush()
        assertTrue(queue.awaitIdle(5, TimeUnit.SECONDS))
        assertEquals(
            listOf(mapOf("progress" to 1, "favorites" to 4), mapOf("progress" to 2)),
            committed,
        )
        queue.close()
        assertTrue(queue.awaitClosed(5, TimeUnit.SECONDS))
    }

    @Test fun `open process queue keeps retrying a failed checkpoint`() {
        val allowCommit = AtomicBoolean(false)
        val failed = CountDownLatch(1)
        val committed = CountDownLatch(1)
        val queue = LatestWriteQueue<String, Int>("test-library", 0, write = { values ->
            assertEquals(mapOf("progress" to 7), values)
            if (allowCommit.get()) {
                committed.countDown()
                true
            } else {
                failed.countDown()
                false
            }
        })

        queue.submit(mapOf("progress" to 7))
        assertTrue(failed.await(5, TimeUnit.SECONDS))
        allowCommit.set(true)
        assertTrue(committed.await(5, TimeUnit.SECONDS))
        assertTrue(queue.awaitIdle(5, TimeUnit.SECONDS))

        queue.close()
        assertTrue(queue.awaitClosed(5, TimeUnit.SECONDS))
    }

    @Test fun `queue can be reused after previous session becomes idle`() {
        val committed = mutableListOf<Map<String, Int>>()
        val queue = LatestWriteQueue<String, Int>("test-library", 60_000, write = {
            committed += it
            true
        })

        queue.submit(mapOf("progress" to 1))
        queue.flush()
        assertTrue(queue.awaitIdle(5, TimeUnit.SECONDS))

        queue.submit(mapOf("progress" to 2, "favorites" to 3))
        queue.flush()
        assertTrue(queue.awaitIdle(5, TimeUnit.SECONDS))
        assertEquals(
            listOf(mapOf("progress" to 1), mapOf("progress" to 2, "favorites" to 3)),
            committed,
        )

        queue.close()
        assertTrue(queue.awaitClosed(5, TimeUnit.SECONDS))
    }

    @Test fun `close does not report success while final checkpoint is failing`() {
        val allowCommit = AtomicBoolean(false)
        val attempts = AtomicInteger()
        val failed = CountDownLatch(1)
        val committed = CountDownLatch(1)
        val queue = LatestWriteQueue<String, Int>("test-library", 60_000, write = { values ->
            attempts.incrementAndGet()
            if (!allowCommit.get()) false else {
                assertEquals(mapOf("progress" to 9), values)
                committed.countDown()
                true
            }
        }, failed = { failed.countDown() })

        queue.submit(mapOf("progress" to 9))
        queue.close()
        assertTrue(failed.await(5, TimeUnit.SECONDS))
        assertFalse(queue.awaitClosed(100, TimeUnit.MILLISECONDS))

        allowCommit.set(true)
        assertTrue(committed.await(5, TimeUnit.SECONDS))
        assertTrue(queue.awaitClosed(5, TimeUnit.SECONDS))
        assertTrue(attempts.get() >= 2)
    }
}
