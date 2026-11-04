// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import com.hongguotv.core.ContentRepository
import com.hongguotv.core.RemoteVideo

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import okhttp3.Dispatcher
import okhttp3.ConnectionPool
import java.util.concurrent.TimeUnit

/** 清理备用播放器及其独立网络会话；不会触碰当前正在播放的主播放器。 */
internal fun MainActivity.clearStandbyPlayer(reason: String) {
    standbyJob?.cancel(true)
    standbyJob = null
    standbyPlayer?.let { player ->
        runCatching { player.playWhenReady = false; player.stop(); player.clearMediaItems(); player.release() }
    }
    standbyPlayer = null
    standbyRemote?.close()
    standbyRemote = null
    closeTransport(standbyHttp)
    standbyHttp = null
    standbyEpisodeId = ""
    standbyReady = false
    debugLog("无缝接续：备用播放器已清理，原因=$reason")
}

/** 在后台创建第二个 ExoPlayer，独立解析并缓冲下一集；不启动声音、不抢占主播放器控制权。 */
internal fun MainActivity.prepareStandbyEpisode(index: Int) {
    val data = detail ?: return
    if (!seamlessPlaybackEnabled || !foreground || screen != "player" || !playbackReady) return
    if (index !in data.episodes.indices) return
    val id = data.episodes[index]
    val quality = library.maxQuality
    val key = "$id:$quality"
    if (standbyEpisodeId == key && (standbyReady || standbyJob?.isDone == false)) return

    val manager = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
    if (manager.isLowRamDevice || manager.memoryClass <= 128) {
        standbyFallbackEpisodeId = data.episodes.getOrNull(episodeIndex).orEmpty()
        debugLog("无缝接续：设备内存等级较低，回退单播放器预加载")
        maybePrefetch(force = true)
        return
    }

    clearStandbyPlayer("准备新的下一集")
    standbyFallbackEpisodeId = ""
    standbyEpisodeId = key
    val http = repository.http.newBuilder()
        .dispatcher(Dispatcher())
        .connectionPool(ConnectionPool())
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .build()
    standbyHttp = http
    val source = ContentRepository(http)
    val requestGeneration = generation
    val requestSeries = data.series.id
    standbyJob = prefetchWorker.submit {
        var remote: com.hongguotv.core.RemoteVideo? = null
        try {
            val prepared = RemoteVideo(http, source.stream(id, quality)).also {
                remote = it
                it.prepare()
            }
            main.post {
                if (isDestroyed || !foreground || generation != requestGeneration ||
                    detail?.series?.id != requestSeries || !seamlessPlaybackEnabled ||
                    standbyEpisodeId != key || screen != "player") {
                    prepared.close()
                    closeTransport(http)
                    if (standbyHttp === http) standbyHttp = null
                    if (standbyEpisodeId == key) { standbyEpisodeId = ""; standbyReady = false }
                    return@post
                }
                try {
                    val renderers = DefaultRenderersFactory(this).setEnableDecoderFallback(true)
                    val secondary = ExoPlayer.Builder(this)
                        .setRenderersFactory(renderers)
                        // 备用播放器只保留短缓冲，避免把主播放器的缓冲预算再复制一份。
                        .setLoadControl(
                            DefaultLoadControl.Builder()
                                .setBufferDurationsMs(1_000, 8_000, 1_000, 1_500)
                                .setTargetBufferBytes(3 * 1024 * 1024)
                                .build()
                        )
                        .build()
                    secondary.setAudioAttributes(
                        AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
                            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true
                    )
                    secondary.volume = 0f
                    secondary.playWhenReady = false
                    secondary.addListener(object : Player.Listener {
                        override fun onPlaybackStateChanged(state: Int) {
                            if (standbyPlayer !== secondary || standbyEpisodeId != key) return
                            if (state == Player.STATE_READY) {
                                standbyReady = true
                                standbyFallbackEpisodeId = ""
                                debugLog("无缝接续：备用播放器 READY，下一集已预备 index=${index + 1}")
                            } else if (state == Player.STATE_IDLE && standbyReady) {
                                standbyReady = false
                            }
                        }
                        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                            if (standbyPlayer !== secondary || standbyEpisodeId != key) return
                            debugLog("无缝接续：备用播放器准备失败 ${error.errorCodeName} ${error.message}")
                            clearStandbyPlayer("备用播放器准备失败")
                            standbyFallbackEpisodeId = data.episodes.getOrNull(episodeIndex).orEmpty()
                            maybePrefetch(force = true)
                        }
                    })
                    secondary.setMediaSource(videoSource(prepared, id))
                    standbyRemote = prepared
                    standbyPlayer = secondary
                    secondary.prepare()
                    debugLog("无缝接续：第二播放器开始准备下一集 index=${index + 1}")
                } catch (problem: Exception) {
                    clearStandbyPlayer("第二播放器初始化异常")
                    standbyFallbackEpisodeId = data.episodes.getOrNull(episodeIndex).orEmpty()
                    debugLog("无缝接续：第二播放器初始化失败，回退单播放器：${problem.message}")
                    maybePrefetch(force = true)
                }
            }
        } catch (problem: Exception) {
            remote?.close()
            closeTransport(http)
            main.post {
                if (standbyHttp === http) standbyHttp = null
                if (standbyEpisodeId == key) {
                    standbyEpisodeId = ""
                    standbyReady = false
                    standbyFallbackEpisodeId = data.episodes.getOrNull(episodeIndex).orEmpty()
                    debugLog("无缝接续：下一集预备失败，回退单播放器：${problem.message}")
                    maybePrefetch(force = true)
                }
            }
        }
    }
}
