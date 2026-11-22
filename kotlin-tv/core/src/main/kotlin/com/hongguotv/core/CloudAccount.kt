// SPDX-License-Identifier: GPL-3.0-only
// Account endpoint shapes referenced from charlie901208/HGTV feat/phone-login, 2026-10-21.
package com.hongguotv.core

import java.io.IOException

// 账户云端数据访问：封装账户相关请求与返回数据。

import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject

class CloudAccountClient(private val http: OkHttpClient) {
    private val transport = CloudAccountTransport(http)

    fun registerDevice(): DeviceRegister.Registered? = transport.registerDevice()

    private fun json(
        credentials: AccountCredentials,
        path: String,
        params: Map<String, String> = emptyMap(),
        body: ByteArray? = null,
        gzip: Boolean = false,
    ): JSONObject = transport.json(credentials, path, params, body, gzip)

    fun profile(credentials: AccountCredentials): CloudProfile {
        val data =
            json(credentials, "/reading/user/info/v1/").optJSONObject("data")
                ?: throw IOException("账号资料为空")
        val id = data.str("biz_user_id")
        if (id.isBlank()) throw IOException("登录态未通过账号验证，请重新登录")
        // 红果账号资料在不同客户端/接口版本中使用过多种头像字段名，按优先级兼容解析。
        val userInfo = data.optJSONObject("user_info") ?: data.optJSONObject("user") ?: data
        val avatar = listOf(
            "avatar_url", "avatar_uri", "avatar_thumb", "avatar_large",
            "user_avatar", "avatar", "head_url", "head_image"
        ).asSequence().map { key ->
            userInfo.optString(key).takeIf { it.isNotBlank() && it != "null" }
                ?: data.optString(key).takeIf { it.isNotBlank() && it != "null" }
        }.filterNotNull().firstOrNull()?.let { raw ->
            when {
                raw.startsWith("//") -> "https:$raw"
                raw.startsWith("https://", true) || raw.startsWith("http://", true) -> raw
                else -> ""
            }
        }.orEmpty()
        return CloudProfile(id, data.str("user_name").ifBlank { "红果用户" }, avatar)
    }

    fun history(credentials: AccountCredentials): List<CloudProgress> {
        val data =
            json(
                credentials,
                "/reading/bookapi/read_progress/list/v1/",
                mapOf("book_types" to "2", "limit" to "50"),
            )
        val rows =
            data.optJSONArray("video_data")
                ?: data.optJSONObject("data")?.optJSONArray("video_data")
                ?: throw IOException("云端历史数据格式已变化")
        val progress = parseProgress(rows)
        if (progress.isEmpty()) return emptyList()
        val details =
            json(
                    credentials,
                    "/reading/bookapi/multi-detail/v1/",
                    mapOf(
                        "book_id" to progress.map { it.series.id }.joinToString(","),
                        "book_type" to "2",
                    ),
                )
                .optJSONArray("data") ?: throw IOException("云端剧集资料格式已变化")
        val series =
            details.objects().associate { row ->
                row.str("book_id") to
                    Series(
                        row.str("book_id"),
                        row.str("book_name"),
                        cleanUrl(row.str("thumb_url")),
                        row.str("abstract"),
                        episodeBadge(row),
                    )
            }
        return progress.map {
            it.copy(series = series[it.series.id] ?: it.series.copy(title = "剧集 ${it.series.id}"))
        }
    }

    /** Read-only video shelf. Protocol reference: charlie901208/HGTV AccountParser.favorites. */
    fun favorites(credentials: AccountCredentials): List<CloudFavorite> =
        parseFavorites(json(credentials, "/reading/bookapi/bookshelf/video/info/v1/"))

    /**
     * Video shelf only (novel bookshelf/add can acknowledge without changing video favorites).
     * Protocol reference: HGTV videoShelf public request model. No automatic retry: callers must
     * read the shelf back before reporting success.
     */
    fun setFavorite(credentials: AccountCredentials, seriesId: String, add: Boolean) {
        require(seriesId.matches(Regex("[0-9]{1,30}"))) { "剧集编号无效" }
        val entry =
            JSONObject()
                .put("book_id", seriesId)
                .put("book_type", 2)
                .put("modify_time", System.currentTimeMillis())
                .put("video_shelf_operate_type", if (add) 0 else 1)
        if (!add) entry.put("group_name", "")
        val body =
            JSONObject()
                .put("is_cancelled", false)
                .put("update_bookshelf_video_list", JSONArray().put(entry))
        if (add)
            body.put(
                "shark_extra",
                JSONObject()
                    .put("enter_from", "MainFragmentActivity")
                    .put("previous_page", "")
                    .put("page_list", "MainFragmentActivity")
                    .put("is_active_behavior", "true")
                    .put("inactive_type", "0"),
            )
        json(
            credentials,
            "/reading/bookapi/bookshelf/video/update/v1/",
            body = body.toString().toByteArray(),
        )
    }

