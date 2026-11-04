package com.hongguotv.nativeapp

import android.animation.ValueAnimator
import android.view.View
import android.widget.FrameLayout

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
}
