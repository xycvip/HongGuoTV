// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Locale

private data class PhonePalette(
    val background: Color, val surface: Color, val card: Color, val text: Color,
    val muted: Color, val accent: Color, val dark: Boolean
)

private fun phonePalette(style: String): PhonePalette = when (style) {
    "immersive" -> PhonePalette(Color(0xFF101114), Color(0xFF191B20), Color(0xFF24262C), Color.White, Color(0xFF9A9DA6), Color(0xFFFF7A27), true)
    "cards" -> PhonePalette(Color(0xFFF3F5F8), Color.White, Color.White, Color(0xFF20232A), Color(0xFF777E89), Color(0xFFFF7626), false)
    else -> PhonePalette(Color(0xFFFAF8F4), Color.White, Color(0xFFF2EEE8), Color(0xFF282522), Color(0xFF8C847B), Color(0xFFEB762D), false)
}

private const val PhoneThemeKey = "phone_ui_theme_style"

internal fun MainActivity.showPhoneComposeCatalog() {
    if (!isPhoneDevice) return
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
    if (::hud.isInitialized) hud.visibility = android.view.View.GONE
    val host = phoneUiState.playerHost ?: ComposeView(this).also {
        phoneUiState.playerHost = it
        it.setContent { PhonePlayerOverlay(this@installPhonePlayerComposeOverlay) }
    }
    if (host.parent !== root) root.addView(host, android.widget.FrameLayout.LayoutParams(-1, -1))
    host.bringToFront()
    phoneUiState.playerControlsVisible = true
}

