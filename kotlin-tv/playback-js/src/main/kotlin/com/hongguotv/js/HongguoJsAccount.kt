// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.js

import com.hongguotv.core.AccountCredentials
import com.hongguotv.core.CloudFavorite
import com.hongguotv.core.CloudProfile
import com.hongguotv.core.CloudProgress
import com.hongguotv.core.CloudReservation
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
        val data = call("phoneCode", JSONObject().put("number", number))
        captureCredentials(data)
        return JsAccountGateway.SentCode(
            maskedMobile = data.optString("mobile").ifBlank { "你的手机" },
            retrySeconds = data.optInt("retryAfter", 60),
        )
    }

    override fun verifyPhoneCode(code: String): AccountCredentials {
        val data = call("phoneSms", JSONObject().put("code", code).put("number", currentPhone))
        captureCredentials(data)
        return lastCredentials
    }

    override fun credentials(): AccountCredentials {
        val data = call("webInfo", JSONObject())
        captureCredentials(data)
        if (lastCredentials.cookie.isBlank()) throw IOException("尚未获取登录态，请先在手机确认登录")
        return lastCredentials
    }

    override fun profile(credentials: AccountCredentials): CloudProfile {
        val data = call("profile", JSONObject(), credentials)
        return CloudProfile(
            id = data.optString("id"),
            nickname = data.optString("nickname").ifBlank { "红果用户" },
            avatarUrl = data.optString("avatar"),
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

    override fun reservations(credentials: AccountCredentials, online: Boolean): List<CloudReservation> {
        // JS 侧没有独立的预约列表 action；返回空列表，避免误报。
        return emptyList()
    }

    override fun reserve(credentials: AccountCredentials, work: RelatedWork) {
        // JS 侧当前没有预约写入 action；静默跳过，保持播放器行为稳定。
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

    // ---- 脚本调用与映射 ----

    private fun call(action: String, payload: JSONObject, credentials: AccountCredentials? = null): JSONObject {
        val options = JSONObject()
        val cookie = credentials?.cookie?.ifBlank { lastCredentials.cookie } ?: lastCredentials.cookie
        if (cookie.isNotBlank()) options.put("cookie", cookie)
        val device = credentials?.device
        if (!device.isNullOrEmpty()) options.put("device", JSONObject(device).toString())
        val result = runBlockingCall("accountCall", action, payload, options)
        if (!result.ok) throw IOException(result.error ?: "账号接口调用失败（${result.code}）")
        return JSONObject(result.data ?: "{}")
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
