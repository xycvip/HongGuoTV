// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import android.net.NetworkCapabilities
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Player
import com.hongguotv.core.*

internal fun MainActivity.hidePlaybackOverlay() {
        main.removeCallbacks(hideHud)
        val focusWasInControls=runCatching { controls }.isSuccess && controls.findFocus()!=null
        panel=false; controls.visibility=View.GONE; controls.clearFocus(); hud.visibility=View.GONE
        playbackTapFeedbackView?.hide()
        // PlayerView 本身不可聚焦；将隐藏控制栏后的焦点固定在播放器根容器，
        // 防止 Android 把焦点错误回退到首页或已移除的控件。
        if(focusWasInControls && screen=="player" && runCatching { root }.isSuccess) {
            root.isFocusable=true
            root.isFocusableInTouchMode=true
            root.requestFocus()
        }
    }

    internal fun MainActivity.resetHudHideTimer() {
        main.removeCallbacks(hideHud)
        if(screen=="player" && commentPanel==null && episodePanel==null && !ffActive &&
            player?.isPlaying==true && !playError) {
            main.postDelayed(hideHud,5000L)
        }
    }

    internal fun MainActivity.showHud() {
        if(screen!="player") return
        phoneUiState.playerControlsVisible=true
        hud.visibility=View.VISIBLE
        updatePlaybackText()
        resetHudHideTimer()
    }

    internal fun MainActivity.updatePlaybackText() {
        transportPlay?.text=if(player?.playWhenReady==true) "暂停" else "播放"
        sleepButton?.text=if(sleepTimer.active) "定时 ${sleepTimer.label()}" else "定时"
        if(screen!="player" || playError) return
        if(sleepStopped) { playbackText.text="定时停止已生效 · 观看位置已保存 · 按播放可继续"; return }
        if(!playbackReady) { playbackText.text=(if(!requestedAutoplay) "准备后保持暂停 · " else "")+if(video==null) "正在获取播放地址…" else "正在缓冲…"; return }
        val p=player ?: return
        val duration=p.duration.coerceAtLeast(0); val position=pendingSeek ?: p.currentPosition
        val state=when { p.playbackState==Player.STATE_BUFFERING -> "缓冲中"; p.playbackState==Player.STATE_ENDED -> "本集已结束"; !p.playWhenReady -> "已暂停"; else -> "正在播放" }
        playbackText.text="$state  ·  ${formatTime(position)} / ${formatTime(duration)}  ·  ${actualVideoResolutionLabel()}  ·  ${PlaybackSpeed.label(p.playbackParameters.speed)}"+(if(sleepTimer.active) "  ·  定时 ${sleepTimer.label()}" else "")
        if(!progressUserSeeking) progressBar.progress=if(duration>0) (position*1000/duration).toInt().coerceIn(0,1000) else 0
    }

    internal fun MainActivity.requestPlayback(play: Boolean) {
        requestedAutoplay=play; pausedForLifecycle=!play
        if(play) sleepStopped=false
        if(!play) { player?.pause(); retryAutoplay=false; saveProgress() }
        else if(playError) { playEpisode(episodeIndex,retryPosition,true); return }
        else if(episodePanel==null) { if(player?.playbackState==Player.STATE_ENDED) player?.seekTo(0); player?.play() }
        if(!play) window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        showHud(); updatePlaybackText(); syncMediaSession(); if(panel && episodePanel==null) showPanel()
    }

    internal fun MainActivity.seekTo(position: Long) {
        val p=player ?: return; if(!playbackReady || p.duration<=0) return
        main.removeCallbacks(seekRunnable); pendingSeek=null
        p.seekTo(position.coerceIn(0,(p.duration-500).coerceAtLeast(0))); updatePlaybackText(); syncMediaSession(); showHud()
    }

    internal fun MainActivity.skipEpisode(direction: Int) {
        if(!foreground || screen!="player") return
        val count=detail?.episodes?.size ?: return
        val target=episodeIndex+direction
        if(target in 0 until count) playEpisode(target)
    }

    internal fun MainActivity.syncMediaSession() {
        if(!foreground || screen!="player") { mediaSession.deactivate(); return }
        val data=detail ?: return; val p=player
        val state=when {
            playError -> android.media.session.PlaybackState.STATE_ERROR
            sleepStopped || !requestedAutoplay || (p!=null && !p.playWhenReady) -> android.media.session.PlaybackState.STATE_PAUSED
            p==null || !playbackReady || p.playbackState==Player.STATE_BUFFERING -> android.media.session.PlaybackState.STATE_BUFFERING
            p.playbackState==Player.STATE_ENDED -> android.media.session.PlaybackState.STATE_STOPPED
            p.isPlaying -> android.media.session.PlaybackState.STATE_PLAYING
            else -> android.media.session.PlaybackState.STATE_PAUSED
        }
        mediaSession.update(data.series.title,episodeIndex,p?.duration?.coerceAtLeast(0) ?: 0,currentPosition(),library.playbackSpeed,state,episodeIndex>0,episodeIndex<data.episodes.lastIndex)
    }

/** 播放状态控制与当前进度读取。 */
internal fun MainActivity.networkAvailable(): Boolean = connectivity.getNetworkCapabilities(connectivity.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)==true

internal fun MainActivity.currentPosition(): Long = pendingSeek ?: player?.currentPosition?.takeIf { playbackReady } ?: startPosition

internal fun MainActivity.togglePlayback()=requestPlayback(!(if(playbackReady) player?.playWhenReady ?: requestedAutoplay else requestedAutoplay))

