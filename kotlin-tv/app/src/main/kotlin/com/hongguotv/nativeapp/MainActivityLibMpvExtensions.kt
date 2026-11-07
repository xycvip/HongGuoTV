// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.hongguotv.core.RemoteVideo
import androidx.media3.common.Player
import com.x.hongguotv.libmpv.LibMpvPlaybackView
import java.util.WeakHashMap

/**
 * Temporary in-player A/B test overlay. The normal ExoPlayer session remains alive
 * but paused underneath, so closing this overlay returns to the same episode without
 * touching the MediaCodec or FFmpeg implementation.
 */
internal fun MainActivity.libMpvDefaultEnabled(): Boolean =
    getSharedPreferences("libmpv-settings-v1", android.content.Context.MODE_PRIVATE)
        .getBoolean("default_engine_libmpv", false)

internal fun MainActivity.setLibMpvDefaultEnabled(enabled: Boolean) {
    getSharedPreferences("libmpv-settings-v1", android.content.Context.MODE_PRIVATE)
        .edit().putBoolean("default_engine_libmpv", enabled).apply()
}

internal fun MainActivity.switchToLibMpv() {
    setLibMpvDefaultEnabled(true)
    if (screen != "player" || video == null || player?.playbackState != Player.STATE_READY) {
        Toast.makeText(this, "已设为默认 libmpv 内核，下次进入播放页时启用", Toast.LENGTH_LONG).show()
        return
    }
    openLibMpvTest()
}

internal fun MainActivity.switchToSystemPlayer() {
    setLibMpvDefaultEnabled(false)
    closeLibMpvTest()
}

internal object LibMpvTestOverlay {
    data class Session(
        val view: LibMpvPlaybackView,
        val toolbar: LinearLayout,
        val oldPlayerViewVisibility: Int,
        val oldHudVisibility: Int,
        val oldDanmakuVisibility: Int,
        val resumeExo: Boolean,
        val positionMs: Long
    )
    val sessions = WeakHashMap<MainActivity, Session>()
}

