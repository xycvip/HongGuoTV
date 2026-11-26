// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp.ui.data

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hongguotv.core.BackupProgress
import com.hongguotv.core.CloudProfile
import com.hongguotv.core.ContentRepository
import com.hongguotv.core.ContentType
import com.hongguotv.core.Detail
import com.hongguotv.core.JsAccount
import com.hongguotv.core.JsContent
import com.hongguotv.core.PhoneField
import com.hongguotv.core.PhoneForm
import com.hongguotv.core.PhoneInputServer
import com.hongguotv.core.PlaybackQuality
import com.hongguotv.core.PlaybackSpeed
import com.hongguotv.core.RankBoard
import com.hongguotv.core.RecentSearch
import com.hongguotv.core.Series
import com.hongguotv.core.TheaterFilterGroup
import com.hongguotv.core.VideoFrameMode
import com.hongguotv.core.AppUpdate
import com.hongguotv.core.AppUpdateRepository
import com.hongguotv.core.DanmakuPrefs
import com.hongguotv.core.DnsProvider
import com.hongguotv.core.DnsSettings
import com.hongguotv.core.NetworkTuning
import com.hongguotv.js.HongguoJs
import com.hongguotv.js.HongguoJsAccount
import com.hongguotv.js.HongguoJsContent
import com.hongguotv.js.HongguoJsStore
import com.hongguotv.js.HongguoJsStreamSource
import com.hongguotv.js.JsScriptUpdater
import com.hongguotv.js.ScriptUpdateOutcome
import com.hongguotv.nativeapp.ui.components.qrPngBytes
import com.hongguotv.nativeapp.AppUpdateInstaller
import com.hongguotv.nativeapp.BuildConfig
import com.hongguotv.nativeapp.Library
import com.hongguotv.nativeapp.PlaybackExtras
import com.hongguotv.nativeapp.playbackStreamSource
import com.hongguotv.nativeapp.ui.ComposeActivity
import com.hongguotv.nativeapp.ui.nav.CollectionKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.net.Inet4Address
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.system.exitProcess

/**
 * 全应用唯一的数据入口。网络与解析在 IO 线程完成，本地片单在主线程读写，
 * 页面只订阅不可变状态，避免旧实现里到处手动刷新 View 的碎片化逻辑。
 */
class AppViewModel(app: Application) : AndroidViewModel(app) {

    val repository = ContentRepository()
    val library = Library(app)
    val extras = PlaybackExtras(app)
    private val prefs = app.getSharedPreferences("native-library-v1", Context.MODE_PRIVATE)
    private val accountPrefs = app.getSharedPreferences("compose-account-v1", Context.MODE_PRIVATE)

    /** 脚本热更新器：走共享连接池与统一 DNS，检查结果持久化在独立 prefs。 */
    private val scriptUpdater = JsScriptUpdater(app, repository.http)

    // Gateways are process-global registries; keep our exact instances so this ViewModel
    // can unregister them on clear without erasing a newer ViewModel's registrations.
    private var installedJs: HongguoJs? = null
    private var installedContentGateway: HongguoJsContent? = null
    private var installedAccountGateway: HongguoJsAccount? = null

    private val _state = MutableStateFlow(AppState())
    val state: StateFlow<AppState> = _state.asStateFlow()

    /** 接口指示器状态：顶栏品牌灯与「设置 · 网络」共用。 */
    private val _apiStatus = MutableStateFlow(ApiStatusState())
    val apiStatus: StateFlow<ApiStatusState> = _apiStatus.asStateFlow()

    /** 更新弹窗状态：检查、下载、校验、安装全流程共用。 */
    private val _update = MutableStateFlow(UpdateUiState())
    val update: StateFlow<UpdateUiState> = _update.asStateFlow()
    private var updateJob: Job? = null

    /** 数据脚本热更新状态：设置页只读展示，更新过程完全自动。 */
    private val _scriptUpdate = MutableStateFlow(ScriptUpdateState())
    val scriptUpdate: StateFlow<ScriptUpdateState> = _scriptUpdate.asStateFlow()

    /**
     * 播放页是否可见。热更新在播放中不切换脚本：
     * 换集会重新取流，切换后可能拿到另一条线路或另一套 ID。
     */
    @Volatile
    private var playerVisible = false

    /** 新脚本已落盘但当时正在播放，等退出播放页再换。 */
    private var scriptSwitchPending = false

    /** 切换正在进行：巡检与「退出播放」可能同时触发，只让第一个进。 */
    private var scriptSwitching = false

    private var homeShort: List<Series> = emptyList()
    private var homeComic: List<Series> = emptyList()
    private var homeAi: List<Series> = emptyList()
    private var homeJob: Job? = null
    /** 冷启动用缓存顶首屏后，网络刷新被推迟到「切走首页」或兜底计时器。 */
    private var homeRefreshPending = false
    private var homeDeferJob: Job? = null
    /** 上次首页网络拉取成功的时间，用于回首页时判断是否已经过期。 */
    private var homeFetchedAt = 0L
    @Volatile
    private var homeVisible = true
    private var theaterJob: Job? = null
    private var rankingJob: Job? = null
    private var theaterMoreJob: Job? = null
    private var rankingMoreJob: Job? = null
    private var theaterGeneration = 0L
    private var rankingGeneration = 0L
    /** 加载更多连续失败次数：单次失败不再判到底，连续失败够了才收手。 */
    private var theaterMoreFailures = 0
    private var rankingMoreFailures = 0
    /** 连续空页计数：服务端返回空页但 has_more=true 时用来跳过并最终收尾。 */
    private var theaterEmptyPages = 0
    private var rankingEmptyPages = 0
    private var searchJob: Job? = null
    private var suggestJob: Job? = null
    private var accountPoll: Job? = null
    private var scriptBootstrapJob: Job? = null
    private val jsAccount get() = JsAccount.required()

