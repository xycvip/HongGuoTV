// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp.ui.player

import android.util.Log

/**
 * 播放链路诊断日志。
 *
 * 只走 logcat（TAG = HongguoPlayer），不落盘、不上报。
 * 解码类故障必须能一眼看出「当时几路解码器、什么编码、什么分辨率、错误码多少」，
 * 否则「无法解码」这类问题永远只能靠猜。
 */
internal object PlayerLog {
    private const val TAG = "HongguoPlayer"

    fun info(message: String) {
        runCatching { Log.i(TAG, message) }
    }

    fun warn(message: String) {
        runCatching { Log.w(TAG, message) }
    }

    fun error(message: String) {
        runCatching { Log.e(TAG, message) }
    }
}
