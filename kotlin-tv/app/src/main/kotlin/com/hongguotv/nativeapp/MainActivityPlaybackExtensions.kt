// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.graphics.drawable.GradientDrawable
import android.view.*
import android.view.inputmethod.InputMethodManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Uri
import android.net.Network
import android.widget.*
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.media3.common.*
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.source.preload.DefaultPreloadManager
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.DecoderMode
import androidx.media3.ui.PlayerView
import com.hongguotv.core.*
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong


    /** 显示中央可点击的播放/暂停按钮，并同步当前播放状态。 */
    internal fun MainActivity.showPlaybackTapFeedback(playing: Boolean) {
        val feedback=playbackTapFeedbackView ?: return
        feedback.show(playing)
    }

    internal fun MainActivity.playEpisode(index: Int,position: Long=0,autoplay: Boolean=true,recovering: Boolean=false,forceRebuild: Boolean=false) {
        updater.interrupt()
        val data=detail ?: return
        val playbackStarted=android.os.SystemClock.elapsedRealtime()
        debugPlaybackStartedAt=playbackStarted; debugPlaybackReadyAt=0L; debugFirstFrameAt=0L
        debugLog("开始播放 index=${index+1} position=$position autoplay=$autoplay recovering=$recovering")
        val selectedIndex=index.coerceIn(0,data.episodes.lastIndex)
        val standbyKey="${data.episodes[selectedIndex]}:${library.maxQuality}"
        val promoteStandby=position==0L && !recovering && !forceRebuild && seamlessPlaybackEnabled &&
            standbyEpisodeId==standbyKey && standbyReady && standbyFirstFrameAt>0L &&
            standbyPlayer?.currentMediaItem?.mediaId==data.episodes[selectedIndex] && standbyRemote!=null
        val reuse=screen=="player" && player!=null && playerView!=null && !recovering && !playError && !forceRebuild
        library.setWatched(data.series.id,false)
        val isStartupPreview=bootAutoPlaybackPending && autoplay && !recovering
        val prefetched=if(promoteStandby) standbyRemote else if(position==0L && !recovering && !forceRebuild) preparedNext.take(standbyKey) else null
        val prefetchedSource=if(prefetched!=null && !promoteStandby) preloadManager?.getMediaSource(videoItem(data.episodes[selectedIndex])) else null
        val claimedHttp=if(promoteStandby) standbyHttp.also { standbyHttp=null } else if(prefetched!=null) prefetchHttp.also { prefetchHttp=null } else null
        // 必须在替换活动播放器前保存旧集进度，不能从尚未播放的备用播放器读取旧集位置。
        saveProgress()
        if(promoteStandby) {
            val oldPlayer=player
            val promoted=standbyPlayer
            playerListener?.let { oldPlayer?.removeListener(it) }
            playerListener=null
            seamlessHandoffInProgress=true
            updatePlaybackBufferingIndicator()
            standbyPromotionAt=android.os.SystemClock.elapsedRealtime()
            standbyVisibleFirstFrameAt=0L
            val handoffStartedAt=standbyPromotionAt
            // 极少数设备可能不回调输出面切换后的首帧；超时恢复提示，避免永久吞掉真实卡顿反馈。
            main.postDelayed({
                if(seamlessHandoffInProgress &&
                    standbyPromotionAt==handoffStartedAt &&
                    standbyVisibleFirstFrameAt==0L
                ) {
                    seamlessHandoffInProgress=false
                    updatePlaybackBufferingIndicator()
                    debugLog("无感连播：接管后 3 秒仍未收到可见首帧，恢复缓冲提示")
                }
            },3000L)
            standbyByteCounterEnabled?.set(true)
            player=promoted
            // 让 PlayerView 在一次 setPlayer 中完成输出面交接，避免先置空造成额外黑屏。
            playerView?.player=promoted
            standbyAttachedAt=android.os.SystemClock.elapsedRealtime()
            // FFmpeg owns its Surface/ANativeWindow bridge. Keep its hidden warm-up
            // output alive until the promoted player has rendered on PlayerView's
            // visible surface. MediaCodec paths retain the original immediate cleanup.
            val keepStandbyOutputForFfmpeg =
                promoted != null && FfmpegPlayerRegistry.currentVideoMode(promoted) == DecoderMode.FFMPEG
            if (keepStandbyOutputForFfmpeg) {
                debugLog("FFmpeg诊断[LIFECYCLE] handoff 保留备用输出面，等待正式 PlayerView 首帧 output=" + standbyOutputView?.let { System.identityHashCode(it) })
            } else {
                standbyOutputView?.let { output -> if(output.parent===root) root.removeView(output) }
                standbyOutputView=null
            }
            FfmpegPlayerRegistry.detach(oldPlayer)
            oldPlayer?.release()
            standbyPlayer=null
            updatePlaybackBufferingIndicator()
            standbyRemote=null
            standbyByteCounterEnabled=null
            standbyEpisodeId=""
            standbyReady=false
            standbyFallbackEpisodeId=""
            standbyJob=null
            debugLog("无感连播：备用播放器接管第 ${selectedIndex+1} 集；READY等待=${if(standbyReadyAt>0L) standbyPromotionAt-standbyReadyAt else -1L}ms；预渲染首帧=${if(standbyFirstFrameAt>0L) standbyPromotionAt-standbyFirstFrameAt else -1L}ms；PlayerView交接=${standbyAttachedAt-standbyPromotionAt}ms")
        } else if(seamlessPlaybackEnabled && standbyEpisodeId.isNotEmpty()) {
            clearStandbyPlayer(if(standbyReady && standbyFirstFrameAt==0L) "备用播放器虽已 READY 但未渲染首帧" else "目标剧集未能由备用播放器接管")
        }
        library.flush(); invalidatePage(); favoriteMonitor.stop(); releaseCatalogViews(); stopDanmaku()
        if(reuse) {
            cancelRecovery(); cancelPrefetch(keepMediaSource=prefetchedSource!=null); tvTools.close()
            episodePanel?.dismiss(); episodePanel=null; speedDialog?.dismiss(); speedDialog=null
            playerListener?.let { player?.removeListener(it) }; playerListener=null
            if(!promoteStandby) { player?.stop(); player?.clearMediaItems() }
            closeActiveVideo(); closeTransport(playbackHttp); playbackHttp=null
            main.removeCallbacks(hideHud); main.removeCallbacks(seekRunnable); pendingSeek=null
        progressUserSeeking=false
        } else releasePlayer(resetOrientation = screen != "player")
        artwork.cancelPage(clearMemory=true)
        playbackHttp=claimedHttp; video=prefetched
        var firstReady=true; var firstFrame=true
        if(!recovering) recovery.reset()
        autoRecovery=false; readySince=0; startPosition=position.coerceAtLeast(0); requestedAutoplay=autoplay
        val skipMark=library.skipMark(data.series.id)
        outroSkipSeconds=skipMark?.outroSeconds ?: 0; outroTriggered=false; ffActive=false
        if(autoplay) sleepStopped=false
        // 手机方向选择仅保留在当前 App 进程：离开播放器仅临时恢复主界面横屏，重新进入播放器继续使用上次选择；重启 App 后默认横屏。
        screen="player"; randomLoadingLabel=null; applyDeviceOrientationPolicy(); episodeIndex=selectedIndex; group=episodeIndex/20
        playbackBytes.set(0L); lastSpeedBytes=0L; lastSpeedAt=android.os.SystemClock.elapsedRealtime(); currentSpeedBytesPerSecond=0L
        activeVideoFormat=null; if(!reuse) activeDecoderName=""
        if(commentPanel!=null) { commentReplyCursors.clear(); commentPanel?.setEpisode(data.series.title,episodeIndex+1); loadOfficialComments(false) }
        if(isStartupPreview) {
            startupPreviewEpisodeId=data.episodes[selectedIndex]
            startupPreviewPlayedMs=0L
            startupPreviewLastTickAt=0L
        } else if(!recovering) {
            // 用户手动选集/正常进入播放时立即恢复原有历史记录逻辑。
            startupPreviewEpisodeId=null
            startupPreviewPlayedMs=0L
            startupPreviewLastTickAt=0L
        }
        panel=false; playError=false; playbackReady=false; quality=""; pausedForLifecycle=!autoplay
        player?.playWhenReady=if(promoteStandby) false else requestedAutoplay
        prefetchAttempts=0; prefetchAttemptAt=0
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if(!reuse) {
            root.removeAllViews(); root.setBackgroundColor(Color.BLACK)
            val view=createPlayerView()
            playerView=view; root.addView(view,FrameLayout.LayoutParams(-1,-1))
            val danmakuLayer=DanmakuView(this).apply {
                isClickable=false
                importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
                elevation=dp(4).toFloat()
                clipToOutline=false
            }
            danmakuView=danmakuLayer
            danmakuLayer.setBlockedFilter { text -> extras.blocks(text) }
            danmakuLayer.applyPrefs(library.danmakuOpacity,library.danmakuFont,library.danmakuSpeed,library.danmakuRows)
            danmakuLayer.setColorMode(danmakuColorMode)
            root.addView(danmakuLayer,FrameLayout.LayoutParams(-1,(resources.displayMetrics.heightPixels*.38f).toInt(),Gravity.TOP).apply { topMargin=dp(12) })
            playbackTapFeedbackView=PlaybackTapFeedbackView(this).apply {
                importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_YES
                elevation=dp(12).toFloat()
                setOnClickListener {
                    if(screen=="player" && isPhoneDevice && commentPanel==null && episodePanel==null && !playError) {
                        // 中央按钮是唯一负责切换播放/暂停的画面点击区域。
                        togglePlayback()
                        showPlaybackTapFeedback(player?.playWhenReady==true)
                    }
                }
            }
            root.addView(playbackTapFeedbackView,FrameLayout.LayoutParams(dp(76),dp(76),Gravity.CENTER))
            // 手机竖屏可用宽度有限，控制栏贴近屏幕两侧；横屏与电视沿用原有留白。
            val phonePortraitHud=isPhoneDevice && heightDp()>widthDp()
            val hudHorizontalInset=if(phonePortraitHud) dp(2) else dp(widthDp()*.05f)
            hud=column().apply { setPadding(hudHorizontalInset,dp(20),hudHorizontalInset,(resources.displayMetrics.heightPixels*.05f).toInt()); background=GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,intArrayOf(Color.TRANSPARENT,Color.argb(240,11,15,21))) }
            playbackTitle=text("",23f).apply { maxLines=1; ellipsize=TextUtils.TruncateAt.END; setTypeface(null,Typeface.BOLD) }; hud.addView(playbackTitle)
            playbackText=text("",15f,muted).apply { setPadding(0,dp(10),0,dp(9)) }; hud.addView(playbackText)
            progressBar=(if(isPhoneDevice) SeekBar(this) else ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal)).apply {
                max=1000
                progressTintList=android.content.res.ColorStateList.valueOf(accent)
                progressBackgroundTintList=android.content.res.ColorStateList.valueOf(surface)
                if(this is SeekBar) {
                    isFocusable=false
                    isFocusableInTouchMode=false
                    setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener {
                        override fun onStartTrackingTouch(seekBar: SeekBar) { progressUserSeeking=true; main.removeCallbacks(seekRunnable) }
                        override fun onProgressChanged(seekBar: SeekBar,progress: Int,fromUser: Boolean) {
                            if(fromUser) {
                                val duration=player?.duration?.coerceAtLeast(0L) ?: 0L
                                if(duration>0L) {
                                    pendingSeek=duration*progress/1000L
                                    playbackText.text="定位播放  ·  " + formatTime(pendingSeek ?: 0L) + " / " + formatTime(duration)
                                }
                            }
                        }
                        override fun onStopTrackingTouch(seekBar: SeekBar) {
                            progressUserSeeking=false
                            val duration=player?.duration?.coerceAtLeast(0L) ?: 0L
                            if(duration>0L) seekTo(duration*seekBar.progress/1000L) else pendingSeek=null
                        }
                    })
                }
            }
            if(isPhoneDevice && heightDp()>widthDp()) progressBar.setPadding(0,progressBar.paddingTop,0,progressBar.paddingBottom)
            hud.addView(progressBar,lp(-1,if(isPhoneDevice) dp(36) else dp(4)))
            hud.addView(text(if(isPhoneDevice) "拖动进度条定位    左右滑动快进/快退    左侧上下调亮度 / 右侧上下调音量    双击播放/暂停" else "确认 播放/暂停    ↑ 上一集    ↓ 下一集    左右 快退/快进    菜单 播放菜单    返回 收起 / 退出",13f,muted).apply { setPadding(0,dp(12),0,0) })
            controls=column().apply { setPadding(0,dp(12),0,0); visibility=View.GONE }; hud.addView(controls)
            root.addView(ScrollView(this).apply {
                isVerticalScrollBarEnabled=false
                clipChildren=false
                clipToPadding=false
                elevation=dp(8).toFloat()
                addView(hud)
            },FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM))
        }
        if(bootAutoPlaybackPending && !reuse) showBootSplash()
        applyClock()
        attachDebugOverlay()
        playbackTitle.text="${data.series.title}  ·  第 ${episodeIndex+1} 集 / 共 ${data.episodes.size} 集"
        playbackText.text="正在获取播放地址…"; progressBar.progress=0; progressBar.visibility=View.VISIBLE; hidePlaybackOverlay()
        controls.removeAllViews(); controls.visibility=View.GONE; transportPlay=null; sleepButton=null
        syncMediaSession()
        val requestedId=data.episodes[episodeIndex]; val maxQuality=library.maxQuality; val ticket=generation
        val attachPrepared: (RemoteVideo) -> Unit = { remote ->
            var attachStage = "收到播放资源"
            try {
            debugLog("播放资源获取成功 quality=${remote.info.quality} total=${remote.total}；当前解码模式=${FfmpegPlayerRegistry.videoMode}；FFmpeg ready=${FfmpegRuntimeLoader.isReady}")
            video=remote; quality=remote.info.quality
            attachStage = "创建播放器与解码器管线"
            val p=player ?: run {
                attachStage = "创建解码器 Renderer"
                val ffmpegSetup=FfmpegPlayerRegistry.create(this)
                debugLog("解码管线创建完成 mode=${FfmpegPlayerRegistry.videoMode} ffmpegReady=${FfmpegRuntimeLoader.isReady} manager=${ffmpegSetup.decoderManager!=null}")
                val renderers=ffmpegSetup.renderersFactory
                // DefaultPreloadManager.build() 会为预加载器创建自己的内部 ExoPlayer。
                // NextRenderersFactory/DecoderRendererController 每个实例只允许创建一组 Renderers，
                // 因此不能把主播放器的 FFmpeg factory 交给 PreloadManager 后再复用。
                // 预加载器使用独立的默认 MediaCodec factory；主播放器单独使用 FFmpeg factory。
                val preloadBuilder=DefaultPreloadManager.Builder(this) { _: Int ->
                    DefaultPreloadManager.PreloadStatus.specifiedRangeLoaded(3000)
                }.setLoadControl(buildPlaybackLoadControl())
                attachStage = "创建预加载管理器"
                preloadManager=preloadBuilder.build()
                debugLog("预加载管理器创建完成；使用独立默认 RendererFactory；主播放器 FFmpeg factory 尚未消费")
                attachStage = "创建 ExoPlayer"
                ExoPlayer.Builder(this)
                    .setRenderersFactory(renderers)
                    .setLoadControl(buildPlaybackLoadControl())
                    .build().also {
                    attachStage = "附加解码器管理器"
                    FfmpegPlayerRegistry.attach(it,ffmpegSetup)
                    player=it; playerView?.player=it
                    it.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),true)
                    it.setHandleAudioBecomingNoisy(true)
                    it.addAnalyticsListener(object: AnalyticsListener {
                        override fun onVideoDecoderInitialized(
                            eventTime: AnalyticsListener.EventTime,
                            decoderName: String,
                            initializedTimestampMs: Long,
                            initializationDurationMs: Long
                        ) {
                            val kind=when {
                                decoderName.contains("ffmpeg",true) -> "software/FFmpeg"
                                decoderName.contains("google",true) || decoderName.contains("c2.android",true) -> "system software"
                                else -> "platform decoder (hardware status depends on device implementation)"
                            }
                            android.util.Log.i("HongguoTV","Video decoder initialized: $decoderName kind=$kind init=${initializationDurationMs}ms mode=${FfmpegPlayerRegistry.videoMode} managerMode=${FfmpegPlayerRegistry.currentVideoMode(it)}")
                            debugLog("解码器初始化完成 name=$decoderName kind=$kind init=${initializationDurationMs}ms requestedMode=${FfmpegPlayerRegistry.videoMode} managerMode=${FfmpegPlayerRegistry.currentVideoMode(it)}")
                            if(player===it) {
                                activeDecoderName=decoderName
                                main.post { if(player===it) refreshVideoInfoOverlay() }
                            }
                        }
                    })
                }
            }
            p.setPlaybackSpeed(library.playbackSpeed)
            val listener=object: Player.Listener {
                private fun active()=player===p && generation==ticket && p.currentMediaItem?.mediaId==requestedId
                override fun onPlaybackStateChanged(state: Int) {
                    if(!active()) return
                    val stateLabel=when(state) {
                        Player.STATE_IDLE -> "IDLE"
                        Player.STATE_BUFFERING -> "BUFFERING"
                        Player.STATE_READY -> "READY"
                        Player.STATE_ENDED -> "ENDED"
                        else -> "UNKNOWN($state)"
                    }
                    debugLog("播放器状态变化：$stateLabel；位置=${p.currentPosition}ms；缓冲=${p.bufferedPosition}ms；集号=${episodeIndex+1}")
                    if(state==Player.STATE_READY) {
                        if(!p.currentTracks.isTypeSelected(C.TRACK_TYPE_VIDEO)) {
                            playerFailure(PlaybackException("资源没有可解码的视频轨道",null,PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED))
                            return
                        }
                        playbackReady=true; incompatibleSkips=0
                        if(firstReady) preloadManager?.remove(videoItem(requestedId))
                        if(firstReady) {
                            firstReady=false; readySince=android.os.SystemClock.elapsedRealtime()
                            debugPlaybackReadyAt=android.os.SystemClock.elapsedRealtime()
                            android.util.Log.i("HongguoTV","Playback ready episode=${episodeIndex+1} preloaded=${prefetched!=null} reused=$reuse elapsedMs=${readySince-playbackStarted}")
                            debugLog("Playback READY elapsedMs=${readySince-playbackStarted} quality=$quality")
                            startDanmaku()
                            if(seamlessPlaybackEnabled && episodeIndex<data.episodes.lastIndex) prepareStandbyEpisode(episodeIndex+1)
                        }
                        updateDebugOverlay()
                        if(hud.visibility==View.VISIBLE) updatePlaybackText()
                    }
                    syncMediaSession()
                    if(state==Player.STATE_ENDED) {
                        saveProgress(true)
                        if(sleepTimer.episodeEnded()) stopForSleep()
                        else if(foreground && requestedAutoplay && !pausedForLifecycle && library.autoNext && episodeIndex<data.episodes.lastIndex) playEpisode(episodeIndex+1)
                        else if(foreground && requestedAutoplay && !pausedForLifecycle && continueToNextSeason()) { /* season jump in progress */ }
                        else { requestedAutoplay=false; p.pause(); showEndedPanel() }
                    }
                }
                override fun onRenderedFirstFrame() {
                    if(active() && firstFrame) {
                        firstFrame=false
                        hideBootSplash()
                        markStartupRecommendationPlaying()
                        // 不在首帧后立即保存：开机推荐必须累计真实播放 30 秒，才允许写入观看历史。
                        debugFirstFrameAt=android.os.SystemClock.elapsedRealtime()
                        android.util.Log.i("HongguoTV","Playback first frame episode=${episodeIndex+1} preloaded=${prefetched!=null} reused=$reuse elapsedMs=${debugFirstFrameAt-playbackStarted}")
                        debugLog("Playback first frame elapsedMs=${debugFirstFrameAt-playbackStarted}")
                    }
                }
                override fun onIsPlayingChanged(playing: Boolean) {
                    if(active()) {
                        debugLog("播放器播放状态：isPlaying=$playing；playWhenReady=${p.playWhenReady}；位置=${p.currentPosition}ms")
                        if(playing && playbackReady) markStartupRecommendationPlaying()
                        danmakuView?.setPlaybackActive(playing)
                        playbackTapFeedbackView?.takeIf { it.visibility==View.VISIBLE }?.setPlaying(p.playWhenReady)
                        if(hud.visibility==View.VISIBLE) showHud()
                        syncMediaSession()
                    }
                }
                override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                    if(active()) {
                        debugLog("播放器播放意图变化：playWhenReady=$playWhenReady；原因代码=$reason；requestedAutoplay=$requestedAutoplay；pausedForLifecycle=$pausedForLifecycle；页面=$screen")
                        playbackTapFeedbackView?.takeIf { it.visibility==View.VISIBLE }?.setPlaying(playWhenReady)
                    }
                }
                override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) {
                    if(active()) {
                        debugLog("播放器播放抑制状态变化：原因代码=$playbackSuppressionReason；playWhenReady=${p.playWhenReady}；isPlaying=${p.isPlaying}")
                    }
                }
                override fun onVideoSizeChanged(videoSize: VideoSize) {
                    if(active()) {
                        debugLog("视频画面尺寸变化：${videoSize.width}x${videoSize.height}；像素比例=${videoSize.pixelWidthHeightRatio}")
                        playerView?.resizeMode = activeFrameResizeMode()
                        android.util.Log.i(
                            "HongguoTV",
                            "Video size ${videoSize.width}x${videoSize.height} pixelRatio=${videoSize.pixelWidthHeightRatio}"
                        )
                        refreshVideoInfoOverlay()
                        if(hud.visibility==View.VISIBLE) updatePlaybackText()
                    }
                }
                override fun onTracksChanged(tracks: Tracks) {
                    if(active()) {
                        refreshVideoInfoOverlay()
                        if(hud.visibility==View.VISIBLE) updatePlaybackText()
                    }
                }
                override fun onPlaybackParametersChanged(parameters: PlaybackParameters) { if(active()) syncMediaSession() }
                override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo,newPosition: Player.PositionInfo,reason: Int) { if(active()) { syncMediaSession(); danmakuView?.seekTo(newPosition.positionMs) } }
                override fun onPlayerError(error: PlaybackException) {
                    if(active()) {
                        debugLog("Player error code=${error.errorCode} name=${error.errorCodeName} message=${error.message}")
                        playerFailure(error)
                    }
                }
            }
            playerListener=listener; p.addListener(listener)
            attachStage = "创建媒体资源"
            val source=prefetchedSource ?: videoSource(remote,requestedId)
            debugLog("媒体资源创建完成 episodeId=$requestedId prefetched=${prefetchedSource!=null}")
            val introSkip=if(position<=0 && (skipMark?.introSeconds ?: 0)>0) skipMark!!.introSeconds*1000L else position.coerceAtLeast(0)
            attachStage = "绑定媒体资源并启动解码"
            if(promoteStandby && p.currentMediaItem?.mediaId==requestedId) {
                p.volume=1f
                // 备用播放器已在第 0 毫秒预解码并渲染过首帧；无片头跳过时不要再 seekTo(0)。
                // 对 READY 状态的 ExoPlayer 执行一次多余 seek 可能触发解码器 flush / 重新缓冲，
                // 表现为接管后约 1 秒画面短暂停顿。只有确实需要跳过片头或位置异常时才定位。
                if (introSkip > 0L) {
                    p.seekTo(introSkip)
                } else if (p.currentPosition > 250L) {
                    p.seekTo(0L)
                }
                p.playWhenReady=foreground && requestedAutoplay && !pausedForLifecycle && episodePanel==null
                if(p.playbackState==Player.STATE_READY) listener.onPlaybackStateChanged(p.playbackState)
            } else {
                p.setMediaSource(source); p.seekTo(introSkip); p.prepare(); p.playWhenReady=foreground && requestedAutoplay && !pausedForLifecycle && episodePanel==null
            }
            if(introSkip>0 && position<=0) Toast.makeText(this,"已跳过片头 ${skipMark!!.introSeconds} 秒",Toast.LENGTH_SHORT).show()
            debugLog("播放器 prepare 已提交 stage=完成 decoderMode=${FfmpegPlayerRegistry.videoMode}")
            } catch (problem: Throwable) {
                android.util.Log.e("HongguoTV-Playback", "播放初始化失败 stage=$attachStage mode=${FfmpegPlayerRegistry.videoMode} ffmpegReady=${FfmpegRuntimeLoader.isReady}", problem)
                debugLogError("播放初始化失败 stage=$attachStage", problem)
                runCatching {
                    playerListener?.let { player?.removeListener(it) }
                    playerListener = null
                    playerView?.player = null
                    preloadManager?.release()
                    preloadManager = null
                    FfmpegPlayerRegistry.detach(player)
                    player?.release()
                }.onFailure { android.util.Log.e("HongguoTV-Playback", "失败播放器释放异常", it) }
                player = null
                video = null
                runCatching { remote.close() }
                if (problem is VirtualMachineError || problem is ThreadDeath) throw problem
                runCatching { playerFailure(problem) }
                    .onFailure { android.util.Log.e("HongguoTV-Playback", "播放失败界面处理再次异常", it) }
            }
        }
        if(promoteStandby) {
            // 备用 RemoteVideo 与播放器均已准备完成，直接在主线程接管，不再绕进 IO 工作队列。
            cancelWork()
            attachPrepared(prefetched!!)
        } else {
            work({ prefetched ?: RemoteVideo(repository.http,repository.stream(requestedId,maxQuality)).prepare() }, attachPrepared, { problem -> playerFailure(problem) })
        }
    }


