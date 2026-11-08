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

    @Test fun ttEncryptUsesPhoenixFixedPrefixAndBlockPadding() {
        val encrypted = PhoenixDeviceRegister.ttEncrypt("phoenix-test".toByteArray())
        assertEquals("746305100000", encrypted.copyOfRange(0, 6).hex())
        assertEquals(
            "3176c9b6d71579d7e82179c9e7cbde787b54d541bad3b15f2e8fed92293fa25a",
            encrypted.copyOfRange(6, 38).hex()
        )
        assertEquals(0, (encrypted.size - 38) % 16)
    }

    @Test fun phoenixDeviceRegistrationUsesCurrentPhoenixEndpointAndQuery() {
        val url = PhoenixDeviceRegister.url(VendorConstants.device, 1794050000000L)
        assertTrue(url.startsWith("https://log5-applog-lq.fqnovel.com/service/2/device_register/"))
        assertTrue(url.contains("version_code=71332"))
        assertTrue(url.contains("version_name=7.1.3.32"))
        assertTrue(url.contains("device_type=25053RT47C"))
        assertTrue(url.contains("device_brand=Redmi"))
        assertTrue(url.contains("os_api=36"))
        assertTrue(url.contains("os_version=16"))
        assertTrue(url.contains("resolution=1280*2772"))
        assertTrue(url.contains("dpi=520"))
        assertTrue(url.contains("_rticket=1794050000000"))
        assertFalse(url.contains("cronet_version="))
    }
}
