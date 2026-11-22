package com.hongguotv.core

import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

class MemoryCookiesTest {
    @Test fun `cookies obey path matching`() {
        val jar=MemoryCookies()
        val origin="https://example.com/".toHttpUrl()
        val cookie=Cookie.Builder().name("sid").value("1").hostOnlyDomain("example.com").path("/account").build()
        jar.saveFromResponse(origin,listOf(cookie))
        assertEquals(listOf(cookie),jar.loadForRequest("https://example.com/account/profile".toHttpUrl()))
        assertTrue(jar.loadForRequest("https://example.com/video".toHttpUrl()).isEmpty())
    }

    @Test fun `secure cookies are never sent over cleartext`() {
        val jar=MemoryCookies()
        val origin="https://example.com/".toHttpUrl()
        val cookie=Cookie.Builder().name("sid").value("1").hostOnlyDomain("example.com").path("/").secure().build()
        jar.saveFromResponse(origin,listOf(cookie))
        assertEquals(listOf(cookie),jar.loadForRequest(origin))
        assertTrue(jar.loadForRequest("http://example.com/".toHttpUrl()).isEmpty())
    }
}
