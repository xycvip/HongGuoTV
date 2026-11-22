// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

/**
 * 第三方片源当前播放会话状态。
 *
 * 保存当前分组/集、待播放器重建时消费的起播指令、备用线路尝试集合及临时暂停位置。
 * 不持有播放器或页面对象；具体播放、存储和生命周期操作仍由 ThirdSourceScreen 执行。
 */
internal class ThirdSourcePlaybackSessionState {
    var group: Int = 0
    var episode: Int = 0

    private var pendingStartMs: Long = 0L
    private var pendingAutoplay: Boolean = true
    private var hasPendingPlay: Boolean = false

    val attemptedFallbackGroups: MutableSet<Int> = mutableSetOf()
    var lastPositionMs: Long = -1L

    fun queuePlay(group: Int, episode: Int, startMs: Long, autoplay: Boolean = true) {
        this.group = group
        this.episode = episode
        pendingStartMs = startMs
        pendingAutoplay = autoplay
        hasPendingPlay = true
    }

    /** 只消费一次，避免页面重建重复执行同一条起播指令。 */
    fun consumePendingPlay(): Pair<Long, Boolean>? {
        if(!hasPendingPlay) return null
        hasPendingPlay = false
        return pendingStartMs to pendingAutoplay
    }

    fun consumeLastPosition(): Long {
        val position = lastPositionMs.coerceAtLeast(0L)
        lastPositionMs = -1L
        return position
    }

    fun markFallbackAttempt(group: Int) {
        attemptedFallbackGroups += group
    }

    fun resetFallbackAttempts() {
        attemptedFallbackGroups.clear()
    }
}
