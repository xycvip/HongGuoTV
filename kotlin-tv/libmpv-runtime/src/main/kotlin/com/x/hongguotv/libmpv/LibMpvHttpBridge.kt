// SPDX-License-Identifier: GPL-3.0-only
package com.x.hongguotv.libmpv

import com.hongguotv.core.RemoteVideo
import java.io.BufferedReader
import java.io.Closeable
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Feeds libmpv with the app's already-resolved/decrypted RemoteVideo stream.
 *
 * The upstream content is encrypted and is not a plain URL that libmpv can open
 * directly. This loopback-only HTTP range bridge exposes the decrypted byte stream
 * without sending another request to the source server.
 */
internal class LibMpvHttpBridge(
    private val video: RemoteVideo,
    private val diagnostic: (String) -> Unit = {}
) : Closeable {
    private val closed = AtomicBoolean(false)
    private val workers = Executors.newCachedThreadPool { task ->
        Thread(task, "LibMpv-Bridge-Worker").apply { isDaemon = true }
    }
    private var server: ServerSocket? = null
    private var acceptThread: Thread? = null

    val url: String
        get() = "http://127.0.0.1:${server?.localPort ?: error("bridge not started")}/stream.mp4"

    fun start(): String {
        check(server == null) { "bridge already started" }
        check(video.total > 0L) { "RemoteVideo must be prepared before libmpv starts" }
        val socket = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        socket.soTimeout = 1000
        server = socket
        acceptThread = Thread({
            while (!closed.get()) {
                try {
                    val client = socket.accept()
                    workers.execute { serve(client) }
                } catch (_: java.net.SocketTimeoutException) {
                    // Re-check the close flag.
                } catch (problem: Exception) {
                    if (!closed.get()) diagnostic("bridge accept failed: ${problem.javaClass.simpleName}: ${problem.message}")
                }
            }
        }, "LibMpv-Bridge-Accept").apply { isDaemon = true; start() }
        diagnostic("loopback bridge ready port=${socket.localPort} size=${video.total}")
        return url
    }

    private fun serve(socket: Socket) {
        socket.use { client ->
            try {
                client.soTimeout = 15000
                val reader = BufferedReader(InputStreamReader(client.getInputStream(), StandardCharsets.US_ASCII))
                val requestLine = reader.readLine() ?: return
                val parts = requestLine.split(' ')
                if (parts.size < 2) return
                val method = parts[0].uppercase()
                var rangeHeader: String? = null
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                    val split = line.indexOf(':')
                    if (split > 0 && line.substring(0, split).equals("Range", true)) {
                        rangeHeader = line.substring(split + 1).trim()
                    }
                }
                if (method != "GET" && method != "HEAD") {
                    writeError(client.getOutputStream(), 405, "Method Not Allowed")
                    return
                }

                val total = video.total
                if (total <= 0L) {
                    writeError(client.getOutputStream(), 503, "Stream Not Ready")
                    return
                }
                val requested = rangeHeader?.let { RANGE.matchEntire(it) }
                val start: Long
                val end: Long
                val partial: Boolean
                if (rangeHeader == null) {
                    start = 0L
                    end = total - 1L
                    partial = false
                } else if (requested != null) {
                    val first = requested.groupValues[1].toLongOrNull()
                    val last = requested.groupValues[2].takeIf { it.isNotEmpty() }?.toLongOrNull()
                    if (first == null || first >= total) {
                        writeRangeError(client.getOutputStream(), total)
                        return
                    }
                    start = first
                    end = (last ?: (total - 1L)).coerceAtMost(total - 1L)
                    if (end < start) {
                        writeRangeError(client.getOutputStream(), total)
                        return
                    }
                    partial = true
                } else {
                    writeRangeError(client.getOutputStream(), total)
                    return
                }

                val length = end - start + 1L
                val output = client.getOutputStream()
                val headers = buildString {
                    append(if (partial) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
                    append("Content-Type: video/mp4\r\n")
                    append("Accept-Ranges: bytes\r\n")
                    append("Content-Length: ").append(length).append("\r\n")
                    if (partial) append("Content-Range: bytes ").append(start).append('-').append(end).append('/').append(total).append("\r\n")
                    append("Connection: close\r\n\r\n")
                }
                output.write(headers.toByteArray(StandardCharsets.US_ASCII))
                output.flush()
                if (method == "HEAD") return

                var position = start
                while (position <= end && !closed.get()) {
                    val count = minOf(CHUNK_BYTES.toLong(), end - position + 1L).toInt()
                    val bytes = video.read(position, count)
                    if (bytes.isEmpty()) break
                    output.write(bytes)
                    position += bytes.size
                }
                output.flush()
                diagnostic("bridge served method=$method range=$start-$end sent=${position - start}")
            } catch (problem: Exception) {
                if (!closed.get()) diagnostic("bridge request failed: ${problem.javaClass.simpleName}: ${problem.message}")
            }
        }
    }

    private fun writeError(output: OutputStream, status: Int, reason: String) {
        val body = "$status $reason".toByteArray(StandardCharsets.US_ASCII)
        output.write("HTTP/1.1 $status $reason\r\nConnection: close\r\nContent-Length: ${body.size}\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
        output.write(body)
        output.flush()
    }

    private fun writeRangeError(output: OutputStream, total: Long) {
        output.write("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */$total\r\nConnection: close\r\nContent-Length: 0\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
        output.flush()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { server?.close() }
        workers.shutdownNow()
        runCatching { workers.awaitTermination(2, TimeUnit.SECONDS) }
        server = null
        diagnostic("loopback bridge closed")
    }

    companion object {
        private const val CHUNK_BYTES = 512 * 1024
        private val RANGE = Regex("bytes=(\\d+)-(\\d*)")
    }
}
