// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

/** 第三方播放器进度与观看历史持久化；集中处理写入节流和主备份触发。 */
internal class ThirdSourceProgressRecorder(
    private val store: ThirdStore,
    private val backupRequest: () -> Unit,
    private val now: () -> Long = System::currentTimeMillis
) {
    private var lastHistoryKey = ""
    private var lastHistoryAt = 0L
    private var lastHistoryBackupAt = 0L

    fun save(
        source: ThirdSource,
        detail: ThirdDetail,
        group: Int,
        episode: Int,
        positionMs: Long,
        durationMs: Long
    ) {
        if(positionMs <= 0L) return
        store.saveProgress(
            source.id,
            detail.video.id,
            ThirdProgress(episode, positionMs, group)
        )
        rememberHistory(source, detail, group, episode, positionMs, durationMs)
    }

    private fun rememberHistory(
        source: ThirdSource,
        detail: ThirdDetail,
        group: Int,
        episode: Int,
        positionMs: Long,
        durationMs: Long
    ) {
        val timestamp = now()
        val completed = ThirdPlaybackHistoryRules.isCompleted(positionMs, durationMs)
        val key = ThirdPlaybackHistoryRules.historyKey(
            source.api, detail.video.id, group, episode, completed
        )
        if(!ThirdPlaybackHistoryRules.shouldWriteHistory(key, lastHistoryKey, timestamp, lastHistoryAt)) return
        lastHistoryKey = key
        lastHistoryAt = timestamp
        val label = detail.groups.getOrNull(group)?.second?.getOrNull(episode)?.label.orEmpty()
        store.rememberHistory(
            ThirdHistoryEntry(
                source.api,
                source.name,
                detail.video.id,
                detail.video.name,
                detail.video.cover,
                episode,
                label,
                positionMs,
                durationMs,
                timestamp,
                completed
            )
        )
        if(completed) store.clearProgress(source.id, detail.video.id)
        if(ThirdPlaybackHistoryRules.shouldBackup(timestamp, lastHistoryBackupAt, completed)) {
            lastHistoryBackupAt = timestamp
            backupRequest()
        }
    }
}
