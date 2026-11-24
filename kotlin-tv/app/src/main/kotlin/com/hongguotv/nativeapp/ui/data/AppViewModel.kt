// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp.ui.data

import android.app.Application
import android.content.ComponentName
import android.content.Context
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
import com.hongguotv.js.HongguoJsAccount
import com.hongguotv.js.HongguoJsContent
import com.hongguotv.js.HongguoJsStore
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.Inet4Address
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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

    private val _state = MutableStateFlow(AppState())
    val state: StateFlow<AppState> = _state.asStateFlow()

    /** 接口指示器状态：顶栏品牌灯与「设置 · 网络」共用。 */
    private val _apiStatus = MutableStateFlow(ApiStatusState())
    val apiStatus: StateFlow<ApiStatusState> = _apiStatus.asStateFlow()

    /** 更新弹窗状态：检查、下载、校验、安装全流程共用。 */
    private val _update = MutableStateFlow(UpdateUiState())
    val update: StateFlow<UpdateUiState> = _update.asStateFlow()
    private var updateJob: Job? = null

    private var homeShort: List<Series> = emptyList()
    private var homeComic: List<Series> = emptyList()
    private var homeAi: List<Series> = emptyList()
    private var homeJob: Job? = null
    private var theaterJob: Job? = null
    private var rankingJob: Job? = null
    private var searchJob: Job? = null
    private var suggestJob: Job? = null
    private var accountPoll: Job? = null
    private val jsAccount get() = JsAccount.required()

    val isTelevision: Boolean
        get() = getApplication<Application>().packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK)

    init {
        // 内容接口（首页/搜索/详情/榜单/弹幕）全部走热更新脚本；装好门面再发首页请求。
        val js = HongguoJsStore.get(app, repository.http)
        JsContent.gateway = HongguoJsContent(js)
        JsAccount.gateway = HongguoJsAccount(js)
        library.loadAsync(
            onFailure = { failure ->
                _state.update { it.copy(bootError = failure.message ?: "本地数据读取失败") }
            },
            onReady = {
                _state.update { it.copy(booted = true, bootError = null) }
                refreshLibraryState()
                loadHome()
                restoreAccount()
                bootstrapPreferences()
                refreshApiStatus()
            },
        )
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
            if (profile != null) accountPrefs.edit().putString("nickname", profile.nickname ?: "红果用户").apply()
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

    fun loadHome(refresh: Boolean = false) {
        if (!library.isLoaded) return
        val cachedShort = library.cachedHome(ContentType.SHORT)?.items.orEmpty()
        val cachedComic = library.cachedHome(ContentType.COMIC)?.items.orEmpty()
        val cachedAi = library.cachedHome(ContentType.AI)?.items.orEmpty()
        if (homeShort.isEmpty()) homeShort = cachedShort
        if (homeComic.isEmpty()) homeComic = cachedComic
        if (homeAi.isEmpty()) homeAi = cachedAi
        if (!refresh && (homeShort.isNotEmpty() || homeComic.isNotEmpty() || homeAi.isNotEmpty())) {
            publishHome(loading = false)
        } else {
            _state.update { it.copy(home = it.home.copy(loading = homeShort.isEmpty() && homeComic.isEmpty(), refreshing = refresh, error = null)) }
        }
        homeJob?.cancel()
        homeJob = viewModelScope.launch {
            val short = withContext(Dispatchers.IO) {
                runCatching { repository.home(1, ContentType.SHORT) }.getOrNull()
            }
            val comic = withContext(Dispatchers.IO) {
                runCatching { repository.home(1, ContentType.COMIC) }.getOrNull()
            }
            val ai = withContext(Dispatchers.IO) {
                runCatching { repository.ranking(RankBoard.AI, 1) }.getOrNull()
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
            publishHome(
                loading = false,
                error = if (failed) "内容加载失败，请检查网络后重试" else null,
            )
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
                        title = "追剧更新",
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
        loadHome()
        toast(if (added) "已加入追剧" else "已取消追剧")
        return added
    }

    fun toggleLater(series: Series) {
        if (!library.isLoaded) return
        val added = library.toggleLater(series)
        refreshLibraryState()
        loadHome()
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
        loadHome()
        toast("已从观看记录移除")
    }

    fun clearHistory() {
        library.clearHistory()
        refreshLibraryState()
        loadHome()
        toast("观看记录已清空")
    }

    fun refreshLibraryState() {
        if (!library.isLoaded) return
        _state.update {
            it.copy(
                library = LibraryState(
                    favorites = library.favorites(),
                    history = library.history(),
                    later = library.later(),
                    favoriteLabels = library.favorites().associate { series -> series.id to library.favoriteLabel(series.id) },
                    version = it.library.version + 1,
                    groupFilter = it.library.groupFilter,
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

    fun openDetail(series: Series) {
        _state.update {
            it.copy(detail = DetailState(loading = true, series = series, favorite = library.favorite(series.id)))
        }
        viewModelScope.launch {
            val detail = withContext(Dispatchers.IO) { runCatching { repository.detail(series.id) } }
            detail.onSuccess { data ->
                val progress = library.progress(series.id)
                val seasons = withContext(Dispatchers.IO) {
                    runCatching { repository.seasons(data.series.id) }.getOrDefault(emptyList())
                }
                _state.update {
                    it.copy(
                        detail = DetailState(
                            loading = false,
                            series = data.series,
                            detail = data,
                            favorite = library.favorite(data.series.id),
                            resumeIndex = (progress?.episodeIndex ?: 0).coerceIn(0, (data.episodes.size - 1).coerceAtLeast(0)),
                            resumePosition = progress?.position ?: 0L,
                            resumeProgress = progress?.let { p -> progressOf(p) },
                            seasons = seasons,
                        ),
                    )
                }
            }.onFailure { error ->
                _state.update {
                    it.copy(detail = it.detail.copy(loading = false, error = error.message ?: "剧集详情加载失败"))
                }
            }
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
        _state.update {
            it.copy(
                theater = it.theater.copy(
                    type = nextType,
                    selection = selection,
                    items = if (reset) emptyList() else it.theater.items,
                    loading = reset,
                    page = 1,
                    hasMore = true,
                    error = null,
                ),
            )
        }
        theaterJob?.cancel()
        theaterJob = viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { repository.theater(1, nextType, selection) }
            }
            result.onSuccess { page ->
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
            }.onFailure { error ->
                if (error is kotlinx.coroutines.CancellationException) return@onFailure
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
        // 保留旧结果：筛选面板展开时整页变成 loading 会闪一下，体验上像「页面被重置」。
        _state.update {
            it.copy(theater = it.theater.copy(selection = selection, loading = true, page = 1))
        }
        theaterJob?.cancel()
        theaterJob = viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { repository.theater(1, type, selection) } }
            result.onSuccess { page ->
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
                if (error is kotlinx.coroutines.CancellationException) return@onFailure
                _state.update { it.copy(theater = it.theater.copy(loading = false, error = error.message ?: "筛选失败")) }
            }
        }
    }

    fun loadMoreTheater() {
        val s = _state.value.theater
        if (s.loading || s.loadingMore || !s.hasMore) return
        _state.update { it.copy(theater = it.theater.copy(loadingMore = true)) }
        viewModelScope.launch {
            val next = s.page + 1
            val result = withContext(Dispatchers.IO) { runCatching { repository.theater(next, s.type, s.selection) } }
            result.onSuccess { page ->
                _state.update {
                    it.copy(
                        theater = it.theater.copy(
                            items = (it.theater.items + page.catalog.items).distinctBy { item -> item.id },
                            hasMore = page.catalog.hasMore,
                            loadingMore = false,
                            page = next,
                        ),
                    )
                }
            }.onFailure {
                _state.update { it.copy(theater = it.theater.copy(loadingMore = false, hasMore = false)) }
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
        _state.update {
            it.copy(
                ranking = it.ranking.copy(
                    board = nextBoard,
                    items = if (reset) emptyList() else it.ranking.items,
                    loading = reset,
                    loadingMore = false,
                    page = 1,
                    hasMore = true,
                    error = null,
                ),
            )
        }
        // 切榜要取消上一榜的请求，否则慢返回的旧榜会覆盖新榜。
        rankingJob?.cancel()
        rankingJob = viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { repository.ranking(nextBoard, 1) } }
            result.onSuccess { page ->
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
            }.onFailure { error ->
                if (error is kotlinx.coroutines.CancellationException) return@onFailure
                _state.update {
                    it.copy(ranking = it.ranking.copy(loading = false, error = error.message ?: "榜单加载失败"))
                }
            }
        }
    }

    fun loadMoreRanking() {
        val s = _state.value.ranking
        if (s.loading || s.loadingMore || !s.hasMore) return
        _state.update { it.copy(ranking = it.ranking.copy(loadingMore = true)) }
        viewModelScope.launch {
            val next = s.page + 1
            val result = withContext(Dispatchers.IO) { runCatching { repository.ranking(s.board, next) } }
            result.onSuccess { page ->
                _state.update {
                    it.copy(
                        ranking = it.ranking.copy(
                            items = (it.ranking.items + page.items).distinctBy { item -> item.id },
                            hasMore = page.hasMore,
                            loadingMore = false,
                            page = next,
                        ),
                    )
                }
            }.onFailure {
                _state.update { it.copy(ranking = it.ranking.copy(loadingMore = false, hasMore = false)) }
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
            delay(280)
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
                    val real = runCatching { repository.ranking(RankBoard.REAL, 1) }.getOrNull()
                    val comic = runCatching { repository.ranking(RankBoard.COMIC, 1) }.getOrNull()
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
        appIconVariant = appIconVariant(),
        remoteUp = extras.remoteUpAction,
        remoteDown = extras.remoteDownAction,
        remoteConfirm = extras.remoteConfirmAction,
        remoteMenu = extras.remoteMenuAction,
        menuKeyAction = extras.menuKeyAction,
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

    fun appIconVariant(): String = prefs.getString("appIconVariant", "i") ?: "i"

    fun setAppIconVariant(variant: String) {
        val aliases = mapOf("h" to "LauncherIconH", "i" to "LauncherIconI", "j" to "LauncherIconJ", "k" to "LauncherIconK")
        val alias = aliases[variant] ?: return
        val app = getApplication<Application>()
        val pm = app.packageManager
        val prefix = ComposeActivity::class.java.name.removeSuffix("ComposeActivity")
        // 先启用目标入口再停用其余，任何时刻桌面都保有恰好一个图标
        pm.setComponentEnabledSetting(
            ComponentName(app, prefix + alias),
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP,
        )
        aliases.filterKeys { it != variant }.values.forEach { other ->
            pm.setComponentEnabledSetting(
                ComponentName(app, prefix + other),
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP,
            )
        }
        prefs.edit().putString("appIconVariant", variant).apply()
        toast("应用图标已更换 · 桌面没刷新就重启一下桌面")
    }

    fun setShowNetworkSpeed(value: Boolean) {
        library.showNetworkSpeed = value
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
                runCatching { AppUpdateRepository().use { it.latest() } }
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
                runCatching { AppUpdateRepository().use { it.latest() } }
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
            val repo = AppUpdateRepository()
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
        viewModelScope.launch {
            withContext(Dispatchers.IO) { runCatching { jsAccount.signOut() } }
            accountPrefs.edit().clear().apply()
            _state.update { it.copy(account = AccountState()) }
            toast("已退出账号")
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
        super.onCleared()
        castRevision++
        castJob?.cancel()
        castServer?.close()
        castServer = null
        homeJob?.cancel()
        theaterJob?.cancel()
        searchJob?.cancel()
        suggestJob?.cancel()
        accountPoll?.cancel()
        updateJob?.cancel()
        library.flush()
        library.close()
    }
}
