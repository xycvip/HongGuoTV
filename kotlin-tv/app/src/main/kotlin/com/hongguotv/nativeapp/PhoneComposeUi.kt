// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
    var pageNumber by remember { mutableIntStateOf(1) }
    var hasMore by remember { mutableStateOf(false) }
    var loadingMore by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var detailLoading by remember { mutableStateOf(false) }
    var detail by remember { mutableStateOf<Detail?>(state.detail) }
    var selected by remember { mutableStateOf<Series?>(state.selectedSeries) }
    var refresh by remember { mutableIntStateOf(0) }

    LaunchedEffect(state.tab) {
        pageNumber = 1
        if (state.tab == 1) { searchText = ""; submittedQuery = "" }
    }

    LaunchedEffect(searchText, state.tab) {
        if (state.tab != 1 || searchText.trim().length < 1) { suggestions = emptyList(); return@LaunchedEffect }
        delay(260)
        suggestions = withContext(Dispatchers.IO) { runCatching { activity.repository.suggest(searchText.trim()) }.getOrDefault(emptyList()) }
    }

    LaunchedEffect(state.tab, state.catalogRevision, submittedQuery, refresh, theaterType, theaterSelections, rankBoard, pageNumber) {
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
                        val short = activity.library.cachedHome(ContentType.SHORT)?.items ?: activity.repository.home(1, ContentType.SHORT).items
                        val comic = activity.library.cachedHome(ContentType.COMIC)?.items ?: activity.repository.home(1, ContentType.COMIC).items
                        val ai = activity.library.cachedHome(ContentType.AI)?.items ?: activity.repository.ranking(RankBoard.AI, 1).items
                        Triple(short, comic, ai)
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
            shortItems = resultRows.first
            comicItems = resultRows.second
            aiItems = resultRows.third
            if (pageNumber > 1 && tabNow in setOf(1, 2, 7)) pageItems = (pageItems + resultRows.first).distinctBy { it.id }
            else pageItems = resultRows.first
            if (tabNow == 3) { pageItems = activity.library.favorites(); hasMore = false }
            if (tabNow == 4) { pageItems = activity.library.history().map { it.series }.distinctBy { it.id }; hasMore = false }
            if (tabNow == 0) {
                activity.library.cacheHome(ContentType.SHORT, shortItems, true)
                if (comicItems.isNotEmpty()) activity.library.cacheHome(ContentType.COMIC, comicItems, true)
                if (aiItems.isNotEmpty()) activity.library.cacheHome(ContentType.AI, aiItems, true)
            }
        }.onFailure { error = it.message ?: "加载失败" }
        loading = false
        loadingMore = false
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
                    PhoneDetailPage(detail, detailLoading, error, palette,
                        resumeIndex = detail?.let { d -> activity.library.progress(d.series.id)?.let { d.episodes.indexOf(it.episodeId) }?.takeIf { it >= 0 } },
                        isFavorite = detail?.let { d -> activity.library.favorites().any { it.id == d.series.id } } == true,
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
                } else {
                    Column(Modifier.fillMaxSize()) {
                        PhoneTopBar(palette, onSearch = { activity.switchTab(1) }, onRefresh = { refresh++ })
                        Box(Modifier.weight(1f)) {
                            when (state.tab) {
                                0 -> PhoneHomePage(shortItems, comicItems, aiItems,
                                    activity.library.history().take(8).map { it.series },
                                    activity.library.favorites().filter { activity.library.favoriteUpdate(it.id)?.hasUpdate == true }.take(8),
                                    activity.library.later().take(10),
                                    palette, style, loading, error,
                                    onSelect = { openPhoneDetail(activity, it) },
                                    onRecentSelect = { activity.openDetail(it, resume = true) },
                                    onMore = { activity.switchTab(7) })
                                1 -> PhoneSearchPage(searchText, { searchText = it },
                                    { pageNumber = 1; pageItems = emptyList(); submittedQuery = searchText.trim(); if (submittedQuery.isNotBlank()) activity.library.rememberSearch(submittedQuery, ContentType.SHORT); refresh++ },
                                    suggestions, { word -> pageNumber = 1; pageItems = emptyList(); searchText = word; submittedQuery = word; activity.library.rememberSearch(word, ContentType.SHORT); refresh++ },
                                    pageItems, loading, error, palette, hasMore, loadingMore, { if (hasMore && !loadingMore) { loadingMore = true; pageNumber++ } },
                                    { openPhoneDetail(activity, it) })
                                2 -> PhoneRankPage(pageItems, rankBoard, loading, error, palette, hasMore, loadingMore,
                                    onBoard = { board -> pageNumber = 1; pageItems = emptyList(); rankBoard = board; refresh++ },
                                    onLoadMore = { if (hasMore && !loadingMore) { loadingMore = true; pageNumber++ } },
                                    onSelect = { openPhoneDetail(activity, it) }, onRefresh = { pageNumber = 1; pageItems = emptyList(); refresh++ })
                                7 -> PhoneTheaterPage(pageItems, theaterGroups, theaterSelections, theaterType, loading, error, palette, hasMore, loadingMore,
                                    onLoadMore = { if (hasMore && !loadingMore) { loadingMore = true; pageNumber++ } },
                                    onType = { pageNumber = 1; pageItems = emptyList(); theaterType = it; theaterSelections = emptyMap(); refresh++ },
                                    onFilter = { key, value -> pageNumber = 1; pageItems = emptyList(); theaterSelections = theaterSelections.toMutableMap().apply { if (value.isBlank()) remove(key) else put(key, value) }; refresh++ },
                                    onSelect = { openPhoneDetail(activity, it) })
                                3, 4 -> PhoneLibraryPage(state.tab, pageItems, loading, error, palette,
                                    onTab = { activity.switchTab(it) },
                                    onSelect = { openPhoneDetail(activity, it) }, onRefresh = { refresh++ })
                                else -> PhoneMinePage(palette, style, themeMode, { next ->
                                    style = next
                                    preferences.edit().putString(PhoneThemeKey, next).apply()
                                }, { next ->
                                    themeMode = next
                                    preferences.edit().putString("phone_ui_theme_mode", next).apply()
                                }, { refresh++ },
                                    onSettings = { state.useLegacyPage = true; activity.switchTab(5) },
                                    onAccount = { state.useLegacyPage = true; activity.switchTab(6) })
                            }
                        }
                        if (state.tab !in setOf(1, 2)) PhoneBottomBar(state.tab, palette, style, onSelect = { target -> activity.switchTab(target) })
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
private fun PhoneTopBar(p: PhonePalette, onSearch: () -> Unit, onRefresh: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(p.surface).padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(32.dp).clip(RoundedCornerShape(10.dp)).background(p.accent), contentAlignment = Alignment.Center) {
            Text("果", color = Color.White, fontWeight = FontWeight.Black, fontSize = 18.sp)
        }
        Spacer(Modifier.width(10.dp))
        Text("红果剧场", fontSize = 21.sp, fontWeight = FontWeight.Bold, color = p.text, modifier = Modifier.weight(1f))
        Text("刷新", color = p.muted, modifier = Modifier.clickable(onClick = onRefresh).padding(10.dp))
        Text("⌕", fontSize = 27.sp, color = p.text, modifier = Modifier.clickable(onClick = onSearch).padding(horizontal = 8.dp))
    }
}

