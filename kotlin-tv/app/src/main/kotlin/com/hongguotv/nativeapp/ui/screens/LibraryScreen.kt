// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hongguotv.core.BackupProgress
import com.hongguotv.core.Series
import com.hongguotv.nativeapp.ui.components.EmptyState
import com.hongguotv.nativeapp.ui.components.FocusSurface
import com.hongguotv.nativeapp.ui.components.PosterCard
import com.hongguotv.nativeapp.ui.components.SelectChip
import com.hongguotv.nativeapp.ui.components.SmartImage
import com.hongguotv.nativeapp.ui.components.ThinProgress
import com.hongguotv.nativeapp.ui.components.TvButton
import com.hongguotv.nativeapp.ui.components.ButtonTone
import com.hongguotv.nativeapp.ui.data.LibraryState
import com.hongguotv.nativeapp.ui.theme.Ink
import com.hongguotv.nativeapp.ui.theme.Paper
import com.hongguotv.nativeapp.ui.theme.Rouge
import com.hongguotv.nativeapp.ui.theme.d
import com.hongguotv.nativeapp.ui.theme.ui

private enum class LibraryTab(val label: String) {
    FAVORITES("我的追剧"),
    HISTORY("观看历史"),
    LATER("稍后看"),
}

@Composable
fun LibraryScreen(
    state: LibraryState,
    onDetail: (Series) -> Unit,
    onResume: (BackupProgress) -> Unit,
    onRemoveHistory: (String) -> Unit,
    onClearHistory: () -> Unit,
    onToggleFavorite: (Series) -> Unit,
    onToggleLater: (Series) -> Unit,
    onGoTheater: () -> Unit,
    version: Int,
    modifier: Modifier = Modifier,
    entryFocus: FocusRequester? = null,
) {
    var tab by remember(version) { mutableIntStateOf(0) }
    val current = LibraryTab.entries[tab.coerceIn(0, 2)]
    val favorites = state.favorites
    val history = state.history

    Box(modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(if (ui.compact) 5 else 6),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = ui.gutter, end = ui.gutter, top = d(96), bottom = d(60)),
            horizontalArrangement = Arrangement.spacedBy(d(16)),
            verticalArrangement = Arrangement.spacedBy(d(24)),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Column {
                            Text("追剧", style = MaterialTheme.typography.displaySmall, color = Paper.Pure, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(d(6)))
                            Text(
                                "收藏 ${favorites.size} 部 · 看过 ${history.size} 部 · 稍后看 ${state.later.size} 部",
                                style = MaterialTheme.typography.bodySmall,
                                color = Paper.Tertiary,
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        if (current == LibraryTab.HISTORY && history.isNotEmpty()) {
                            TvButton("清空历史", onClearHistory, tone = ButtonTone.Ghost)
                        }
                    }
                    Spacer(Modifier.height(d(18)))
                    Row(horizontalArrangement = Arrangement.spacedBy(d(10))) {
                        LibraryTab.entries.forEachIndexed { index, item ->
                            SelectChip(
                                text = "${item.label}  ${when (index) { 0 -> favorites.size; 1 -> history.size; else -> state.later.size }}",
                                selected = tab == index,
                                onClick = { tab = index },
                                focusRequester = if (index == 0) entryFocus else null,
                            )
                        }
                    }
                    Spacer(Modifier.height(d(6)))
                }
            }

            when (current) {
                LibraryTab.FAVORITES -> {
                    if (favorites.isEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            EmptyState(
                                title = "还没有追剧",
                                subtitle = "在剧集详情页按「加入追剧」，更新会第一时间出现在这里。",
                                modifier = Modifier.height(d(320)),
                                action = { TvButton("去剧场逛逛", onGoTheater, tone = ButtonTone.Ghost) },
                            )
                        }
                    } else {
                        items(favorites, key = { it.id }) { series ->
                            PosterCard(
                                series = series,
                                onClick = { onDetail(series) },
                                onLongClick = { onToggleFavorite(series) },
                                fillWidth = true,
                                subtitle = state.favoriteLabels[series.id],
                            )
                        }
                    }
                }
                LibraryTab.HISTORY -> {
                    if (history.isEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            EmptyState(
                                title = "还没有观看记录",
                                subtitle = "看过的剧会自动出现在这里，随时接着看。",
                                modifier = Modifier.height(d(320)),
                                action = { TvButton("去首页看看", onGoTheater, tone = ButtonTone.Ghost) },
                            )
                        }
                    } else {
                        items(history, key = { it.series.id }, span = { GridItemSpan(maxLineSpan) }) { progress ->
                            HistoryRow(
                                progress = progress,
                                onClick = { onResume(progress) },
                                onRemove = { onRemoveHistory(progress.series.id) },
                            )
                        }
                    }
                }
                LibraryTab.LATER -> {
                    if (state.later.isEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            EmptyState(
                                title = "稍后看是空的",
                                subtitle = "看到感兴趣的剧，加入稍后看，晚上再慢慢挑。",
                                modifier = Modifier.height(d(320)),
                                action = { TvButton("去剧场逛逛", onGoTheater, tone = ButtonTone.Ghost) },
                            )
                        }
                    } else {
                        items(state.later, key = { it.id }) { series ->
                            PosterCard(
                                series = series,
                                onClick = { onDetail(series) },
                                onLongClick = { onToggleLater(series) },
                                fillWidth = true,
                                subtitle = series.tags.take(14).ifBlank { "稍后看" },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryRow(
    progress: BackupProgress,
    onClick: () -> Unit,
    onRemove: () -> Unit,
) {
    val percent = if (progress.duration > 0) (progress.position.toFloat() / progress.duration).coerceIn(0f, 1f) else 0f
    FocusSurface(
        onClick = onClick,
        shape = RoundedCornerShape(d(16)),
        focusScale = 1.012f,
        borderWidth = d(1.2f),
    ) { focused ->
        Row(
            Modifier
                .fillMaxWidth()
                .padding(d(12)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .width(d(196))
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(d(10)))
                    .background(Ink.SurfaceHigh),
            ) {
                SmartImage(progress.series.cover, Modifier.fillMaxSize(), fallbackTitle = progress.series.title)
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color(0xAA000000))),
                )
                ThinProgress(
                    percent,
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
                    height = d(3),
                )
            }
            Spacer(Modifier.width(d(18)))
            Column(Modifier.weight(1f)) {
                Text(
                    progress.series.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (focused) Paper.Pure else Paper.Primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(d(6)))
                Text(
                    "看到第 ${progress.episodeIndex + 1} 集 · ${formatSeconds(progress.position)}" +
                        if (progress.duration > 0) " / ${formatSeconds(progress.duration)}" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = Paper.Tertiary,
                )
                Spacer(Modifier.height(d(10)))
                ThinProgress(percent, Modifier.width(d(280)), height = d(3))
            }
            Spacer(Modifier.width(d(16)))
            TvButton("继续观看", onClick, tone = ButtonTone.Ghost)
            Spacer(Modifier.width(d(10)))
            TvButton("移除", onRemove, tone = ButtonTone.Quiet)
        }
    }
}

fun formatSeconds(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return "%02d:%02d".format(total / 60, total % 60)
}
