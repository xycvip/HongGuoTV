// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.itemsIndexed
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.hongguotv.core.*
import androidx.media3.common.Player
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Locale

private data class PhonePalette(
    val background: Color, val surface: Color, val card: Color, val text: Color,
    val muted: Color, val accent: Color, val dark: Boolean
)

private fun phonePalette(style: String, dark: Boolean): PhonePalette = when (style) {
    "immersive" -> if (dark) PhonePalette(Color(0xFF0E0E11), Color(0xFF1A1A1F), Color(0xFF26262D), Color(0xFFF5F5F7), Color(0xFFA3A3AD), Color(0xFFD63A24), true)
        else PhonePalette(Color(0xFFF6F6F8), Color.White, Color(0xFFE9E9ED), Color(0xFF17171B), Color(0xFF62626C), Color(0xFFD63A24), false)
    "cards" -> if (dark) PhonePalette(Color(0xFF0F1012), Color(0xFF1A1B1F), Color(0xFF23252A), Color(0xFFF2F3F5), Color(0xFFA5A9B1), Color(0xFFD42E24), true)
        else PhonePalette(Color(0xFFF3F4F6), Color.White, Color(0xFFE9EBEE), Color(0xFF17181B), Color(0xFF5F646C), Color(0xFFD42E24), false)
    else -> if (dark) PhonePalette(Color(0xFF1A1512), Color(0xFF241E1A), Color(0xFF2C2520), Color(0xFFF5EEE8), Color(0xFFB8ACA3), Color(0xFFC94426), true)
        else PhonePalette(Color(0xFFFCF8F4), Color.White, Color(0xFFF5EDE5), Color(0xFF292421), Color(0xFF6B625C), Color(0xFFC94426), false)
}

private const val PhoneThemeKey = "phone_ui_theme_style"

internal fun MainActivity.showPhoneComposeCatalog() {
    if (!isPhoneDevice) return
    phoneUiState.useLegacyPage = false
    invalidatePage()
    releaseCatalogViews()
    screen = "catalog"
    applyDeviceOrientationPolicy()
    initialCatalogPending = false
    restoreStoppedPage = false
    pendingDetail = null
    phoneUiState.page = PhonePage.CATALOG
    phoneUiState.selectedSeries = null
    phoneUiState.detail = null
    phoneUiState.tab = tab
    val host = phoneUiState.catalogHost ?: ComposeView(this).also {
        phoneUiState.catalogHost = it
        it.setContent { PhoneComposeApp(this@showPhoneComposeCatalog) }
    }
    if (host.parent !== root) {
        root.removeAllViews()
        root.addView(host, android.widget.FrameLayout.LayoutParams(-1, -1))
    }
    phoneUiState.catalogRevision++
}

internal fun MainActivity.showPhoneComposeDetail() {
    if (!isPhoneDevice) return
    screen = "detail"
    applyDeviceOrientationPolicy()
    phoneUiState.page = PhonePage.DETAIL
    phoneUiState.selectedSeries = detail?.series ?: phoneUiState.selectedSeries
    phoneUiState.detail = detail
    val host = phoneUiState.catalogHost ?: run { showPhoneComposeCatalog(); phoneUiState.catalogHost } ?: return
    if (host.parent !== root) {
        root.removeAllViews()
        root.addView(host, android.widget.FrameLayout.LayoutParams(-1, -1))
    }
    phoneUiState.catalogRevision++
}

internal fun MainActivity.installPhonePlayerComposeOverlay() {
    if (!isPhoneDevice || screen != "player") return
    hud.visibility = android.view.View.GONE
    val host = phoneUiState.playerHost ?: ComposeView(this).also {
        phoneUiState.playerHost = it
        it.setContent { PhonePlayerOverlay(this@installPhonePlayerComposeOverlay) }
    }
    if (host.parent !== root) root.addView(host, android.widget.FrameLayout.LayoutParams(-1, -1))
    host.bringToFront()
    debugOverlayText?.bringToFront()
    phoneUiState.playerControlsVisible = true
}