@Composable
private fun PhoneBottomBar(tab: Int, p: PhonePalette, style: String, onSelect: (Int) -> Unit) {
    val active = when (tab) { 7 -> 7; 3, 4 -> 3; 6, 5 -> 6; else -> 0 }
    Row(Modifier.fillMaxWidth().background(p.surface).navigationBarsPadding().padding(horizontal = 12.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.SpaceAround, verticalAlignment = Alignment.CenterVertically) {
        listOf(0 to "首页", 7 to (if (style == "immersive") "剧场" else "发现"), 3 to (if (style == "immersive") "追剧" else "片单"), 6 to "我的").forEach { (id, label) ->
            Column(Modifier.clip(RoundedCornerShape(18.dp)).clickable { onSelect(id) }
                .background(if (active == id) p.accent.copy(alpha = 0.12f) else Color.Transparent)
                .padding(horizontal = 18.dp, vertical = 7.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(if (id == active) "●" else "○", color = p.accent, fontSize = 13.sp)
                Text(label, color = if (id == active) p.accent else p.muted, fontSize = 11.sp,
                    fontWeight = if (id == active) FontWeight.Bold else FontWeight.Normal)
            }
        }
    }
}

@Composable
private fun PhoneHomePage(short: List<Series>, comic: List<Series>, ai: List<Series>, recent: List<Series>,
    updates: List<Series>, later: List<Series>, p: PhonePalette, style: String, loading: Boolean, error: String?,
    onSelect: (Series) -> Unit, onRecentSelect: (Series) -> Unit, onMore: () -> Unit) {
    when (style) {
        "immersive" -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 18.dp)) {
            if (recent.isNotEmpty()) item { PhoneShelf("接着看", recent, p, onRecentSelect) }
            if (updates.isNotEmpty()) item { PhoneShelf("收藏有更新", updates, p, onSelect) }
            if (later.isNotEmpty()) item { PhoneShelf("稍后看", later, p, onSelect) }
            items((short + comic + ai).distinctBy { it.id }, key = { it.id }) { item -> PhoneImmersiveCard(item, p, onSelect) }
            if (loading) item { PhoneLoading(p) }
            error?.let { item { PhoneError(it, p) } }
        }
        "cards" -> Column(Modifier.fillMaxSize()) {
            if (recent.isNotEmpty()) PhoneShelf("接着看", recent, p, onRecentSelect)
            if (updates.isNotEmpty()) PhoneShelf("收藏有更新", updates, p, onSelect)
            Box(Modifier.weight(1f)) {
                LazyVerticalGrid(columns = GridCells.Fixed(2), modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    items((short + comic + ai + later).distinctBy { it.id }, key = { it.id }) { PhonePoster(it, p, Modifier.fillMaxWidth(), onSelect) }
                    if (loading) item { PhoneLoading(p) }
                    if (error != null) item { PhoneError(error, p) }
                }
            }
        }
        else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 18.dp)) {
            if (recent.isNotEmpty()) item { PhoneShelf("接着看", recent, p, onRecentSelect) }
            if (updates.isNotEmpty()) item { PhoneShelf("收藏有更新", updates, p, onSelect) }
            if (later.isNotEmpty()) item { PhoneShelf("稍后看", later, p, onSelect) }
            item { PhoneShelf("热门短剧", short, p, onSelect, onMore) }
            item { PhoneShelf("热门漫剧", comic, p, onSelect, onMore) }
            item { PhoneShelf("AI 剧", ai, p, onSelect, onMore) }
            if (loading) item { PhoneLoading(p) }
            error?.let { item { PhoneError(it, p) } }
        }
    }
}

