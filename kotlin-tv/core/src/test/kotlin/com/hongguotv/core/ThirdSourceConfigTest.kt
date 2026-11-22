package com.hongguotv.core

import org.junit.Assert.*
import org.junit.Test

class ThirdSourceConfigTest {
    @Test fun `only absolute HTTP endpoints with a host are accepted`() {
        val raw="""{"format":"hongguotv.third-sources","version":1,"sources":[
            {"name":"bad scheme","api":"httpx://example.com/api"},
            {"name":"missing host","api":"https:///api"},
            {"name":"credentials","api":"https://user:pass@example.com/api"},
            {"name":"bad port","api":"http://example.com:99999/api"},
            {"name":"lan","api":"http://192.168.1.8:9978/api.php/provide/vod"},
            {"name":"secure","api":"https://example.com/api"}
        ]}"""
        assertEquals(
            listOf(
                BackupThirdSource("lan","http://192.168.1.8:9978/api.php/provide/vod"),
                BackupThirdSource("secure","https://example.com/api")
            ),
            ThirdSourceConfig.decode(raw)
        )
    }
}
