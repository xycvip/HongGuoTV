// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import com.hongguotv.core.StreamInfo
import com.hongguotv.core.StreamSource
import java.io.IOException
import java.io.InterruptedIOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
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
        var fallbackCalls = 0
        val stream = AutoFallbackStreamSource(
            primary = source("native") { throw IOException("HTTP 500") },
            fallback = { fallbackCalls++; source("phoenix") { validInfo } },
            onFallbackSuccess = { selected = it }
        )

        assertEquals(validInfo, stream.stream("123", 720))
        assertEquals(1, fallbackCalls)
        assertEquals("phoenix", selected)
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
