package com.hongguotv.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PublicMp4StreamSourceTest {
    @Test fun `range probe accepts only a valid single-byte partial response`() {
        assertTrue(isValidPublicMp4RangeResponse(206, "bytes 0-0/1024"))
        assertTrue(isValidPublicMp4RangeResponse(206, "bytes 0-0/1"))
    }

    @Test fun `range probe rejects full responses and malformed content ranges`() {
        assertFalse(isValidPublicMp4RangeResponse(200, "bytes 0-0/1024"))
        assertFalse(isValidPublicMp4RangeResponse(206, null))
        assertFalse(isValidPublicMp4RangeResponse(206, "bytes 0-1/1024"))
        assertFalse(isValidPublicMp4RangeResponse(206, "bytes 0-0/*"))
        assertFalse(isValidPublicMp4RangeResponse(206, "bytes 0-0/0"))
    }
}
