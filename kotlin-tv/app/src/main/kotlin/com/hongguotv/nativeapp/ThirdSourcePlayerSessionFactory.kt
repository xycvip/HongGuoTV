// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.app.Activity
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.ui.PlayerView

internal class ThirdSourcePlayerListener(
    private val isPlayScreen: () -> Boolean,
    private val onBuffering: () -> Unit,
    private val onReady: () -> Unit,
    private val onEnded: () -> Unit,
    private val onPlayingChanged: (Boolean) -> Unit,
    private val onError: (PlaybackException) -> Unit
) : Player.Listener {
    override fun onPlaybackStateChanged(state: Int) {
        when(state) {
            Player.STATE_BUFFERING -> if(isPlayScreen()) onBuffering()
            Player.STATE_READY -> onReady()
            Player.STATE_ENDED -> onEnded()
        }
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) = onPlayingChanged(isPlaying)

    override fun onPlayerError(error: PlaybackException) = onError(error)
}

internal class ThirdSourcePlayerViews {
    var playerHost: FrameLayout? = null
    var playerView: PlayerView? = null
    var playLoading: TextView? = null
    var playErrorText: TextView? = null
    var overlay: View? = null
    var progressFill: View? = null
    var progressThumb: View? = null
    var progressBubble: TextView? = null
    var seekBarView: View? = null
    var playTime: TextView? = null
    val overlayButtons: MutableList<TextView> = mutableListOf()
    var overlayTopView: View? = null
    var progressTrackView: View? = null

    fun clear() {
        overlayButtons.clear()
        playerHost = null
        playerView = null
        playLoading = null
        playErrorText = null
        overlay = null
        progressFill = null
        progressThumb = null
        progressBubble = null
        seekBarView = null
        playTime = null
        overlayTopView = null
        progressTrackView = null
    }
}

internal data class ThirdSourcePlayerSession(
    val host: FrameLayout,
    val view: PlayerView,
    val player: ExoPlayer,
    val loading: TextView,
    val error: TextView
)

/** 创建并配置第三方播放器会话，页面控制器只负责业务状态与叠层交互。 */
internal class ThirdSourcePlayerSessionFactory(
    private val activity: Activity,
    private val ui: ThirdSourceUi,
    private val listener: ThirdSourcePlayerListener,
    private val onBandwidthEstimate: (Long) -> Unit
) {
    fun create(
        episodeUrl: String,
        startMs: Long,
        autoplay: Boolean,
        playbackSpeed: Float,
        resizeMode: Int,
        initialSeekMs: Long = 0L
    ): ThirdSourcePlayerSession {
        val host = FrameLayout(activity).apply {
            isFocusable = false
            setBackgroundColor(Color.BLACK)
        }
        val view = PlayerView(activity).apply {
            useController = false
            this.resizeMode = resizeMode
        }
        host.addView(view, FrameLayout.LayoutParams(-1, -1))

        val loading = ui.text("正在加载…", 15f, Color.WHITE).apply { gravity = Gravity.CENTER }
        val error = ui.text("", 14f, TvStyle.accent).apply {
            gravity = Gravity.CENTER
            setPadding(0, ui.dp(8), 0, 0)
        }
        val center = ui.column().apply {
            gravity = Gravity.CENTER
            importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        center.addView(loading)
        center.addView(error)
        host.addView(center, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))

        val player = ExoPlayer.Builder(activity).build()
        player.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build(),
            true
        )
        view.player = player
        player.addListener(listener)
        player.addAnalyticsListener(object : AnalyticsListener {
            override fun onBandwidthEstimate(
                eventTime: AnalyticsListener.EventTime,
                totalLoadTimeMs: Int,
                totalBytesLoaded: Long,
                bitrateEstimate: Long
            ) {
                onBandwidthEstimate(bitrateEstimate)
            }
        })
        player.setMediaItem(MediaItem.fromUri(episodeUrl), startMs.coerceAtLeast(0L))
        player.prepare()
        player.setPlaybackSpeed(playbackSpeed)
        if(initialSeekMs > 0L) player.seekTo(initialSeekMs)
        player.playWhenReady = autoplay
        return ThirdSourcePlayerSession(host, view, player, loading, error)
    }
}
