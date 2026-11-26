// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.hongguotv.core.*
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

// The backup and native library use the same immutable progress shape.
typealias WatchProgress = BackupProgress

/** Memory-only UI access; disk reads, JSON serialization and commits run on workers. */
class Library(context: Context) {
    /**
     * Persistence belongs to the process, not to an Activity/ViewModel.
     * Reusing the serial queues avoids waiting up to 30 seconds for a retired
     * Activity's writer when the app is opened again in the same process.
     */
    private class PersistenceQueues(context: Context) {
        val prefs: SharedPreferences = context.getSharedPreferences("native-library-v1", Context.MODE_PRIVATE)
        val homePrefs: SharedPreferences = context.getSharedPreferences("native-home-cache-v1", Context.MODE_PRIVATE)
        val writes = LatestWriteQueue<String, () -> Any?>(
            "hongguotv-library-write",
            write = { batch -> commit(prefs, batch) },
            failed = { Log.w("HongGuoTV", "Local library commit failed; pending changes retained for retry") },
        )
        val homeWrites = LatestWriteQueue<String, () -> Any?>(
            "hongguotv-home-write",
            write = { batch -> commit(homePrefs, batch) },
            failed = { Log.w("HongGuoTV", "Home cache commit failed; pending changes retained for retry") },
        )

        private fun commit(target: SharedPreferences, values: Map<String, () -> Any?>): Boolean {
            val edit = target.edit()
            values.forEach { (key, encode) ->
                when (val value = encode()) {
                    null -> edit.remove(key)
                    is String -> edit.putString(key, value)
                    is Int -> edit.putInt(key, value)
                    is Float -> edit.putFloat(key, value)
                    is Boolean -> edit.putBoolean(key, value)
                    is Set<*> -> edit.putStringSet(key, value.filterIsInstance<String>().toSet())
                    else -> error("Unsupported library value")
                }
            }
            return edit.commit()
        }
    }

    private companion object {
        @Volatile private var processPersistence: PersistenceQueues? = null

        private fun persistenceFor(context: Context): PersistenceQueues {
            processPersistence?.let { return it }
            return synchronized(this) {
                processPersistence ?: PersistenceQueues(context.applicationContext).also { processPersistence = it }
            }
        }
    }

    private val app = context.applicationContext
    private val persistence = persistenceFor(app)
    private val main = Handler(Looper.getMainLooper())
    private val loader = Executors.newSingleThreadExecutor { task -> Thread(task, "hongguotv-library-load") }
    private val prefs = persistence.prefs
    private val homePrefs = persistence.homePrefs
    private val writes = persistence.writes
    private val homeWrites = persistence.homeWrites
    private var index: LibraryIndex? = null
    private val homes = mutableMapOf<ContentType, CatalogPage>()
    private val homeSavedAt = mutableMapOf<ContentType, Long>()
    private var loading = false
    private var closed = false
    private val readyCallbacks = mutableListOf<() -> Unit>()
    private val errorCallbacks = mutableListOf<(Throwable) -> Unit>()
    val isLoaded get() = index != null

