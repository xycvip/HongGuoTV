// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import org.json.JSONArray
import org.json.JSONObject

// 备份文件的数据结构与单条记录序列化。
data class BackupProgress(val series: Series,val episodeId: String,val episodeIndex: Int,val position: Long,val duration: Long,val completed: Boolean,val updatedAt: Long) {
    fun json()=JSONObject().put("series",LibraryBackup.seriesJson(series)).put("episodeId",episodeId).put("episodeIndex",episodeIndex).put("position",position).put("duration",duration).put("completed",completed).put("updatedAt",updatedAt)
}
data class BackupSettings(val quality: Int=1080,val speed: Float=1f,val autoNext: Boolean=true,val type: ContentType=ContentType.SHORT,val frame: VideoFrameMode=VideoFrameMode.FIT,val autoSeason: Boolean=true,val danmaku: Boolean=true,
    val danmakuOpacity: Int=DanmakuPrefs.OPACITY_DEFAULT,val danmakuFont: Int=DanmakuPrefs.FONT_DEFAULT,val danmakuSpeed: Int=DanmakuPrefs.SPEED_DEFAULT,val danmakuRows: Int=DanmakuPrefs.ROWS_DEFAULT,
    val showClock: Boolean=false,val sourceHidden: Boolean=false,
    val startupAutoplay: Boolean=true,val sleepActionMode: Int=0,val theme: String="NIGHT",
    val videoInfoMode: Int=0,val showNetworkSpeed: Boolean=false) {
    fun json()=JSONObject().put("quality",quality).put("speed",speed.toDouble()).put("autoNext",autoNext).put("type",type.storedValue).put("frame",frame.name).put("autoSeason",autoSeason).put("danmaku",danmaku)
        .put("danmakuOpacity",danmakuOpacity).put("danmakuFont",danmakuFont).put("danmakuSpeed",danmakuSpeed).put("danmakuRows",danmakuRows).put("showClock",showClock).put("sourceHidden",sourceHidden)
        .put("startupAutoplay",startupAutoplay).put("sleepActionMode",sleepActionMode).put("theme",theme)
        .put("videoInfoMode",videoInfoMode).put("showNetworkSpeed",showNetworkSpeed)
}

/** 本机播放增强与遥控器配置：随用户选择的播放设置一并备份。 */
data class BackupPlaybackExtras(
    val blockedWords: Set<String> = setOf("加群", "微信", "公众号", "QQ群"),
    val perSeriesDanmaku: Map<String, Boolean> = emptyMap(),
    val autoSkipUnplayable: Boolean = false,
    val seekStepSeconds: Int = 10,
    val longPressSpeed: Float = 3f,
    val menuKeyAction: String = "panel",
    val remoteUpAction: String = "previous",
    val remoteDownAction: String = "next",
    val remoteConfirmAction: String = "play_pause",
    val remoteMenuAction: String = "menu",
    val skipMarks: Map<String, SkipMark> = emptyMap()
) {
    fun json(): JSONObject {
        val perSeries = JSONObject()
        perSeriesDanmaku.entries.take(500).forEach { (id, enabled) ->
            if (id.matches(Regex("[0-9]{1,30}"))) perSeries.put(id, enabled)
        }
        val marks = JSONObject()
        skipMarks.entries.take(SkipMarks.MAX_ENTRIES).forEach { (id, mark) ->
            if (id.matches(Regex("[0-9]{1,30}")) && !mark.empty) {
                marks.put(id, JSONObject().put("intro", mark.introSeconds).put("outro", mark.outroSeconds))
            }
        }
        return JSONObject()
            .put("blockedWords", JSONArray(blockedWords.map { it.filterNot(Char::isISOControl).take(50) }.filter { it.isNotBlank() }.take(200)))
            .put("perSeriesDanmaku", perSeries)
            .put("autoSkipUnplayable", autoSkipUnplayable)
            .put("seekStepSeconds", seekStepSeconds)
            .put("longPressSpeed", longPressSpeed.toDouble())
            .put("menuKeyAction", menuKeyAction)
            .put("remoteUpAction", remoteUpAction)
            .put("remoteDownAction", remoteDownAction)
            .put("remoteConfirmAction", remoteConfirmAction)
            .put("remoteMenuAction", remoteMenuAction)
            .put("skipMarks", marks)
    }
}
data class BackupData(val favorites: List<Series>,val history: List<BackupProgress>,val watched: Set<String>,val searches: List<RecentSearch>,val settings: BackupSettings,val later: List<Series> = emptyList(),val hidden: Set<String> = emptySet(),val favoriteGroups: Map<String,String> = emptyMap(),val thirdSources: List<BackupThirdSource> = emptyList(),val thirdHistory: List<BackupThirdHistory> = emptyList(),val thirdFavorites: List<BackupThirdFavorite> = emptyList(),val accountState: String? = null,val playbackExtras: BackupPlaybackExtras? = null)

/** 三方片源配置：只保存名称与接口，随主备份一起迁移；恢复时只追加不覆盖。 */
data class BackupThirdSource(val name: String,val api: String)

/** 三方片源收藏：随主备份迁移，恢复时按「源接口 + 影片」保留较新的一条。 */
data class BackupThirdFavorite(
    val sourceApi: String,val sourceName: String,val videoId: String,val title: String,val cover: String,
    val episodeIndex: Int,val episodeLabel: String,val positionMs: Long,val updatedAt: Long
) {
    fun json()=JSONObject().put("sourceApi",sourceApi).put("sourceName",sourceName).put("videoId",videoId)
        .put("title",title).put("cover",cover).put("episodeIndex",episodeIndex).put("episodeLabel",episodeLabel)
        .put("positionMs",positionMs).put("updatedAt",updatedAt)
}

/** 三方片源观看记录：随主备份迁移，恢复时按「源接口 + 影片」合并并保留较新的进度。 */
data class BackupThirdHistory(
    val sourceApi: String,val sourceName: String,val videoId: String,val title: String,val cover: String,
    val episodeIndex: Int,val episodeLabel: String,val positionMs: Long,val durationMs: Long,
    val updatedAt: Long,val completed: Boolean
) {
    fun json()=JSONObject().put("sourceApi",sourceApi).put("sourceName",sourceName).put("videoId",videoId)
        .put("title",title).put("cover",cover).put("episodeIndex",episodeIndex).put("episodeLabel",episodeLabel)
        .put("positionMs",positionMs).put("durationMs",durationMs).put("updatedAt",updatedAt).put("completed",completed)
}