@Composable
private fun PhoneImmersiveCard(item: Series, p: PhonePalette, onSelect: (Series) -> Unit) {
    Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 7.dp).height(270.dp)
        .clip(RoundedCornerShape(18.dp)).clickable { onSelect(item) }) {
        AsyncImage(model = item.cover, contentDescription = item.title, modifier = Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xDD000000)))))
        Column(Modifier.align(Alignment.BottomStart).padding(16.dp)) {
            if (item.badge.isNotBlank()) Text(item.badge, color = Color.White, fontSize = 11.sp)
            Text(item.title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 2)
            if (item.tags.isNotBlank()) Text(item.tags, color = Color(0xFFDDDDDD), fontSize = 11.sp, maxLines = 1)
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

@Composable
private fun PhoneTheaterPage(
    items: List<Series>, groups: List<TheaterFilterGroup>, selections: Map<String, String>,
    type: ContentType, loading: Boolean, error: String?, p: PhonePalette,
    hasMore: Boolean, loadingMore: Boolean, onLoadMore: () -> Unit,
    onType: (ContentType) -> Unit, onFilter: (String, String) -> Unit, onSelect: (Series) -> Unit
) {
    val gridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
    LaunchedEffect(gridState, hasMore, loadingMore, items.size) {
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index to gridState.layoutInfo.totalItemsCount }
            .collect { (last, total) -> if (last != null && hasMore && !loadingMore && total > 0 && last >= total - 4) onLoadMore() }
    }
    Column(Modifier.fillMaxSize()) {
        Text("发现", color = p.text, fontSize = 22.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 18.dp, top = 10.dp, bottom = 8.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(ContentType.SHORT to "短剧", ContentType.COMIC to "漫剧", ContentType.AI to "AI剧").forEach { (value, label) ->
                PhoneAction(label, if (type == value) p.accent else p.card, if (type == value) Color.White else p.text) { onType(value) }
            }
        }
        groups.forEach { group ->
            Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Text(group.label, color = p.muted, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 16.dp))
                LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(group.options, key = { it.id }) { option ->
                        val selected = selections[group.type] == option.id || (option.id.isBlank() && selections[group.type].isNullOrBlank())
                        Text(option.label, color = if (selected) Color.White else p.text, fontSize = 11.sp,
                            modifier = Modifier.clip(RoundedCornerShape(14.dp))
                                .background(if (selected) p.accent else p.card)
                                .clickable { onFilter(group.type, option.id) }
                                .padding(horizontal = 10.dp, vertical = 7.dp))
                    }
                }
            }
        }
        LazyVerticalGrid(state = gridState, columns = GridCells.Adaptive(132.dp), modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            items(items, key = { it.id }) { PhonePoster(it, p, Modifier.fillMaxWidth(), onSelect) }
            if (loading) item { PhoneLoading(p) }
            if (error != null) item { PhoneError(error, p) }
        }
    }
}

