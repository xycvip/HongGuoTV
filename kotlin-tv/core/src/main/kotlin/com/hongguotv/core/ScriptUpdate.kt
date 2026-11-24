// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import org.json.JSONObject
import java.security.MessageDigest

/**
 * main.js 热更新通道。
 *
 * 源仓库：https://github.com/xycvip/RedGuoAPI
 *   dist/index.json              清单（CI 生成，含 size / sha256）
 *   dist/hongguoTV/HongGuoTV.js  脚本（build 产物的字节级副本）
 *
 * 线路实测（2026-11）：gh-proxy / ghfast / ghproxy.net / raw / jsDelivr 均可达；
 * api.github.com 匿名调用会 403（未认证限流 60 次/小时），只作最后兜底。
 */
enum class ScriptTransport {
    /** 前缀式代理：{proxy}https://raw.githubusercontent.com/... */
    PREFIX,
    /** 官方 raw 直连。 */
    DIRECT,
    /** jsDelivr 的 gh/user/repo@ref/path 形态。 */
    CDN_PATH,
    /** GitHub contents API，返回 base64 包装。 */
    GITHUB_API,
}

enum class ScriptChannel(
    val key: String,
    val label: String,
    val transport: ScriptTransport,
    private val prefix: String = "",
) {
    GH_PROXY("ghproxy", "gh-proxy", ScriptTransport.PREFIX, "https://gh-proxy.com/"),
    GHFAST("ghfast", "ghfast", ScriptTransport.PREFIX, "https://ghfast.top/"),
    GHMIRROR("ghproxy-net", "ghproxy.net", ScriptTransport.PREFIX, "https://ghproxy.net/"),
    RAW("raw", "GitHub", ScriptTransport.DIRECT),
    JSDELIVR("jsdelivr", "jsDelivr", ScriptTransport.CDN_PATH),
    API("api", "GitHub API", ScriptTransport.GITHUB_API);

    /** @param path 仓库内相对路径，如 `dist/index.json`。 */
    fun urlFor(path: String): String = when (transport) {
        ScriptTransport.PREFIX -> prefix + RAW_BASE + path
        ScriptTransport.DIRECT -> RAW_BASE + path
        ScriptTransport.CDN_PATH -> CDN_BASE + path
        ScriptTransport.GITHUB_API -> API_BASE + path + "?ref=" + REPO_REF
    }

    companion object {
        const val REPO_REF = "main"
        const val RAW_BASE = "https://raw.githubusercontent.com/xycvip/RedGuoAPI/$REPO_REF/"
        const val CDN_BASE = "https://cdn.jsdelivr.net/gh/xycvip/RedGuoAPI@$REPO_REF/"
        const val API_BASE = "https://api.github.com/repos/xycvip/RedGuoAPI/contents/"

        const val MANIFEST_PATH = "dist/index.json"
        const val SCRIPT_PATH = "dist/hongguoTV/HongGuoTV.js"
        const val PACKAGE_NAME = "com.x.hongguotv.bate"

        /** 竞速顺序：国内可达性优先，API 放最后（匿名易 403）。 */
        val ORDER: List<ScriptChannel> =
            listOf(GH_PROXY, GHFAST, GHMIRROR, RAW, JSDELIVR, API)
    }
}

/** dist/index.json 的内容。任何字段不合规都视为不可信清单。 */
data class ScriptManifest(
    val version: String,
    val size: Long,
    val sha256: String,
    val path: String,
    val builtAt: String,
) {
    companion object {
        const val MIN_SIZE = 200L * 1024
        const val MAX_SIZE = 2L * 1024 * 1024
        private val SHA256 = Regex("[0-9a-f]{64}")

        fun parse(raw: String): ScriptManifest {
            val root = JSONObject(raw)
            require(root.optInt("schema") == 1) { "清单格式不支持" }
            require(root.optString("packageName") == ScriptChannel.PACKAGE_NAME) { "清单与当前应用不匹配" }
            val script = root.optJSONObject("script") ?: throw IllegalArgumentException("清单缺少 script")
            val path = script.optString("path")
            val size = script.optLong("size")
            val sha = script.optString("sha256").lowercase()
            require(path.startsWith("dist/") && path.endsWith(".js")) { "脚本路径非法" }
            require(size in MIN_SIZE..MAX_SIZE) { "脚本长度异常（$size）" }
            require(SHA256.matches(sha)) { "摘要格式非法" }
            return ScriptManifest(
                version = script.optString("version").ifBlank { "0.0.0" },
                size = size,
                sha256 = sha,
                path = path,
                builtAt = script.optString("builtAt"),
            )
        }
    }
}

/** 脚本字节的 sha256 小写十六进制。热更新链路上所有比对都用它。 */
fun ByteArray.sha256Hex(): String =
    MessageDigest.getInstance("SHA-256").digest(this).joinToString("") { "%02x".format(it) }
