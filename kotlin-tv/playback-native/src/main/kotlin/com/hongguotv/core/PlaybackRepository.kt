// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import okhttp3.OkHttpClient
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * Native N playback adapter.
 *
 * The upstream request, V5 signing, registered identity and model parsing follow
 * N3urda/hongguoTV's active MobilePlayback path. App quality-mode and MP4
 * streaming behavior remain local adapter responsibilities.
 */
class PlaybackRepository(
    private val http: OkHttpClient,
    private val clock: () -> Long,
    private val playbackSession: PlaybackDeviceSession,
) {
    fun stream(id: String, maxQuality: Int = 1080): StreamInfo {
        require(id.matches(Regex("[0-9]{1,30}")))
        return RequestScope.withDeadline(
            System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
        ) {
            val identity = playbackSession.profile(http)
            val ladder = MobilePlayback.ladder(
                id = id,
                device = identity.values,
                http = http,
                signer = MobilePlayback.signer,
                now = clock(),
            )
            val portrait = ladder.renditions.firstNotNullOfOrNull { row ->
                if (row.width != null && row.height != null) row.height > row.width else null
            }
            val limit = PlaybackQualityMode.limit(maxQuality, portrait)
            val selected = if (maxQuality == PlaybackQualityMode.AUTO_COMPAT) {
                ladder.renditions
                    .filter { it.codec in setOf("h264", "avc", "avc1", "avc3") }
                    .maxWithOrNull(compareBy<Rendition> { it.quality })
                    ?: ladder.select(limit)
            } else {
                ladder.select(limit)
            } ?: throw UnplayableVideoException("本集片源暂不兼容，请播放下一集")

            val urlPath = runCatching {
                URI(selected.url).path.orEmpty().lowercase(java.util.Locale.ROOT)
            }.getOrDefault("")
            if (urlPath.endsWith(".m3u8") || urlPath.endsWith(".mpd") ||
                listOf(".ts", ".webm", ".mkv", ".avi").any(urlPath::endsWith)
            ) {
                throw UnsupportedVideoFormatException("当前片源容器暂不支持，现有播放链路需要 MP4 资源")
            }

            val info = selected.toStreamInfo()
            if (selected.quality > limit) info.copy(quality = info.quality + "（兼容资源）") else info
        }
    }
}
