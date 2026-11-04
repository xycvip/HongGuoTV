package com.hongguotv.core

import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.EOFException
import java.io.IOException
import java.net.SocketException

class NetworkRetryTest {
    private val get=Request.Builder().url("https://example.com/video.mp4").build()

    @Test fun retriesTransientGetOnceAndReturnsSuccessfulResult() {
        var attempts=0
        val result=NetworkRetry.execute(get) {
            attempts++
            if(attempts==1) throw EOFException("unexpected end of stream")
            "ok"
        }
        assertEquals("ok",result)
        assertEquals(2,attempts)
    }

    @Test fun doesNotRetryPostRequests() {
        val post=Request.Builder().url("https://example.com/player").post("{}".toRequestBody()).build()
        var attempts=0
        try {
            NetworkRetry.execute(post) {
                attempts++
                throw SocketException("connection reset")
            }
        } catch(_: IOException) {}
        assertEquals(1,attempts)
    }

    @Test fun retriesTransientHttpStatusOnlyWhenRetryAfterIsAbsent() {
        var attempts=0
        val result=NetworkRetry.execute(get) {
            attempts++
            if(attempts==1) throw HttpStatusException(503,null,"service unavailable")
            "ok"
        }
        assertEquals("ok",result)
        assertEquals(2,attempts)

        attempts=0
        try {
            NetworkRetry.execute(get) {
                attempts++
                throw HttpStatusException(503,"5","service unavailable")
            }
        } catch(_: IOException) {}
        assertEquals(1,attempts)
    }
}