@Composable
private fun PhoneComposeApp(activity: MainActivity) {
    val state = activity.phoneUiState
    var style by remember {
        mutableStateOf(activity.getSharedPreferences("phone-ui-preferences", android.content.Context.MODE_PRIVATE)
            .getString(PhoneThemeKey, "warm") ?: "warm")
    }
    val palette = remember(style) { phonePalette(style) }
    var searchText by remember { mutableStateOf("") }
    var submittedQuery by remember { mutableStateOf("") }
    var shortItems by remember { mutableStateOf(emptyList<Series>()) }
    var comicItems by remember { mutableStateOf(emptyList<Series>()) }
    var aiItems by remember { mutableStateOf(emptyList<Series>()) }
    var pageItems by remember { mutableStateOf(emptyList<Series>()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var detailLoading by remember { mutableStateOf(false) }
    var detail by remember { mutableStateOf<Detail?>(state.detail) }
    var selected by remember { mutableStateOf<Series?>(state.selectedSeries) }
    var refresh by remember { mutableIntStateOf(0) }

    LaunchedEffect(state.tab, state.catalogRevision, submittedQuery, refresh) {
        if (state.page == PhonePage.DETAIL) return@LaunchedEffect
        loading = true
        error = null
        val tabNow = state.tab
        val result = withContext(Dispatchers.IO) {
            runCatching {
                when (tabNow) {
                    0 -> {
                        val short = activity.library.cachedHome(ContentType.SHORT)?.items ?: activity.repository.home(1, ContentType.SHORT).items
                        val comic = activity.library.cachedHome(ContentType.COMIC)?.items ?: activity.repository.home(1, ContentType.COMIC).items
                        val ai = activity.library.cachedHome(ContentType.AI)?.items ?: activity.repository.ranking(RankBoard.AI, 1).items
                        Triple(short, comic, ai)
                    }
                    1 -> Triple(if (submittedQuery.isNotBlank()) activity.repository.searchCombined(submittedQuery).items else emptyList(), emptyList(), emptyList())
                    2 -> Triple(activity.repository.ranking(RankBoard.ALL, 1).items, emptyList(), emptyList())
                    7 -> Triple(activity.repository.theater(1, activity.library.contentType).catalog.items, emptyList(), emptyList())
                    3 -> Triple(activity.library.favorites(), emptyList(), emptyList())
                    4 -> Triple(activity.library.history().map { it.series }.distinctBy { it.id }, emptyList(), emptyList())
                    else -> Triple(emptyList(), emptyList(), emptyList())
                }
            }
        }
        result.onSuccess { resultRows ->
            shortItems = resultRows.first
            comicItems = resultRows.second
            aiItems = resultRows.third
            pageItems = resultRows.first
            if (tabNow == 3) pageItems = activity.library.favorites()
            if (tabNow == 4) pageItems = activity.library.history().map { it.series }.distinctBy { it.id }
            if (tabNow == 0) {
                activity.library.cacheHome(ContentType.SHORT, shortItems, true)
                if (comicItems.isNotEmpty()) activity.library.cacheHome(ContentType.COMIC, comicItems, true)
                if (aiItems.isNotEmpty()) activity.library.cacheHome(ContentType.AI, aiItems, true)
            }
        }.onFailure { error = it.message ?: "加载失败" }
        loading = false
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
                                    activity.library.history().take(8).map { it.series }, palette, loading, error,
                                    onSelect = { openPhoneDetail(activity, it) }, onMore = { activity.switchTab(7) })
                                1 -> PhoneSearchPage(searchText, { searchText = it },
                                    { submittedQuery = searchText.trim(); if (submittedQuery.isNotBlank()) activity.library.rememberSearch(submittedQuery, ContentType.SHORT); refresh++ },
                                    pageItems, loading, error, palette, { openPhoneDetail(activity, it) })
                                2, 7 -> PhoneGridPage(if (state.tab == 2) "排行榜" else "发现", pageItems, loading, error, palette,
                                    { openPhoneDetail(activity, it) }, { refresh++ })
                                3 -> PhoneGridPage("我的收藏", pageItems, loading, error, palette, { openPhoneDetail(activity, it) }, { refresh++ })
                                4 -> PhoneGridPage("最近观看", pageItems, loading, error, palette, { openPhoneDetail(activity, it) }, { refresh++ })
                                else -> PhoneMinePage(palette, style, { next ->
                                    style = next
                                    activity.getSharedPreferences("phone-ui-preferences", android.content.Context.MODE_PRIVATE).edit().putString(PhoneThemeKey, next).apply()
                                }, { refresh++ })
                            }
                        }
                        PhoneBottomBar(state.tab, palette, onSelect = { target -> activity.switchTab(target) })
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
private fun PhoneBottomBar(tab: Int, p: PhonePalette, onSelect: (Int) -> Unit) {
    val active = when (tab) { 7 -> 7; 3, 4 -> 3; 6, 5 -> 6; else -> 0 }
    Row(Modifier.fillMaxWidth().background(p.surface).navigationBarsPadding().padding(horizontal = 12.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.SpaceAround, verticalAlignment = Alignment.CenterVertically) {
        listOf(0 to "首页", 7 to "发现", 3 to "追剧", 6 to "我的").forEach { (id, label) ->
            Column(Modifier.clip(RoundedCornerShape(18.dp)).clickable { onSelect(id) }
                .background(if (active == id) p.accent.copy(alpha = .12f) else Color.Transparent)
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
    p: PhonePalette, loading: Boolean, error: String?, onSelect: (Series) -> Unit, onMore: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 18.dp)) {
        if (recent.isNotEmpty()) item { PhoneShelf("接着看", recent, p, onSelect) }
        item { PhoneShelf("热门短剧", short, p, onSelect, onMore) }
        item { PhoneShelf("热门漫剧", comic, p, onSelect, onMore) }
        item { PhoneShelf("AI 剧", ai, p, onSelect, onMore) }
        if (loading) item { PhoneLoading(p) }
        error?.let { item { PhoneError(it, p) } }
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
private fun PhoneGridPage(title: String, items: List<Series>, loading: Boolean, error: String?, p: PhonePalette,
    onSelect: (Series) -> Unit, onRefresh: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = p.text, fontSize = 21.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text("刷新", color = p.accent, modifier = Modifier.clickable(onClick = onRefresh).padding(8.dp))
        }
        LazyVerticalGrid(columns = GridCells.Adaptive(132.dp), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.fillMaxSize()) {
            items(items, key = { it.id }) { PhonePoster(it, p, Modifier.fillMaxWidth(), onSelect) }
            if (loading) item { PhoneLoading(p) }
            if (error != null) item { PhoneError(error, p) }
        }
    }
}

@Composable
private fun PhoneSearchPage(text: String, onText: (String) -> Unit, onSearch: () -> Unit, results: List<Series>,
    loading: Boolean, error: String?, p: PhonePalette, onSelect: (Series) -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Text("搜索短剧", color = p.text, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 12.dp))
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(p.card).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(text, onText, Modifier.weight(1f).padding(10.dp), singleLine = true,
                textStyle = LocalTextStyle.current.copy(color = p.text, fontSize = 15.sp),
                decorationBox = { inner -> if (text.isEmpty()) Text("输入剧名或关键词", color = p.muted); inner() })
            Text("搜索", color = Color.White, modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(p.accent)
                .clickable(onClick = onSearch).padding(horizontal = 18.dp, vertical = 10.dp))
        }
        LazyVerticalGrid(columns = GridCells.Adaptive(132.dp), modifier = Modifier.fillMaxSize(),
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
                modifier = Modifier.fillMaxWidth().aspectRatio(.69f).clip(RoundedCornerShape(13.dp)))
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
    onBack: () -> Unit, onPlay: (Int) -> Unit, onFavorite: (Series) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().background(palette.surface).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("‹", fontSize = 30.sp, color = palette.text, modifier = Modifier.clickable(onClick = onBack).padding(end = 14.dp))
            Text(detail?.series?.title ?: "剧集详情", color = palette.text, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }
        if (loading) PhoneLoading(palette)
        if (error != null) PhoneError(error, palette)
        detail?.let { data ->
            LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                item {
                    Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.Top) {
                        AsyncImage(model = data.series.cover, contentDescription = null,
                            modifier = Modifier.width(132.dp).aspectRatio(.69f).clip(RoundedCornerShape(14.dp)))
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text(data.series.title, color = palette.text, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                            Text(data.episodes.size.toString() + " 集 · " + data.series.tags, color = palette.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                            Text(data.series.description, color = palette.muted, fontSize = 13.sp, maxLines = 5,
                                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp))
                            Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                PhoneAction("▶ 立即播放", palette.accent, Color.White) { onPlay(0) }
                                PhoneAction("收藏", palette.card, palette.text) { onFavorite(data.series) }
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
                if (data.related.isNotEmpty()) item { PhoneShelf("猜你喜欢", data.related, palette, { }) }
            }
        }
    }
}

@Composable
private fun PhoneMinePage(palette: PhonePalette, style: String, onTheme: (String) -> Unit, onRefresh: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(18.dp)) {
        item { Text("我的", color = palette.text, fontSize = 24.sp, fontWeight = FontWeight.Bold) }
        item { Text("界面主题", color = palette.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 24.dp, bottom = 10.dp)) }
        item {
            listOf("warm" to "暖白精修", "immersive" to "沉浸暗场", "cards" to "内容卡片").forEach { (key, label) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 5.dp).clip(RoundedCornerShape(12.dp))
                    .background(if (style == key) palette.accent.copy(alpha = .12f) else palette.card)
                    .clickable { onTheme(key) }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(label, color = palette.text, modifier = Modifier.weight(1f))
                    if (style == key) Text("✓", color = palette.accent)
                }
            }
        }
        item { Text("数据与设置", color = palette.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 22.dp, bottom = 8.dp)) }
        item { Text("刷新内容与本地片单", color = palette.text, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
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
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var playing by remember { mutableStateOf(false) }
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
            speed = activity.library.playbackSpeed
            delay(300)
        }
    }
    LaunchedEffect(controlsVisible, playing, showEpisodes, showSettings) {
        if (controlsVisible && playing && !showEpisodes && !showSettings) {
            delay(4500)
            state.playerControlsVisible = false
            activity.hidePlaybackOverlay()
        }
    }
    Box(Modifier.fillMaxSize()) {
        if (controlsVisible) {
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
                0f to Color(0x99000000), .22f to Color.Transparent, .68f to Color.Transparent, 1f to Color(0xCC000000)
            )))
            Column(Modifier.fillMaxSize().padding(horizontal = 22.dp, vertical = 16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PhoneAction("‹", Color(0x55000000), Color.White) { activity.returnToDetail(true) }
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(data?.series?.title.orEmpty(), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                        Text("第 " + (activity.episodeIndex + 1) + " 集 / 共 " + (data?.episodes?.size ?: 0) + " 集", color = Color(0xFFCCCCCC), fontSize = 11.sp)
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
                        .combinedClickable(onClick = { activity.togglePlayback() }, onLongClick = {
                            activity.player?.setPlaybackSpeed(2f)
                        }), contentAlignment = Alignment.Center) {
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
                        colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color(0xFFFF792A), inactiveTrackColor = Color(0x66FFFFFF)),
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
        } else {
            Box(Modifier.align(Alignment.BottomEnd).padding(18.dp)) {
                PhoneAction("显示控制", Color(0x88000000), Color.White) {
                    state.playerControlsVisible = true
                    activity.showHud()
                }
            }
        }
    }
    if (showSpeed) AlertDialog(
        onDismissRequest = { showSpeed = false }, title = { Text("播放倍速") },
        text = { Column { listOf(.5f, .75f, 1f, 1.25f, 1.5f, 2f).forEach { value ->
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
            Text("评论", modifier = Modifier.fillMaxWidth().clickable {
                showSettings = false
                activity.openOfficialComments(activity.phoneUiState.playerHost)
            }.padding(12.dp))
            Text("画面模式", modifier = Modifier.fillMaxWidth().clickable {
                showSettings = false
                activity.showFramePicker(android.widget.TextView(activity), true)
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
