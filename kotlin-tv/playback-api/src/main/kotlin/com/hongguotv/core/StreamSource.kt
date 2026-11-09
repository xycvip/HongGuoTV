// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

/** Quality selection modes passed through StreamSource.maxQuality. */
object PlaybackQualityMode {
    const val AUTO_HIGH = -1
    const val AUTO_COMPAT = -2

    fun limit(mode: Int, portrait: Boolean?): Int = when (mode) {
        AUTO_HIGH -> 1080
        AUTO_COMPAT -> if (portrait == false) 1080 else 720
        else -> mode
    }
}

/** One independently replaceable playback-address channel. */
interface StreamSource {
    val name: String
    fun stream(id: String, maxQuality: Int): StreamInfo
}
