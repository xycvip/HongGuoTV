package com.hongguotv.js

import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.UnknownHostException

class JsEngineHostUrlSafetyTest {
    @Test
    fun `rejects local and private literal URLs`() {
        listOf(
            "http://example.com/", "https://localhost/", "https://sub.localhost/",
            "https://router.local/", "https://service.internal/", "https://127.0.0.1/",
            "https://10.1.2.3/", "https://172.16.0.1/", "https://192.168.1.1/",
            "https://100.64.0.1/", "https://192.88.99.1/", "https://[2001:db8::1]/", "https://[::1]/", "https://[fc00::1]/",
            "https://[fe80::1]/", "https://user:pass@example.com/"
        ).forEach { assertFalse("must reject $it", JsEngineHost.isAllowedScriptUrl(it)) }
    }

    @Test
    fun `allows public HTTPS hostname and public IPv4 literal`() {
        assertTrue(JsEngineHost.isAllowedScriptUrl("https://example.com/path"))
        assertTrue(JsEngineHost.isAllowedScriptUrl("https://8.8.8.8/"))
    }

    @Test
    fun `DNS answer rejects private and mixed public private addresses`() {
        val public = InetAddress.getByName("8.8.8.8")
        val private = InetAddress.getByName("192.168.1.10")
        assertSame(public, JsEngineHost.validatePublicDns("example.com", listOf(public)).single())
        listOf(listOf(private), listOf(public, private)).forEach { addresses ->
            try {
                JsEngineHost.validatePublicDns("example.com", addresses)
                throw AssertionError("private DNS result should be rejected")
            } catch (_: UnknownHostException) {
                // Expected.
            }
        }
    }
}
