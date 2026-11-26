// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.hongguotv.core.Series
import com.hongguotv.nativeapp.ui.components.EmptyState
import com.hongguotv.nativeapp.ui.components.ErrorState
import com.hongguotv.nativeapp.ui.components.LoadingState
import com.hongguotv.nativeapp.ui.components.PosterCard
import com.hongguotv.nativeapp.ui.components.RoundIconButton
import com.hongguotv.nativeapp.ui.components.SmartImage
import com.hongguotv.nativeapp.ui.components.tryRequestFocus
import com.hongguotv.nativeapp.ui.data.ActorState
import com.hongguotv.nativeapp.ui.theme.Gold
import com.hongguotv.nativeapp.ui.theme.Paper
import com.hongguotv.nativeapp.ui.theme.Rouge
import com.hongguotv.nativeapp.ui.theme.d
import com.hongguotv.nativeapp.ui.theme.ui

/**
 * 演职员弹窗：左边是演员档案，右边是参演作品。
 *
 * 为什么不再做成整页：演员是从某一部剧里点出来的，用户的问题通常是
 * 「这人是谁、还演过什么」，看完就回去接着选集。整页会把详情页整块顶掉、
 * 返回后选集位置也不一定还在；弹窗把这两件事压在同一屏里，返回键一按就回到原处。
 *
 * 焦点：进入即落在第一部作品，没有作品时落在关闭按钮，绝不出现「弹窗开着但遥控器没落点」。
 */
