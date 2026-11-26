// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Buffers official comments for one episode ahead of the playback position. Call [loadUpTo]
 * from a background thread as playback advances (it is cheap and re-entrant); read visible
 * comments with [between] from the UI thread. Failures back off silently — comments are a
 * decoration and must never interrupt playback.
 *
 * 窗口数据来自 [DanmakuWindowSource]（热更新 JS 门面）；本类只保留缓冲/去重/退避逻辑。
 */
class DanmakuLoader(
    private val source: DanmakuWindowSource,
    private val seriesId: String,
    private val vid: String,
    @Volatile var durationMs: Long,
    private val leadMs: Long = 60_000L,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val comments = ArrayList<Danmaku>()
    private val seen = HashSet<String>()
    private val loading = AtomicBoolean(false)
    private val cancelled = AtomicBoolean(false)
    private var frontier = 0L
    private var exhausted = false
    private var failures = 0
    private var retryAfter = 0L

    private fun keyOf(comment: Danmaku): String = if (comment.id.isNotEmpty()) "id:${comment.id}" else "anonymous:${comment.offsetMs}:${comment.text}"

    /** Keep a rolling history and hard cap; older ranges are fetched again after a backward seek. */
    private fun trimCache(positionMs: Long) {
        val cutoff = (positionMs - HISTORY_RETENTION_MS).coerceAtLeast(0L)
        val removeUntil = comments.indexOfFirst { it.offsetMs >= cutoff }.let { if (it < 0) comments.size else it }
        if (removeUntil > 0) {
            for (i in 0 until removeUntil) seen.remove(keyOf(comments[i]))
            comments.subList(0, removeUntil).clear()
        }
        while (comments.size > MAX_BUFFERED_COMMENTS) {
            seen.remove(keyOf(comments.removeAt(0)))
        }
    }

    /** Fetch windows until buffered past [positionMs] + lead. No-op while another load runs. */
    /** Stop this episode's loader. An in-flight source call may finish, but its result is discarded. */
    fun cancel() {
        cancelled.set(true)
    }

    fun loadUpTo(positionMs: Long) {
        if (cancelled.get() || durationMs <= 0 || !loading.compareAndSet(false, true)) return
        try {
            while (!cancelled.get()) {
                val start = synchronized(this) {
                    trimCache(positionMs)
                    // A backward seek beyond the retained/load-ahead range must reopen old windows.
                    if (positionMs + leadMs < frontier) {
                        frontier = (positionMs / WINDOW_MS) * WINDOW_MS
                        exhausted = false
                        failures = 0
                        retryAfter = 0L
                    }
                    when {
                        exhausted || clock() < retryAfter -> return
                        frontier >= durationMs -> { exhausted = true; return }
                        positionMs > frontier -> {
                            // A forward seek skips windows that will never be watched.
                            frontier = positionMs / WINDOW_MS * WINDOW_MS
                            if (frontier > positionMs + leadMs) return
                            frontier
                        }
                        frontier > positionMs + leadMs -> return
                        else -> frontier
                    }
                }
                val window = try {
                    source.window(seriesId, vid, durationMs, start)
                } catch (failed: Exception) {
                    synchronized(this) {
                        failures++
                        // 退避递增（8s → 16s → 32s → 60s 封顶），把放弃前的窗口拉到十几分钟。
                        // 原来固定 8 秒、6 次就永久 exhausted，一次长一点的网络抖动整集就再也没弹幕。
                        val backoff = (RETRY_BACKOFF_MS shl (failures - 1).coerceAtMost(3))
                            .coerceAtMost(MAX_BACKOFF_MS)
                        retryAfter = clock() + backoff
                        if (failures >= MAX_FAILURES) exhausted = true
                    }
                    return
                }
                if (cancelled.get()) return
                synchronized(this) {
                    if (cancelled.get()) return
                    failures = 0
                    for (comment in window.comments) {
                        val key = keyOf(comment)
                        if (seen.add(key)) comments.add(comment)
                    }
                    // Windows normally arrive in order; a forward seek can interleave offsets.
                    if (comments.size > 1 && comments[comments.size - 2].offsetMs > comments[comments.size - 1].offsetMs) {
                        comments.sortBy { it.offsetMs }
                    }
                    frontier = if (window.nextStartMs > start) window.nextStartMs else start + WINDOW_MS
                    trimCache(positionMs)
                    if (frontier >= durationMs) exhausted = true
                }
            }
        } finally {
            loading.set(false)
        }
    }

    /** Comments anchored in [fromMs, [toMs]); the list is sorted by offset. UI-thread safe. */
    fun between(fromMs: Long, toMs: Long): List<Danmaku> = synchronized(this) {
        if (fromMs >= toMs || comments.isEmpty()) return@synchronized emptyList()
        // 每帧都会调用：二分定位起点的开销是 log(n)，比整表 filter 稳得多。
        // 两小时电影缓存上万条时，原来的 O(n) 扫描 + 分配会直接顶到主线程预算。
        var low = 0
        var high = comments.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (comments[mid].offsetMs < fromMs) low = mid + 1 else high = mid
        }
        if (low >= comments.size) return@synchronized emptyList()
        val out = ArrayList<Danmaku>(4)
        var index = low
        while (index < comments.size) {
            val comment = comments[index]
            if (comment.offsetMs >= toMs) break
            out.add(comment)
            index++
        }
        out
    }

    /** Total distinct comments buffered so far; mainly for diagnostics and tests. */
    val buffered: Int get() = synchronized(this) { comments.size }

    companion object {
        const val WINDOW_MS = 30_000L
        /**
         * 网络抖一下就永久放弃整集弹幕，用户看到的就是「这集有时有弹幕、有时一条都没有」。
         * 退避 8 秒起、逐次翻倍，给 10 次机会（约 8 分钟窗口），长时间断网恢复后还能自己接上。
         */
        private const val MAX_FAILURES = 10
        private const val RETRY_BACKOFF_MS = 8_000L
        private const val MAX_BACKOFF_MS = 60_000L
        private const val HISTORY_RETENTION_MS = 8 * 60_000L
        private const val MAX_BUFFERED_COMMENTS = 4_000
    }
}
