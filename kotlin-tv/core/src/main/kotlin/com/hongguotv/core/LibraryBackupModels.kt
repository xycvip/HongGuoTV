// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import org.json.JSONArray
import org.json.JSONObject

// 备份文件的数据结构与单条记录序列化。
data class BackupProgress(val series: Series,val episodeId: String,val episodeIndex: Int,val position: Long,val duration: Long,val completed: Boolean,val updatedAt: Long) {
    fun json()=JSONObject().put("series",LibraryBackup.seriesJson(series)).put("episodeId",episodeId).put("episodeIndex",episodeIndex).put("position",position).put("duration",duration).put("completed",completed).put("updatedAt",updatedAt)
}
data class BackupSettings(val quality: Int=PlaybackQuality.AUTO_COMPAT,val speed: Float=1f,val autoNext: Boolean=true,val frame: VideoFrameMode=VideoFrameMode.FIT,val autoSeason: Boolean=true,val danmaku: Boolean=true,
    val danmakuOpacity: Int=DanmakuPrefs.OPACITY_DEFAULT,val danmakuFont: Int=DanmakuPrefs.FONT_DEFAULT,val danmakuSpeed: Int=DanmakuPrefs.SPEED_DEFAULT,val danmakuRows: Int=DanmakuPrefs.ROWS_DEFAULT,
    val showClock: Boolean=false,
    val startupAutoplay: Boolean=true,val sleepActionMode: Int=0,val theme: String="NIGHT",
    val videoInfoMode: Int=0,val showNetworkSpeed: Boolean=false) {
    fun json()=JSONObject().put("quality",quality).put("speed",speed.toDouble()).put("autoNext",autoNext).put("frame",frame.name).put("autoSeason",autoSeason).put("danmaku",danmaku)
        .put("danmakuOpacity",danmakuOpacity).put("danmakuFont",danmakuFont).put("danmakuSpeed",danmakuSpeed).put("danmakuRows",danmakuRows).put("showClock",showClock)
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
data class BackupData(val favorites: List<Series>,val history: List<BackupProgress>,val watched: Set<String>,val searches: List<RecentSearch>,val settings: BackupSettings,val later: List<Series> = emptyList(),val hidden: Set<String> = emptySet(),val favoriteGroups: Map<String,String> = emptyMap(),val accountState: String? = null,val playbackExtras: BackupPlaybackExtras? = null)
