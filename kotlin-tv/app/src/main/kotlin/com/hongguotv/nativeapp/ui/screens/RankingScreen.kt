// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.Icon
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
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hongguotv.core.RankBoard
import com.hongguotv.core.RankKind
import com.hongguotv.core.Series
import com.hongguotv.nativeapp.ui.components.ErrorState
import com.hongguotv.nativeapp.ui.components.FocusSurface
import com.hongguotv.nativeapp.ui.components.LoadingState
import com.hongguotv.nativeapp.ui.components.SelectChip
import com.hongguotv.nativeapp.ui.components.SmartImage
import com.hongguotv.nativeapp.ui.components.focusUpTo
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
    onKind: (RankKind) -> Unit,
    onBoard: (RankBoard) -> Unit,
    /** 打开演员榜：那是「人」的榜，换的是对象而不是口径，所以单独一页。 */
    onActorRank: () -> Unit,
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
    LaunchedEffect(listState, state.hasMore, state.items.size, state.page, state.board, state.kind) {
        if (!state.hasMore) return@LaunchedEffect
        snapshotFlow {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: return@snapshotFlow false
            state.items.isNotEmpty() && last >= info.totalItemsCount - 5
        }.collect { nearEnd -> if (nearEnd) onLoadMore() }
    }

    // 第一行是「榜」（页面主控），第二行是「内容」（次级筛选），焦点默认落第一行。
    val kindFoci = remember(entryFocus) {
        RankKind.entries.associateWith { kind ->
            if (kind == RankKind.entries.first() && entryFocus != null) entryFocus else FocusRequester()
        }
    }
    val boardFoci = remember { RankBoard.entries.associateWith { FocusRequester() } }

    // 只跟着「榜」走：切内容时焦点本来就在内容签上，没必要把它抢回第一行。
    // items 从空变非空也要再试一次——首屏加载时签还没进组合树，requestFocus 会失败。
    LaunchedEffect(state.kind, state.items.isEmpty()) {
        repeat(6) {
            val target = kindFoci[state.kind]
            if (target != null && target.tryRequestFocus()) return@LaunchedEffect
            withFrameNanos { }
        }
    }

    // 换榜/换内容都回顶部：旧榜的滚动位置留在这里会让人以为「榜单没切换」。
    LaunchedEffect(state.kind, state.board) { listState.scrollToItem(0) }

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
                                    // 脚本给的描述跟着榜走（「11月26日已更新·基于…顺序」），
                                    // 拿不到才用本地兜底文案，避免四个榜共用一个写死的标题。
                                    state.updatedText.ifBlank { state.kind.sourceText },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Paper.Tertiary,
                                )
                            }
                        }
                        Spacer(Modifier.height(d(18)))
                        // 第一行 = 榜（主控）：实心胶囊，选中红底，四个口径按「热度→发现→时效→口碑」排。
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(d(10)),
                        ) {
                            RankKind.entries.forEach { kind ->
                                SelectChip(
                                    text = kind.label,
                                    selected = state.kind == kind,
                                    onClick = { onKind(kind) },
                                    accent = Rouge.Core,
                                    focusRequester = kindFoci[kind],
                                    upFocus = upFocus,
                                )
                            }
                            // 竖线把「换榜」和「换到人榜」隔开：金色实心、图标开场，
                            // 一眼能看出它不是第五个榜签，按下去是另一页。
                            Box(
                                Modifier
                                    .padding(horizontal = d(4))
                                    .width(d(1))
                                    .height(d(18))
                                    .background(Ink.LineSoft),
                            )
                            ActorRankChip(onClick = onActorRank, upFocus = upFocus)
                        }
                        Spacer(Modifier.height(d(12)))
                        // 第二行 = 内容（次级）：细描边小签，一眼能和上面区分开。
                        // 榜只覆盖一类时（臻果）不渲染内容签，改一句话说明，不留按到死的灰键。
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(d(8)),
                        ) {
                            Text(
                                "内容",
                                style = MaterialTheme.typography.labelSmall,
                                color = Paper.Tertiary,
                                modifier = Modifier.padding(end = d(2)),
                            )
                            if (state.kind.availableBoards.size > 1) {
                                RankBoard.entries.forEach { board ->
                                    if (board in state.kind.availableBoards) {
                                        TypeChip(
                                            text = board.label,
                                            selected = state.board == board,
                                            onClick = { onBoard(board) },
                                            accent = Gold.Core,
                                            focusRequester = boardFoci[board],
                                            upFocus = kindFoci[state.kind],
                                        )
                                    }
                                }
                            } else {
                                Text(
                                    state.kind.scopeNote.ifBlank { "仅${state.board.label}已开分" },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Gold.Core.copy(alpha = 0.9f),
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

/**
 * 次级「内容」签：比榜签小一号、无填充、细描边。
 *
 * 两个选中态不能打架——榜签实心红、内容签只亮描边，
 * 一眼能看出「当前是哪个榜」和「当前看哪一类内容」。
 */
@Composable
private fun TypeChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    accent: Color,
    focusRequester: FocusRequester?,
    upFocus: FocusRequester?,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Box(
        Modifier
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .focusUpTo(upFocus)
            .clip(RoundedCornerShape(d(999)))
            .background(
                when {
                    selected -> accent.copy(alpha = 0.16f)
                    focused -> Color(0x14FFFFFF)
                    else -> Color.Transparent
                },
            )
            .border(
                d(1f),
                when {
                    focused -> accent
                    selected -> accent.copy(alpha = 0.55f)
                    else -> Ink.LineSoft
                },
                RoundedCornerShape(d(999)),
            )
            .combinedClickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = d(14), vertical = d(6)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = when {
                selected || focused -> Paper.Pure
                else -> Paper.Secondary
            },
            maxLines = 1,
        )
    }
}

/**
 * 「演员榜」入口。
 *
 * 金色实心 + 图标，与上方的红底榜签刻意不同：它不切换当前榜单，而是去另一页看「人」。
 * 放在四个榜签同一行的末尾，是因为它回答的是同一个问题「谁在火」，
 * 只是主体从剧换成了演员，连着按左右键就能到，不用另找入口。
 */
@Composable
private fun ActorRankChip(onClick: () -> Unit, upFocus: FocusRequester?) {
    FocusSurface(
        onClick = onClick,
        modifier = Modifier.focusUpTo(upFocus),
        shape = RoundedCornerShape(d(999)),
        focusScale = 1.08f,
        glow = Gold.Core,
        borderWidth = d(1.2f),
    ) { focused ->
        Row(
            Modifier
                .background(
                    Brush.horizontalGradient(
                        if (focused) {
                            listOf(Gold.Deep.copy(alpha = 0.95f), Gold.Core.copy(alpha = 0.55f))
                        } else {
                            listOf(Gold.Deep.copy(alpha = 0.50f), Gold.Core.copy(alpha = 0.20f))
                        },
                    ),
                )
                .padding(horizontal = d(16), vertical = d(7)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Rounded.Star,
                contentDescription = null,
                tint = Gold.Bright,
                modifier = Modifier.size(d(15)),
            )
            Spacer(Modifier.width(d(7)))
            Text(
                "演员榜",
                style = MaterialTheme.typography.labelMedium,
                color = Gold.Bright,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
        }
    }
}
