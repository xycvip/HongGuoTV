package com.hongguotv.nativeapp

import android.view.View
import android.widget.TextView
import java.io.File
import java.util.concurrent.Executors

internal class DebugUiState {
    @Volatile var file: File? = null
    val logExecutor = Executors.newSingleThreadExecutor()
    val lock = Any()
    var overlayText: TextView? = null
    var tick: Runnable? = null
    var lastBytes = 0L
    var lastSampleAt = 0L
    var currentBytesPerSecond = 0L
    var playbackStartedAt = 0L
    var playbackReadyAt = 0L
    var firstFrameAt = 0L
    var settingButton: TextView? = null
    var settingRevealedThisSession = false
    var revealTapCount = 0
    var lastSpeedBytes = 0L
    var lastSpeedAt = 0L
    var currentSpeedBytesPerSecond = 0L
}
