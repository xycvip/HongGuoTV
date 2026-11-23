// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/** A deterministic source failure, unlike a transient transport failure. */
class PlaybackSourceException(val reason: Reason, val upstreamCode: Int? = null): IOException(
    when (reason) {
        Reason.REJECTED -> "上游拒绝播放请求（错误码 $upstreamCode），请稍后手动重试"
        Reason.HTTP_REJECTED -> "播放请求被拒绝（HTTP $upstreamCode），请稍后手动重试"
        Reason.INVALID_RESPONSE -> "播放信息格式异常，请稍后手动重试"
        Reason.NO_PLAYABLE_SOURCE -> "没有可播放的视频资源，可尝试其他清晰度或剧集"
    }
) {
    enum class Reason { REJECTED, HTTP_REJECTED, INVALID_RESPONSE, NO_PLAYABLE_SOURCE }
}

/** Pure parsing only: no device registration, requests or entitlement changes. */
internal object PlaybackModels {
    fun checkStatus(data: JSONObject) {
        val code = data.optInt("Code", data.optInt("code", 0))
        if (code != 0) throw PlaybackSourceException(PlaybackSourceException.Reason.REJECTED, code)
    }
    fun objectValue(value: Any?): JSONObject? = when(value) {
        is JSONObject -> value
        is String -> runCatching { JSONObject(value) }.getOrNull()
        else -> null
    }
    fun model(data: JSONObject, id: String): JSONObject? {
        // Never silently play a different episode from a batch response.
        val entry = data.optJSONObject(id) ?: data.takeIf { it.has("video_model") }
        return objectValue(entry?.opt("video_model"))
    }
    fun fallback(model: JSONObject): String? {
        val raw = model.opt("fallback_api")
        val value = when(raw) {
            is JSONArray -> raw.optString(0)
            else -> objectValue(raw)?.str("fallback_api") ?: (raw as? String).orEmpty()
        }
        return safeUrl(value, FALLBACK_HOSTS)
    }
    private fun url(raw: String): String? = safeUrl(raw, MEDIA_HOSTS)

    private fun safeUrl(raw: String, allowedDomains: Set<String>): String? {
        val parsed = cleanUrl(raw).trim().toHttpUrlOrNull() ?: return null
        val host = parsed.host.lowercase()
        if (parsed.scheme != "https" || parsed.port != 443 ||
            parsed.username.isNotEmpty() || parsed.password.isNotEmpty() ||
            allowedDomains.none { host == it || host.endsWith(".$it") }
        ) return null
        return parsed.toString()
    }

    private val MEDIA_HOSTS = setOf("qznovelvod.com", "fqnovelvod.com", "douyinvod.com")
    private val FALLBACK_HOSTS = setOf("fqnovel.com", "snssdk.com")

    fun candidates(model: JSONObject): List<StreamInfo> = renditions(model).map {
        StreamInfo(it.url, it.key, it.quality.toString())
    }

