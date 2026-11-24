package com.hongguotv.core

import com.sun.net.httpserver.HttpServer
import okhttp3.OkHttpClient
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import java.net.InetSocketAddress

/** readInto 与 read 必须逐字节等价；明文流不应再复制整块缓冲。 */
class RemoteVideoReadIntoTest {
    private fun be32(v: Int)=byteArrayOf((v ushr 24).toByte(),(v ushr 16).toByte(),(v ushr 8).toByte(),v.toByte())
    private fun box(type: String,body: ByteArray)=be32(body.size+8)+type.toByteArray()+body

    @Test fun plainStreamIsReturnedWithoutCopy() {
        val prepared=MediaCrypto.Prepared(ByteArray(0),emptyList())
        val data=ByteArray(4096){(it%251).toByte()}
        assertSame(data,MediaCrypto.decrypt(data,1024,null,prepared))
    }

    @Test fun psshWindowStillGetsRewritten() {
        val prepared=MediaCrypto.Prepared(ByteArray(0),emptyList(),topLevelPsshOffsets=listOf(100L))
        val data=ByteArray(200){0x50}
        val decrypted=MediaCrypto.decrypt(data,0,null,prepared)
        assertEquals("free",String(decrypted,100,4,Charsets.US_ASCII))
        // 不含 pssh 的窗口走同一份数据，不再复制
        assertSame(data,MediaCrypto.decrypt(data,120,null,prepared))
    }

    @Test fun readIntoMatchesReadAcrossChunkBoundary() {
        val head=box("ftyp","isom0000".toByteArray())+box("moov",ByteArray(0))
        val data=head+ByteArray(4*1024*1024-head.size){(it%239).toByte()}
        val server=HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
        server.createContext("/video") { exchange ->
            val range=exchange.requestHeaders.getFirst("Range")
            val (start,requested)=range.removePrefix("bytes=").split('-').map(String::toInt)
            val end=minOf(data.lastIndex,requested)
            val bytes=data.copyOfRange(start,end+1)
            exchange.responseHeaders.add("Content-Range","bytes $start-$end/${data.size}")
            exchange.sendResponseHeaders(206,bytes.size.toLong()); exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        val client=OkHttpClient()
        try {
            val remote=RemoteVideo(
                client,
                StreamInfo("http://127.0.0.1:${server.address.port}/video",null,"720P"),
                aheadBytes=0
            ).warm()
            val cases=listOf(
                0L to 65536,
                65536L to 128*1024,
                (2*1024*1024-65536).toLong() to 128*1024,   // 跨块
                (3*1024*1024+12345).toLong() to 512*1024,
                (data.size-1000).toLong() to 1000,          // 尾部
            )
            val reused=ByteArray(512*1024+64)
            for((start,length) in cases) {
                val expected=remote.read(start,length)
                val copied=remote.readInto(reused,64,start,length)
                assertEquals("len@$start",expected.size,copied)
                assertArrayEquals("data@$start",expected,reused.copyOfRange(64,64+copied))
            }
            // 同一块缓冲连续复用，第二次读必须完整覆盖上次的残留
            assertEquals(4096,remote.readInto(reused,0,1000,4096))
            assertArrayEquals(data.copyOfRange(1000,5096),reused.copyOfRange(0,4096))
            assertEquals(0,remote.readInto(reused,0,data.size.toLong(),16))
            remote.close()
        } finally { server.stop(0); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdownNow() }
    }
}
