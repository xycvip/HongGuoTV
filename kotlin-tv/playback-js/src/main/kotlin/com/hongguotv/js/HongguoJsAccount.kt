// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.js

import com.hongguotv.core.AccountCredentials
import com.hongguotv.core.CloudFavorite
import com.hongguotv.core.CloudProfile
import com.hongguotv.core.CloudProgress
import com.hongguotv.core.CloudReservation
import com.hongguotv.core.Creator
import com.hongguotv.core.CreatorProfile
import com.hongguotv.core.CreatorWorksPage
import com.hongguotv.core.ImageUrls
import com.hongguotv.core.Review
import com.hongguotv.core.ReviewPage
import com.hongguotv.core.JsAccountGateway
import com.hongguotv.core.RelatedWork
import com.hongguotv.core.Series
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/**
 * 账号能力的 JS 门面实现。
 *
 * 所有账号接口（扫码/验证码登录、资料/收藏/历史/进度/预约）的请求构造、签名、
 * HTTP 传输和响应解析都在热更新脚本 main.js 的 accountCall 内完成；本类仅：
 *  1. 调脚本的 accountCall(action, payload, options)；
 *  2. 把脚本返回的归一化 JSON 映射成 core 模型；
 *  3. 把脚本下发的 _cookies/_device 转成可持久化的 [AccountCredentials]。
 *
 * 全部方法阻塞式（内部 runBlocking），供 Dispatchers.IO 直接调用。
 */
