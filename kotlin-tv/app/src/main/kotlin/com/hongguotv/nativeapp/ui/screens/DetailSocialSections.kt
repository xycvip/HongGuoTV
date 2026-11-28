// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.hongguotv.core.Creator
import com.hongguotv.core.Review
import com.hongguotv.core.Series
import com.hongguotv.nativeapp.ui.components.EmptyState
import com.hongguotv.nativeapp.ui.components.ErrorState
import com.hongguotv.nativeapp.ui.components.LoadingState
import com.hongguotv.nativeapp.ui.components.PosterCard
import com.hongguotv.nativeapp.ui.components.RoundIconButton
import com.hongguotv.nativeapp.ui.components.SmartImage
import com.hongguotv.nativeapp.ui.components.tryRequestFocus
import com.hongguotv.nativeapp.ui.data.CreatorState
import com.hongguotv.nativeapp.ui.data.ReviewState
import com.hongguotv.nativeapp.ui.theme.Gold
import com.hongguotv.nativeapp.ui.theme.Ink
import com.hongguotv.nativeapp.ui.theme.Paper
import com.hongguotv.nativeapp.ui.theme.Rouge
import com.hongguotv.nativeapp.ui.theme.d
import com.hongguotv.nativeapp.ui.theme.ui

/**
 * 发布作者弹窗：详情页「作者信息」胶囊打开。
 *
 * 作者身份卡在上、TA 的作品网格在下；返回键关闭，焦点回到胶囊。
 * 与演职员弹窗同一套深色卡片 + 顶栏，切剧时由上层重置 [CreatorState]，
 * 不会出现新详情继承旧作者的情况。
 */
