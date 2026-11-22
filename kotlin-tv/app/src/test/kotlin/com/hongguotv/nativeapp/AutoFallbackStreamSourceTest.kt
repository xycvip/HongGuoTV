// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import com.hongguotv.core.StreamInfo
import com.hongguotv.core.StreamSource
import java.io.IOException
import java.io.InterruptedIOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoFallbackStreamSourceTest {
    private fun source(name: String, action: () -> StreamInfo): StreamSource =
        object : StreamSource {
            override val name: String = name
            override fun stream(id: String, maxQuality: Int): StreamInfo = action()
        }

    private val validInfo = StreamInfo("https://cdn.example/video.mp4", null, "720P")

    @Test
    fun `primary success does not request fallback`() {
        var fallbackCreated = false
        val stream = AutoFallbackStreamSource(
            primary = source("native") { validInfo },
            fallback = { fallbackCreated = true; source("phoenix") { validInfo } }
        )

        assertEquals(validInfo, stream.stream("123", 720))
        assertFalse(fallbackCreated)
    }

    @Test
    fun `failed primary falls back and remembers successful route`() {
        var selected = ""
        var selectedId = ""
        var selectedQuality = 0
        var fallbackCalls = 0
        val stream = AutoFallbackStreamSource(
            primary = source("native") { throw IOException("HTTP 500") },
            fallback = { fallbackCalls++; source("phoenix") { validInfo } },
            onFallbackSuccess = { id, api, quality ->
                selectedId = id
                selected = api
                selectedQuality = quality
            }
        )

        assertEquals(validInfo, stream.stream("123", 720))
        assertEquals(1, fallbackCalls)
        assertEquals("123", selectedId)
        assertEquals("phoenix", selected)
        assertEquals(720, selectedQuality)
    }

    @Test
    fun `fallback notice is scoped to episode and quality and consumed once`() {
        PlaybackApiDebugState.clearFallbackNotice("notice-test", 720)
        PlaybackApiDebugState.rememberFallbackNotice("notice-test", 720, "N → P 已自动切换至线路 Phoenix")

        assertNull(PlaybackApiDebugState.consumeFallbackNotice("other-episode", 720))
        assertNull(PlaybackApiDebugState.consumeFallbackNotice("notice-test", 540))
        assertEquals(
            "N → P 已自动切换至线路 Phoenix",
            PlaybackApiDebugState.consumeFallbackNotice("notice-test", 720)
        )
        assertNull(PlaybackApiDebugState.consumeFallbackNotice("notice-test", 720))
    }

    @Test
    fun `both failures preserve primary error for diagnosis`() {
        val primaryError = IOException("native HTTP 500")
        val fallbackError = IOException("Phoenix empty response")
        val stream = AutoFallbackStreamSource(
            primary = source("native") { throw primaryError },
            fallback = { source("phoenix") { throw fallbackError } }
        )

        val thrown = assertThrows(IOException::class.java) { stream.stream("123", 720) }
        assertSame(fallbackError, thrown.cause)
        assertTrue(thrown.message.orEmpty().contains("native"))
        assertTrue(fallbackError.suppressed.any { it === primaryError })
    }

    @Test
    fun `cancelled request never starts fallback`() {
        var fallbackCreated = false
        val stream = AutoFallbackStreamSource(
            primary = source("native") { throw InterruptedIOException("请求已取消") },
            fallback = { fallbackCreated = true; source("phoenix") { validInfo } }
        )

        assertThrows(InterruptedIOException::class.java) { stream.stream("123", 720) }
        assertFalse(fallbackCreated)
    }
}
