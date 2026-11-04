package com.hongguotv.nativeapp

import android.app.AlertDialog
import android.widget.TextView
import android.view.View
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.hongguotv.core.*
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.OkHttpClient

internal class PlaybackPipelineState {
    var randomReplenishing = false
    var randomPlaybackLaunching = false
    var randomLoadingLabel: TextView? = null
    var randomDialog: AlertDialog? = null
    var exitDialog: AlertDialog? = null
    var killOnDestroy = false
    var prefetchHttp: OkHttpClient? = null
    var playbackHttp: OkHttpClient? = null
    val preparedNext = PreparedSlot<RemoteVideo>({ android.os.SystemClock.elapsedRealtime() })
    var prefetchJob: Future<*>? = null
    var prefetchScope: RequestScope? = null
    var prefetchedFor: String = ""
    var readySince: Long = 0L
    var standbyPlayer: ExoPlayer? = null
    var standbyRemote: RemoteVideo? = null
    var standbyHttp: OkHttpClient? = null
    var standbyEpisodeId: String = ""
    var standbyReady: Boolean = false
    var standbyFallbackEpisodeId: String = ""
    var standbyJob: Future<*>? = null
    var standbyOutputView: View? = null
    var standbyByteCounterEnabled: AtomicBoolean? = null
    var standbyPrepareStartedAt: Long = 0L
    var standbyReadyAt: Long = 0L
    var standbyFirstFrameAt: Long = 0L
    var standbyPromotionAt: Long = 0L
    var standbyAttachedAt: Long = 0L
    var standbyVisibleFirstFrameAt: Long = 0L
    var playerListener: Player.Listener? = null
    var directPlayback = false
    var activeVideoFormat: Format? = null
    var activeDecoderName: String = ""
    val playbackBytes = AtomicLong(0L)
}
