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
import android.net.NetworkCapabilities
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
import androidx.media3.ui.PlayerView
import com.hongguotv.core.*
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

    internal fun MainActivity.updateDebugSettingLabel() {
        debugSettingButton?.text=debugSettingLabel()
    }

    internal fun MainActivity.startDebugLogging(anchor: View?) {
        if(!hasBackupStoragePermission()) {
            pendingBackupAction=2; pendingBackupAnchor=anchor
            ensureBackupStoragePermission()
            return
        }
        if(debugFile!=null && debugEnabled) {
            attachDebugOverlay()
            updateDebugSettingLabel()
            return
        }
        debugLogExecutor.execute {
            val result=runCatching {
                val folder=backupFolder()
                if(!folder.exists() && !folder.mkdirs()) error("无法创建目录：${folder.absolutePath}")
                if(!folder.isDirectory || !folder.canWrite()) error("目录不可写：${folder.absolutePath}")
                val stamp=java.text.SimpleDateFormat("yyyyMMdd-HHmmss",Locale.US).format(java.util.Date())
                val file=java.io.File(folder,"hongguotv-debug-$stamp.log")
                file.appendText("===== 红果TV 调试日志开始 ${java.util.Date()} =====\n",Charsets.UTF_8)
                file
            }
            main.post {
                if(isDestroyed || resettingAllData) return@post
                result.onSuccess { file ->
                    debugFile=file
                    getSharedPreferences("debug-mode-v1",Context.MODE_PRIVATE).edit().putBoolean("enabled",true).apply()
                    updateDebugSettingLabel()
                    debugLog("调试模式已开启，日志文件：${file.absolutePath}")
                    attachDebugOverlay()
                    Toast.makeText(this,"调试模式已开启，日志保存在 HongGuoTV 文件夹",Toast.LENGTH_LONG).show()
                }.onFailure { error ->
                    getSharedPreferences("debug-mode-v1",Context.MODE_PRIVATE).edit().putBoolean("enabled",false).apply()
                    updateDebugSettingLabel()
                    tvTools.info("调试日志无法保存","无法创建调试日志：${error.message ?: "未知错误"}",anchor)
                }
            }
        }
    }

    internal fun MainActivity.stopDebugLogging() {
        debugLog("调试模式已关闭")
        getSharedPreferences("debug-mode-v1",Context.MODE_PRIVATE).edit().putBoolean("enabled",false).apply()
        debugTick?.let(main::removeCallbacks); debugTick=null
        debugOverlayText?.let { if(it.parent===root) root.removeView(it) }
        debugOverlayText=null
        updateDebugSettingLabel()
        Toast.makeText(this,"调试模式已关闭",Toast.LENGTH_SHORT).show()
    }

    /** Quietly stop debug writes before the all-data reset removes their destination. */
    internal fun MainActivity.stopDebugLoggingForReset() {
        debugTick?.let(main::removeCallbacks)
        debugTick = null
        debugOverlayText?.let { if (it.parent === root) root.removeView(it) }
        debugOverlayText = null
        getSharedPreferences("debug-mode-v1", Context.MODE_PRIVATE)
            .edit().putBoolean("enabled", false).commit()
        debugFile = null
    }

    internal fun MainActivity.debugLog(message: String) {
        if(!debugEnabled) return
        val target=debugFile ?: return
        val line="${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS",Locale.US).format(java.util.Date())}  $message\n"
        debugLogExecutor.execute {
            runCatching {
                synchronized(debugLock) {
                    var file=debugFile ?: target
                    if(file.length()>8L*1024*1024) {
                        val stamp=java.text.SimpleDateFormat("yyyyMMdd-HHmmss",Locale.US).format(java.util.Date())
                        file=java.io.File(file.parentFile,"hongguotv-debug-$stamp.log")
                        debugFile=file
                        file.appendText("===== 调试日志分卷 =====\n",Charsets.UTF_8)
                    }
                    file.appendText(line,Charsets.UTF_8)
                }
            }
        }
    }

    internal fun MainActivity.attachDebugOverlay() {
        if(!debugEnabled || screen!="player" || root.childCount==0) return
        if(debugOverlayText?.parent!==root) {
            debugOverlayText?.let { (it.parent as? android.view.ViewGroup)?.removeView(it) }
            val mobile=isPhoneDevice
            val view=text("",if(mobile) 8.5f else 10f,Color.WHITE).apply {
                typeface=Typeface.MONOSPACE
                includeFontPadding=false
                maxLines=22
                ellipsize=TextUtils.TruncateAt.END
                setPadding(dp(8),dp(6),dp(8),dp(6))
                background=GradientDrawable().apply {
                    setColor(Color.argb(205,0,0,0))
                    cornerRadius=dp(5).toFloat()
                }
                isFocusable=false; isClickable=false
                importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
                elevation=dp(30).toFloat()
            }
            val maxWidth=(resources.displayMetrics.widthPixels*(if(mobile) 0.64f else 0.48f)).toInt()
            root.addView(view,FrameLayout.LayoutParams(maxWidth.coerceAtLeast(dp(220)),-2,Gravity.TOP or Gravity.START).apply {
                leftMargin=dp(10); topMargin=dp(10)
            })
            debugOverlayText=view
        }
        updateDebugOverlay()
        startDebugTick()
    }

    internal fun MainActivity.debugSnapshot(): String {
        val p=player
        val data=detail
        val format=selectedVideoFormat()
        val tracks=p?.currentTracks?.groups.orEmpty()
        val selected=tracks.flatMap { group ->
            (0 until group.length).filter { group.isTrackSelected(it) }.map { group.type to group.mediaTrackGroup.getFormat(it) }
        }
        val videoTrack=selected.firstOrNull { it.first==C.TRACK_TYPE_VIDEO }?.second ?: format
        val audioTrack=selected.firstOrNull { it.first==C.TRACK_TYPE_AUDIO }?.second
        val state=when(p?.playbackState) {
            Player.STATE_IDLE -> "IDLE"
            Player.STATE_BUFFERING -> "BUFFERING"
            Player.STATE_READY -> "READY"
            Player.STATE_ENDED -> "ENDED"
            else -> "UNKNOWN"
        }
        val url=video?.info?.url.orEmpty()
        val parsedUrl=runCatching { Uri.parse(url) }.getOrNull()
        val host=parsedUrl?.host.orEmpty()
        val sourcePath=parsedUrl?.path.orEmpty()
        val network=connectivity.activeNetwork?.let { connectivity.getNetworkCapabilities(it) }
        val networkType=when {
            network?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)==true -> "Wi-Fi"
            network?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)==true -> "Ethernet"
            network?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)==true -> "Cellular"
            else -> "Unknown"
        }
        val position=p?.currentPosition?.coerceAtLeast(0) ?: 0L
        val duration=p?.duration?.coerceAtLeast(0) ?: 0L
        val buffered=p?.bufferedPosition?.coerceAtLeast(0) ?: 0L
        val stats=video?.stats()?.toString() ?: "N/A"
        val version=runCatching { packageManager.getPackageInfo(packageName,0).versionName.orEmpty() }.getOrDefault("?")
        val size=videoTrack?.let { "${it.width}x${it.height}" } ?: "N/A"
        val codec=videoTrack?.let { "${it.sampleMimeType.orEmpty()} ${it.codecs.orEmpty()}" }?.trim().orEmpty().ifBlank { "N/A" }
        val fps=videoTrack?.frameRate?.takeIf { it>0 }?.let { "%.2f".format(Locale.US,it) } ?: "N/A"
        val audio=audioTrack?.let { "${it.sampleMimeType.orEmpty()} ${it.sampleRate}Hz/${it.channelCount}ch" }?.trim() ?: "N/A"
        val byteRate=debugCurrentBytesPerSecond
        return listOf(
            "DEBUG  红果TV $version  Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})",
            "设备：${Build.MANUFACTURER} ${Build.MODEL}  屏幕：${widthDp().toInt()}x${heightDp().toInt()}dp",
            "内容：${data?.series?.title.orEmpty()}  第 ${episodeIndex+1} 集 / ${data?.episodes?.size ?: 0}",
            "ID：${p?.currentMediaItem?.mediaId ?: data?.episodes?.getOrNull(episodeIndex).orEmpty()}",
            "状态：$state  isPlaying=${p?.isPlaying}  playWhenReady=${p?.playWhenReady}  ready=$playbackReady",
            "进度：${formatTime(position)} / ${formatTime(duration)}  缓冲至 ${formatTime(buffered)}（+${formatTime((buffered-position).coerceAtLeast(0))}）",
            "清晰度：${video?.info?.quality ?: quality}  画面：$size  FPS：$fps",
            "编码：$codec  解码器：${activeDecoderName.ifBlank { "N/A" }}",
            "音频：$audio  MIME：${video?.info?.mimeType ?: "N/A"}",
            "倍速：${p?.playbackParameters?.speed ?: library.playbackSpeed}  网络：$networkType",
            "源站：${host.ifBlank { "N/A" }}${sourcePath.take(70)}",
            "读取：${playbackBytes.get()} bytes  读取速率：${byteRate/1024} KB/s  资源总长：${video?.total ?: 0} bytes",
            "Range缓存：$stats",
            "首帧耗时：${if(debugFirstFrameAt>0) debugFirstFrameAt-debugPlaybackStartedAt else -1}ms  READY耗时：${if(debugPlaybackReadyAt>0) debugPlaybackReadyAt-debugPlaybackStartedAt else -1}ms",
            "播放错误：${if(playError) "是" else "否"}  自动恢复：$autoRecovery  资源重试：${recovery.attempts}/3"
        ).joinToString("\n")
    }

    internal fun MainActivity.updateDebugOverlay() {
        if(!debugEnabled || screen!="player") return
        debugOverlayText?.text=debugSnapshot()
    }

    internal fun MainActivity.startDebugTick() {
        if(debugTick!=null || !debugEnabled) return
        debugLastBytes=playbackBytes.get()
        debugLastSampleAt=android.os.SystemClock.elapsedRealtime()
        val tick=object: Runnable {
            override fun run() {
                if(!debugEnabled || screen!="player" || debugOverlayText?.parent!==root) { debugTick=null; return }
                val now=android.os.SystemClock.elapsedRealtime()
                val bytes=playbackBytes.get()
                val elapsed=(now-debugLastSampleAt).coerceAtLeast(1)
                debugCurrentBytesPerSecond=((bytes-debugLastBytes).coerceAtLeast(0)*1000L/elapsed)
                debugLastBytes=bytes; debugLastSampleAt=now
                val snapshot=debugSnapshot()
                debugOverlayText?.text=snapshot
                debugLog(snapshot.replace("\n"," | "))
                main.postDelayed(this,1000L)
            }
        }
        debugTick=tick
        main.post(tick)
    }

internal fun MainActivity.debugSettingLabel()=if(debugEnabled) "关闭调试模式" else "打开调试模式"
