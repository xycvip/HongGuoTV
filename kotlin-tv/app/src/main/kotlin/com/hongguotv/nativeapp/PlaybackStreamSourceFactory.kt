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

internal fun Context.playbackApiSelection(): String {
    val selected = getSharedPreferences(PLAYBACK_API_PREFS, Context.MODE_PRIVATE)
        .getString(PLAYBACK_API_KEY, "native")
        .let { if (it == "phoenix") "phoenix" else "native" }
    PlaybackApiDebugState.select(selected)
    return selected
}

internal fun Context.playbackResourceKey(id: String, quality: Int): String =
    "$id:$quality:${playbackApiSelection()}"

internal fun Context.playbackApiLabel(value: String = playbackApiSelection()): String =
    if (value == "phoenix") "Phoenix（实验）" else "原生"

internal object PlaybackApiDebugState {
    @Volatile var selected: String = "native"
        private set
    private val events = java.util.concurrent.CopyOnWriteArrayList<String>()

    @Synchronized
    fun select(api: String) {
        val normalized = if (api == "phoenix") "phoenix" else "native"
        if (selected == normalized) return
        selected = normalized
        events.clear()
        val label = if (normalized == "phoenix") "Phoenix" else "原生"
        append(normalized, "已切换到$label播放线路")
    }

    @Synchronized
    fun record(api: String, event: String): Boolean {
        val normalized = if (api == "phoenix") "phoenix" else "native"
        if (normalized != selected) return false
        append(normalized, event)
        return true
    }

    private fun append(api: String, event: String) {
        val stamp = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date())
        val label = if (api == "phoenix") "Phoenix" else "原生"
        events.add("$stamp [$label] $event")
        while (events.size > 12) events.removeAt(0)
    }

    fun snapshot(): List<String> = events.toList()
}

internal fun Context.playbackStreamSource(http: OkHttpClient): StreamSource {
    val api = playbackApiSelection()
    return if (api == "phoenix") {
        PlaybackApiDebugState.record(api, "选择 Phoenix 播放接口")
        PhoenixPlaybackSource(http, PhoenixPlaybackDeviceSession.shared, PhoenixRequestSigner()) { event ->
            if (PlaybackApiDebugState.record(api, event) && this is MainActivity) {
                debugLog("播放接口诊断：[$api] $event")
            }
        }
    } else {
        PlaybackApiDebugState.record(api, "选择原生播放接口")
        val repository = ContentRepository(http)
        object : StreamSource {
            override val name: String = "native"
            override fun stream(id: String, maxQuality: Int): StreamInfo {
                PlaybackApiDebugState.record(api, "原生：开始获取播放地址")
                return try {
                    repository.stream(id, maxQuality).also {
                        PlaybackApiDebugState.record(api, "原生：播放地址获取成功，清晰度=${it.quality}")
                    }
                } catch (error: Exception) {
                    PlaybackApiDebugState.record(api, "原生：播放地址获取失败，异常类型=${error.javaClass.simpleName}")
                    throw error
                }
            }
        }
    }
}
