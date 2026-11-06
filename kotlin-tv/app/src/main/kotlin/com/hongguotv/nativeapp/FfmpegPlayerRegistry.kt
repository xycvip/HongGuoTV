// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
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

    val videoMode: DecoderMode get() = requestedVideoMode

    @Synchronized
    fun setVideoMode(mode: DecoderMode) {
        requestedVideoMode = mode
        managers.values.toList().forEach { manager ->
            manager.selectVideoDecoder(mode)
        }
    }

    fun create(context: Context): Setup {
        // Do not touch NextLib renderers before the downloaded runtime is activated.
        // This lets users download/activate FFmpeg later in the same app process,
        // provided no FFmpeg renderer has attempted a native load yet.
        if (!FfmpegRuntimeLoader.isReady) {
            return Setup(
                DefaultRenderersFactory(context).setEnableDecoderFallback(true),
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