@Composable
private fun PhoneComposeApp(activity: MainActivity) {
    val state = activity.phoneUiState
    val preferences = remember { activity.getSharedPreferences("phone-ui-preferences", android.content.Context.MODE_PRIVATE) }
    var style by remember { mutableStateOf(preferences.getString(PhoneThemeKey, "warm") ?: "warm") }
    var themeMode by remember { mutableStateOf(preferences.getString("phone_ui_theme_mode", "default") ?: "default") }
    var navStyle by remember { mutableStateOf(preferences.getString("phone_ui_nav_bar_style", "classic") ?: "classic") }
    val dark = when (themeMode) {
        "light" -> false
        "dark" -> true
        "system" -> isSystemInDarkTheme()
        else -> style == "immersive"
    }
    val palette = remember(style, dark) { phonePalette(style, dark) }
    var searchText by remember { mutableStateOf("") }
    var submittedQuery by remember { mutableStateOf("") }
    var suggestions by remember { mutableStateOf(emptyList<String>()) }
    var shortItems by remember { mutableStateOf(emptyList<Series>()) }
    var comicItems by remember { mutableStateOf(emptyList<Series>()) }
    var aiItems by remember { mutableStateOf(emptyList<Series>()) }
    var pageItems by remember { mutableStateOf(emptyList<Series>()) }
    var theaterGroups by remember { mutableStateOf(emptyList<TheaterFilterGroup>()) }
    var theaterSelections by remember { mutableStateOf(emptyMap<String, String>()) }
    var theaterType by remember { mutableStateOf(activity.library.contentType) }
    var rankBoard by remember { mutableStateOf(RankBoard.TOTAL) }
    var homeType by remember { mutableStateOf(ContentType.SHORT) }
    var homePage by remember { mutableIntStateOf(1) }
    var homeHasMore by remember { mutableStateOf(true) }
    var homeLoadingMore by remember { mutableStateOf(false) }
    var forceHomeRefresh by remember { mutableStateOf(false) }
    var pageNumber by remember { mutableIntStateOf(1) }
    var hasMore by remember { mutableStateOf(false) }
    var loadingMore by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var detailLoading by remember { mutableStateOf(false) }
    var detail by remember { mutableStateOf<Detail?>(state.detail) }
    var selected by remember { mutableStateOf<Series?>(state.selectedSeries) }
    var refresh by remember { mutableIntStateOf(0) }
    var settingsOpen by remember { mutableStateOf(false) }

    BackHandler(enabled = settingsOpen || state.page == PhonePage.DETAIL || state.tab != 0) {
        if (settingsOpen) {
            settingsOpen = false
        } else if (state.page == PhonePage.DETAIL) {
            state.page = PhonePage.CATALOG
            state.selectedSeries = null
            state.detail = null
        } else activity.switchTab(0)
    }

    LaunchedEffect(state.tab) {
        pageNumber = 1
        if (state.tab == 0) { homePage = 1; homeLoadingMore = false }
        if (state.tab == 1) { searchText = ""; submittedQuery = ""; pageItems = emptyList(); suggestions = emptyList() }
    }

    LaunchedEffect(searchText, state.tab) {
        if (state.tab != 1 || searchText.trim().length < 1) { suggestions = emptyList(); return@LaunchedEffect }
        delay(260)
        suggestions = withContext(Dispatchers.IO) { runCatching { activity.repository.suggest(searchText.trim()) }.getOrDefault(emptyList()) }
    }

    LaunchedEffect(state.tab, state.catalogRevision, submittedQuery, refresh, theaterType, theaterSelections, rankBoard, pageNumber, homePage, homeType, forceHomeRefresh) {
        if (state.page == PhonePage.DETAIL) return@LaunchedEffect
        loading = true
        error = null
        val tabNow = state.tab
        val result = withContext(Dispatchers.IO) {
            runCatching {
                var fetchedTheaterFilters = emptyList<TheaterFilterGroup>()
                var fetchedHasMore = false
                val rows = when (tabNow) {
                    0 -> {
                        if (homePage > 1) {
                            val page = if (homeType == ContentType.AI) activity.repository.ranking(RankBoard.AI, homePage)
                                else activity.repository.home(homePage, homeType)
                            fetchedHasMore = page.hasMore
                            when (homeType) {
                                ContentType.COMIC -> Triple(emptyList(), page.items, emptyList())
                                ContentType.AI -> Triple(emptyList(), emptyList(), page.items)
                                else -> Triple(page.items, emptyList(), emptyList())
                            }
                        } else {
                            val shortPage = if (forceHomeRefresh) activity.repository.home(1, ContentType.SHORT)
                                else activity.library.cachedHome(ContentType.SHORT) ?: activity.repository.home(1, ContentType.SHORT)
                            val comicPage = if (forceHomeRefresh) activity.repository.home(1, ContentType.COMIC)
                                else activity.library.cachedHome(ContentType.COMIC) ?: activity.repository.home(1, ContentType.COMIC)
                            val aiPage = if (forceHomeRefresh) activity.repository.ranking(RankBoard.AI, 1)
                                else activity.library.cachedHome(ContentType.AI) ?: activity.repository.ranking(RankBoard.AI, 1)
                            fetchedHasMore = when (homeType) {
                                ContentType.COMIC -> comicPage.hasMore
                                ContentType.AI -> aiPage.hasMore
                                else -> shortPage.hasMore
                            }
                            Triple(shortPage.items, comicPage.items, aiPage.items)
                        }
                    }
                    1 -> {
                        if (submittedQuery.isBlank()) Triple(emptyList(), emptyList(), emptyList())
                        else {
                            val page = activity.repository.searchCombined(submittedQuery, pageNumber)
                            fetchedHasMore = page.hasMore
                            Triple(page.items, emptyList(), emptyList())
                        }
                    }
                    2 -> {
                        val page = activity.repository.ranking(rankBoard, pageNumber)
                        fetchedHasMore = page.hasMore
                        Triple(page.items, emptyList(), emptyList())
                    }
                    7 -> {
                        val theater = activity.repository.theater(pageNumber, theaterType, theaterSelections)
                        fetchedTheaterFilters = theater.filters
                        fetchedHasMore = theater.catalog.hasMore
                        Triple(theater.catalog.items, emptyList(), emptyList())
                    }
                    3 -> Triple(activity.library.favorites(), emptyList(), emptyList())
                    4 -> Triple(activity.library.history().map { it.series }.distinctBy { it.id }, emptyList(), emptyList())
                    else -> Triple(emptyList(), emptyList(), emptyList())
                }
                (rows to fetchedTheaterFilters) to fetchedHasMore
            }
        }
        result.onSuccess { payload ->
            val resultRows = payload.first.first
            theaterGroups = payload.first.second
            hasMore = payload.second
            if (tabNow == 0) {
                homeHasMore = payload.second
                if (homePage > 1) {
                    when (homeType) {
                        ContentType.COMIC -> comicItems = (comicItems + resultRows.second).distinctBy { it.id }
                        ContentType.AI -> aiItems = (aiItems + resultRows.third).distinctBy { it.id }
                        else -> shortItems = (shortItems + resultRows.first).distinctBy { it.id }
                    }
                } else {
                    shortItems = resultRows.first
                    comicItems = resultRows.second
                    aiItems = resultRows.third
                }
                homeLoadingMore = false
                forceHomeRefresh = false
            } else {
                shortItems = resultRows.first
                comicItems = resultRows.second
                aiItems = resultRows.third
            }
            if (pageNumber > 1 && tabNow in setOf(1, 2, 7)) pageItems = (pageItems + resultRows.first).distinctBy { it.id }
            else pageItems = resultRows.first
            if (tabNow == 3) { pageItems = activity.library.favorites(); hasMore = false }
            if (tabNow == 4) { pageItems = activity.library.history().map { it.series }.distinctBy { it.id }; hasMore = false }
            if (tabNow == 0 && homePage == 1) {
                activity.library.cacheHome(ContentType.SHORT, shortItems, homeHasMore)
                if (comicItems.isNotEmpty()) activity.library.cacheHome(ContentType.COMIC, comicItems, true)
                if (aiItems.isNotEmpty()) activity.library.cacheHome(ContentType.AI, aiItems, true)
            }
        }.onFailure { error = it.message ?: "加载失败" }
        loading = false
        loadingMore = false
        if (tabNow == 0) homeLoadingMore = false
    }

    LaunchedEffect(state.page, state.selectedSeries?.id) {
        if (state.page != PhonePage.DETAIL) {
            selected = null
            detail = null
            return@LaunchedEffect
        }
        selected = state.selectedSeries
        val series = selected ?: return@LaunchedEffect
        detailLoading = true
        val loaded = withContext(Dispatchers.IO) { runCatching { activity.repository.detail(series.id) } }
        loaded.onSuccess {
            detail = it
            state.detail = it
            activity.detail = it
            activity.library.observeFavorite(series.id, it.episodes.size, System.currentTimeMillis(), acknowledge = true)
        }.onFailure { error = it.message ?: "详情加载失败" }
        detailLoading = false
    }

    MaterialTheme(colorScheme = if (palette.dark) darkColorScheme(
        primary = palette.accent, background = palette.background, surface = palette.surface,
        onPrimary = Color.White, onBackground = palette.text, onSurface = palette.text
    ) else lightColorScheme(
        primary = palette.accent, background = palette.background, surface = palette.surface,
        onPrimary = Color.White, onBackground = palette.text, onSurface = palette.text
    )) {
        Surface(Modifier.fillMaxSize(), color = palette.background) {
            AnimatedContent(state.page, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "phone_page") { page ->
                if (page == PhonePage.DETAIL) {
                    PhoneDetailPage(detail, detailLoading, error, palette, style,
                        resumeIndex = detail?.let { d -> activity.library.progress(d.series.id)?.let { d.episodes.indexOf(it.episodeId) }?.takeIf { it >= 0 } },
                        isFavorite = detail?.let { d -> activity.library.favorites().any { it.id == d.series.id } } == true,
                        isLater = detail?.let { d -> activity.library.queued(d.series.id) } == true,
                        onLater = { item -> activity.library.toggleLater(item); refresh++ },
                        onResume = {
                            val d = detail
                            val progress = d?.let { activity.library.progress(it.series.id) }
                            val index = d?.episodes?.indexOf(progress?.episodeId ?: "").takeIf { it != null && it >= 0 } ?: 0
                            if (d != null) { activity.detail = d; activity.playEpisode(index, progress?.position ?: 0L) }
                        },
                        onRelated = { openPhoneDetail(activity, it) },
                        onBack = {
                            state.page = PhonePage.CATALOG
                            state.selectedSeries = null
                            state.detail = null
                        },
                        onPlay = { index ->
                            val data = detail ?: return@PhoneDetailPage
                            activity.detail = data
                            activity.episodeIndex = index.coerceIn(data.episodes.indices)
                            activity.playEpisode(activity.episodeIndex)
                        },
                        onFavorite = { item -> activity.library.toggle(item); refresh++ })
                } else if (settingsOpen) {
                    PhoneSettingsPage(activity, palette, style, themeMode, navStyle,
                        onBack = { settingsOpen = false },
                        onTheme = { next -> style = next; preferences.edit().putString(PhoneThemeKey, next).apply() },
                        onMode = { next -> themeMode = next; preferences.edit().putString("phone_ui_theme_mode", next).apply() },
                        onNavStyle = { next -> navStyle = next; preferences.edit().putString("phone_ui_nav_bar_style", next).apply() })
                } else {
                    Column(Modifier.fillMaxSize()) {
                        if (state.tab == 0 && style == "warm") PhoneTopBar(palette, state.tab, onBack = { activity.switchTab(0) }, onRank = { activity.switchTab(2) }, onSearch = { activity.switchTab(1) }, onRefresh = { forceHomeRefresh = true; homePage = 1; homeLoadingMore = false; pageNumber = 1; pageItems = emptyList(); refresh++ })
                        Box(Modifier.weight(1f)) {
                            when (state.tab) {
                                0 -> PhoneHomePage(shortItems, comicItems, aiItems, homeType,
                                    activity.library.history().take(8).map { it.series },
                                    activity.library.favorites().filter { activity.library.favoriteUpdate(it.id)?.hasUpdate == true }.take(8),
                                    activity.library.later().take(10),
                                    activity.library.favorites().map { it.id }.toSet(),
                                    (if (style == "immersive") phonePalette(style, true) else palette), style, loading, error,
                                    homeHasMore, homeLoadingMore,
                                    onLoadMore = { if (homeHasMore && !homeLoadingMore) { homeLoadingMore = true; homePage++ } },
                                    onType = { homeType = it; homePage = 1; homeLoadingMore = false; refresh++ },
                                    onSelect = { openPhoneDetail(activity, it) },
                                    onFavorite = { activity.library.toggle(it); refresh++ },
                                    onPlay = { activity.openDetail(it, resume = true) },
                                    onRecentSelect = { activity.openDetail(it, resume = true) },
                                    onSearch = { activity.switchTab(1) }, onRank = { activity.switchTab(2) },
                                    onRefresh = { forceHomeRefresh = true; homePage = 1; homeLoadingMore = false; refresh++ },
                                    onMore = { activity.switchTab(7) })
                                1 -> PhoneSearchPage(searchText, { searchText = it },
                                    { pageNumber = 1; pageItems = emptyList(); submittedQuery = searchText.trim(); if (submittedQuery.isNotBlank()) activity.library.rememberSearch(submittedQuery, ContentType.SHORT); refresh++ },
                                    suggestions, { word -> pageNumber = 1; pageItems = emptyList(); searchText = word; submittedQuery = word; activity.library.rememberSearch(word, ContentType.SHORT); refresh++ },
                                    activity.library.searches(), shortItems.take(8), { activity.library.clearSearches(); refresh++ },
                                    pageItems, loading, error, palette, hasMore, loadingMore, { if (hasMore && !loadingMore) { loadingMore = true; pageNumber++ } },
                                    { openPhoneDetail(activity, it) })
                                2 -> PhoneRankPage(pageItems, rankBoard, loading, error, palette, hasMore, loadingMore,
                                    onBack = { activity.switchTab(0) },
                                    onBoard = { board -> pageNumber = 1; pageItems = emptyList(); rankBoard = board; refresh++ },
                                    onLoadMore = { if (hasMore && !loadingMore) { loadingMore = true; pageNumber++ } },
                                    onSelect = { openPhoneDetail(activity, it) }, onRefresh = { pageNumber = 1; pageItems = emptyList(); refresh++ })
                                7 -> PhoneTheaterPage(pageItems, theaterGroups, theaterSelections, theaterType, loading, error, palette, style, hasMore, loadingMore,
                                    onSearch = { activity.switchTab(1) }, onRank = { activity.switchTab(2) },
                                    onLoadMore = { if (hasMore && !loadingMore) { loadingMore = true; pageNumber++ } },
                                    onType = { pageNumber = 1; pageItems = emptyList(); theaterType = it; theaterSelections = emptyMap(); refresh++ },
                                    onApplyFilters = { values -> pageNumber = 1; pageItems = emptyList(); theaterSelections = values; refresh++ },
                                    onSelect = { openPhoneDetail(activity, it) })
                                3, 4 -> PhoneLibraryPage(state.tab, pageItems, loading, error, palette, style,
                                    onTab = { activity.switchTab(it) },
                                    onSelect = { openPhoneDetail(activity, it) },
                                    onRemove = { item ->
                                        if (state.tab == 3) activity.library.toggle(item) else activity.library.removeHistory(item.id)
                                        refresh++
                                    }, progressLabel = { item -> activity.library.progress(item.id)?.let { "第" + (it.episodeIndex + 1) + "集 · " + phoneTime(it.position) }.orEmpty() },
                                    onRefresh = { refresh++ })
                                else -> PhoneMinePage(palette, style, themeMode,
                                    accountName = activity.cloudAccount.accountLabel,
                                    accountStatus = if (activity.cloudAccount.signedIn) "红果账号已登录" else "登录后可同步云端记录",
                                    accountAvatar = activity.cloudAccount.accountAvatarUrl,
                                    favoriteCount = activity.library.favorites().size,
                                    historyCount = activity.library.history().size,
                                    onTheme = { next -> style = next; preferences.edit().putString(PhoneThemeKey, next).apply() },
                                    onMode = { next -> themeMode = next; preferences.edit().putString("phone_ui_theme_mode", next).apply() },
                                    onRefresh = { refresh++ },
                                    onFavorites = { activity.switchTab(3) }, onHistory = { activity.switchTab(4) },
                                    onSettings = { settingsOpen = true },
                                    onAccount = { state.useLegacyPage = true; activity.switchTab(6) })
                            }
                        }
                        if (state.tab !in setOf(1, 2)) PhoneBottomBar(state.tab,
                            if (state.tab == 0 && style == "immersive") phonePalette(style, true) else palette,
                            style, navStyle, onSelect = { target -> activity.switchTab(target) })
                    }
                }
            }
        }
    }
}

