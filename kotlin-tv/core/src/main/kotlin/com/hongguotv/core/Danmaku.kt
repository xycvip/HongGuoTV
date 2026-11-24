// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

/** One official bullet comment, anchored to a playback offset within its episode. */
data class Danmaku(val id: String, val offsetMs: Long, val text: String, val diggCount: Int = 0, val color: Int? = null)