    fun renditions(model: JSONObject): List<Rendition> {
        val list = model.opt("video_list")
        val entries = when(list) {
            is JSONObject -> list.keys().asSequence().mapNotNull { key -> list.optJSONObject(key)?.let { key to it } }.toList()
            is JSONArray -> list.objects().map { "" to it }
            else -> emptyList()
        }
        return entries.mapNotNull { (name, entry) ->
            // A malformed variant must not discard all the other variants.
            runCatching {
                val codec = listOf(entry.str("codec_type"), entry.str("gear_des_key"), entry.optJSONObject("video_meta")?.str("codec_type").orEmpty()).joinToString(" ").lowercase()
                if (codec.contains("bytevc2") || Regex("(^|[^a-z0-9])bvc2([^a-z0-9]|$)").containsMatchIn(codec)) return@runCatching null
                val seed = model.str("key_seed").takeIf { it.isNotEmpty() }?.let(::decode64)
                val encryption = entry.optJSONObject("encrypt_info")
                val key = encryption?.str("spade_a").orEmpty().ifEmpty { entry.str("spade_a") }
                    .takeIf { it.isNotEmpty() }?.let(MediaCrypto::deriveKey)
                if (encryption != null && key == null && encryption.opt("encrypt") !in listOf(false, 0, "0", "false")) return@runCatching null
                val rawUrls = buildList {
                    listOf("main_url", "backup_url", "backup_url_1", "backup_url_2", "backup_url_3", "play_url")
                        .forEach { field -> entry.str(field).takeIf { it.isNotBlank() }?.let(::add) }
                    listOf("backup_url_list", "url_list").forEach { field ->
                        entry.optJSONArray(field)?.let { values ->
                            for (index in 0 until values.length()) {
                                values.optString(index).takeIf { it.isNotBlank() }?.let(::add)
                            }
                        }
                    }
                }
                val resolved = rawUrls.firstNotNullOfOrNull { value ->
                    runCatching {
                        val raw = cleanUrl(value)
                        if (raw.isEmpty()) null else url(raw) ?: url(
                            if (seed != null) MediaCrypto.decryptUrl(raw, seed)
                            else String(decode64(raw), Charsets.UTF_8)
                        )
                    }.getOrNull()
                } ?: return@runCatching null
                val meta=entry.optJSONObject("video_meta")
                val width=entry.optInt("vwidth",meta?.optInt("vwidth",0) ?: 0); val height=entry.optInt("vheight",meta?.optInt("vheight",0) ?: 0)
                val dimensions=if(width>0 && height>0) minOf(width,height).toString() else ""
                val quality=qualityNumber(entry.str("quality_desc").ifEmpty { meta?.str("definition").orEmpty() }.ifEmpty { dimensions.ifEmpty { entry.str("height").ifEmpty { entry.str("vheight").ifEmpty { entry.str("quality").ifEmpty { name } } } } })
                val label = entry.str("codec_type").ifEmpty { meta?.str("codec_type").orEmpty() }.lowercase()
                Rendition(quality, label, width.takeIf { it > 0 }, height.takeIf { it > 0 }, resolved, key)
            }.getOrNull()
        }
    }
    fun pick(rows: List<StreamInfo>, maxQuality: Int): StreamInfo {
        val known=rows.filter { it.quality.toInt()>0 }.sortedByDescending { it.quality.toInt() }
        val chosen=known.firstOrNull { it.quality.toInt()<=maxQuality } ?: known.lastOrNull() ?: rows.firstOrNull()
            ?: throw PlaybackSourceException(PlaybackSourceException.Reason.NO_PLAYABLE_SOURCE)
        val q=chosen.quality.toInt()
        return chosen.copy(quality=if(q>0) "${q}P"+(if(q>maxQuality) "（兼容资源）" else "") else "自动")
    }
}


/** One playable rendition; width/height are the coded dimensions when the API reports them. */
data class Rendition(
    val quality: Int,
    val codec: String,
    val width: Int?,
    val height: Int?,
    val url: String,
    val key: ByteArray?,
) {
    fun toStreamInfo() = StreamInfo(url, key, if (quality > 0) "${quality}P" else "自动")
}

/** Resolution-first rendition selection, matching N3urda's current stream ladder. */
class StreamLadder(val renditions: List<Rendition>) {
    private val better =
        compareBy<Rendition> { it.quality }.thenBy { if (it.codec in AVC_LABELS) 1 else 0 }

    fun select(maxQuality: Int, supported: (Rendition) -> Boolean = { true }): Rendition? {
        val pool = renditions.filter(supported).ifEmpty { renditions }
        return pool.filter { it.quality in 1..maxQuality }.maxWithOrNull(better)
            ?: pool.filter { it.quality <= 0 }.maxWithOrNull(better)
            ?: pool.minWithOrNull(better)
    }

    fun playbackOrder(
        maxQuality: Int,
        supported: (Rendition) -> Boolean = { true },
    ): List<Rendition> {
        val pick = select(maxQuality, supported) ?: return emptyList()
        val alternates = renditions
            .filter { it !== pick && it.quality <= pick.quality }
            .sortedWith(better.reversed())
        return listOf(pick) + alternates
    }

    companion object {
        private val AVC_LABELS = setOf("h264", "avc", "avc1", "avc3")
    }
}

private fun qualityNumber(value: String): Int =
    Regex("2160|1440|1080|720|576|540|480|360").find(value)?.value?.toInt()
        ?: mapOf("1920" to 1080, "1280" to 720, "1024" to 576, "854" to 480, "640" to 360)[value]
        ?: value.toIntOrNull() ?: 0
