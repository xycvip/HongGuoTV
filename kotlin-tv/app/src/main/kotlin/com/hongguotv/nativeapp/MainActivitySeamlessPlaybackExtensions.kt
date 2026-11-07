// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import com.hongguotv.core.ContentRepository
import com.hongguotv.core.RemoteVideo

import android.content.Context
import android.view.Gravity
import android.view.SurfaceView
import android.view.TextureView
import android.widget.FrameLayout
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import okhttp3.Dispatcher
import okhttp3.ConnectionPool
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** 清理备用播放器及其独立网络会话；不会触碰当前正在播放的主播放器。 */
internal fun MainActivity.clearStandbyPlayer(reason: String, releaseFfmpegHandoffOutput: Boolean = true) {
    val oldStandby = standbyPlayer
    val oldRemote = standbyRemote
    val oldOutput = standbyOutputView
    debugLog("FFmpeg诊断[LIFECYCLE] clearStandby reason=$reason B=${oldStandby?.let { System.identityHashCode(it) }} BState=${oldStandby?.playbackState} BMedia=${oldStandby?.currentMediaItem?.mediaId} BPosition=${oldStandby?.currentPosition} BPlayWhenReady=${oldStandby?.playWhenReady} remote=${oldRemote?.let { System.identityHashCode(it) }} output=${oldOutput?.let { System.identityHashCode(it) }} outputState=${when(oldOutput) { is SurfaceView -> "SurfaceView valid=${oldOutput.holder.surface?.isValid}"; is TextureView -> "TextureView available=${oldOutput.isAvailable}"; else -> "none" }} A=${player?.let { System.identityHashCode(it) }} AState=${player?.playbackState} AMedia=${player?.currentMediaItem?.mediaId}")
    standbyJob?.cancel(true)
    standbyJob = null
    standbyByteCounterEnabled?.set(false)
    standbyByteCounterEnabled = null
    standbyPlayer?.let { player ->
        FfmpegPlayerRegistry.detach(player)
        runCatching { player.playWhenReady = false; player.stop(); player.clearMediaItems(); player.release() }
    }
    standbyPlayer = null
    val protectedFfmpegOutput = ffmpegHandoffOutputView
    val keepProtectedFfmpegOutput = protectedFfmpegOutput != null && !releaseFfmpegHandoffOutput
    if (!keepProtectedFfmpegOutput) seamlessHandoffInProgress = false
    standbyOutputView?.let { view ->
        if (view !== protectedFfmpegOutput && view.parent === root) root.removeView(view)
    }
    standbyOutputView = null
    if (protectedFfmpegOutput != null && releaseFfmpegHandoffOutput) {
        debugLog("FFmpeg诊断[LIFECYCLE] 释放受保护输出面 reason=$reason output=${System.identityHashCode(protectedFfmpegOutput)}")
        if (protectedFfmpegOutput.parent === root) root.removeView(protectedFfmpegOutput)
        ffmpegHandoffOutputView = null
    } else if (keepProtectedFfmpegOutput) {
        debugLog("FFmpeg诊断[LIFECYCLE] 暂不释放接管中的 FFmpeg 输出面 reason=$reason output=${System.identityHashCode(protectedFfmpegOutput)}")
    }
    standbyRemote?.close()
    standbyRemote = null
    closeTransport(standbyHttp)
    standbyHttp = null
    standbyEpisodeId = ""
    standbyReady = false
    // 先清空所有双播放器状态，再恢复普通缓冲提示；否则旧目标 ID 会让隐藏条件仍成立。
    updatePlaybackBufferingIndicator()
    if (!keepProtectedFfmpegOutput) {
        standbyPrepareStartedAt = 0L
        standbyReadyAt = 0L
        standbyFirstFrameAt = 0L
        standbyPromotionAt = 0L
        standbyAttachedAt = 0L
        standbyVisibleFirstFrameAt = 0L
    }
    debugLog("无感连播：备用播放器已清理，原因=$reason")
}