private fun openPhoneDetail(activity: MainActivity, series: Series) {
    activity.phoneUiState.selectedSeries = series
    activity.phoneUiState.detail = null
    activity.phoneUiState.page = PhonePage.DETAIL
    activity.phoneUiState.catalogRevision++
}

@Composable
private fun PhoneTopBar(p: PhonePalette, tab: Int, onBack: () -> Unit, onRank: () -> Unit, onSearch: () -> Unit, onRefresh: () -> Unit) {
    val title = when (tab) { 0 -> "红果剧场"; 7 -> "发现"; 2 -> "排行榜"; 3, 4 -> "追剧"; 1 -> "搜索"; else -> "我的" }
    Row(Modifier.fillMaxWidth().background(p.surface).statusBarsPadding().padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        if (tab == 1 || tab == 2) {
            Text("‹", fontSize = 29.sp, color = p.text, modifier = Modifier.clickable(onClick = onBack).padding(end = 12.dp))
        } else {
            Box(Modifier.size(32.dp).clip(RoundedCornerShape(10.dp)).background(p.accent), contentAlignment = Alignment.Center) {
                Text("果", color = Color.White, fontWeight = FontWeight.Black, fontSize = 18.sp)
            }
            Spacer(Modifier.width(10.dp))
        }
        Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = p.text, modifier = Modifier.weight(1f))
        if (tab != 1) {
            if (tab == 0 || tab == 7) Text("榜单", color = p.muted, modifier = Modifier.clickable(onClick = onRank).padding(horizontal = 7.dp, vertical = 10.dp))
            Text("刷新", color = p.muted, modifier = Modifier.clickable(onClick = onRefresh).padding(horizontal = 7.dp, vertical = 10.dp))
            Text("⌕", fontSize = 27.sp, color = p.text, modifier = Modifier.clickable(onClick = onSearch).padding(horizontal = 7.dp))
        }
    }
}