    val isTelevision: Boolean
        get() = getApplication<Application>().packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK)

    init {
        ensureLauncherAliasConsistency()
        applyDnsSettings()
        scriptUpdater.clearPending()
        // APK 不再内置 main.js：本地有脚本才装门面进主界面，否则先走引导下载。
        val ready = HongguoJsStore.getOrNull(app, repository.http)
        if (ready != null) {
            installScriptGateways(ready)
            startBoot()
        } else {
            _state.update { it.copy(scriptSetup = ScriptSetupState(preparing = true)) }
            launchBootstrap()
        }
    }

    /** 把脚本实例接到内容与账号两个门面上；切换脚本时也走这里。 */
    private fun installScriptGateways(js: HongguoJs) {
        val contentGateway = HongguoJsContent(js)
        val accountGateway = HongguoJsAccount(js)
        installedJs = js
        installedContentGateway = contentGateway
        installedAccountGateway = accountGateway
        JsContent.gateway = contentGateway
        JsAccount.gateway = accountGateway
        _scriptUpdate.value = ScriptUpdateState(
            version = js.builtAt,
            digest = js.contentDigest.take(8),
            fromUpdate = js.fromUpdate,
            // 能走到这里说明落盘的脚本已经加载生效，不存在「待生效」。
            pendingVersion = "",
            checkedAt = scriptUpdater.lastCheckedAt(),
            installedAt = scriptUpdater.installedAt(),
            lastError = scriptUpdater.lastError().ifBlank { null },
        )
    }

    private var bootStarted = false

    private fun startBoot() {
        if (bootStarted) return
        bootStarted = true
        library.loadAsync(
            onFailure = { failure ->
                _state.update {
                    it.copy(bootError = failure.message ?: "本地数据读取失败", scriptSetup = ScriptSetupState())
                }
            },
            onReady = {
                _state.update { it.copy(booted = true, bootError = null, scriptSetup = ScriptSetupState()) }
                refreshLibraryState()
                // 首屏只上缓存，网络请求推迟到切走首页（或 45 秒兜底）。
                loadHome(deferNetwork = true)
                restoreAccount()
                bootstrapPreferences()
                // 接口探针会并发发起多次脚本调用；延后到首屏渲染完成后再跑，首屏阶段只留首页那一个 runtime。
                viewModelScope.launch {
                    delay(5_000)
                    refreshApiStatus()
                }
                // 只做 DNS 预解析：搜索/联想/详情首次解析不再等系统 getaddrinfo。
                viewModelScope.launch {
                    delay(1_200)
                    withContext(Dispatchers.IO) { NetworkTuning.warmDns() }
                }
                startScriptAutoUpdate()
            },
        )
    }

    /**
     * 首次安装的脚本下载。APK 不带 main.js，这条路走不通就进不了主界面。
     *
     * 自动重试两次（间隔 4s / 8s）：刚开机 Wi-Fi 往往还没就绪，
     * 比让用户立刻看到「下载失败」更有用。仍失败才把重试交给用户。
     */
    private fun launchBootstrap() {
        if (scriptBootstrapJob?.isActive == true) return
        scriptBootstrapJob = viewModelScope.launch { bootstrapScript() }
    }

    private suspend fun bootstrapScript() {
        var attempt = 0
        while (true) {
            val outcome = scriptUpdater.check(force = true)
            if (outcome is ScriptUpdateOutcome.Installed || outcome is ScriptUpdateOutcome.UpToDate) {
                HongguoJsStore.reload()
                val js = HongguoJsStore.getOrNull(getApplication(), repository.http)
                if (js != null) {
                    installScriptGateways(js)
                    // 引导页保留到本地库加载完成（onReady 里收起），中间不会闪一帧空主界面。
                    startBoot()
                    return
                }
            }
            attempt++
            if (attempt > SCRIPT_BOOTSTRAP_RETRIES) {
                val message = bootstrapErrorText(
                    (outcome as? ScriptUpdateOutcome.Failed)?.message ?: "数据脚本下载失败",
                )
                _state.update { it.copy(scriptSetup = ScriptSetupState(preparing = false, error = message)) }
                return
            }
            _state.update { it.copy(scriptSetup = ScriptSetupState(preparing = true)) }
            delay(SCRIPT_BOOTSTRAP_RETRY_MS * attempt)
        }
    }

    /** 把更新器里的技术性错误翻成能照着做的一句话。 */
    private fun bootstrapErrorText(raw: String): String = when {
        raw.contains("HTTP 4") || raw.contains("HTTP 5") -> "发布服务器暂时不可用（$raw），请稍后重试"
        raw.contains("超时") || raw.contains("timeout", ignoreCase = true) -> "连接发布通道超时，请检查电视网络"
        raw.contains("长度") || raw.contains("摘要") || raw.contains("编译") || raw.contains("有效脚本") ->
            "下载内容校验未通过，已自动重试"
        else -> raw
    }

    /** 引导页「重试」。 */
    fun retryScriptBootstrap() {
        if (_state.value.scriptSetup.preparing) return
        _state.update { it.copy(scriptSetup = ScriptSetupState(preparing = true)) }
        launchBootstrap()
    }

    // ---------------------------------------------------------------- 网络

    private fun applyDnsSettings() {
        val provider = prefs.getString("dnsProvider", DnsProvider.TENCENT.key)
        val custom = prefs.getString("dnsCustom", "")
        DnsSettings.update(provider, custom)
    }

    fun setDnsProvider(key: String) {
        val provider = DnsProvider.of(key)
        prefs.edit().putString("dnsProvider", provider.key).apply()
        DnsSettings.update(provider.key, prefs.getString("dnsCustom", ""))
        touchSettings()
        toast(
            if (provider == DnsProvider.CUSTOM && DnsSettings.parseServers(prefs.getString("dnsCustom", "")).isEmpty()) {
                "DNS · 自定义（未填写服务器，暂用系统）"
            } else {
                "DNS · ${DnsSettings.activeLabel()}"
            },
        )
    }

    fun setDnsCustom(text: String) {
        val servers = DnsSettings.parseServers(text)
        prefs.edit().putString("dnsCustom", servers.joinToString(", ")).apply()
        DnsSettings.update(prefs.getString("dnsProvider", DnsProvider.TENCENT.key), servers.joinToString(", "))
        touchSettings()
        toast(if (servers.isEmpty()) "自定义 DNS 已清空" else "DNS · ${servers.joinToString(", ")}")
    }

    private fun bootstrapPreferences() {
        // 首次启动时写入一份默认海报列数与主题偏好，旧版本升级也无需迁移。
        if (!prefs.getBoolean("composeUIInitialized", false)) {
            prefs.edit().putBoolean("composeUIInitialized", true).apply()
        }
    }

    /**
     * 启动时恢复本地登录态。凭据损坏直接清掉；云端资料拉取失败只保留本地登录态，
     * 不清凭据——断网不该让用户掉登录。
     */
    private fun restoreAccount() {
        val saved = accountPrefs.getString("credentials", null) ?: return
        val credentials = runCatching { com.hongguotv.core.AccountCredentials.fromJson(saved) }.getOrNull()
        if (credentials == null) {
            accountPrefs.edit().clear().apply()
            return
        }
        _state.update { it.copy(account = it.account.copy(signedIn = true, busy = true)) }
        viewModelScope.launch {
            val profile = withContext(Dispatchers.IO) { runCatching { jsAccount.profile(credentials) }.getOrNull() }
            val favorites = withContext(Dispatchers.IO) { runCatching { jsAccount.favorites(credentials) }.getOrNull() }
            val history = withContext(Dispatchers.IO) { runCatching { jsAccount.history(credentials) }.getOrNull() }
            if (profile != null) accountPrefs.edit().putString("nickname", profile.nickname).apply()
            _state.update {
                it.copy(
                    account = it.account.copy(
                        signedIn = true,
                        busy = false,
                        nickname = profile?.nickname ?: it.account.nickname.ifBlank { "红果用户" },
                        avatar = profile?.avatarUrl ?: it.account.avatar,
                        cloudFavorites = favorites?.size ?: it.account.cloudFavorites,
                        cloudHistory = history?.size ?: it.account.cloudHistory,
                    ),
                )
            }
        }
    }

    // ---------------------------------------------------------------- 首页

    /** 本地片单变化（收藏、历史）后只需要重排书架，不必再拉一次网络。 */
    private fun refreshHomeShelves() {
        if (homeShort.isEmpty() && homeComic.isEmpty() && homeAi.isEmpty()) {
            loadHome()
        } else {
            publishHome(loading = false)
        }
    }

    /**
     * 首页加载。
     *
     * [refresh] 下拉刷新：强制走网络，并显示刷新指示。
     * [deferNetwork] 冷启动专用：只要有缓存就只把缓存上屏，网络请求推迟到
     * 用户离开首页（或 45 秒兜底）再静默补——首屏不占网络、不占 JS 运行时，秒开。
     */
    fun loadHome(refresh: Boolean = false, deferNetwork: Boolean = false) {
        if (!library.isLoaded) return
        val cachedShort = library.cachedHome(ContentType.SHORT)?.items.orEmpty()
        val cachedComic = library.cachedHome(ContentType.COMIC)?.items.orEmpty()
        val cachedAi = library.cachedHome(ContentType.AI)?.items.orEmpty()
        if (homeShort.isEmpty()) homeShort = cachedShort
        if (homeComic.isEmpty()) homeComic = cachedComic
        if (homeAi.isEmpty()) homeAi = cachedAi
        val hasCache = homeShort.isNotEmpty() || homeComic.isNotEmpty() || homeAi.isNotEmpty()
        if (!refresh && hasCache) {
            publishHome(loading = false)
        } else {
            _state.update { it.copy(home = it.home.copy(loading = !hasCache, refreshing = refresh, error = null)) }
        }
        homeJob?.cancel()
        homeDeferJob?.cancel()
        // 冷启动命中缓存：不发请求。用户切到别的页面时由 setHomeVisible(false) 触发，
        // 一直停在首页则由兜底计时器补一次，避免内容永远停在缓存版本。
        if (deferNetwork && !refresh && hasCache) {
            homeRefreshPending = true
            homeDeferJob = viewModelScope.launch {
                delay(HOME_DEFER_FALLBACK_MS)
                if (homeRefreshPending) {
                    homeRefreshPending = false
                    loadHome()
                }
            }
            return
        }
        homeRefreshPending = false
        homeJob = viewModelScope.launch {
            // 三类内容并发拉取：每次脚本调用都是一次独立的运行时 + 网络请求，
            // 串行等于把三个 RTT 相加，首屏要等三倍。
            val (short, comic, ai) = withContext(Dispatchers.IO) {
                coroutineScope {
                    val shortJob = async { runCatching { repository.home(1, ContentType.SHORT) }.getOrNull() }
                    val comicJob = async { runCatching { repository.home(1, ContentType.COMIC) }.getOrNull() }
                    val aiJob = async { runCatching { repository.ranking(RankBoard.AI, 1) }.getOrNull() }
                    Triple(shortJob.await(), comicJob.await(), aiJob.await())
                }
            }
            if (short != null) {
                homeShort = short.items
                library.cacheHome(ContentType.SHORT, short.items, short.hasMore)
            }
            if (comic != null) {
                homeComic = comic.items
                library.cacheHome(ContentType.COMIC, comic.items, comic.hasMore)
            }
            if (ai != null) {
                homeAi = ai.items
                library.cacheHome(ContentType.AI, ai.items, ai.hasMore)
            }
            val failed = short == null && comic == null && ai == null
            if (!failed) homeFetchedAt = System.currentTimeMillis()
            publishHome(
                loading = false,
                error = if (failed) "内容加载失败，请检查网络后重试" else null,
            )
        }
    }

    /**
     * 首页可见性由路由与 tab 共同决定。
     *
     * 启动用缓存顶首屏时，这里负责两件事：
     *  - 切走：稍后静默补一次网络（用户看不到骨架，回来已是新内容）；
     *  - 回来：距上次成功拉取超过 5 分钟才再拉，避免来回切页反复请求。
     */
    fun setHomeVisible(visible: Boolean) {
        homeVisible = visible
        if (visible) {
            if (homeRefreshPending) return
            val stale = homeFetchedAt == 0L || System.currentTimeMillis() - homeFetchedAt > HOME_STALE_MS
            if (stale) loadHome()
            return
        }
        if (!homeRefreshPending) return
        homeRefreshPending = false
        viewModelScope.launch {
            // 起播详情页会并发发出详情、系列剧、相关推荐、预取取流四个请求，
            // 让它们先落地再补首页，避免新页面首屏被首页刷新拖慢。
            delay(HOME_BACKGROUND_REFRESH_DELAY_MS)
            if (!homeVisible) loadHome()
        }
    }

    private fun publishHome(loading: Boolean, error: String? = null) {
        val hidden = library.hidden()
        val short = homeShort.filterNot { it.id in hidden }
        val comic = homeComic.filterNot { it.id in hidden }
        val ai = homeAi.filterNot { it.id in hidden }
        val history = library.history().filterNot { library.watched(it.series.id) }
        val updates = library.favorites().filter { library.favoriteUpdate(it.id)?.hasUpdate == true }
        val later = library.later()
        val shelves = buildList {
            if (history.isNotEmpty()) {
                add(
                    Shelf(
                        key = "resume",
                        title = "接着看",
                        subtitle = "上次停在的地方",
                        style = ShelfStyle.RESUME,
                        collection = CollectionKind.RESUME,
                        entries = history.take(10).map {
                            ShelfEntry(
                                series = it.series,
                                label = "第 ${it.episodeIndex + 1} 集 · " + if (it.completed) "看下一集" else formatPosition(it.position),
                                progress = progressOf(it),
                            )
                        },
                    ),
                )
            }
            if (updates.isNotEmpty()) {
                add(
                    Shelf(
                        key = "updates",
                        title = "收藏更新",
                        subtitle = "你收藏的剧有新内容",
                        collection = CollectionKind.UPDATES,
                        entries = updates.take(10).map { ShelfEntry(it, library.favoriteLabel(it.id)) },
                    ),
                )
            }
            if (later.isNotEmpty()) {
                add(
                    Shelf(
                        key = "later",
                        title = "稍后看",
                        subtitle = null,
                        collection = CollectionKind.LATER,
                        entries = later.take(10).map { ShelfEntry(it, it.badge.ifBlank { "待看" }) },
                    ),
                )
            }
            if (short.isNotEmpty()) {
                add(
                    Shelf(
                        key = "hot-short",
                        title = "热门短剧",
                        subtitle = "今天大家都在追",
                        collection = CollectionKind.HOT_SHORT,
                        entries = short.take(12).map { ShelfEntry(it, it.badge.ifBlank { it.tags.take(12) }) },
                    ),
                )
            }
            if (comic.isNotEmpty()) {
                add(
                    Shelf(
                        key = "hot-comic",
                        title = "热门漫剧",
                        collection = CollectionKind.HOT_COMIC,
                        entries = comic.take(12).map { ShelfEntry(it, it.badge.ifBlank { it.tags.take(12) }) },
                    ),
                )
            }
            if (ai.isNotEmpty()) {
                add(
                    Shelf(
                        key = "hot-ai",
                        title = "AI 剧场",
                        subtitle = "新形态的短剧体验",
                        collection = CollectionKind.HOT_AI,
                        entries = ai.take(12).map { ShelfEntry(it, it.badge.ifBlank { it.tags.take(12) }) },
                    ),
                )
            }
        }
        val hero = buildList {
            addAll(short.take(3))
            addAll(comic.take(2))
            addAll(ai.take(2))
        }.filter { it.cover.isNotBlank() }.distinctBy { it.id }.take(6)
        _state.update {
            it.copy(
                home = it.home.copy(
                    loading = loading,
                    refreshing = false,
                    error = error,
                    hero = if (hero.isNotEmpty()) hero else it.home.hero,
                    shelves = shelves,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
        }
    }

    fun setHeroIndex(index: Int) {
        _state.update { it.copy(home = it.home.copy(heroIndex = index)) }
    }

    private fun progressOf(progress: BackupProgress): Float =
        if (progress.duration > 0) (progress.position.toFloat() / progress.duration).coerceIn(0f, 1f) else 0f

    fun formatPosition(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        val minutes = total / 60
        val seconds = total % 60
        return String.format(Locale.CHINA, "%02d:%02d", minutes, seconds)
    }

    // ---------------------------------------------------------------- 收藏 / 进度

    fun toggleFavorite(series: Series): Boolean {
        if (!library.isLoaded) return false
        val added = library.toggle(series)
        refreshLibraryState()
        _state.update { s ->
            if (s.detail.series?.id == series.id) s.copy(detail = s.detail.copy(favorite = added)) else s
        }
        refreshHomeShelves()
        toast(if (added) "已加入收藏" else "已取消收藏")
        return added
    }

    fun toggleLater(series: Series) {
        if (!library.isLoaded) return
        val added = library.toggleLater(series)
        refreshLibraryState()
        refreshHomeShelves()
        toast(if (added) "已加入稍后看" else "已从稍后看移除")
    }

    fun saveProgress(series: Series, episodeId: String, episodeIndex: Int, position: Long, duration: Long, completed: Boolean = false) {
        if (!library.isLoaded) return
        library.save(
            BackupProgress(
                series = series,
                episodeId = episodeId,
                episodeIndex = episodeIndex,
                position = position,
                duration = duration,
                completed = completed,
                updatedAt = System.currentTimeMillis(),
            ),
        )
        refreshLibraryState()
    }

    fun removeHistory(id: String) {
        library.removeHistory(id)
        refreshLibraryState()
        refreshHomeShelves()
        toast("已从观看记录移除")
    }

    fun clearHistory() {
        library.clearHistory()
        refreshLibraryState()
        refreshHomeShelves()
        toast("观看记录已清空")
    }

    fun refreshLibraryState() {
        if (!library.isLoaded) return
        _state.update {
            // 「稍后看」在电视上和「收藏」是同一个动作，UI 只保留一个入口；
            // 旧的稍后看数据并到收藏列表尾部展示，不丢内容，也不用再让用户二选一。
            val favoriteRows = library.favorites()
            val seen = favoriteRows.mapTo(HashSet()) { row -> row.id }
            val favorites = favoriteRows + library.later().filter { seen.add(it.id) }
            val history = library.history()
            // 同一部剧可能有多条进度，取最新一条的 completed 作为整剧「已看完」状态。
            val completedIds = history
                .sortedByDescending { it.updatedAt }
                .distinctBy { it.series.id }
                .filter { it.completed }
                .mapTo(HashSet()) { it.series.id }
            it.copy(
                library = LibraryState(
                    favorites = favorites,
                    history = history,
                    later = emptyList(),
                    favoriteLabels = favorites.associate { series -> series.id to library.favoriteLabel(series.id) },
                    completedIds = completedIds,
                    version = it.library.version + 1,
                    groupFilter = it.library.groupFilter,
                ),
            )
        }
    }

    /**
     * 播放器退出/暂停后把观看记录同步到界面：本地库 → 页面状态 → 首页「接着看」。
     * 之前只在切页时才 refresh，用户回到首页要等下次启动才看得到刚看的剧。
     */
    fun syncWatchHistory() {
        if (!library.isLoaded) return
        refreshLibraryState()
        republishHome()
    }

    /** 只按当前本地数据重排首页栏目，不发起网络请求。 */
    private fun republishHome() {
        if (!library.isLoaded) return
        publishHome(loading = false)
    }

    /** 从播放器回到详情页时，同步最新进度与收藏状态。 */
    fun refreshDetailResume() {
        val id = _state.value.detail.series?.id ?: return
        val progress = library.progress(id) ?: return
        _state.update { s ->
            val episodes = s.detail.detail?.episodes?.size ?: 0
            s.copy(
                detail = s.detail.copy(
                    favorite = library.favorite(id),
                    resumeIndex = progress.episodeIndex.coerceIn(0, (episodes - 1).coerceAtLeast(0)),
                    resumePosition = progress.position,
                    resumeProgress = progressOf(progress),
                ),
            )
        }
    }

    fun collectionItems(kind: CollectionKind): List<ShelfEntry> = when (kind) {
        CollectionKind.RESUME -> library.history().filterNot { library.watched(it.series.id) }.map {
            ShelfEntry(
                it.series,
                "第 ${it.episodeIndex + 1} 集 · " + if (it.completed) "看下一集" else formatPosition(it.position),
                progressOf(it),
            )
        }
        CollectionKind.UPDATES -> library.favorites()
            .filter { library.favoriteUpdate(it.id)?.hasUpdate == true }
            .map { ShelfEntry(it, library.favoriteLabel(it.id)) }
        CollectionKind.LATER -> library.later().map { ShelfEntry(it, it.badge.ifBlank { "待看" }) }
        CollectionKind.FAVORITES -> library.favorites().map { ShelfEntry(it, library.favoriteLabel(it.id)) }
        CollectionKind.HISTORY -> library.history().map {
            ShelfEntry(it.series, "第 ${it.episodeIndex + 1} 集 · ${formatPosition(it.position)}", progressOf(it))
        }
        CollectionKind.HOT_SHORT -> homeShort.map { ShelfEntry(it, it.badge.ifBlank { it.tags.take(12) }) }
        CollectionKind.HOT_COMIC -> homeComic.map { ShelfEntry(it, it.badge.ifBlank { it.tags.take(12) }) }
        CollectionKind.HOT_AI -> homeAi.map { ShelfEntry(it, it.badge.ifBlank { it.tags.take(12) }) }
    }

    // ---------------------------------------------------------------- 详情

    /**
     * 「接着看」直连播放：只取剧集列表，不写 detail 状态。
     *
     * 走 openDetail 会在点击的瞬间把路由切到详情页、等数据回来再自动起播，
     * 用户看到的是「详情页闪一下」；这里刻意绕开它，拿到集号就直接进播放器。
     * 失败返回 null，由调用方退回详情页。
     */
    suspend fun playbackDetail(series: Series): Detail? =
        withContext(Dispatchers.IO) { runCatching { repository.detail(series.id) }.getOrNull() }

    /**
     * 首页「接着看」的起播位置：优先上次进度；该集已经看完则从下一集开头开始，
     * 避免从片尾进入、立刻触发一次「本集结束」。
     */
    fun resumePoint(seriesId: String, episodeCount: Int): Pair<Int, Long> {
        val progress = library.progress(seriesId) ?: return 0 to 0L
        val last = (episodeCount - 1).coerceAtLeast(0)
        if (progress.completed && progress.episodeIndex < last) return progress.episodeIndex + 1 to 0L
        return progress.episodeIndex.coerceIn(0, last) to progress.position
    }

    fun openDetail(series: Series) {
        _state.update {
            it.copy(detail = DetailState(loading = true, series = series, favorite = library.favorite(series.id)))
        }
        viewModelScope.launch {
            val detail = withContext(Dispatchers.IO) { runCatching { repository.detail(series.id) } }
            detail.onSuccess { data ->
                val progress = library.progress(series.id)
                val resumeIndex = (progress?.episodeIndex ?: 0)
                    .coerceIn(0, (data.episodes.size - 1).coerceAtLeast(0))
                // 详情一到就先上屏：系列剧、相关推荐、播放地址都是「锦上添花」，
                // 让它们把首屏卡住是以前点进详情要等两三秒的主要原因。
                _state.update {
                    it.copy(
                        detail = DetailState(
                            loading = false,
                            series = data.series,
                            detail = data,
                            favorite = library.favorite(data.series.id),
                            resumeIndex = resumeIndex,
                            resumePosition = progress?.position ?: 0L,
                            resumeProgress = progress?.let { p -> progressOf(p) },
                        ),
                    )
                }
                // 播放地址最先排队：用户最可能马上点播放，让预取先占住脚本运行时。
                val prefetchJob = async(Dispatchers.IO) {
                    prefetchPlayback(data.series.id, data.episodes.getOrNull(resumeIndex))
                }
                // 系列剧与相关推荐随后补齐，不阻塞详情首屏。
                val seasonsJob = async(Dispatchers.IO) {
                    runCatching { repository.seasons(data.series.id) }.getOrDefault(emptyList())
                }
                val relatedJob = async(Dispatchers.IO) {
                    runCatching { repository.related(data.series.id, relatedGenre(data.series), 12) }
                        .getOrDefault(emptyList())
                }
                val seasons = seasonsJob.await()
                val related = relatedJob.await()
                prefetchJob.await()
                val sameName = seasons.map { it.series }.filter { it.id != data.series.id }
                _state.update { current ->
                    // 用户可能已经退到别的详情页：只更新仍在看的那一集。
                    if (current.detail.series?.id != data.series.id || current.detail.loading) {
                        current
                    } else {
                        current.copy(
                            detail = current.detail.copy(
                                detail = data.copy(related = related, sameName = sameName),
                                seasons = seasons,
                            ),
                        )
                    }
                }
            }.onFailure { error ->
                _state.update {
                    it.copy(detail = it.detail.copy(loading = false, error = error.message ?: "剧集详情加载失败"))
                }
            }
        }
    }

    /** 预取首集播放地址 + 预热播放域名的 TLS；失败静默，真正播放时会再取一次。 */
    private fun prefetchPlayback(seriesId: String, episodeId: String?) {
        val episode = episodeId?.takeIf { it.isNotBlank() } ?: return
        runCatching {
            val source = getApplication<Application>().playbackStreamSource(repository.http)
            (source as? HongguoJsStreamSource)?.prefetch("$seriesId:$episode", library.maxQuality)
        }
        runCatching { NetworkTuning.warmPlayback(repository.http) }
    }

    /** 相关推荐走脚本的推荐位；类型只用于挑分类，猜错最多是推荐位不同。 */
    private fun relatedGenre(series: Series): String {
        val text = (series.tags + " " + series.title).lowercase()
        return when {
            "漫剧" in text || "漫画" in text || "动漫" in text -> "comic_series"
            "ai" in text -> "ai_series"
            else -> "short_play"
        }
    }

    fun toggleSynopsis() {
        _state.update { it.copy(detail = it.detail.copy(synopsisExpanded = !it.detail.synopsisExpanded)) }
    }

    // ---------------------------------------------------------------- 剧场

    fun loadTheater(type: ContentType? = null, reset: Boolean = true) {
        // 本地索引未就绪时不能静默返回：把 loading 收回 false，等页面重新进入时再发起，
        // 否则默认 loading=true 会一直转圈且再也不重试。
        if (!library.isLoaded) {
            _state.update { it.copy(theater = it.theater.copy(loading = false)) }
            return
        }
        val current = _state.value.theater
        val nextType = type ?: current.type
        val selection = if (reset) emptyMap() else current.selection
        val generation = ++theaterGeneration
        theaterJob?.cancel()
        theaterMoreJob?.cancel()
        theaterMoreJob = null
        _state.update {
            it.copy(
                theater = it.theater.copy(
                    type = nextType,
                    selection = selection,
                    // 不清空旧列表：一旦清空，整页会切到 LoadingState，
                    // 类型 chip 随之被销毁，遥控器焦点直接掉回顶部导航栏。
                    // 旧海报先顶着，新数据回来后整表替换。
                    items = it.theater.items,
                    loading = reset,
                    loadingMore = false,
                    page = 1,
                    hasMore = true,
                    error = null,
                ),
            )
        }
        theaterMoreFailures = 0
        theaterEmptyPages = 0
        theaterJob = viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { repository.theater(1, nextType, selection) }
            }
            result.onSuccess { page ->
                if (generation != theaterGeneration) return@onSuccess
                _state.update {
                    it.copy(
                        theater = it.theater.copy(
                            groups = page.filters,
                            items = page.catalog.items,
                            hasMore = page.catalog.hasMore,
                            loading = false,
                            page = 1,
                        ),
                    )
                }
                // 首屏只有一页（服务端单页条数不多），用户稍微滑一下就到底。
                // 这里静默把第二页也取回来，第一屏看完时下一页已经在手里。
                if (page.catalog.hasMore) loadMoreTheater()
            }.onFailure { error ->
                if (error is kotlinx.coroutines.CancellationException || generation != theaterGeneration) return@onFailure
                _state.update {
                    it.copy(theater = it.theater.copy(loading = false, error = error.message ?: "剧场加载失败"))
                }
            }
        }
    }

    fun selectTheaterFilter(group: TheaterFilterGroup, optionId: String) {
        val current = _state.value.theater
        val selection = current.selection.toMutableMap()
        if (optionId.isBlank()) selection.remove(group.type) else selection[group.type] = optionId
        loadTheaterWith(selection)
    }

    /** 一次性清空所有筛选条件，避免逐个条件重置触发多次请求。 */
    fun clearTheaterFilters() {
        if (_state.value.theater.selection.isEmpty()) return
        loadTheaterWith(emptyMap())
    }

    private fun loadTheaterWith(selection: Map<String, String>) {
        val type = _state.value.theater.type
        val generation = ++theaterGeneration
        theaterJob?.cancel()
        theaterMoreJob?.cancel()
        theaterMoreJob = null
        // 新筛选条件必须从零统计，不能继承旧条件的空页/失败次数。
        theaterMoreFailures = 0
        theaterEmptyPages = 0
        // 保留旧结果：筛选面板展开时整页变成 loading 会闪一下，体验上像「页面被重置」。
        _state.update {
            it.copy(theater = it.theater.copy(selection = selection, loading = true, loadingMore = false, page = 1, hasMore = true))
        }
        theaterJob = viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { repository.theater(1, type, selection) } }
            result.onSuccess { page ->
                if (generation != theaterGeneration) return@onSuccess
                _state.update {
                    it.copy(
                        theater = it.theater.copy(
                            groups = page.filters,
                            items = page.catalog.items,
                            hasMore = page.catalog.hasMore,
                            loading = false,
                            error = null,
                        ),
                    )
                }
            }.onFailure { error ->
                if (error is kotlinx.coroutines.CancellationException || generation != theaterGeneration) return@onFailure
                _state.update { it.copy(theater = it.theater.copy(loading = false, error = error.message ?: "筛选失败")) }
            }
        }
    }

    fun loadMoreTheater() {
        val s = _state.value.theater
        if (s.loading || s.loadingMore || !s.hasMore) return
        val generation = theaterGeneration
        val next = s.page + 1
        _state.update { it.copy(theater = it.theater.copy(loadingMore = true)) }
        theaterMoreJob = viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { repository.theater(next, s.type, s.selection) }
            }
            if (generation != theaterGeneration) return@launch
            result.onSuccess { page ->
                if (generation != theaterGeneration) return@onSuccess
                theaterMoreFailures = 0
                val empty = page.catalog.items.isEmpty()
                theaterEmptyPages = if (empty) theaterEmptyPages + 1 else 0
                _state.update { state ->
                    if (generation != theaterGeneration || state.theater.type != s.type ||
                        state.theater.selection != s.selection || state.theater.page != s.page
                    ) state else state.copy(theater = state.theater.copy(
                        items = (state.theater.items + page.catalog.items).distinctBy { item -> item.id },
                        hasMore = page.catalog.hasMore && theaterEmptyPages < 2,
                        loadingMore = false, page = next, error = null,
                    ))
                }
                if (empty && page.catalog.hasMore && theaterEmptyPages < 2 && generation == theaterGeneration) loadMoreTheater()
            }.onFailure { error ->
                if (error is kotlinx.coroutines.CancellationException || generation != theaterGeneration) return@onFailure
                theaterMoreFailures++
                val giveUp = theaterMoreFailures >= MAX_LOAD_MORE_RETRIES
                _state.update { state ->
                    if (generation != theaterGeneration || state.theater.type != s.type ||
                        state.theater.selection != s.selection || state.theater.page != s.page
                    ) state else state.copy(theater = state.theater.copy(
                        loadingMore = false, hasMore = !giveUp,
                        error = if (giveUp) "加载更多失败，稍后重试" else state.theater.error,
                    ))
                }
                if (!giveUp && generation == theaterGeneration) {
                    kotlinx.coroutines.delay(1_500)
                    if (generation == theaterGeneration) loadMoreTheater()
                }
            }
        }
    }

    // ---------------------------------------------------------------- 榜单

    fun loadRanking(board: RankBoard? = null, reset: Boolean = true) {
        if (!library.isLoaded) {
            _state.update { it.copy(ranking = it.ranking.copy(loading = false)) }
            return
        }
        val current = _state.value.ranking
        val nextBoard = board ?: current.board
        val generation = ++rankingGeneration
        rankingJob?.cancel()
        rankingMoreJob?.cancel()
        rankingMoreJob = null
        _state.update {
            it.copy(
                ranking = it.ranking.copy(
                    board = nextBoard,
                    // 同剧场：切榜不清空旧列表，否则榜单切换条会随整页 loading 一起被销毁，焦点掉回导航栏。
                    items = it.ranking.items,
                    loading = reset,
                    loadingMore = false,
                    page = 1,
                    hasMore = true,
                    error = null,
                ),
            )
        }
        // 切榜要取消上一榜的请求，否则慢返回的旧榜会覆盖新榜。
        rankingMoreFailures = 0
        rankingEmptyPages = 0
        rankingJob = viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { repository.ranking(nextBoard, 1) } }
            result.onSuccess { page ->
                if (generation != rankingGeneration) return@onSuccess
                _state.update {
                    it.copy(
                        ranking = it.ranking.copy(
                            items = page.items,
                            loading = false,
                            hasMore = page.hasMore,
                            page = 1,
                            heat = page.ranking?.positions?.mapValues { entry -> entry.value.heat }.orEmpty(),
                            updatedText = page.ranking?.updatedText.orEmpty(),
                        ),
                    )
                }
                // 榜单同理：首屏之后立刻补一页，避免第一次下滑就撞到加载圈。
                if (page.hasMore) loadMoreRanking()
            }.onFailure { error ->
                if (error is kotlinx.coroutines.CancellationException || generation != rankingGeneration) return@onFailure
                _state.update {
                    it.copy(ranking = it.ranking.copy(loading = false, error = error.message ?: "榜单加载失败"))
                }
            }
        }
    }

    fun loadMoreRanking() {
        val s = _state.value.ranking
        if (s.loading || s.loadingMore || !s.hasMore) return
        val generation = rankingGeneration
        val next = s.page + 1
        _state.update { it.copy(ranking = it.ranking.copy(loadingMore = true)) }
        rankingMoreJob = viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { repository.ranking(s.board, next) }
            }
            if (generation != rankingGeneration) return@launch
            result.onSuccess { page ->
                if (generation != rankingGeneration) return@onSuccess
                rankingMoreFailures = 0
                val empty = page.items.isEmpty()
                rankingEmptyPages = if (empty) rankingEmptyPages + 1 else 0
                _state.update { state ->
                    if (generation != rankingGeneration || state.ranking.board != s.board || state.ranking.page != s.page) {
                        state
                    } else state.copy(ranking = state.ranking.copy(
                        items = (state.ranking.items + page.items).distinctBy { item -> item.id },
                        hasMore = page.hasMore && rankingEmptyPages < 2,
                        loadingMore = false, page = next, error = null,
                    ))
                }
                if (empty && page.hasMore && rankingEmptyPages < 2 && generation == rankingGeneration) loadMoreRanking()
            }.onFailure { error ->
                if (error is kotlinx.coroutines.CancellationException || generation != rankingGeneration) return@onFailure
                rankingMoreFailures++
                val giveUp = rankingMoreFailures >= MAX_LOAD_MORE_RETRIES
                _state.update { state ->
                    if (generation != rankingGeneration || state.ranking.board != s.board || state.ranking.page != s.page) {
                        state
                    } else state.copy(ranking = state.ranking.copy(
                        loadingMore = false, hasMore = !giveUp,
                        error = if (giveUp) "加载更多失败，稍后重试" else state.ranking.error,
                    ))
                }
                if (!giveUp && generation == rankingGeneration) {
                    kotlinx.coroutines.delay(1_500)
                    if (generation == rankingGeneration) loadMoreRanking()
                }
            }
        }
    }

    // ---------------------------------------------------------------- 投播

    private var castServer: PhoneInputServer? = null
    private var castJob: Job? = null
    private var castRevision = 0

    /** 固定端口方便老用户手动输地址；被占用时退回系统分配，功能优先于「好记」。 */
    private val castPort = 3309

    private fun castForm() = PhoneForm(
        heading = "投播到电视",
        description = "把红果短剧或抖音的分享文案整段粘贴进来，电视会自动识别剧名与集数。",
        fields = listOf(
            PhoneField(
                key = "share",
                label = "分享文案 / 链接",
                hint = "长按输入框选择「粘贴」，支持整段分享文案……",
                maxLength = 10000,
                required = true,
                multiline = true,
            ),
        ),
        submitLabel = "发送到电视",
        doneMessage = "已发送到电视",
        doneHint = "电视正在识别，匹配成功会自动开始播放。可以返回继续发送下一条。",
        invalidMessage = "请粘贴 1—10000 字的分享内容",
    )

    /** 局域网 IPv4：只接受 Wi-Fi / 网线，移动数据下的地址手机根本连不到。 */
    private fun castLocalAddress(): String? {
        val manager = getApplication<Application>().getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return null
        val network = manager.activeNetwork ?: return null
        val caps = manager.getNetworkCapabilities(network) ?: return null
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && !caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
            return null
        }
        return runCatching {
            manager.getLinkProperties(network)?.linkAddresses
                ?.map { it.address }
                ?.firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
                ?.hostAddress
        }.getOrNull()
    }

    /** 打开投播页即开启通道；页面关闭时由 stopCastServer 释放端口。 */
    fun startCastServer() {
        if (castServer != null) return
        _state.update {
            it.copy(cast = it.cast.copy(phase = CastPhase.STARTING, message = "正在开启投播通道…", detail = null))
        }
        val address = castLocalAddress()
        if (address == null) {
            _state.update {
                it.copy(
                    cast = it.cast.copy(
                        phase = CastPhase.FAILED,
                        url = "",
                        port = 0,
                        message = "没有可用的局域网地址",
                        detail = "请让电视连上 Wi-Fi 或网线，再重新打开投播。",
                    ),
                )
            }
            return
        }
        fun open(fixedPort: Int): PhoneInputServer {
            lateinit var session: PhoneInputServer
            session = PhoneInputServer(
                address = address,
                form = castForm(),
                onSubmit = { values ->
                    viewModelScope.launch(Dispatchers.Main) {
                        if (castServer === session) acceptCastShare(values["share"].orEmpty())
                    }
                },
                ttlMillis = 0,
                fixedPort = fixedPort,
                tokenRequired = false,
                singleUse = false,
            )
            return session
        }
        val server = runCatching { open(castPort) }
            .recoverCatching { problem -> if (problem is java.net.BindException) open(0) else throw problem }
            .getOrElse { problem ->
                _state.update {
                    it.copy(
                        cast = it.cast.copy(
                            phase = CastPhase.FAILED,
                            url = "",
                            port = 0,
                            message = "投播通道启动失败",
                            detail = problem.message ?: "请检查网络设置后重试。",
                        ),
                    )
                }
                return
            }
        castServer = server
        val port = server.url.substringAfterLast(':').substringBefore('/').toIntOrNull() ?: 0
        _state.update {
            it.copy(
                cast = it.cast.copy(
                    phase = CastPhase.READY,
                    url = server.url,
                    port = port,
                    message = "投播通道已开启",
                    detail = "手机与电视连同一个 Wi-Fi，扫码后粘贴分享文案。",
                    candidates = emptyList(),
                ),
            )
        }
    }

    fun stopCastServer() {
        castRevision++
        castJob?.cancel()
        castJob = null
        castServer?.close()
        castServer = null
        // 保留尚未消费的跳转请求：页面销毁与「打开详情」可能发生在同一帧。
        _state.update { it.copy(cast = CastState(open = it.cast.open)) }
    }

    fun restartCastServer() {
        stopCastServer()
        startCastServer()
    }

    fun dismissCastCandidates() {
        _state.update {
            it.copy(
                cast = it.cast.copy(
                    candidates = emptyList(),
                    message = if (it.cast.running) "投播通道已开启" else it.cast.message,
                    detail = if (it.cast.running) "可以继续扫码投下一条。" else it.cast.detail,
                ),
            )
        }
    }

    fun consumeCastOpen() {
        _state.update { it.copy(cast = it.cast.copy(open = null)) }
    }

    fun pickCastCandidate(series: Series) {
        _state.update {
            it.copy(
                cast = it.cast.copy(
                    candidates = emptyList(),
                    message = "正在打开《${series.title}》…",
                    detail = "已按你的选择进入剧集。",
                    open = CastOpen(series),
                ),
            )
        }
        openDetail(series)
    }

    private class CastResolution(
        val direct: Detail? = null,
        val directEpisodeId: String? = null,
        val exact: Series? = null,
        val candidates: List<Series> = emptyList(),
        val titles: List<String> = emptyList(),
        val failures: List<String> = emptyList(),
    )

    /**
     * 手机发来的内容在这里变成「哪部剧、第几集」。
     * 红果链接能直接拿到剧目/分集 ID，走精确路径；抖音只能先识别短剧名，再走搜索，
     * 命中多部时交回界面让用户选，绝不按近似标题直接开播。
     */
    private fun resolveCast(shares: List<PushShareItem>): CastResolution {
        val candidates = mutableListOf<Series>()
        val failures = mutableListOf<String>()
        val verified = mutableListOf<String>()
        var directDetail: Detail? = null
        var directEpisodeId: String? = null

        shares.take(4).forEach { share ->
            var resolved = false
            var searchTitle = share.title
            when (share.source) {
                "抖音" -> if (share.url.isNotBlank()) {
                    runCatching { DouyinShareResolver.resolve(share.url) }
                        .onSuccess { info ->
                            searchTitle = info.title
                            if (info.title.isNotBlank()) verified += info.title
                        }
                        .onFailure { problem ->
                            failures += "抖音链接识别失败：" + (problem.message ?: "未知错误")
                            // 「某某的作品」是作者标签，不是剧名，拿去搜索会误播。
                            if (searchTitle.contains("的作品") || searchTitle == "作品") searchTitle = ""
                        }
                }
                "抖音短剧" -> if (share.url.isNotBlank()) {
                    runCatching {
                        val playletId = Regex("/share/playlet/detail/(\\d{8,25})")
                            .find(URI(share.url).path.orEmpty())?.groupValues?.getOrNull(1)
                            ?: throw java.io.IOException("抖音短剧地址里没有 playlet_id")
                        DouyinPlayletResolver.resolveTitle(playletId)
                    }.onSuccess { title ->
                        searchTitle = title
                        if (title.isNotBlank()) verified += title
                    }.onFailure { failures += "抖音短剧识别失败：" + (it.message ?: "未知错误") }
                }
                else -> if (share.title.isNotBlank()) verified += share.title
            }
            if (share.source == "红果" && share.url.isNotBlank()) {
                runCatching {
                    val ids = RedShareResolver.resolve(share.url)
                    val seriesId = ids.seriesId
                    if (seriesId.isNullOrBlank()) return@runCatching
                    val detail = repository.detail(seriesId)
                    val index = ids.videoId?.let { detail.episodes.indexOf(it) } ?: -1
                    if (ids.videoId.isNullOrBlank() || index >= 0) {
                        if (directDetail == null) {
                            directDetail = detail
                            directEpisodeId = ids.videoId
                        }
                        resolved = true
                    } else {
                        failures += "红果链接已解析剧目，但分集 ID 不在该剧中"
                    }
                }.onFailure { failures += "红果链接解析失败：" + (it.message ?: "未知错误") }
            }
            if (!resolved) {
                val queries = if (share.source == "抖音" && searchTitle == share.title) {
                    share.titleCandidates.ifEmpty { listOf(searchTitle) }
                } else {
                    listOf(searchTitle)
                }
                queries.filter { it.isNotBlank() }.distinct().forEach { query ->
                    runCatching { repository.searchCombined(query, 1).items.take(12) }
                        .onSuccess { items ->
                            items.forEach { series ->
                                if (candidates.none { it.id == series.id }) candidates += series
                            }
                        }
                        .onFailure { failures += "${share.source}《$query》搜索失败：" + (it.message ?: "") }
                }
            }
        }

        val titles = verified.ifEmpty {
            shares.flatMap { it.titleCandidates.ifEmpty { listOf(it.title) } }
                .filter { it.isNotBlank() }
                .distinct()
        }
        val ranked = candidates.distinctBy { it.id }
            .sortedByDescending { pushTitleScore(it.title, titles) }
            .take(12)
        val exact = ranked.firstOrNull { pushTitleScore(it.title, titles) >= 1000 }
        val unique = exact != null && ranked.size == 1 && shares.size == 1
        return CastResolution(
            direct = directDetail,
            directEpisodeId = directEpisodeId,
            exact = exact.takeIf { unique },
            candidates = if (unique) emptyList() else ranked,
            titles = titles,
            failures = failures,
        )
    }

    private fun acceptCastShare(raw: String) {
        val shares = PushShareParser.parse(raw)
        if (shares.isEmpty()) {
            _state.update {
                it.copy(
                    cast = it.cast.copy(
                        phase = CastPhase.READY,
                        message = "没有识别到分享内容",
                        detail = "请粘贴红果或抖音的完整分享文案（要带链接）。",
                    ),
                )
            }
            return
        }
        val revision = ++castRevision
        castJob?.cancel()
        _state.update {
            it.copy(
                cast = it.cast.copy(
                    phase = CastPhase.RECEIVING,
                    received = it.cast.received + shares.size,
                    message = "收到 ${shares.size} 条分享",
                    detail = "正在识别来源并匹配剧集…",
                    candidates = emptyList(),
                ),
            )
        }
        castJob = viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { resolveCast(shares) } }
            if (revision != castRevision) return@launch
            val resolved = result.getOrNull()
            if (resolved == null) {
                _state.update {
                    it.copy(
                        cast = it.cast.copy(
                            phase = CastPhase.FAILED,
                            message = "识别失败",
                            detail = result.exceptionOrNull()?.message ?: "请重试或换一条分享文案。",
                        ),
                    )
                }
                return@launch
            }
            val direct = resolved.direct
            val exact = resolved.exact
            when {
                direct != null -> {
                    val episodeLabel = resolved.directEpisodeId
                        ?.let { id -> direct.episodes.indexOf(id).takeIf { it >= 0 }?.let { "第 ${it + 1} 集" } }
                        .orEmpty()
                    prepareDetailForCast(direct, resolved.directEpisodeId)
                    _state.update {
                        it.copy(
                            cast = it.cast.copy(
                                phase = CastPhase.READY,
                                message = "已找到《${direct.series.title}》",
                                detail = if (episodeLabel.isBlank()) "正在从第 1 集开始播放…" else "已定位 $episodeLabel，正在播放…",
                                lastTitle = direct.series.title,
                                candidates = emptyList(),
                                open = CastOpen(direct.series, resolved.directEpisodeId, episodeLabel),
                            ),
                        )
                    }
                }
                exact != null -> {
                    openDetail(exact)
                    _state.update {
                        it.copy(
                            cast = it.cast.copy(
                                phase = CastPhase.READY,
                                message = "已匹配《${exact.title}》",
                                detail = "正在打开剧集…",
                                lastTitle = exact.title,
                                candidates = emptyList(),
                                open = CastOpen(exact),
                            ),
                        )
                    }
                }
                resolved.candidates.isNotEmpty() -> {
                    _state.update {
                        it.copy(
                            cast = it.cast.copy(
                                phase = CastPhase.READY,
                                message = "找到 ${resolved.candidates.size} 部可能匹配的剧集",
                                detail = "请在弹窗里选一部，避免误播。",
                                candidates = resolved.candidates,
                                lastTitle = resolved.titles.firstOrNull().orEmpty(),
                            ),
                        )
                    }
                }
                else -> {
                    _state.update {
                        it.copy(
                            cast = it.cast.copy(
                                phase = CastPhase.FAILED,
                                message = "没有找到匹配剧集",
                                detail = resolved.failures.take(2).joinToString("；")
                                    .ifBlank { "为避免误播，未开始播放。可以换一条更完整的分享文案再试。" },
                            ),
                        )
                    }
                }
            }
        }
    }

    /** 投播直连时详情已经在手，先填好状态再跳转，省一次网络往返。 */
    private fun prepareDetailForCast(detail: Detail, episodeId: String?) {
        val index = episodeId?.let { detail.episodes.indexOf(it) }?.takeIf { it >= 0 } ?: 0
        _state.update {
            it.copy(
                detail = DetailState(
                    loading = false,
                    series = detail.series,
                    detail = detail,
                    favorite = library.favorite(detail.series.id),
                    resumeIndex = index,
                    resumePosition = 0L,
                ),
            )
        }
        viewModelScope.launch {
            val seasons = withContext(Dispatchers.IO) {
                runCatching { repository.seasons(detail.series.id) }.getOrDefault(emptyList())
            }
            _state.update {
                if (it.detail.detail === detail) it.copy(detail = it.detail.copy(seasons = seasons)) else it
            }
        }
    }

    // ---------------------------------------------------------------- 搜索

    fun openSearch() {
        // 每次进入搜索都从干净状态开始：上次的关键词、结果、提交态都不该留到下一次。
        // 只保留「大家在搜」缓存和本地搜索历史，避免重复请求。
        val hot = _state.value.search.hot
        _state.update {
            it.copy(
                search = SearchState(
                    history = library.searches(),
                    hot = hot,
                    hotLoading = hot.isEmpty(),
                ),
            )
        }
        if (hot.isEmpty()) loadHot()
    }

    fun updateQuery(value: String) {
        _state.update { it.copy(search = it.search.copy(query = value, error = null)) }
        suggestJob?.cancel()
        if (value.isBlank()) {
            _state.update { it.copy(search = it.search.copy(suggestions = emptyList())) }
            return
        }
        suggestJob = viewModelScope.launch {
            // 150ms 足够滤掉连打，又不会让联想显得迟钝；真正的去重由脚本门面的 TTL 缓存兜住，
            // 同一个前缀在 5 分钟内不会再发第二次请求。
            delay(150)
            val words = withContext(Dispatchers.IO) { runCatching { repository.suggest(value.trim()) }.getOrDefault(emptyList()) }
            if (_state.value.search.query == value) {
                _state.update { it.copy(search = it.search.copy(suggestions = words.take(8))) }
            }
        }
    }

    fun submitSearch(query: String = _state.value.search.query, type: ContentType? = _state.value.search.type) {
        val clean = query.trim()
        if (clean.isEmpty()) return
        library.rememberSearch(clean, type ?: ContentType.SHORT)
        _state.update {
            it.copy(
                search = it.search.copy(
                    query = clean,
                    type = type,
                    submitted = true,
                    searching = true,
                    keyboardVisible = false,
                    results = emptyList(),
                    page = 1,
                    hasMore = true,
                    error = null,
                    suggestions = emptyList(),
                    history = library.searches(),
                ),
            )
        }
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    if (type == null) repository.searchCombined(clean, 1) else repository.search(clean, 1, type)
                }
            }
            result.onSuccess { page ->
                _state.update {
                    it.copy(search = it.search.copy(results = page.items, hasMore = page.hasMore, searching = false))
                }
            }.onFailure { error ->
                _state.update {
                    it.copy(search = it.search.copy(searching = false, error = error.message ?: "搜索失败，请重试"))
                }
            }
        }
    }

    fun loadMoreSearch() {
        val s = _state.value.search
        if (s.searching || !s.hasMore || s.results.isEmpty()) return
        _state.update { it.copy(search = it.search.copy(searching = true)) }
        viewModelScope.launch {
            val next = s.page + 1
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    if (s.type == null) repository.searchCombined(s.query, next) else repository.search(s.query, next, s.type)
                }
            }
            result.onSuccess { page ->
                _state.update {
                    it.copy(
                        search = it.search.copy(
                            results = (it.search.results + page.items).distinctBy { item -> item.id },
                            hasMore = page.hasMore,
                            searching = false,
                            page = next,
                        ),
                    )
                }
            }.onFailure {
                _state.update { it.copy(search = it.search.copy(searching = false, hasMore = false)) }
            }
        }
    }

    fun setSearchType(type: ContentType?) {
        _state.update { it.copy(search = it.search.copy(type = type)) }
        if (_state.value.search.query.isNotBlank()) submitSearch(_state.value.search.query, type)
    }

    fun toggleKeyboard() {
        _state.update { it.copy(search = it.search.copy(keyboardVisible = !it.search.keyboardVisible)) }
    }

    fun clearSearch() {
        _state.update { it.copy(search = SearchState(history = library.searches(), hot = it.search.hot, hotLoading = false)) }
    }

    fun clearSearchHistory() {
        library.clearSearches()
        _state.update { it.copy(search = it.search.copy(history = emptyList())) }
        toast("搜索历史已清空")
    }

    fun loadHot() {
        viewModelScope.launch {
            val hot = withContext(Dispatchers.IO) {
                runCatching {
                    // 短剧榜与漫剧榜并行，进搜索页时「大家在搜」不再等两轮榜单。
                    val (real, comic) = coroutineScope {
                        val realJob = async { runCatching { repository.ranking(RankBoard.REAL, 1) }.getOrNull() }
                        val comicJob = async { runCatching { repository.ranking(RankBoard.COMIC, 1) }.getOrNull() }
                        realJob.await() to comicJob.await()
                    }
                    val mixed = mutableListOf<Series>()
                    repeat(maxOf(real?.items?.size ?: 0, comic?.items?.size ?: 0)) { index ->
                        real?.items?.getOrNull(index)?.let { mixed += it.copy(badge = "短剧") }
                        comic?.items?.getOrNull(index)?.let { mixed += it.copy(badge = "漫剧") }
                    }
                    mixed.distinctBy { it.id }
                }.getOrDefault(emptyList())
            }
            _state.update { it.copy(search = it.search.copy(hot = hot, hotLoading = false)) }
        }
    }

    // ---------------------------------------------------------------- 设置

    fun settingsSnapshot(): SettingsSnapshot = SettingsSnapshot(
        quality = library.maxQuality,
        speed = library.playbackSpeed,
        autoNext = library.autoNext,
        autoSeason = library.autoSeason,
        danmaku = library.danmaku,
        showClock = library.showClock,
        frameMode = library.frameMode.name,
        danmakuOpacity = library.danmakuOpacity,
        danmakuFont = library.danmakuFont,
        danmakuSpeed = library.danmakuSpeed,
        danmakuRows = library.danmakuRows,
        danmakuColor = extras.danmakuColorMode,
        blockedWords = extras.blockedWords.sorted(),
        startupAutoplay = extras.startupAutoplay,
        seamlessPlayback = extras.seamlessPlayback,
        seekStepSeconds = extras.seekStepSeconds,
        longPressSpeed = extras.longPressSpeed,
        posterColumns = extras.posterColumns,
        videoInfoMode = library.videoInfoMode,
        showNetworkSpeed = library.showNetworkSpeed,
        persistentProgress = library.persistentProgress,
        showScreenBack = library.showScreenBack,
        appIconVariant = appIconVariant(),
        remoteUp = extras.remoteUpAction,
        remoteDown = extras.remoteDownAction,
        remoteConfirm = extras.remoteConfirmAction,
        remoteMenu = extras.remoteMenuAction,
        menuKeyAction = extras.menuKeyAction,
        dnsProvider = DnsSettings.provider().key,
        dnsCustom = DnsSettings.customText(),
    )

    fun setQuality(value: Int) {
        library.maxQuality = value
        toast("清晰度 · ${PlaybackQuality.label(value)}")
    }

    fun setSpeed(value: Float) {
        library.playbackSpeed = PlaybackSpeed.normalize(value)
        toast("默认倍速 · ${PlaybackSpeed.label(library.playbackSpeed)}")
    }

    fun setAutoNext(value: Boolean) {
        library.autoNext = value
        toast(if (value) "自动连播已开启" else "自动连播已关闭")
    }

    fun setAutoSeason(value: Boolean) {
        library.autoSeason = value
        toast(if (value) "跨季连播已开启" else "跨季连播已关闭")
    }

    fun setDanmaku(value: Boolean) {
        library.danmaku = value
        toast(if (value) "弹幕已开启" else "弹幕已关闭")
    }

    fun setShowClock(value: Boolean) {
        library.showClock = value
        touchSettings()
    }

    fun setFrameMode(mode: VideoFrameMode) {
        library.frameMode = mode
        toast("画面模式 · ${mode.label}")
    }

    fun resetPlaybackPreferences() {
        library.resetPlaybackPreferences()
        toast("播放偏好已恢复默认")
    }

    /** 重置设备身份：清掉持久化的 device_register 结果，下次请求会重新注册一台新设备。 */
    fun resetDeviceIdentity() {
        HongguoJsStore.resetDeviceIdentity(getApplication())
        toast("设备身份已重置，下次联网将重新注册")
    }

    /**
     * 清除缓存：只删可自动重建的临时文件——更新下载包与 JS 字节码缓存。
     * 收藏/历史/设置/账号/脚本本体全部保留，重启后 bytecode 会自动重编译。
     */
    fun clearCache() {
        val app = getApplication<Application>()
        var bytes = 0L
        runCatching {
            File(app.cacheDir, "updates").let { if (it.exists()) { bytes += dirSize(it); it.deleteRecursively() } }
            File(app.filesDir, "js-bytecode").let { if (it.exists()) { bytes += dirSize(it); it.deleteRecursively() } }
        }
        toast(if (bytes > 0) "已清除缓存 ${formatBytes(bytes)}" else "暂无缓存可清理")
    }

    /**
     * 清除全部数据：清空本机所有 SharedPreferences（收藏/历史/设置/账号/设备身份/脚本版本等）、
     * 缓存，以及 data 脚本本体。APK 不内置脚本，启动检测不到本地脚本会自动走首次安装流程联网下载，
     * 因此删掉脚本不会变砖，重启后等同于全新安装。
     */
    fun clearAllData() {
        val app = getApplication<Application>()
        toast("正在保存尚未保存完成的数据，请稍候…")

        // Stop all ViewModel-owned jobs before touching storage. The dedicated reset thread
        // survives scope cancellation and waits for the loader plus both process-wide writers.
        viewModelScope.coroutineContext[Job]?.cancel()
        Thread({
            fun restartProcess() {
                runCatching {
                    val intent = app.packageManager.getLaunchIntentForPackage(app.packageName)?.apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    }
                    if (intent != null) app.startActivity(intent)
                }
                exitProcess(0)
            }

            if (!library.shutdownAndAwait(timeoutSeconds = 30)) {
                // The queues are now closing and this ViewModel cannot safely continue.
                // Preserve the existing files/preferences and relaunch with fresh process state.
                toast("未能安全清除数据，原有数据已保留，正在重启…")
                restartProcess()
                return@Thread
            }

            // Both persistence queues are fully drained: the pending local changes are now
            // safely on disk, so tell the user before the destructive reset starts.
            toast("数据已保存，正在清除本机数据…")

            val prefNames = listOf(
                "native-library-v1", "native-home-cache-v1", "startup_playback",
                "playback-settings-v1", "playback-extras-v1", "compose-account-v1",
                "hongguo-js-kv", "script-update-v1",
            )
            val failedPrefs = prefNames.filter { name ->
                !runCatching {
                    app.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
                }.getOrDefault(false)
            }.filter { name ->
                !runCatching {
                    app.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
                }.getOrDefault(false)
            }
            if (failedPrefs.isNotEmpty()) {
                toast("部分数据未能清除，正在重启应用…")
                restartProcess()
                return@Thread
            }

            runCatching { app.cacheDir.deleteRecursively() }
            runCatching { File(app.filesDir, "js-bytecode").deleteRecursively() }
            // 脚本本体也清掉：重启后走首次安装流程重新联网下载，等同全新安装。
            runCatching { File(app.filesDir, "hongguo-js").deleteRecursively() }
            toast("已清除全部数据，正在重启…")
            restartProcess()
        }, "hongguotv-clear-all-data").apply {
            isDaemon = false
            start()
        }
    }

    private fun dirSize(dir: File): Long =
        runCatching { dir.walkTopDown().filter { it.isFile }.map { it.length() }.sum() }.getOrDefault(0L)

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1024 * 1024 -> "${bytes / 1024 / 1024} MB"
        bytes >= 1024 -> "${bytes / 1024} KB"
        else -> "$bytes B"
    }

    // ---- 弹幕细化 ----

    fun setDanmakuOpacity(value: Int) {
        library.danmakuOpacity = value
        toast("弹幕不透明度 · ${library.danmakuOpacity}%")
    }

    fun setDanmakuFont(value: Int) {
        library.danmakuFont = value
        toast("弹幕字号 · ${DanmakuPrefs.fontLabel(library.danmakuFont)}")
    }

    fun setDanmakuSpeed(value: Int) {
        library.danmakuSpeed = value
        toast("弹幕速度 · ${DanmakuPrefs.speedLabel(library.danmakuSpeed)}")
    }

    fun setDanmakuRows(value: Int) {
        library.danmakuRows = value
        toast("弹幕行数 · ${library.danmakuRows} 行")
    }

    fun setDanmakuColor(value: Int) {
        extras.danmakuColorMode = value
        toast("弹幕颜色已更新")
    }

    fun addBlockedWord(word: String) {
        val next = word.trim()
        if (next.isEmpty()) return
        extras.blockedWords = extras.blockedWords + next
        toast("已屏蔽「$next」")
    }

    fun removeBlockedWord(word: String) {
        extras.blockedWords = extras.blockedWords - word
        toast("已取消屏蔽「$word」")
    }

    fun resetDanmakuPreferences() {
        library.danmakuOpacity = DanmakuPrefs.OPACITY_DEFAULT
        library.danmakuFont = DanmakuPrefs.FONT_DEFAULT
        library.danmakuSpeed = DanmakuPrefs.SPEED_DEFAULT
        library.danmakuRows = DanmakuPrefs.ROWS_DEFAULT
        extras.danmakuColorMode = 0
        toast("弹幕效果已恢复默认")
    }

    // ---- 播放增强 ----

    fun setStartupAutoplay(value: Boolean) {
        extras.startupAutoplay = value
        toast(if (value) "下次启动直接播放首页推荐" else "已关闭启动自动播放")
    }

    fun setSeamlessPlayback(value: Boolean) {
        extras.seamlessPlayback = value
        toast(if (value) "无感连播已开启" else "无感连播已关闭")
    }

    fun setSeekStep(seconds: Int) {
        extras.seekStepSeconds = seconds
        toast("快进步长 · $seconds 秒")
    }

    /** 启动自动播放只触发一次；首页数据就绪后由外壳取走。 */
    private var startupConsumed = false

    fun consumeStartupRecommendation(): Series? {
        if (startupConsumed) return null
        if (!extras.startupAutoplay) {
            startupConsumed = true
            return null
        }
        val home = _state.value.home
        val first = home.hero.firstOrNull()
            ?: home.shelves.firstOrNull { it.entries.isNotEmpty() }?.entries?.firstOrNull()?.series
            ?: return null
        startupConsumed = true
        return first
    }

    // ---- 画面 ----

    fun setPosterColumns(value: Int) {
        extras.posterColumns = value
        toast(if (value == 0) "海报列数 · 自动" else "海报列数 · 每行 $value 部")
    }

    fun setVideoInfoMode(value: Int) {
        library.videoInfoMode = value
        toast("播放信息 · ${videoInfoLabel(value)}")
    }

    // ---- 应用图标（launcher activity-alias 切换） ----

    fun appIconVariant(): String =
        prefs.getString("appIconVariant", "i")?.takeIf { APP_ICON_ALIASES.containsKey(it) } ?: "i"

    /** Resolve DEFAULT against the manifest instead of treating it as disabled. */
    private fun launcherAliasEnabled(pm: PackageManager, component: ComponentName): Boolean =
        when (pm.getComponentEnabledSetting(component)) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED -> false
            else -> runCatching { pm.getActivityInfo(component, 0).enabled }.getOrDefault(false)
        }

    /**
     * Apply one launcher entry as a single PackageManager transaction on Android 13+.
     * Older versions enable and verify the target before disabling any previous entry.
     */
    private fun applyLauncherAliasVariant(variant: String) {
        require(APP_ICON_ALIASES.containsKey(variant))
        val app = getApplication<Application>()
        val pm = app.packageManager
        val prefix = ComposeActivity::class.java.name.removeSuffix("ComposeActivity")
        val components = APP_ICON_ALIASES.mapValues { (_, alias) -> ComponentName(app, prefix + alias) }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val settings = APP_ICON_ALIASES.map { (key, _) ->
                PackageManager.ComponentEnabledSetting(
                    components.getValue(key),
                    if (key == variant) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                    else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP,
                )
            }
            pm.setComponentEnabledSettings(settings)
        } else {
            val target = components.getValue(variant)
            pm.setComponentEnabledSetting(
                target,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP,
            )
            check(launcherAliasEnabled(pm, target)) { "目标桌面入口未能启用" }
            components.filterKeys { it != variant }.values.forEach { component ->
                pm.setComponentEnabledSetting(
                    component,
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP,
                )
            }
        }

        val enabled = components.filterValues { launcherAliasEnabled(pm, it) }.keys
        check(enabled == setOf(variant)) { "桌面入口状态异常：${enabled.joinToString()}" }
    }

    /** Activity 能启动时自检一次，纠正偏好与系统实际 alias 状态不一致的情况。 */
    private fun ensureLauncherAliasConsistency() {
        val app = getApplication<Application>()
        val pm = app.packageManager
        val prefix = ComposeActivity::class.java.name.removeSuffix("ComposeActivity")
        val active = APP_ICON_ALIASES.mapNotNull { (key, alias) ->
            val component = ComponentName(app, prefix + alias)
            key.takeIf { runCatching { launcherAliasEnabled(pm, component) }.getOrDefault(false) }
        }.toSet()
        val preferred = appIconVariant()
        if (active == setOf(preferred)) return

        val recovered = runCatching { applyLauncherAliasVariant(preferred); preferred }.getOrElse {
            val fallback = active.singleOrNull() ?: "i"
            runCatching { applyLauncherAliasVariant(fallback); fallback }.getOrNull()
        }
        if (recovered != null) prefs.edit().putString("appIconVariant", recovered).commit()
    }

    fun setAppIconVariant(variant: String) {
        if (!APP_ICON_ALIASES.containsKey(variant)) return
        val previous = appIconVariant()
        val app = getApplication<Application>()
        val pm = app.packageManager
        val prefix = ComposeActivity::class.java.name.removeSuffix("ComposeActivity")
        val previousActive = APP_ICON_ALIASES.mapNotNull { (key, alias) ->
            val component = ComponentName(app, prefix + alias)
            key.takeIf { runCatching { launcherAliasEnabled(pm, component) }.getOrDefault(false) }
        }.toSet()

        try {
            applyLauncherAliasVariant(variant)
            check(prefs.edit().putString("appIconVariant", variant).commit()) { "图标偏好保存失败" }
            toast("应用图标已更换 · 桌面可能需要刷新图标缓存")
        } catch (failure: Exception) {
            // Recover a known working launcher entry first; never leave all aliases disabled.
            val fallback = previousActive.singleOrNull() ?: previous
            val recovered = runCatching { applyLauncherAliasVariant(fallback); fallback }.getOrElse {
                runCatching { applyLauncherAliasVariant("i"); "i" }.getOrNull()
            }
            if (recovered != null) prefs.edit().putString("appIconVariant", recovered).commit()
            toast("图标切换失败，已尝试恢复可启动入口")
        }
    }

    fun setShowNetworkSpeed(value: Boolean) {
        library.showNetworkSpeed = value
        touchSettings()
    }

    fun setPersistentProgress(value: Boolean) {
        library.persistentProgress = value
        touchSettings()
    }

    fun setShowScreenBack(value: Boolean) {
        library.showScreenBack = value
        touchSettings()
    }

    // ---- 按键 ----

    fun setRemoteAction(key: RemoteKey, value: String) {
        when (key) {
            RemoteKey.UP -> extras.remoteUpAction = value
            RemoteKey.DOWN -> extras.remoteDownAction = value
            RemoteKey.CONFIRM -> extras.remoteConfirmAction = value
            RemoteKey.MENU -> extras.remoteMenuAction = value
        }
        toast("按键已更新")
    }

    // ---- 接口指示器 ----

    /** 检测四项接口；onlyKey 非空时只重测其中一项。 */
    fun refreshApiStatus(onlyKey: String? = null) {
        if (_apiStatus.value.running) return
        val targets = if (onlyKey == null) ApiStatusState().probes.map { it.key } else listOf(onlyKey)
        _apiStatus.update { st ->
            st.copy(
                running = true,
                probes = st.probes.map { probe ->
                    if (probe.key in targets) probe.copy(status = ApiProbeStatus.CHECKING, detail = "检测中…", elapsedMs = 0)
                    else probe
                },
            )
        }
        viewModelScope.launch {
            // 四项并发，避免播放探针把首页/搜索的结果一起拖慢。
            val results = withContext(Dispatchers.IO) { targets.map { key -> async { key to runProbe(key) } }.awaitAll() }
            _apiStatus.update { st ->
                st.copy(running = false, probes = st.probes.map { probe -> results.firstOrNull { it.first == probe.key }?.second ?: probe })
            }
        }
    }

    private fun runProbe(key: String): ApiProbe {
        val label = when (key) {
            "home" -> "首页"
            "search" -> "搜索"
            "play" -> "播放"
            else -> "联想"
        }
        val started = SystemClock.elapsedRealtime()
        return try {
            val detail = when (key) {
                "home" -> "返回 ${repository.home(1, ContentType.SHORT).items.size} 条内容"
                "search" -> "返回 ${repository.search("修仙", 1, ContentType.SHORT).items.size} 条结果"
                "play" -> {
                    val latest = library.history().maxByOrNull { it.updatedAt }
                    if (latest == null) {
                        return ApiProbe(key, label, ApiProbeStatus.UNKNOWN, "暂无观看记录，无法验证播放地址", 0L, System.currentTimeMillis())
                    }
                    val stream = getApplication<Application>().playbackStreamSource(repository.http)
                        .stream("${latest.series.id}:${latest.episodeId}", library.maxQuality)
                    "已获取播放地址 · ${stream.quality}"
                }
                else -> "返回 ${repository.probeSuggest("xx")} 个联想词"
            }
            ApiProbe(key, label, ApiProbeStatus.OK, detail, SystemClock.elapsedRealtime() - started, System.currentTimeMillis())
        } catch (problem: Exception) {
            val reason = if (!hasValidatedInternet()) "网络已断开"
            else (problem.message ?: problem.javaClass.simpleName).take(80)
            ApiProbe(key, label, ApiProbeStatus.FAILED, reason, SystemClock.elapsedRealtime() - started, System.currentTimeMillis())
        }
    }

    private fun hasValidatedInternet(): Boolean = runCatching {
        val cm = getApplication<Application>().getSystemService(ConnectivityManager::class.java)
            ?: return@runCatching false
        val network = cm.activeNetwork ?: return@runCatching false
        cm.getNetworkCapabilities(network)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
    }.getOrDefault(false)

    // ---- 检查更新 ----

    /**
     * 根据安装包名选择更新通道，防止测试版读取正式版更新清单。
     * 正式版保持原有 AppDownload 更新配置。
     */
    private fun createAppUpdateRepository(): AppUpdateRepository {
        return if (getApplication<Application>().packageName == AppUpdate.BETA_PACKAGE) {
            AppUpdateRepository(
                expectedPackageName = AppUpdate.BETA_PACKAGE,
                releaseRepository = AppUpdate.BETA_REPOSITORY,
                releaseTagPrefix = AppUpdate.BETA_TAG_PREFIX,
                latestManifestUrl = AppUpdate.BETA_LATEST_MANIFEST,
                apkFilePrefix = "HongguoTV-",
            )
        } else {
            AppUpdateRepository()
        }
    }

    fun openUpdateDialog() {
        _update.update { it.copy(visible = true, error = null, notice = null) }
        val current = _update.value
        if (current.phase == UpdatePhase.IDLE || (current.phase == UpdatePhase.ERROR && current.update == null)) checkUpdates()
    }

    fun dismissUpdateDialog() {
        // 下载中关闭只收起弹窗，不中断后台下载。
        _update.update { it.copy(visible = false) }
    }

    /** 启动后的一次静默检查：只有发现新版才弹窗，已是最新时完全不打扰。 */
    fun autoCheckUpdates() {
        if (_update.value.phase != UpdatePhase.IDLE || _update.value.visible) return
        _update.update { it.copy(phase = UpdatePhase.CHECKING, update = null, error = null, notice = null) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { createAppUpdateRepository().use { it.latest() } }
            }
            result.onSuccess { info ->
                val installed = BuildConfig.VERSION_CODE.toLong()
                if (info.newerThan(installed, Build.VERSION.SDK_INT)) {
                    _update.update { it.copy(update = info, phase = UpdatePhase.AVAILABLE, visible = true) }
                } else {
                    _update.update { it.copy(phase = UpdatePhase.IDLE) }
                }
            }.onFailure {
                _update.update { it.copy(phase = UpdatePhase.IDLE) }
            }
        }
    }

    fun checkUpdates() {
        val phase = _update.value.phase
        if (phase == UpdatePhase.CHECKING || phase == UpdatePhase.DOWNLOADING || phase == UpdatePhase.VERIFYING) return
        _update.update { it.copy(phase = UpdatePhase.CHECKING, update = null, error = null, notice = null, progress = 0, apkPath = null) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { createAppUpdateRepository().use { it.latest() } }
            }
            result.onSuccess { info ->
                val installed = BuildConfig.VERSION_CODE.toLong()
                val newer = info.newerThan(installed, Build.VERSION.SDK_INT)
                val tooNewSdk = !newer && info.versionCode > installed && info.minSdk > Build.VERSION.SDK_INT
                _update.update {
                    it.copy(
                        update = info,
                        phase = if (newer) UpdatePhase.AVAILABLE else UpdatePhase.UP_TO_DATE,
                        notice = if (tooNewSdk) "新版需要 Android API ${info.minSdk}，当前设备暂不支持" else null,
                    )
                }
            }.onFailure { problem ->
                _update.update {
                    it.copy(phase = UpdatePhase.ERROR, error = "检查更新失败：${problem.message ?: "网络不可用"}")
                }
            }
        }
    }

    fun downloadUpdate() {
        val info = _update.value.update ?: return
        if (_update.value.phase == UpdatePhase.DOWNLOADING) return
        val app = getApplication<Application>()
        val dir = File(app.cacheDir, "updates").apply { mkdirs() }
        // 每个版本独立文件名，避免系统安装器把旧包缓存当成新包。
        val target = File(dir, "HongGuoTV-${info.versionName}-${info.versionCode}.apk")
        updateJob?.cancel()
        _update.update { it.copy(phase = UpdatePhase.DOWNLOADING, progress = 0, speedBytesPerSecond = 0, error = null, notice = null, apkPath = null) }
        updateJob = viewModelScope.launch {
            val repo = createAppUpdateRepository()
            try {
                withContext(Dispatchers.IO) {
                    repo.download(
                        update = info,
                        file = target,
                        progress = { percent ->
                            _update.update { if (it.phase == UpdatePhase.DOWNLOADING) it.copy(progress = percent) else it }
                        },
                        mirrorIndex = _update.value.mirrorIndex,
                        sourceIndex = null,
                        speed = { bytesPerSecond ->
                            _update.update { if (it.phase == UpdatePhase.DOWNLOADING) it.copy(speedBytesPerSecond = bytesPerSecond) else it }
                        },
                    )
                }
                _update.update { it.copy(phase = UpdatePhase.VERIFYING) }
                withContext(Dispatchers.IO) { AppUpdateInstaller.validate(app, target, info) }
                _update.update {
                    it.copy(
                        phase = UpdatePhase.READY,
                        progress = 100,
                        speedBytesPerSecond = 0,
                        apkPath = target.absolutePath,
                        notice = "下载完成，已通过 SHA-256 与签名校验",
                    )
                }
            } catch (cancelled: CancellationException) {
                _update.update { it.copy(phase = UpdatePhase.AVAILABLE, progress = 0, speedBytesPerSecond = 0, notice = "下载已取消") }
            } catch (problem: Exception) {
                _update.update {
                    it.copy(phase = UpdatePhase.ERROR, error = problem.message ?: "下载失败，请重试", progress = 0, speedBytesPerSecond = 0)
                }
            } finally {
                runCatching { repo.close() }
            }
        }
    }

    fun cancelUpdateDownload() {
        updateJob?.cancel()
        updateJob = null
        _update.update { it.copy(phase = UpdatePhase.AVAILABLE, progress = 0, speedBytesPerSecond = 0, notice = "下载已取消") }
    }

    fun setUpdateMirror(index: Int?) {
        _update.update { it.copy(mirrorIndex = index) }
    }

    /** 更新弹窗内的即时提示（安装授权、安装器异常），比底部 toast 更不容易被弹窗遮住。 */
    fun notifyUpdate(message: String) {
        _update.update { it.copy(notice = message) }
    }

    // ---------------------------------------------------------------- 手机验证码登录

    private var countdownJob: Job? = null

    fun switchLoginMode(mode: LoginMode) {
        if (_state.value.account.mode == mode) return
        _state.update { it.copy(account = it.account.copy(mode = mode, message = null, phoneError = null)) }
        // 切回扫码且还没有二维码时补一张，避免用户看到空卡片。
        if (mode == LoginMode.QR && _state.value.account.qrBitmap == null && !_state.value.account.qrLoading) {
            startQrLogin()
        }
    }

    fun setLoginPhone(value: String) {
        val digits = value.filter { it.isDigit() }.take(11)
        _state.update { it.copy(account = it.account.copy(phone = digits, phoneError = null)) }
    }

    fun setLoginCode(value: String) {
        val digits = value.filter { it.isDigit() }.take(6)
        _state.update { it.copy(account = it.account.copy(code = digits, phoneError = null)) }
    }

    fun sendLoginCode() {
        val account = _state.value.account
        if (account.sending || account.countdown > 0) return
        if (!account.phoneValid) {
            _state.update { it.copy(account = it.account.copy(phoneError = "请输入 11 位手机号")) }
            return
        }
        _state.update { it.copy(account = it.account.copy(sending = true, phoneError = null, message = null)) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { jsAccount.sendPhoneCode(account.phone) } }
            result.onSuccess { sent ->
                _state.update {
                    it.copy(
                        account = it.account.copy(
                            sending = false,
                            codeSent = true,
                            phoneMasked = sent.maskedMobile,
                            countdown = sent.retrySeconds.coerceIn(30, 600),
                            message = "验证码已发送",
                        ),
                    )
                }
                startCodeCountdown()
            }.onFailure { error ->
                _state.update {
                    it.copy(account = it.account.copy(sending = false, phoneError = error.message ?: "验证码发送失败，请重试"))
                }
            }
        }
    }

    private fun startCodeCountdown() {
        countdownJob?.cancel()
        countdownJob = viewModelScope.launch {
            while (_state.value.account.countdown > 0) {
                delay(1_000)
                _state.update { it.copy(account = it.account.copy(countdown = (it.account.countdown - 1).coerceAtLeast(0))) }
            }
        }
    }

    fun verifyLoginCode() {
        val account = _state.value.account
        if (account.verifying) return
        if (!account.codeValid) {
            _state.update { it.copy(account = it.account.copy(phoneError = "请输入收到的验证码")) }
            return
        }
        _state.update { it.copy(account = it.account.copy(verifying = true, phoneError = null, message = null)) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { jsAccount.verifyPhoneCode(account.code) } }
            result.onSuccess { credentials ->
                _state.update { it.copy(account = it.account.copy(verifying = false, code = "")) }
                finishLogin(credentials)
            }.onFailure { error ->
                _state.update {
                    it.copy(account = it.account.copy(verifying = false, phoneError = error.message ?: "登录失败，请重试"))
                }
            }
        }
    }

    /** 返回上一步改号码：清掉验证码与倒计时。 */
    fun resetLoginPhone() {
        countdownJob?.cancel()
        _state.update {
            it.copy(
                account = it.account.copy(
                    codeSent = false,
                    code = "",
                    countdown = 0,
                    phoneError = null,
                    phoneMasked = "",
                ),
            )
        }
    }

    fun startQrLogin() {
        if (_state.value.account.qrLoading) return
        _state.update { it.copy(account = it.account.copy(qrLoading = true, message = null, qrHint = "正在获取二维码…")) }
        accountPoll?.cancel()
        accountPoll = viewModelScope.launch {
            // 登录请求/解析全部走热更新脚本；app 只把扫码链接编码成图片。
            val qr = withContext(Dispatchers.IO) { runCatching { jsAccount.qr() } }
            qr.onSuccess { code ->
                val image = withContext(Dispatchers.Default) { qrPngBytes(code.qrUrl) }
                if (image == null) {
                    failLogin("二维码生成失败")
                    return@launch
                }
                _state.update {
                    it.copy(account = it.account.copy(qrBitmap = image, qrLoading = false, qrHint = "使用红果 App 扫码登录"))
                }
                val deadline = System.currentTimeMillis() + (code.expiresIn.coerceAtLeast(60) * 1000L)
                while (System.currentTimeMillis() < deadline) {
                    delay(2000)
                    val checked = withContext(Dispatchers.IO) { runCatching { jsAccount.poll() } }
                    if (checked.isSuccess && checked.getOrDefault(false)) {
                        val credentials = withContext(Dispatchers.IO) { runCatching { jsAccount.credentials() } }.getOrNull()
                        if (credentials != null) finishLogin(credentials) else failLogin("登录态获取失败")
                        return@launch
                    }
                    if (checked.isFailure) {
                        val message = checked.exceptionOrNull()?.message.orEmpty()
                        if (message.contains("过期")) {
                            _state.update { it.copy(account = it.account.copy(qrBitmap = null, qrHint = "二维码已过期，点按刷新")) }
                            return@launch
                        }
                    }
                }
                _state.update { it.copy(account = it.account.copy(qrBitmap = null, qrHint = "二维码已过期，点按刷新")) }
            }.onFailure { error ->
                failLogin(error.message ?: "二维码获取失败")
            }
        }
    }

    private fun finishLogin(credentials: com.hongguotv.core.AccountCredentials) {
        accountPrefs.edit().putString("credentials", credentials.toJson()).apply()
        _state.update { it.copy(account = it.account.copy(signedIn = true, qrBitmap = null, busy = true, qrHint = "登录成功")) }
        viewModelScope.launch {
            val profile = withContext(Dispatchers.IO) { runCatching { jsAccount.profile(credentials) }.getOrNull() }
            val favorites = withContext(Dispatchers.IO) { runCatching { jsAccount.favorites(credentials) }.getOrNull() }
            val history = withContext(Dispatchers.IO) { runCatching { jsAccount.history(credentials) }.getOrNull() }
            accountPrefs.edit().putString("nickname", profile?.nickname ?: "红果用户").apply()
            _state.update {
                it.copy(
                    account = it.account.copy(
                        signedIn = true,
                        busy = false,
                        nickname = profile?.nickname ?: "红果用户",
                        avatar = profile?.avatarUrl.orEmpty(),
                        cloudFavorites = favorites?.size ?: 0,
                        cloudHistory = history?.size ?: 0,
                        message = "登录成功，云端数据已同步",
                    ),
                )
            }
        }
    }

    private fun failLogin(message: String) {
        _state.update { it.copy(account = it.account.copy(qrLoading = false, busy = false, qrHint = message, message = message)) }
    }

    fun signOut() {
        accountPoll?.cancel()
        countdownJob?.cancel()
        viewModelScope.launch {
            withContext(Dispatchers.IO) { runCatching { jsAccount.signOut() } }
            accountPrefs.edit().clear().apply()
            _state.update { it.copy(account = AccountState()) }
            toast("已退出账号")
        }
    }

    // ------------------------------------------------------------ 脚本热更新

    /**
     * 启动即检查，之后每 3 小时静默巡检一次。
     *
     * 检查与下载都在后台完成（只占网络，不占 JS 运行时）；新脚本通过四道校验后**立即切换**：
     * 重建脚本实例与两个门面、清掉取流缓存，后续请求立刻用新脚本。
     * 唯一的例外是播放中——取流和连播队列挂在旧脚本上，这时只做标记，
     * 用户一退回主界面就换上，不需要重启 App。
     */
    private fun startScriptAutoUpdate() {
        viewModelScope.launch {
            delay(SCRIPT_FIRST_DELAY_MS)
            // 引导页刚下载完的那次不再重复检查，否则启动路径会连发两轮清单竞速。
            val fresh = System.currentTimeMillis() - scriptUpdater.installedAt() < SCRIPT_FRESH_INSTALL_MS
            if (!fresh) runScriptCheck(force = true)
            while (true) {
                delay(SCRIPT_CHECK_INTERVAL_MS)
                runScriptCheck(force = false)
            }
        }
    }

    private suspend fun runScriptCheck(force: Boolean) {
        if (_scriptUpdate.value.checking) return
        _scriptUpdate.update { it.copy(checking = true) }
        val outcome = scriptUpdater.check(force)
        _scriptUpdate.update {
            it.copy(
                checking = false,
                pendingVersion = scriptUpdater.pendingVersion(),
                checkedAt = scriptUpdater.lastCheckedAt(),
                installedAt = scriptUpdater.installedAt(),
                lastError = scriptUpdater.lastError().ifBlank { null },
            )
        }
        if (outcome is ScriptUpdateOutcome.Installed) {
            applyInstalledScript()
        }
    }

    /** 新脚本已落盘：当前能切就立刻切，播放中则等退回主界面再切。 */
    private suspend fun applyInstalledScript() {
        if (playerVisible) {
            scriptSwitchPending = true
            return
        }
        // 首屏还在请求时稍等一下，避免在首页并发取数的中途重建运行时。
        withTimeoutOrNull(SCRIPT_SWITCH_WAIT_MS) {
            while (_state.value.home.loading) delay(150)
        }
        if (playerVisible) {
            scriptSwitchPending = true
            return
        }
        switchToInstalledScript()
    }

    /**
     * 就地切换到刚落盘的新脚本。
     *
     * [HongguoJsStore.reload] 会丢弃旧实例并清空播放地址缓存；重新 get 时走
     * 「在线版（含摘要校验）→ 上一版 → 内置」的既有降级链，所以这里不需要再做校验。
     * 重建失败时保留旧门面继续用，下一次取流会重新走降级链，不会把应用打死。
     */
    private suspend fun switchToInstalledScript() {
        if (scriptSwitching) return
        scriptSwitching = true
        try {
            performScriptSwitch()
        } finally {
            scriptSwitching = false
        }
    }

    private suspend fun performScriptSwitch() {
        val context = getApplication<Application>()
        val js = withContext(Dispatchers.IO) {
            runCatching {
                HongguoJsStore.reload()
                HongguoJsStore.get(context, repository.http)
            }.getOrNull()
        } ?: return
        scriptUpdater.clearPending()
        installScriptGateways(js)
        // 首页列表是旧脚本拉的，静默重拉一遍（已有数据时不会出骨架），
        // 免得新旧脚本的 ID / 分类对不上，点进详情才报错。
        loadHome()
    }

    /** 播放页可见性由路由驱动：播放中不切脚本，退出后补一次。 */
    fun setPlayerVisible(visible: Boolean) {
        playerVisible = visible
        if (!visible && scriptSwitchPending) {
            scriptSwitchPending = false
            viewModelScope.launch { switchToInstalledScript() }
        }
    }

    // ---------------------------------------------------------------- 提示

    fun toast(message: String) {
        _state.update { it.copy(toast = message, settingsRevision = it.settingsRevision + 1) }
    }

    private fun touchSettings() {
        _state.update { it.copy(settingsRevision = it.settingsRevision + 1) }
    }

    fun clearToast() {
        _state.update { it.copy(toast = null) }
    }

    override fun onCleared() {
        // ViewModel.onCleared() itself does not cancel viewModelScope; ViewModel.clear()
        // closes its registered scope after this callback returns. Cancel it here first so
        // active JS/OkHttp requests receive cancellation before global gateways are detached.
        viewModelScope.coroutineContext[Job]?.cancel()
        super.onCleared()
        castRevision++
        listOfNotNull(
            castJob, homeJob, homeDeferJob, theaterJob, rankingJob,
            theaterMoreJob, rankingMoreJob, searchJob, suggestJob,
            accountPoll, scriptBootstrapJob, updateJob, countdownJob,
        ).forEach { it.cancel() }
        castServer?.close()
        castServer = null

        // Do not leave process-global gateways pointing at a cleared ViewModel.
        // Identity checks protect a replacement ViewModel that has already installed new ones.
        if (JsContent.gateway === installedContentGateway) JsContent.gateway = null
        if (JsAccount.gateway === installedAccountGateway) JsAccount.gateway = null
        installedContentGateway = null
        installedAccountGateway = null

        // QuickJS runtimes are per-call and close in JsScriptRunner.finally. Release only
        // this ViewModel's cached facade; a newer session may already own the process store.
        installedJs?.let(HongguoJsStore::release)
        installedJs = null
        library.flush()
        library.close()
    }
}

