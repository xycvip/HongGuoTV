package com.hongguotv.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoenixPlayerSignerTest {
    @Test fun emitsPhoenixPlayerHeadersWithoutLegacyHeliosMedusa() {
        val body = """{"video_id":"7701336636495252504","content_type":1,"biz_param":{"need_all_video_definition":true,"video_platform":3}}"""
            .toByteArray(Charsets.UTF_8)
        val headers = PhoenixPlayerSigner.headers(
            query = "aid=8662&device_id=123456789&iid=987654321&_rticket=1794050000000",
            body = body,
            deviceId = "123456789",
            nowMs = 1794050000000L,
            userAgent = "PhoenixReadApi test UA"
        )
        assertEquals(52, headers.getValue("X-Gorgon").length)
        assertEquals(digest("MD5", body).hex().uppercase(), headers.getValue("X-SS-STUB"))
        assertTrue(decode64(headers.getValue("X-Argus")).size > 32)
        assertTrue(decode64(headers.getValue("X-Ladon")).size > 16)
        assertTrue(headers.containsKey("X-Khronos"))
        assertTrue(headers.containsKey("X-SS-Req-Ticket"))
        assertFalse(headers.containsKey("X-Helios"))
        assertFalse(headers.containsKey("X-Medusa"))
    }

    @Test fun ttEncryptMatchesOoiUaMagicAndRandomEnvelope() {
        val random = ByteArray(32) { it.toByte() }
        val encrypted = PhoenixDeviceRegister.ttEncrypt("phoenix-test".toByteArray(), random)
        assertEquals("746305100000", encrypted.copyOfRange(0, 6).hex())
        assertEquals(random.hex(), encrypted.copyOfRange(6, 38).hex())
        assertEquals(0, (encrypted.size - 38) % 16)
    }

    @Test fun registrationUsesOoiUaLog5Endpoint() {
        val url = PhoenixDeviceRegister.registrationUrlForTest()
        assertTrue(url.startsWith("https://log5-applog-lf.fqnovel.com/service/2/device_register/"))
        assertTrue(url.contains("cronet_version=8d40f833_2026-03-03"))
        assertTrue(url.contains("ttnet_version=4.2.243.31-douyin"))
        assertTrue(url.contains("use_store_region_cookie=1"))
    }
}