    /** Call before showing the first page. Successful/error callbacks run on the main thread. */
    fun loadAsync(onFailure: (Throwable) -> Unit = { Log.e("HongguoTV", "Local library failed to load", it) }, onReady: () -> Unit) {
        check(!closed)
        if (isLoaded) { onReady(); return }
        readyCallbacks += onReady
        errorCallbacks += onFailure
        if (loading) return
        loading = true
        loader.execute {
            val result = runCatching {
                // Flush the previous session's checkpoints, but never let a failed disk
                // commit hold the second launch behind the old queue for 30 seconds.
                // Both sessions share the same serial writers, so later values still win.
                writes.flush()
                homeWrites.flush()
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
                val libraryWritesReady = writes.awaitIdle(2, TimeUnit.SECONDS)
                val remaining = (deadline - System.nanoTime()).coerceAtLeast(0L)
                val homeWritesReady = homeWrites.awaitIdle(remaining, TimeUnit.NANOSECONDS)
                if (!libraryWritesReady || !homeWritesReady) {
                    Log.w("HongGuoTV", "Previous library checkpoint is still pending; continuing startup")
                }
                // A failed previous-session commit may still be in flight after the bounded
                // wait. Overlay its newest immutable snapshot so this launch never initializes
                // from stale disk data and then overwrites the user's last changes.
                val values = prefs.all.toMutableMap()
                writes.snapshotPending().forEach { (key, encode) ->
                    val value = encode()
                    if (value == null) values.remove(key) else values[key] = value
                }
                val homeValues = homePrefs.all.toMutableMap()
                homeWrites.snapshotPending().forEach { (key, encode) ->
                    val value = encode()
                    if (value == null) homeValues.remove(key) else homeValues[key] = value
                }
                val loaded = LibraryDisk.decode(values)
                val now = System.currentTimeMillis()
                val cached = mutableMapOf<ContentType, CatalogPage>()
                val stamps = mutableMapOf<ContentType, Long>()
                val migration = homePrefs.edit()
                val oldKeys = mutableListOf<String>()
                ContentType.entries.forEach { type ->
                    val key = "home-${type.storedValue}"
                    val legacy = values[key] as? String
                    val raw = (homeValues[key] as? String) ?: legacy.orEmpty()
                    CatalogSnapshot.decode(raw, now)?.let { page ->
                        cached[type] = page
                        stamps[type] = org.json.JSONObject(raw).getLong("savedAt")
                        if (!homeValues.containsKey(key)) migration.putString(key, raw)
                    }
                    if (legacy != null) oldKeys += key
                }
                // Do not race an older process-session cache write with migration.
                // A later launch can retry migration after the shared writer becomes idle.
                if (homeWritesReady && oldKeys.isNotEmpty() && migration.commit()) {
                    prefs.edit().also { edit -> oldKeys.forEach(edit::remove) }.commit()
                }
                Triple(loaded, cached, stamps)
            }
            main.post {
                loading = false
                if (closed) { readyCallbacks.clear(); errorCallbacks.clear(); return@post }
                val ready = readyCallbacks.toList(); readyCallbacks.clear()
                val errors = errorCallbacks.toList(); errorCallbacks.clear()
                result.onSuccess { (loaded, cached, stamps) ->
                    index = loaded; homes.putAll(cached); homeSavedAt.putAll(stamps)
                    ready.forEach { it() }
                }.onFailure { failure -> errors.forEach { it(failure) } }
            }
        }
    }
    private fun state(): LibraryIndex = checkNotNull(index) { "Load the local library before accessing it" }
    private fun put(key: String, value: Any?) { writes.submit(mapOf(key to { value })) }
    private fun persist(vararg keys: String) {
        val state = state()
        val batch = linkedMapOf<String, () -> Any?>()
        keys.forEach { key ->
            // Each lambda captures an immutable snapshot; JSON is built by the serial writer.
            batch[key] = when (key) {
                "favorites" -> state.favorites().let { rows -> { LibraryDisk.series(rows) } }
                "later" -> state.later().let { rows -> { LibraryDisk.series(rows) } }
                "progress" -> state.history().let { rows -> { LibraryDisk.history(rows) } }
                "favoriteUpdates" -> state.updates().let { rows -> { LibraryDisk.updates(rows) } }
                "favoriteGroups" -> state.groupsSnapshot().let { rows -> { LibraryDisk.groups(rows) } }
                "searches" -> state.searches().let { rows -> { SearchHistory.encode(rows) } }
                "watched" -> state.watched().let { ids -> { ids } }
                "hidden" -> state.hidden().let { ids -> { ids } }
                else -> error("Unknown library partition")
            }
        }
        writes.submit(batch)
    }
    fun favorites() = state().favorites()
    fun favorite(id: String) = state().favorite(id)
    fun later() = state().later()
    fun queued(id: String) = state().queued(id)
    fun history(): List<WatchProgress> = state().history()
    fun progress(id: String): WatchProgress? = state().progress(id)
    fun searches() = state().searches()
    fun watched(id: String) = state().watched(id)
    fun hidden() = state().hidden()
    fun favoriteUpdate(id: String) = state().favoriteUpdate(id)
    fun updatedFavorites() = state().updatedFavorites()
    fun updates() = state().updates()
    fun groups() = state().groups()
    fun groupOf(id: String) = state().groupOf(id)
    fun setGroup(id: String, group: String?): Boolean = state().setGroup(id, group).also { if (it) persist("favoriteGroups") }
    fun favoriteLabel(id: String): String = favoriteUpdate(id)?.let {
        val season = if (it.hasNewSeason) "《${it.newSeasonTitle ?: "新季"}》已上线" else ""
        val episodes = if (it.added > 0) "新增 ${it.added} 集 · 更新至 ${it.total} 集" else "更新至 ${it.total} 集"
        listOf(season, episodes).filter { it.isNotEmpty() }.joinToString(" · ")
    } ?: "尚未检查更新"
    fun toggle(series: Series) = state().toggle(series).also { persist("favorites", "favoriteUpdates") }
    fun toggleLater(series: Series) = state().toggleLater(series).also { persist("later") }
    fun save(progress: WatchProgress) { if (state().save(progress)) persist("progress") }
    fun setWatched(id: String, value: Boolean) { if (state().setWatched(id, value)) persist("watched") }
    fun hide(id: String) { state().hide(id); persist("hidden") }
    fun unhideAll() { state().unhideAll(); persist("hidden") }
    fun rememberSearch(query: String, type: ContentType) { state().rememberSearch(query, type); persist("searches") }
    fun clearSearches() { state().clearSearches(); persist("searches") }
    fun removeHistory(id: String) { state().removeHistory(id); persist("progress", "watched") }
    fun clearHistory() { state().clearHistory(); persist("progress", "watched") }
    fun observeFavorite(id: String, count: Int, now: Long, acknowledge: Boolean = false, newSeason: Series? = null) {
        if (state().observeFavorite(id, count, now, acknowledge, newSeason)) persist("favoriteUpdates")
    }
    fun cachedHome(type: ContentType): CatalogPage? {
        state()
        val age = System.currentTimeMillis() - (homeSavedAt[type] ?: return null)
        return homes[type].takeIf { age in 0..7 * 24 * 60 * 60 * 1000L }
    }
    fun cacheHome(type: ContentType, items: List<Series>, hasMore: Boolean) {
        state()
        if (items.isEmpty()) return
        val now = System.currentTimeMillis()
        val retained = items.take(30).map { it.copy(description = it.description.take(1500)) }
        homes[type] = CatalogPage(retained, hasMore)
        homeSavedAt[type] = now
        homeWrites.submit(mapOf("home-${type.storedValue}" to { CatalogSnapshot.encode(retained, hasMore, now) }))
    }
    var maxQuality: Int
        get() = state().settings.quality
        set(value) { val next = PlaybackQuality.normalize(value); if (next != maxQuality) { state().settings = state().settings.copy(quality = next); put("quality", next) } }
    var autoNext: Boolean
        get() = state().settings.autoNext
        set(value) { if (value != autoNext) { state().settings = state().settings.copy(autoNext = value); put("autoNext", value) } }
    var autoSeason: Boolean
        get() = state().settings.autoSeason
        set(value) { if (value != autoSeason) { state().settings = state().settings.copy(autoSeason = value); put("autoSeason", value) } }
    var danmaku: Boolean
        get() = state().settings.danmaku
        set(value) { if (value != danmaku) { state().settings = state().settings.copy(danmaku = value); put("danmaku", value) } }
    var danmakuOpacity: Int
        get() = state().settings.danmakuOpacity
        set(value) { val next = DanmakuPrefs.opacity(value); if (next != danmakuOpacity) { state().settings = state().settings.copy(danmakuOpacity = next); put("danmakuOpacity", next) } }
    var danmakuFont: Int
        get() = state().settings.danmakuFont
        set(value) { val next = DanmakuPrefs.font(value); if (next != danmakuFont) { state().settings = state().settings.copy(danmakuFont = next); put("danmakuFont", next) } }
    var danmakuSpeed: Int
        get() = state().settings.danmakuSpeed
        set(value) { val next = DanmakuPrefs.speed(value); if (next != danmakuSpeed) { state().settings = state().settings.copy(danmakuSpeed = next); put("danmakuSpeed", next) } }
    var danmakuRows: Int
        get() = state().settings.danmakuRows
        set(value) { val next = DanmakuPrefs.rows(value); if (next != danmakuRows) { state().settings = state().settings.copy(danmakuRows = next); put("danmakuRows", next) } }
    var playbackSpeed: Float
        get() = state().settings.speed
        set(value) { val next = PlaybackSpeed.normalize(value); if (next != playbackSpeed) { state().settings = state().settings.copy(speed = next); put("playbackSpeed", next) } }
    var frameMode: VideoFrameMode
        get() = state().settings.frame
        set(value) { if (value != frameMode) { state().settings = state().settings.copy(frame = value); put("frameMode", value.name) } }
    var showClock: Boolean
        get() = state().settings.showClock
        set(value) { if (value != showClock) { state().settings = state().settings.copy(showClock = value); put("showClock", value) } }
    /** Player overlays are device-local display preferences, independent of playback and clock visibility. */
    var videoInfoMode: Int
        get() = prefs.getInt("videoInfoMode", 0).coerceIn(0, 2)
        set(value) { prefs.edit().putInt("videoInfoMode", value.coerceIn(0, 2)).apply() }
    var showNetworkSpeed: Boolean
        get() = prefs.getBoolean("showNetworkSpeed", false)
        set(value) { prefs.edit().putBoolean("showNetworkSpeed", value).apply() }
    /** 屏幕最底部常驻一条极细进度条；设备本地显示偏好，默认关闭。 */
    var persistentProgress: Boolean
        get() = prefs.getBoolean("persistentProgress", false)
        set(value) { prefs.edit().putBoolean("persistentProgress", value).apply() }
    /** 是否在页面顶部显示可点击的返回箭头；默认关闭，遥控器返回键始终可用。 */
    var showScreenBack: Boolean
        get() = prefs.getBoolean("showScreenBack", false)
        set(value) { prefs.edit().putBoolean("showScreenBack", value).apply() }
    var theme: String
        get() = prefs.getString("theme", null) ?: "NIGHT"
        set(value) { prefs.edit().putString("theme", value).apply() }
    // Per-series intro/outro skips are stored locally and included in exported backups.
    private var marksCache: LinkedHashMap<String, SkipMark>? = null
    private fun marks(): LinkedHashMap<String, SkipMark> =
        marksCache ?: SkipMarks.decode(prefs.getString("skipMarks", null)).also { marksCache = it }
    fun skipMark(id: String): SkipMark? = marks()[id]
    fun setSkipMark(id: String, mark: SkipMark?) {
        val next = SkipMarks.set(marks(), id, mark)
        marksCache = next
        prefs.edit().putString("skipMarks", SkipMarks.encode(next)).apply()
    }
    fun skipMarksSnapshot(): Map<String, SkipMark> = marks().toMap()
    fun restoreSkipMarks(incoming: Map<String, SkipMark>) {
        val normalized = SkipMarks.decode(SkipMarks.encode(incoming))
        marksCache = normalized
        prefs.edit().putString("skipMarks", SkipMarks.encode(normalized)).apply()
    }
    fun snapshot() = state().snapshot()

