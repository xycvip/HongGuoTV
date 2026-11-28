// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import com.hongguotv.core.DanmakuLoader

/** 播放会话使用的弹幕渲染器契约，共享数据源、时钟与偏好。 */
interface DanmakuRenderer {
    fun applyPrefs(opacityPct: Int, font: Int, speed: Int, rows: Int)
    fun setColorMode(mode: Int)
    fun setMergeRepeat(enabled: Boolean)
    fun setBlockedFilter(filter: (String) -> Boolean)
    fun bind(loader: DanmakuLoader, position: () -> Long, needMore: (Long) -> Unit = {}, playing: Boolean = true)
    fun setPlaybackActive(active: Boolean)
    fun seekTo(positionMs: Long)
    fun unbind()
}
