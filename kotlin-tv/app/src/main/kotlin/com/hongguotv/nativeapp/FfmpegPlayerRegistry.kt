// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.DecoderManager
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.DecoderMode
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory
import java.util.WeakHashMap

/**
 * Owns one DecoderManager per ExoPlayer. Main and seamless standby players must
 * never share a manager because each manager is attached to exactly one player.
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal object FfmpegPlayerRegistry {
    data class Setup(
        val renderersFactory: DefaultRenderersFactory,
        internal val decoderManager: DecoderManager?
    )

    private val managers = WeakHashMap<ExoPlayer, DecoderManager>()

    @Volatile
    private var requestedVideoMode: DecoderMode = DecoderMode.AUTO

    @Volatile
    private var preferencesLoaded = false

    val videoMode: DecoderMode get() = requestedVideoMode

    @Synchronized
    private fun loadPreferences(context: Context) {
        if (preferencesLoaded) return
        val saved = context.getSharedPreferences("ffmpeg-settings-v1", Context.MODE_PRIVATE)
            .getString("video_decoder_mode", DecoderMode.AUTO.name)
        requestedVideoMode = runCatching { DecoderMode.valueOf(saved ?: DecoderMode.AUTO.name) }
            .getOrDefault(DecoderMode.AUTO)
        preferencesLoaded = true
    }

    @Synchronized
    fun setVideoMode(context: Context, mode: DecoderMode) {
        requestedVideoMode = mode
        preferencesLoaded = true
        context.applicationContext.getSharedPreferences("ffmpeg-settings-v1", Context.MODE_PRIVATE)
            .edit().putString("video_decoder_mode", mode.name).apply()
        managers.values.toList().forEach { manager ->
            manager.selectVideoDecoder(mode)
        }
    }

    fun create(context: Context): Setup {
        loadPreferences(context)
        // Before FFmpeg is installed, still expose hardware/software MediaCodec
        // selection. The optional FFmpeg renderer is added only after activation.
        if (!FfmpegRuntimeLoader.isReady) {
            val selector = MediaCodecSelector { mimeType, secure, tunneling ->
                val decoders = MediaCodecSelector.DEFAULT.getDecoderInfos(mimeType, secure, tunneling)
                val mode = requestedVideoMode
                when {
                    !MimeTypes.isVideo(mimeType) -> decoders
                    mode == DecoderMode.HARDWARE -> decoders.filter { it.hardwareAccelerated }
                    mode == DecoderMode.SOFTWARE -> decoders.filter { it.softwareOnly }
                    else -> decoders.sortedBy {
                        when {
                            it.hardwareAccelerated -> 0
                            it.softwareOnly -> 1
                            else -> 2
                        }
                    }
                }
            }
            return Setup(
                DefaultRenderersFactory(context)
                    .setEnableDecoderFallback(true)
                    .setMediaCodecSelector(selector),
                null
            )
        }
        val manager = DecoderManager(
            initialVideoMode = requestedVideoMode,
            initialAudioMode = DecoderMode.AUTO
        )
        val factory = NextRenderersFactory(context.applicationContext)
            .setDecoderManager(manager)
        return Setup(factory, manager)
    }

    @Synchronized
    fun attach(player: ExoPlayer, setup: Setup) {
        val manager = setup.decoderManager ?: return
        manager.attach(player)
        managers[player] = manager
    }

    @Synchronized
    fun detach(player: ExoPlayer?) {
        if (player == null) return
        managers.remove(player)?.let { manager ->
            runCatching { manager.detach() }
        }
    }

    @Synchronized
    fun selectVideo(player: ExoPlayer, mode: DecoderMode) {
        managers[player]?.selectVideoDecoder(mode)
    }

    @Synchronized
    fun currentVideoMode(player: ExoPlayer): DecoderMode? =
        managers[player]?.activeVideoMode
}
