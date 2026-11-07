// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import com.hongguotv.core.RemoteVideo
import java.io.EOFException
import java.io.IOException

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class VideoDataSource(
    private val video: RemoteVideo,
    private val onBytesRead: (Int) -> Unit = {},
    private val diagnostic: (String) -> Unit = {},
    private val diagnosticKey: String = ""
): BaseDataSource(true) {
    private var bytesReadForSession=0L
    private var lastLoggedMiB=0L
    private fun trace(message: String) { runCatching { diagnostic(message) } }
    private var position=0L
    private var remaining=0L
    private var buffer=byteArrayOf()
    private var offset=0
    private var opened=false
    private var uri: Uri?=null

    override fun open(spec: DataSpec): Long {
        transferInitializing(spec)
        if(spec.position<0L) throw IOException("视频读取位置无效")
        if(spec.length<0L && spec.length!=C.LENGTH_UNSET.toLong()) throw IOException("视频读取长度无效")
        if(spec.position>video.total) throw IOException("读取位置超出视频长度")
        uri=spec.uri
        position=spec.position
        remaining=if(spec.length==C.LENGTH_UNSET.toLong()) {
            video.total-position
        } else {
            minOf(spec.length,video.total-position)
        }
        buffer=byteArrayOf()
        offset=0
        opened=true
        bytesReadForSession=0L
        lastLoggedMiB=0L
        trace("DataSource open position=${spec.position} length=${spec.length} uri=${spec.uri}")
        transferStarted(spec)
        return remaining
    }

    override fun read(target: ByteArray,targetOffset: Int,length: Int): Int {
        if(length==0) return 0
        if(length<0 || targetOffset<0 || targetOffset>target.size-length) {
            throw IndexOutOfBoundsException("视频读取缓冲区范围无效")
        }
        if(remaining<=0L) return C.RESULT_END_OF_INPUT
        if(offset>=buffer.size) {
            val request=minOf(512L*1024L,remaining).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            val started=android.os.SystemClock.elapsedRealtime()
            try { buffer=video.read(position,request); trace("DataSource read offset=$position requested=$request returned=${buffer.size} elapsedMs=${android.os.SystemClock.elapsedRealtime()-started}") }
            catch(problem: Throwable) { trace("DataSource read失败 offset=$position requested=$request elapsedMs=${android.os.SystemClock.elapsedRealtime()-started} error=${problem.javaClass.simpleName}: ${problem.message.orEmpty().take(200)}"); throw problem }
            offset=0
        }
        if(buffer.isEmpty()) throw EOFException("视频分段意外结束")
        val available=buffer.size-offset
        if(available<=0) throw EOFException("视频分段数据不完整")
        // Keep the remaining byte count as Long until the final bounded copy size is chosen.
        val count=minOf(length,available,remaining.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        if(count<=0) throw EOFException("视频分段数据不完整")
        buffer.copyInto(target,targetOffset,offset,offset+count)
        offset+=count
        position+=count.toLong()
        remaining-=count.toLong()
        bytesTransferred(count)
        onBytesRead(count)
        bytesReadForSession+=count.toLong()
        val mib=bytesReadForSession/(1024L*1024L)
        if(mib>lastLoggedMiB) { lastLoggedMiB=mib; trace("DataSource进度 bytes=$bytesReadForSession MiB=$mib position=$position") }
        return count
    }

    override fun getUri()=uri

    override fun close() {
        buffer=byteArrayOf()
        offset=0
        uri=null
        if(opened) {
            opened=false
            trace("DataSource close bytesRead=$bytesReadForSession finalPosition=$position")
            transferEnded()
        }
    }
}
