package com.hongguotv.js

import com.hongguotv.core.HttpStatusException
import com.hongguotv.core.NetworkRetry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.EOFException
import java.io.IOException
import okhttp3.Request

class NetworkRetryPolicyTest {
    @Test
    fun `only known transient failures are retryable`() {
        assertTrue(NetworkRetry.isRetryable(HttpStatusException(503, null, "service unavailable")))
        assertTrue(NetworkRetry.isRetryable(EOFException("truncated response")))
        assertFalse(NetworkRetry.isRetryable(HttpStatusException(403, null, "forbidden")))
        assertFalse(NetworkRetry.isRetryable(HttpStatusException(503, "30", "retry later")))
        assertFalse(NetworkRetry.isRetryable(IOException("invalid content range")))
    }
    @Test
    fun `caller can cap total attempts`() {
        val request = Request.Builder().url("https://example.com/").build()
        var attempts = 0
        try {
            NetworkRetry.execute(request, maxAttempts = 2) {
                attempts++
                throw EOFException("temporary truncated response")
            }
            throw AssertionError("retry should ultimately fail")
        } catch (_: EOFException) {
            assertEquals(2, attempts)
        }
    }
}
