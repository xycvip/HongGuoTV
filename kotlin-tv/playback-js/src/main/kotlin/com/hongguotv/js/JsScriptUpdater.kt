// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.js

import android.content.Context
import android.util.Base64
import com.dokar.quickjs.QuickJs
import com.hongguotv.core.ScriptChannel
import com.hongguotv.core.ScriptManifest
import com.hongguotv.core.ScriptTransport
import com.hongguotv.core.sha256Hex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** 一次检查的结果。失败不会影响当前运行中的脚本。 */
sealed class ScriptUpdateOutcome {
    /** 距上次检查太近，本次跳过。 */
    data object Skipped : ScriptUpdateOutcome()

    /** 远端与本地一致，无需下载。 */
    data object UpToDate : ScriptUpdateOutcome()

    /** 新脚本已通过四道校验并落盘，调用方可以立即切换。 */
    data class Installed(
        val version: String,
        val channel: String,
        val size: Long,
    ) : ScriptUpdateOutcome()

    data class Failed(val message: String) : ScriptUpdateOutcome()
}

/**
 * main.js 热更新器。
 *
 * 流程：六条线路并发竞速取清单 → 摘要比对 → 下载（同样竞速）→ 四道校验 → 原子落盘。
 * 四道校验缺一不可：
 *  1. 长度必须与清单一致（挡住代理返回的错误页）；
 *  2. 内容形态必须是脚本（长度区间 + 首尾特征，挡住 HTML 与截断）；
 *  3. sha256 必须与清单一致（决定性判据，与 CI 发布的字节完全相同）；
 *  4. 必须能被 QuickJS 编译（挡住「字节对但语法坏」的发布事故）。
 *
 * 任何一步不过都直接放弃本次更新，运行中的脚本保持不变。
 * 校验通过后新脚本立刻落盘，是否马上切换由调用方决定（见 AppViewModel）。
 */
