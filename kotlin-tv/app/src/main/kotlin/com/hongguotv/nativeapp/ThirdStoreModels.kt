// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import org.json.JSONObject

// 三方片源本地历史、收藏与探活状态模型。
/** 三方片源观看记录：按「源接口 + 影片」去重，最近观看在前。标题与封面本地留存，源或影片失效时卡片仍可显示。 */
data class ThirdHistoryEntry(
    val sourceApi: String, val sourceName: String, val videoId: String, val title: String, val cover: String,
    val episodeIndex: Int, val episodeLabel: String, val positionMs: Long, val durationMs: Long,
    val updatedAt: Long, val completed: Boolean
) {
    fun json() = JSONObject().put("sourceApi", sourceApi).put("sourceName", sourceName).put("videoId", videoId)
        .put("title", title).put("cover", cover).put("episodeIndex", episodeIndex).put("episodeLabel", episodeLabel)
        .put("positionMs", positionMs).put("durationMs", durationMs).put("updatedAt", updatedAt).put("completed", completed)
}

/** 片源内收藏：独立存储并随主备份迁移；标题与封面本地留存，源失效时卡片仍可显示。 */
data class ThirdFavorite(
    val sourceApi: String, val sourceName: String, val videoId: String, val title: String, val cover: String,
    val episodeIndex: Int, val episodeLabel: String, val positionMs: Long, val updatedAt: Long
) {
    fun json() = JSONObject().put("sourceApi", sourceApi).put("sourceName", sourceName).put("videoId", videoId)
        .put("title", title).put("cover", cover).put("episodeIndex", episodeIndex).put("episodeLabel", episodeLabel)
        .put("positionMs", positionMs).put("updatedAt", updatedAt)
}

/** 探活结果缓存：TTL 由界面判断，落盘避免每次冷启动都全量探活。 */
data class ThirdProbeRecord(val ok: Boolean, val total: Int, val reason: String?, val at: Long)

/** 片源播放器的片头/片尾跳过设置（秒，0 表示关闭）。 */
data class ThirdSkipMark(val intro: Int, val outro: Int)
