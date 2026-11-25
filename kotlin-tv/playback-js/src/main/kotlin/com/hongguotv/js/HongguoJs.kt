// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.js

import android.content.Context
import com.hongguotv.core.RequestScope
import com.hongguotv.core.sha256Hex
import okhttp3.OkHttpClient
import java.io.File

/**
 * main.js 的热更新脚本门面。
 *
 * APK 不再内置脚本，数据层完全由云端下发：
 *  1. filesDir/hongguo-js/main.js + 同目录 main.js.sha256（当前版本，摘要校验通过才用）；
 *  2. 同目录 main.js.prev + main.js.prev.sha256（上一版，当前版本损坏时兜底）。
 *
 * 两者都不存在或都校验失败时抛 [ScriptUnavailableException]，由 App 侧亮出引导页
 * 并强制下载一份。更新写入由 [JsScriptUpdater] 负责：它在落盘前完成长度、内容形态、
 * 摘要、可编译性四道校验，所以这里只需要信任摘要。
 */
class HongguoJs private constructor(
    private val runner: JsScriptRunner,
    /** 当前脚本 sha256 前 16 位，用于字节码缓存键与诊断。 */
    val scriptDigest: String,
    /** 当前脚本完整 sha256，用于与远端清单比对。 */
    val contentDigest: String,
    /** true = 来自在线更新，false = APK 内置。 */
    val fromUpdate: Boolean,
    /** 脚本头部「构建时间」，空串表示未识别。 */
    val builtAt: String,
) {
    suspend fun call(function: String, vararg args: Any?): JsCallResult {
        val argsJson = args.toJsonArray()
        return RequestScope().runSuspending { runner.call(function, argsJson) }
    }

    companion object {
        private const val UPDATE_DIR = "hongguo-js"
        private const val UPDATE_SCRIPT = "main.js"
        private const val UPDATE_DIGEST = "main.js.sha256"
        private const val PREV_SCRIPT = "main.js.prev"
        private const val PREV_DIGEST = "main.js.prev.sha256"

        private const val STAMP_HEAD_BYTES = 4096

        fun create(
            context: Context,
            http: OkHttpClient,
            store: JsKeyValueStore = InMemoryJsKeyValueStore(),
            onLog: (String) -> Unit = {},
        ): HongguoJs {
            val loaded = loadScript(context)
            val cache = JsBytecodeCache.onDisk(context.filesDir)
            val runner = JsScriptRunner(
                host = JsEngineHost(http, store, onLog),
                scriptSource = { loaded.bytes },
                bytecodeCache = cache,
            )
            return HongguoJs(
                runner = runner,
                scriptDigest = runner.scriptDigest(),
                contentDigest = loaded.digest,
                fromUpdate = loaded.fromUpdate,
                builtAt = loaded.bytes.buildStamp(),
            )
        }

        private class Loaded(
            val bytes: ByteArray,
            val digest: String,
            val fromUpdate: Boolean,
        )

        /** 返回已验证的脚本；在线版损坏时依次退到上一版、内置版。 */
        private fun loadScript(context: Context): Loaded {
            val dir = File(context.filesDir, UPDATE_DIR)
            readValid(File(dir, UPDATE_SCRIPT), File(dir, UPDATE_DIGEST))?.let { (bytes, digest) ->
                return Loaded(bytes, digest, true)
            }
            readValid(File(dir, PREV_SCRIPT), File(dir, PREV_DIGEST))?.let { (bytes, digest) ->
                return Loaded(bytes, digest, true)
            }
            throw ScriptUnavailableException("数据脚本尚未下载")
        }

        /** 本地是否已经有一份可用脚本（不创建运行时，供启动阶段判断）。 */
        fun isInstalled(context: Context): Boolean {
            val dir = File(context.filesDir, UPDATE_DIR)
            return readValid(File(dir, UPDATE_SCRIPT), File(dir, UPDATE_DIGEST)) != null ||
                readValid(File(dir, PREV_SCRIPT), File(dir, PREV_DIGEST)) != null
        }

        /** 读文件并校验摘要；摘要缺失或为空视为有效（兼容早期手动放置的脚本）。 */
        private fun readValid(script: File, digestFile: File): Pair<ByteArray, String>? = runCatching {
            if (!script.isFile) return@runCatching null
            val bytes = script.readBytes()
            if (bytes.isEmpty()) return@runCatching null
            val actual = bytes.sha256Hex()
            val expected = digestFile.takeIf { it.isFile }?.readText()?.trim()?.lowercase().orEmpty()
            if (expected.isNotEmpty() && expected != actual) null else bytes to actual
        }.getOrNull()

        /**
         * 落盘在线脚本：旧版留一份到 main.js.prev，损坏时可回退。
         * 校验由调用方（JsScriptUpdater）完成，这里只做原子写入。
         */
        fun installUpdate(context: Context, bytes: ByteArray): Boolean {
            val digest = bytes.sha256Hex()
            val dir = File(context.filesDir, UPDATE_DIR)
            return runCatching {
                dir.mkdirs()
                val script = File(dir, UPDATE_SCRIPT)
                val digestFile = File(dir, UPDATE_DIGEST)
                if (script.isFile && script.length() > 0) {
                    runCatching { script.copyTo(File(dir, PREV_SCRIPT), overwrite = true) }
                    runCatching {
                        digestFile.takeIf { it.isFile }?.copyTo(File(dir, PREV_DIGEST), overwrite = true)
                    }
                }
                val temp = File(dir, "$UPDATE_SCRIPT.tmp")
                temp.writeBytes(bytes)
                if (!temp.renameTo(script)) {
                    temp.delete()
                    return false
                }
                digestFile.writeText(digest)
                true
            }.getOrDefault(false)
        }

        /** 删除本地脚本（在线版与备份）。没有内置兜底，调用方必须先确保能重新下载。 */
        fun clearUpdate(context: Context): Boolean = runCatching {
            File(context.filesDir, UPDATE_DIR).deleteRecursively()
        }.getOrDefault(false)

        /** 头部注释里的「构建时间：2026-11-24 03:38:11」；识别不到返回空串。 */
        private fun ByteArray.buildStamp(): String {
            val head = String(this, 0, minOf(size, STAMP_HEAD_BYTES), Charsets.UTF_8)
            return Regex("构建时间：([0-9]{4}-[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2})")
                .find(head)?.groupValues?.getOrNull(1).orEmpty()
        }
    }
}

/** 本地没有任何可用脚本（首次安装，或当前版与上一版都损坏）。 */
class ScriptUnavailableException(message: String) : IllegalStateException(message)

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
