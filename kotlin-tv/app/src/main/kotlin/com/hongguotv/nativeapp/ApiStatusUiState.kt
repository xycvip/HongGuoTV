package com.hongguotv.nativeapp

import android.animation.ValueAnimator
import android.view.View
import android.widget.FrameLayout
import com.hongguotv.core.RequestScope
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Future

internal class ApiStatusUiState {
    val views = mutableMapOf<String, ApiStatusFlowerView>()
    val results = mutableMapOf<String, Pair<Int, String>>()
    val updatedAt = mutableMapOf<String, Long>()
    val elapsedMs = mutableMapOf<String, Long?>()
    var checkBatchId = 0L
    val animators = mutableMapOf<String, ValueAnimator>()
    var catalogNavFrame: FrameLayout? = null
    var catalogNavFlower: View? = null
    var catalogNavVisible = true
    var checkStarted = false
    val playbackProbeScopes = CopyOnWriteArrayList<RequestScope>()
    val playbackProbeJobs = CopyOnWriteArrayList<Future<*>>()

    fun cancelPlaybackProbes() {
        playbackProbeScopes.forEach { it.cancel() }
        playbackProbeScopes.clear()
        playbackProbeJobs.forEach { it.cancel(true) }
        playbackProbeJobs.clear()
    }
}
