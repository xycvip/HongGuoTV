// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp.ui.player

/**
 * 调试模式（播放信息第 4 档）在屏幕上展示的播放诊断快照。
 * 由 [PlayerSession] 在心跳里生成，界面只读；一组一组地铺在视频左侧，带半透明背景。
 */
data class DebugRow(val label: String, val value: String)

data class DebugSection(val title: String, val rows: List<DebugRow>)

data class PlaybackDebugInfo(
    val sections: List<DebugSection> = emptyList(),
    val updatedAt: String = "",
)
