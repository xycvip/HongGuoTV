package com.hongguotv.nativeapp

import android.animation.ValueAnimator
import android.view.View
import android.widget.FrameLayout

internal class ApiStatusUiState {
    val views = mutableMapOf<String, ApiStatusFlowerView>()
    val results = mutableMapOf<String, Pair<Int, String>>()
    val animators = mutableMapOf<String, ValueAnimator>()
    var catalogNavFrame: FrameLayout? = null
    var catalogNavFlower: View? = null
    var catalogNavVisible = true
    var checkStarted = false
}
