// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp.ui

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hongguotv.nativeapp.AppUpdateInstaller
import com.hongguotv.core.Series
import com.hongguotv.nativeapp.BuildConfig
import com.hongguotv.nativeapp.ui.components.AmbientBackground
import com.hongguotv.nativeapp.ui.components.ApiStatusDialog
import com.hongguotv.nativeapp.ui.components.ToastHost
import com.hongguotv.nativeapp.ui.components.UpdateDialog
import com.hongguotv.nativeapp.ui.components.TopBar
import com.hongguotv.nativeapp.ui.components.tryRequestFocus
import com.hongguotv.nativeapp.ui.data.AppViewModel
import com.hongguotv.nativeapp.ui.data.LoginMode
import com.hongguotv.nativeapp.ui.nav.CollectionKind
import com.hongguotv.nativeapp.ui.nav.Route
import com.hongguotv.nativeapp.ui.nav.Tab
import com.hongguotv.nativeapp.ui.player.PlayerSession
import com.hongguotv.nativeapp.ui.screens.AccountScreen
import com.hongguotv.nativeapp.ui.screens.CastScreen
import com.hongguotv.nativeapp.ui.screens.CollectionScreen
import com.hongguotv.nativeapp.ui.screens.DetailScreen
import com.hongguotv.nativeapp.ui.screens.HomeScreen
import com.hongguotv.nativeapp.ui.screens.LibraryScreen
import com.hongguotv.nativeapp.ui.screens.PlayerScreen
import com.hongguotv.nativeapp.ui.screens.RankingScreen
import com.hongguotv.nativeapp.ui.screens.ScriptBootstrapScreen
import com.hongguotv.nativeapp.ui.screens.SearchScreen
import com.hongguotv.nativeapp.ui.screens.SettingsScreen
import com.hongguotv.nativeapp.ui.screens.TheaterScreen
import java.io.File
import com.hongguotv.nativeapp.ui.theme.Ink
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HongguoApp(vm: AppViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(Tab.HOME) }
    var route by remember { mutableStateOf<Route>(Route.Main) }
    var clock by remember { mutableStateOf("") }
    var showApiStatus by remember { mutableStateOf(false) }
    // 播放页「弹幕设置」入口：先记下要落到的分栏，再切回设置页；SettingsScreen 消费后清空，
    // 避免下次正常进设置还停在弹幕栏。
    var pendingSettingsSection by remember { mutableStateOf<String?>(null) }
    // 「接着看」直连播放：取剧集列表期间拦住重复点击，避免叠出多次跳转。
    var resumingSeriesId by remember { mutableStateOf<String?>(null) }
    val appScope = rememberCoroutineScope()
    val context = LocalContext.current
    val apiStatus by vm.apiStatus.collectAsStateWithLifecycle()
    val update by vm.update.collectAsStateWithLifecycle()
    val snapshot = remember(state.library.version, state.settingsRevision) { runCatching { vm.settingsSnapshot() }.getOrNull() }

    // 全新安装没有内置脚本：先下载再进主界面，避免空门面渲染出一堆错误态。
    if (state.scriptSetup.active) {
        ScriptBootstrapScreen(
            state = state.scriptSetup,
            versionName = BuildConfig.VERSION_NAME,
            onRetry = vm::retryScriptBootstrap,
        )
        return
    }

    // 每个主页面第一个可聚焦元素的锚点：导航栏选中项按下、返回首页、冷启动都靠它定位光标。
    val homeEntry = remember { FocusRequester() }
    val theaterEntry = remember { FocusRequester() }
    val rankEntry = remember { FocusRequester() }
    val libraryEntry = remember { FocusRequester() }
    val settingsEntry = remember { FocusRequester() }
    val accountEntry = remember { FocusRequester() }
    fun entryOf(target: Tab): FocusRequester? = when (target) {
        Tab.HOME -> homeEntry
        Tab.THEATER -> theaterEntry
        Tab.RANK -> rankEntry
        Tab.LIBRARY -> libraryEntry
        Tab.SETTINGS -> settingsEntry
        Tab.ACCOUNT -> accountEntry
    }
    // 导航项各自的锚点。切页、返回首页时把光标钉在「下划线所在的那一项」上，
    // 而不是散落在上一页残留的位置——这是「红线到首页、光标还在设置」的修法。
    val tabAnchors = remember { Tab.entries.associateWith { FocusRequester() } }
    // 内容区是否已经有可聚焦元素。没有时不要把导航项的「下」指过去，
    // 否则会指向一个尚未 attach 的 requester，焦点移动直接抛异常。
    val contentReady = when (tab) {
        // HomeScreen 在 Hero 有数据时把入口挂在 Hero 浏览锚点；没有 Hero 才挂到第一排片单。
        // 但加载/错误空态不会创建 LazyColumn，因此必须同时判断当前实际显示的是不是内容区。
        Tab.HOME -> {
            val emptyLoading = state.home.loading && state.home.shelves.isEmpty()
            val emptyError = state.home.error != null && state.home.shelves.isEmpty()
            !emptyLoading && !emptyError &&
                (state.home.hero.isNotEmpty() || state.home.visibleShelves.isNotEmpty())
        }
        Tab.THEATER -> state.theater.items.isNotEmpty()
        Tab.RANK -> state.ranking.items.isNotEmpty()
        // 追剧页即使历史/收藏为空，也始终显示「观看历史 / 我的收藏」标签，
        // entryFocus 就挂在「观看历史」标签上，不应依赖列表数据量。
        Tab.LIBRARY -> true
        // 设置页是静态布局，只要有快照就一定有可聚焦项（分类导轨）。
        Tab.SETTINGS -> snapshot != null
        // 账号页始终有入口：未登录时是登录方式标签，已登录时是退出登录按钮。
        Tab.ACCOUNT -> true
    }

    LaunchedEffect(Unit) {
        while (true) {
            clock = runCatching { SimpleDateFormat("HH:mm", Locale.CHINA).format(Date()) }.getOrDefault("")
            delay(15_000)
        }
    }

    LaunchedEffect(state.toast) {
        val message = state.toast ?: return@LaunchedEffect
        // Clearing all data may spend up to 30 seconds draining pending disk writes.
        // Keep this progress message visible for the whole wait instead of letting the
        // normal short toast disappear and making the app look unresponsive.
        delay(if (message.startsWith("正在保存尚未保存完成的数据")) 45_000 else 2400)
        vm.clearToast()
    }

    // 启动后静默检查一次更新：只有发现新版才弹窗，且不在播放页打扰。
    LaunchedEffect(state.booted, route) {
        if (!state.booted || route != Route.Main) return@LaunchedEffect
        delay(6_000)
        vm.autoCheckUpdates()
    }

    // 启动时播放推荐：首页数据就绪后自动进入第一部。
    LaunchedEffect(state.booted, state.home.loading, state.home.hero.size, route) {
        if (route != Route.Main) return@LaunchedEffect
        val first = vm.consumeStartupRecommendation() ?: return@LaunchedEffect
        vm.openDetail(first)
        route = Route.Detail(first.id, first, autoplay = true)
    }

    // 首页可见性：冷启动用缓存顶首屏，切走后静默补网络，回来就是新内容。
    LaunchedEffect(tab, route, state.booted) {
        vm.setHomeVisible(state.booted && tab == Tab.HOME && route == Route.Main)
    }

    // 脚本热更新：播放中不切换（换集会重新取流），退出播放页后立刻补上。
    LaunchedEffect(route) {
        vm.setPlayerVisible(route is Route.Player)
    }

    // 从播放器退回详情时，把播放器刚写进本地库的进度同步到详情页。
    LaunchedEffect(route) {
        if (route is Route.Detail) vm.refreshDetailResume()
    }

    // 进入详情且请求了「自动播放」时，等详情就绪后直接进播放器。
    LaunchedEffect(route, state.detail.loading) {
        val current = route
        if (current is Route.Detail && current.autoplay && !state.detail.loading) {
            val detail = state.detail.detail
            if (detail != null && detail.episodes.isNotEmpty()) {
                route = Route.Player(
                    series = detail.series,
                    episodes = detail.episodes,
                    episodeIndex = state.detail.resumeIndex.coerceIn(0, detail.episodes.lastIndex),
                    positionMs = state.detail.resumePosition,
                )
            }
        }
    }

    // 投播匹配成功后只留一个跳转请求：消费后立刻清空，避免重组时重复进详情。
    LaunchedEffect(state.cast.open) {
        val open = state.cast.open ?: return@LaunchedEffect
        vm.consumeCastOpen()
        route = Route.Detail(open.series.id, open.series, autoplay = true)
    }

    // 冷启动、切换 Tab、从详情/搜索返回时，把光标带回「下划线所在的导航项」。
    // 之前这里请求的是内容区第一个元素，一旦数据没就绪/未 attach 就整段失败，
    // 表现为下划线已经跳到首页、光标还赖在设置上，再按一次确定又回设置页。
    var pendingFocus by remember { mutableStateOf<Tab?>(null) }
    // 非当前导航项按「下」：先切页，等这一页的内容落点 attach 后再把焦点交过去。
    var pendingContent by remember { mutableStateOf<Tab?>(null) }
    // 切季后希望光标落在新季的季签上，连按左右就能换季；详情页请求一次后回清。
    var pendingSeasonFocus by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(tab, route, state.booted) {
        // 从导航项按下进内容时由 pendingContent 接管光标，不在成功进入后拉回导航栏。
        if (route == Route.Main && state.booted && pendingContent == null) pendingFocus = tab
    }
    LaunchedEffect(pendingFocus, route, state.booted) {
        val target = pendingFocus ?: return@LaunchedEffect
        if (route != Route.Main || !state.booted) return@LaunchedEffect
        val requester = tabAnchors[target] ?: return@LaunchedEffect
        repeat(20) {
            if (requester.tryRequestFocus()) {
                pendingFocus = null
                return@LaunchedEffect
            }
            withFrameNanos { }
        }
    }

    // 导航栏按「下」只进入当前选中页，不根据焦点所在的导航项切页。
    // 即时请求失败或页面尚未就绪时，保留意图并在入口 attach 后重试，
    // 避免几何搜索把焦点送到其他页面。
    LaunchedEffect(pendingContent, tab, route, state.booted, contentReady) {
        val target = pendingContent ?: return@LaunchedEffect
        if (route != Route.Main || !state.booted) return@LaunchedEffect
        if (tab != target) {
            // 用户已切到别的页：旧的进入意图作废，并把焦点同步到新选中页导航项。
            pendingContent = null
            pendingFocus = tab
            return@LaunchedEffect
        }
        val requester = entryOf(target)
        if (requester == null) {
            // 这一页本来就没有内容落点（设置/账号）：停在导航项，清掉意图。
            tabAnchors[target]?.tryRequestFocus()
            pendingContent = null
            return@LaunchedEffect
        }
        if (!contentReady) {
            // 有落点但内容还在加载：保持 pendingContent，等 contentReady 变 true 后
            // 本 effect 会因依赖变化重启，再把焦点精确送进去。此时绝不动焦点、也不清除意图。
            return@LaunchedEffect
        }
        // 内容就绪：落点可能刚 attach，多试几帧确保成功。
        var moved = false
        var attempts = 0
        while (!moved && attempts < 30) {
            moved = requester.tryRequestFocus()
            if (!moved) withFrameNanos { }
            attempts++
        }
        // 极端情况仍未 attach：停在导航项，绝不让焦点掉进别的页面。
        if (!moved) tabAnchors[target]?.tryRequestFocus()
        pendingContent = null
    }

    BackHandler(enabled = route != Route.Main) {
        route = when (val current = route) {
            is Route.Player -> Route.Detail(current.series.id, current.series)
            is Route.Detail -> Route.Main
            else -> Route.Main
        }
    }

    BackHandler(enabled = route == Route.Main && tab != Tab.HOME) {
        tab = Tab.HOME
    }
    BackHandler(enabled = route == Route.Main && tab == Tab.HOME) {
        (context as? Activity)?.finish()
    }

    AmbientBackground {
        AnimatedContent(
            targetState = route,
            transitionSpec = {
                (fadeIn(androidx.compose.animation.core.tween(260)) + slideInHorizontally { it / 24 })
                    .togetherWith(fadeOut(androidx.compose.animation.core.tween(180)))
            },
            label = "route",
        ) { target ->
            when (target) {
                Route.Main -> Box(Modifier.fillMaxSize()) {
                    MainTabContent(
                        tab = tab,
                        vm = vm,
                        state = state,
                        snapshot = snapshot,
                        onTab = { tab = it },
                        onDetail = { series -> vm.openDetail(series); route = Route.Detail(series.id, series) },
                        onPlay = { series -> vm.openDetail(series); route = Route.Detail(series.id, series, autoplay = true) },
                        onResume = { series ->
                            if (resumingSeriesId == null) {
                                resumingSeriesId = series.id
                                vm.toast("正在打开上次进度…")
                                appScope.launch {
                                    val detail = vm.playbackDetail(series)
                                    resumingSeriesId = null
                                    if (detail != null && detail.episodes.isNotEmpty()) {
                                        val (index, position) = vm.resumePoint(series.id, detail.episodes.size)
                                        route = Route.Player(detail.series, detail.episodes, index, position)
                                    } else {
                                        // 取不到剧集列表时退回详情页，这条路用户至少能重试。
                                        vm.toast("暂时无法获取剧集，已打开详情页")
                                        vm.openDetail(series)
                                        route = Route.Detail(series.id, series)
                                    }
                                }
                            }
                        },
                        onSearch = { vm.openSearch(); route = Route.Search },
                        onCollection = { kind -> route = Route.Collection(kind) },
                        onApiStatus = { showApiStatus = true },
                        homeEntry = homeEntry,
                        theaterEntry = theaterEntry,
                        rankEntry = rankEntry,
                        libraryEntry = libraryEntry,
                        settingsEntry = settingsEntry,
                        accountEntry = accountEntry,
                        upFocus = tabAnchors[tab],
                        initialSettingsSection = pendingSettingsSection,
                        onSettingsSectionConsumed = { pendingSettingsSection = null },
                    )
                    TopBar(
                        current = tab,
                        onSelect = { selected -> tab = selected },
                        onSearch = { vm.openSearch(); route = Route.Search },
                        onCast = { route = Route.Cast },
                        elevated = false,
                        clock = if (snapshot?.showClock == true) clock else "",
                        accountName = state.account.nickname.takeIf { state.account.signedIn },
                        onAccount = {
                            tab = Tab.ACCOUNT
                            if (!state.account.signedIn && state.account.mode == LoginMode.QR &&
                                state.account.qrBitmap == null
                            ) {
                                vm.startQrLogin()
                            }
                        },
                        apiStatus = apiStatus,
                        onApiStatus = { showApiStatus = true },
                        contentEntry = entryOf(tab)?.takeIf { contentReady },
                        tabFocus = tabAnchors,
                        onEnterContent = { selected ->
                            // 仅记录当前选中页的进入意图；不因焦点所在的导航项切换页面。
                            // 即使即时 requestFocus 失败，也由 pendingContent 等待 attach 后重试。
                            if (selected == tab) pendingContent = selected
                        },
                        modifier = Modifier.align(Alignment.TopCenter),
                    )
                }

                is Route.Cast -> CastScreen(
                    state = state.cast,
                    onBack = { route = Route.Main },
                    showBack = snapshot?.showScreenBack == true,
                    onStart = vm::startCastServer,
                    onRestart = vm::restartCastServer,
                    onStop = vm::stopCastServer,
                    onDismissCandidates = vm::dismissCastCandidates,
                    onPick = vm::pickCastCandidate,
                )

                is Route.Search -> SearchScreen(
                    state = state.search,
                    onQuery = vm::updateQuery,
                    onSubmit = { vm.submitSearch(it) },
                    onType = vm::setSearchType,
                    onKeyboard = vm::toggleKeyboard,
                    onLoadMore = vm::loadMoreSearch,
                    onClearHistory = vm::clearSearchHistory,
                    onClear = vm::clearSearch,
                    onHistory = { q, type -> vm.submitSearch(q, type) },
                    onDetail = { series -> vm.openDetail(series); route = Route.Detail(series.id, series) },
                    onBack = { route = Route.Main },
                )

                is Route.Detail -> DetailScreen(
                    state = state.detail,
                    onBack = { route = Route.Main },
                    focusSeasonId = pendingSeasonFocus,
                    onSeasonFocusConsumed = { pendingSeasonFocus = null },
                    onPlay = { index, position ->
                        val detail = state.detail.detail
                        if (detail != null && detail.episodes.isNotEmpty()) {
                            route = Route.Player(detail.series, detail.episodes, index, position)
                        }
                    },
                    onNextEpisode = {},
                    onFavorite = { state.detail.series?.let { vm.toggleFavorite(it) } },
                    onLater = { state.detail.series?.let { vm.toggleLater(it) } },
                    onSeries = { series ->
                        // 从季签切换时把光标留在新季上；从相关推荐进来则按常规落在播放键。
                        pendingSeasonFocus = series.id
                        vm.openDetail(series)
                        route = Route.Detail(series.id, series)
                    },
                    onExpandSynopsis = vm::toggleSynopsis,
                    showBack = snapshot?.showScreenBack == true,
                )

                is Route.Player -> PlayerRoute(
                    route = target,
                    vm = vm,
                    clock = clock,
                    onBack = {
                        route = Route.Detail(target.series.id, target.series)
                    },
                    onDanmakuSettings = {
                        pendingSettingsSection = "DANMAKU"
                        tab = Tab.SETTINGS
                        route = Route.Main
                    },
                )

                is Route.Collection -> CollectionScreen(
                    title = target.kind.title,
                    entries = vm.collectionItems(target.kind),
                    onDetail = { series -> vm.openDetail(series); route = Route.Detail(series.id, series) },
                    onBack = { route = Route.Main },
                )
            }
        }

        ToastHost(
            message = state.toast,
            modifier = Modifier.align(Alignment.BottomCenter).background(androidx.compose.ui.graphics.Color.Transparent),
        )
    }

    if (showApiStatus) {
        ApiStatusDialog(
            state = apiStatus,
            onRefresh = { key -> vm.refreshApiStatus(key) },
            onDismiss = { showApiStatus = false },
        )
    }
    UpdateDialog(
        state = update,
        onDownload = vm::downloadUpdate,
        onCancelDownload = vm::cancelUpdateDownload,
        onInstall = {
            val info = update.update
            val path = update.apkPath
            val activity = context as? Activity
            if (info != null && path != null && activity != null) {
                runCatching { AppUpdateInstaller.install(activity, File(path), info) }
                    .onSuccess { launched ->
                        if (!launched) vm.notifyUpdate("已在系统中打开安装权限页，允许后返回再点「立即安装」")
                    }
                    .onFailure { vm.notifyUpdate("安装器无法启动：${it.message ?: "未知错误"}") }
            }
        },
        onRetry = {
            if (update.update != null && update.phase == com.hongguotv.nativeapp.ui.data.UpdatePhase.ERROR) vm.downloadUpdate()
            else vm.checkUpdates()
        },
        onDismiss = vm::dismissUpdateDialog,
        onPickMirror = vm::setUpdateMirror,
    )
}

