package com.hongguotv.core

import org.junit.Assert.*
import org.junit.Test
import java.net.Socket
import java.net.URI
import java.net.URLEncoder
import java.nio.file.Files

class LibraryTransferServerTest {
    private fun request(server:LibraryTransferServer,target:String="",method:String="GET",body:ByteArray=byteArrayOf(),headers:String=""):ByteArray {
        val uri=URI(server.url)
        return Socket("127.0.0.1",uri.port).use{socket->
            socket.soTimeout=5000
            val path=if(uri.path=="/") target.ifBlank{"/"} else uri.path+target
            val head="$method $path HTTP/1.1\r\nHost: ${uri.authority}\r\n$headers"+(if(method=="POST")"Content-Type: application/octet-stream\r\nContent-Length: ${body.size}\r\n\r\n" else "\r\n")
            socket.getOutputStream().apply{write(head.toByteArray());write(body);flush()}
            socket.getInputStream().readBytes()
        }
    }
    private fun body(response:ByteArray)=response.toString(Charsets.UTF_8).substringAfter("\r\n\r\n")
    private fun encoded(value:String)=URLEncoder.encode(value,"UTF-8")
    private fun freePort()=java.net.ServerSocket(0).use{it.localPort}

    @Test fun `phone page lists public folder and supports upload and download of arbitrary files`() {
        val root=Files.createTempDirectory("hongguotv-files").toFile()
        val nested=java.io.File(root,"子目录").apply{mkdirs()}
        java.io.File(nested,"电视说明.txt").writeText("existing")
        LibraryTransferServer("127.0.0.1",root,port=freePort()).use{server->
            val page=request(server).toString(Charsets.UTF_8)
            assertTrue(page.startsWith("HTTP/1.1 200"))
            assertTrue(page.contains("电视公共文件夹"))
            assertTrue(page.contains("子目录"))
            assertFalse(page.contains("textarea id=\"data\""))
            val uploadPath="/upload?path=${encoded("子目录")}&name=${encoded("来自手机.bin")}"
            val bytes=byteArrayOf(0,1,2,3,0xff.toByte())
            val uploaded=request(server,uploadPath,"POST",bytes).toString(Charsets.UTF_8)
            assertTrue(uploaded.startsWith("HTTP/1.1 200"))
            assertArrayEquals(bytes,java.io.File(nested,"来自手机.bin").readBytes())
            val download=request(server,"/download?path=${encoded("子目录/来自手机.bin")}").toString(Charsets.ISO_8859_1)
            assertTrue(download.startsWith("HTTP/1.1 200"))
            assertArrayEquals(bytes,download.substringAfter("\r\n\r\n").toByteArray(Charsets.ISO_8859_1))
            val nestedPage=request(server,"?path=${encoded("子目录")}").toString(Charsets.UTF_8)
            assertTrue(nestedPage.contains("电视说明.txt"))
        }
        root.deleteRecursively()
    }

    @Test fun `rejects traversal and cross origin requests`() {
        val root=Files.createTempDirectory("hongguotv-files").toFile()
        val outside=Files.createTempFile("outside",".txt").toFile().apply{writeText("secret")}
        LibraryTransferServer("127.0.0.1",root).use{server->
            val traversal=request(server,"?path=${encoded("../${outside.name}")}").toString(Charsets.UTF_8)
            assertTrue(traversal.startsWith("HTTP/1.1 400"))
            val uri=URI(server.url)
            val denied=Socket("127.0.0.1",uri.port).use{socket->
                socket.soTimeout=3000
                socket.getOutputStream().write("GET ${uri.path} HTTP/1.1\r\nHost: ${uri.authority}\r\nOrigin: http://evil.example\r\n\r\n".toByteArray())
                socket.getInputStream().readBytes().toString(Charsets.UTF_8)
            }
            assertTrue(denied.startsWith("HTTP/1.1 403"))
        }
        root.deleteRecursively();outside.delete()
    }

    @Test fun `session expires and closes its listener`() {
        val root=Files.createTempDirectory("hongguotv-files").toFile()
        LibraryTransferServer("127.0.0.1",root,{},50,freePort()).use{server->
            Thread.sleep(120)
            assertTrue(runCatching{request(server)}.isFailure)
        }
        root.deleteRecursively()
    }
}
