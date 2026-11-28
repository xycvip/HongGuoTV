// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hongguotv.core.BackupProgress
import com.hongguotv.core.ContentType
import com.hongguotv.core.Series
import com.hongguotv.nativeapp.ui.components.CornerBadge
import com.hongguotv.nativeapp.ui.components.EmptyState
import com.hongguotv.nativeapp.ui.components.FocusSurface
import com.hongguotv.nativeapp.ui.components.PosterCard
import com.hongguotv.nativeapp.ui.components.SelectChip
import com.hongguotv.nativeapp.ui.components.SmartImage
import com.hongguotv.nativeapp.ui.components.ThinProgress
import com.hongguotv.nativeapp.ui.components.TvButton
import com.hongguotv.nativeapp.ui.components.ButtonTone
import com.hongguotv.nativeapp.ui.data.LibraryState
import com.hongguotv.nativeapp.ui.theme.Gold
import com.hongguotv.nativeapp.ui.theme.Ink
import com.hongguotv.nativeapp.ui.theme.Paper
import com.hongguotv.nativeapp.ui.theme.Rouge
import com.hongguotv.nativeapp.ui.theme.d
import com.hongguotv.nativeapp.ui.theme.ui

/** 追剧页的分区。公开是为了让账号中心能直接跳到指定分区。 */
enum class LibraryTab(val label: String) {
    HISTORY("观看历史"),
    FAVORITES("我的收藏"),
    RESERVATIONS("我的预约"),
}