@Composable
private fun PhoneLibraryPage(
    tab: Int, items: List<Series>, loading: Boolean, error: String?, p: PhonePalette,
    onTab: (Int) -> Unit, onSelect: (Series) -> Unit, onRefresh: () -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PhoneAction("收藏", if (tab == 3) p.accent else p.card, if (tab == 3) Color.White else p.text) { onTab(3) }
            PhoneAction("最近观看", if (tab == 4) p.accent else p.card, if (tab == 4) Color.White else p.text) { onTab(4) }
        }
        Box(Modifier.weight(1f)) {
            PhoneGridPage(if (tab == 3) "我的收藏" else "最近观看", items, loading, error, p,
                false, false, {}, onSelect, onRefresh)
        }
    }
}

@Composable
private fun PhoneRankPage(
    items: List<Series>, board: RankBoard, loading: Boolean, error: String?, p: PhonePalette,
    hasMore: Boolean, loadingMore: Boolean, onBoard: (RankBoard) -> Unit,
    onLoadMore: () -> Unit, onSelect: (Series) -> Unit, onRefresh: () -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            RankBoard.entries.forEach { value ->
                PhoneAction(value.buttonLabel, if (value == board) p.accent else p.card,
                    if (value == board) Color.White else p.text) { onBoard(value) }
            }
        }
        Box(Modifier.weight(1f)) {
            PhoneGridPage(board.buttonLabel, items, loading, error, p, hasMore, loadingMore,
                onLoadMore, onSelect, onRefresh)
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
    suggestions: List<String>, onSuggestion: (String) -> Unit, results: List<Series>,
    loading: Boolean, error: String?, p: PhonePalette, hasMore: Boolean, loadingMore: Boolean,
    onLoadMore: () -> Unit, onSelect: (Series) -> Unit) {
    val gridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
    LaunchedEffect(gridState, hasMore, loadingMore, results.size) {
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index to gridState.layoutInfo.totalItemsCount }
            .collect { (last, total) -> if (last != null && hasMore && !loadingMore && total > 0 && last >= total - 4) onLoadMore() }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Text("搜索短剧", color = p.text, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 12.dp))
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(p.card).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(text, onText, Modifier.weight(1f).padding(10.dp), singleLine = true,
                textStyle = LocalTextStyle.current.copy(color = p.text, fontSize = 15.sp),
                decorationBox = { inner -> if (text.isEmpty()) Text("输入剧名或关键词", color = p.muted); inner() })
            Text("搜索", color = Color.White, modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(p.accent)
                .clickable(onClick = onSearch).padding(horizontal = 18.dp, vertical = 10.dp))
        }
        if (suggestions.isNotEmpty() && text.isNotBlank() && results.isEmpty()) {
            LazyRow(contentPadding = PaddingValues(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(suggestions, key = { it }) { word ->
                    Text(word, color = p.text, fontSize = 12.sp, modifier = Modifier.clip(RoundedCornerShape(14.dp))
                        .background(p.card).clickable { onSuggestion(word) }.padding(horizontal = 12.dp, vertical = 8.dp))
                }
            }
        }
        LazyVerticalGrid(state = gridState, columns = GridCells.Adaptive(132.dp), modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            items(results, key = { it.id }) { PhonePoster(it, p, Modifier.fillMaxWidth(), onSelect) }
            if (loading) item { PhoneLoading(p) }
            if (error != null) item { PhoneError(error, p) }
        }
    }
}

