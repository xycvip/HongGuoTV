// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

/** 第三方播放器长按左右键预览状态；不直接访问 ExoPlayer、Handler 或 Android View。 */
internal class ThirdSourceScrubState {
    var active: Boolean = false
        private set
    var direction: Int = 0
        private set
    var positionMs: Long = 0L
        private set
    private var startedAtMs: Long = 0L

    fun start(direction: Int, positionMs: Long, nowMs: Long) {
        active = true
        this.direction = direction
        this.positionMs = positionMs.coerceAtLeast(0L)
        startedAtMs = nowMs
    }

    fun changeDirection(direction: Int, nowMs: Long) {
        if(!active || this.direction == direction) return
        this.direction = direction
        startedAtMs = nowMs
    }

    fun advance(nowMs: Long, durationMs: Long): Long {
        if(!active) return positionMs
        val heldMs = (nowMs - startedAtMs).coerceAtLeast(0L)
        val step = ThirdPlaybackInteractionRules.scrubStep(heldMs)
        positionMs = ThirdPlaybackInteractionRules.scrubPosition(positionMs, direction, step, durationMs)
        return positionMs
    }

    fun stop() { active = false }
}
