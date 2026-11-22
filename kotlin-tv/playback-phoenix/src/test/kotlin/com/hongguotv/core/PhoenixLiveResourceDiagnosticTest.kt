// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import okhttp3.OkHttpClient
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Opt-in live integration diagnostic.
 * Set PHOENIX_VIDEO_ID to a real episode/video ID when dispatching the workflow.
 * This deliberately calls the same registration, signing, request and resolver path
 * used by PhoenixPlaybackSource; it does not use fixture JSON.
 */
class PhoenixLiveResourceDiagnosticTest {
    @Test
    fun fetchAndFilterRealPhoenixResources() {
        val videoId = System.getenv("PHOENIX_VIDEO_ID")?.trim().orEmpty()
        org.junit.Assume.assumeTrue(
            "Set workflow_dispatch input phoenix_video_id to run the live Phoenix diagnostic",
            videoId.matches(Regex("[0-9]{1,30}"))
        )

        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .callTimeout(90, TimeUnit.SECONDS)
            .build()
        val source = PhoenixPlaybackSource(
            http = client,
            session = PhoenixPlaybackDeviceSession(),
            onDiagnostic = { println("[PHOENIX-LIVE] $it") }
        )

        try {
            val selected = source.resolve(videoId, 1080)
            val host = runCatching { java.net.URI(selected.url).host.orEmpty() }.getOrDefault("")
            println("[PHOENIX-LIVE] 最终结果：成功选中资源，清晰度=${selected.quality}，MIME=${selected.mimeType}，host=$host；完整URL已隐藏")
        } catch (error: Throwable) {
            System.err.println("[PHOENIX-LIVE] 真实接口诊断失败，异常类型=${error.javaClass.simpleName}")
            throw error
        } finally {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }
}