@Composable
private fun PhoneBottomBar(tab: Int, p: PhonePalette, style: String, navStyle: String, onSelect: (Int) -> Unit) {
    val active = when (tab) { 7 -> 7; 2 -> 2; 3, 4 -> 3; 6, 5 -> 6; else -> 0 }
    Row(Modifier.fillMaxWidth().background(p.surface).navigationBarsPadding().padding(horizontal = 12.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.SpaceAround, verticalAlignment = Alignment.CenterVertically) {
        listOf(0 to "首页", 7 to (if (style == "immersive") "剧场" else "发现"), 3 to (if (style == "immersive") "追剧" else "片单"), 6 to "我的").forEach { (id, label) ->
            Column(Modifier.clip(RoundedCornerShape(18.dp)).clickable { onSelect(id) }
                .background(if (active == id) p.accent.copy(alpha = 0.12f) else Color.Transparent)
                .padding(horizontal = 18.dp, vertical = 7.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                val glyph = when (id) { 0 -> "⌂"; 7 -> "◈"; 3 -> "☆"; else -> "♙" }
                Text(glyph, color = if (id == active) p.accent else p.muted, fontSize = 21.sp,
                    fontWeight = if (id == active) FontWeight.Bold else FontWeight.Normal)
                Text(label, color = if (id == active) p.accent else p.muted, fontSize = 11.sp,
                    fontWeight = if (id == active) FontWeight.Bold else FontWeight.Normal)
            }
        }
    }
}

@Composable
private fun PhoneHomePage(short: List<Series>, comic: List<Series>, ai: List<Series>, type: ContentType,
    recent: List<Series>, updates: List<Series>, later: List<Series>, favoriteIds: Set<String>,
    p: PhonePalette, style: String, loading: Boolean, error: String?, hasMore: Boolean, loadingMore: Boolean,
    onLoadMore: () -> Unit, onType: (ContentType) -> Unit, onSelect: (Series) -> Unit,
    onFavorite: (Series) -> Unit, onPlay: (Series) -> Unit, onRecentSelect: (Series) -> Unit,
    onSearch: () -> Unit, onRank: () -> Unit, onRefresh: () -> Unit, onMore: () -> Unit) {
    val items = when (type) { ContentType.COMIC -> comic; ContentType.AI -> ai; else -> short }
    val homeListState = androidx.compose.foundation.lazy.rememberLazyListState()
    LaunchedEffect(homeListState, hasMore, loadingMore, type, items.size) {
        snapshotFlow { homeListState.layoutInfo.visibleItemsInfo.lastOrNull()?.index to homeListState.layoutInfo.totalItemsCount }
            .collect { (last, total) -> if (last != null && hasMore && !loadingMore && total > 0 && last >= total - 4) onLoadMore() }
    }
    when (style) {
        "immersive" -> Column(Modifier.fillMaxSize().background(Color.Black)) {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("刷新", color = Color.White, modifier = Modifier.clickable(onClick = onRefresh).padding(9.dp))
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.Center) {
                    listOf(ContentType.SHORT to "热度", ContentType.COMIC to "漫剧", ContentType.AI to "AI剧").forEach { (value, label) ->
                        Text(label, color = if (type == value) Color.White else Color(0xFFAAAAAA),
                            fontSize = if (type == value) 16.sp else 14.sp,
                            fontWeight = if (type == value) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier.clickable { onType(value) }.padding(horizontal = 11.dp, vertical = 9.dp))
                    }
                }
                Text("榜单", color = Color.White, fontSize = 12.sp, modifier = Modifier.clickable(onClick = onRank).padding(7.dp))
                Text("⌕", color = Color.White, fontSize = 25.sp, modifier = Modifier.clickable(onClick = onSearch).padding(7.dp))
            }
            if (items.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    if (loading) PhoneLoading(p.copy(dark = true)) else PhoneError(error ?: "这一组暂时没有剧集", p.copy(dark = true))
                }
            } else {
                val pagerState = rememberPagerState(pageCount = { items.size })
                LaunchedEffect(pagerState, items.size, hasMore, loadingMore) {
                    snapshotFlow { pagerState.currentPage }.collect { page ->
                        if (page >= items.size - 3 && hasMore && !loadingMore) onLoadMore()
                    }
                }
                VerticalPager(state = pagerState, modifier = Modifier.weight(1f).fillMaxWidth(),
                    key = { items.getOrNull(it)?.id ?: it }, beyondViewportPageCount = 1) { page ->
                    items.getOrNull(page)?.let { item ->
                        PhoneImmersiveCard(item, p.copy(dark = true), onSelect, Modifier.fillMaxSize(),
                            favorite = item.id in favoriteIds, onFavorite = onFavorite, onPlay = onPlay)
                    }
                }
                if (loadingMore) PhoneLoading(p.copy(dark = true))
                if (error != null) PhoneError(error, p.copy(dark = true))
            }
        }
        "cards" -> {
            val gridState = rememberLazyStaggeredGridState()
            LaunchedEffect(gridState, hasMore, loadingMore, type, items.size) {
                snapshotFlow { (gridState.layoutInfo.visibleItemsInfo.maxOfOrNull { it.index } ?: -1) to gridState.layoutInfo.totalItemsCount }
                    .collect { (last, total) -> if (last >= total - 4 && hasMore && !loadingMore && total > 0) onLoadMore() }
            }
            LazyVerticalStaggeredGrid(columns = StaggeredGridCells.Fixed(2), state = gridState,
                modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalItemSpacing = 8.dp) {
                item(span = StaggeredGridItemSpan.FullLine) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("⌕ 搜索剧名或关键词", color = p.muted, fontSize = 13.sp,
                            modifier = Modifier.weight(1f).clip(RoundedCornerShape(22.dp)).background(p.card)
                                .clickable(onClick = onSearch).padding(horizontal = 14.dp, vertical = 11.dp))
                        Text("榜单", color = p.text, modifier = Modifier.clickable(onClick = onRank).padding(10.dp))
                    }
                }
                item(span = StaggeredGridItemSpan.FullLine) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(ContentType.SHORT to "热度", ContentType.COMIC to "漫剧", ContentType.AI to "推荐").forEach { (value, label) ->
                            PhoneAction(label, if (type == value) p.accent else p.card, if (type == value) Color.White else p.text) { onType(value) }
                        }
                    }
                }
                if (recent.isNotEmpty()) item(span = StaggeredGridItemSpan.FullLine) {
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(13.dp)).background(p.card)
                        .clickable { onRecentSelect(recent.first()) }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("▶ 继续看", color = p.accent, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Text(recent.first().title, color = p.text, fontSize = 12.sp, maxLines = 1,
                            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = 8.dp))
                    }
                }
                if (items.isNotEmpty()) item(span = StaggeredGridItemSpan.FullLine) {
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(15.dp)).background(p.card).padding(12.dp)) {
                        Text(if (type == ContentType.COMIC) "漫剧榜" else if (type == ContentType.AI) "推荐榜" else "热度榜",
                            color = p.text, fontSize = 17.sp, fontWeight = FontWeight.Black)
                        items.take(5).forEachIndexed { index, item ->
                            Row(Modifier.fillMaxWidth().clickable { onSelect(item) }.padding(vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Text((index + 1).toString(), color = if (index < 3) p.accent else p.muted,
                                    fontWeight = FontWeight.Bold, modifier = Modifier.width(22.dp))
                                AsyncImage(model = item.cover, contentDescription = null,
                                    modifier = Modifier.width(38.dp).height(48.dp).clip(RoundedCornerShape(6.dp)), contentScale = ContentScale.Crop)
                                Text(item.title, color = p.text, fontSize = 13.sp, maxLines = 1,
                                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = 9.dp))
                            }
                        }
                    }
                }
                itemsIndexed(items.drop(5), key = { _, item -> item.id }) { index, item ->
                    PhoneWaterfallPoster(item, p, listOf(0.72f, 0.84f, 0.68f, 0.78f, 0.74f)[index % 5], onSelect)
                }
                if (later.isNotEmpty()) item(span = StaggeredGridItemSpan.FullLine) { PhoneShelf("稍后看", later, p, onSelect) }
                if (loading || loadingMore) item(span = StaggeredGridItemSpan.FullLine) { PhoneLoading(p) }
                error?.let { item(span = StaggeredGridItemSpan.FullLine) { PhoneError(it, p) } }
            }
        }
        else -> LazyColumn(state = homeListState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 18.dp)) {
            if (recent.isNotEmpty()) item { PhoneShelf("接着看", recent, p, onRecentSelect) }
            if (updates.isNotEmpty()) item { PhoneShelf("收藏有更新", updates, p, onSelect) }
            if (later.isNotEmpty()) item { PhoneShelf("稍后看", later, p, onSelect) }
            item { PhoneShelf("热门短剧", short, p, onSelect, onMore) }
            item { PhoneShelf("热门漫剧", comic, p, onSelect, onMore) }
            item { PhoneShelf("AI 剧", ai, p, onSelect, onMore) }
            if (loading || loadingMore) item { PhoneLoading(p) }
            error?.let { item { PhoneError(it, p) } }
        }
    }
}
@Composable
private fun PhoneImmersiveCard(
    item: Series, p: PhonePalette, onSelect: (Series) -> Unit, modifier: Modifier = Modifier,
    favorite: Boolean, onFavorite: (Series) -> Unit, onPlay: (Series) -> Unit
) {
    Box(modifier.fillMaxSize()) {
        AsyncImage(model = item.cover, contentDescription = item.title, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0x66000000), Color.Transparent, Color(0xEE000000)))))
        Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(start = 17.dp, end = 12.dp, bottom = 24.dp),
            verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f).padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (item.badge.isNotBlank()) Text(item.badge, color = Color.White, fontSize = 11.sp,
                    modifier = Modifier.clip(RoundedCornerShape(5.dp)).background(p.accent).padding(horizontal = 7.dp, vertical = 4.dp))
                Text(item.title, color = Color.White, fontSize = 22.sp, lineHeight = 29.sp,
                    fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(item.tags,
                    color = Color(0xFFDDDDDD), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (item.description.isNotBlank()) Text(item.description, color = Color(0xFFE5E5E5), fontSize = 12.sp,
                    lineHeight = 18.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("立即播放  ›", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(p.accent)
                        .clickable { onPlay(item) }.padding(vertical = 13.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                Text("上滑看下一部", color = Color.White.copy(alpha = 0.58f), fontSize = 11.sp,
                    modifier = Modifier.align(Alignment.CenterHorizontally))
            }
            Column(verticalArrangement = Arrangement.spacedBy(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                PhoneAction(if (favorite) "★ 已追" else "☆ 追剧", Color(0x77000000), Color.White) { onFavorite(item) }
                PhoneAction("选集", Color(0x77000000), Color.White) { onSelect(item) }
                PhoneAction("详情", Color(0x77000000), Color.White) { onSelect(item) }
            }
        }
    }
}
@Composable
private fun PhoneShelf(title: String, items: List<Series>, p: PhonePalette, onSelect: (Series) -> Unit, onMore: (() -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(top = 16.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = p.text, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (onMore != null) Text("更多 ›", color = p.muted, fontSize = 12.sp, modifier = Modifier.clickable(onClick = onMore))
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(items, key = { it.id }) { item -> PhonePoster(item, p, Modifier.width(122.dp), onSelect) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PhoneTheaterPage(
    items: List<Series>, groups: List<TheaterFilterGroup>, selections: Map<String, String>,
    type: ContentType, loading: Boolean, error: String?, p: PhonePalette, style: String,
    hasMore: Boolean, loadingMore: Boolean, onSearch: () -> Unit, onRank: () -> Unit, onLoadMore: () -> Unit,
    onType: (ContentType) -> Unit, onApplyFilters: (Map<String, String>) -> Unit, onSelect: (Series) -> Unit
) {
    var showFilters by remember { mutableStateOf(false) }
    var draftSelections by remember(selections) { mutableStateOf(selections) }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    LaunchedEffect(listState, hasMore, loadingMore, items.size) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index to listState.layoutInfo.totalItemsCount }
            .collect { (last, total) -> if (last != null && hasMore && !loadingMore && total > 0 && last >= total - 4) onLoadMore() }
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 18.dp, end = 8.dp, top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (style == "immersive") "剧场" else "发现", color = p.text, fontSize = 22.sp,
                fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text("榜单", color = p.muted, fontSize = 12.sp, modifier = Modifier.clickable(onClick = onRank).padding(8.dp))
            Text("⌕", color = p.text, fontSize = 25.sp, modifier = Modifier.clickable(onClick = onSearch).padding(8.dp))
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(ContentType.SHORT to "短剧", ContentType.COMIC to "漫剧", ContentType.AI to "AI剧").forEach { (value, label) ->
                PhoneAction(label, if (type == value) p.accent else p.card, if (type == value) Color.White else p.text) { onType(value) }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            PhoneAction("筛选" + selections.values.count { it.isNotBlank() }.takeIf { it > 0 }?.let { " · $it" }.orEmpty(),
                p.card, p.text) { draftSelections = selections; showFilters = true }
            val quickGroup = groups.firstOrNull { it.type == "sort" } ?: groups.firstOrNull()
            quickGroup?.let { group ->
                group.options.filter { it.id.isNotBlank() }.take(8).forEach { option ->
                    val selected = selections[group.type] == option.id
                    Text(option.label, color = if (selected) Color.White else p.text, fontSize = 11.sp,
                        modifier = Modifier.padding(start = 6.dp).clip(RoundedCornerShape(14.dp))
                            .background(if (selected) p.accent else p.card)
                            .clickable {
                                val next = selections.toMutableMap().apply {
                                    if (selected) remove(group.type) else put(group.type, option.id)
                                }
                                onApplyFilters(next)
                            }.padding(horizontal = 9.dp, vertical = 7.dp))
                }
            }
        }
        if (items.isEmpty() && !loading && error == null) {
            Column(Modifier.fillMaxWidth().weight(1f), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center) {
                Text("没有符合条件的剧集", color = p.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text("试试减少筛选条件，或切换类型。", color = p.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
            }
        } else if (style == "cards") {
            LazyColumn(state = listState, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(9.dp)) {
                items(items, key = { it.id }) { item ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(p.card)
                        .clickable { onSelect(item) }.padding(10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        AsyncImage(model = item.cover, contentDescription = item.title,
                            modifier = Modifier.width(78.dp).height(104.dp).clip(RoundedCornerShape(10.dp)))
                        Column(Modifier.weight(1f).heightIn(min = 104.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text(item.title, color = p.text, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 2,
                                overflow = TextOverflow.Ellipsis)
                            Text(item.description, color = p.muted, fontSize = 12.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.weight(1f))
                            Text(item.tags, color = p.accent, fontSize = 11.sp, maxLines = 1)
                        }
                    }
                }
                if (loading || loadingMore) item { PhoneLoading(p) }
                if (error != null) item { PhoneError(error, p) }
                if (items.isNotEmpty() && !hasMore && !loading) item {
                    Text("已经到底了", color = p.muted, fontSize = 12.sp, modifier = Modifier.fillMaxWidth().padding(14.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
            }
        } else {
            if (style == "immersive" && items.isNotEmpty()) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp).clip(RoundedCornerShape(15.dp))
                    .background(p.card).padding(10.dp)) {
                    Text("热播榜", color = p.text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    items.take(3).forEachIndexed { index, item ->
                        Row(Modifier.fillMaxWidth().clickable { onSelect(item) }.padding(vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text((index + 1).toString(), color = p.accent, fontSize = 18.sp, fontWeight = FontWeight.Black,
                                modifier = Modifier.width(22.dp))
                            AsyncImage(model = item.cover, contentDescription = null,
                                modifier = Modifier.width(42.dp).height(56.dp).clip(RoundedCornerShape(7.dp)))
                            Text(item.title, color = p.text, fontSize = 13.sp, maxLines = 1,
                                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = 9.dp))
                        }
                    }
                }
            }
            val gridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
            LaunchedEffect(gridState, hasMore, loadingMore, items.size) {
                snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index to gridState.layoutInfo.totalItemsCount }
                    .collect { (last, total) -> if (last != null && hasMore && !loadingMore && total > 0 && last >= total - 4) onLoadMore() }
            }
            LazyVerticalGrid(state = gridState, columns = if (style == "immersive") GridCells.Fixed(3) else GridCells.Adaptive(132.dp),
                modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                items(if (style == "immersive") items.drop(3) else items, key = { it.id }) {
                    PhonePoster(it, p, Modifier.fillMaxWidth(), onSelect)
                }
                if (loading || loadingMore) item { PhoneLoading(p) }
                if (error != null) item { PhoneError(error, p) }
            }
        }
    }
    if (showFilters) ModalBottomSheet(onDismissRequest = { showFilters = false; draftSelections = selections },
        containerColor = p.surface, contentColor = p.text) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 18.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("全部筛选", color = p.text, fontSize = 19.sp, fontWeight = FontWeight.Bold)
            groups.forEach { group ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(group.label, color = p.muted, fontSize = 12.sp)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        items(group.options, key = { it.id }) { option ->
                            val selected = draftSelections[group.type] == option.id || (option.id.isBlank() && draftSelections[group.type].isNullOrBlank())
                            Text(option.label, color = if (selected) Color.White else p.text, fontSize = 12.sp,
                                modifier = Modifier.clip(RoundedCornerShape(15.dp))
                                    .background(if (selected) p.accent else p.card)
                                    .clickable {
                                        draftSelections = draftSelections.toMutableMap().apply {
                                            if (option.id.isBlank()) remove(group.type) else put(group.type, option.id)
                                        }
                                    }.padding(horizontal = 11.dp, vertical = 8.dp))
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PhoneAction("重置", p.card, p.text) { draftSelections = emptyMap() }
                PhoneAction("应用", p.accent, Color.White) {
                    onApplyFilters(draftSelections)
                    showFilters = false
                }
            }
        }
    }
}
@Composable
private fun PhoneLibraryPage(
    tab: Int, items: List<Series>, loading: Boolean, error: String?, p: PhonePalette, style: String,
    onTab: (Int) -> Unit, onSelect: (Series) -> Unit, onRemove: (Series) -> Unit,
    progressLabel: (Series) -> String, onRefresh: () -> Unit
) {
    var managing by remember(tab) { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 18.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (style == "immersive") "追剧" else "片单", color = p.text, fontSize = 22.sp,
                fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (items.isNotEmpty()) Text(if (managing) "完成" else "管理", color = p.muted, fontSize = 12.sp,
                modifier = Modifier.clickable { managing = !managing }.padding(8.dp))
            Text("刷新", color = p.accent, fontSize = 12.sp, modifier = Modifier.clickable(onClick = onRefresh).padding(8.dp))
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            listOf(4 to "历史", 3 to "收藏").forEach { (value, label) ->
                Column(Modifier.clickable { onTab(value) }, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(label, color = if (tab == value) p.accent else p.muted, fontSize = 16.sp,
                        fontWeight = if (tab == value) FontWeight.Bold else FontWeight.Medium, modifier = Modifier.padding(vertical = 11.dp))
                    if (tab == value) Box(Modifier.width(28.dp).height(2.dp).clip(CircleShape).background(p.accent))
                }
            }
        }
        if (items.isEmpty() && !loading) {
            Column(Modifier.fillMaxWidth().weight(1f), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center) {
                Text(if (tab == 4) "还没有观看记录" else "还没有收藏的剧集",
                    color = p.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Text(if (tab == 4) "开始播放后，这里会记住集数和进度。" else "遇到喜欢的短剧，点亮收藏就能在这里找到。",
                    color = p.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
                PhoneAction("去发现", p.accent, Color.White) { onTab(7) }
            }
        } else {
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)) {
                items(items.chunked(3).size) { row ->
                    Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items.drop(row * 3).take(3).forEach { item ->
                            Column(Modifier.weight(1f)) {
                                PhonePoster(item, p, Modifier.fillMaxWidth(), onSelect)
                                if (tab == 4) Text(progressLabel(item).ifBlank { "继续观看" }, color = p.muted,
                                    fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
                                Text(if (tab == 3) "取消收藏" else "删除记录", color = p.accent, fontSize = 11.sp,
                                    modifier = Modifier.clickable { onRemove(item) }.padding(vertical = 5.dp))
                            }
                        }
                        repeat((3 - items.drop(row * 3).take(3).size).coerceAtLeast(0)) { Spacer(Modifier.weight(1f)) }
                    }
                }
                if (loading) item { PhoneLoading(p) }
                if (error != null) item { PhoneError(error, p) }
            }
        }
    }
}
@Composable
private fun PhoneRankPage(
    items: List<Series>, board: RankBoard, loading: Boolean, error: String?, p: PhonePalette,
    hasMore: Boolean, loadingMore: Boolean, onBoard: (RankBoard) -> Unit,
    onBack: () -> Unit, onLoadMore: () -> Unit, onSelect: (Series) -> Unit, onRefresh: () -> Unit
) {
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    LaunchedEffect(listState, hasMore, loadingMore, items.size) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index to listState.layoutInfo.totalItemsCount }
            .collect { (last, total) -> if (last != null && hasMore && !loadingMore && total > 0 && last >= total - 4) onLoadMore() }
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            RankBoard.entries.forEach { value ->
                PhoneAction(value.buttonLabel, if (value == board) p.accent else p.card,
                    if (value == board) Color.White else p.text) { onBoard(value) }
            }
        }
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("‹", color = p.text, fontSize = 29.sp, modifier = Modifier.clickable(onClick = onBack).padding(end = 12.dp))
            Text(board.buttonLabel, color = p.text, fontSize = 21.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text("刷新", color = p.accent, fontSize = 12.sp, modifier = Modifier.clickable(onClick = onRefresh).padding(8.dp))
        }
        LazyColumn(state = listState, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (items.isEmpty() && !loading && error == null) item {
                Text("暂无榜单数据", color = p.muted, fontSize = 14.sp, modifier = Modifier.fillMaxWidth().padding(28.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
            items(items.size, key = { items[it].id }) { index ->
                val item = items[index]
                Row(Modifier.fillMaxWidth().heightIn(min = 112.dp).clickable { onSelect(item) },
                    horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text((index + 1).toString(), color = if (index < 3) p.accent else p.muted,
                        fontSize = 19.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(26.dp))
                    AsyncImage(model = item.cover, contentDescription = item.title,
                        modifier = Modifier.width(78.dp).height(106.dp).clip(RoundedCornerShape(11.dp)))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(item.title, color = p.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(item.tags, color = p.accent, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(item.description, color = p.muted, fontSize = 12.sp, lineHeight = 18.sp,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            if (loading || loadingMore) item { PhoneLoading(p) }
            if (error != null) item { PhoneError(error, p) }
            if (items.isNotEmpty() && !hasMore && !loading) item {
                Text("已经到底了", color = p.muted, fontSize = 12.sp, modifier = Modifier.fillMaxWidth().padding(16.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
    }
}
@Composable
private fun PhoneGridPage(title: String, items: List<Series>, loading: Boolean, error: String?, p: PhonePalette,
    hasMore: Boolean, loadingMore: Boolean, onLoadMore: () -> Unit,
    onSelect: (Series) -> Unit, onRefresh: () -> Unit) {
    val gridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
    LaunchedEffect(gridState, hasMore, loadingMore, items.size) {
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index to gridState.layoutInfo.totalItemsCount }
            .collect { (last, total) -> if (last != null && hasMore && !loadingMore && total > 0 && last >= total - 4) onLoadMore() }
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = p.text, fontSize = 21.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text("刷新", color = p.accent, modifier = Modifier.clickable(onClick = onRefresh).padding(8.dp))
        }
        LazyVerticalGrid(state = gridState, columns = GridCells.Adaptive(132.dp), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.weight(1f)) {
            items(items, key = { it.id }) { PhonePoster(it, p, Modifier.fillMaxWidth(), onSelect) }
            if (loading) item { PhoneLoading(p) }
            if (error != null) item { PhoneError(error, p) }
        }
    }
}

@Composable
private fun PhoneSearchPage(text: String, onText: (String) -> Unit, onSearch: () -> Unit,
    suggestions: List<String>, onSuggestion: (String) -> Unit, recent: List<RecentSearch>, hot: List<Series>,
    onClearRecent: () -> Unit, results: List<Series>, loading: Boolean, error: String?, p: PhonePalette,
    hasMore: Boolean, loadingMore: Boolean, onLoadMore: () -> Unit, onSelect: (Series) -> Unit) {
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    LaunchedEffect(listState, hasMore, loadingMore, results.size) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index to listState.layoutInfo.totalItemsCount }
            .collect { (last, total) -> if (last != null && hasMore && !loadingMore && total > 0 && last >= total - 4) onLoadMore() }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(value = text, onValueChange = onText, modifier = Modifier.weight(1f)
                .clip(RoundedCornerShape(16.dp)).background(p.card).padding(horizontal = 14.dp, vertical = 13.dp),
                singleLine = true, textStyle = LocalTextStyle.current.copy(color = p.text, fontSize = 15.sp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                decorationBox = { inner -> if (text.isEmpty()) Text("搜索剧名或关键词", color = p.muted); inner() })
            Spacer(Modifier.width(8.dp))
            PhoneAction("搜索", p.accent, Color.White, onSearch)
        }
        if (suggestions.isNotEmpty() && text.isNotBlank() && results.isEmpty()) {
            Column(Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(RoundedCornerShape(12.dp)).background(p.card)) {
                suggestions.take(8).forEach { word ->
                    Text("⌕  " + word, color = p.text, fontSize = 13.sp,
                        modifier = Modifier.fillMaxWidth().clickable { onSuggestion(word) }.padding(horizontal = 14.dp, vertical = 10.dp))
                }
            }
        }
        if (text.isBlank() && results.isEmpty()) {
            if (recent.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("最近搜索", color = p.text, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Text("清空", color = p.muted, fontSize = 12.sp, modifier = Modifier.clickable(onClick = onClearRecent).padding(8.dp))
                }
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(recent, key = { it.query }) { item ->
                        Text(item.query, color = p.text, fontSize = 12.sp, modifier = Modifier.clip(RoundedCornerShape(18.dp))
                            .background(p.card).clickable { onSuggestion(item.query) }.padding(horizontal = 12.dp, vertical = 8.dp))
                    }
                }
            }
            if (hot.isNotEmpty()) {
                Text("热门推荐", color = p.text, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 18.dp, bottom = 8.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(hot, key = { it.id }) { item ->
                        Column(Modifier.width(104.dp).clickable { onSelect(item) }) {
                            AsyncImage(model = item.cover, contentDescription = item.title,
                                modifier = Modifier.fillMaxWidth().aspectRatio(0.72f).clip(RoundedCornerShape(10.dp)))
                            Text(item.title, color = p.text, fontSize = 12.sp, maxLines = 2,
                                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 5.dp))
                        }
                    }
                }
            }
        }
        if (text.isNotBlank() && results.isNotEmpty()) {
            Text("搜索结果 · " + results.size + " 部", color = p.text, fontSize = 17.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(vertical = 9.dp))
        }
        if (loading) PhoneLoading(p)
        if (error != null) PhoneError(error, p)
        if (text.isNotBlank() && results.isEmpty() && !loading && error == null) {
            Column(Modifier.fillMaxWidth().padding(vertical = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("没有找到相关剧集", color = p.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text("试试缩短关键词，或换个剧名再搜索。", color = p.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
            }
        }
        LazyColumn(state = listState, modifier = Modifier.weight(1f), contentPadding = PaddingValues(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(results, key = { it.id }) { item ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onSelect(item) }.padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    AsyncImage(model = item.cover, contentDescription = item.title,
                        modifier = Modifier.width(82.dp).height(110.dp).clip(RoundedCornerShape(10.dp)))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(item.title, color = p.text, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 2,
                            overflow = TextOverflow.Ellipsis)
                        Text(item.tags, color = p.muted, fontSize = 12.sp)
                        Text(item.description, color = p.muted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text("查看选集 ›", color = p.accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            if (loadingMore) item { PhoneLoading(p) }
            if (results.isNotEmpty() && !hasMore && !loading && !loadingMore)
                item { Text("已经到底了", color = p.muted, fontSize = 12.sp, modifier = Modifier.fillMaxWidth().padding(14.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center) }
        }
    }
}
@Composable
private fun PhoneWaterfallPoster(item: Series, p: PhonePalette, ratio: Float, onSelect: (Series) -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(13.dp)).clickable { onSelect(item) }) {
        AsyncImage(model = item.cover, contentDescription = item.title,
            modifier = Modifier.fillMaxWidth().aspectRatio(ratio).clip(RoundedCornerShape(12.dp)), contentScale = ContentScale.Crop)
        Text(item.title, color = p.text, fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium,
            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 2.dp, vertical = 5.dp))
        if (item.tags.isNotBlank()) Text(item.tags, color = p.muted, fontSize = 10.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 2.dp))
    }
}

@Composable
private fun PhonePoster(item: Series, p: PhonePalette, modifier: Modifier, onSelect: (Series) -> Unit) {
    Column(modifier.clip(RoundedCornerShape(14.dp)).clickable { onSelect(item) }) {
        Box {
            AsyncImage(model = item.cover, contentDescription = item.title,
                modifier = Modifier.fillMaxWidth().aspectRatio(0.69f).clip(RoundedCornerShape(13.dp)), contentScale = ContentScale.Crop)
            if (item.badge.isNotBlank()) Text(item.badge, color = Color.White, fontSize = 10.sp,
                modifier = Modifier.align(Alignment.BottomStart).padding(6.dp).clip(RoundedCornerShape(5.dp))
                    .background(p.accent).padding(horizontal = 6.dp, vertical = 3.dp))
        }
        Text(item.title, color = p.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp, start = 2.dp, end = 2.dp))
        if (item.tags.isNotBlank()) Text(item.tags, color = p.muted, fontSize = 10.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 2.dp, vertical = 3.dp))
    }
}

@Composable
private fun PhoneDetailPage(detail: Detail?, loading: Boolean, error: String?, palette: PhonePalette, style: String,
    resumeIndex: Int?, isFavorite: Boolean, isLater: Boolean, onLater: (Series) -> Unit,
    onResume: () -> Unit, onRelated: (Series) -> Unit,
    onBack: () -> Unit, onPlay: (Int) -> Unit, onFavorite: (Series) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().background(palette.surface).statusBarsPadding().padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("‹", fontSize = 30.sp, color = palette.text, modifier = Modifier.clickable(onClick = onBack).padding(end = 14.dp))
            Text(detail?.series?.title ?: "剧集详情", color = palette.text, fontSize = 18.sp,
                fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (loading) PhoneLoading(palette)
        if (error != null) PhoneError(error, palette)
        detail?.let { data ->
            val detailListState = androidx.compose.foundation.lazy.rememberLazyListState(
                initialFirstVisibleItemIndex = if (resumeIndex != null) 2 + resumeIndex / 5 else 0)
            LazyColumn(state = detailListState, modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(bottom = 28.dp)) {
                item {
                    when (style) {
                        "immersive" -> Box(Modifier.fillMaxWidth().height(390.dp)) {
                            AsyncImage(model = data.series.cover, contentDescription = data.series.title,
                                modifier = Modifier.fillMaxSize())
                            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(
                                Color(0x22000000), Color(0x44000000), Color(0xEE000000)))))
                            Column(Modifier.align(Alignment.BottomStart).padding(horizontal = 20.dp, vertical = 22.dp)) {
                                if (data.series.badge.isNotBlank()) Text(data.series.badge, color = Color.White, fontSize = 11.sp,
                                    modifier = Modifier.clip(RoundedCornerShape(5.dp)).background(palette.accent).padding(horizontal = 7.dp, vertical = 4.dp))
                                Text(data.series.title, color = Color.White, fontSize = 27.sp, fontWeight = FontWeight.Black)
                                Text(data.episodes.size.toString() + " 集 · " + data.series.tags,
                                    color = Color(0xFFDDDDDD), fontSize = 12.sp, modifier = Modifier.padding(top = 7.dp))
                                Text(data.series.description, color = Color(0xFFE5E5E5), fontSize = 13.sp,
                                    maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 9.dp))
                                PhoneDetailActions(resumeIndex, isFavorite, isLater, palette, onResume, { onPlay(0) }, { onFavorite(data.series) }) {
                                onLater(data.series)
                            }
                            }
                        }
                        "cards" -> Column(Modifier.fillMaxWidth().padding(16.dp).clip(RoundedCornerShape(22.dp))
                            .background(palette.surface).padding(16.dp)) {
                            Row(verticalAlignment = Alignment.Top) {
                                AsyncImage(model = data.series.cover, contentDescription = data.series.title,
                                    modifier = Modifier.width(138.dp).aspectRatio(0.69f).clip(RoundedCornerShape(16.dp)))
                                Spacer(Modifier.width(15.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(data.series.title, color = palette.text, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                                    Text(data.episodes.size.toString() + " 集 · " + data.series.tags,
                                        color = palette.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                                    Text(data.series.description, color = palette.muted, fontSize = 13.sp,
                                        maxLines = 7, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp))
                                }
                            }
                            PhoneDetailActions(resumeIndex, isFavorite, isLater, palette, onResume, { onPlay(0) }, { onFavorite(data.series) }) {
                                onLater(data.series)
                            }
                        }
                        else -> Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.Top) {
                            AsyncImage(model = data.series.cover, contentDescription = data.series.title,
                                modifier = Modifier.width(132.dp).aspectRatio(0.69f).clip(RoundedCornerShape(14.dp)))
                            Spacer(Modifier.width(16.dp))
                            Column(Modifier.weight(1f)) {
                                Text(data.series.title, color = palette.text, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                                Text(data.episodes.size.toString() + " 集 · " + data.series.tags, color = palette.muted,
                                    fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                                Text(data.series.description, color = palette.muted, fontSize = 13.sp, maxLines = 5,
                                    overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp))
                                PhoneDetailActions(resumeIndex, isFavorite, isLater, palette, onResume, { onPlay(0) }, { onFavorite(data.series) }) {
                                onLater(data.series)
                            }
                            }
                        }
                    }
                    if (!data.social.isEmpty) {
                        val metrics = listOf(
                            if (data.social.rating > 0) String.format(Locale.CHINA, "%.1f 分", data.social.rating) else "",
                            data.social.favoriteText, data.social.heatText
                        ).filter { it.isNotBlank() }.joinToString(" · ")
                        if (metrics.isNotBlank()) Text(metrics, color = palette.accent, fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp))
                    }
                }
                item {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text("选集", color = palette.text, fontSize = 19.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        Text(data.episodes.size.toString() + " 集", color = palette.muted, fontSize = 12.sp)
                    }
                }
                items(data.episodes.chunked(5).size) { row ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        data.episodes.drop(row * 5).take(5).forEachIndexed { offset, _ ->
                            val index = row * 5 + offset
                            Box(Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                                .background(if (resumeIndex == index) palette.accent.copy(alpha = 0.16f) else palette.card)
                                .clickable { onPlay(index) }.padding(vertical = 13.dp), contentAlignment = Alignment.Center) {
                                Text((index + 1).toString(), color = if (resumeIndex == index) palette.accent else palette.text,
                                    fontSize = 14.sp, fontWeight = if (resumeIndex == index) FontWeight.Bold else FontWeight.Normal)
                            }
                        }
                        repeat((5 - data.episodes.drop(row * 5).take(5).size).coerceAtLeast(0)) { Spacer(Modifier.weight(1f)) }
                    }
                }
                if (data.sameName.isNotEmpty()) item { PhoneShelf("同名作品 / 续作", data.sameName, palette, onRelated) }
                if (data.related.isNotEmpty()) item { PhoneShelf("猜你喜欢", data.related, palette, onRelated) }
            }
            if (data.episodes.isNotEmpty()) {
                val playIndex = (resumeIndex ?: 0).coerceIn(data.episodes.indices)
                Text("▶  播放第 " + (playIndex + 1) + " 集", color = Color.White, fontSize = 16.sp,
                    fontWeight = FontWeight.Bold, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp)
                        .clip(RoundedCornerShape(14.dp)).background(palette.accent)
                        .clickable { onPlay(playIndex) }.padding(vertical = 15.dp))
            }
        }
    }
}

@Composable
private fun PhoneDetailActions(resumeIndex: Int?, isFavorite: Boolean, isLater: Boolean, p: PhonePalette,
    onResume: () -> Unit, onPlay: () -> Unit, onFavorite: () -> Unit, onLater: () -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        if (resumeIndex != null) PhoneAction("继续观看", p.accent, Color.White, onResume)
        PhoneAction("▶ 播放", if (resumeIndex == null) p.accent else p.card,
            if (resumeIndex == null) Color.White else p.text, onPlay)
        PhoneAction(if (isFavorite) "已收藏" else "收藏", p.card, p.text, onFavorite)
        PhoneAction(if (isLater) "已稍后看" else "稍后看", p.card, p.text, onLater)
    }
}

@Composable
private fun PhoneSettingsPage(
    activity: MainActivity, p: PhonePalette, style: String, themeMode: String, navStyle: String,
    onBack: () -> Unit, onTheme: (String) -> Unit, onMode: (String) -> Unit, onNavStyle: (String) -> Unit
) {
    var autoNext by remember { mutableStateOf(activity.library.autoNext) }
    var autoSeason by remember { mutableStateOf(activity.library.autoSeason) }
    var danmaku by remember { mutableStateOf(activity.library.danmaku) }
    var showClock by remember { mutableStateOf(activity.library.showClock) }
    var showNetworkSpeed by remember { mutableStateOf(activity.library.showNetworkSpeed) }
    LaunchedEffect(Unit) {
        while (true) {
            autoNext = activity.library.autoNext
            autoSeason = activity.library.autoSeason
            danmaku = activity.library.danmaku
            showClock = activity.library.showClock
            showNetworkSpeed = activity.library.showNetworkSpeed
            delay(500)
        }
    }
    LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(13.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("‹", color = p.text, fontSize = 30.sp, modifier = Modifier.clickable(onClick = onBack).padding(end = 12.dp))
                Text("设置", color = p.text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            }
        }
        item { Text("外观", color = p.muted, fontSize = 13.sp) }
        item {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(15.dp)).background(p.card).padding(13.dp),
                verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Text("主题样式", color = p.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                listOf("warm" to "暖白精修", "immersive" to "沉浸暗场", "cards" to "内容卡片").forEach { (key, label) ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                        .background(if (style == key) p.accent.copy(alpha = 0.13f) else Color.Transparent)
                        .clickable { onTheme(key) }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(label, color = p.text, modifier = Modifier.weight(1f))
                        if (style == key) Text("✓", color = p.accent, fontWeight = FontWeight.Bold)
                    }
                }
                Text("明暗模式", color = p.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 6.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(listOf("default" to "主题默认", "system" to "跟随系统", "light" to "浅色", "dark" to "深色"), key = { it.first }) { (key, label) ->
                        PhoneAction(label, if (themeMode == key) p.accent else p.surface,
                            if (themeMode == key) Color.White else p.text) { onMode(key) }
                    }
                }
            }
        }
        item {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(15.dp)).background(p.card).padding(13.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("底部导航样式", color = p.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PhoneAction("经典底栏", if (navStyle == "classic") p.accent else p.surface,
                        if (navStyle == "classic") Color.White else p.text) { onNavStyle("classic") }
                    PhoneAction("悬浮玻璃", if (navStyle == "glass") p.accent else p.surface,
                        if (navStyle == "glass") Color.White else p.text) { onNavStyle("glass") }
                }
            }
        }
        item { Text("播放偏好", color = p.muted, fontSize = 13.sp) }
        item {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(15.dp)).background(p.card).padding(horizontal = 14.dp)) {
                PhoneSwitchRow("自动连播下一集", "本集结束后自动播放下一集", autoNext, p) {
                    autoNext = it
                    activity.library.autoNext = it
                }
                PhoneSwitchRow("自动切换下一季", "当前剧集结束后接续下一季", autoSeason, p) {
                    autoSeason = it
                    activity.library.autoSeason = it
                }
                PhoneSwitchRow("弹幕总开关", "控制播放器弹幕显示", danmaku, p) {
                    danmaku = it
                    activity.library.danmaku = it
                }
                PhoneSwitchRow("显示播放时钟", "在播放器画面显示当前时间", showClock, p) {
                    showClock = it
                    activity.library.showClock = it
                }
                PhoneSwitchRow("显示网络速度", "在播放器画面显示实时网速", showNetworkSpeed, p) {
                    showNetworkSpeed = it
                    activity.library.showNetworkSpeed = it
                }
            }
        }
        item {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(15.dp)).background(p.card).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("画质与画面", color = p.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text("最高画质：" + activity.library.maxQuality, color = p.text, fontSize = 13.sp,
                    modifier = Modifier.fillMaxWidth().clickable { activity.showQualityPicker(android.widget.TextView(activity), true) }.padding(vertical = 10.dp))
                Text("画面模式：" + activity.library.frameMode.name, color = p.text, fontSize = 13.sp,
                    modifier = Modifier.fillMaxWidth().clickable { activity.showFramePicker(android.widget.TextView(activity), true) }.padding(vertical = 10.dp))
                Text("播放器信息显示：" + activity.library.videoInfoMode, color = p.text, fontSize = 13.sp,
                    modifier = Modifier.fillMaxWidth().clickable { activity.library.videoInfoMode = (activity.library.videoInfoMode + 1) % 3 }.padding(vertical = 10.dp))
            }
        }
        item { Text("播放引擎、P/N 播放线路、解码器与更新仍由红果TV原有模块管理。", color = p.muted,
            fontSize = 12.sp, lineHeight = 19.sp, modifier = Modifier.padding(bottom = 24.dp)) }
    }
}

