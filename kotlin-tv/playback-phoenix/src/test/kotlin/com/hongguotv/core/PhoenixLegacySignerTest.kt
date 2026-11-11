package com.hongguotv.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoenixLegacySignerTest {
    @Test fun legacyFallbackUsesOoiUaQueryShapeAndLegacyHeaders() {
        val signer = PhoenixLegacySigner(clock = { 1_700_000_000_123L }, random = { 0.25 })
        val values = linkedMapOf(
            "iid" to "123456789",
            "device_id" to "987654321",
            "aid" to "8662",
            "version_code" to "73932",
            "version_name" to "7.3.9.32",
            "cdid" to "00000000-0000-0000-0000-000000000000"
        )
        val request = signer.signPlayback(
            "https://api5-normal-sinfonlineb.fqnovel.com/reading/bookapi/video_tab/multi_video_model/v1/",
            values,
            null,
            "OOIUa playback UA"
        )
        val query = request.url.substringAfter('?')
        assertFalse(query.split('&').any { it.substringBefore('=') == "ts" })
        assertTrue(query.contains("_rticket="))
        assertTrue(query.contains("version_code=73932"))
        assertTrue(request.headers.containsKey("x-helios"))
        assertTrue(request.headers.containsKey("x-medusa"))
        assertFalse(request.headers.containsKey("x-ss-stub"))
    }
}
