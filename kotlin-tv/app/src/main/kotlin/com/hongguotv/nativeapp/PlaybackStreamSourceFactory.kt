// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import com.hongguotv.core.ContentRepository
import com.hongguotv.core.PhoenixPlaybackSource
import com.hongguotv.core.PhoenixPlaybackDeviceSession
import com.hongguotv.core.PhoenixRequestSigner
import com.hongguotv.core.StreamSource
import com.hongguotv.core.StreamInfo
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import java.io.IOException

private const val PLAYBACK_API_PREFS = "playback-api-v1"
private const val PLAYBACK_API_KEY = "source"
private const val PLAYBACK_API_REVISION_KEY = "revision"

internal data class PlaybackRouteSnapshot(val api: String, val revision: Long)

internal object PlaybackRouteSelection {
    @Synchronized
    fun snapshot(context: Context): PlaybackRouteSnapshot {
        val prefs = context.getSharedPreferences(PLAYBACK_API_PREFS, Context.MODE_PRIVATE)
        val api = prefs.getString(PLAYBACK_API_KEY, "native")
            .let { if (it == "phoenix") "phoenix" else "native" }
        PlaybackApiDebugState.select(api)
        return PlaybackRouteSnapshot(api, prefs.getLong(PLAYBACK_API_REVISION_KEY, 0L))
    }

    @Synchronized
    fun set(context: Context, api: String) {
        val normalized = if (api == "phoenix") "phoenix" else "native"
        val prefs = context.getSharedPreferences(PLAYBACK_API_PREFS, Context.MODE_PRIVATE)
        val revision = prefs.getLong(PLAYBACK_API_REVISION_KEY, 0L)
        prefs.edit().putString(PLAYBACK_API_KEY, normalized)
            .putLong(PLAYBACK_API_REVISION_KEY, revision + 1L).apply()
        PlaybackApiDebugState.select(normalized)
    }

    @Synchronized
    fun commitFallback(context: Context, expected: PlaybackRouteSnapshot, api: String): Boolean {
        val normalized = if (api == "phoenix") "phoenix" else "native"
        val prefs = context.getSharedPreferences(PLAYBACK_API_PREFS, Context.MODE_PRIVATE)
        val currentApi = prefs.getString(PLAYBACK_API_KEY, "native")
            .let { if (it == "phoenix") "phoenix" else "native" }
        val currentRevision = prefs.getLong(PLAYBACK_API_REVISION_KEY, 0L)
        if (currentApi != expected.api || currentRevision != expected.revision) return false
        prefs.edit().putString(PLAYBACK_API_KEY, normalized)
            .putLong(PLAYBACK_API_REVISION_KEY, currentRevision + 1L).apply()
        PlaybackApiDebugState.select(normalized)
        return true
    }
}

internal fun Context.playbackApiSelection(): String = PlaybackRouteSelection.snapshot(this).api

// Route-independent identity lets a successfully prepared standby player survive N/P fallback.
internal fun Context.playbackResourceKey(id: String, quality: Int): String = "$id:$quality"

internal fun Context.playbackApiLabel(value: String = playbackApiSelection()): String =
    if (value == "phoenix") "线路首选 · Phoenix" else "线路首选 · Native"

internal object PlaybackApiDebugState {
    @Volatile var selected: String = "native"
        private set
    private val events = java.util.concurrent.CopyOnWriteArrayList<String>()
    private val pendingFallbackNotices = java.util.concurrent.ConcurrentHashMap<String, String>()

    private fun noticeKey(id: String, quality: Int): String = "$id:$quality"

    fun rememberFallbackNotice(id: String, quality: Int, message: String) {
        pendingFallbackNotices[noticeKey(id, quality)] = message
    }

    fun consumeFallbackNotice(id: String, quality: Int): String? =
        pendingFallbackNotices.remove(noticeKey(id, quality))

    fun clearFallbackNotice(id: String, quality: Int) {
        pendingFallbackNotices.remove(noticeKey(id, quality))
    }

    @Synchronized
    fun select(api: String) {
        val normalized = if (api == "phoenix") "phoenix" else "native"
        if (selected == normalized) return
        selected = normalized
        events.clear()
        val label = if (normalized == "phoenix") "Phoenix" else "Native"
        append(normalized, "当前优先线路已设为${label}")
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
        val label = if (api == "phoenix") "Phoenix" else "Native"
        events.add("$stamp [$label] $event")
        while (events.size > 12) events.removeAt(0)
    }

    fun snapshot(): List<String> = events.toList()
}

internal interface PlaybackRouteAware {
    val resolvedApi: String
}

internal class RoutedStreamSource(
    override val resolvedApi: String,
    private val delegate: StreamSource
) : StreamSource, PlaybackRouteAware {
    override val name: String get() = delegate.name
    override fun stream(id: String, maxQuality: Int): StreamInfo = delegate.stream(id, maxQuality)
}

internal fun StreamSource.resolvedPlaybackApi(): String =
    (this as? PlaybackRouteAware)?.resolvedApi ?: name.let {
        if (it.contains("phoenix", true)) "phoenix" else "native"
    }

