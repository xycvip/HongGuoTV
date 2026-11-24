// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.js

import android.content.Context
import com.hongguotv.core.RequestScope
import okhttp3.OkHttpClient
import java.io.File

/**
 * main.js 的热更新脚本门面。
 *
 * 脚本来源优先级：
 *  1. filesDir/hongguo-js/main.js + 同目录 main.js.sha256（在线更新落地，校验通过才用）；
 *  2. APK 内置 assets/hongguo-js/main.js（永远可用的兜底）。
 * 更新失败/校验不过时静默回退内置版，脚本来源变化只影响数据新鲜度，不影响可用性。
 */
class HongguoJs private constructor(
    private val runner: JsScriptRunner,
    /** 当前脚本内容 sha256 前缀，用于诊断与更新比对。 */
    val scriptDigest: String,
    /** true = 来自在线更新，false = APK 内置。 */
    val fromUpdate: Boolean,
) {
    suspend fun call(function: String, vararg args: Any?): JsCallResult {
        val argsJson = args.toJsonArray()
        return RequestScope.run { runner.call(function, argsJson) }
    }

    companion object {
        private const val ASSET_PATH = "hongguo-js/main.js"
        private const val UPDATE_DIR = "hongguo-js"
        private const val UPDATE_SCRIPT = "main.js"
        private const val UPDATE_DIGEST = "main.js.sha256"

        fun create(
            context: Context,
            http: OkHttpClient,
            store: JsKeyValueStore = InMemoryJsKeyValueStore(),
            onLog: (String) -> Unit = {},
        ): HongguoJs {
            val (bytes, fromUpdate) = loadScript(context)
            val cache = JsBytecodeCache.onDisk(context.filesDir)
            val runner = JsScriptRunner(
                host = JsEngineHost(http, store, onLog),
                scriptSource = { bytes },
                bytecodeCache = cache,
            )
            return HongguoJs(runner, runner.scriptDigest(), fromUpdate)
        }

        /** 返回 (脚本字节, 是否来自在线更新)。 */
        private fun loadScript(context: Context): Pair<ByteArray, Boolean> {
            val dir = File(context.filesDir, UPDATE_DIR)
            val script = File(dir, UPDATE_SCRIPT)
            val digestFile = File(dir, UPDATE_DIGEST)
            val updated = runCatching {
                if (!script.isFile || !digestFile.isFile) return@runCatching null
                val bytes = script.readBytes()
                if (bytes.isEmpty()) return@runCatching null
                val expected = digestFile.readText().trim().lowercase()
                val actual = bytes.sha256Hex()
                if (expected.isNotEmpty() && expected != actual) null else bytes
            }.getOrNull()
            if (updated != null) return updated to true
            val asset = context.assets.open(ASSET_PATH).use { it.readBytes() }
            require(asset.isNotEmpty()) { "内置脚本缺失：assets/$ASSET_PATH" }
            return asset to false
        }

        /** 供更新器落盘后调用：写脚本 + 校验值，下次启动生效。 */
        fun installUpdate(context: Context, bytes: ByteArray): Boolean {
            val digest = bytes.sha256Hex()
            val dir = File(context.filesDir, UPDATE_DIR)
            val script = File(dir, UPDATE_SCRIPT)
            return runCatching {
                dir.mkdirs()
                val temp = File(dir, "$UPDATE_SCRIPT.tmp")
                temp.writeBytes(bytes)
                if (!temp.renameTo(script)) {
                    temp.delete()
                    return false
                }
                File(dir, UPDATE_DIGEST).writeText(digest)
                true
            }.getOrDefault(false)
        }

        fun clearUpdate(context: Context): Boolean = runCatching {
            File(context.filesDir, UPDATE_DIR).deleteRecursively()
        }.getOrDefault(false)

        private fun ByteArray.sha256Hex(): String =
            java.security.MessageDigest.getInstance("SHA-256").digest(this)
                .joinToString("") { "%02x".format(it) }
    }
}

/** org.json 在 Android 设备上是真实实现；参数序列化统一走 JSON 文本，避免跨边界类型问题。 */
internal fun Array<out Any?>.toJsonArray(): String {
    val array = org.json.JSONArray()
    forEach { item ->
        when (item) {
            null -> array.put(org.json.JSONObject.NULL)
            is Boolean, is Int, is Long, is Double, is Float -> array.put(item)
            is String -> array.put(item)
            is Map<*, *> -> array.put(org.json.JSONObject(item.mapKeys { it.key.toString() }))
            is List<*> -> array.put(org.json.JSONArray(item))
            is org.json.JSONObject -> array.put(item)
            is org.json.JSONArray -> array.put(item)
            else -> array.put(item.toString())
        }
    }
    return array.toString()
}
