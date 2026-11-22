package com.hongguotv.nativeapp

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream

class ThirdCmsClientTest {
    @Test fun `bounded response reader accepts exact limit`() {
        assertArrayEquals(
            byteArrayOf(1,2,3,4),
            ThirdCmsClient.readBoundedResponse(ByteArrayInputStream(byteArrayOf(1,2,3,4)),4)
        )
    }

    @Test fun `bounded response reader rejects oversized body`() {
        assertNull(ThirdCmsClient.readBoundedResponse(ByteArrayInputStream(byteArrayOf(1,2,3,4,5)),4))
    }
}