    fun restore(incoming: BackupData, restoreSettings: Boolean) {
        // 文件恢复以备份为准覆盖本机记录；仅在用户选择保留时沿用当前播放/设备偏好。
        val current = snapshot()
        val restored = incoming.copy(settings = if (restoreSettings) incoming.settings else current.settings)
        index = LibraryIndex(restored, emptyMap())
        if (restoreSettings) {
            // A-D 设置中有一部分保存在独立 SharedPreferences，必须与 Library 设置一起恢复。
            prefs.edit()
                .putString("theme", restored.settings.theme)
                .putInt("videoInfoMode", restored.settings.videoInfoMode.coerceIn(0, 2))
                .putBoolean("showNetworkSpeed", restored.settings.showNetworkSpeed)
                .apply()
            app.getSharedPreferences("startup_playback", Context.MODE_PRIVATE).edit()
                .putBoolean("autoplay_recommendation", restored.settings.startupAutoplay)
                .apply()
            app.getSharedPreferences("playback-settings-v1", Context.MODE_PRIVATE).edit()
                .putInt("sleep_action_mode", restored.settings.sleepActionMode.coerceIn(0, 1))
                .apply()
        }
        persist("favorites", "later", "progress", "watched", "hidden", "searches", "favoriteUpdates", "favoriteGroups")
        writes.submit(mapOf("quality" to { restored.settings.quality }, "playbackSpeed" to { restored.settings.speed },
            "autoNext" to { restored.settings.autoNext }, "frameMode" to { restored.settings.frame.name },
            "autoSeason" to { restored.settings.autoSeason }, "danmaku" to { restored.settings.danmaku },
            "danmakuOpacity" to { restored.settings.danmakuOpacity }, "danmakuFont" to { restored.settings.danmakuFont },
            "danmakuSpeed" to { restored.settings.danmakuSpeed }, "danmakuRows" to { restored.settings.danmakuRows }, "showClock" to { restored.settings.showClock }))
        flush()
    }
    /** Reset only playback preferences; preserve catalog filters and device/interface preferences. */
    fun resetPlaybackPreferences() {
        val defaults = LibraryDisk.decode(emptyMap<String, Any?>()).settings
        maxQuality = defaults.quality
        playbackSpeed = defaults.speed
        autoNext = defaults.autoNext
        autoSeason = defaults.autoSeason
        frameMode = defaults.frame
        danmaku = defaults.danmaku
        danmakuOpacity = defaults.danmakuOpacity
        danmakuFont = defaults.danmakuFont
        danmakuSpeed = defaults.danmakuSpeed
        danmakuRows = defaults.danmakuRows
        marksCache = linkedMapOf()
        prefs.edit().remove("skipMarks").apply()
    }

