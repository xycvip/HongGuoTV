// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.js

import com.hongguotv.core.EmptyPlaybackResponseException
import com.hongguotv.core.PlaybackQuality
import com.hongguotv.core.StreamInfo
import com.hongguotv.core.StreamSource
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.IOException
import java.io.InterruptedIOException

/**
 * 热更新 JS 播放线路：StreamSource 的 JS 实现。
 *
 * 取流全在 main.js 内完成（vendor 的 spider.play -> video_model + V5 签名），
 * 本类只负责三件事：ID 解析、调脚本、把返回值映射成 StreamInfo。
 *
 * ID 约定（与 adapter 的 chapter.id 一致）：
 *   "hongguo:<seriesId>:<videoId>" 或 "<seriesId>:<videoId>"
 * 纯 videoId 无法取流（vendor 需要 seriesId 构造 player URL），调用方
 * （PlayerSession / 探测）必须传组合 ID。
 */
class HongguoJsStreamSource(
    private val js: HongguoJs,
) : StreamSource {
    override val name: String = "hongguo-js"

    override fun stream(id: String, maxQuality: Int): StreamInfo {
        val cacheKey = "$id|$maxQuality"
        val now = System.currentTimeMillis()
        cache[cacheKey]?.let { entry ->
            if (now - entry.at < CACHE_TTL_MS) return entry.info
        }
        val (seriesId, videoId) = parseEpisodeId(id)
        val task = JSONObject()
            .put("dramaId", seriesId)
            .put("chapter", JSONObject().put("id", "hongguo:$seriesId:$videoId"))
            .put("maxQuality", PlaybackQuality.streamRequest(maxQuality))
        val result = runBlocking { js.call("resolveMedia", task) }
        if (!result.ok) throw mapScriptError(result)
        val data = JSONObject(result.data ?: "{}")
        val url = data.optString("url").trim()
        if (url.isEmpty()) throw IOException("脚本未返回播放地址")
        val key = data.optString("key").trim().takeIf { it.isNotEmpty() }?.let { decodeKey(it) }
        val quality = data.optInt("quality")
        val info = StreamInfo(
            url = url,
            key = key,
            quality = if (quality > 0) "${quality}P" else "",
            mimeType = data.optString("mimeType").ifBlank { "video/mp4" },
        )
        if (cache.size > MAX_ENTRIES) cache.entries.removeIf { now - it.value.at >= CACHE_TTL_MS }
        cache[cacheKey] = Cached(info, now)
        return info
    }

    /** 详情页打开时预取首集地址：用户点播放时直接命中缓存，省掉一次完整取流。 */
    fun prefetch(id: String, maxQuality: Int) {
        runCatching { stream(id, maxQuality) }
    }

    private class Cached(val info: StreamInfo, val at: Long)

    private fun mapScriptError(result: JsCallResult): IOException = when (result.code) {
        "PLAYBACK_UNRESOLVED" -> EmptyPlaybackResponseException(result.error)
        "CANCELLED", "SCRIPT_TIMEOUT" -> InterruptedIOException(result.error)
        else -> IOException(result.error ?: "脚本取流失败（${result.code}）")
    }

    companion object {
        private val DIGITS = Regex("[0-9]{1,30}")

        /** 取流结果缓存 90 秒：足够覆盖详情页预取到用户按下播放的时间，又不会拿到过期的签名地址。 */
        private const val CACHE_TTL_MS = 90 * 1000L
        private const val MAX_ENTRIES = 24

        private val cache = java.util.concurrent.ConcurrentHashMap<String, Cached>()

        /** 脚本热更新后清空，避免旧线路地址残留。 */
        fun clearCache() = cache.clear()

        /**
         * 让某一集的地址缓存立即失效。
         *
         * 播放到一半出现「分段不可用 / HTTP 403 / 416」时，90 秒的缓存会让「重新取流」拿到
         * 同一条已经失效的签名地址，于是重试必然再失败一次。清掉这一条再取，才能换到新签名。
         * 只删当前这一集，不动别的集的预热结果。
         */
        fun invalidate(id: String, maxQuality: Int) {
            cache.remove("$id|$maxQuality")
        }

        fun parseEpisodeId(id: String): Pair<String, String> {
            val cleaned = id.trim().removePrefix("hongguo:")
            val colon = cleaned.lastIndexOf(':')
            if (colon > 0) {
                val series = cleaned.substring(0, colon)
                val video = cleaned.substring(colon + 1)
                if (series.matches(DIGITS) && video.matches(DIGITS)) return series to video
            }
            val under = cleaned.lastIndexOf('_')
            if (under > 0) {
                val series = cleaned.substring(0, under)
                val video = cleaned.substring(under + 1)
                if (series.matches(DIGITS) && video.matches(DIGITS)) return series to video
            }
            throw IOException("无效的分集 ID（需要 剧集ID:分集ID 组合）")
        }

        /** vendor 的 decryptionKey 是 hex 串；容错解析 base64。 */
        fun decodeKey(raw: String): ByteArray? {
            val hex = Regex("^[0-9a-fA-F]{32,}$").matches(raw)
            if (hex) {
                val out = ByteArray(raw.length / 2)
                for (i in out.indices) {
                    out[i] = raw.substring(i * 2, i * 2 + 2).toInt(16).toByte()
                }
                return out
            }
            return runCatching { java.util.Base64.getDecoder().decode(raw) }.getOrNull()
        }
    }
}
