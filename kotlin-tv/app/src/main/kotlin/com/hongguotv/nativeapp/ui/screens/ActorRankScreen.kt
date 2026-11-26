// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hongguotv.core.Actor
import com.hongguotv.core.ActorRankBoard
import com.hongguotv.core.ActorRankEntry
import com.hongguotv.core.Series
import com.hongguotv.nativeapp.ui.components.EmptyState
import com.hongguotv.nativeapp.ui.components.ErrorState
import com.hongguotv.nativeapp.ui.components.FocusSurface
import com.hongguotv.nativeapp.ui.components.LoadingState
import com.hongguotv.nativeapp.ui.components.SelectChip
import com.hongguotv.nativeapp.ui.components.SmartImage
import com.hongguotv.nativeapp.ui.components.focusUpTo
import com.hongguotv.nativeapp.ui.components.tryRequestFocus
import com.hongguotv.nativeapp.ui.data.ActorRankState
import com.hongguotv.nativeapp.ui.data.ActorState
import com.hongguotv.nativeapp.ui.theme.Gold
import com.hongguotv.nativeapp.ui.theme.Paper
import com.hongguotv.nativeapp.ui.theme.Rouge
import com.hongguotv.nativeapp.ui.theme.d
import com.hongguotv.nativeapp.ui.theme.ui
import kotlinx.coroutines.flow.collect

/**
 * 演员榜：剧星榜（看参演剧热度）/ 星光榜（看粉丝互动）。
 *
 * 为什么不并进排行榜当第五个签：榜签换的是「口径」（热播/推荐/新剧/臻果），
 * 第二行的内容签（综合/真人/漫剧/AI剧）对「人」没有意义。
 * 单独一页才能把排版换成头像网格，也才装得下「名次变动」这种只有人榜才有的信息。
 *
 * 两类卡：前 3 名用大卡（名次水印 + 圆头像 + 人气说明），4 名起用紧凑网格，
 * 一屏能看到 20 多位，不用一直按遥控器。
 */
