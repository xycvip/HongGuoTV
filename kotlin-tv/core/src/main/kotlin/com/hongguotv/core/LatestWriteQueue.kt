// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** Serial persistence with one pending value per key; serialization belongs inside [write]. */
class LatestWriteQueue<K, V>(
    name: String,
    private val delayMillis: Long = 250,
    private val write: (Map<K, V>) -> Boolean,
    private val failed: () -> Unit = {}
) {
    private val worker = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, name).apply { isDaemon = true }
    }
    private val pending = linkedMapOf<K, V>()
    private var scheduled: ScheduledFuture<*>? = null
    private var closed = false
    private var retries = 0

    @Synchronized fun submit(values: Map<K, V>) {
        check(!closed) { "Persistence queue is closed" }
        pending.putAll(values)
        if (scheduled == null) schedule(delayMillis)
    }

    @Synchronized fun flush() {
        if (closed || pending.isEmpty()) return
        scheduled?.cancel(false)
        schedule(0)
    }

    private fun schedule(delay: Long) {
        scheduled = worker.schedule({ drain() }, delay, TimeUnit.MILLISECONDS)
    }

    private fun drain() {
        val batch = synchronized(this) {
            scheduled = null
            if (pending.isEmpty()) {
                if (closed) worker.shutdown()
                return
            }
            pending.toMap().also { pending.clear() }
        }
        val success = runCatching { write(batch) }.getOrDefault(false)
        synchronized(this) {
            if (success) {
                retries = 0
            } else {
                // A newer queued checkpoint must win over this failed, older snapshot.
                batch.forEach { (key, value) -> if (!pending.containsKey(key)) pending[key] = value }
                retries++
                runCatching { failed() }
            }

            if (closed) {
                // Closing is complete only after every final checkpoint is committed.
                if (pending.isEmpty()) {
                    worker.shutdown()
                } else if (scheduled == null) {
                    // Retry indefinitely with bounded backoff; awaitClosed() supplies the caller's
                    // time limit while the daemon worker keeps the last snapshot available.
                    val exponent = (retries - 1).coerceIn(0, 5)
                    val retryDelay = minOf(30_000L, 1_000L * (1L shl exponent))
                    schedule(retryDelay)
                }
            } else if (!success && retries <= 2 && scheduled == null) {
                schedule(1_000)
            }
        }
    }

    /**
     * Nonblocking close: queued writes continue with backoff until the final snapshot is committed.
     * The worker is shut down only after the pending map is empty.
     */
    @Synchronized fun close() {
        if (closed) return
        closed = true
        scheduled?.cancel(false)
        scheduled = null
        schedule(0)
    }

    /** True only when the worker has terminated and no uncommitted values remain. */
    fun awaitClosed(timeout: Long, unit: TimeUnit): Boolean {
        val terminated = worker.awaitTermination(timeout, unit)
        return terminated && synchronized(this) { pending.isEmpty() }
    }
}
