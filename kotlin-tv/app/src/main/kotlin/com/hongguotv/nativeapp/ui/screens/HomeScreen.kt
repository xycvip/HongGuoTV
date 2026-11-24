// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
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
import kotlinx.coroutines.delay

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
) {
    val listState = rememberLazyListState()
    // hero 的「下移」必须落到第一张海报上：默认焦点搜索会先撞到轮播指示条，
    // 这里直接把目标钉死，遥控器不会掉进空隙。
    val firstShelfFocus = remember { FocusRequester() }
    val hero = state.hero
    // 主视觉自动轮播：7 秒换一张，用户从详情返回时仍停留在原来的位置。
    LaunchedEffect(hero.map { it.id }) {
        if (hero.size <= 1) return@LaunchedEffect
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
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(d(34)),
                contentPadding = PaddingValues(bottom = d(56)),
            ) {
                item(key = "hero") {
                    val current = hero.getOrNull(state.heroIndex) ?: hero.firstOrNull()
                    if (current != null) {
                        AnimatedContent(
                            targetState = current,
                            transitionSpec = {
                                (fadeIn(androidx.compose.animation.core.tween(650)) +
                                    slideInHorizontally { it / 14 })
                                    .togetherWith(fadeOut(androidx.compose.animation.core.tween(450)))
                            },
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
                                downFocus = firstShelfFocus,
                                playFocus = entryFocus,
                            )
                        }
                        // 轮播指示条：当前项用一条会「走完」的细线
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = ui.gutter),
                            horizontalArrangement = Arrangement.spacedBy(d(8)),
                        ) {
                            hero.forEachIndexed { index, item ->
                                val active = item.id == current.id
                                Box(
                                    Modifier
                                        // 轮播指示条只是状态展示，不能参与遥控器焦点——
                                        // 否则从 hero 按钮按下会掉进这条 3dp 细线，看起来就是「下不去」。
                                        .focusProperties { canFocus = false }
                                        .width(if (active) d(38) else d(14))
                                        .height(d(3))
                                        .clip(RoundedCornerShape(99.dp))
                                        .background(if (active) Rouge.Bright else Color(0x33FFFFFF))
                                        .clickable { onHeroIndex(index) },
                                )
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
