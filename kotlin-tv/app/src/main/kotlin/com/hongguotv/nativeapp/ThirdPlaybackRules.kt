// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import androidx.media3.ui.AspectRatioFrameLayout

internal object ThirdPlaybackHistoryRules {
    const val HISTORY_INTERVAL_MS = 30_000L
    const val BACKUP_INTERVAL_MS = 5 * 60_000L
    const val COMPLETION_TOLERANCE_MS = 5_000L

    fun isCompleted(positionMs: Long, durationMs: Long): Boolean =
        durationMs > 0L && positionMs >= durationMs - COMPLETION_TOLERANCE_MS

    fun historyKey(
        sourceApi: String,
        videoId: String,
        groupIndex: Int,
        episodeIndex: Int,
        completed: Boolean
    ): String {
        val key = "$sourceApi|$videoId|$groupIndex|$episodeIndex"
        return if(completed) "$key|done" else key
    }

    fun shouldWriteHistory(
        key: String,
        previousKey: String?,
        nowMs: Long,
        previousAtMs: Long
    ): Boolean = key != previousKey || nowMs - previousAtMs >= HISTORY_INTERVAL_MS

    fun shouldBackup(nowMs: Long, previousBackupAtMs: Long, completed: Boolean): Boolean =
        completed || nowMs - previousBackupAtMs >= BACKUP_INTERVAL_MS
}

internal object ThirdPlaybackInteractionRules {
    fun scrubStep(heldMs: Long): Long =
        (5_000L + heldMs.coerceAtLeast(0L) / 1_000L * 10_000L).coerceAtMost(60_000L)

    fun scrubPosition(currentMs: Long, direction: Int, stepMs: Long, durationMs: Long): Long {
        val upper = durationMs.takeIf { it > 0L } ?: Long.MAX_VALUE
        return (currentMs + direction.toLong() * stepMs).coerceIn(0L, upper)
    }

    fun bufferText(bandwidthBps: Long): String {
        if(bandwidthBps <= 0L) return "正在缓冲…"
        val kbs = bandwidthBps / 8 / 1024
        val speed = if(kbs >= 1024) String.format("%.1f MB/s", kbs / 1024f) else "$kbs KB/s"
        return "正在缓冲 · $speed"
    }

    fun nextFallbackGroup(
        groupCount: Int,
        currentGroup: Int,
        attempted: Set<Int>,
        hasEpisodes: (Int) -> Boolean
    ): Int? = (0 until groupCount).firstOrNull { index ->
        index != currentGroup && index !in attempted && hasEpisodes(index)
    }
}

internal object ThirdPlaybackSkipRules {
    fun storageKey(sourceApi: String?, videoId: String): String =
        (sourceApi.orEmpty()) + "|" + videoId

    fun introTargetMs(seconds: Int): Long =
        seconds.coerceAtLeast(0).toLong() * 1_000L

    fun shouldSkipIntro(currentPositionMs: Long, seconds: Int): Boolean =
        seconds > 0 && currentPositionMs < introTargetMs(seconds)

    fun shouldSkipOutro(
        isPlaying: Boolean,
        durationMs: Long,
        currentPositionMs: Long,
        seconds: Int,
        alreadyTriggered: Boolean
    ): Boolean {
        if(alreadyTriggered || !isPlaying || seconds <= 0 || durationMs <= 0) return false
        val threshold = durationMs - introTargetMs(seconds)
        return currentPositionMs >= threshold
    }
}

internal object ThirdSourcePlaybackRules {
    fun resizeModeLabel(mode: Int): String = when (mode) {
        1 -> "拉伸"
        2 -> "裁剪"
        else -> "适应"
    }

    fun resizeModeValue(mode: Int): Int = when (mode) {
        1 -> AspectRatioFrameLayout.RESIZE_MODE_FILL
        2 -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
    }
}
