// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

data class ActorRankEntry(
    val userId: String, val actorId: String, val name: String, val avatar: String,
    val popularity: String, val intro: String, val details: List<String>, val position: Int,
    val changeType: Int = 0, val changePlaces: Int = 0,
)
data class ActorRankPage(
    val items: List<ActorRankEntry>, val nextOffset: Int, val hasMore: Boolean,
    val sessionId: String, val version: String,
)
fun ActorRankEntry.toSeries() = Series(
    id = actorId.ifBlank { userId }, title = name, cover = avatar, description = intro,
    badge = popularity, tags = details.joinToString(" · "),
)

internal object ActorRankParser {
    fun parse(raw: String, requestedOffset: Int): ActorRankPage {
        try {
            val root = JSONObject(raw)
            val code = root.opt("code")?.toString().orEmpty()
            if (code.isNotBlank() && code != "0") {
                val message = root.optString("message").ifBlank { root.optString("msg") }
                throw IOException("演员榜接口返回错误（code=" + code + (if (message.isNotBlank()) ", $message" else "") + "）")
            }
            val data = root.optJSONObject("data")
                ?: throw IOException("演员榜响应缺少 data（" + root.optString("message").ifBlank { root.optString("msg") } + "）")

            // 不同演员子榜的 cell/change 响应层级并不完全一致。
            // 优先兼容 cell_view.cell_data，同时递归识别直接列表及嵌套用户列表。
            val candidates = mutableListOf<JSONObject>()
            // 星光榜接口使用精简响应：data.cell / has_next / rank_session；
            // 剧星榜旧响应则使用 data.cell_view.cell_data / has_more / session_id。
            // cell 可能是数组、对象，部分网关还会把内层 JSON 序列化成字符串。
            collectActorNodes(data.opt("cell"), candidates)
            collectActorNodes(data.opt("cell_view"), candidates)
            collectActorNodes(data, candidates)
            val items = mutableListOf<ActorRankEntry>()
            val seen = hashSetOf<String>()
            for (node in candidates) {
                val info = node.optJSONObject("user_info") ?: node.optJSONObject("user") ?: node
                val base = info.optJSONObject("base_info") ?: info.optJSONObject("user_base_info") ?: info
                // 官方结构：user_id 位于 user_info，演员作品查询 ID 位于 base_info.actor_id_str。
                // 不能把 user_id 错当成 base_info 的字段，否则星光榜会把整页演员全部过滤掉。
                val userId = firstString(info, "user_id", "uid", "userId", "id")
                    .ifBlank { firstString(base, "user_id", "uid", "userId", "id") }
                // 红果不同版本会把昵称/演员 ID 放在 base_info 或 user_info；
                // 不能只读 base_info，否则即使 user_id 已识别，也会因 name 为空过滤整页演员。
                val actorId = firstString(base, "actor_id_str", "actor_id", "star_id", "author_id")
                    .ifBlank { firstString(info, "actor_id_str", "actor_id", "star_id", "author_id") }
                // 星光榜真实 cell 的 user_name 可能直接放在 user_info，
                // 而剧星榜常见结构则放在 base_info；两层都要读取。
                val name = firstString(base, "user_name", "nickname", "name", "nick_name")
                    .ifBlank { firstString(info, "user_name", "nickname", "name", "nick_name") }
                    .ifBlank { firstString(node, "user_name", "nickname", "name", "nick_name") }
                    .ifBlank { firstString(info, "user_name", "nickname", "name", "nick_name") }
                    .ifBlank { firstString(node, "user_name", "nickname", "name", "nick_name") }
                if (!userId.matches(Regex("[0-9]{1,30}")) || name.isBlank() || !seen.add(userId)) continue

                val detailsArray = node.optJSONArray("sub_title_list")
                    ?: node.optJSONArray("sub_titles")
                    ?: info.optJSONArray("sub_title_list")
                val details = buildList {
                    if (detailsArray != null) for (i in 0 until detailsArray.length()) {
                        val item = detailsArray.opt(i)
                        val value = if (item is JSONObject) firstString(item, "content", "text", "title")
                            else item?.toString().orEmpty().trim()
                        if (value.isNotBlank()) add(value)
                    }
                }
                val rec = node.optJSONObject("rec_text_item") ?: info.optJSONObject("rec_text_item")
                val change = node.optJSONObject("rank_change_data")
                items += ActorRankEntry(
                    userId = userId, actorId = actorId, name = name,
                    avatar = avatarUrl(base, info, node),
                    popularity = firstString(rec, "RecommendText", "recommend_text", "text")
                        .ifBlank { firstString(node, "popularity", "fans_count_text", "fans_text") },
                    intro = firstString(node, "intro", "description", "user_intro")
                        .ifBlank { firstString(info, "intro", "description", "user_intro") },
                    details = details, position = requestedOffset + items.size + 1,
                    changeType = change?.optInt("rank_change_type") ?: 0,
                    changePlaces = change?.optInt("ChangeNum") ?: 0,
                )
            }

            if (requestedOffset == 0 && items.isEmpty()) {
                val fields = data.keys().asSequence().toList().sorted().joinToString(",")
                val message = root.optString("message").ifBlank { root.optString("msg") }
                throw IOException("演员榜未找到可识别的演员数据（code=" + code +
                    (if (message.isNotBlank()) ", $message" else "") + "；data字段=" + fields + "）")
            }
            val reportedOffset = data.optInt("next_offset", requestedOffset + items.size)
            val nextOffset = if (reportedOffset > requestedOffset) reportedOffset else requestedOffset + items.size
            val hasMore = data.optBoolean("has_more", data.optBoolean("has_next", false))
            val session = data.optString("session_id").ifBlank { data.optString("rank_session") }
            return ActorRankPage(
                items = items, nextOffset = nextOffset,
                hasMore = hasMore && items.isNotEmpty() && nextOffset > requestedOffset,
                sessionId = session, version = data.optString("rank_version"),
            )
        } catch (error: IOException) {
            throw error
        } catch (error: Exception) {
            throw IOException("演员榜数据解析失败", error)
        }
    }