@Composable
fun ActorRankScreen(
    state: ActorRankState,
    actorState: ActorState,
    onBoard: (ActorRankBoard) -> Unit,
    onActor: (Actor) -> Unit,
    onActorClose: () -> Unit,
    onActorDetail: (Series) -> Unit,
    onActorLoadMore: () -> Unit,
    onActorRetry: () -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    entryFocus: FocusRequester? = null,
    upFocus: FocusRequester? = null,
) {
    val listState = rememberLazyListState()
    val columns = if (ui.compact) 4 else 6

    // 触底续页：提前一屏发起，滑到底时数据已经换成下一批。
    LaunchedEffect(listState, state.hasMore, state.items.size, state.board) {
        if (!state.hasMore) return@LaunchedEffect
        snapshotFlow {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: return@snapshotFlow false
            state.items.isNotEmpty() && last >= info.totalItemsCount - 2
        }.collect { nearEnd -> if (nearEnd) onLoadMore() }
    }

    val boardFoci = remember(entryFocus) {
        ActorRankBoard.entries.associateWith { board ->
            if (board == ActorRankBoard.entries.first() && entryFocus != null) entryFocus else FocusRequester()
        }
    }
    val firstCardFocus = remember { FocusRequester() }
    // 只在「本次进入页面」把焦点送到第一张卡；之后切榜就停在签上，方便连续对比两个榜。
    // 从详情页返回时列表已经有数据，此时不抢焦点，免得把用户从刚才那一位身上拽回第一名。
    var cardFocusPlaced by remember { mutableStateOf(state.items.isNotEmpty()) }

    LaunchedEffect(state.board) {
        repeat(6) {
            val target = boardFoci[state.board]
            if (target != null && target.tryRequestFocus()) return@LaunchedEffect
            withFrameNanos { }
        }
    }
    LaunchedEffect(state.items.size) {
        if (cardFocusPlaced || state.items.isEmpty()) return@LaunchedEffect
        repeat(10) {
            if (firstCardFocus.tryRequestFocus()) {
                cardFocusPlaced = true
                return@LaunchedEffect
            }
            withFrameNanos { }
        }
    }

    Box(modifier.fillMaxSize()) {
        when {
            state.loading && state.items.isEmpty() -> LoadingState(label = "正在拉取演员榜")

            state.error != null && state.items.isEmpty() -> ErrorState(
                state.error,
                onRetry,
                focusRequester = entryFocus,
            )

            else -> LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().focusGroup(),
                contentPadding = PaddingValues(
                    start = ui.gutter,
                    end = ui.gutter,
                    top = d(96),
                    bottom = d(60),
                ),
                verticalArrangement = Arrangement.spacedBy(d(22)),
            ) {
                item {
                    Column {
                        Row(verticalAlignment = Alignment.Bottom) {
                            Column {
                                Text(
                                    "演员榜",
                                    style = MaterialTheme.typography.displaySmall,
                                    color = Paper.Pure,
                                    fontWeight = FontWeight.Bold,
                                )
                                Spacer(Modifier.height(d(6)))
                                Text(
                                    // 副标题跟榜走：两个榜统计口径完全不同，共用一句会误导。
                                    state.board.tagline + " · 红果官方数据",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Paper.Tertiary,
                                )
                            }
                            Spacer(Modifier.weight(1f))
                            Text(
                                if (state.items.isEmpty()) {
                                    "返回键回排行榜"
                                } else {
                                    "共 ${state.items.size} 位 · 返回键回排行榜"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = Paper.Tertiary,
                                modifier = Modifier.padding(bottom = d(4)),
                            )
                        }
                        Spacer(Modifier.height(d(18)))
                        Row(horizontalArrangement = Arrangement.spacedBy(d(10))) {
                            ActorRankBoard.entries.forEach { board ->
                                SelectChip(
                                    text = board.label,
                                    selected = state.board == board,
                                    onClick = { onBoard(board) },
                                    accent = Gold.Core,
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
                            top.forEachIndexed { index, entry ->
                                PodiumActor(
                                    entry = entry,
                                    onClick = { onActor(entry.actor) },
                                    modifier = Modifier.weight(1f),
                                    focusRequester = if (index == 0) firstCardFocus else null,
                                    upFocus = boardFoci[state.board],
                                )
                            }
                            if (top.size < 3) {
                                repeat(3 - top.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                    }
                }

                val rest = state.items.drop(3)
                if (rest.isNotEmpty()) {
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
                                    .background(Gold.Core),
                            )
                            Spacer(Modifier.width(d(9)))
                            Text(
                                "第 4 名起",
                                style = MaterialTheme.typography.titleMedium,
                                color = Paper.Primary,
                            )
                            Spacer(Modifier.weight(1f))
                            Text(
                                "左右键浏览 · 上下键滚动",
                                style = MaterialTheme.typography.labelSmall,
                                color = Paper.Tertiary,
                            )
                        }
                    }
                    itemsIndexed(rest.chunked(columns)) { _, row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(d(14))) {
                            row.forEach { entry ->
                                ActorCell(
                                    entry = entry,
                                    onClick = { onActor(entry.actor) },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            if (row.size < columns) {
                                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                    }
                }

                if (state.items.isNotEmpty() && (state.loadingMore || !state.hasMore)) {
                    item {
                        Box(
                            Modifier.fillMaxWidth().padding(vertical = d(8)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                if (state.loadingMore) "正在加载更多…" else "已经到底了",
                                style = MaterialTheme.typography.bodySmall,
                                color = Paper.Tertiary,
                            )
                        }
                    }
                } else if (state.items.isEmpty() && !state.loading && state.error == null) {
                    item {
                        Box(Modifier.fillMaxWidth().height(d(320)), contentAlignment = Alignment.Center) {
                            EmptyState("暂无榜单数据", "官方这会儿没有返回演员，稍后再试试。")
                        }
                    }
                }
            }
        }

        // 榜上点开演员：复用详情页那套弹窗，档案 + 作品一屏看完。
        if (actorState.actor != null) {
            ActorSheet(
                state = actorState,
                onClose = onActorClose,
                onDetail = onActorDetail,
                onLoadMore = onActorLoadMore,
                onRetry = onActorRetry,
            )
        }
    }
}

/** 前三名：名次 + 圆头像 + 姓名 + 人气说明，金银铜三色描边。 */
@Composable
private fun PodiumActor(
    entry: ActorRankEntry,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    upFocus: FocusRequester? = null,
) {
    val accent = when (entry.rank) {
        1 -> Gold.Bright
        2 -> Color(0xFFDCE2ED)
        else -> Color(0xFFE4B08A)
    }
    FocusSurface(
        onClick = onClick,
        modifier = modifier.focusUpTo(upFocus),
        shape = RoundedCornerShape(d(20)),
        focusScale = 1.03f,
        glow = if (entry.rank == 1) Gold.Core else Rouge.Core,
        borderWidth = d(1.4f),
        focusRequester = focusRequester,
    ) { focused ->
        Column(
            Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            accent.copy(alpha = if (focused) 0.18f else 0.10f),
                            Color(0x00FFFFFF),
                        ),
                    ),
                )
                .padding(horizontal = d(20), vertical = d(18)),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "NO.${entry.rank}",
                    style = MaterialTheme.typography.titleMedium,
                    color = accent,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.weight(1f))
                TrendBadge(entry)
            }
            Spacer(Modifier.height(d(14)))
            Box(
                Modifier
                    .size(d(112))
                    .border(d(2f), accent.copy(alpha = 0.75f), CircleShape)
                    .padding(d(3))
                    .clip(CircleShape),
            ) {
                ActorAvatar(
                    name = entry.actor.name,
                    url = entry.actor.avatar,
                    modifier = Modifier.fillMaxSize().clip(CircleShape),
                )
            }
            Spacer(Modifier.height(d(12)))
            Text(
                entry.actor.name,
                style = MaterialTheme.typography.headlineSmall,
                color = Paper.Pure,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(d(6)))
            Text(
                entry.popularity.ifBlank { entry.note },
                style = MaterialTheme.typography.bodySmall,
                color = accent.copy(alpha = 0.92f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(d(4)))
            Text(
                entry.note,
                style = MaterialTheme.typography.labelSmall,
                color = Paper.Tertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** 第 4 名起的紧凑卡：圆头像 + 名次角标 + 姓名 + 一句说明。 */
@Composable
private fun ActorCell(
    entry: ActorRankEntry,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FocusSurface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(d(16)),
        focusScale = 1.05f,
        glow = Gold.Core,
        borderWidth = d(1.2f),
    ) { focused ->
        Column(
            Modifier
                .fillMaxWidth()
                .background(Color(if (focused) 0x14FFFFFF else 0x06FFFFFF))
                .padding(horizontal = d(10), vertical = d(14)),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.size(d(84))) {
                ActorAvatar(
                    name = entry.actor.name,
                    url = entry.actor.avatar,
                    modifier = Modifier
                        .fillMaxSize()
                        .border(
                            d(1.5f),
                            if (focused) Gold.Bright else Color(0x33FFFFFF),
                            CircleShape,
                        )
                        .padding(d(2))
                        .clip(CircleShape),
                )
                Box(
                    Modifier
                        .align(Alignment.TopStart)
                        .clip(RoundedCornerShape(d(7)))
                        .background(Color(0xE60A0C12))
                        .padding(horizontal = d(6), vertical = d(2)),
                ) {
                    Text(
                        entry.rank.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (entry.rank <= 10) Gold.Bright else Paper.Tertiary,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            Spacer(Modifier.height(d(10)))
            Text(
                entry.actor.name,
                style = MaterialTheme.typography.labelLarge,
                color = Paper.Primary,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(d(3)))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TrendBadge(entry)
                Spacer(Modifier.width(d(6)))
                Text(
                    entry.popularity.ifBlank { entry.note },
                    style = MaterialTheme.typography.labelSmall,
                    color = Paper.Tertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** 名次变动：上升金色、下降灰色、持平一条短横；官方不给数据时不造谣。 */
@Composable
private fun TrendBadge(entry: ActorRankEntry) {
    val color = when {
        entry.trendUp -> Gold.Bright
        entry.trendDown -> Paper.Tertiary
        else -> Paper.Disabled
    }
    Text(
        entry.changeText,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        fontWeight = if (entry.trendUp) FontWeight.Bold else FontWeight.Normal,
    )
}

/**
 * 榜单头像。
 *
 * 头像字段来自脚本侧，版本不同可能压根不下发；这时留一块纯黑，
 * 用户只会认为「页面坏了」。没有头像就画一个带姓名的圆底（首字很大、金色），
 * 至少知道这是谁；有头像才走正常图片加载。
 *
 * 图片 URL 已在解析层过了一遍 [com.hongguotv.core.ImageUrls.normalize]，
 * 裸 novel-pic / heic 都会重建成可直接解码的 JPEG，不会再出现「有地址却全黑」。
 */
@Composable
private fun ActorAvatar(
    name: String,
    url: String,
    modifier: Modifier = Modifier,
) {
    if (url.isBlank()) {
        Box(
            modifier.background(
                Brush.linearGradient(listOf(Color(0xFF2B3448), Color(0xFF151A24))),
                CircleShape,
            ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                name.take(1).ifBlank { "?" },
                style = MaterialTheme.typography.headlineMedium,
                color = Gold.Bright.copy(alpha = 0.9f),
                fontWeight = FontWeight.Bold,
            )
        }
    } else {
        SmartImage(url = url, modifier = modifier, fallbackTitle = name.take(1))
    }
}
