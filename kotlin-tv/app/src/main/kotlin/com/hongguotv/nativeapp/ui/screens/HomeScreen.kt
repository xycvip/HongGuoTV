// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp.ui.screens

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hongguotv.core.BackupProgress
import com.hongguotv.core.Series
import com.hongguotv.nativeapp.ui.components.ErrorState
import com.hongguotv.nativeapp.ui.components.HeroBanner
import com.hongguotv.nativeapp.ui.components.LoadingState
import com.hongguotv.nativeapp.ui.components.PosterCard
import com.hongguotv.nativeapp.ui.components.ResumeCard
import com.hongguotv.nativeapp.ui.components.SectionHeader
import com.hongguotv.nativeapp.ui.components.TvButton
import com.hongguotv.nativeapp.ui.components.ButtonTone
import com.hongguotv.nativeapp.ui.data.HomeState
import com.hongguotv.nativeapp.ui.data.Shelf
import com.hongguotv.nativeapp.ui.data.ShelfStyle
import com.hongguotv.nativeapp.ui.nav.CollectionKind
import com.hongguotv.nativeapp.ui.theme.Paper
import com.hongguotv.nativeapp.ui.theme.Rouge
import com.hongguotv.nativeapp.ui.theme.d
import com.hongguotv.nativeapp.ui.theme.ui
import coil.imageLoader
import coil.request.ImageRequest
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(
    posterColumns: Int = 0,
    state: HomeState,
    favoriteIds: Set<String>,
    progressById: Map<String, BackupProgress>,
    onPlay: (Series) -> Unit,
    onDetail: (Series) -> Unit,
    onFavorite: (Series) -> Unit,
    onCollection: (CollectionKind) -> Unit,
    onRefresh: () -> Unit,
    onHeroIndex: (Int) -> Unit,
    modifier: Modifier = Modifier,
    entryFocus: FocusRequester? = null,
    /** 内容区最上行的「上」目标：本页导航项，避免几何搜索跑到别页/工具位。 */
    upFocus: FocusRequester? = null,
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    // hero 的「下移」必须落到第一张海报上：默认焦点搜索会先撞到轮播指示条，
    // 这里直接把目标钉死，遥控器不会掉进空隙。
    //
    // 导航栏「按下」也落到这里（而不是 hero 的播放按钮）：hero 在 AnimatedContent 里
    // 每 7 秒轮播一次，过渡期间同一个 requester 会短暂挂到两份内容上，焦点容易指到
    // 正在淡出的那一份。第一张海报是稳定节点，落点不会丢。
    val firstShelfFocus = remember(entryFocus) { entryFocus ?: FocusRequester() }
    val hero = state.hero
    // 光标停在主视觉（播放/详情/追剧按钮）时暂停轮播：换图会重建 AnimatedContent 子树，
    // 正在聚焦的按钮被销毁，焦点就会掉到顶栏。
    var heroFocused by remember { mutableStateOf(false) }
    // 主视觉自动轮播：7 秒换一张，用户从详情返回时仍停留在原来的位置。
    LaunchedEffect(hero.map { it.id }, heroFocused) {
        if (hero.size <= 1 || heroFocused) return@LaunchedEffect
        while (true) {
            delay(7000)
            onHeroIndex((hero.indexOfFirst { it.id == hero.getOrNull(state.heroIndex)?.id }.coerceAtLeast(0) + 1) % hero.size)
        }
    }

    Box(modifier.fillMaxSize()) {
        when {
            state.loading && state.shelves.isEmpty() -> LoadingState(label = "正在为你准备片单")
            state.error != null && state.shelves.isEmpty() -> ErrorState(state.error, onRefresh)
            else -> LazyColumn(
                state = listState,
                // 数据刷新时把焦点还给同一分组的邻居，避免焦点被顶栏抢走。
                modifier = Modifier.fillMaxSize().focusGroup().focusRestorer(),
                verticalArrangement = Arrangement.spacedBy(d(34)),
                contentPadding = PaddingValues(bottom = d(56)),
            ) {
                item(key = "hero") {
                    val current = hero.getOrNull(state.heroIndex) ?: hero.firstOrNull()
                    if (current != null) {
                      val context = LocalContext.current
                      // 提前把下一张海报解到内存里：否则切换瞬间新图还没就绪，
                      // 画面会先退回深色占位再亮起来，看起来就是「抖一下」。
                      val density = LocalDensity.current
                      val heroWidthPx = with(density) {
                          LocalConfiguration.current.screenWidthDp.dp.roundToPx()
                      }
                      val heroHeightPx = with(density) { d(452).roundToPx() }
                      LaunchedEffect(current.id, hero.size) {
                          if (hero.size <= 1) return@LaunchedEffect
                          val at = hero.indexOfFirst { it.id == current.id }
                          val next = hero.getOrNull((at + 1) % hero.size) ?: return@LaunchedEffect
                          if (next.cover.isBlank()) return@LaunchedEffect
                          context.imageLoader.enqueue(
                              ImageRequest.Builder(context)
                                  .data(next.cover)
                                  // 尺寸与 hero 实际约束一致，显示时才能命中内存缓存。
                                  .size(heroWidthPx, heroHeightPx)
                                  .build(),
                          )
                      }
                      Box(
                          Modifier
                              .fillMaxWidth()
                              // 高度写死：切换海报时容器尺寸不变，LazyColumn 不会重排，画面就不会上下跳。
                              .height(d(452))
                              .onFocusChanged { focus ->
                                  heroFocused = focus.hasFocus
                                  // 焦点从「接着看」回到主视觉时，列表往往还停在 hero 被顶出屏幕的位置，
                                  // 聚焦元素只保证自身可见，hero 顶部仍在屏外。这里主动把首项拉回顶部。
                                  if (focus.hasFocus && listState.firstVisibleItemIndex > 0) {
                                      scope.launch { listState.animateScrollToItem(0) }
                                  }
                              },
                      ) {
                        // 缩放相位提到切换动画外面：新旧两张海报共用同一个缩放值，
                        // 交叉淡入时才不会一张在放大、另一张在缩小——那看着就是整幅画面在抖。
                        val heroZoom = rememberInfiniteTransition(label = "hero-zoom").animateFloat(
                            initialValue = 1f,
                            targetValue = 1.06f,
                            animationSpec = infiniteRepeatable(tween(18000), RepeatMode.Reverse),
                            label = "hero-zoom",
                        )
                        Crossfade(
                            targetState = current,
                            modifier = Modifier.fillMaxSize(),
                            // 纯淡入淡出，不做水平位移：整块 452dp 海报横移比淡入更像「画面在抖」。
                            animationSpec = tween(480),
                            label = "hero",
                        ) { series ->
                            val progress = progressById[series.id]
                            HeroBanner(
                                series = series,
                                eyebrow = "红果精选 · 正在热播",
                                meta = buildList {
                                    series.tags.split(" ", "/", "·").filter { it.isNotBlank() }.take(3).forEach { add(it) }
                                    if (series.badge.isNotBlank()) add(series.badge)
                                },
                                summary = series.description.ifBlank { "全网热播短剧，打开即看。" },
                                progressPercent = progress?.let {
                                    if (it.duration > 0) (it.position.toFloat() / it.duration).coerceIn(0f, 1f) else null
                                },
                                resumeLabel = progress?.let { "看到第 ${it.episodeIndex + 1} 集" },
                                onPlay = { onPlay(series) },
                                onDetail = { onDetail(series) },
                                onFavorite = { onFavorite(series) },
                                isFavorite = series.id in favoriteIds,
                                height = d(452),
                                imageScale = heroZoom,
                                // hero 自己有一次整幅淡入，图片再叠一层 260ms crossfade 会变成双重淡入，
                                // 切换瞬间先暗一下再亮起来，看着像闪。这里交给 Crossfade 统一处理。
                                imageCrossfadeMillis = 0,
                                downFocus = firstShelfFocus,
                                playFocus = null,
                                upFocus = upFocus,
                            )
                        }
                        // 轮播指示条：当前项用一条会「走完」的细线。
                        // 必须钉在 hero 底部——它原先没有 align，Box 默认左上，整条跑到画面顶端，
                        // 就是用户看到的「顶部若隐若现的红线」。
                        Row(
                            Modifier
                                .align(Alignment.BottomStart)
                                .fillMaxWidth()
                                .padding(horizontal = ui.gutter)
                                .padding(bottom = d(28)),
                            horizontalArrangement = Arrangement.spacedBy(d(8)),
                        ) {
                            hero.forEachIndexed { index, item ->
                                val active = item.id == current.id
                                val barWidth by animateDpAsState(
                                    targetValue = if (active) d(40) else d(14),
                                    animationSpec = androidx.compose.animation.core.tween(320),
                                    label = "hero-bar",
                                )
                                Box(
                                    Modifier
                                        // 轮播指示条只是状态展示，不能参与遥控器焦点——
                                        // 否则从 hero 按钮按下会掉进这条 3dp 细线，看起来就是「下不去」。
                                        .focusProperties { canFocus = false }
                                        .width(barWidth)
                                        .height(d(3))
                                        .clip(RoundedCornerShape(99.dp))
                                        .background(if (active) Rouge.Bright else Color(0x33FFFFFF))
                                        .clickable { onHeroIndex(index) },
                                )
                            }
                        }
                      }
                    }
                }
                itemsIndexed(state.visibleShelves, key = { _, shelf -> shelf.key }) { index, shelf ->
                    ShelfBlock(
                        shelf = shelf,
                        posterColumns = posterColumns,
                        progressById = progressById,
                        onPlay = onPlay,
                        onDetail = onDetail,
                        onCollection = onCollection,
                        firstFocus = if (index == 0) firstShelfFocus else null,
                    )
                }
                item(key = "footer") {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = d(10)),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(
                            Modifier
                                .width(d(40))
                                .height(d(1))
                                .background(Color(0x1FFFFFFF)),
                        )
                        Spacer(Modifier.height(d(14)))
                        Text(
                            "红果 TV · 只为好剧",
                            style = MaterialTheme.typography.labelSmall,
                            color = Paper.Tertiary,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ShelfBlock(
    shelf: Shelf,
    posterColumns: Int,
    progressById: Map<String, BackupProgress>,
    onPlay: (Series) -> Unit,
    onDetail: (Series) -> Unit,
    onCollection: (CollectionKind) -> Unit,
    firstFocus: FocusRequester? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(d(16))) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = ui.gutter),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SectionHeader(
                title = shelf.title,
                subtitle = shelf.subtitle,
                modifier = Modifier.weight(1f),
            )
            if (shelf.collection != null) {
                TvButton(
                    "查看全部",
                    onClick = { onCollection(shelf.collection) },
                    tone = ButtonTone.Quiet,
                )
            }
        }
        if (shelf.style == ShelfStyle.RESUME) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = ui.gutter),
                horizontalArrangement = Arrangement.spacedBy(d(18)),
            ) {
                itemsIndexed(shelf.entries, key = { _, entry -> entry.series.id }) { index, entry ->
                    ResumeCard(
                        series = entry.series,
                        label = entry.label.orEmpty(),
                        progress = entry.progress ?: 0f,
                        onClick = { onDetail(entry.series) },
                        focusRequester = if (index == 0) firstFocus else null,
                    )
                }
            }
        } else {
            LazyRow(
                contentPadding = PaddingValues(horizontal = ui.gutter),
                horizontalArrangement = Arrangement.spacedBy(d(16)),
            ) {
                itemsIndexed(shelf.entries, key = { _, entry -> entry.series.id }) { index, entry ->
                    PosterCard(
                        series = entry.series,
                        onClick = { onDetail(entry.series) },
                        subtitle = entry.label,
                        progress = entry.progress,
                        rank = entry.rank,
                        width = shelfCardWidth(posterColumns),
                        focusRequester = if (index == 0) firstFocus else null,
                    )
                }
            }
        }
    }
}

/** 按「每行几部」换算卡片宽度；0 表示沿用设计稿的 168dp。 */
@Composable
@androidx.compose.runtime.ReadOnlyComposable
private fun shelfCardWidth(columns: Int): androidx.compose.ui.unit.Dp {
    if (columns !in 3..8) return d(168)
    val screen = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp.dp
    val gap = d(16)
    val computed = (screen - ui.gutter * 2 - gap * (columns - 1)) / columns
    return computed.coerceAtMost(d(260))
}
