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

internal object PlaybackApiDebugState {
    @Volatile var selected: String = "native"
        private set
    private val events = java.util.concurrent.CopyOnWriteArrayList<String>()
    fun record(api: String, event: String) {
        selected = api
        val stamp = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date())
        events.add(stamp + " " + event)
        while (events.size > 12) events.removeAt(0)
    }
    fun snapshot(): List<String> = events.toList()
}

internal fun Context.playbackStreamSource(http: OkHttpClient): StreamSource =
    if (playbackApiSelection() == "phoenix") {
        PlaybackApiDebugState.record("phoenix", "选择 Phoenix 播放接口")
        PhoenixPlaybackSource(http, PhoenixPlaybackDeviceSession.shared, PhoenixRequestSigner()) { event ->
            PlaybackApiDebugState.record("phoenix", event)
            if (this is MainActivity) debugLog("播放接口诊断：" + event)
        }
    } else {
        PlaybackApiDebugState.record("native", "选择原生播放接口")
        val repository = ContentRepository(http)
        object : StreamSource {
            override val name: String = "native"
            override fun stream(id: String, maxQuality: Int) = repository.stream(id, maxQuality)
        }
    }
