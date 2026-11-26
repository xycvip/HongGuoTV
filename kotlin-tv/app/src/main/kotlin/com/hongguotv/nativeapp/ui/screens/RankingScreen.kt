// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import com.hongguotv.nativeapp.ui.components.tryRequestFocus
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hongguotv.core.RankBoard
import com.hongguotv.core.Series
import com.hongguotv.nativeapp.ui.components.ErrorState
import com.hongguotv.nativeapp.ui.components.FocusSurface
import com.hongguotv.nativeapp.ui.components.LoadingState
import com.hongguotv.nativeapp.ui.components.SelectChip
import com.hongguotv.nativeapp.ui.components.SmartImage
import com.hongguotv.nativeapp.ui.data.RankingState
import com.hongguotv.nativeapp.ui.theme.Gold
import com.hongguotv.nativeapp.ui.theme.Ink
import com.hongguotv.nativeapp.ui.theme.Paper
import com.hongguotv.nativeapp.ui.theme.Rouge
import com.hongguotv.nativeapp.ui.theme.d
import com.hongguotv.nativeapp.ui.theme.ui
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged

@Composable
fun RankingScreen(
    state: RankingState,
    onBoard: (RankBoard) -> Unit,
    onDetail: (Series) -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    entryFocus: FocusRequester? = null,
    upFocus: FocusRequester? = null,
) {
    val listState = rememberLazyListState()
    // 无限滚动：订阅布局信息而不是用 derivedStateOf 当 key——
    // 之前列表长度不变时状态变化不会重启副作用，滚到底也不会再补页。
    LaunchedEffect(listState, state.hasMore, state.items.size, state.page, state.board) {
        if (!state.hasMore) return@LaunchedEffect
        snapshotFlow {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: return@snapshotFlow false
            state.items.isNotEmpty() && last >= info.totalItemsCount - 5
        }.collect { nearEnd -> if (nearEnd) onLoadMore() }
    }

    // 换榜后整列 id 全换，焦点节点会被销毁；把落点钉在榜签上，遥控器不会掉回导航栏。
    val boardFoci = remember(entryFocus) {
        RankBoard.entries.associateWith { board ->
            if (board == RankBoard.entries.first() && entryFocus != null) entryFocus else FocusRequester()
        }
    }
    LaunchedEffect(state.board) {
        repeat(6) {
            val target = boardFoci[state.board]
            if (target != null && target.tryRequestFocus()) return@LaunchedEffect
            withFrameNanos { }
        }
    }

    // 换榜后回到顶部：旧榜的滚动位置留在这里会让人以为「榜单没切换」。
    LaunchedEffect(state.board) { listState.scrollToItem(0) }

    Box(modifier.fillMaxSize()) {
        when {
            state.loading && state.items.isEmpty() -> LoadingState(label = "正在拉取榜单")
            state.error != null && state.items.isEmpty() -> ErrorState(
                state.error,
                onRetry,
                focusRequester = entryFocus,
            )
            else -> LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().focusGroup().focusRestorer(),
                contentPadding = PaddingValues(start = ui.gutter, end = ui.gutter, top = d(96), bottom = d(60)),
                verticalArrangement = Arrangement.spacedBy(d(22)),
            ) {
                item {
                    Column {
                        Row(verticalAlignment = Alignment.Bottom) {
                            Column {
                                Text("排行榜", style = MaterialTheme.typography.displaySmall, color = Paper.Pure, fontWeight = FontWeight.Bold)
                                Spacer(Modifier.height(d(6)))
                                Text(
                                    state.updatedText.ifBlank { "红果官方热播榜 · 实时更新" },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Paper.Tertiary,
                                )
                            }
                        }
                        Spacer(Modifier.height(d(18)))
                        Row(horizontalArrangement = Arrangement.spacedBy(d(10))) {
                            RankBoard.entries.forEach { board ->
                                SelectChip(
                                    text = board.buttonLabel,
                                    selected = state.board == board,
                                    onClick = { onBoard(board) },
                                    accent = if (board == RankBoard.TOTAL) Rouge.Core else Gold.Core,
                                    focusRequester = boardFoci[board],
                                    upFocus = upFocus,
                                )
                            }
                        }
                    }
                }
                if (state.items.size >= 1) {
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(d(18))) {
                            val top = state.items.take(3)
                            top.forEachIndexed { index, series ->
                                PodiumCard(
                                    series = series,
                                    rank = index + 1,
                                    heat = state.heat[series.id],
                                    onClick = { onDetail(series) },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            if (top.size < 3) {
                                repeat(3 - top.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                    }
                }
                item {
                    Row(
                        Modifier.fillMaxWidth().padding(top = d(4)),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier
                                .width(d(3))
                                .height(d(16))
                                .clip(RoundedCornerShape(2.dp))
                                .background(Rouge.Core),
                        )
                        Spacer(Modifier.width(d(9)))
                        Text("榜单 4 - 20", style = MaterialTheme.typography.titleMedium, color = Paper.Primary)
                        Spacer(Modifier.weight(1f))
                        Text("左右键浏览 · 上下键滚动", style = MaterialTheme.typography.labelSmall, color = Paper.Tertiary)
                    }
                }
                val rest = state.items.drop(3)
                items(rest.chunked(2)) { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(d(16))) {
                        row.forEachIndexed { index, series ->
                            val rank = state.items.indexOfFirst { it.id == series.id } + 1
                            RankRow(
                                series = series,
                                rank = rank,
                                heat = state.heat[series.id],
                                onClick = { onDetail(series) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
                if (state.loadingMore || !state.hasMore) {
                    item {
                        Box(Modifier.fillMaxWidth().padding(vertical = d(6)), contentAlignment = Alignment.Center) {
                            Text(
                                if (state.loadingMore) "正在加载更多…" else "已经到底了",
                                style = MaterialTheme.typography.bodySmall,
                                color = Paper.Tertiary,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PodiumCard(
    series: Series,
    rank: Int,
    heat: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = when (rank) {
        1 -> Gold.Bright
        2 -> Color(0xFFDCE2ED)
        else -> Color(0xFFE4B08A)
    }
    FocusSurface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(d(20)),
        focusScale = 1.03f,
        glow = if (rank == 1) Gold.Core else Rouge.Core,
        borderWidth = d(1.4f),
    ) { focused ->
        Column {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f),
            ) {
                SmartImage(series.cover, Modifier.fillMaxSize(), fallbackTitle = series.title)
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                0.35f to Color.Transparent,
                                1f to Color(0xE6080A10),
                            ),
                        ),
                )
                Text(
                    rank.toString(),
                    style = MaterialTheme.typography.displayLarge,
                    color = accent.copy(alpha = 0.85f),
                    fontWeight = FontWeight.Black,
                    modifier = Modifier.padding(start = d(16), top = d(6)),
                )
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(d(12))
                        .clip(RoundedCornerShape(d(999)))
                        .background(Color(0xCC0A0C12))
                        .border(d(1f), accent.copy(0.45f), RoundedCornerShape(d(999)))
                        .padding(horizontal = d(10), vertical = d(4)),
                ) {
                    Text(if (rank == 1) "冠军" else if (rank == 2) "亚军" else "季军", style = MaterialTheme.typography.labelSmall, color = accent)
                }
                Column(
                    Modifier
                        .align(Alignment.BottomStart)
                        .padding(d(16)),
                ) {
                    Text(
                        series.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = Paper.Pure,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (!heat.isNullOrBlank()) {
                        Spacer(Modifier.height(d(4)))
                        Text("热度 $heat", style = MaterialTheme.typography.labelSmall, color = accent.copy(0.9f))
                    }
                }
                if (focused) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(Brush.verticalGradient(listOf(Color(0x0FFFFFFF), Color(0x22F2415A)))),
                    )
                }
            }
        }
    }
}

@Composable
private fun RankRow(
    series: Series,
    rank: Int,
    heat: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FocusSurface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(d(14)),
        focusScale = 1.02f,
        borderWidth = d(1.2f),
    ) { focused ->
        Row(
            Modifier
                .fillMaxWidth()
                .padding(d(12)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                rank.toString().padStart(2, '0'),
                style = MaterialTheme.typography.titleLarge,
                color = if (focused) Gold.Bright else Paper.Tertiary,
                fontWeight = FontWeight.Black,
                modifier = Modifier.width(d(38)),
            )
            Box(
                Modifier
                    .size(width = d(54), height = d(76))
                    .clip(RoundedCornerShape(d(8)))
                    .background(Ink.SurfaceHigh),
            ) {
                SmartImage(series.cover, Modifier.fillMaxSize(), fallbackTitle = series.title)
            }
            Spacer(Modifier.width(d(14)))
            Column(Modifier.weight(1f)) {
                Text(
                    series.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (focused) Paper.Pure else Paper.Primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(d(5)))
                Text(
                    series.tags.ifBlank { series.badge }.take(24),
                    style = MaterialTheme.typography.bodySmall,
                    color = Paper.Tertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!heat.isNullOrBlank()) {
                Text(heat, style = MaterialTheme.typography.labelSmall, color = Gold.Core.copy(0.85f))
            }
        }
    }
}
