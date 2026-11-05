// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.net.NetworkCapabilities
import android.view.*
import android.view.View
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import com.hongguotv.core.*

internal fun MainActivity.closeActiveVideo() {
        val current=video ?: return
        android.util.Log.i(
            "HongguoTV",
            "Playback range diagnostics quality=${current.info.quality} size=${current.total} bytes ${current.stats()}"
        )
        debugLog("播放资源关闭 quality=${current.info.quality} size=${current.total} stats=${current.stats()}")
        current.close()
        video=null
    }

    internal fun MainActivity.releasePlayer(resetOrientation: Boolean = true) {
        commentGeneration++
        commentListScope?.cancel(); commentListScope=null
        commentPanel?.let { if(it.parent===root) root.removeView(it) }
        commentPanel=null; commentAnchor=null; commentReplyCursors.clear()
        hideBootSplash()
        cancelRecovery(); cancelPrefetch(); clearStandbyPlayer("退出播放器"); preloadManager?.release(); preloadManager=null
        mediaSession.deactivate(); tvTools.close()
        ffActive=false; outroSkipSeconds=0; outroTriggered=false
        val wasForeground=foreground; foreground=false
        val oldPanel=episodePanel; episodePanel=null; oldPanel?.dismiss(); foreground=wasForeground
        speedDialog?.dismiss(); speedDialog=null
        main.removeCallbacks(hideHud); main.removeCallbacks(seekRunnable); main.removeCallbacks(singleTapPlayback); pendingSeek=null
        progressUserSeeking=false; lastTapAt=0L
        playerListener?.let { player?.removeListener(it) }; playerListener=null
        debugTick?.let(main::removeCallbacks); debugTick=null
        debugOverlayText?.let { if(it.parent===root) root.removeView(it) }; debugOverlayText=null
        videoInfoView?.let { root.removeView(it) }; videoInfoView=null
        networkSpeedView?.let { root.removeView(it) }; networkSpeedView=null
        activeVideoFormat=null; activeDecoderName=""
        closeActiveVideo(); playerView?.player=null; playerView=null; player?.release(); player=null; playbackReady=false; transportPlay=null; sleepButton=null
        stopDanmaku(); danmakuView=null
        closeTransport(playbackHttp); playbackHttp=null
        phonePlayerOriginalWindowBrightness?.let { originalBrightness ->
            val attributes = window.attributes
            attributes.screenBrightness = originalBrightness
            window.attributes = attributes
            phonePlayerOriginalWindowBrightness = null
        }
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if(resetOrientation) {
            phonePlaybackOrientation=android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            requestedOrientation=android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
    }

/** 下一集预加载、取消和独立网络传输资源回收。 */
internal fun MainActivity.closeTransport(http: okhttp3.OkHttpClient?) {
        if(http==null) return
        // Closing an idle TLS connection can write close_notify; keep it off the UI thread.
        networkCleanup.execute { http.dispatcher.cancelAll(); http.connectionPool.evictAll(); http.dispatcher.executorService.shutdown() }
    }

internal fun MainActivity.cancelPrefetch(keepMediaSource: Boolean=false) {
        if(!keepMediaSource) preloadManager?.reset()
        prefetchScope?.cancel(); prefetchScope=null
        preparedNext.clear(); prefetchJob?.cancel(true); prefetchJob=null
        closeTransport(prefetchHttp); prefetchHttp=null; prefetchedFor=""
    }

internal fun MainActivity.maybePrefetch(force: Boolean=false) {
        val p=player ?: return; val data=detail ?: return
        if(!foreground || !p.isPlaying || !library.autoNext || playError || sleepStopped || episodeIndex>=data.episodes.lastIndex) return
        val currentEpisodeId=data.episodes.getOrNull(episodeIndex).orEmpty()
        val expectedStandbyKey="${data.episodes[episodeIndex+1]}:${library.maxQuality}"
        val standbyHasRendered=standbyReady && standbyFirstFrameAt>0L && standbyEpisodeId==expectedStandbyKey
        if(seamlessPlaybackEnabled && !force && standbyFallbackEpisodeId!=currentEpisodeId) {
            if(standbyHasRendered) return
            if(!PrefetchPolicy.nearEnd(p.currentPosition,p.duration,p.playbackParameters.speed)) return
            val standbyPending=standbyEpisodeId==expectedStandbyKey
            val standbyAge=if(standbyPrepareStartedAt>0L) android.os.SystemClock.elapsedRealtime()-standbyPrepareStartedAt else Long.MAX_VALUE
            // 短剧可能全程都处于 30 秒窗口；给备用解析 / 解码至少 10 秒机会，避免刚启动就被兜底逻辑取消。
            if(standbyPending && standbyAge<10_000L) return
            // 临近片尾仍未真正渲染备用首帧，不能只凭 READY 赌切集；主动释放备用资源并启用旧预加载兜底。
            standbyFallbackEpisodeId=currentEpisodeId
            clearStandbyPlayer("临近片尾备用首帧尚未就绪，切换单播放器预加载兜底")
            debugLog("无缝接续：下一集未完成首帧预备，已启用单播放器兜底")
        }
        if(!PrefetchPolicy.nearEnd(p.currentPosition,p.duration,p.playbackParameters.speed)) return
        val id=data.episodes[episodeIndex+1]; val quality=library.maxQuality; val key="$id:$quality"
        if(preparedNext.isFresh(key) || prefetchJob?.isDone==false) return
        val now=android.os.SystemClock.elapsedRealtime()
        if(readySince==0L || now-readySince<3000 || p.bufferedPosition-p.currentPosition<5000) return
        if(prefetchAttempts>=3 || (prefetchAttempts>0 && now-prefetchAttemptAt<15_000) || connectivity.isActiveNetworkMetered || !networkAvailable()) return
        cancelPrefetch(); prefetchedFor=key; prefetchAttemptAt=now; prefetchAttempts++
        val ticket=preparedNext.begin(key)
        val playbackGeneration=generation
        // 记住播放身份的匿名 Cookie 罐必须继承，否则预加载与正式播放不是同一个会话。
        // Isolate the speculative client's dispatcher and pool. OkHttp newBuilder() otherwise
        // shares both with repository.http, and closeTransport() would shut down the app-wide
        // dispatcher when a prefetch is cancelled. CookieJar/interceptors remain inherited.
        val http=repository.http.newBuilder()
            .dispatcher(okhttp3.Dispatcher())
            .connectionPool(okhttp3.ConnectionPool())
            .connectTimeout(8,java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(15,java.util.concurrent.TimeUnit.SECONDS)
            .callTimeout(20,java.util.concurrent.TimeUnit.SECONDS)
            .build()
        val source=ContentRepository(http); prefetchHttp=http
        val scope=RequestScope(); prefetchScope=scope
        prefetchJob=prefetchWorker.submit {
            var remote: RemoteVideo?=null
            try {
                val prepared=scope.run {
                    RemoteVideo(http,source.stream(id,quality)).also { remote=it; it.warm() }
                }
                if(preparedNext.complete(ticket,key,prepared)) main.post {
                    if(foreground && generation==playbackGeneration && player===p && prefetchedFor==key && preparedNext.isFresh(key)) {
                        preloadManager?.apply {
                            add(videoSource(prepared,id),episodeIndex+1)
                            setCurrentPlayingIndex(episodeIndex)
                            invalidate()
                        }
                        android.util.Log.i("HongguoTV","Next episode prepared; Media3 sample preload started")
                    }
                }
            } catch(_: Exception) { remote?.close() }
        }
    }

internal fun MainActivity.saveProgress(completed: Boolean=false) {
        val p=player ?: return; val data=detail ?: return; if(!playbackReady || episodeIndex !in data.episodes.indices) return
        val episodeCompleted=completed || p.playbackState==Player.STATE_ENDED
        // 开机自动推荐视频必须实际播放满 30 秒才写入历史；暂停/缓冲不计时。
        // 只限制启动时自动播放的这一集，手动正常播放仍按原逻辑记录。
        if(startupPreviewEpisodeId==data.episodes[episodeIndex] && startupPreviewPlayedMs<30_000L) return
        val position=p.currentPosition.coerceAtLeast(0)
        val duration=p.duration.coerceAtLeast(0)
        val updated=System.currentTimeMillis()
        library.save(WatchProgress(data.series,data.episodes[episodeIndex],episodeIndex,position,duration,episodeCompleted,updated))
        runCatching {
            cloudAccount.upload(
                CloudProgress(data.series,data.episodes[episodeIndex],episodeIndex,position,duration,updated,data.episodes.size),
                data.episodes.size,
                force=episodeCompleted
            )
        }
        if(startupPreviewEpisodeId==data.episodes[episodeIndex]) {
            startupPreviewEpisodeId=null
            startupPreviewPlayedMs=0L
            startupPreviewLastTickAt=0L
        }
    }

internal fun MainActivity.hasValidatedInternet(): Boolean {
        val capabilities=connectivity.getNetworkCapabilities(connectivity.activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    internal fun MainActivity.cancelRecovery() { main.removeCallbacks(retryRunnable); retryPending=false; autoRecovery=false }

    internal fun MainActivity.scheduleRecovery() {
        if(!foreground || !playError || !autoRecovery || retryPending) return
        if(!recovery.remaining) { autoRecovery=false; playbackText.text="已自动重试 3 次，仍无法播放。可手动重试或切换清晰度。"; return }
        if(!networkAvailable()) { playbackText.text="网络已断开，联网后自动续播 · 第 ${episodeIndex+1} 集 ${formatTime(retryPosition)}"; return }
        val delay=recovery.delayMillis ?: return
        playbackText.text="${if(retryAutoplay) "准备恢复播放" else "准备恢复暂停位置"} · ${delay/1000f} 秒后重试 ${recovery.attempts+1}/3 · ${formatTime(retryPosition)}"
        retryPending=true; main.postDelayed(retryRunnable,delay)
    }

    internal fun MainActivity.failStartupRecommendation() {
        main.removeCallbacks(startupRecommendationTimeout)
        startupRecommendationExpired=true
        startupAutoPlaybackActive=false
        bootAutoPlaybackPending=false
        startupPreviewEpisodeId=null
        startupPreviewPlayedMs=0L
        startupPreviewLastTickAt=0L
        hideBootSplash()
        cancelRecovery()
        cancelPrefetch()
        if(screen=="player") releasePlayer()
        showCatalog(load=true)
    }

internal fun MainActivity.playerFailure(problem: Throwable) {
        debugLog("播放器故障 type=${problem.javaClass.simpleName} message=${problem.message}")
        debugLogError("播放器故障",problem)
        val startupFailure=startupAutoPlaybackActive
        retryPosition=currentPosition().coerceAtLeast(0); retryAutoplay=requestedAutoplay && !sleepStopped
        saveProgress(); hideBootSplash(); bootAutoPlaybackPending=false; cancelRecovery(); cancelPrefetch(); clearStandbyPlayer("当前集播放失败")
        if(startupFailure) {
            failStartupRecommendation()
            android.util.Log.i("HongguoTV","Startup recommendation playback failed; returned to home: ${problem.message}")
            return
        }
        playError=true; player?.pause(); progressBar.visibility=View.GONE; hud.visibility=View.VISIBLE; controls.removeAllViews(); controls.visibility=View.VISIBLE; panel=true
        val incompatible=problem is UnplayableVideoException
        val unsupportedFormat=problem is UnsupportedVideoFormatException
        val decoding=problem is PlaybackException && problem.errorCode in 3000..4999
        val retryable=!incompatible && !unsupportedFormat && !decoding && (if(problem is PlaybackException) problem.errorCode in 2000..2999 || problem.errorCode==PlaybackException.ERROR_CODE_TIMEOUT else problem is java.io.IOException)
        playbackText.text=when {
            unsupportedFormat -> "当前片源格式暂不支持，现有播放链路需要 MP4 资源。"
            incompatible -> "本集片源暂不兼容：平台未提供电视可解码的清晰度，可播放下一集。"
            decoding -> "电视无法解码当前视频，可尝试更低清晰度。"
            else -> "播放失败，可重试或切换清晰度。"
        }
        val actions=row(); controls.addView(actions)
        if(incompatible || unsupportedFormat) {
            val hasNext=episodeIndex<detail?.episodes.orEmpty().lastIndex
            if(incompatible && hasNext && extras.autoSkipUnplayable && incompatibleSkips<5) {
                // 整批集数可能都是不可解码片源：最多连跳 5 集，避免无限空转
                incompatibleSkips++
                playbackText.text="本集片源暂不兼容，自动跳过 · 已跳过 $incompatibleSkips 集"
                addButton(actions,"返回选集") { returnToDetail(true) }
                main.postDelayed({ if(foreground && screen=="player" && playError) playEpisode(episodeIndex+1) },1200)
            } else {
                if(hasNext) addButton(actions,"播放下一集") { playEpisode(episodeIndex+1) }.requestFocus()
                val back=addButton(actions,"返回选集") { returnToDetail(true) }
                if(!hasNext) back.requestFocus()
            }
        } else {
            addButton(actions,"立即重试") { playEpisode(episodeIndex,retryPosition,retryAutoplay) }.requestFocus()
            addButton(actions,"尝试 720P") { library.maxQuality=720; playEpisode(episodeIndex,retryPosition,retryAutoplay) }
            addButton(actions,"返回选集") { returnToDetail(true) }
        }
        if(retryable && !sleepStopped) {
            addButton(controls,"停止自动重试") { cancelRecovery(); playbackText.text="自动重试已停止，观看位置已保留。" }
            autoRecovery=true; scheduleRecovery()
        }
        android.util.Log.e("HongguoTV","Playback failure: ${playbackFailureDetails(problem)}",problem)
    }
