// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** Serial persistence with one pending value per key; serialization belongs inside [write]. */
class LatestWriteQueue<K, V>(
    name: String,
    private val delayMillis: Long = 250,
    private val write: (Map<K, V>) -> Boolean,
    private val failed: () -> Unit = {}
) {
    private val worker = ScheduledThreadPoolExecutor(1) { task ->
        Thread(task, name).apply { isDaemon = true }
    }.apply {
        // Scheduled retries may be delayed for up to 30 seconds. Keep the daemon worker
        // available so an idle timeout cannot strand a delayed task in the scheduler queue.
        setRemoveOnCancelPolicy(true)
    }
    private val pending = linkedMapOf<K, V>()
    private var scheduled: ScheduledFuture<*>? = null
    private var retryScheduled = false
    private var closed = false
    private var writing = false
    private var inFlight: Map<K, V> = emptyMap()
    private var retries = 0

    @Synchronized fun submit(values: Map<K, V>) {
        check(!closed) { "Persistence queue is closed" }
        pending.putAll(values)
        if (scheduled == null) {
            schedule(delayMillis)
        } else if (retryScheduled && !writing) {
            // A new checkpoint should not sit behind a long retry backoff.
            scheduled?.cancel(false)
            scheduled = null
            schedule(delayMillis)
        }
    }

    @Synchronized fun flush() {
        if (closed || pending.isEmpty()) return
        scheduled?.cancel(false)
        scheduled = null
        schedule(0)
    }

    private fun schedule(delay: Long, retry: Boolean = false) {
        retryScheduled = retry
        scheduled = worker.schedule({ drain() }, delay, TimeUnit.MILLISECONDS)
    }

    private fun drain() {
        val batch = synchronized(this) {
            scheduled = null
            retryScheduled = false
            if (pending.isEmpty()) {
                if (closed) worker.shutdown()
                return
            }
            writing = true
            pending.toMap().also {
                pending.clear()
                inFlight = it
            }
        }
        val success = runCatching { write(batch) }.getOrDefault(false)
        synchronized(this) {
            writing = false
            inFlight = emptyMap()
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
                    schedule(retryDelayMillis(), retry = true)
                }
            } else if (!success && scheduled == null) {
                // Keep retrying while the process is alive; a newer submit/flush can pull
                // the retry forward instead of waiting behind the maximum backoff.
                schedule(retryDelayMillis(), retry = true)
            }
        }
    }

    private fun retryDelayMillis(): Long {
        val exponent = (retries - 1).coerceIn(0, 5)
        return minOf(30_000L, 1_000L * (1L shl exponent))
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
        retryScheduled = false
        schedule(0)
    }

    /**
     * Returns the newest not-yet-durable values, including the batch currently being written.
     * Pending values override in-flight values for the same key. Readers can overlay this
     * snapshot on SharedPreferences after a bounded wait, avoiding stale startup state when
     * the previous session is still retrying a failed disk commit.
     */
    @Synchronized fun snapshotPending(): Map<K, V> =
        LinkedHashMap<K, V>(inFlight.size + pending.size).apply {
            putAll(inFlight)
            putAll(pending)
        }

    /**
     * Waits for the current pending batch to become idle without shutting the queue down.
     * Used by a replacement Library instance to get a fresh snapshot while keeping the
     * process-wide writer reusable for subsequent sessions.
     */
    fun awaitIdle(timeout: Long, unit: TimeUnit): Boolean {
        val deadline = System.nanoTime() + unit.toNanos(timeout)
        while (true) {
            val idle = synchronized(this) { pending.isEmpty() && scheduled == null && !writing }
            if (idle) return true
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0L) return false
            try {
                TimeUnit.NANOSECONDS.sleep(minOf(remaining, TimeUnit.MILLISECONDS.toNanos(20)))
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
    }

    /** True only when the worker has terminated and no uncommitted values remain. */
    fun awaitClosed(timeout: Long, unit: TimeUnit): Boolean {
        val terminated = worker.awaitTermination(timeout, unit)
        return terminated && synchronized(this) { pending.isEmpty() }
    }
}
