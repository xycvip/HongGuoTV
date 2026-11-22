// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.os.Handler
import android.view.MotionEvent
import android.view.View
import android.widget.SeekBar

/** 手机播放器画面触控协调器：进度条/HUD 命中测试、中央按钮点击与单击唤起控制栏。 */
internal class MainActivityTouchGestureController(
    private val state: PlaybackUiState,
    private val main: Handler,
    private val isPhonePlayer: () -> Boolean,
    private val progressBar: () -> View?,
    private val hud: () -> View?,
    private val hasBlockingPanel: () -> Boolean,
    private val playerDuration: () -> Long,
    private val screenWidthPx: () -> Float,
    private val dp: (Int) -> Float,
    private val currentPosition: () -> Long,
    private val seekRunnable: Runnable,
    private val centerControl: () -> View?,
    private val showCenterControl: () -> Unit,
    private val showHud: () -> Unit,
    private val hideHud: () -> Unit,
    private val updatePlaybackText: () -> Unit,
    private val seekTo: (Long) -> Unit,
    private val screenHeightPx: () -> Float,
    private val beginVerticalGesture: (Boolean) -> Float,
    private val updateVerticalGesture: (Boolean, Float, Float) -> Int,
    private val finishVerticalGesture: (Boolean, Int) -> Unit,
    private val changeEpisode: (Boolean) -> Unit,
    private val startLongPressSpeed: () -> Unit,
    private val stopLongPressSpeed: () -> Unit
) {
    private var verticalGesture = false
    private var verticalLeft = false
    private var verticalEpisode = false
    private var verticalStartValue = 0f
    private var verticalLastValue = -1
    private var longPressSpeedActive = false
    private var touchOnCenterControl = false
    private var touchStartedWithHudVisible = false
    private val longPressSpeedRunnable = Runnable {
        if(state.touchTracking && !state.touchOnHud && !state.touchOnProgressBar &&
            !state.touchSeekGesture && !verticalGesture && !hasBlockingPanel()
        ) {
            longPressSpeedActive = true
            startLongPressSpeed()
        }
    }
    fun dispatch(event: MotionEvent, dispatchToView: (MotionEvent) -> Boolean): Boolean {
        val action = event.actionMasked
        if(!isPhonePlayer()) {
            if(action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) clearGesture()
            return dispatchToView(event)
        }

        if(action == MotionEvent.ACTION_DOWN) {
            touchOnCenterControl = isInside(centerControl(), event)
            touchStartedWithHudVisible = hud()?.let { it.visibility == View.VISIBLE && it.isShown } == true
        }
        if(touchOnCenterControl) {
            val handled = dispatchToView(event)
            if(action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                touchOnCenterControl = false
            }
            return handled
        }

        if(action == MotionEvent.ACTION_DOWN) {
            state.touchOnProgressBar = false
            state.touchOnHud = false
            progressBar()?.takeIf { it is SeekBar && it.visibility == View.VISIBLE && it.isShown }?.let { bar ->
                val location = IntArray(2)
                bar.getLocationOnScreen(location)
                state.touchOnProgressBar = event.rawX >= location[0] &&
                    event.rawX <= location[0] + bar.width &&
                    event.rawY >= location[1] &&
                    event.rawY <= location[1] + bar.height
            }
            if(!state.touchOnProgressBar) {
                hud()?.takeIf { it.visibility == View.VISIBLE && it.isShown }?.let { panel ->
                    val location = IntArray(2)
                    panel.getLocationOnScreen(location)
                    state.touchOnHud = event.rawX >= location[0] &&
                        event.rawX <= location[0] + panel.width &&
                        event.rawY >= location[1] &&
                        event.rawY <= location[1] + panel.height
                }
            }
        }

        if(state.touchOnProgressBar) {
            val handled = dispatchToView(event)
            if(action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                state.touchOnProgressBar = false
                state.touchTracking = false
                state.touchSeekGesture = false
                state.touchSeekTarget = null
            }
            return handled
        }

        if(state.touchOnHud) {
            val handled = dispatchToView(event)
            if(action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) state.touchOnHud = false
            return handled
        }

        if(hasBlockingPanel()) {
            if(action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                state.touchTracking = false
                state.touchSeekGesture = false
                state.touchSeekTarget = null
                state.touchOnHud = false
            }
            return dispatchToView(event)
        }

        when(action) {
            MotionEvent.ACTION_DOWN -> {
                state.touchTracking = true
                state.touchStartX = event.x
                state.touchStartY = event.y
                state.touchSeekStart = currentPosition()
                state.touchSeekTarget = null
                state.touchSeekGesture = false
                verticalGesture = false
                verticalEpisode = false
                verticalLastValue = -1
                // 手机视频画面任意一侧长按均临时切换 3 倍速；控制栏/进度条区域不参与。
                main.removeCallbacks(longPressSpeedRunnable)
                main.postDelayed(longPressSpeedRunnable, 350L)
            }
            MotionEvent.ACTION_MOVE -> if(state.touchTracking) {
                val dx = event.x - state.touchStartX
                val dy = event.y - state.touchStartY
                if(kotlin.math.abs(dx) > dp(24) || kotlin.math.abs(dy) > dp(24)) {
                    main.removeCallbacks(longPressSpeedRunnable)
                    if(longPressSpeedActive) {
                        longPressSpeedActive = false
                        stopLongPressSpeed()
                    }
                }
                if(!state.touchSeekGesture && !verticalGesture &&
                    kotlin.math.abs(dy) > dp(24) &&
                    kotlin.math.abs(dy) > kotlin.math.abs(dx) * 1.2f
                ) {
                    verticalGesture = true
                    val width = screenWidthPx().coerceAtLeast(1f)
                    val startX = state.touchStartX
                    verticalEpisode = startX >= width * 0.2f && startX < width * 0.8f
                    if (!verticalEpisode) {
                        verticalLeft = startX < width * 0.2f
                        verticalStartValue = beginVerticalGesture(verticalLeft)
                        verticalLastValue = (verticalStartValue * 100f).toInt().coerceIn(0, 100)
                    }
                }
                if(verticalGesture) {
                    if (!verticalEpisode) {
                        val delta = (-dy / screenHeightPx().coerceAtLeast(1f) * 1.5f)
                        verticalLastValue = updateVerticalGesture(verticalLeft, verticalStartValue, delta)
                            .coerceIn(0, 100)
                        showHud()
                    }
                    return true
                }
                if(!verticalGesture && !state.touchSeekGesture && kotlin.math.abs(dx) > dp(24) &&
                    kotlin.math.abs(dx) > kotlin.math.abs(dy) * 1.2f
                ) state.touchSeekGesture = true
                if(state.touchSeekGesture) {
                    val duration = playerDuration().coerceAtLeast(0L)
                    if(duration > 0L) {
                        main.removeCallbacks(seekRunnable)
                        val maxDelta = minOf(duration, 180_000L)
                        val fraction = (dx / screenWidthPx().coerceAtLeast(1f)).coerceIn(-1f, 1f)
                        val target = (state.touchSeekStart + (fraction * maxDelta).toLong())
                            .coerceIn(0L, (duration - 500L).coerceAtLeast(0L))
                        state.touchSeekTarget = target
                        state.pendingSeek = target
                        showHud()
                        updatePlaybackText()
                        return true
                    }
                }
            }
            MotionEvent.ACTION_UP -> if(state.touchTracking) {
                main.removeCallbacks(longPressSpeedRunnable)
                state.touchTracking = false
                if(longPressSpeedActive) {
                    longPressSpeedActive = false
                    stopLongPressSpeed()
                    return true
                }
                if(verticalGesture) {
                    verticalGesture = false
                    if (verticalEpisode) {
                        // dy < 0：上滑下一集；dy > 0：下滑上一集。
                        changeEpisode(event.y < state.touchStartY)
                    } else {
                        finishVerticalGesture(verticalLeft, verticalLastValue.coerceAtLeast(0))
                    }
                    verticalEpisode = false
                    verticalLastValue = -1
                    return true
                }
                if(state.touchSeekGesture) {
                    val target = state.touchSeekTarget ?: state.pendingSeek
                    state.touchSeekGesture = false
                    state.touchSeekTarget = null
                    if(target != null) seekTo(target) else showHud()
                    return true
                }
                if(kotlin.math.abs(event.x - state.touchStartX) <= dp(24) &&
                    kotlin.math.abs(event.y - state.touchStartY) <= dp(24)
                ) {
                    // 控制栏已显示时，点击视频空白区域将其收起；隐藏时单击才唤起。
                    if(touchStartedWithHudVisible) {
                        hideHud()
                    } else {
                        showHud()
                        showCenterControl()
                    }
                    touchStartedWithHudVisible = false
                    return true
                }
                showHud()
            }
            MotionEvent.ACTION_CANCEL -> {
                main.removeCallbacks(longPressSpeedRunnable)
                if(longPressSpeedActive) {
                    longPressSpeedActive = false
                    stopLongPressSpeed()
                }
                if(verticalGesture && !verticalEpisode) {
                    finishVerticalGesture(verticalLeft, verticalLastValue.coerceAtLeast(0))
                }
                verticalGesture = false
                verticalEpisode = false
                verticalLastValue = -1
                state.touchTracking = false
                state.touchSeekGesture = false
                state.touchSeekTarget = null
                state.pendingSeek = null
                touchOnCenterControl = false
            }
        }
        return dispatchToView(event)
    }

    private fun clearGesture() {
        main.removeCallbacks(longPressSpeedRunnable)
        if(longPressSpeedActive) { longPressSpeedActive = false; stopLongPressSpeed() }
        state.touchTracking = false
        state.touchSeekGesture = false
        state.touchSeekTarget = null
        state.touchOnProgressBar = false
        touchOnCenterControl = false
        touchStartedWithHudVisible = false
    }

    private fun isInside(view: View?, event: MotionEvent): Boolean {
        if(view == null || view.visibility != View.VISIBLE || !view.isShown || view.width <= 0 || view.height <= 0) return false
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        return event.rawX >= location[0] && event.rawX <= location[0] + view.width &&
            event.rawY >= location[1] && event.rawY <= location[1] + view.height
    }
}
