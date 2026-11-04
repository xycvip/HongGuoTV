// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import java.io.IOException
import java.util.logging.Logger

/** One independently replaceable playback-address channel. */
internal interface StreamSource {
    val name: String
    fun stream(id: String, maxQuality: Int): StreamInfo
}

/**
 * Sequential fallback: the primary App API always gets the first chance.
 * Secondary channels are touched only after a primary IOException, so a healthy
 * playback path does not pay any extra network round trips.
 */
internal class StreamScheduler(private val sources: List<StreamSource>) {
    private val log=Logger.getLogger("com.hongguotv.core.stream")

    fun stream(id: String,maxQuality: Int): StreamInfo {
        var last: IOException?=null
        for(source in sources) {
            try {
                val result=source.stream(id,maxQuality)
                log.info("playback channel ${source.name} selected for episode $id (${result.quality})")
                return result
            } catch(error: IOException) {
                // A known codec/container incompatibility is not a transient channel outage.
                // Do not hide it behind an unrelated low-resolution URL.
                if(error is UnplayableVideoException || error is UnsupportedVideoFormatException) throw error
                last=error
                log.warning("playback channel ${source.name} failed for episode $id: ${error.message}")
            }
        }
        throw last ?: IOException("没有可用的播放通道")
    }
}

/** Last-resort public MP4 route; it is intentionally low priority and fixed at 360p. */
internal class PublicMp4StreamSource(
    private val http: okhttp3.OkHttpClient,
    private val webUa: String,
    private val site: String
): StreamSource {
    override val name="public-mp4-360p"

    override fun stream(id: String,maxQuality: Int): StreamInfo {
        val url="$site/video/public/$id/360p.mp4"
        val request=okhttp3.Request.Builder().url(url)
            .header("User-Agent",webUa)
            .header("Referer","$site/")
            .header("Range","bytes=0-0")
            .header("Accept-Encoding","identity")
            .build()
        NetworkRetry.execute(request) {
            RequestScope.execute(http.newCall(request)) { response ->
                if(response.code!=206) throw HttpStatusException(
                    response.code,response.header("Retry-After"),
                    "备用播放地址暂不可用（HTTP ${response.code}）"
                )
                if(!isValidPublicMp4RangeResponse(response.code,response.header("Content-Range"))) {
                    throw IOException("备用播放地址分段响应异常")
                }
                val body=response.body ?: throw IOException("备用播放地址为空")
                // 播放器随后依赖 Range 读取；探测阶段也必须确认服务器至少返回了请求的 1 字节。
                if(body.source().readByteArray(1L).size!=1) throw IOException("备用播放地址分段数据为空")
            }
        }
        return StreamInfo(url,null,"360P（备用源）")
    }
}


/** The public MP4 probe must satisfy the same byte-range contract as RemoteVideo.range(). */
internal fun isValidPublicMp4RangeResponse(code: Int, contentRange: String?): Boolean {
    if(code!=206) return false
    val total=Regex("""bytes 0-0/(\d+)""").matchEntire(contentRange.orEmpty())
        ?.groupValues?.get(1)?.toLongOrNull()
    return total!=null && total>0L
}