    private fun collectActorNodes(value: Any?, out: MutableList<JSONObject>) {
        when (value) {
            is JSONObject -> {
                if (value.optJSONObject("user_info") != null || value.optJSONObject("base_info") != null ||
                    (value.has("user_id") && (value.has("user_name") || value.has("nickname")))
                ) out += value
                val keys = value.keys()
                while (keys.hasNext()) collectActorNodes(value.opt(keys.next()), out)
            }
            is JSONArray -> for (i in 0 until value.length()) collectActorNodes(value.opt(i), out)
            is String -> {
                val json = value.trim()
                if (json.startsWith("{") || json.startsWith("[")) {
                    runCatching {
                        if (json.startsWith("{")) collectActorNodes(JSONObject(json), out)
                        else collectActorNodes(JSONArray(json), out)
                    }
                }
            }
        }
    }

    private fun firstString(obj: JSONObject?, vararg keys: String): String {
        if (obj == null) return ""
        for (key in keys) {
            val value = obj.opt(key)
            if (value is String && value.isNotBlank() && value != "null") return cleanUrl(value).trim()
            if (value is Number) return value.toString()
        }
        return ""
    }

    private fun avatarUrl(vararg sources: JSONObject): String {
        val keys = listOf("user_avatar", "avatar_url", "avatar", "avatar_thumb", "avatar_medium", "avatar_large", "avatar_300", "head_url")
        for (source in sources) for (key in keys) {
            val candidate = imageValue(source.opt(key))
            if (candidate.isNotBlank()) return candidate
        }
        return ""
    }

    /**
     * BitmapFactory on our Android 8+ artwork loader cannot decode the HEIC avatar
     * URLs returned by the app API. The official image CDN supports the same image
     * through its shrink transform; use that URL rather than passing HEIC bytes to
     * the bitmap decoder.
     */
    private fun normalizeAvatarUrl(value: String): String {
        val uri = runCatching { java.net.URI(value) }.getOrNull() ?: return value
        val host = uri.host?.lowercase() ?: return value
        val supportedHost = host == "fqnovelpic.com" || host.endsWith(".fqnovelpic.com") ||
            host == "byteimg.com" || host.endsWith(".byteimg.com")
        if (!supportedHost) return value
        val path = uri.path ?: return value
        if (!path.startsWith("/novel-pic/")) return value
        val basePath = path.substringBefore("~")
            .replace(Regex("\\.(heic|jpeg|jpg|png|webp)$", RegexOption.IGNORE_CASE), "")
        return "https://p3-novel.byteimg.com${basePath}~tplv-shrink:640:0.image"
    }

    private fun imageValue(value: Any?): String {
        when (value) {
            is String -> {
                val url = normalizeAvatarUrl(cleanUrl(value.trim()))
                if (url.startsWith("https://") || url.startsWith("http://")) return url
            }
            is JSONArray -> for (i in 0 until value.length()) {
                val url = imageValue(value.opt(i))
                if (url.isNotBlank()) return url
            }
            is JSONObject -> {
                val urls = value.optJSONArray("url_list") ?: value.optJSONArray("urlList")
                if (urls != null) {
                    val url = imageValue(urls)
                    if (url.isNotBlank()) return url
                }
                for (key in listOf("url", "uri", "url_list", "urlList")) {
                    val url = imageValue(value.opt(key))
                    if (url.isNotBlank()) return url
                }
            }
        }
        return ""
    }
}