class JsScriptUpdater(
    private val context: Context,
    private val http: OkHttpClient,
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val running = AtomicBoolean(false)

    /** @param force 忽略检查间隔（手动触发时用）。 */
    suspend fun check(force: Boolean = false): ScriptUpdateOutcome = withContext(Dispatchers.IO) {
        if (!running.compareAndSet(false, true)) return@withContext ScriptUpdateOutcome.Skipped
        try {
            performCheck(force)
        } finally {
            running.set(false)
        }
    }

    private suspend fun performCheck(force: Boolean): ScriptUpdateOutcome {
        val now = System.currentTimeMillis()
        if (!force && now - prefs.getLong(KEY_CHECKED_AT, 0L) < MIN_INTERVAL_MS) {
            return ScriptUpdateOutcome.Skipped
        }
        prefs.edit().putLong(KEY_CHECKED_AT, now).apply()

        val (manifest, source) = try {
            fetchManifest()
        } catch (error: Throwable) {
            return fail(error)
        }

        val current = runCatching { HongguoJsStore.get(context, http).contentDigest }.getOrNull()
        if (current != null && current.equals(manifest.sha256, ignoreCase = true)) {
            prefs.edit().putString(KEY_ERROR, "").apply()
            return ScriptUpdateOutcome.UpToDate
        }

        val bytes = try {
            fetchScript(manifest, source)
        } catch (error: Throwable) {
            return fail(error)
        }

        if (!bytes.looksLikeScript(manifest.size)) {
            return fail(IOException("下载内容不是有效脚本"))
        }
        if (!bytes.sha256Hex().equals(manifest.sha256, ignoreCase = true)) {
            return fail(IOException("脚本摘要不匹配"))
        }
        if (!canCompile(bytes)) {
            return fail(IOException("新脚本无法编译，已放弃本次更新"))
        }
        if (!HongguoJs.installUpdate(context, bytes, manifest.version)) {
            return fail(IOException("脚本写入失败"))
        }

        prefs.edit()
            .putString(KEY_VERSION, manifest.version)
            .putLong(KEY_INSTALLED_AT, now)
            .putString(KEY_ERROR, "")
            .apply()
        return ScriptUpdateOutcome.Installed(manifest.version, source.label, bytes.size.toLong())
    }

    // ------------------------------------------------------------------ 网络

    private suspend fun fetchManifest(): Pair<ScriptManifest, ScriptChannel> {
        val tasks = ScriptChannel.ORDER.map { channel ->
            suspend {
                val raw = requestText(channel, ScriptChannel.MANIFEST_PATH, MANIFEST_MAX_CHARS)
                ScriptManifest.parse(raw) to channel
            }
        }
        return raceFirst(tasks)
    }

    private suspend fun fetchScript(manifest: ScriptManifest, source: ScriptChannel): ByteArray {
        // 清单既然从某条线路最快取到，就直接用它拉脚本；失败再依次退到备用线路。
        // 不并发下载：528KB × N 条线路的无效流量在移动网下太浪费。
        val order = (listOf(source) + ScriptChannel.ORDER.filter { it != source }).take(SCRIPT_ATTEMPTS)
        var lastError: Throwable? = null
        for (channel in order) {
            try {
                return download(channel, manifest.path, manifest.size)
            } catch (error: Throwable) {
                lastError = error
            }
        }
        throw lastError ?: IOException("所有线路均不可用")
    }

    private fun requestText(channel: ScriptChannel, path: String, maxChars: Int): String {
        val request = Request.Builder()
            .url(channel.urlFor(path))
            .header("Accept", "application/json, text/plain, */*")
            .header("Cache-Control", "no-cache")
            .build()
        val client = http.newBuilder()
            .callTimeout(MANIFEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val body = response.body?.string().orEmpty()
            if (body.isBlank()) throw IOException("空响应")
            if (body.length > maxChars) throw IOException("响应过大")
            return decodeApiBody(channel, body)
        }
    }

    private fun download(channel: ScriptChannel, path: String, expectedSize: Long): ByteArray {
        val request = Request.Builder().url(channel.urlFor(path)).build()
        val client = http.newBuilder()
            .callTimeout(DOWNLOAD_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val body = response.body ?: throw IOException("空响应")
            val raw = body.bytes()
            val bytes = if (channel.transport == ScriptTransport.GITHUB_API) {
                decodeApiBytes(raw)
            } else {
                raw
            }
            if (bytes.size.toLong() != expectedSize) {
                throw IOException("长度不符（${bytes.size}/$expectedSize）")
            }
            return bytes
        }
    }

    /** GitHub contents API 把内容包在 base64 里；其它线路是原文。 */
    private fun decodeApiBody(channel: ScriptChannel, body: String): String {
        if (channel.transport != ScriptTransport.GITHUB_API) return body
        return String(decodeApiBytes(body.toByteArray(Charsets.UTF_8)), Charsets.UTF_8)
    }

    private fun decodeApiBytes(raw: ByteArray): ByteArray {
        val json = JSONObject(String(raw, Charsets.UTF_8))
        val encoded = json.optString("content")
        if (encoded.isBlank()) throw IOException("接口未返回内容")
        return Base64.decode(encoded, Base64.DEFAULT)
    }

    // ------------------------------------------------------------------ 校验

    /** 首尾特征检查：HTML 错误页、被截断的半截文件都会在这里被拦下。 */
    private fun ByteArray.looksLikeScript(expectedSize: Long): Boolean {
        if (size.toLong() != expectedSize) return false
        if (size < ScriptManifest.MIN_SIZE || size > ScriptManifest.MAX_SIZE) return false
        val head = String(this, 0, minOf(size, 2048), Charsets.UTF_8).lowercase()
        if (head.contains("<html") || head.contains("<!doctype")) return false
        val text = String(this, Charsets.UTF_8)
        if (!text.contains("module.exports")) return false
        // 模块约定把导出放在文件末尾；尾部没有导出基本可以判定为截断。
        val tailStart = maxOf(0, size - 8192)
        return String(this, tailStart, size - tailStart, Charsets.UTF_8).contains("module.exports")
    }

    /** QuickJS 编译一次，挡住「字节对但语法坏」的脚本。内存给 48MB 够用。 */
    private suspend fun canCompile(bytes: ByteArray): Boolean =
        withContext(JsRuntimeThreads.dispatcher) {
            runCatching {
                val runtime = QuickJs.create(JsRuntimeThreads.dispatcher)
                try {
                    runtime.memoryLimit = COMPILE_MEMORY_LIMIT
                    runtime.maxStackSize = 4L * 1024 * 1024
                    runtime.compile(String(bytes, Charsets.UTF_8), filename = "main.js")
                    true
                } finally {
                    runCatching { runtime.close() }
                }
            }.getOrDefault(false)
        }

    // ------------------------------------------------------------------ 工具

    private suspend fun <T : Any> raceFirst(tasks: List<suspend () -> T>): T = coroutineScope {
        val results = Channel<Result<T>>(tasks.size)
        val jobs = tasks.map { task ->
            launch(Dispatchers.IO) { results.trySend(runCatching { task() }) }
        }
        var last: Throwable? = null
        try {
            repeat(tasks.size) {
                val result = results.receive()
                val value = result.getOrNull()
                if (value != null) {
                    jobs.forEach { it.cancel() }
                    return@coroutineScope value
                }
                last = result.exceptionOrNull()
            }
            throw last ?: IOException("所有线路均不可用")
        } finally {
            jobs.forEach { it.cancel() }
            results.close()
        }
    }

    private fun fail(error: Throwable): ScriptUpdateOutcome {
        val message = when (error) {
            is IOException -> error.message ?: "网络不可用"
            is IllegalArgumentException -> error.message ?: "清单格式异常"
            else -> error.message ?: error.javaClass.simpleName
        }.orEmpty()
        prefs.edit().putString(KEY_ERROR, message).apply()
        return ScriptUpdateOutcome.Failed(message)
    }

    /** 设置页展示用：上次检查时间、已下载待生效版本。 */
    fun lastCheckedAt(): Long = prefs.getLong(KEY_CHECKED_AT, 0L)

    fun installedAt(): Long = prefs.getLong(KEY_INSTALLED_AT, 0L)

    fun pendingVersion(): String = prefs.getString(KEY_VERSION, "").orEmpty()

    /** 新脚本已切到运行中：清掉「待生效」标记，避免重启后仍显示有未生效版本。 */
    fun clearPending() {
        if (prefs.contains(KEY_VERSION)) prefs.edit().remove(KEY_VERSION).apply()
    }

    fun lastError(): String = prefs.getString(KEY_ERROR, "").orEmpty()

    fun forget() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val PREFS = "script-update-v1"
        private const val KEY_CHECKED_AT = "checkedAt"
        private const val KEY_INSTALLED_AT = "installedAt"
        private const val KEY_VERSION = "version"
        private const val KEY_ERROR = "lastError"

        /** 同一进程内最短重复检查间隔，防止页面重建导致连环触发。 */
        private const val MIN_INTERVAL_MS = 5 * 60 * 1000L
        private const val MANIFEST_TIMEOUT_MS = 8_000L
        private const val DOWNLOAD_TIMEOUT_MS = 45_000L
        private const val MANIFEST_MAX_CHARS = 64 * 1024
        private const val SCRIPT_ATTEMPTS = 3
        private const val COMPILE_MEMORY_LIMIT = 48L * 1024 * 1024
    }
}
