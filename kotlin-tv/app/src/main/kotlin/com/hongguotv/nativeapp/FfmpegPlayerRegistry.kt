// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import android.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.DecoderManager
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.DecoderMode
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory
import java.util.WeakHashMap

/** 每个 ExoPlayer 独占一个 DecoderManager；解码模式切换即时通知所有活动播放器。 */
@androidx.annotation.OptIn(UnstableApi::class)
internal object FfmpegPlayerRegistry {
    private const val TAG = "HongguoTV-Decoder"
    data class Setup(val renderersFactory: DefaultRenderersFactory, internal val decoderManager: DecoderManager?)
    private val managers = WeakHashMap<ExoPlayer, DecoderManager>()
    @Volatile private var requestedVideoMode: DecoderMode = DecoderMode.AUTO
    @Volatile private var preferencesLoaded = false
    val videoMode: DecoderMode get() = requestedVideoMode

    @Synchronized
    private fun loadPreferences(context: Context) {
        if (preferencesLoaded) return
        val saved = context.getSharedPreferences("ffmpeg-settings-v1", Context.MODE_PRIVATE)
            .getString("video_decoder_mode", DecoderMode.AUTO.name)
        requestedVideoMode = runCatching { DecoderMode.valueOf(saved ?: DecoderMode.AUTO.name) }.getOrDefault(DecoderMode.AUTO)
        preferencesLoaded = true
        Log.i(TAG, "解码设置已加载 mode=$requestedVideoMode")
    }

    @Synchronized
    fun setVideoMode(context: Context, mode: DecoderMode) {
        val previous = requestedVideoMode
        requestedVideoMode = mode
        preferencesLoaded = true
        context.applicationContext.getSharedPreferences("ffmpeg-settings-v1", Context.MODE_PRIVATE)
            .edit().putString("video_decoder_mode", mode.name).apply()
        Log.i(TAG, "动态切换视频解码模式：$previous -> $mode；活动 FFmpeg 播放器=${managers.size}")
        managers.entries.toList().forEach { (player, manager) ->
            try {
                val playerId = System.identityHashCode(player)
                val before = "requested=${manager.videoMode} active=${manager.activeVideoMode} decoder=${manager.activeVideoDecoderName}"
                Log.i(TAG, "动态切换前 player=$playerId media=${player.currentMediaItem?.mediaId} $before -> target=$mode")
                manager.selectVideoDecoder(mode)
                Log.i(TAG, "动态切换已提交 player=$playerId media=${player.currentMediaItem?.mediaId} before={$before} after=requested=${manager.videoMode} active=${manager.activeVideoMode} decoder=${manager.activeVideoDecoderName} state=${player.playbackState}")
            } catch (problem: Throwable) {
                Log.e(TAG, "动态切换解码模式失败 player=${System.identityHashCode(player)} mode=$mode", problem)
                if (problem is VirtualMachineError || problem is ThreadDeath) throw problem
            }
        }
    }

    private fun mediaCodecSetup(context: Context, modeProvider: () -> DecoderMode): Setup {
        val selector = MediaCodecSelector { mimeType, secure, tunneling ->
            val decoders = MediaCodecSelector.DEFAULT.getDecoderInfos(mimeType, secure, tunneling)
            val mode = modeProvider()
            when {
                !MimeTypes.isVideo(mimeType) -> decoders
                mode == DecoderMode.HARDWARE -> decoders.filter { it.hardwareAccelerated }
                mode == DecoderMode.SOFTWARE -> decoders.filter { it.softwareOnly }
                else -> decoders.sortedBy { when { it.hardwareAccelerated -> 0; it.softwareOnly -> 1; else -> 2 } }
            }
        }
        return Setup(DefaultRenderersFactory(context.applicationContext)
            .setEnableDecoderFallback(true).setMediaCodecSelector(selector), null)
    }