/** Stable manifest aliases used by both phone and Leanback launchers. */
private val APP_ICON_ALIASES = linkedMapOf(
    "h" to "LauncherIconH",
    "i" to "LauncherIconI",
    "j" to "LauncherIconJ",
    "k" to "LauncherIconK",
)

/** 分页失败重试上限：网络连续失败三次才判定「到底」，避免一次抖动锁死列表。 */
private const val MAX_LOAD_MORE_RETRIES = 3

/** 引导页刚装完脚本的宽限期：这期间启动检查直接跳过。 */
private const val SCRIPT_FRESH_INSTALL_MS = 60_000L

/** 首次安装的脚本下载：自动重试次数与退避基数。 */
private const val SCRIPT_BOOTSTRAP_RETRIES = 2
private const val SCRIPT_BOOTSTRAP_RETRY_MS = 4_000L

/** 冷启动用缓存顶首屏时的兜底：用户一直停在首页，也要在 45 秒后静默补一次。 */
private const val HOME_DEFER_FALLBACK_MS = 45_000L
/** 回首页时距上次成功拉取超过 5 分钟才再拉，避免来回切页反复请求。 */
private const val HOME_STALE_MS = 5 * 60 * 1000L
/** 切走首页后稍等再补网络：让目标页的首屏请求先占用脚本运行时。 */
private const val HOME_BACKGROUND_REFRESH_DELAY_MS = 1_200L

/** 脚本热更新：首屏请求发出去之后再开始，清单只有几百字节，不会抢带宽。 */
private const val SCRIPT_FIRST_DELAY_MS = 1_500L
/** 使用中静默巡检：长时间开机的电视盒子也能拿到新脚本，全程不打断。 */
private const val SCRIPT_CHECK_INTERVAL_MS = 3 * 60 * 60 * 1000L
/** 新脚本就绪时等首屏收尾的上限；超时就直接切，不等首页。 */
private const val SCRIPT_SWITCH_WAIT_MS = 20_000L