/** 各分区的二级筛选，独立于主 Tab，切分区时各自保留。 */
enum class HistoryFilter(val label: String) { ALL("全部"), UNFINISHED("未看完"), FINISHED("已看完") }
enum class FavoriteFilter(val label: String) { ALL("全部"), REAL("真人剧"), COMIC("漫剧"), AI("AI剧") }
enum class ReservationFilter(val label: String) { ALL("全部"), UPCOMING("未上线"), ONLINE("已上线") }

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
    /** 从账号中心跳进来时指定的分区；消费后由外部清空，避免下次进入被旧值劫持。 */
    initialTab: LibraryTab? = null,
    onInitialTabConsumed: () -> Unit = {},
    entryFocus: FocusRequester? = null,
    upFocus: FocusRequester? = null,
) {
    // 「观看历史」排在前且为默认入口：进入追剧页先看历史。
    // 计数/落点全部按枚举类型判断，避免依赖顺序；entryFocus 也绑到历史标签。
    val historyIndex = LibraryTab.entries.indexOf(LibraryTab.HISTORY)
    var tab by remember(version) { mutableIntStateOf(historyIndex) }
    // 账号中心指定分区：覆盖当前选择并立刻回报已消费，防止切换回来时重复生效。
    LaunchedEffect(initialTab) {
        if (initialTab != null) {
            tab = LibraryTab.entries.indexOf(initialTab)
            onInitialTabConsumed()
        }
    }
    val current = LibraryTab.entries[tab.coerceIn(0, LibraryTab.entries.lastIndex)]
    val favorites = state.favorites
    val history = state.history
    val reservations = state.reservations
    // 二级筛选状态：每个分区独立记住，切走再回来不用重选。
    var historyFilter by remember(version) { mutableIntStateOf(HistoryFilter.entries.indexOf(HistoryFilter.ALL)) }
    var favoriteFilter by remember(version) { mutableIntStateOf(FavoriteFilter.entries.indexOf(FavoriteFilter.ALL)) }
    var reservationFilter by remember(version) { mutableIntStateOf(ReservationFilter.entries.indexOf(ReservationFilter.ALL)) }

    // 按分区 + 筛选得到最终列表。
    val shownHistory = when (HistoryFilter.entries[historyFilter.coerceIn(0, HistoryFilter.entries.lastIndex)]) {
        HistoryFilter.ALL -> history
        HistoryFilter.UNFINISHED -> history.filterNot { it.completed }
        HistoryFilter.FINISHED -> history.filter { it.completed }
    }
    val shownFavorites = when (FavoriteFilter.entries[favoriteFilter.coerceIn(0, FavoriteFilter.entries.lastIndex)]) {
        FavoriteFilter.ALL -> favorites
        FavoriteFilter.REAL -> favorites.filter { it.contentType == ContentType.SHORT }
        FavoriteFilter.COMIC -> favorites.filter { it.contentType == ContentType.COMIC }
        FavoriteFilter.AI -> favorites.filter { it.contentType == ContentType.AI }
    }
    val shownReservations = when (ReservationFilter.entries[reservationFilter.coerceIn(0, ReservationFilter.entries.lastIndex)]) {
        ReservationFilter.ALL -> reservations
        ReservationFilter.UPCOMING -> reservations.filterNot { it.online }
        ReservationFilter.ONLINE -> reservations.filter { it.online }
    }

    Box(modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(if (ui.compact) 5 else 6),
            modifier = Modifier.fillMaxSize().focusGroup().focusRestorer(),
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
                                "收藏 ${favorites.size} · 看过 ${history.size} · 预约 ${reservations.size}",
                                style = MaterialTheme.typography.bodySmall,
                                color = Paper.Tertiary,
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        if (current == LibraryTab.HISTORY && history.isNotEmpty()) {
                            TvButton("清空历史", onClearHistory, tone = ButtonTone.Ghost)
                        }
                    }
                    Spacer(Modifier.height(d(16)))
                    // 一级分区：三个大胶囊。
                    Row(horizontalArrangement = Arrangement.spacedBy(d(12))) {
                        LibraryTab.entries.forEachIndexed { index, item ->
                            SelectChip(
                                text = "${item.label}  ${
                                    when (item) {
                                        LibraryTab.FAVORITES -> favorites.size
                                        LibraryTab.RESERVATIONS -> reservations.size
                                        else -> history.size
                                    }
                                }",
                                selected = tab == index,
                                onClick = { tab = index },
                                focusRequester = if (item == LibraryTab.HISTORY) entryFocus else null,
                                upFocus = upFocus,
                            )
                        }
                    }
                    Spacer(Modifier.height(d(12)))
                    // 二级筛选：细胶囊 + 左侧引导线，视觉上与主分区分层。
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(d(8)),
                    ) {
                        Box(Modifier.width(d(2)).height(d(16)).background(Gold.Core.copy(alpha = 0.6f)))
                        when (current) {
                            LibraryTab.HISTORY -> HistoryFilter.entries.forEachIndexed { index, f ->
                                SelectChip(
                                    text = f.label, selected = historyFilter == index,
                                    onClick = { historyFilter = index }, accent = Gold.Core,
                                )
                            }
                            LibraryTab.FAVORITES -> FavoriteFilter.entries.forEachIndexed { index, f ->
                                SelectChip(
                                    text = f.label, selected = favoriteFilter == index,
                                    onClick = { favoriteFilter = index }, accent = Gold.Core,
                                )
                            }
                            LibraryTab.RESERVATIONS -> ReservationFilter.entries.forEachIndexed { index, f ->
                                SelectChip(
                                    text = f.label, selected = reservationFilter == index,
                                    onClick = { reservationFilter = index }, accent = Gold.Core,
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(d(10)))
                }
            }

            when (current) {
                LibraryTab.FAVORITES -> {
                    if (shownFavorites.isEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            EmptyState(
                                title = if (favorites.isEmpty()) "还没有收藏" else "这个分类还没有收藏",
                                subtitle = if (favorites.isEmpty())
                                    "在剧集详情页按「加入收藏」，更新会第一时间出现在这里。"
                                else "切换上方分类，或在详情页收藏这个类型的剧。",
                                modifier = Modifier.height(d(320)),
                                action = { TvButton("去剧场逛逛", onGoTheater, tone = ButtonTone.Ghost) },
                            )
                        }
                    } else {
                        items(shownFavorites, key = { it.id }) { series ->
                            PosterCard(
                                series = series,
                                onClick = { onDetail(series) },
                                onLongClick = { onToggleFavorite(series) },
                                fillWidth = true,
                                subtitle = state.favoriteLabels[series.id],
                                completed = series.id in state.completedIds,
                            )
                        }
                    }
                }
                LibraryTab.HISTORY -> {
                    if (shownHistory.isEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            EmptyState(
                                title = if (history.isEmpty()) "还没有观看记录" else "这个筛选下没有记录",
                                subtitle = if (history.isEmpty())
                                    "看过的剧会自动出现在这里，随时接着看。"
                                else "切换上方筛选查看其它观看记录。",
                                modifier = Modifier.height(d(320)),
                                action = { TvButton("去首页看看", onGoTheater, tone = ButtonTone.Ghost) },
                            )
                        }
                    } else {
                        items(shownHistory, key = { it.series.id }, span = { GridItemSpan(maxLineSpan) }) { progress ->
                            HistoryRow(
                                progress = progress,
                                onClick = { onResume(progress) },
                                onRemove = { onRemoveHistory(progress.series.id) },
                            )
                        }
                    }
                }
                LibraryTab.RESERVATIONS -> {
                    if (shownReservations.isEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            EmptyState(
                                title = if (reservations.isEmpty()) "还没有预约" else "这个筛选下没有预约",
                                subtitle = if (reservations.isEmpty())
                                    "登录账号后，手机端预约的作品会同步显示在这里。"
                                else "切换上方筛选查看其它预约。",
                                modifier = Modifier.height(d(320)),
                                action = { TvButton("去剧场逛逛", onGoTheater, tone = ButtonTone.Ghost) },
                            )
                        }
                    } else {
                        items(shownReservations, key = { it.series.id }) { reservation ->
                            PosterCard(
                                series = reservation.series,
                                onClick = { onDetail(reservation.series) },
                                fillWidth = true,
                                subtitle = if (reservation.online) "已上线 · 点击观看"
                                    else if (reservation.publishText.isNotBlank()) reservation.publishText
                                    else "未上线 · 已预约",
                                completed = false,
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
    // 整行是外层焦点，两个按钮各自可聚焦。显式指定横向焦点链，避免外层 combinedClickable
    // 与内层按钮焦点竞争导致「继续观看 / 移除」按遥控器选不中。
    val rowFocus = remember { FocusRequester() }
    val playFocus = remember { FocusRequester() }
    val removeFocus = remember { FocusRequester() }
    FocusSurface(
        onClick = onClick,
        shape = RoundedCornerShape(d(16)),
        focusScale = 1.012f,
        borderWidth = d(1.2f),
        focusRequester = rowFocus,
        modifier = Modifier.focusProperties { right = playFocus },
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
                // 已看完角标：completed 表示本集播完、续播跳下一集，与首页「看下一集」口径一致。
                if (progress.completed) {
                    Box(Modifier.align(Alignment.TopStart).padding(d(6))) {
                        CornerBadge("已看完", background = Color(0xCC1B8A4C))
                    }
                }
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
                    if (progress.completed) {
                        "看到第 ${progress.episodeIndex + 1} 集 · 已看完"
                    } else {
                        "看到第 ${progress.episodeIndex + 1} 集 · ${formatSeconds(progress.position)}" +
                            if (progress.duration > 0) " / ${formatSeconds(progress.duration)}" else ""
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = Paper.Tertiary,
                )
                Spacer(Modifier.height(d(10)))
                ThinProgress(percent, Modifier.width(d(280)), height = d(3))
            }
            Spacer(Modifier.width(d(16)))
            TvButton(
                "继续观看", onClick, tone = ButtonTone.Ghost,
                modifier = Modifier
                    .focusRequester(playFocus)
                    .focusProperties { right = removeFocus; left = rowFocus },
            )
            Spacer(Modifier.width(d(10)))
            TvButton(
                "移除", onRemove, tone = ButtonTone.Quiet,
                modifier = Modifier
                    .focusRequester(removeFocus)
                    .focusProperties { left = playFocus },
            )
        }
    }
}

fun formatSeconds(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return "%02d:%02d".format(total / 60, total % 60)
}