/** 在后台解析下一集，并用独立的隐藏输出面预先启动解码器。 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal fun MainActivity.prepareStandbyEpisode(index: Int) {
    val data = detail ?: return
    if (!seamlessPlaybackEnabled || !foreground || screen != "player" || !playbackReady) return
    if (index !in data.episodes.indices) return
    val id = data.episodes[index]
    val quality = library.maxQuality
    val key = "$id:$quality"
    if (standbyEpisodeId == key && (standbyReady || standbyJob?.isDone == false)) return

    val manager = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
    if (manager.isLowRamDevice || manager.memoryClass <= 128) {
        standbyFallbackEpisodeId = data.episodes.getOrNull(episodeIndex).orEmpty()
        debugLog("无感连播：设备内存等级较低，回退单播放器预加载")
        maybePrefetch(force = true)
        return
    }

    // 接管成功后，备用资源已经转为当前播放器；此时不能再调用清理函数，
    // 否则会把当前播放器尚未完成的画面交接状态一并清零，造成缓冲转圈闪现。
    val hasOldStandbyResources = standbyPlayer != null ||
        standbyRemote != null ||
        standbyHttp != null ||
        standbyJob != null ||
        standbyOutputView != null ||
        standbyByteCounterEnabled != null ||
        standbyEpisodeId.isNotEmpty()
    if (hasOldStandbyResources) clearStandbyPlayer("准备新的下一集", releaseFfmpegHandoffOutput = false)
    standbyFallbackEpisodeId = ""
    standbyEpisodeId = key
    // 从解析下一集地址开始就视为双播放器准备中，不等 ExoPlayer 创建完成。
    updatePlaybackBufferingIndicator()
    // 每一轮备用准备使用独立计时基准，不能沿用上一集的 READY/隐藏首帧时间。
    standbyPrepareStartedAt = android.os.SystemClock.elapsedRealtime()
    standbyReadyAt = 0L
    standbyFirstFrameAt = 0L
    val counterGate = AtomicBoolean(false)
    standbyByteCounterEnabled = counterGate
    val http = repository.http.newBuilder()
        .dispatcher(Dispatcher())
        .connectionPool(ConnectionPool())
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .build()
    standbyHttp = http
    val source = ContentRepository(http)
    val requestGeneration = generation
    val requestSeries = data.series.id
    standbyJob = prefetchWorker.submit {
        var remote: RemoteVideo? = null
        try {
            val prepared = RemoteVideo(http, source.stream(id, quality), diagnostic = { event -> main.post { if (!isDestroyed) debugLog("FFmpeg诊断[B][$id] $event") } }).also {
                remote = it
                it.prepare()
            }
            main.post {
                if (isDestroyed || !foreground || generation != requestGeneration ||
                    detail?.series?.id != requestSeries || !seamlessPlaybackEnabled ||
                    standbyEpisodeId != key || screen != "player") {
                    prepared.close()
                    closeTransport(http)
                    if (standbyHttp === http) standbyHttp = null
                    if (standbyByteCounterEnabled === counterGate) {
                        counterGate.set(false)
                        standbyByteCounterEnabled = null
                    }
                    if (standbyEpisodeId == key) {
                        standbyEpisodeId = ""
                        standbyReady = false
                        updatePlaybackBufferingIndicator()
                        standbyPrepareStartedAt = 0L
                        standbyReadyAt = 0L
                        standbyFirstFrameAt = 0L
                    }
                    return@post
                }
                var secondaryPlayer: ExoPlayer? = null
                try {
                    val ffmpegSetup = FfmpegPlayerRegistry.create(this, "B")
                    val renderers = ffmpegSetup.renderersFactory
                    val secondary = ExoPlayer.Builder(this)
                        .setRenderersFactory(renderers)
                        .setLoadControl(
                            DefaultLoadControl.Builder()
                                // B 接管后前几秒偶发卡顿：预加载阶段不能只满足首帧，
                                // 还要尽量攒够连续播放所需的数据，避免刚接管就追网络。
                                .setBufferDurationsMs(6_000, 18_000, 1_500, 3_500)
                                .setTargetBufferBytes(8 * 1024 * 1024)
                                // 双播放器设备已通过内存门槛；优先满足缓冲时长，
                                // 避免 1080P 等高码率视频因小内存阈值提前停止加载。
                                .setPrioritizeTimeOverSizeThresholds(true)
                                .build()
                        )
                        .build()
                    secondaryPlayer = secondary
                    FfmpegPlayerRegistry.attach(secondary, ffmpegSetup)
                    debugLog("FFmpeg诊断[B][ATTACH] identity=${ffmpegSetup.identity} ${FfmpegPlayerRegistry.identitySnapshot(secondary)}")
                    secondary.setAudioAttributes(
                        AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
                            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true
                    )
                    secondary.volume = 0f
                    secondary.playWhenReady = false

                    // READY 只代表播放器管线可播放；隐藏输出面让解码器先真正输出首帧。
                    // 这不是整集缓存，仍只保留播放器短缓冲。
                    val output: android.view.View = if (resolvedVideoRendererMode() == 2) {
                        TextureView(this).apply { alpha = 0f; isFocusable = false }
                    } else {
                        SurfaceView(this).apply { alpha = 0f; isFocusable = false }
                    }
                    output.importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    root.addView(output, FrameLayout.LayoutParams(dp(16), dp(16), Gravity.BOTTOM or Gravity.END))
                    standbyOutputView = output
                    when (output) {
                        is TextureView -> secondary.setVideoTextureView(output)
                        is SurfaceView -> secondary.setVideoSurfaceView(output)
                    }

                    debugLog("FFmpeg诊断[B][CREATE] identity=${ffmpegSetup.identity} ${FfmpegPlayerRegistry.identitySnapshot(secondary)} remote=${System.identityHashCode(prepared)} output=${System.identityHashCode(output)} outputState=${when(output) { is SurfaceView -> "SurfaceView valid=${output.holder.surface?.isValid}"; is TextureView -> "TextureView available=${output.isAvailable}"; else -> "unknown" }} A=${player?.let { System.identityHashCode(it) }} AState=${player?.playbackState}")
                    secondary.addAnalyticsListener(object : AnalyticsListener {
                        override fun onVideoDecoderInitialized(
                            eventTime: AnalyticsListener.EventTime,
                            decoderName: String,
                            initializedTimestampMs: Long,
                            initializationDurationMs: Long
                        ) {
                            debugLog("FFmpeg诊断[B][DECODER] player=${System.identityHashCode(secondary)} media=${secondary.currentMediaItem?.mediaId} name=$decoderName init=${initializationDurationMs}ms globalMode=${FfmpegPlayerRegistry.videoMode} actualMode=${FfmpegPlayerRegistry.currentVideoMode(secondary)} identity=${FfmpegPlayerRegistry.identitySnapshot(secondary)}")
                        }
                    })
                    secondary.addListener(object : Player.Listener {
                        override fun onIsLoadingChanged(isLoading: Boolean) { if (standbyPlayer === secondary || player === secondary) debugLog("FFmpeg诊断[B][$id] loading=$isLoading state=${secondary.playbackState} position=${secondary.currentPosition} buffered=${secondary.bufferedPosition}") }
                        override fun onPlaybackStateChanged(state: Int) {
                            if (standbyPlayer !== secondary && player !== secondary) return
                            debugLog("FFmpeg诊断[B][$id] state=$state position=${secondary.currentPosition} buffered=${secondary.bufferedPosition} playWhenReady=${secondary.playWhenReady}")
                            if (state == Player.STATE_READY) {
                                if (!secondary.currentTracks.isTypeSelected(C.TRACK_TYPE_VIDEO)) {
                                    debugLog("无感连播：备用播放器 READY 但没有已选视频轨道，拒绝接管")
                                    clearStandbyPlayer("备用播放器没有可用视频轨道")
                                    standbyFallbackEpisodeId = data.episodes.getOrNull(episodeIndex).orEmpty()
                                    maybePrefetch(force = true)
                                    return
                                }
                                standbyReady = true
                                standbyReadyAt = android.os.SystemClock.elapsedRealtime()
                                standbyFallbackEpisodeId = ""
                                debugLog("无感连播：备用播放器 READY，耗时=${standbyReadyAt - standbyPrepareStartedAt}ms，下一集 index=${index + 1}")
                            } else if (state == Player.STATE_IDLE && standbyReady) {
                                standbyReady = false
                            }
                        }

                        override fun onRenderedFirstFrame() {
                            val now = android.os.SystemClock.elapsedRealtime()
                            debugLog("FFmpeg诊断[LIFECYCLE] B firstFrame player=${System.identityHashCode(secondary)} state=${secondary.playbackState} position=${secondary.currentPosition} surface=${System.identityHashCode(output)} surfaceState=${when(output) { is SurfaceView -> "valid=${output.holder.surface?.isValid}"; is TextureView -> "available=${output.isAvailable}"; else -> "unknown" }} promoted=${player === secondary}")
                            if (standbyPlayer === secondary && standbyEpisodeId == key) {
                                if (standbyFirstFrameAt == 0L) {
                                    standbyFirstFrameAt = now
                                    debugLog("无感连播：备用播放器隐藏输出已渲染首帧，距准备开始=${now - standbyPrepareStartedAt}ms，距 READY=${if (standbyReadyAt > 0L) now - standbyReadyAt else -1L}ms")
                                }
                            } else if (player === secondary && standbyPromotionAt > 0L && standbyVisibleFirstFrameAt == 0L) {
                                // FFmpeg handoff keeps the warm-up surface alive until this
                                // first frame confirms that PlayerView's visible surface works.
                                if (ffmpegHandoffOutputView === output) {
                                    debugLog("FFmpeg诊断[LIFECYCLE] 正式画面首帧已到，释放受保护的 FFmpeg 输出面 output=" + System.identityHashCode(output))
                                    if (output.parent === root) root.removeView(output)
                                    ffmpegHandoffOutputView = null
                                }
                                if (standbyOutputView === output) standbyOutputView = null
                                standbyVisibleFirstFrameAt = now
                                val handoffAt = standbyPromotionAt
                                // 首帧刚出现时，播放器可能紧接着再短暂报告 BUFFERING。
                                // 留出 350ms 稳定窗口，避免画面已接上却闪一下转圈；持续缓冲仍会恢复提示。
                                main.postDelayed({
                                    if (player === secondary &&
                                        standbyPromotionAt == handoffAt &&
                                        standbyVisibleFirstFrameAt > 0L &&
                                        seamlessHandoffInProgress
                                    ) {
                                        seamlessHandoffInProgress = false
                                        updatePlaybackBufferingIndicator()
                                        debugLog("无感连播：可见首帧稳定 350ms，恢复普通缓冲提示")
                                    }
                                }, 350L)
                                debugLog("无感连播：接管后可见首帧，切换到画面=${now - handoffAt}ms，PlayerView绑定=${standbyAttachedAt - handoffAt}ms；缓冲提示延后 350ms 恢复")
                            }
                        }

                        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                            if (standbyPlayer !== secondary && player !== secondary) return
                            debugLog("FFmpeg诊断[B][$id] PlayerError code=${error.errorCode} name=${error.errorCodeName} message=${error.message}")
                            debugLog("FFmpeg诊断[B][ERROR] ${FfmpegPlayerRegistry.identitySnapshot(secondary)} ${VideoDataSourceTraceRegistry.errorSnapshot("B:${System.identityHashCode(secondary)}:$id")}")
                            debugLog("FFmpeg诊断[LIFECYCLE] B error snapshot player=${System.identityHashCode(secondary)} state=${secondary.playbackState} position=${secondary.currentPosition} buffered=${secondary.bufferedPosition} playWhenReady=${secondary.playWhenReady} media=${secondary.currentMediaItem?.mediaId} remote=${standbyRemote?.let { System.identityHashCode(it) }} output=${System.identityHashCode(output)} outputState=${when(output) { is SurfaceView -> "valid=${output.holder.surface?.isValid}"; is TextureView -> "available=${output.isAvailable}"; else -> "unknown" }} A=${player?.let { System.identityHashCode(it) }} AState=${player?.playbackState} AMedia=${player?.currentMediaItem?.mediaId}")
                            debugLog("无感连播：备用播放器准备失败 ${error.errorCodeName} ${error.message}")
                            clearStandbyPlayer("备用播放器准备失败")
                            standbyFallbackEpisodeId = data.episodes.getOrNull(episodeIndex).orEmpty()
                            maybePrefetch(force = true)
                        }
                    })
                    standbyRemote = prepared
                    standbyPlayer = secondary
                    updatePlaybackBufferingIndicator()
                    secondary.setMediaSource(videoSource(
                        prepared, id,
                        includeInPlaybackStats = { counterGate.get() },
                        diagnostic = { event -> debugLog("FFmpeg诊断[B][$id] $event") },
                        diagnosticKey = "B:${System.identityHashCode(secondary)}:$id"
                    ))
                    secondary.prepare()
                    debugLog("无感连播：第二播放器开始准备下一集 index=${index + 1}，独立输出面已创建")
                } catch (problem: Throwable) {
                    android.util.Log.e("HongguoTV-Standby", "备用播放器初始化失败 mode=${FfmpegPlayerRegistry.videoMode} ffmpegReady=${FfmpegRuntimeLoader.isReady}", problem)
                    if (problem is VirtualMachineError || problem is ThreadDeath) throw problem
                    if (secondaryPlayer != null && standbyPlayer !== secondaryPlayer) {
                        FfmpegPlayerRegistry.detach(secondaryPlayer)
                        runCatching { secondaryPlayer?.release() }
                    }
                    if (standbyRemote !== prepared) runCatching { prepared.close() }
                    clearStandbyPlayer("第二播放器初始化异常")
                    standbyFallbackEpisodeId = data.episodes.getOrNull(episodeIndex).orEmpty()
                    debugLogError("无感连播：第二播放器初始化失败，回退单播放器", problem)
                    maybePrefetch(force = true)
                }
            }
        } catch (problem: Throwable) {
            android.util.Log.e("HongguoTV-Standby", "备用集地址准备失败 episodeId=$id", problem)
            if (problem is VirtualMachineError || problem is ThreadDeath) throw problem
            remote?.close()
            closeTransport(http)
            main.post {
                if (standbyHttp === http) standbyHttp = null
                if (standbyByteCounterEnabled === counterGate) {
                    counterGate.set(false)
                    standbyByteCounterEnabled = null
                }
                if (standbyEpisodeId == key) {
                    standbyEpisodeId = ""
                    standbyReady = false
                    updatePlaybackBufferingIndicator()
                    standbyPrepareStartedAt = 0L
                    standbyReadyAt = 0L
                    standbyFirstFrameAt = 0L
                    standbyFallbackEpisodeId = data.episodes.getOrNull(episodeIndex).orEmpty()
                    debugLogError("无感连播：下一集预备失败，回退单播放器", problem)
                    maybePrefetch(force = true)
                }
            }
        }
    }
}
