// SPDX-License-Identifier: GPL-3.0-only
// 账户凭据、云端数据模型与响应体安全读取。
package com.hongguotv.core

import java.io.IOException
import org.json.JSONObject

data class AccountCredentials(
    val cookie: String,
    val deviceId: String = "",
    val installId: String = "",
    val device: Map<String, String> = emptyMap(),
) {
    fun toJson() =
        JSONObject()
            .put("cookie", cookie)
            .put("deviceId", deviceId)
            .put("installId", installId)
            .put("device", JSONObject(device))
            .toString()

    override fun toString() = "AccountCredentials([redacted])"

    companion object {
        fun fromJson(value: String): AccountCredentials {
            val json = JSONObject(value)
            val device = json.optJSONObject("device") ?: JSONObject()
            return AccountCredentials(
                json.getString("cookie"),
                json.str("deviceId"),
                json.str("installId"),
                device.keys().asSequence().associateWith { device.getString(it) },
            )
        }
    }
}

internal object AccountCredentialsParser {
    fun parseHeader(value: String) = AccountCredentials(value)
}

data class CloudProfile(val id: String, val nickname: String, val avatarUrl: String = "")

data class CloudProgress(
    val series: Series,
    val episodeId: String,
    val episodeIndex: Int,
    val position: Long,
    val duration: Long,
    val updatedAt: Long,
    val episodeCount: Int = 0,
)

internal fun cloudResponse(response: okhttp3.Response): String {
    if (!response.isSuccessful) throw IOException("账号请求失败（HTTP ${response.code}）")
    val body = response.body ?: throw IOException("账号接口响应为空")
    val limit = 2 * 1024 * 1024L
    if (body.contentLength() > limit) throw IOException("账号接口响应过大")
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    val input = body.byteStream()
    while (out.size() <= limit) {
        val n = input.read(buffer)
        if (n < 0) break
        out.write(buffer, 0, n)
    }
    val bytes = out.toByteArray()
    if (bytes.size > limit) throw IOException("账号接口响应过大")
    val text = String(bytes, Charsets.UTF_8)
    if (text.isBlank()) throw IOException("账号接口返回空响应，请稍后重试")
    return text
}

data class CloudFavorite(val series: Series, val collectedAt: Long)

data class CloudReservation(
    val series: Series,
    val online: Boolean,
    val publishAt: Long,
    val publishText: String = "",
)
