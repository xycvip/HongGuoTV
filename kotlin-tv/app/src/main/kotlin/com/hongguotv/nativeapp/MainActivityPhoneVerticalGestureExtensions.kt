// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import android.media.AudioManager
import android.provider.Settings
import android.widget.Toast
import kotlin.math.roundToInt

/** 手机播放器上下滑动：左侧调节当前播放窗口亮度，右侧调节媒体音量。 */
internal fun MainActivity.beginPhoneVerticalGesture(isLeft: Boolean): Float {
    if (isLeft) {
        if (phonePlayerOriginalWindowBrightness == null) {
            phonePlayerOriginalWindowBrightness = window.attributes.screenBrightness
        }
        val windowValue = window.attributes.screenBrightness
        return if (windowValue >= 0f) windowValue.coerceIn(0.05f, 1f) else {
            val systemValue = runCatching {
                Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS)
            }.getOrDefault(128)
            (systemValue / 255f).coerceIn(0.05f, 1f)
        }
    }
    val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
    val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
    return audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max
}

internal fun MainActivity.updatePhoneVerticalGesture(
    isLeft: Boolean,
    startValue: Float,
    delta: Float
): Int {
    val value = (startValue + delta).coerceIn(0.05f, 1f)
    val percent = (value * 100f).roundToInt().coerceIn(0, 100)
    if (isLeft) {
        val attributes = window.attributes
        attributes.screenBrightness = value
        window.attributes = attributes
    } else {
        val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val target = (value * max).roundToInt().coerceIn(0, max)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
    }
    return percent
}

internal fun MainActivity.finishPhoneVerticalGesture(isLeft: Boolean, percent: Int) {
    Toast.makeText(
        this,
        (if (isLeft) "亮度" else "音量") + "  $percent%",
        Toast.LENGTH_SHORT
    ).show()
}
