// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * 云账号同步策略。
 *
 * - [OFF]：默认。账号数据与本机数据隔离，账号内容只读（可从账号数据播放，
 *   但播放进度只写本机）；云端收藏/历史不并入本机，本机变化也不上行。
 * - [MERGE]：双向合并（推荐）。开启时把云端与本机按最新取新合并，之后本机变化增量上行。
 * - [CLOUD_WINS]：丢弃本机，以账号为准。本机收藏/历史被云端全集替换，本机独有的项被丢弃。
 * - [LOCAL_WINS]：上传本机覆盖账号。本机全集上传到云端，云端本机没有的项从本机剔除。
 */
enum class SyncMode(val value: String) {
    OFF("off"),
    MERGE("merge"),
    CLOUD_WINS("cloud_wins"),
    LOCAL_WINS("local_wins");

    companion object {
        fun from(value: String?): SyncMode = entries.firstOrNull { it.value == value } ?: OFF
    }
}

/**
 * 删除墓碑：本机删除某条收藏/历史时记录，避免下次合并时云端旧数据把它「复活」。
 *
 * 收藏有云端取消接口（favoriteUpdate follow=0），历史有云端软删除接口
 * （read_history/update is_delete）。两者上传成功后均可从墓碑移除；
 * 若云端接口不可用，墓碑至少在本机保留、阻止下次合并复活该项。
 */
data class SyncTombstone(
    val seriesId: String,
    val deletedAt: Long,
    /** 收藏墓碑走取消收藏接口，历史墓碑走软删除接口，均可推云端清除。 */
    val kind: Kind,
) {
    enum class Kind(val value: String) {
        FAVORITE("favorite"),
        HISTORY("history");

        companion object {
            fun from(value: String?) = entries.firstOrNull { it.value == value } ?: HISTORY
        }
    }

    fun toJson() = JSONObject()
        .put("seriesId", seriesId)
        .put("deletedAt", deletedAt)
        .put("kind", kind.value)

    companion object {
        fun fromJson(obj: JSONObject) = SyncTombstone(
            seriesId = obj.optString("seriesId"),
            deletedAt = obj.optLong("deletedAt"),
            kind = Kind.from(obj.optString("kind")),
        )
    }
}

/** 一次同步结束后持久化的游标与墓碑集合。按账号隔离存储。 */
data class SyncRecord(
    val mode: SyncMode = SyncMode.OFF,
    val lastSyncAt: Long = 0L,
    val tombstones: List<SyncTombstone> = emptyList(),
) {
    fun isTombstoned(seriesId: String, kind: SyncTombstone.Kind): Boolean =
        tombstones.any { it.seriesId == seriesId && it.kind == kind }

    fun encode(): String {
        val arr = JSONArray(tombstones.map { it.toJson() })
        return JSONObject()
            .put("mode", mode.value)
            .put("lastSyncAt", lastSyncAt)
            .put("tombstones", arr)
            .toString()
    }

    companion object {
        fun decode(value: String?): SyncRecord {
            if (value.isNullOrBlank()) return SyncRecord()
            return runCatching {
                val obj = JSONObject(value)
                val arr = obj.optJSONArray("tombstones") ?: JSONArray()
                SyncRecord(
                    mode = SyncMode.from(obj.optString("mode")),
                    lastSyncAt = obj.optLong("lastSyncAt"),
                    tombstones = (0 until arr.length()).mapNotNull { i ->
                        arr.optJSONObject(i)?.let(::fromJsonCompat)
                    },
                )
            }.getOrDefault(SyncRecord())
        }

        private fun fromJsonCompat(obj: JSONObject): SyncTombstone? {
            val t = SyncTombstone.fromJson(obj)
            return t.takeIf { it.seriesId.isNotBlank() }
        }
    }
}
