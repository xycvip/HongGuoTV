package com.hongguotv.nativeapp

import android.app.AlertDialog
import android.view.View
import android.widget.TextView
import androidx.media3.ui.PlayerView
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.preload.DefaultPreloadManager
import com.hongguotv.core.RemoteVideo

internal class PlaybackUiState {
    var player: ExoPlayer? = null
    var preloadManager: DefaultPreloadManager? = null
    var video: RemoteVideo? = null
    var playbackReady = false
    var pausedForLifecycle = false
    var panel = false
    var touchTracking = false
    var touchStartX = 0f
    var touchStartY = 0f
    var touchSeekStart = 0L
    var touchSeekTarget: Long? = null
    var touchSeekGesture = false
    var touchOnProgressBar = false
    var touchOnHud = false
    var progressUserSeeking = false
    var lastTapAt = 0L
    var lastTapX = 0f
    var lastTapY = 0f
    var transportPlay: TextView? = null
    var sleepButton: TextView? = null
    var speedDialog: AlertDialog? = null
    var pendingSeek: Long? = null
    var playError = false
    var lastSaved = 0L
    var quality = ""
    var playerView: PlayerView? = null
    var episodePanel: EpisodePanel? = null
    val resumeCards = mutableListOf<View>()
    var retryPending = false
    var autoRecovery = false
    var incompatibleSkips = 0
    var startPosition = 0L
    var outroSkipSeconds = 0
    var outroTriggered = false
    var ffActive = false
    var ffSpeedBefore = 1f
    var retryPosition = 0L
    var requestedAutoplay = true
    var retryAutoplay = true
    var sleepSettingHintShown = false
    var sleepStopped = false
}