@Composable
private fun PhoneSwitchRow(label: String, description: String, checked: Boolean, p: PhonePalette, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 10.dp)) {
            Text(label, color = p.text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(description, color = p.muted, fontSize = 11.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 3.dp))
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun PhoneMinePage(palette: PhonePalette, style: String, themeMode: String,
    accountName: String, accountStatus: String, accountAvatar: String,
    favoriteCount: Int, historyCount: Int,
    onTheme: (String) -> Unit, onMode: (String) -> Unit, onRefresh: () -> Unit,
    onFavorites: () -> Unit, onHistory: () -> Unit, onSettings: () -> Unit, onAccount: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("我的", color = palette.text, fontSize = 27.sp, fontWeight = FontWeight.Bold) }
        item {
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(palette.card)
                .clickable(onClick = onAccount).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(52.dp).clip(CircleShape).background(palette.accent.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
                    if (accountAvatar.isNotBlank()) AsyncImage(model = accountAvatar, contentDescription = "账号头像",
                        modifier = Modifier.fillMaxSize().clip(CircleShape), contentScale = ContentScale.Crop)
                    else Text("果", color = palette.accent, fontSize = 24.sp, fontWeight = FontWeight.Black)
                }
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(accountName, color = palette.text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    Text(accountStatus, color = palette.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 5.dp))
                }
                Text("›", color = palette.muted, fontSize = 24.sp)
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f).clip(RoundedCornerShape(15.dp)).background(palette.card)
                    .clickable(onClick = onFavorites).padding(15.dp)) {
                    Text("本机收藏", color = palette.muted, fontSize = 12.sp)
                    Text(favoriteCount.toString(), color = palette.text, fontSize = 25.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 5.dp))
                }
                Column(Modifier.weight(1f).clip(RoundedCornerShape(15.dp)).background(palette.card)
                    .clickable(onClick = onHistory).padding(15.dp)) {
                    Text("本机看过", color = palette.muted, fontSize = 12.sp)
                    Text(historyCount.toString(), color = palette.text, fontSize = 25.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 5.dp))
                }
            }
        }
        item { Text("界面主题", color = palette.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp)) }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                listOf("warm" to "暖白精修", "immersive" to "沉浸暗场", "cards" to "内容卡片").forEach { (key, label) ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .background(if (style == key) palette.accent.copy(alpha = 0.14f) else palette.card)
                        .clickable { onTheme(key) }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(label, color = palette.text, modifier = Modifier.weight(1f))
                        if (style == key) Text("✓", color = palette.accent, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        item { Text("明暗模式", color = palette.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 7.dp)) }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(listOf("default" to "主题默认", "system" to "跟随系统", "light" to "浅色", "dark" to "深色"), key = { it.first }) { (key, label) ->
                    PhoneAction(label, if (themeMode == key) palette.accent else palette.card,
                        if (themeMode == key) Color.White else palette.text) { onMode(key) }
                }
            }
        }
        item { Text("账号与设置", color = palette.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 7.dp)) }
        item {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(15.dp)).background(palette.card)) {
                Row(Modifier.fillMaxWidth().clickable(onClick = onAccount).padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("账号与隐私", color = palette.text, modifier = Modifier.weight(1f))
                    Text("›", color = palette.muted, fontSize = 20.sp)
                }
                Box(Modifier.fillMaxWidth().padding(start = 14.dp).height(0.5.dp).background(palette.muted.copy(alpha = 0.2f)))
                Row(Modifier.fillMaxWidth().clickable(onClick = onSettings).padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("设置", color = palette.text, modifier = Modifier.weight(1f))
                    Text("›", color = palette.muted, fontSize = 20.sp)
                }
                Box(Modifier.fillMaxWidth().padding(start = 14.dp).height(0.5.dp).background(palette.muted.copy(alpha = 0.2f)))
                Row(Modifier.fillMaxWidth().clickable(onClick = onRefresh).padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("刷新内容与本地片单", color = palette.text, modifier = Modifier.weight(1f))
                    Text("›", color = palette.muted, fontSize = 20.sp)
                }
            }
        }
        item { Text("播放器引擎、播放线路、更新与备份继续使用红果TV原有模块。", color = palette.muted, fontSize = 12.sp,
            modifier = Modifier.padding(top = 8.dp, bottom = 20.dp)) }
    }
}
@Composable
private fun PhoneAction(label: String, bg: Color, fg: Color, onClick: () -> Unit) {
    Text(label, color = fg, fontSize = 12.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.clip(RoundedCornerShape(11.dp)).background(bg).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp))
}

