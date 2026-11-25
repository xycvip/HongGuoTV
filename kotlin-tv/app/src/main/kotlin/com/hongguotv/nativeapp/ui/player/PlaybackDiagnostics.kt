// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp.ui.player

import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.SystemClock
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * 播放专用持久化现场记录。独立于播放器、JS 引擎和协程生命周期。
 * 每次关键事件立即追加并 flush，优先保留崩溃前最后一步。
 */
internal object PlaybackDiagnostics {
    private const val PREFS = "playback_diagnostics"
    private const val ACTIVE_FILE = "active_session"
    private const val MAX_FILES = 3
    private const val MAX_BYTES = 1024L * 1024L
    private val lock = Any()
    @Volatile private var appContext: Context? = null
    @Volatile private var sessionId: String = ""
    @Volatile private var sessionFile: File? = null
    private var sequence = 0L

    fun initialize(context: Context) {
        synchronized(lock) {
            if (appContext != null) return
            appContext = context.applicationContext
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val previous = prefs.getString(ACTIVE_FILE, null)
            if (!previous.isNullOrBlank()) {
                val file = File(context.filesDir, "playback-diagnostics/$previous")
                appendTo(file, "PROCESS_INTERRUPTED", "上次播放会话未正常结束；可能是闪退、系统回收或强制停止", emptyMap())
                prefs.edit().remove(ACTIVE_FILE).commit()
            }
        }
    }

    fun beginSession(seriesId: String, episodeId: String, episodeIndex: Int, dual: Boolean) {
        synchronized(lock) {
            val context = appContext ?: return
            val dir = File(context.filesDir, "playback-diagnostics")
            if (!dir.exists()) dir.mkdirs()
            sessionId = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + "-" + UUID.randomUUID().toString().take(8)
            sequence = 0L
            sessionFile = File(dir, "playback-$sessionId.log")
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(ACTIVE_FILE, sessionFile?.name).commit()
            recordLocked("SESSION_START", "播放会话开始", mapOf(
                "series" to safeId(seriesId), "episode" to safeId(episodeId),
                "episodeIndex" to episodeIndex, "dualPlayer" to dual,
                "device" to (Build.MANUFACTURER + " " + Build.MODEL),
                "android" to Build.VERSION.RELEASE, "sdk" to Build.VERSION.SDK_INT,
                "memory" to memorySnapshot(context),
            ))
            rotate(dir)
        }
    }

    fun event(name: String, detail: String = "", fields: Map<String, Any?> = emptyMap()) {
        synchronized(lock) { recordLocked(name, detail, fields) }
    }

    fun endSession(reason: String) {
        synchronized(lock) {
            recordLocked("SESSION_END", reason, emptyMap())
            appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()?.remove(ACTIVE_FILE)?.commit()
            sessionId = ""
            sessionFile = null
        }
    }

    fun readAll(): String = synchronized(lock) {
        val dir = appContext?.let { File(it.filesDir, "playback-diagnostics") } ?: return@synchronized "暂无播放诊断记录"
        val files = dir.listFiles()?.filter { it.isFile && it.name.endsWith(".log") }?.sortedByDescending { it.lastModified() }.orEmpty()
        if (files.isEmpty()) return@synchronized "暂无播放诊断记录"
        files.joinToString("\n\n") { file ->
            runCatching { file.readText(Charsets.UTF_8) }.getOrDefault("[读取失败：${file.name}]")
        }.takeLast(120_000)
    }

    fun clear() {
        synchronized(lock) {
            val dir = appContext?.let { File(it.filesDir, "playback-diagnostics") } ?: return
            dir.listFiles()?.filter { it.isFile && it.name.endsWith(".log") }?.forEach { it.delete() }
            val active = sessionFile
            dir.listFiles()?.filter { it.isFile && it.name.endsWith(".log") && it != active }?.forEach { it.delete() }
            if (active != null) {
                active.writeText("", Charsets.UTF_8)
                recordLocked("RECORDS_CLEARED", "用户清空了播放诊断记录", emptyMap())
            } else {
                sessionFile = null
                sessionId = ""
                appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()?.remove(ACTIVE_FILE)?.commit()
            }
        }
    }

    private fun recordLocked(name: String, detail: String, fields: Map<String, Any?>) {
        val file = sessionFile ?: return
        val context = appContext
        val enriched = LinkedHashMap<String, Any?>()
        enriched.putAll(fields)
        if (name in setOf("PLAYER_STATE", "PLAYER_ERROR", "EPISODE_TRANSITION", "QUEUE_START", "QUEUE_READY", "PLAYER_RELEASE")) {
            if (context != null) enriched["memory"] = memorySnapshot(context)
        }
        appendTo(file, name, detail, enriched)
        if (file.length() > MAX_BYTES) {
            runCatching {
                val tail = file.readText(Charsets.UTF_8).takeLast((MAX_BYTES * 3L / 4L).toInt())
                file.writeText(tail, Charsets.UTF_8)
            }
        }
        rotate(file.parentFile)
    }

    private fun appendTo(file: File, name: String, detail: String, fields: Map<String, Any?>) {
        runCatching {
            file.parentFile?.mkdirs()
            val obj = JSONObject()
            obj.put("time", SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date()))
            obj.put("elapsedMs", SystemClock.elapsedRealtime())
            obj.put("seq", ++sequence)
            obj.put("session", sessionId)
            obj.put("event", name)
            obj.put("detail", detail.take(1200))
            fields.forEach { (key, value) -> obj.put(key, value ?: JSONObject.NULL) }
            file.appendText(obj.toString() + "\n", Charsets.UTF_8)
        }
    }

    private fun memorySnapshot(context: Context): String {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
        val info = android.app.ActivityManager.MemoryInfo()
        am?.getMemoryInfo(info)
        val runtime = Runtime.getRuntime()
        return "availMB=${info.availMem / 1048576L},totalMB=${info.totalMem / 1048576L},lowMemory=${info.lowMemory},heapUsedMB=${(runtime.totalMemory()-runtime.freeMemory())/1048576L},nativeHeapMB=${Debug.getNativeHeapAllocatedSize()/1048576L}"
    }

    private fun safeId(value: String): String = value
        .replace(Regex("(?i)(token|sign|key|cookie|auth)=[^& ]+"), "$1=<redacted>")
        .take(160)

    private fun rotate(dir: File?) {
        if (dir == null || !dir.exists()) return
        val files = dir.listFiles()?.filter { it.isFile && it.name.endsWith(".log") }
            ?.sortedByDescending { it.lastModified() }.orEmpty()
        files.drop(MAX_FILES).forEach { it.delete() }
    }
}