@Composable
fun CreatorSheet(
    state: CreatorState,
    onClose: () -> Unit,
    onFollow: () -> Unit,
    onWork: (Series) -> Unit,
    onLoadMoreWorks: () -> Unit,
) {
    val config = LocalConfiguration.current
    val cardWidth = minOf((config.screenWidthDp * 0.84f).dp, d(1240))
    val cardHeight = minOf((config.screenHeightDp * 0.84f).dp, d(820))
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = false,
            dismissOnBackPress = true,
        ),
    ) {
        val closeFocus = remember { FocusRequester() }
        val grid = rememberLazyGridState()
        // 弹窗一出现，遥控器必须有一个确定能按的落点；内容项由方向键自然可达。
        LaunchedEffect(Unit) {
            repeat(12) {
                if (closeFocus.tryRequestFocus()) return@LaunchedEffect
                withFrameNanos { }
            }
        }
        // 触底续页：提前一整行发起，滑到底时下一批已就位；和演员弹窗一样无限滑，不再点「加载更多」。
        val columns = if (ui.compact) 4 else 6
        LaunchedEffect(grid, state.works.size, state.hasMore) {
            if (state.works.isEmpty() || !state.hasMore) return@LaunchedEffect
            snapshotFlow { grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
                .collect { last ->
                    if (last >= state.works.size - columns) onLoadMoreWorks()
                }
        }
        Box(
            Modifier.fillMaxSize().background(Color(0xA8060910)),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier
                    .width(cardWidth)
                    .height(cardHeight)
                    .clip(RoundedCornerShape(d(28)))
                    .background(Brush.verticalGradient(listOf(Color(0xFF171B25), Color(0xFF0B0E14))))
                    .border(d(1f), Color(0x24FFFFFF), RoundedCornerShape(d(28)))
                    .padding(horizontal = d(34), vertical = d(28)),
            ) {
                SheetTopBar(title = "作者信息", onClose = onClose, closeFocus = closeFocus)
                Spacer(Modifier.height(d(22)))

                val creator = state.creator
                if (creator == null) {
                    Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                        if (state.loading) LoadingState(label = "正在加载作者信息")
                        else EmptyState("暂无作者信息", "这部剧暂时没有收录到发布作者。")
                    }
                } else {
                    CreatorIdentityCard(
                        creator = creator,
                        onFollow = onFollow,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(d(20)))
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            "TA 的作品",
                            style = MaterialTheme.typography.headlineSmall,
                            color = Paper.Primary,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.width(d(10)))
                        Text(
                            "${if (creator.worksCount > 0) creator.worksCount else state.works.size} 部 · 选中查看",
                            style = MaterialTheme.typography.bodySmall,
                            color = Paper.Tertiary,
                            modifier = Modifier.padding(bottom = d(2)),
                        )
                    }
                    Spacer(Modifier.height(d(14)))
                    Box(Modifier.fillMaxWidth().weight(1f)) {
                        when {
                            state.loadingWorks && state.works.isEmpty() ->
                                LoadingState(label = "正在加载作品")

                            state.error != null && state.works.isEmpty() ->
                                ErrorState(state.error, onLoadMoreWorks)

                            state.works.isEmpty() ->
                                EmptyState("暂无作品", "这位作者暂时没有收录到的作品。")

                            else -> LazyVerticalGrid(
                                columns = GridCells.Fixed(columns),
                                state = grid,
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(bottom = d(8)),
                                verticalArrangement = Arrangement.spacedBy(d(20)),
                                horizontalArrangement = Arrangement.spacedBy(d(14)),
                            ) {
                                gridItems(state.works, key = { it.id }) { series ->
                                    PosterCard(
                                        series = series,
                                        onClick = { onWork(series) },
                                        fillWidth = true,
                                        subtitle = series.tags.take(14).ifBlank { series.badge },
                                    )
                                }
                                // 触底自动翻页，只在加载中显示一行轻提示；没有可点的「加载更多」按钮。
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

/**
 * 剧评弹窗：详情页「剧评」胶囊打开。公开评论列表，可翻页。
 * 每条剧评可聚焦，遥控器上下键能滚动列表；返回键关闭。
 */
@Composable
fun ReviewSheet(
    state: ReviewState,
    onClose: () -> Unit,
    onLoadMore: () -> Unit,
) {
    val config = LocalConfiguration.current
    val cardWidth = minOf((config.screenWidthDp * 0.60f).dp, d(900))
    val cardHeight = minOf((config.screenHeightDp * 0.84f).dp, d(820))
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = false,
            dismissOnBackPress = true,
        ),
    ) {
        val closeFocus = remember { FocusRequester() }
        val list = rememberLazyListState()
        LaunchedEffect(Unit) {
            repeat(12) {
                if (closeFocus.tryRequestFocus()) return@LaunchedEffect
                withFrameNanos { }
            }
        }
        // 触底续页：滑到最后几条时自动加载下一批，和演员弹窗一样无限滑。
        LaunchedEffect(list, state.items.size, state.hasMore) {
            if (state.items.isEmpty() || !state.hasMore) return@LaunchedEffect
            snapshotFlow { list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
                .collect { last ->
                    if (last >= state.items.size - 3) onLoadMore()
                }
        }
        Box(
            Modifier.fillMaxSize().background(Color(0xA8060910)),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier
                    .width(cardWidth)
                    .height(cardHeight)
                    .clip(RoundedCornerShape(d(28)))
                    .background(Brush.verticalGradient(listOf(Color(0xFF171B25), Color(0xFF0B0E14))))
                    .border(d(1f), Color(0x24FFFFFF), RoundedCornerShape(d(28)))
                    .padding(horizontal = d(32), vertical = d(28)),
            ) {
                SheetTopBar(
                    title = "剧评",
                    meta = if (state.total > 0) "共 ${state.total} 条" else null,
                    onClose = onClose,
                    closeFocus = closeFocus,
                )
                Spacer(Modifier.height(d(20)))
                Box(Modifier.fillMaxWidth().weight(1f)) {
                    when {
                        state.loading && state.items.isEmpty() ->
                            LoadingState(label = "正在加载剧评")

                        state.error != null && state.items.isEmpty() ->
                            ErrorState(state.error, onLoadMore)

                        state.items.isEmpty() ->
                            EmptyState("暂无剧评", "这部剧还没有公开剧评。")

                        else -> LazyColumn(
                            state = list,
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(d(14)),
                            contentPadding = PaddingValues(bottom = d(8)),
                        ) {
                            items(state.items) { review ->
                                ReviewRow(review = review, modifier = Modifier.focusable())
                            }
                            // 触底自动翻页，只在加载中显示一行轻提示；没有可点的「加载更多」按钮。
                            if (state.loadingMore) {
                                item {
                                    Box(
                                        Modifier.fillMaxWidth().padding(vertical = d(8)),
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

/** 弹窗通用顶栏：品类色条 + 标题 + 计数 + 关闭按钮。 */
@Composable
private fun SheetTopBar(
    title: String,
    meta: String? = null,
    onClose: () -> Unit,
    closeFocus: FocusRequester,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .width(d(42))
                .height(d(3))
                .clip(RoundedCornerShape(d(99)))
                .background(Brush.horizontalGradient(listOf(Rouge.Bright, Gold.Core))),
        )
        Spacer(Modifier.width(d(12)))
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            color = Rouge.Bright,
            fontWeight = FontWeight.SemiBold,
        )
        if (!meta.isNullOrBlank()) {
            Spacer(Modifier.width(d(10)))
            Text(meta, style = MaterialTheme.typography.labelSmall, color = Paper.Tertiary)
        }
        Spacer(Modifier.weight(1f))
        Text("返回键关闭", style = MaterialTheme.typography.labelSmall, color = Paper.Tertiary)
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
}

/** 作者身份卡：头像 / 昵称 / 作品数 / 关注按钮。 */
@Composable
private fun CreatorIdentityCard(
    creator: Creator,
    onFollow: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val following = creator.following == true
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(d(16)))
            .background(
                Brush.linearGradient(
                    listOf(Ink.SurfaceHigh, Ink.Surface),
                ),
            )
            .border(
                d(if (focused) 2f else 1f),
                if (focused) Rouge.Bright else Color(0x1FFFFFFF),
                RoundedCornerShape(d(16)),
            )
            .padding(d(16)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SmartImage(
            url = creator.avatar,
            modifier = Modifier
                .size(d(72))
                .clip(CircleShape)
                .border(d(1.6f), Gold.Deep, CircleShape),
            fallbackTitle = creator.name.take(1).ifBlank { "作" },
        )
        Spacer(Modifier.width(d(16)))
        Column(Modifier.weight(1f)) {
            Text(
                creator.name.ifBlank { "红果作者" },
                style = MaterialTheme.typography.titleMedium,
                color = Paper.Pure,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(d(4)))
            val meta = buildList {
                if (creator.worksCount > 0) add("${creator.worksCount} 部作品")
                add(if (creator.relativeType == 3) "作者本人" else "关联创作者")
            }.joinToString(" · ")
            Text(meta, style = MaterialTheme.typography.labelMedium, color = Paper.Secondary, maxLines = 1)
            if (creator.description.isNotBlank()) {
                Spacer(Modifier.height(d(4)))
                Text(
                    creator.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = Paper.Tertiary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(d(12)))
        FollowButton(following = following, onClick = onFollow, interactionSource = interaction)
    }
}

@Composable
private fun FollowButton(
    following: Boolean,
    onClick: () -> Unit,
    interactionSource: MutableInteractionSource,
) {
    val focused by interactionSource.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (focused) 1.06f else 1f, tween(140), label = "followFocus")
    Row(
        Modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(d(20)))
            .background(if (following) Ink.SurfaceTop else Rouge.Deep)
            .border(d(1f), if (focused) Rouge.Bright else Color.Transparent, RoundedCornerShape(d(20)))
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .padding(horizontal = d(18), vertical = d(9)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (following) Icons.Rounded.Check else Icons.Rounded.Add,
            contentDescription = null,
            tint = Paper.Pure,
            modifier = Modifier.size(d(16)),
        )
        Spacer(Modifier.width(d(6)))
        Text(
            if (following) "已关注" else "关注",
            style = MaterialTheme.typography.labelLarge,
            color = Paper.Pure,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun ReviewRow(review: Review, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(d(12)))
            .background(Ink.Surface)
            .focusable()
            .padding(d(14)),
    ) {
        SmartImage(
            url = review.userAvatar,
            modifier = Modifier
                .size(d(40))
                .clip(CircleShape)
                .border(d(1f), Color(0x26FFFFFF), CircleShape),
            fallbackTitle = review.userName.take(1).ifBlank { "评" },
        )
        Spacer(Modifier.width(d(12)))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    review.userName,
                    style = MaterialTheme.typography.labelLarge,
                    color = Paper.Primary,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (review.createTime > 0) {
                    Spacer(Modifier.width(d(8)))
                    Text(
                        formatReviewTime(review.createTime),
                        style = MaterialTheme.typography.labelSmall,
                        color = Paper.Tertiary,
                    )
                }
            }
            Spacer(Modifier.height(d(6)))
            Text(
                review.text,
                style = MaterialTheme.typography.bodyMedium,
                color = Paper.Secondary,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
            if (review.diggCount > 0 || review.replyCount > 0) {
                Spacer(Modifier.height(d(8)))
                val stats = buildList {
                    if (review.diggCount > 0) add("${review.diggCount} 赞")
                    if (review.replyCount > 0) add("${review.replyCount} 回复")
                }.joinToString("  ·  ")
                Text(stats, style = MaterialTheme.typography.labelSmall, color = Paper.Tertiary)
            }
        }
    }
}

private fun formatReviewTime(seconds: Long): String {
    val millis = if (seconds < 1_000_000_000_000L) seconds * 1000 else seconds
    val delta = System.currentTimeMillis() - millis
    val day = 86_400_000L
    return when {
        delta < 3_600_000L -> "${(delta / 3_600_000L).coerceAtLeast(1)} 小时前"
        delta < day -> "${delta / 3_600_000L} 小时前"
        delta < 30 * day -> "${delta / day} 天前"
        else -> java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.CHINA).format(java.util.Date(millis))
    }
}