internal fun MainActivity.openLibMpvTest(resumeOverride: Boolean? = null) {
    if (LibMpvTestOverlay.sessions.containsKey(this)) return
    val remote: RemoteVideo = video ?: run {
        Toast.makeText(this, "播放地址尚未准备好，请等画面开始播放后再试", Toast.LENGTH_LONG).show()
        return
    }
    val oldPlayerView = playerView
    val oldHud = hud
    val oldDanmaku = danmakuView
    val oldPlayerViewVisibility = oldPlayerView?.visibility ?: View.VISIBLE
    val oldHudVisibility = oldHud.visibility
    val oldDanmakuVisibility = oldDanmaku?.visibility ?: View.GONE
    val position = currentPosition().coerceAtLeast(0L)
    val resume = resumeOverride ?: (player?.playWhenReady ?: requestedAutoplay)
    saveProgress()
    player?.pause()

    val mpv = LibMpvPlaybackView(this).apply {
        setZOrderMediaOverlay(true)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
    }
    val status = TextView(this).apply {
        text = "libmpv：正在初始化原生播放内核…"
        setTextColor(Color.WHITE)
        textSize = 14f
        setPadding(dp(12), dp(8), dp(12), dp(8))
        background = GradientDrawable().apply {
            setColor(0xCC111820.toInt())
            cornerRadius = dp(8).toFloat()
        }
    }
    val toolbar = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12), dp(8), dp(12), dp(8))
        background = GradientDrawable().apply { setColor(0xCC080B10.toInt()) }
        addView(status, LinearLayout.LayoutParams(0, -2, 1f))
    }
    fun action(label: String, click: () -> Unit) {
        val item = TextView(this).apply {
            text = label
            textSize = 14f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(9), dp(14), dp(9))
            background = GradientDrawable().apply {
                setColor(0xFF34485B.toInt())
                cornerRadius = dp(6).toFloat()
            }
            isFocusable = true
            isClickable = true
            setOnClickListener { click() }
        }
        toolbar.addView(item, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8) })
    }
    action("暂停/播放") { mpv.togglePause() }
    action("硬件解码") { mpv.setHardwareMode(LibMpvPlaybackView.HardwareMode.HARDWARE) }
    action("软件解码") { mpv.setHardwareMode(LibMpvPlaybackView.HardwareMode.SOFTWARE) }
    action("切回系统播放器") { switchToSystemPlayer() }

    var firstMpvPositionReported = false
    mpv.listener = object : LibMpvPlaybackView.Listener {
        override fun onState(state: String) {
            status.text = "libmpv · $state · 解码器：${mpv.currentHardwareDecoder()}"
            if (state == "ENDED" && LibMpvTestOverlay.sessions.containsKey(this@openLibMpvTest)) {
                saveProgress(true)
                val shouldContinue = foreground && requestedAutoplay && !pausedForLifecycle
                closeLibMpvTest(restorePlayback = false)
                when {
                    sleepTimer.episodeEnded() -> stopForSleep()
                    shouldContinue && library.autoNext && episodeIndex < (detail?.episodes?.lastIndex ?: -1) ->
                        playEpisode(episodeIndex + 1)
                    shouldContinue && continueToNextSeason() -> Unit
                    else -> {
                        requestedAutoplay = false
                        showEndedPanel()
                    }
                }
            }
        }
        override fun onPosition(positionSeconds: Double, durationSeconds: Double) {
            if (!firstMpvPositionReported) {
                firstMpvPositionReported = true
                hideBootSplash()
                markStartupRecommendationPlaying()
            }
            if (durationSeconds > 0.0) {
                status.text = "libmpv · ${formatTime((positionSeconds * 1000).toLong())} / ${formatTime((durationSeconds * 1000).toLong())} · ${mpv.currentHardwareDecoder()}"
            }
        }
        override fun onDecoder(name: String) {
            status.text = "libmpv · 实际解码器：$name"
        }
        override fun onLog(level: Int, message: String) {
            debugLog("libmpv[$level] $message")
        }
    }

    try {
        mpv.initialize(
            video = remote,
            mode = LibMpvPlaybackView.HardwareMode.HARDWARE,
            startPositionSeconds = position / 1000.0,
            autoplay = resume
        )
        mpv.setSpeed(library.playbackSpeed.toDouble())
        oldPlayerView?.visibility = View.INVISIBLE
        oldHud.visibility = View.GONE
        oldDanmaku?.visibility = View.GONE
        playbackTapFeedbackView?.hide()
        root.addView(mpv, FrameLayout.LayoutParams(-1, -1))
        root.addView(toolbar, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        LibMpvTestOverlay.sessions[this] = LibMpvTestOverlay.Session(
            mpv, toolbar,
            oldPlayerViewVisibility,
            oldHudVisibility,
            oldDanmakuVisibility,
            resume, position
        )
        mpv.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> { switchToSystemPlayer(); true }
                KeyEvent.KEYCODE_DPAD_LEFT -> { mpv.seekTo((mpv.currentPositionSeconds() - 10.0).coerceAtLeast(0.0)); true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { mpv.seekTo(mpv.currentPositionSeconds() + 10.0); true }
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { mpv.togglePause(); true }
                else -> false
            }
        }
        mpv.requestFocus()
        debugLog("libmpv A/B 测试已启动 position=$position resume=$resume mode=HARDWARE")
    } catch (problem: Throwable) {
        runCatching { mpv.close() }
        oldPlayerView?.visibility = oldPlayerViewVisibility
        oldHud.visibility = oldHudVisibility
        oldDanmaku?.visibility = oldDanmakuVisibility
        setLibMpvDefaultEnabled(false)
        if (resume) player?.play()
        Toast.makeText(this, "libmpv 启动失败：${problem.message}，已恢复系统播放器", Toast.LENGTH_LONG).show()
        debugLogError("libmpv 测试启动失败", problem)
    }
}

internal fun MainActivity.closeLibMpvTest(restorePlayback: Boolean = true) {
    val session = LibMpvTestOverlay.sessions.remove(this) ?: return
    val returnPositionMs = if (restorePlayback) {
        runCatching { (session.view.currentPositionSeconds() * 1000.0).toLong() }
            .getOrNull()?.takeIf { it > 0L } ?: session.positionMs
    } else session.positionMs
    runCatching { session.view.close() }
    if (session.view.parent === root) root.removeView(session.view)
    if (session.toolbar.parent === root) root.removeView(session.toolbar)
    playerView?.visibility = session.oldPlayerViewVisibility
    hud.visibility = session.oldHudVisibility
    danmakuView?.visibility = session.oldDanmakuVisibility
    if (restorePlayback) {
        player?.seekTo(returnPositionMs)
        saveProgress()
        if (session.resumeExo) player?.play()
        updatePlaybackText()
        syncMediaSession()
    }
    debugLog("libmpv A/B 测试已结束，恢复 ExoPlayer position=$returnPositionMs resume=${session.resumeExo}")
}
