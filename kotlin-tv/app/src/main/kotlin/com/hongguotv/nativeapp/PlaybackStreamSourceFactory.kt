// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import com.hongguotv.core.ContentRepository
import com.hongguotv.core.PhoenixPlaybackDeviceSession
import com.hongguotv.core.PhoenixPlaybackSource
import com.hongguotv.core.PhoenixRequestSigner
import com.hongguotv.core.StreamSource
import okhttp3.OkHttpClient

private const val PLAYBACK_API_PREFS = "playback-api-v1"
private const val PLAYBACK_API_KEY = "source"

internal fun Context.playbackApiSelection(): String =
    getSharedPreferences(PLAYBACK_API_PREFS, Context.MODE_PRIVATE)
        .getString(PLAYBACK_API_KEY, "native")
        .let { if (it == "phoenix") "phoenix" else "native" }

internal fun Context.playbackApiLabel(value: String = playbackApiSelection()): String =
    if (value == "phoenix") "Phoenix（实验）" else "原生"

internal fun Context.playbackStreamSource(http: OkHttpClient): StreamSource =
    if (playbackApiSelection() == "phoenix") {
        PhoenixPlaybackSource(http, PhoenixPlaybackDeviceSession.shared, PhoenixRequestSigner())
    } else {
        val repository = ContentRepository(http)
        object : StreamSource {
            override val name: String = "native"
            override fun stream(id: String, maxQuality: Int) = repository.stream(id, maxQuality)
        }
    }