    /**
     * Protocol reference: charlie901208/HGTV feat/phone-login, accountReserves. Keep the two
     * upstream categories separate; an unpublished series is never playable. The upstream
     * pagination contract is unverified, so expose only this requested batch.
     */
    fun reservations(credentials: AccountCredentials, online: Boolean): List<CloudReservation> {
        val raw =
            json(
                credentials,
                "/reading/user/subscribe/list/v0/",
                mapOf(
                    "offset" to "0",
                    "limit" to "20",
                    "is_online" to online.toString(),
                    "tab_type" to "13",
                    "subscribe_order_type" to "0",
                    "swipe_type" to "0",
                    "need_calendar_schema" to "false",
                    "subscribe_offset" to "0",
                ),
            )
        val rows = parseReservations(raw, online)
        if (!online) return rows
        val missing =
            rows.filter { EpisodeSummary.total(it.series.badge) == 0 }.map { it.series.id }
        if (missing.isEmpty()) return rows
        // Optional batch metadata: its failure must not hide a valid reservation list.
        val totals =
            runCatching {
                    json(
                            credentials,
                            "/reading/bookapi/multi-detail/v1/",
                            mapOf("book_id" to missing.joinToString(","), "book_type" to "2"),
                        )
                        .optJSONArray("data")
                        ?.objects()
                        .orEmpty()
                        .associate { it.str("book_id") to episodeBadge(it) }
                }
                .getOrDefault(emptyMap())
        return rows.map { row ->
            totals[row.series.id]
                ?.takeIf { it.isNotBlank() }
                ?.let { row.copy(series = row.series.copy(badge = it)) } ?: row
        }
    }

    /**
     * SearchUncoverSubscribeRequest fields come from the official RPC model. code=0 only
     * acknowledges the write; account-list confirmation is a separate operation.
     */
    fun reserve(credentials: AccountCredentials, work: RelatedWork) {
        require(work.upcoming && work.series.id.matches(Regex("[0-9]{1,19}")))
        val id = work.series.id.toLongOrNull() ?: throw IllegalArgumentException("预约编号无效")
        val body =
            JSONObject()
                .put("item_id", id)
                .put("item_type", 3)
                .put("op_type", 1)
                .put("subscribe_source", 0)
                .toString()
                .toByteArray()
        json(credentials, "/reading/bookapi/search/uncover_subscribe/v1/", body = body, gzip = true)
    }

    fun upload(credentials: AccountCredentials, progress: CloudProgress, episodeCount: Int) {
        require(
            progress.series.id.matches(Regex("[0-9]{1,30}")) &&
                progress.episodeId.matches(Regex("[0-9]{1,30}"))
        )
        val entry =
            JSONObject()
                .put("book_id", progress.series.id)
                .put("item_id", progress.episodeId)
                .put("vid_index", progress.episodeIndex + 1)
                .put("current_play_time", progress.position.coerceAtLeast(0))
                .put("current_total_time", progress.duration.coerceAtLeast(0))
                .put("episode_count", episodeCount)
                .put("read_timestamp_ms", progress.updatedAt)
                .put("book_type", 2)
                .put(
                    "progress_rate",
                    if (progress.duration > 0)
                        (progress.position.toDouble() / progress.duration)
                            .coerceIn(0.0, 1.0)
                            .toString()
                    else "0",
                )
                .put("player_cumulative_total_duration", progress.position.coerceAtLeast(0))
        val body =
            JSONObject()
                .put("books", JSONArray().put(entry))
                .put("write_mode", 1)
                .toString()
                .toByteArray()
        json(credentials, "/reading/bookapi/read_progress/upload/v1/", body = body)
    }

    companion object {
        private fun episodeBadge(row: JSONObject): String = CloudAccountParsers.episodeBadge(row)
        fun parseFavorites(raw: JSONObject): List<CloudFavorite> = CloudAccountParsers.parseFavorites(raw)
        fun parseReservations(raw: JSONObject, online: Boolean): List<CloudReservation> =
            CloudAccountParsers.parseReservations(raw, online)
        fun parseProgress(rows: JSONArray): List<CloudProgress> = CloudAccountParsers.parseProgress(rows)
    }

}