    /** Restore playback/interface preferences while retaining all user library data. */
    fun resetSettings() {
        val defaults = LibraryDisk.decode(emptyMap<String, Any?>()).settings
        state().settings = defaults
        marksCache = linkedMapOf()
        prefs.edit().remove("theme").remove("skipMarks").remove("videoInfoMode").remove("showNetworkSpeed")
            .remove("persistentProgress").remove("showScreenBack").apply()
        writes.submit(mapOf(
            "quality" to { defaults.quality }, "playbackSpeed" to { defaults.speed },
            "autoNext" to { defaults.autoNext },
            "frameMode" to { defaults.frame.name }, "autoSeason" to { defaults.autoSeason },
            "danmaku" to { defaults.danmaku }, "danmakuOpacity" to { defaults.danmakuOpacity },
            "danmakuFont" to { defaults.danmakuFont }, "danmakuSpeed" to { defaults.danmakuSpeed },
            "danmakuRows" to { defaults.danmakuRows }, "showClock" to { defaults.showClock }
        ))
    }

    /** Close and wait for queued library writes before an all-data reset removes storage. */
    fun closeAndAwait(timeoutSeconds: Long = 15): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
        close()
        val loaderDone = loader.awaitTermination(timeoutSeconds, TimeUnit.SECONDS)
        val afterLoader = (deadline - System.nanoTime()).coerceAtLeast(0L)
        val writesDone = writes.awaitIdle(afterLoader, TimeUnit.NANOSECONDS)
        val remaining = (deadline - System.nanoTime()).coerceAtLeast(0L)
        val homesDone = homeWrites.awaitIdle(remaining, TimeUnit.NANOSECONDS)
        return loaderDone && writesDone && homesDone
    }

    /** Requests prompt background commit, without lifecycle-thread disk waits. */
    fun flush() {
        writes.flush(); homeWrites.flush()
    }
    /** Close this Library view; process-owned persistence queues remain reusable for relaunch. */
    fun close() {
        if (closed) return
        closed = true
        readyCallbacks.clear(); errorCallbacks.clear()
        loader.shutdown()
        // Keep the process-owned queues alive for a quick relaunch; flush without blocking
        // the lifecycle thread. Their single writer preserves ordering across Library instances.
        writes.flush()
        homeWrites.flush()
    }
}