internal class AutoFallbackStreamSource(
    private val primary: StreamSource,
    private val fallback: () -> StreamSource,
    private val onFallbackStart: (String) -> Unit = {},
    private val onFallbackSuccess: (String, String, Int) -> Unit = { _, _, _ -> },
    private val onFallbackFailure: (String) -> Unit = {}
) : StreamSource, PlaybackRouteAware {
    @Volatile
    override var resolvedApi: String = primary.resolvedPlaybackApi()
        private set

    override val name: String = "auto:${primary.name}"

    override fun stream(id: String, maxQuality: Int): StreamInfo {
        resolvedApi = primary.resolvedPlaybackApi()
        try {
            return primary.stream(id, maxQuality)
        } catch (primaryError: IOException) {
            if (isPlaybackRequestCancelled(primaryError) ||
                primaryError is UnplayableVideoException ||
                primaryError is UnsupportedVideoFormatException
            ) throw primaryError
            val backup = fallback()
            runCatching { onFallbackStart(backup.name) }
            try {
                val result = backup.stream(id, maxQuality)
                resolvedApi = backup.resolvedPlaybackApi()
                runCatching { onFallbackSuccess(id, resolvedApi, maxQuality) }
                return result
            } catch (fallbackError: IOException) {
                runCatching { onFallbackFailure(primary.name) }
                if (isPlaybackRequestCancelled(fallbackError) ||
                    fallbackError is UnplayableVideoException ||
                    fallbackError is UnsupportedVideoFormatException
                ) throw fallbackError
                fallbackError.addSuppressed(primaryError)
                throw IOException(
                    "首选播放接口 ${primary.name} 失败，备用接口 ${backup.name} 也失败：${fallbackError.message}",
                    fallbackError
                )
            }
        }
    }

    private fun isPlaybackRequestCancelled(error: IOException): Boolean {
        if (Thread.currentThread().isInterrupted) return true
        val message = error.message.orEmpty().trim().lowercase(java.util.Locale.ROOT)
        return message.contains("请求已取消") || message == "canceled" || message == "cancelled"
    }
}
internal fun Context.playbackStreamSource(
    http: OkHttpClient,
    notifyOnFallback: Boolean = true,
    allowFallback: Boolean = true
): StreamSource {
    fun createSource(api: String): StreamSource {
        return if (api == "phoenix") {
            PlaybackApiDebugState.record(api, "选择 Phoenix 播放接口")
            PhoenixPlaybackSource(http.newBuilder().cookieJar(CookieJar.NO_COOKIES).build(), PhoenixPlaybackDeviceSession.shared, PhoenixRequestSigner()) { event ->
                if (PlaybackApiDebugState.record(api, event) && this is MainActivity) {
                    debugLog("播放接口诊断：[$api] $event")
                }
            }
        } else {
            PlaybackApiDebugState.record(api, "选择 Native 播放接口")
            val repository = ContentRepository(http)
            object : StreamSource {
                override val name: String = "native"
                override fun stream(id: String, maxQuality: Int): StreamInfo {
                    PlaybackApiDebugState.record(api, "Native：开始获取播放地址")
                    return try {
                        repository.stream(id, maxQuality).also {
                            PlaybackApiDebugState.record(api, "Native：播放地址获取成功，清晰度=${it.quality}")
                        }
                    } catch (error: Exception) {
                        PlaybackApiDebugState.record(api, "Native：播放地址获取失败，异常类型=${error.javaClass.simpleName}")
                        throw error
                    }
                }
            }
        }
    }

    val routeSnapshot = PlaybackRouteSelection.snapshot(this)
    val primary = createSource(routeSnapshot.api)
    if (!allowFallback) return primary
    val fallbackApi = if (routeSnapshot.api == "phoenix") "native" else "phoenix"
    return AutoFallbackStreamSource(
        primary = primary,
        fallback = { createSource(fallbackApi) },
        onFallbackStart = { api ->
            PlaybackApiDebugState.record(routeSnapshot.api, "首选接口失败，自动尝试备用接口 $api")
            if (this is MainActivity) debugLog("播放接口自动切换：${routeSnapshot.api} 失败，开始尝试 $api")
        },
        onFallbackSuccess = { id, api, maxQuality ->
            val committed = PlaybackRouteSelection.commitFallback(this, routeSnapshot, api)
            if (committed) {
                PlaybackApiDebugState.record(api, "备用接口获取播放资源成功，已记为下次优先线路")
                if (this is MainActivity) {
                    if (notifyOnFallback) {
                        val notice = if (routeSnapshot.api == "native") "已自动切换至线路 Phoenix"
                            else "已自动切换至线路 Native"
                        PlaybackApiDebugState.rememberFallbackNotice(id, maxQuality, notice)
                    }
                    debugLog("播放接口自动切换成功：后续优先使用 $api")
                }
            } else if (this is MainActivity) {
                debugLog("备用线路 $api 已成功，但优先线路已被更新，忽略旧请求的线路写入与提示")
            }
        },
        onFallbackFailure = { api ->
            PlaybackApiDebugState.record(routeSnapshot.api, "备用接口 $api 也失败，保留当前优先线路")
        }
    )
}
