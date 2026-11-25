package com.hongguotv.js

import com.hongguotv.core.HttpStatusException
import com.hongguotv.core.NetworkRetry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.EOFException
import java.io.IOException

class NetworkRetryPolicyTest {
    @Test
    fun `only known transient failures are retryable`() {
        assertTrue(NetworkRetry.isRetryable(HttpStatusException(503, null, "service unavailable")))
        assertTrue(NetworkRetry.isRetryable(EOFException("truncated response")))
        assertFalse(NetworkRetry.isRetryable(HttpStatusException(403, null, "forbidden")))
        assertFalse(NetworkRetry.isRetryable(HttpStatusException(503, "30", "retry later")))
        assertFalse(NetworkRetry.isRetryable(IOException("invalid content range")))
    }
}