@Composable
private fun PhonePoster(item: Series, p: PhonePalette, modifier: Modifier, onSelect: (Series) -> Unit) {
    Column(modifier.clip(RoundedCornerShape(14.dp)).clickable { onSelect(item) }) {
        Box {
            AsyncImage(model = item.cover, contentDescription = item.title,
                modifier = Modifier.fillMaxWidth().aspectRatio(0.69f).clip(RoundedCornerShape(13.dp)))
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
private fun PhoneDetailPage(detail: Detail?, loading: Boolean, error: String?, palette: PhonePalette,
    resumeIndex: Int?, isFavorite: Boolean, onResume: () -> Unit, onRelated: (Series) -> Unit,
    onBack: () -> Unit, onPlay: (Int) -> Unit, onFavorite: (Series) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().background(palette.surface).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("‹", fontSize = 30.sp, color = palette.text, modifier = Modifier.clickable(onClick = onBack).padding(end = 14.dp))
            Text(detail?.series?.title ?: "剧集详情", color = palette.text, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }
        if (loading) PhoneLoading(palette)
        if (error != null) PhoneError(error, palette)
        detail?.let { data ->
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(bottom = 24.dp)) {
                item {
                    Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.Top) {
                        AsyncImage(model = data.series.cover, contentDescription = null,
                            modifier = Modifier.width(132.dp).aspectRatio(0.69f).clip(RoundedCornerShape(14.dp)))
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text(data.series.title, color = palette.text, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                            Text(data.episodes.size.toString() + " 集 · " + data.series.tags, color = palette.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                            Text(data.series.description, color = palette.muted, fontSize = 13.sp, maxLines = 5,
                                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp))
                            if (!data.social.isEmpty) {
                                val metrics = listOf(
                                    if (data.social.rating > 0) String.format(Locale.CHINA, "%.1f 分", data.social.rating) else "",
                                    data.social.favoriteText, data.social.heatText
                                ).filter { it.isNotBlank() }.joinToString(" · ")
                                Text(metrics, color = palette.accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(top = 8.dp))
                            }
                            Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (resumeIndex != null) PhoneAction("继续观看", palette.accent, Color.White, onResume)
                                PhoneAction("▶ 立即播放", if (resumeIndex != null) palette.card else palette.accent,
                                    if (resumeIndex != null) palette.text else Color.White) { onPlay(0) }
                                PhoneAction(if (isFavorite) "已收藏" else "收藏", palette.card, palette.text) { onFavorite(data.series) }
                            }
                        }
                    }
                }
                item { Text("选集", color = palette.text, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp)) }
                items(data.episodes.chunked(4).size) { row ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        data.episodes.drop(row * 4).take(4).forEachIndexed { offset, _ ->
                            val index = row * 4 + offset
                            Box(Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(palette.card)
                                .clickable { onPlay(index) }.padding(vertical = 13.dp), contentAlignment = Alignment.Center) {
                                Text((index + 1).toString(), color = palette.text, fontSize = 14.sp)
                            }
                        }
                        repeat((4 - data.episodes.drop(row * 4).take(4).size).coerceAtLeast(0)) { Spacer(Modifier.weight(1f)) }
                    }
                }
                if (data.sameName.isNotEmpty()) item { PhoneShelf("同名作品 / 续作", data.sameName, palette, onRelated) }
                if (data.related.isNotEmpty()) item { PhoneShelf("猜你喜欢", data.related, palette, onRelated) }
            }
        }
    }
}

