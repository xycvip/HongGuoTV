// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import android.graphics.Color
import android.view.SurfaceView
import android.view.TextureView
import android.widget.FrameLayout
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import com.hongguotv.core.RemoteVideo

internal fun MainActivity.buildPlaybackLoadControl(): DefaultLoadControl {
        val manager=getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val lowRam=manager.isLowRamDevice || manager.memoryClass<=128
        val metered=runCatching { connectivity.isActiveNetworkMetered }.getOrDefault(false)
        val downstreamKbps=runCatching {
            connectivity.getNetworkCapabilities(connectivity.activeNetwork)?.linkDownstreamBandwidthKbps ?: 0
        }.getOrDefault(0)
        val slowNetwork=downstreamKbps in 1..5_000
        val minBuffer: Int
        val maxBuffer: Int
        val startBuffer: Int
        val rebuffer: Int
        val targetBytes: Int
        when {
            lowRam -> {
                minBuffer=10_000; maxBuffer=25_000; startBuffer=1_000; rebuffer=2_000
                targetBytes=6*1024*1024
            }
            metered && slowNetwork -> {
                minBuffer=18_000; maxBuffer=40_000; startBuffer=1_500; rebuffer=3_000
                targetBytes=8*1024*1024
            }
            metered -> {
                minBuffer=12_000; maxBuffer=30_000; startBuffer=1_000; rebuffer=2_000
                targetBytes=8*1024*1024
            }
            slowNetwork -> {
                minBuffer=18_000; maxBuffer=45_000; startBuffer=1_500; rebuffer=3_500
                targetBytes=8*1024*1024
            }
            else -> {
                minBuffer=15_000; maxBuffer=45_000; startBuffer=1_000; rebuffer=2_500
                targetBytes=12*1024*1024
            }
        }
        android.util.Log.i("HongguoTV","Playback buffer profile lowRam=$lowRam metered=$metered downstream=${downstreamKbps}kbps slow=$slowNetwork min=$minBuffer max=$maxBuffer target=${targetBytes/1024/1024}MB")
        return DefaultLoadControl.Builder()
            .setBufferDurationsMs(minBuffer,maxBuffer,startBuffer,rebuffer)
            .setTargetBufferBytes(targetBytes)
            .build()
    }

    internal fun MainActivity.playbackFailureDetails(problem: Throwable): String {
        val chain=mutableListOf<String>()
        val seen=java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable,Boolean>())
        var current: Throwable?=problem
        while(current!=null && chain.size<5 && seen.add(current)) {
            chain+= "${current.javaClass.simpleName}: ${current.message.orEmpty().take(140)}"
            current=current.cause
        }
        val code=(problem as? PlaybackException)?.let { " code=${it.errorCode}(${it.errorCodeName})" }.orEmpty()
        return problem.javaClass.simpleName+code+" causes="+chain.joinToString(" <- ")
    }

internal fun MainActivity.createPlayerView(): PlayerView {
        val view = if(resolvedVideoRendererMode()==2) {
            layoutInflater.inflate(R.layout.player_view_texture,null,false) as PlayerView
        } else PlayerView(this)
        return view.apply {
            useController=false
            resizeMode=activeFrameResizeMode()
            isFocusable=false
            setKeepContentOnPlayerReset(true)
            setShutterBackgroundColor(Color.BLACK)
            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
        }
    }

    internal fun MainActivity.applyVideoRenderer() {
        val old=playerView ?: return
        val desired=resolvedVideoRendererMode()
        val current=old.videoSurfaceView
        if((desired==2 && current is TextureView) || (desired==1 && current is SurfaceView)) return
        val activePlayer=old.player
        val index=root.indexOfChild(old).coerceAtLeast(0)
        val params=old.layoutParams ?: FrameLayout.LayoutParams(-1,-1)
        old.player=null
        root.removeView(old)
        val replacement=createPlayerView()
        replacement.player=activePlayer
        playerView=replacement
        root.addView(replacement,index,params)
    }

internal fun MainActivity.videoItem(id: String)=MediaItem.Builder()
    .setMediaId(id).setUri("hongguotv://episode/$id").setMimeType(MimeTypes.VIDEO_MP4).build()

internal fun MainActivity.videoSource(
    remote: RemoteVideo,
    id: String,
    includeInPlaybackStats: () -> Boolean = { true }
)=ProgressiveMediaSource.Factory {
    VideoDataSource(remote) { count ->
        if(includeInPlaybackStats()) playbackBytes.addAndGet(count.toLong())
    }
}.createMediaSource(videoItem(id))
