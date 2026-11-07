// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

/** One independently replaceable playback-address channel. */
interface StreamSource {
    val name: String
    fun stream(id: String, maxQuality: Int): StreamInfo
}