@Composable
fun ActorSheet(
    state: ActorState,
    onClose: () -> Unit,
    onDetail: (Series) -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
) {
    val actor = state.actor ?: return
    val config = LocalConfiguration.current
    // 卡片尺寸跟着屏幕走：1080p 上约 1360×800，720p 上等比收，绝不出界。
    val cardWidth = minOf((config.screenWidthDp * 0.80f).dp, d(1360))
    val cardHeight = minOf((config.screenHeightDp * 0.84f).dp, d(820))
    val tight = cardHeight < d(660)

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = false,
            dismissOnBackPress = true,
        ),
    ) {
        val grid = rememberLazyGridState()
        val closeFocus = remember { FocusRequester() }
        val firstItemFocus = remember { FocusRequester() }

        // 作品到位就把焦点交给第一张卡；还在加载/出错时落在关闭按钮，
        // 保证「弹窗一出现，遥控器就有一个确定能按的落点」。
        // 只在首次拿到数据时定位一次：续页会让 items.size 变化，若每次都抢焦点，
        // 用户刚滑到第二屏就被拽回第一张。
        var focusPlaced by remember(actor.id) { mutableStateOf(false) }
        LaunchedEffect(actor.id, state.items.size, state.loading, state.error) {
            if (focusPlaced) return@LaunchedEffect
            if (state.items.isEmpty()) {
                repeat(12) {
                    if (closeFocus.tryRequestFocus()) return@LaunchedEffect
                    withFrameNanos { }
                }
                return@LaunchedEffect
            }
            repeat(20) {
                if (firstItemFocus.tryRequestFocus()) {
                    focusPlaced = true
                    return@LaunchedEffect
                }
                withFrameNanos { }
            }
        }

        // 触底续页：提前一整行发起，滑到底时数据已经换成下一批。
        val columns = if (ui.compact) 3 else 5
        LaunchedEffect(grid, state.items.size, state.hasMore) {
            if (state.items.isEmpty() || !state.hasMore) return@LaunchedEffect
            snapshotFlow { grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
                .collect { last ->
                    if (last >= state.items.size - columns) onLoadMore()
                }
        }

        Box(
            Modifier
                .fillMaxSize()
                // 系统 Dialog 自己还会叠一层 dim，这里只补到「详情页明显退后」的程度，
                // 拉满会变成纯黑，弹窗看着像换了一页。
                .background(Color(0xA8060910)),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier
                    .width(cardWidth)
                    .height(cardHeight)
                    .clip(RoundedCornerShape(d(28)))
                    .background(
                        Brush.verticalGradient(
                            listOf(Color(0xFF171B25), Color(0xFF0B0E14)),
                        ),
                    )
                    .border(d(1f), Color(0x24FFFFFF), RoundedCornerShape(d(28)))
                    .padding(horizontal = d(36), vertical = d(30)),
            ) {
                // ---- 顶栏：品类标签 + 关闭（返回键同样可关） ----
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .width(d(42))
                            .height(d(3))
                            .clip(RoundedCornerShape(99.dp))
                            .background(Brush.horizontalGradient(listOf(Rouge.Bright, Gold.Core))),
                    )
                    Spacer(Modifier.width(d(12)))
                    Text(
                        "演职员档案",
                        style = MaterialTheme.typography.labelLarge,
                        color = Rouge.Bright,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        "返回键关闭",
                        style = MaterialTheme.typography.labelSmall,
                        color = Paper.Tertiary,
                    )
                    Spacer(Modifier.width(d(14)))
                    RoundIconButton(
                        Icons.Rounded.Close,
                        onClose,
                        size = d(40),
                        iconSize = d(16),
                        contentDescription = "关闭",
                        focusRequester = closeFocus,
                    )
                }
                Spacer(Modifier.height(d(if (tight) 18 else 26)))

                Row(Modifier.fillMaxWidth().weight(1f)) {
                    // ---- 左：演员档案 ----
                    Column(
                        Modifier.width(d(if (tight) 240 else 296)).fillMaxSize(),
                        horizontalAlignment = Alignment.Start,
                    ) {
                        val avatar = if (tight) d(112) else d(150)
                        Box(
                            Modifier
                                .size(avatar)
                                .background(
                                    Brush.linearGradient(
                                        listOf(Rouge.Deep.copy(alpha = 0.60f), Color(0x14FFFFFF)),
                                    ),
                                    CircleShape,
                                )
                                .padding(d(3))
                                .clip(CircleShape),
                        ) {
                            SmartImage(
                                url = actor.avatar,
                                modifier = Modifier.fillMaxSize().clip(CircleShape),
                                fallbackTitle = actor.name.take(1),
                            )
                        }
                        Spacer(Modifier.height(d(if (tight) 16 else 22)))
                        Text(
                            actor.name,
                            style = MaterialTheme.typography.displaySmall,
                            color = Paper.Pure,
                            fontWeight = FontWeight.Bold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        // 「本剧饰谁」比任何简介都更快回答「这人是谁」。
                        if (actor.role.isNotBlank()) {
                            Spacer(Modifier.height(d(10)))
                            Box(
                                Modifier
                                    .clip(RoundedCornerShape(d(999)))
                                    .background(Rouge.Core.copy(alpha = 0.16f))
                                    .padding(horizontal = d(12), vertical = d(6)),
                            ) {
                                Text(
                                    "本剧饰 ${actor.role}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Rouge.Bright,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        Spacer(Modifier.height(d(14)))
                        val count = if (state.total > 0) state.total else state.items.size
                        Text(
                            // 从演员榜点进来时官方自带「32部作品 · 双周追剧161万人」，
                            // 比我们自己数的「共 N 部作品」信息量大，优先用它。
                            when {
                                actor.meta.isNotBlank() -> actor.meta
                                count > 0 -> "共 $count 部作品"
                                else -> "作品整理中"
                            },
                            style = MaterialTheme.typography.labelLarge,
                            color = Paper.Tertiary,
                        )
                        Spacer(Modifier.height(d(14)))
                        Text(
                            actor.intro.ifBlank { "暂无公开简介。" },
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (actor.intro.isBlank()) Paper.Tertiary else Paper.Secondary,
                            maxLines = if (tight) 4 else 7,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    Spacer(Modifier.width(d(if (tight) 24 else 34)))

                    // ---- 右：参演作品 ----
                    Column(Modifier.weight(1f).fillMaxSize()) {
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(
                                "参演作品",
                                style = MaterialTheme.typography.headlineSmall,
                                color = Paper.Primary,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Spacer(Modifier.width(d(10)))
                            Text(
                                "选中查看详情",
                                style = MaterialTheme.typography.bodySmall,
                                color = Paper.Tertiary,
                                modifier = Modifier.padding(bottom = d(2)),
                            )
                        }
                        Spacer(Modifier.height(d(16)))
                        val loadError = state.error
                        Box(Modifier.fillMaxWidth().weight(1f)) {
                            when {
                                state.loading && state.items.isEmpty() ->
                                    LoadingState(label = "正在加载作品")

                                loadError != null && state.items.isEmpty() ->
                                    ErrorState(
                                        message = loadError,
                                        onRetry = onRetry,
                                        focusRequester = closeFocus,
                                    )

                                state.items.isEmpty() ->
                                    EmptyState("暂无作品", "这位演员暂时没有收录到的作品。")

                                else -> LazyVerticalGrid(
                                    columns = GridCells.Fixed(columns),
                                    state = grid,
                                    modifier = Modifier.fillMaxSize(),
                                    contentPadding = PaddingValues(bottom = d(10)),
                                    horizontalArrangement = Arrangement.spacedBy(d(14)),
                                    verticalArrangement = Arrangement.spacedBy(d(22)),
                                ) {
                                    items(state.items, key = { it.id }) { series ->
                                        PosterCard(
                                            series = series,
                                            onClick = { onDetail(series) },
                                            fillWidth = true,
                                            subtitle = series.tags.take(14).ifBlank { series.badge },
                                            focusRequester = firstItemFocus.takeIf { series.id == state.items.first().id },
                                        )
                                    }
                                    if (state.loadingMore) {
                                        item(span = { GridItemSpan(maxLineSpan) }) {
                                            Box(
                                                Modifier.fillMaxWidth().height(d(54)),
                                                contentAlignment = Alignment.Center,
                                            ) {
                                                Text(
                                                    "正在加载更多…",
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
                }
            }
        }
    }
}