@Composable
private fun PhoneLoading(p: PhonePalette) {
    Row(Modifier.fillMaxWidth().padding(18.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(color = p.accent, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        Text("正在加载…", color = p.muted, fontSize = 12.sp, modifier = Modifier.padding(start = 10.dp))
    }
}

@Composable
private fun PhoneError(message: String, p: PhonePalette) {
    Text(message, color = p.muted, fontSize = 12.sp, modifier = Modifier.padding(18.dp))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PhonePlayerOverlay(activity: MainActivity) {
    val state = activity.phoneUiState
    val playerPrefs = remember { activity.getSharedPreferences("phone-ui-preferences", android.content.Context.MODE_PRIVATE) }
    val playerStyle = remember { playerPrefs.getString(PhoneThemeKey, "warm") ?: "warm" }
    val playerMode = remember { playerPrefs.getString("phone_ui_theme_mode", "default") ?: "default" }
    val playerDark = when (playerMode) {
        "light" -> false
        "dark" -> true
        "system" -> isSystemInDarkTheme()
        else -> playerStyle == "immersive"
    }
    val playerPalette = remember(playerStyle, playerDark) { phonePalette(playerStyle, playerDark) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var playing by remember { mutableStateOf(false) }
    var ready by remember { mutableStateOf(false) }
    var buffering by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var playbackMessage by remember { mutableStateOf("") }
    var showEpisodes by remember { mutableStateOf(false) }
    var showFavoriteOptions by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showSpeed by remember { mutableStateOf(false) }
    var speed by remember { mutableFloatStateOf(activity.library.playbackSpeed) }
    var autoNext by remember { mutableStateOf(activity.library.autoNext) }
    var danmakuEnabled by remember { mutableStateOf(activity.library.danmaku) }
    var fastForwardHeld by remember { mutableStateOf(false) }
    var scrubbing by remember { mutableStateOf(false) }
    var interactionTick by remember { mutableIntStateOf(0) }
    var scrubValue by remember { mutableFloatStateOf(0f) }
    val data = activity.detail
    var favorite by remember(data?.series?.id) { mutableStateOf(data?.series?.let { activity.library.favorite(it.id) } == true) }
    val landscape = activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    val controlsVisible = state.playerControlsVisible

    LaunchedEffect(Unit) {
        while (true) {
            val p = activity.player
            duration = p?.duration?.takeIf { it > 0 } ?: 0L
            position = activity.currentPosition().coerceAtLeast(0L)
            playing = p?.isPlaying == true
            ready = activity.playbackReady
            buffering = p?.playbackState == Player.STATE_BUFFERING
            failed = activity.playError
            if (failed) state.playerControlsVisible = true
            playbackMessage = if (runCatching { activity.playbackText.text.toString() }.isSuccess) activity.playbackText.text.toString() else ""
            speed = activity.library.playbackSpeed
            autoNext = activity.library.autoNext
            danmakuEnabled = activity.library.danmaku
            fastForwardHeld = activity.ffActive
            delay(300)
        }
    }
    LaunchedEffect(controlsVisible, playing, showEpisodes, showFavoriteOptions, showSettings, showSpeed, scrubbing, interactionTick) {
        if (controlsVisible && playing && !showEpisodes && !showFavoriteOptions && !showSettings && !showSpeed) {
            delay(4500)
            state.playerControlsVisible = false
            activity.hidePlaybackOverlay()
        }
    }
    MaterialTheme(colorScheme = if (playerPalette.dark) darkColorScheme(
        primary = playerPalette.accent, background = playerPalette.background, surface = playerPalette.surface,
        onPrimary = Color.White, onBackground = playerPalette.text, onSurface = playerPalette.text
    ) else lightColorScheme(
        primary = playerPalette.accent, background = playerPalette.background, surface = playerPalette.surface,
        onPrimary = Color.White, onBackground = playerPalette.text, onSurface = playerPalette.text
    )) {
    Box(Modifier.fillMaxSize()) {
        if (fastForwardHeld) Text("2× 临时加速中", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 74.dp).clip(RoundedCornerShape(20.dp))
                .background(Color(0x99000000)).padding(horizontal = 14.dp, vertical = 8.dp))
        if ((!ready || buffering) && !failed) CircularProgressIndicator(Modifier.align(Alignment.Center).size(34.dp),
            color = Color.White, strokeWidth = 2.dp)
        if (!playing && ready && !controlsVisible) Text("▶", color = Color.White.copy(alpha = 0.82f), fontSize = 52.sp,
            modifier = Modifier.align(Alignment.Center))
        if (controlsVisible) {
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
                0f to Color(0x99000000), 0.22f to Color.Transparent, 0.68f to Color.Transparent, 1f to Color(0xCC000000)
            )))
            Column(Modifier.fillMaxSize().then(if (landscape) Modifier else Modifier.statusBarsPadding().navigationBarsPadding())
                .padding(horizontal = 22.dp, vertical = 16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PhoneAction("‹", Color(0x55000000), Color.White) { interactionTick++; activity.returnToDetail(true) }
                    Column(Modifier.weight(1f).padding(start = 12.dp).clickable { activity.returnToDetail(true) }) {
                        if (landscape) Text(data?.series?.title.orEmpty(), color = Color.White, fontSize = 16.sp,
                            fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("第 " + (activity.episodeIndex + 1) + " 集 / 共 " + (data?.episodes?.size ?: 0) + " 集",
                            color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        if (!ready || failed || buffering) Text(if (failed) playbackMessage.ifBlank { "播放失败" } else if (buffering) "缓冲中…" else "正在获取播放地址…",
                            color = if (failed) Color(0xFFFFA27B) else Color(0xFFCCCCCC), fontSize = 11.sp)
                        if (failed) PhoneAction("重新播放", Color(0x66C94426), Color.White) {
                            activity.playEpisode(activity.episodeIndex, activity.retryPosition, activity.retryAutoplay)
                        }
                    }
                    if (landscape) PhoneAction(if (favorite) "★" else "☆", Color(0x55000000), Color.White) {
                        interactionTick++
                        showFavoriteOptions = true
                    }
                    Spacer(Modifier.width(6.dp))
                    PhoneAction("选集", Color(0x55000000), Color.White) { interactionTick++; showEpisodes = true }
                }
                Spacer(Modifier.weight(1f))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    PhoneAction("上集", Color(0x55000000), Color.White) {
                        interactionTick++
                        if (activity.episodeIndex > 0) activity.playEpisode(activity.episodeIndex - 1)
                    }
                    Spacer(Modifier.width(22.dp))
                    Box(Modifier.size(62.dp).clip(CircleShape).background(Color(0x77000000))
                        .clickable { interactionTick++; if (failed) activity.playEpisode(activity.episodeIndex, activity.retryPosition, activity.retryAutoplay) else activity.togglePlayback() }, contentAlignment = Alignment.Center) {
                        Text(if (playing) "Ⅱ" else "▶", color = Color.White, fontSize = 27.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.width(22.dp))
                    PhoneAction("下集", Color(0x55000000), Color.White) {
                        interactionTick++
                        if (activity.episodeIndex < (data?.episodes?.lastIndex ?: -1)) activity.playEpisode(activity.episodeIndex + 1)
                    }
                }
                Spacer(Modifier.weight(1f))
                if (!landscape) {
                    Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(data?.series?.title.orEmpty(), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
                                .clickable { interactionTick++; activity.returnToDetail(true) })
                        PhoneAction(if (favorite) "★ 已收藏" else "☆ 收藏", Color(0x55000000), Color.White) {
                            interactionTick++
                            showFavoriteOptions = true
                        }
                    }
                }
                if (duration > 0L) {
                    Slider(value = if (scrubbing) scrubValue else (position.toFloat() / duration).coerceIn(0f, 1f),
                        onValueChange = { scrubValue = it; scrubbing = true; interactionTick++ },
                        onValueChangeFinished = { activity.seekTo((duration * scrubValue).toLong()); scrubbing = false; interactionTick++ },
                        colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = playerPalette.accent, inactiveTrackColor = Color(0x66FFFFFF)),
                        modifier = Modifier.fillMaxWidth().height(28.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(phoneTime(position) + " / " + phoneTime(duration), color = Color.White, fontSize = 11.sp, modifier = Modifier.weight(1f))
                    PhoneAction(String.format(Locale.US, "%.2f", speed) + "×", Color(0x55000000), Color.White) { interactionTick++; showSpeed = true }
                    Spacer(Modifier.width(7.dp))
                    PhoneAction(activity.phonePlaybackOrientationLabel(), Color(0x55000000), Color.White) { interactionTick++; activity.togglePhonePlaybackOrientation() }
                    Spacer(Modifier.width(7.dp))
                    PhoneAction("更多", Color(0x55000000), Color.White) { interactionTick++; showSettings = true }
                }
            }
        }
    }
    if (showSpeed) ModalBottomSheet(onDismissRequest = { showSpeed = false },
        containerColor = playerPalette.surface, contentColor = playerPalette.text) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 22.dp, vertical = 8.dp)) {
            Text("播放倍速", color = playerPalette.text, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 10.dp))
            listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f).forEach { value ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                    .background(if (speed == value) playerPalette.accent.copy(alpha = 0.14f) else Color.Transparent)
                    .clickable {
                        speed = value
                        activity.library.playbackSpeed = value
                        activity.player?.setPlaybackSpeed(value)
                        showSpeed = false
                    }.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(value.toString() + "×", color = if (speed == value) playerPalette.accent else playerPalette.text,
                        fontWeight = if (speed == value) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.weight(1f))
                    if (speed == value) Text("✓", color = playerPalette.accent)
                }
            }
        }
    }
    if (showFavoriteOptions) ModalBottomSheet(onDismissRequest = { showFavoriteOptions = false },
        containerColor = playerPalette.surface, contentColor = playerPalette.text) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("收藏这部剧", color = playerPalette.text, fontSize = 19.sp, fontWeight = FontWeight.Bold)
            Text(data?.series?.title.orEmpty(), color = playerPalette.muted, fontSize = 13.sp, maxLines = 2,
                overflow = TextOverflow.Ellipsis)
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(playerPalette.card).padding(13.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("本机收藏", color = playerPalette.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text("保存在当前设备的追剧片单", color = playerPalette.muted, fontSize = 11.sp,
                        modifier = Modifier.padding(top = 4.dp))
                }
                PhoneAction(if (favorite) "取消收藏" else "加入收藏", playerPalette.accent, Color.White) {
                    data?.series?.let { favorite = activity.library.toggle(it) }
                    showFavoriteOptions = false
                }
            }
        }
    }
    if (showEpisodes) ModalBottomSheet(
        onDismissRequest = { showEpisodes = false }, containerColor = playerPalette.surface, contentColor = playerPalette.text
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 18.dp)) {
            Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("选集", color = playerPalette.text, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text("共 " + (data?.episodes?.size ?: 0) + " 集", color = playerPalette.muted, fontSize = 12.sp)
            }
            val episodeGridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState(
                initialFirstVisibleItemIndex = (activity.episodeIndex / 5) * 5)
            LazyVerticalGrid(state = episodeGridState, columns = GridCells.Fixed(5), modifier = Modifier.heightIn(max = 380.dp),
                contentPadding = PaddingValues(bottom = 18.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(data?.episodes?.size ?: 0) { index ->
                    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                        .background(if (index == activity.episodeIndex) playerPalette.accent else playerPalette.card)
                        .clickable {
                            showEpisodes = false
                            if (index != activity.episodeIndex) activity.playEpisode(index)
                        }.padding(vertical = 13.dp), contentAlignment = Alignment.Center) {
                        Text((index + 1).toString(), color = if (index == activity.episodeIndex) Color.White else playerPalette.text,
                            fontSize = 13.sp, fontWeight = if (index == activity.episodeIndex) FontWeight.Bold else FontWeight.Normal)
                    }
                }
            }
        }
    }
    if (showSettings) ModalBottomSheet(onDismissRequest = { showSettings = false },
        containerColor = playerPalette.surface, contentColor = playerPalette.text) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 8.dp)) {
            Text("播放设置", color = playerPalette.text, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 10.dp))
            PhonePlayerSettingRow("画质", playerPalette) {
                showSettings = false
                activity.showQualityPicker(android.widget.TextView(activity), true)
            }
            PhonePlayerSettingRow("画面模式", playerPalette) {
                showSettings = false
                activity.showFramePicker(android.widget.TextView(activity), true)
            }
            PhonePlayerSettingRow("定时停止", playerPalette) {
                showSettings = false
                activity.showSleepPicker(android.widget.TextView(activity))
            }
            PhonePlayerSettingRow("弹幕设置（总开关：" + if (danmakuEnabled) "开启）" else "关闭）", playerPalette) {
                activity.toggleSeriesDanmaku(data?.series?.id.orEmpty())
            }
            PhonePlayerSettingRow("跳过片头", playerPalette) {
                showSettings = false
                activity.showSkipPicker(android.widget.TextView(activity), true)
            }
            PhonePlayerSettingRow("跳过片尾", playerPalette) {
                showSettings = false
                activity.showSkipPicker(android.widget.TextView(activity), false)
            }
            PhonePlayerSettingRow("自动连播：" + if (autoNext) "开启" else "关闭", playerPalette) {
                autoNext = !autoNext
                activity.library.autoNext = autoNext
            }
            PhonePlayerSettingRow("评论", playerPalette) {
                showSettings = false
                activity.openOfficialComments(activity.phoneUiState.playerHost)
            }
        }
    }
    }
}

@Composable
private fun PhonePlayerSettingRow(label: String, p: PhonePalette, onClick: () -> Unit) {
    Text(label, color = p.text, fontSize = 14.sp,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 13.dp))
}

private fun phoneTime(ms: Long): String {
    if (ms <= 0L) return "00:00"
    val seconds = ms / 1000
    return if (seconds >= 3600) String.format(Locale.US, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
    else String.format(Locale.US, "%02d:%02d", seconds / 60, seconds % 60)
}