@Composable
private fun PhoneMinePage(palette: PhonePalette, style: String, themeMode: String,
    onTheme: (String) -> Unit, onMode: (String) -> Unit, onRefresh: () -> Unit,
    onSettings: () -> Unit, onAccount: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(18.dp)) {
        item { Text("我的", color = palette.text, fontSize = 24.sp, fontWeight = FontWeight.Bold) }
        item { Text("界面主题", color = palette.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 24.dp, bottom = 10.dp)) }
        item {
            listOf("warm" to "暖白精修", "immersive" to "沉浸暗场", "cards" to "内容卡片").forEach { (key, label) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 5.dp).clip(RoundedCornerShape(12.dp))
                    .background(if (style == key) palette.accent.copy(alpha = 0.12f) else palette.card)
                    .clickable { onTheme(key) }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(label, color = palette.text, modifier = Modifier.weight(1f))
                    if (style == key) Text("✓", color = palette.accent)
                }
            }
        }
        item { Text("账号与设置", color = palette.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 22.dp, bottom = 8.dp)) }
        item { Text("账号中心  ›", color = palette.text, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(palette.card).clickable(onClick = onAccount).padding(16.dp)) }
        item { Text("系统设置  ›", color = palette.text, modifier = Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(12.dp))
            .background(palette.card).clickable(onClick = onSettings).padding(16.dp)) }
        item { Text("刷新内容与本地片单", color = palette.text, modifier = Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(12.dp))
            .background(palette.card).clickable(onClick = onRefresh).padding(16.dp)) }
        item { Text("播放器引擎、播放线路、更新与备份继续使用红果TV原有模块。", color = palette.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 14.dp)) }
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
    var showSettings by remember { mutableStateOf(false) }
    var showSpeed by remember { mutableStateOf(false) }
    var speed by remember { mutableFloatStateOf(activity.library.playbackSpeed) }
    var scrubbing by remember { mutableStateOf(false) }
    var scrubValue by remember { mutableFloatStateOf(0f) }
    val data = activity.detail
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
            delay(300)
        }
    }
    LaunchedEffect(controlsVisible, playing, showEpisodes, showSettings, showSpeed) {
        if (controlsVisible && playing && !showEpisodes && !showSettings && !showSpeed) {
            delay(4500)
            state.playerControlsVisible = false
            activity.hidePlaybackOverlay()
        }
    }
    Box(Modifier.fillMaxSize()) {
        if (controlsVisible) {
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
                0f to Color(0x99000000), 0.22f to Color.Transparent, 0.68f to Color.Transparent, 1f to Color(0xCC000000)
            )))
            Column(Modifier.fillMaxSize().padding(horizontal = 22.dp, vertical = 16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PhoneAction("‹", Color(0x55000000), Color.White) { activity.returnToDetail(true) }
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(data?.series?.title.orEmpty(), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                        Text("第 " + (activity.episodeIndex + 1) + " 集 / 共 " + (data?.episodes?.size ?: 0) + " 集", color = Color(0xFFCCCCCC), fontSize = 11.sp)
                        if (!ready || failed || buffering) Text(if (failed) playbackMessage.ifBlank { "播放失败" } else if (buffering) "缓冲中…" else "正在获取播放地址…",
                            color = if (failed) Color(0xFFFFA27B) else Color(0xFFCCCCCC), fontSize = 11.sp)
                    }
                    PhoneAction("选集", Color(0x55000000), Color.White) { showEpisodes = true }
                }
                Spacer(Modifier.weight(1f))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    PhoneAction("上集", Color(0x55000000), Color.White) {
                        if (activity.episodeIndex > 0) activity.playEpisode(activity.episodeIndex - 1)
                    }
                    Spacer(Modifier.width(22.dp))
                    Box(Modifier.size(62.dp).clip(CircleShape).background(Color(0x77000000))
                        .clickable { if (failed) activity.playEpisode(activity.episodeIndex, activity.retryPosition, activity.retryAutoplay) else activity.togglePlayback() }, contentAlignment = Alignment.Center) {
                        Text(if (playing) "Ⅱ" else "▶", color = Color.White, fontSize = 27.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.width(22.dp))
                    PhoneAction("下集", Color(0x55000000), Color.White) {
                        if (activity.episodeIndex < (data?.episodes?.lastIndex ?: -1)) activity.playEpisode(activity.episodeIndex + 1)
                    }
                }
                Spacer(Modifier.weight(1f))
                if (duration > 0L) {
                    Slider(value = if (scrubbing) scrubValue else (position.toFloat() / duration).coerceIn(0f, 1f),
                        onValueChange = { scrubValue = it; scrubbing = true },
                        onValueChangeFinished = { activity.seekTo((duration * scrubValue).toLong()); scrubbing = false },
                        colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = playerPalette.accent, inactiveTrackColor = Color(0x66FFFFFF)),
                        modifier = Modifier.fillMaxWidth().height(28.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(phoneTime(position) + " / " + phoneTime(duration), color = Color.White, fontSize = 11.sp, modifier = Modifier.weight(1f))
                    PhoneAction(String.format(Locale.US, "%.2f", speed) + "×", Color(0x55000000), Color.White) { showSpeed = true }
                    Spacer(Modifier.width(7.dp))
                    PhoneAction(activity.phonePlaybackOrientationLabel(), Color(0x55000000), Color.White) { activity.togglePhonePlaybackOrientation() }
                    Spacer(Modifier.width(7.dp))
                    PhoneAction("更多", Color(0x55000000), Color.White) { showSettings = true }
                }
            }
        }
    }
    if (showSpeed) AlertDialog(
        onDismissRequest = { showSpeed = false }, title = { Text("播放倍速") },
        text = { Column { listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f).forEach { value ->
            Text(value.toString() + "×", modifier = Modifier.fillMaxWidth().clickable {
                speed = value; activity.library.playbackSpeed = value; activity.player?.setPlaybackSpeed(value); showSpeed = false
            }.padding(12.dp))
        } } },
        confirmButton = { TextButton(onClick = { showSpeed = false }) { Text("关闭") } }
    )
    if (showEpisodes) AlertDialog(
        onDismissRequest = { showEpisodes = false }, title = { Text("选集") },
        text = { LazyColumn(Modifier.heightIn(max = 420.dp)) {
            items(data?.episodes?.size ?: 0) { index ->
                Text("第 " + (index + 1) + " 集" + if (index == activity.episodeIndex) " · 正在播放" else "",
                    modifier = Modifier.fillMaxWidth().clickable {
                        showEpisodes = false
                        if (index != activity.episodeIndex) activity.playEpisode(index)
                    }.padding(12.dp))
            }
        } },
        confirmButton = { TextButton(onClick = { showEpisodes = false }) { Text("完成") } }
    )
    if (showSettings) AlertDialog(
        onDismissRequest = { showSettings = false }, title = { Text("播放设置") },
        text = { Column {
            Text("画质", modifier = Modifier.fillMaxWidth().clickable {
                showSettings = false
                activity.showQualityPicker(android.widget.TextView(activity), true)
            }.padding(12.dp))
            Text("画面模式", modifier = Modifier.fillMaxWidth().clickable {
                showSettings = false
                activity.showFramePicker(android.widget.TextView(activity), true)
            }.padding(12.dp))
            Text("定时停止", modifier = Modifier.fillMaxWidth().clickable {
                showSettings = false
                activity.showSleepPicker(android.widget.TextView(activity))
            }.padding(12.dp))
            Text("弹幕：点击切换", modifier = Modifier.fillMaxWidth().clickable {
                activity.toggleSeriesDanmaku(data?.series?.id.orEmpty())
            }.padding(12.dp))
            Text("跳过片头", modifier = Modifier.fillMaxWidth().clickable {
                showSettings = false
                activity.showSkipPicker(android.widget.TextView(activity), true)
            }.padding(12.dp))
            Text("跳过片尾", modifier = Modifier.fillMaxWidth().clickable {
                showSettings = false
                activity.showSkipPicker(android.widget.TextView(activity), false)
            }.padding(12.dp))
            Text("自动连播：" + if (activity.library.autoNext) "开启" else "关闭",
                modifier = Modifier.fillMaxWidth().clickable {
                    activity.library.autoNext = !activity.library.autoNext
                }.padding(12.dp))
            Text("评论", modifier = Modifier.fillMaxWidth().clickable {
                showSettings = false
                activity.openOfficialComments(activity.phoneUiState.playerHost)
            }.padding(12.dp))
        } },
        confirmButton = { TextButton(onClick = { showSettings = false }) { Text("关闭") } }
    )
}

private fun phoneTime(ms: Long): String {
    if (ms <= 0L) return "00:00"
    val seconds = ms / 1000
    return if (seconds >= 3600) String.format(Locale.US, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
    else String.format(Locale.US, "%02d:%02d", seconds / 60, seconds % 60)
}