class HongguoJsAccount(
    private val js: HongguoJs,
) : JsAccountGateway {

    private var lastCredentials = AccountCredentials("")

    override fun qr(): JsAccountGateway.QrCode {
        val data = call("qr", JSONObject())
        captureCredentials(data)
        val qrUrl = data.optString("qrUrl")
        if (qrUrl.isBlank()) throw IOException("扫码链接为空")
        val expiresAt = data.optLong("expiresAt")
        val expiresIn = if (expiresAt > 1_000_000_000L) {
            ((expiresAt - System.currentTimeMillis() / 1000) / 1000).toInt()
        } else {
            expiresAt.toInt()
        }.coerceIn(60, 600)
        return JsAccountGateway.QrCode(qrUrl, expiresIn)
    }

    override fun poll(): Boolean {
        val data = call("poll", JSONObject().put("token", currentToken))
        captureCredentials(data)
        val status = data.optString("status")
        if (status in setOf("5", "expired")) throw IOException("二维码已过期，请重新获取")
        return status in setOf("3", "confirmed", "return_success") ||
            data.optString("_cookies").contains("sessionid")
    }

    override fun sendPhoneCode(number: String): JsAccountGateway.SentCode {
        // 号码要留在会话里：verifyPhoneCode 只拿验证码，手机号由这里记住。
        currentPhone = number
        val data = call("phoneCode", JSONObject().put("number", number))
        captureCredentials(data)
        if (data.optBoolean("rejected")) throw IOException("该号码需要完成安全验证，请改用扫码登录")
        return JsAccountGateway.SentCode(
            maskedMobile = data.optString("mobile").ifBlank { maskMobile(number) },
            retrySeconds = data.optInt("retryAfter", 60).coerceIn(30, 600),
        )
    }

    override fun verifyPhoneCode(code: String): AccountCredentials {
        if (currentPhone.isBlank()) throw IOException("请先获取验证码")
        val data = call("phoneSms", JSONObject().put("code", code).put("number", currentPhone))
        captureCredentials(data)
        if (data.optBoolean("rejected")) throw IOException("需要完成安全验证，请改用扫码登录")
        if (!lastCredentials.cookie.contains("sessionid")) throw IOException("验证码不正确或已过期，请重试")
        return lastCredentials
    }

    override fun credentials(): AccountCredentials {
        val data = call("webInfo", JSONObject())
        captureCredentials(data)
        if (lastCredentials.cookie.isBlank()) throw IOException("尚未获取登录态，请先在手机确认登录")
        return lastCredentials
    }

    override fun profile(credentials: AccountCredentials): CloudProfile {
        val direct = runCatching {
            val data = call("profile", JSONObject(), credentials)
            val id = data.optString("id")
            if (id.isBlank()) {
                null
            } else {
                CloudProfile(
                    id = id,
                    nickname = data.optString("nickname").ifBlank { "红果用户" },
                    // 头像与剧集封面走同一套归一化：脚本可能下发 //p3-... 或 .heic，
                    // TV 盒子解不出来就是「头像空白」，这里统一修好。
                    avatarUrl = ImageUrls.normalize(data.optString("avatar")),
                )
            }
        }.getOrNull()
        if (direct != null) return direct
        // 官方资料接口偶尔改结构（id 字段换名即整条失败）。webInfo 同样返回昵称/头像，
        // 且登录态有效时必定可用——宁可多一次请求，也不要让账号页只剩「红果用户 + 首字」。
        val info = call("webInfo", JSONObject(), credentials)
        return CloudProfile(
            id = info.optString("id"),
            nickname = info.optString("nickname").ifBlank { "红果用户" },
            avatarUrl = ImageUrls.normalize(info.optString("avatar")),
        )
    }

    override fun favorites(credentials: AccountCredentials): List<CloudFavorite> {
        val data = call("favorites", JSONObject(), credentials)
        return data.optJSONArray("items").mapFavorites()
    }

    override fun history(credentials: AccountCredentials): List<CloudProgress> {
        val data = call("history", JSONObject(), credentials)
        return data.optJSONArray("items").mapProgress()
    }

    override fun historySeries(credentials: AccountCredentials): List<Series> =
        history(credentials).map { it.series }

    override fun setFavorite(credentials: AccountCredentials, seriesId: String, add: Boolean) {
        call("favoriteUpdate", JSONObject().put("id", seriesId).put("follow", if (add) "1" else "0"), credentials)
    }

    override fun uploadProgress(
        credentials: AccountCredentials,
        progress: CloudProgress,
        episodeCount: Int,
    ) {
        val payload = JSONObject()
            .put("id", progress.series.id)
            .put("itemId", progress.episodeId)
            .put("positionMs", progress.position)
            .put("durationMs", progress.duration)
            .put("episode", progress.episodeIndex + 1)
            .put("episodes", episodeCount)
            .put("updatedAt", progress.updatedAt)
        call("progressUpdate", payload, credentials)
    }

    override fun deleteHistory(credentials: AccountCredentials, seriesId: String, videoId: String, updatedAt: Long) {
        val payload = JSONObject()
            .put("id", seriesId)
            .put("itemId", videoId.ifBlank { seriesId })
            .put("updatedAt", updatedAt.takeIf { it > 0 } ?: System.currentTimeMillis())
        call("historyDelete", payload, credentials)
    }

    override fun reservations(credentials: AccountCredentials, online: Boolean): List<CloudReservation> {
        val data = call("reservations", JSONObject().put("online", online), credentials)
        val rows = data.optJSONArray("items") ?: return emptyList()
        val out = ArrayList<CloudReservation>(rows.length())
        for (index in 0 until rows.length()) {
            val row = rows.optJSONObject(index) ?: continue
            val series = row.toSeries() ?: continue
            val onlineFlag = if (row.has("online")) row.optBoolean("online") else online
            out += CloudReservation(
                series = series,
                online = onlineFlag,
                publishAt = row.optLong("publishAt"),
                publishText = row.optString("note"),
            )
        }
        return out.distinctBy { it.series.id }
    }

    override fun reserve(credentials: AccountCredentials, work: RelatedWork) {
        // op_type: 1 = 预约/加入片单，2 = 取消预约。
        val payload = JSONObject()
            .put("id", work.series.id)
            .put("opType", if (work.upcoming) 1 else 2)
        call("reserveUpdate", payload, credentials)
    }

    // ---- 详情页社交数据（匿名设备签名，未登录可读） ----

    override fun creatorList(seriesId: String): List<Creator> {
        val data = call("creatorList", JSONObject().put("id", seriesId))
        val rows = data.optJSONArray("creators") ?: return emptyList()
        val out = ArrayList<Creator>(rows.length())
        for (index in 0 until rows.length()) {
            val row = rows.optJSONObject(index) ?: continue
            val id = row.optString("id")
            if (!id.matches(Regex("[0-9]{1,30}"))) continue
            out += Creator(
                id = id,
                name = row.optString("name"),
                relativeType = row.optInt("relativeType"),
                following = if (row.has("following") && !row.isNull("following")) row.optBoolean("following") else null,
                canFollow = row.optBoolean("canFollow"),
            )
        }
        return out
    }

    override fun creatorProfile(userId: String, credentials: AccountCredentials?): CreatorProfile {
        val data = call("creatorProfile", JSONObject().put("userId", userId), credentials)
        return CreatorProfile(data.optJSONObject("creator")?.toCreator() ?: Creator(id = userId))
    }

    override fun creatorWorks(userId: String, cursor: String, count: Int): CreatorWorksPage {
        val payload = JSONObject()
            .put("userId", userId)
            .put("count", count.coerceIn(1, 50))
        if (cursor.isNotBlank()) payload.put("cursor", cursor)
        val data = call("creatorWorks", payload)
        val rows = data.optJSONArray("items") ?: return CreatorWorksPage(emptyList(), false, "", 0)
        val items = ArrayList<Series>(rows.length())
        for (index in 0 until rows.length()) {
            val row = rows.optJSONObject(index) ?: continue
            val series = row.toSeries() ?: continue
            items += series
        }
        return CreatorWorksPage(
            items = items,
            hasMore = data.optBoolean("hasMore"),
            nextCursor = data.optString("nextCursor"),
            nextOffset = data.optInt("nextOffset"),
        )
    }

    override fun commentList(seriesId: String, cursor: String, count: Int): ReviewPage {
        val payload = JSONObject()
            .put("bookId", seriesId)
            .put("id", seriesId)
            .put("count", count.coerceIn(1, 50))
            .put("sort", 1)
        if (cursor.isNotBlank()) payload.put("cursor", cursor)
        val data = call("commentList", payload)
        val rows = data.optJSONArray("list") ?: return ReviewPage(emptyList(), 0, false, "")
        val items = ArrayList<Review>(rows.length())
        for (index in 0 until rows.length()) {
            val row = rows.optJSONObject(index) ?: continue
            val id = row.optString("id")
            if (id.isBlank()) continue
            items += Review(
                id = id,
                text = row.optString("text"),
                userName = row.optString("userName").ifBlank { "红果用户" },
                userAvatar = ImageUrls.normalize(row.optString("userAvatar")),
                userId = row.optString("userId"),
                createTime = row.optLong("createTime"),
                diggCount = row.optInt("diggCount"),
                replyCount = row.optInt("replyCount"),
            )
        }
        return ReviewPage(
            items = items,
            total = data.optInt("total"),
            hasMore = data.optBoolean("hasMore"),
            cursor = data.optString("cursor"),
        )
    }

    override fun creatorFollow(userId: String, relativeType: Int, follow: Boolean, credentials: AccountCredentials) {
        val payload = JSONObject()
            .put("userId", userId)
            .put("relativeType", relativeType)
            .put("follow", if (follow) "1" else "0")
        call("creatorFollow", payload, credentials)
    }

    private fun JSONObject.toCreator(): Creator {
        val id = optString("id")
        return Creator(
            id = id,
            name = optString("name"),
            avatar = ImageUrls.normalize(optString("avatar")),
            description = optString("description"),
            worksCount = optInt("worksCount"),
            following = if (has("following") && !isNull("following")) optBoolean("following") else null,
            canFollow = optBoolean("canFollow"),
            relativeType = optInt("relativeType"),
        )
    }

    override fun restore(credentials: AccountCredentials) {
        lastCredentials = credentials
    }

    override fun signOut() {
        lastCredentials = AccountCredentials("")
        currentToken = ""
        currentPhone = ""
        runCatching { runBlockingNoResult("resetAccount") }
    }

    // ---- 会话内状态（扫码 token / 手机号），供多步登录复用 ----

    private var currentToken = ""
    private var currentPhone = ""

    private fun captureCredentials(data: JSONObject) {
        val cookie = data.optString("_cookies")
        if (cookie.isNotBlank()) {
            lastCredentials = lastCredentials.copy(cookie = cookie)
        }
        data.optString("token").takeIf { it.isNotBlank() }?.let { currentToken = it }
    }

    private fun maskMobile(number: String): String {
        val digits = number.filter { it.isDigit() }
        return if (digits.length >= 7) digits.take(3) + "****" + digits.takeLast(4) else "你的手机"
    }

    // ---- 脚本调用与映射 ----

    private fun call(action: String, payload: JSONObject, credentials: AccountCredentials? = null): JSONObject {
        val options = JSONObject()
        val cookie = credentials?.cookie?.ifBlank { lastCredentials.cookie } ?: lastCredentials.cookie
        if (cookie.isNotBlank()) options.put("cookie", cookie)
        val device = credentials?.device
        if (!device.isNullOrEmpty()) options.put("device", JSONObject(device).toString())
        val result = runBlockingCall("accountCall", action, payload, options)
        if (!result.ok) throw IOException(friendlyAccountError(result.error ?: "账号接口调用失败（${result.code}）"))
        return JSONObject(result.data ?: "{}")
    }

    /** 脚本抛的是官方错误码，直接展示给电视用户太生硬；这里换成能照着做的文案。 */
    private fun friendlyAccountError(message: String): String = when {
        message.startsWith("ACCOUNT_BUSINESS_") ->
            "官方拒绝了这次请求（${message.removePrefix("ACCOUNT_BUSINESS_")}），请稍后再试或改用扫码登录"
        message.contains("SECONDARY_VERIFICATION") || message.contains("ACCOUNT_CHALLENGE") ->
            "该号码需要完成安全验证，请改用扫码登录"
        else -> message
    }

    private fun runBlockingCall(function: String, vararg args: Any?): JsCallResult =
        kotlinx.coroutines.runBlocking { js.call(function, *args) }

    private fun runBlockingNoResult(function: String) {
        kotlinx.coroutines.runBlocking { js.call(function) }
    }

    private fun JSONArray?.mapFavorites(): List<CloudFavorite> {
        if (this == null) return emptyList()
        val out = ArrayList<CloudFavorite>(length())
        for (index in 0 until length()) {
            val row = optJSONObject(index) ?: continue
            val series = row.toSeries() ?: continue
            out += CloudFavorite(series, row.optLong("updatedAt"))
        }
        return out.distinctBy { it.series.id }
    }

    private fun JSONArray?.mapProgress(): List<CloudProgress> {
        if (this == null) return emptyList()
        val out = ArrayList<CloudProgress>(length())
        for (index in 0 until length()) {
            val row = optJSONObject(index) ?: continue
            val series = row.toSeries() ?: continue
            val episodeId = row.optString("episodeId")
            if (episodeId.isBlank()) continue
            out += CloudProgress(
                series = series,
                episodeId = episodeId,
                episodeIndex = (row.optInt("episodeIndex", 1) - 1).coerceAtLeast(0),
                position = row.optLong("positionMs"),
                duration = row.optLong("durationMs"),
                updatedAt = row.optLong("updatedAt"),
                episodeCount = row.optInt("episodes"),
            )
        }
        return out.distinctBy { it.series.id }.sortedByDescending { it.updatedAt }
    }

    private fun JSONObject.toSeries(): Series? {
        val id = optString("id")
        if (!id.matches(Regex("[0-9]{1,30}"))) return null
        return Series(
            id = id,
            title = optString("title"),
            cover = optString("cover"),
            description = optString("description"),
            badge = if (optInt("episodes") > 0) "全 ${optInt("episodes")} 集" else "",
        )
    }
}
