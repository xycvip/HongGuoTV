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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
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
import com.hongguotv.nativeapp.ui.screens.SearchScreen
import com.hongguotv.nativeapp.ui.screens.SettingsScreen
import com.hongguotv.nativeapp.ui.screens.TheaterScreen
import java.io.File
import com.hongguotv.nativeapp.ui.theme.Ink
import kotlinx.coroutines.delay
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
    val context = LocalContext.current
    val apiStatus by vm.apiStatus.collectAsStateWithLifecycle()
    val update by vm.update.collectAsStateWithLifecycle()
    val snapshot = remember(state.library.version, state.settingsRevision) { runCatching { vm.settingsSnapshot() }.getOrNull() }

    // 每个主页面第一个可聚焦元素的锚点：导航栏选中项按下、返回首页、冷启动都靠它定位光标。
    val homeEntry = remember { FocusRequester() }
    val theaterEntry = remember { FocusRequester() }
    val rankEntry = remember { FocusRequester() }
    val libraryEntry = remember { FocusRequester() }
    fun entryOf(target: Tab): FocusRequester? = when (target) {
        Tab.HOME -> homeEntry
        Tab.THEATER -> theaterEntry
        Tab.RANK -> rankEntry
        Tab.LIBRARY -> libraryEntry
        Tab.SETTINGS -> null
        Tab.ACCOUNT -> null
    }

    LaunchedEffect(Unit) {
        while (true) {
            clock = runCatching { SimpleDateFormat("HH:mm", Locale.CHINA).format(Date()) }.getOrDefault("")
            delay(15_000)
        }
    }

    LaunchedEffect(state.toast) {
        if (state.toast != null) {
            delay(2400)
            vm.clearToast()
        }
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

    // 冷启动、切换 Tab、从详情/搜索返回时，把光标带回当前页的首个可聚焦元素。
    // 用「待办标记」而不是直接依赖数据变化，避免翻页加载时把焦点抢回顶部。
    var pendingFocus by remember { mutableStateOf<Tab?>(null) }
    LaunchedEffect(tab, route, state.booted) {
        if (route == Route.Main && state.booted) pendingFocus = tab
    }
    LaunchedEffect(
        pendingFocus,
        state.home.hero.size,
        state.theater.items.size,
        state.theater.loading,
        state.ranking.items.size,
        state.ranking.loading,
    ) {
        val target = pendingFocus ?: return@LaunchedEffect
        if (route != Route.Main) return@LaunchedEffect
        val requester = when (target) {
            Tab.HOME -> homeEntry.takeIf { state.home.hero.isNotEmpty() }
            else -> entryOf(target)
        } ?: return@LaunchedEffect
        repeat(20) {
            if (requester.tryRequestFocus()) {
                pendingFocus = null
                return@LaunchedEffect
            }
            withFrameNanos { }
        }
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
                        onSearch = { vm.openSearch(); route = Route.Search },
                        onCollection = { kind -> route = Route.Collection(kind) },
                        onApiStatus = { showApiStatus = true },
                        homeEntry = homeEntry,
                        theaterEntry = theaterEntry,
                        rankEntry = rankEntry,
                        libraryEntry = libraryEntry,
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
                            if (!state.account.signedIn && state.account.qrBitmap == null) vm.startQrLogin()
                        },
                        apiStatus = apiStatus,
                        onApiStatus = { showApiStatus = true },
                        contentEntry = entryOf(tab),
                        modifier = Modifier.align(Alignment.TopCenter),
                    )
                }

                is Route.Cast -> CastScreen(
                    state = state.cast,
                    onBack = { route = Route.Main },
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
                    onPlay = { index, position ->
                        val detail = state.detail.detail
                        if (detail != null && detail.episodes.isNotEmpty()) {
                            route = Route.Player(detail.series, detail.episodes, index, position)
                        }
                    },
                    onNextEpisode = {},
                    onFavorite = { state.detail.series?.let { vm.toggleFavorite(it) } },
                    onLater = { state.detail.series?.let { vm.toggleLater(it) } },
                    onSeries = { series -> vm.openDetail(series); route = Route.Detail(series.id, series) },
                    onExpandSynopsis = vm::toggleSynopsis,
                )

                is Route.Player -> PlayerRoute(
                    route = target,
                    vm = vm,
                    onBack = {
                        route = Route.Detail(target.series.id, target.series)
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
private fun PlayerRoute(route: Route.Player, vm: AppViewModel, onBack: () -> Unit) {
    val session = remember(route.series.id) {
        PlayerSession(
            context = vm.getApplication(),
            repository = vm.repository,
            library = vm.library,
            extras = vm.extras,
            series = route.series,
            episodes = route.episodes,
            initialIndex = route.episodeIndex,
        )
    }
    androidx.compose.runtime.DisposableEffect(session) {
        onDispose { session.release() }
    }
    PlayerScreen(
        session = session,
        settings = remember { vm.settingsSnapshot() },
        onBack = onBack,
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
    onSearch: () -> Unit,
    onCollection: (CollectionKind) -> Unit,
    onApiStatus: () -> Unit = {},
    homeEntry: FocusRequester? = null,
    theaterEntry: FocusRequester? = null,
    rankEntry: FocusRequester? = null,
    libraryEntry: FocusRequester? = null,
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
            Tab.ACCOUNT -> if (!state.account.signedIn && state.account.qrBitmap == null) vm.startQrLogin()
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
            onFavorite = { vm.toggleFavorite(it) },
            onCollection = onCollection,
            onRefresh = { vm.loadHome(refresh = true) },
            onHeroIndex = vm::setHeroIndex,
            entryFocus = homeEntry,
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
        )

        Tab.RANK -> RankingScreen(
            state = state.ranking,
            onBoard = { board -> vm.loadRanking(board) },
            onDetail = onDetail,
            onLoadMore = vm::loadMoreRanking,
            onRetry = { vm.loadRanking(state.ranking.board) },
            entryFocus = rankEntry,
        )

        Tab.LIBRARY -> LibraryScreen(
            state = state.library,
            onDetail = onDetail,
            onResume = { progress -> onPlay(progress.series) },
            onRemoveHistory = vm::removeHistory,
            onClearHistory = vm::clearHistory,
            onToggleFavorite = { vm.toggleFavorite(it) },
            onToggleLater = { vm.toggleLater(it) },
            onGoTheater = { onTab(Tab.THEATER) },
            version = state.library.version,
            entryFocus = libraryEntry,
        )

        Tab.SETTINGS -> if (snapshot != null) {
            SettingsScreen(
                vm = vm,
                snapshot = snapshot,
                versionName = BuildConfig.VERSION_NAME,
                onApiStatus = onApiStatus,
            )
        }

        Tab.ACCOUNT -> AccountScreen(
            state = state.account,
            onLogin = vm::startQrLogin,
            onSignOut = vm::signOut,
        )
    }
}
