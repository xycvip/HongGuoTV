// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

/** 播放控制层的轻量 UI 状态，不持有 Android View 或播放器实例。 */
internal class ThirdSourcePlaybackUiState {
    var overlayVisible: Boolean = false
    var lastSeekAt: Long = 0L
    var lastBandwidthBps: Long = 0L

    /** 与原控制器一致：两次方向键 seek 至少间隔 120ms。 */
    fun tryBeginSeek(nowMs: Long): Boolean {
        if(nowMs - lastSeekAt < 120L) return false
        lastSeekAt = nowMs
        return true
    }

    fun resetSession() {
        overlayVisible = false
        lastSeekAt = 0L
        lastBandwidthBps = 0L
    }
}