@Composable
private fun PlayerRoute(
    route: Route.Player,
    vm: AppViewModel,
    clock: String,
    onBack: () -> Unit,
    onDanmakuSettings: () -> Unit,
) {
    val session = remember(route.series.id) {
        PlayerSession(
            context = vm.getApplication(),
            repository = vm.repository,
            library = vm.library,
            extras = vm.extras,
            series = route.series,
            episodes = route.episodes,
            initialIndex = route.episodeIndex,
            initialPositionMs = route.positionMs,
        )
    }
    androidx.compose.runtime.DisposableEffect(session) {
        onDispose {
            // 先落盘再刷新首页：否则「接着看」要等下次启动才出现。
            session.release()
            vm.syncWatchHistory()
        }
    }
    // 切后台暂停、回前台恢复：Activity 不随 Compose 页面 dispose，切后台时播放器会继续播。
    // 只在切后台前正在播时才恢复，不覆盖用户此前的手动暂停。
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { session.onAppBackground() }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { session.onAppForeground() }
    PlayerScreen(
        session = session,
        settings = remember { vm.settingsSnapshot() },
        onBack = onBack,
        clock = clock,
        onDanmakuSettings = onDanmakuSettings,
    )
}

@Composable
private fun MainTabContent(
    tab: Tab,
    vm: AppViewModel,
    state: com.hongguotv.nativeapp.ui.data.AppState,
    snapshot: com.hongguotv.nativeapp.ui.data.SettingsSnapshot?,
    onTab: (Tab) -> Unit,
    onDetail: (Series) -> Unit,
    onPlay: (Series) -> Unit,
    onResume: (Series) -> Unit,
    onSearch: () -> Unit,
    onCollection: (CollectionKind) -> Unit,
    onApiStatus: () -> Unit = {},
    homeEntry: FocusRequester? = null,
    theaterEntry: FocusRequester? = null,
    rankEntry: FocusRequester? = null,
    libraryEntry: FocusRequester? = null,
    settingsEntry: FocusRequester? = null,
    accountEntry: FocusRequester? = null,
    upFocus: FocusRequester? = null,
    initialSettingsSection: String? = null,
    onSettingsSectionConsumed: () -> Unit = {},
) {
    val favoriteIds = remember(state.library.version) { state.library.favorites.map { it.id }.toSet() }
    val progressById = remember(state.library.version) { state.library.history.associateBy { it.series.id } }
    // 进入页面即按需加载：不依赖顶栏点击路径，冷启动或从其它页返回都能自愈。
    LaunchedEffect(tab, state.booted) {
        if (!state.booted) return@LaunchedEffect
        when (tab) {
            Tab.THEATER -> if (state.theater.items.isEmpty() && !state.theater.loading) vm.loadTheater(state.theater.type)
            Tab.RANK -> if (state.ranking.items.isEmpty() && !state.ranking.loading) vm.loadRanking(state.ranking.board)
            Tab.LIBRARY -> vm.refreshLibraryState()
            Tab.ACCOUNT -> if (!state.account.signedIn && state.account.mode == LoginMode.QR &&
                state.account.qrBitmap == null
            ) {
                vm.startQrLogin()
            }
            else -> Unit
        }
    }
    when (tab) {
        Tab.HOME -> HomeScreen(
            state = state.home,
            posterColumns = snapshot?.posterColumns ?: 0,
            favoriteIds = favoriteIds,
            progressById = progressById,
            onPlay = onPlay,
            onDetail = onDetail,
            onResume = onResume,
            onFavorite = { vm.toggleFavorite(it) },
            onCollection = onCollection,
            onRefresh = { vm.loadHome(refresh = true) },
            onHeroIndex = vm::setHeroIndex,
            entryFocus = homeEntry,
            upFocus = upFocus,
        )

        Tab.THEATER -> TheaterScreen(
            state = state.theater,
            posterColumns = snapshot?.posterColumns ?: 0,
            onType = { type -> vm.loadTheater(type) },
            onFilter = { group, option -> vm.selectTheaterFilter(group, option) },
            onResetFilters = vm::clearTheaterFilters,
            onLoadMore = vm::loadMoreTheater,
            onDetail = onDetail,
            onRetry = { vm.loadTheater(state.theater.type) },
            entryFocus = theaterEntry,
            upFocus = upFocus,
        )

        Tab.RANK -> RankingScreen(
            state = state.ranking,
            onBoard = { board -> vm.loadRanking(board) },
            onDetail = onDetail,
            onLoadMore = vm::loadMoreRanking,
            onRetry = { vm.loadRanking(state.ranking.board) },
            entryFocus = rankEntry,
            upFocus = upFocus,
        )

        Tab.LIBRARY -> LibraryScreen(
            state = state.library,
            onDetail = onDetail,
            // 片单页的「继续观看」与首页「接着看」同一行为：直接续播，不进详情页。
            onResume = { progress -> onResume(progress.series) },
            onRemoveHistory = vm::removeHistory,
            onClearHistory = vm::clearHistory,
            onToggleFavorite = { vm.toggleFavorite(it) },
            onToggleLater = { vm.toggleLater(it) },
            onGoTheater = { onTab(Tab.THEATER) },
            version = state.library.version,
            entryFocus = libraryEntry,
            upFocus = upFocus,
        )

        Tab.SETTINGS -> if (snapshot != null) {
            SettingsScreen(
                vm = vm,
                snapshot = snapshot,
                versionName = BuildConfig.VERSION_NAME,
                onApiStatus = onApiStatus,
                initialSection = initialSettingsSection,
                onInitialSectionConsumed = onSettingsSectionConsumed,
                entryFocus = settingsEntry,
                upFocus = upFocus,
            )
        }

        Tab.ACCOUNT -> AccountScreen(
            state = state.account,
            onLogin = vm::startQrLogin,
            onSignOut = vm::signOut,
            onSwitchMode = vm::switchLoginMode,
            onPhone = vm::setLoginPhone,
            onCode = vm::setLoginCode,
            onSendCode = vm::sendLoginCode,
            onVerify = vm::verifyLoginCode,
            onResetPhone = vm::resetLoginPhone,
            entryFocus = accountEntry,
        )
    }
}
