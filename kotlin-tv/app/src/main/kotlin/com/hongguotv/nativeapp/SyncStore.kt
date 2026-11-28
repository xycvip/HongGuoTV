// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import android.content.SharedPreferences
import com.hongguotv.core.SyncMode
import com.hongguotv.core.SyncRecord
import com.hongguotv.core.SyncTombstone

/**
 * 同步状态的持久化仓库。按账号 id 分命名空间：换账号自动重置为默认关闭，
 * 退出登录不清（下次登录同一账号仍记得开关）。
 *
 * 用 SharedPreferences 而非 Library 的 serial writer：同步状态读写都在主线程/IO 轻量发生，
 * 且需要与 Library 备份完全隔离（不进导出备份，避免把 A 账号的墓碑带到 B 账号）。
 */
class SyncStore(context: Context) {
    private val app = context.applicationContext
    private val prefs: SharedPreferences = app.getSharedPreferences("cloud-sync-v1", Context.MODE_PRIVATE)

    /** 当前活跃账号的 id；未登录时为空。所有读写都针对这个账号。 */
    @Volatile
    var accountKey: String = ""
        private set

    fun bindAccount(key: String) {
        accountKey = key
    }

    private fun keyOf(suffix: String) = "sync:$accountKey:$suffix"

    fun record(): SyncRecord {
        if (accountKey.isBlank()) return SyncRecord()
        return SyncRecord.decode(prefs.getString(keyOf("record"), null))
    }

    fun save(record: SyncRecord) {
        if (accountKey.isBlank()) return
        prefs.edit().putString(keyOf("record"), record.encode()).apply()
    }

    /** 追加墓碑；同一 seriesId + kind 只保留最新一条。 */
    fun addTombstone(seriesId: String, kind: SyncTombstone.Kind, deletedAt: Long = System.currentTimeMillis()) {
        if (accountKey.isBlank() || seriesId.isBlank()) return
        val current = record()
        val kept = current.tombstones.filterNot { it.seriesId == seriesId && it.kind == kind } +
            SyncTombstone(seriesId, deletedAt, kind)
        save(current.copy(tombstones = kept))
    }

    /** 墓碑推云端成功后移除（收藏走取消收藏、历史走软删除）。 */
    fun clearTombstone(seriesId: String, kind: SyncTombstone.Kind) {
        if (accountKey.isBlank()) return
        val current = record()
        val kept = current.tombstones.filterNot { it.seriesId == seriesId && it.kind == kind }
        save(current.copy(tombstones = kept))
    }

    /** CLOUD_WINS 以账号为准时，本机此前的删除意图全部作废。 */
    fun clearAllTombstones() {
        if (accountKey.isBlank()) return
        save(record().copy(tombstones = emptyList()))
    }

    fun setMode(mode: SyncMode) {
        if (accountKey.isBlank()) return
        save(record().copy(mode = mode))
    }

    fun setLastSyncAt(time: Long) {
        if (accountKey.isBlank()) return
        save(record().copy(lastSyncAt = time))
    }

    /** 账号退出时只解绑，不删数据——重新登录同一账号要记得开关与墓碑。 */
    fun unbind() {
        accountKey = ""
    }
}
