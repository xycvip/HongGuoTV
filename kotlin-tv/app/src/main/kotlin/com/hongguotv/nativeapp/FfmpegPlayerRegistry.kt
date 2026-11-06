// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.DecoderManager
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.DecoderMode
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory
import java.util.WeakHashMap

/**
 * Owns one DecoderManager per ExoPlayer. Main and seamless standby players must
 * never share a manager because each manager is attached to exactly one player.
 */
@UnstableApi
internal object FfmpegPlayerRegistry {
    data class Setup(
        val renderersFactory: NextRenderersFactory,
        internal val decoderManager: DecoderManager
    )

    private val managers = WeakHashMap<ExoPlayer, DecoderManager>()

    fun create(context: Context): Setup {
        val manager = DecoderManager(
            initialVideoMode = DecoderMode.AUTO,
            initialAudioMode = DecoderMode.AUTO
        )
        val factory = NextRenderersFactory(context.applicationContext)
            .setDecoderManager(manager)
        return Setup(factory, manager)
    }

    @Synchronized
    fun attach(player: ExoPlayer, setup: Setup) {
        setup.decoderManager.attach(player)
        managers[player] = setup.decoderManager
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