internal val MainActivity.videoRendererMode: Int
    get() = getSharedPreferences("playback-renderer-v1", Context.MODE_PRIVATE)
        .getInt("video_renderer_mode", 0).coerceIn(0, 2)

/** 播放器设置与控制栏共用的显示标签和渲染模式映射。 */
internal fun MainActivity.sleepActionLabel(mode: Int = sleepActionMode) = listOf("播放", "软件")[mode.coerceIn(0, 1)]

internal fun MainActivity.danmakuColorModeLabel(mode: Int = danmakuColorMode): String = when(mode) {
    DanmakuView.COLOR_MODE_COLORFUL -> "多彩"
    else -> DanmakuView.COLOR_NAMES.getOrElse(mode - DanmakuView.COLOR_MODE_FIRST) { "默认" }
        .let { if(mode < DanmakuView.COLOR_MODE_FIRST) "默认" else it }
}

internal fun MainActivity.videoRendererModeLabel(mode: Int = videoRendererMode): String =
    listOf("自动", "SurfaceView", "TextureView")[mode.coerceIn(0, 2)]

internal fun MainActivity.resolvedVideoRendererMode(): Int =
    if(videoRendererMode != 0) videoRendererMode
    else if(isTelevisionDevice && seamlessPlaybackEnabled) 2 else 1

internal fun MainActivity.frameResizeMode()=when(library.frameMode) {
    VideoFrameMode.FIT -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
    VideoFrameMode.ZOOM -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
    VideoFrameMode.FILL -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL
}

internal fun MainActivity.skipButtonLabel(intro: Boolean,seconds: Int)=if(seconds>0) "跳过${if(intro) "片头" else "片尾"} ${seconds}秒" else if(intro) "片头" else "片尾"

internal fun MainActivity.stopForSleep() {
        sleepStopped=true; requestedAutoplay=false; retryAutoplay=false; pausedForLifecycle=true
        cancelRecovery(); player?.pause(); saveProgress()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        when (sleepActionMode) {
            1 -> {
                Toast.makeText(this,"定时关闭：正在退出红果TV",Toast.LENGTH_LONG).show()
                library.flush(); killOnDestroy=true; finish()
            }
            else -> {
                playbackText.text="定时关闭已生效 · 已暂停播放 · 观看位置已保存"
                showHud(); if(!playError && episodePanel==null) showPanel(); syncMediaSession()
                Toast.makeText(this,"定时关闭已生效，播放已暂停",Toast.LENGTH_LONG).show()
            }
        }
    }

    internal fun MainActivity.showSleepPicker(anchor: TextView) {
        val labels=listOf("关闭定时停止","15 分钟后","30 分钟后","60 分钟后","90 分钟后","本集播完","再播完 3 集","再播完 5 集")
        tvTools.choose("定时停止 · ${sleepTimer.label()}",labels,anchor,{ choice ->
            when(choice) { 0 -> sleepTimer.cancel(); in 1..4 -> sleepTimer.afterMinutes(listOf(15,30,60,90)[choice-1]); else -> sleepTimer.afterEpisodes(listOf(1,3,5)[choice-5]) }
            if(anchor.tag!="playback-settings") anchor.text=if(sleepTimer.active) "定时 ${sleepTimer.label()}" else "定时"; updatePlaybackText(); showHud()
        })
    }

/** 播放轨道与编码信息读取，供播放诊断和信息浮层复用。 */
internal fun MainActivity.shouldShowSleepSettingHint(): Boolean {
        if(sleepSettingHintShown) return false
        sleepSettingHintShown=true
        return true
    }

/** 显示当前实际视频画面尺寸，不使用用户请求的清晰度档位代替。 */
internal fun MainActivity.actualVideoResolutionLabel(): String {
        val size=player?.videoSize
        val width=size?.width?.takeIf { it>0 }
        val height=size?.height?.takeIf { it>0 }
        if(width!=null && height!=null) return "${width}×${height}"
        val format=selectedVideoFormat()
        val trackWidth=format?.width?.takeIf { it>0 }
        val trackHeight=format?.height?.takeIf { it>0 }
        return if(trackWidth!=null && trackHeight!=null) "${trackWidth}×${trackHeight}" else "分辨率未知"
}

internal fun MainActivity.selectedVideoFormat(): Format? {
        val p=player ?: return activeVideoFormat
        val group=p.currentTracks.groups.firstOrNull { it.type==C.TRACK_TYPE_VIDEO && it.isSelected } ?: return activeVideoFormat
        val selected=(0 until group.length).firstOrNull { group.isTrackSelected(it) } ?: return activeVideoFormat
        return group.mediaTrackGroup.getFormat(selected)
    }

internal fun MainActivity.videoCodecLabel(format: Format?): String {
        val mime=format?.sampleMimeType.orEmpty()
        val label=when(mime) {
            "video/avc" -> "H.264"
            "video/hevc" -> "H.265/HEVC"
            "video/av01" -> "AV1"
            "video/x-vnd.on2.vp9" -> "VP9"
            "video/mp4v-es" -> "MPEG-4"
            "video/3gpp" -> "H.263"
            else -> mime.removePrefix("video/").ifBlank { "编码未知" }
        }
        val codec=format?.codecs?.takeIf { it.isNotBlank() }
        return if(codec!=null && !codec.equals(label,true)) "$label ($codec)" else label
    }

internal fun MainActivity.refreshVideoInfoOverlay() = updateDebugOverlay()