    fun create(context: Context): Setup {
        loadPreferences(context)
        if (!FfmpegRuntimeLoader.isReady) {
            Log.i(TAG, "创建 MediaCodec 管线 mode=$requestedVideoMode ffmpegReady=false")
            return mediaCodecSetup(context) { requestedVideoMode }
        }
        return try {
            val manager = DecoderManager(initialVideoMode = requestedVideoMode, initialAudioMode = DecoderMode.AUTO)
            val factory = NextRenderersFactory(context.applicationContext).setDecoderManager(manager)
            Log.i(TAG, "创建 FFmpeg 管线 mode=$requestedVideoMode")
            Setup(factory, manager)
        } catch (problem: Throwable) {
            Log.e(TAG, "创建 FFmpeg renderer 失败，降级到 MediaCodec mode=$requestedVideoMode", problem)
            if (problem is VirtualMachineError || problem is ThreadDeath) throw problem
            mediaCodecSetup(context) { requestedVideoMode }
        }
    }

    @Synchronized
    fun attach(player: ExoPlayer, setup: Setup) {
        identities[player] = setup.identity
        val manager = setup.decoderManager
        if (manager == null) {
            Log.i(TAG, "Renderer 创建完成 role=${setup.identity.role} player=${System.identityHashCode(player)} media=${player.currentMediaItem?.mediaId} identity=${setup.identity} renderers=${rendererTypes(player)}")
            return
        }
        try {
            manager.attach(player)
            manager.selectVideoDecoder(requestedVideoMode)
            managers[player] = manager
            Log.i(TAG, "DecoderManager 已附加 player=${System.identityHashCode(player)} mode=$requestedVideoMode ffmpegRendererPresent=${manager.controller.owns(player)} renderers=${rendererTypes(player)}")
        } catch (problem: Throwable) {
            Log.e(TAG, "DecoderManager 附加失败 player=${System.identityHashCode(player)}", problem)
            runCatching { manager.detach() }
            if (problem is VirtualMachineError || problem is ThreadDeath) throw problem
            throw IllegalStateException("FFmpeg 解码管理器附加失败", problem)
        }
    }

    @Synchronized
    fun detach(player: ExoPlayer?) {
        if (player == null) return
        managers.remove(player)?.let { manager ->
            runCatching { manager.detach() }.onFailure {
                Log.e(TAG, "DecoderManager detach 失败 player=${System.identityHashCode(player)}", it)
            }
        }
        identities.remove(player)
        Log.d(TAG, "播放器解码管理器已解绑 player=${System.identityHashCode(player)}")
    }

    @Synchronized
    fun selectVideo(player: ExoPlayer, mode: DecoderMode) {
        val manager = managers[player] ?: return
        try {
            val before = "requested=${manager.videoMode} active=${manager.activeVideoMode} decoder=${manager.activeVideoDecoderName}"
            Log.i(TAG, "指定播放器切换前 player=${System.identityHashCode(player)} media=${player.currentMediaItem?.mediaId} $before -> $mode")
            manager.selectVideoDecoder(mode)
            Log.i(TAG, "指定播放器切换后 player=${System.identityHashCode(player)} media=${player.currentMediaItem?.mediaId} before={$before} requested=${manager.videoMode} active=${manager.activeVideoMode} decoder=${manager.activeVideoDecoderName}")
        } catch (problem: Throwable) {
            Log.e(TAG, "指定播放器动态切换失败 player=${System.identityHashCode(player)} mode=$mode", problem)
            if (problem is VirtualMachineError || problem is ThreadDeath) throw problem
        }
    }

    private fun rendererTypes(player: ExoPlayer): String =
        (0 until player.rendererCount).joinToString(",") { index ->
            "${index}:${player.getRenderer(index).javaClass.simpleName}#${System.identityHashCode(player.getRenderer(index))}"
        }

    @Synchronized
    fun identitySnapshot(player: ExoPlayer?): String {
        if (player == null) return "player=null"
        val identity = identities[player]
        val manager = managers[player]
        return "player=${System.identityHashCode(player)} media=${player.currentMediaItem?.mediaId} " +
            "creation=${identity ?: "UNKNOWN"} manager=${manager != null} " +
            "managerRequested=${manager?.videoMode} activeMode=${manager?.activeVideoMode} " +
            "activeDecoder=${manager?.activeVideoDecoderName} renderers=[${rendererTypes(player)}]"
    }

    @Synchronized
    fun currentVideoMode(player: ExoPlayer): DecoderMode? = managers[player]?.activeVideoMode
}
