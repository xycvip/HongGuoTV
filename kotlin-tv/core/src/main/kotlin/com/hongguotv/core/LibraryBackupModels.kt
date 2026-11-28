// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

// 本机资料库的内存快照结构；同时作为收藏/历史等数据的统一表示，供本地序列化与云同步使用。
data class BackupProgress(val series: Series,val episodeId: String,val episodeIndex: Int,val position: Long,val duration: Long,val completed: Boolean,val updatedAt: Long)
data class BackupSettings(val quality: Int=PlaybackQuality.AUTO_HIGH,val speed: Float=1f,val autoNext: Boolean=true,val frame: VideoFrameMode=VideoFrameMode.FIT,val autoSeason: Boolean=true,val danmaku: Boolean=true,
    val danmakuOpacity: Int=DanmakuPrefs.OPACITY_DEFAULT,val danmakuFont: Int=DanmakuPrefs.FONT_DEFAULT,val danmakuSpeed: Int=DanmakuPrefs.SPEED_DEFAULT,val danmakuRows: Int=DanmakuPrefs.ROWS_DEFAULT,
    val showClock: Boolean=false,
    val startupAutoplay: Boolean=true,
    val videoInfoMode: Int=0,val showNetworkSpeed: Boolean=false)
data class BackupData(val favorites: List<Series>,val history: List<BackupProgress>,val watched: Set<String>,val searches: List<RecentSearch>,val settings: BackupSettings,val later: List<Series> = emptyList(),val hidden: Set<String> = emptySet(),val favoriteGroups: Map<String,String> = emptyMap(),val accountState: String? = null)
