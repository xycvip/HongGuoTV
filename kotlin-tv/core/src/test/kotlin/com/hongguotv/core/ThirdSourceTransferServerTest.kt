package com.hongguotv.core

import org.junit.Assert.*
import org.junit.Test
import java.net.Socket
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ThirdSourceTransferServerTest {
    private val sources=listOf(BackupThirdSource("测试源","https://example.com/api.php/provide/vod"))
    private val config=ThirdSourceConfig.encode(sources)
    private fun download(server: ThirdSourceTransferServer): String {
        val uri=URI(server.url)
        return Socket("127.0.0.1",uri.port).use { socket ->
            socket.soTimeout=3000
            socket.getOutputStream().write("GET ${uri.path}/download HTTP/1.1\r\nHost: ${uri.authority}\r\n\r\n".toByteArray())
            socket.getInputStream().readBytes().toString(Charsets.UTF_8)
        }
    }

    @Test fun `concurrent connections all close`() {
        val server=ThirdSourceTransferServer("127.0.0.1",config,{ fail("must not import") })
        val uri=URI(server.url)
        val sockets=(1..3).map {
            Socket("127.0.0.1",uri.port).apply {
                soTimeout=1500
                getOutputStream().write("POST /".toByteArray())
                getOutputStream().flush()
            }
        }
        Thread.sleep(100)
        server.close()
        sockets.forEach { socket -> socket.use { assertEquals(-1,it.getInputStream().read()) } }
    }

    @Test fun `concurrent downloads consume one use only once`() {
        val server=ThirdSourceTransferServer("127.0.0.1",config,{ fail("must not import") })
        val start=CountDownLatch(1)
        val results=java.util.Collections.synchronizedList(mutableListOf<String>())
        val workers=(1..2).map {
            Thread { start.await(); results += download(server) }.apply { start() }
        }
        start.countDown()
        workers.forEach { it.join(3000) }
        assertEquals(2,results.size)
        assertEquals(1,results.count { it.startsWith("HTTP/1.1 200") })
        assertEquals(1,results.count { it.startsWith("HTTP/1.1 410") })
        server.close()
    }

    @Test fun `valid config is imported once`() {
        val latch=CountDownLatch(1)
        var received: List<BackupThirdSource>?=null
        val server=ThirdSourceTransferServer("127.0.0.1",config,{ received=it; latch.countDown() })
        val uri=URI(server.url)
        Socket("127.0.0.1",uri.port).use { socket ->
            socket.soTimeout=3000
            val body=config.toByteArray(Charsets.UTF_8)
            val header="POST ${uri.path}/import HTTP/1.1\r\nHost: ${uri.authority}\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\n\r\n"
            socket.getOutputStream().write(header.toByteArray(Charsets.US_ASCII)+body)
            assertTrue(socket.getInputStream().readBytes().toString(Charsets.UTF_8).startsWith("HTTP/1.1 200"))
        }
        assertTrue(latch.await(2,TimeUnit.SECONDS))
        assertEquals(sources,received)
        server.close()
    }
}
