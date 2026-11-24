// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.FilterAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import com.hongguotv.nativeapp.ui.components.tryRequestFocus
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hongguotv.core.ContentType
import com.hongguotv.core.Series
import com.hongguotv.core.TheaterFilterGroup
import com.hongguotv.nativeapp.ui.components.ErrorState
import com.hongguotv.nativeapp.ui.components.LoadingState
import com.hongguotv.nativeapp.ui.components.PosterCard
import com.hongguotv.nativeapp.ui.components.SelectChip
import com.hongguotv.nativeapp.ui.data.TheaterState
import com.hongguotv.nativeapp.ui.theme.Ink
import com.hongguotv.nativeapp.ui.theme.Paper
import com.hongguotv.nativeapp.ui.theme.Rouge
import com.hongguotv.nativeapp.ui.theme.d
import com.hongguotv.nativeapp.ui.theme.ui
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged

/** 剧场的三个主类型。剧场语境里「短剧」就是真人剧，用更明确的说法。 */
private val theaterTypes = listOf(ContentType.SHORT, ContentType.COMIC, ContentType.AI)

/** 距离末尾还有这么多项时就开始取下一页，避免用户真的滑到底才等网络。 */
private const val PREFETCH_DISTANCE = 12

private val ContentType.theaterLabel: String
    get() = when (this) {
        ContentType.SHORT -> "真人剧"
        else -> label
    }

@Composable
fun TheaterScreen(
    state: TheaterState,
    posterColumns: Int = 0,
    onType: (ContentType?) -> Unit,
    onFilter: (TheaterFilterGroup, String) -> Unit,
    onResetFilters: () -> Unit,
    onLoadMore: () -> Unit,
    onDetail: (Series) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    entryFocus: FocusRequester? = null,
    /** 控制带按「上」回本页导航项。 */
    upFocus: FocusRequester? = null,
) {
    val gridState = rememberLazyGridState()
    // 筛选默认收起：主页面只留一条控制带，海报第一时间就能看到。
    var filtersOpen by rememberSaveable { mutableStateOf(false) }
    // 无限滚动：直接订阅布局信息，滚到末行就补一页。
    // 不用 distinctUntilChanged：加载完一页后如果用户还在底部，状态仍是 nearEnd，
    // 去重会把它当成「没变化」而不再触发，表现就是滚到底要手动再动一下才继续。
    // 重复调用由 ViewModel 的 loading/loadingMore/hasMore 守卫拦掉。
    LaunchedEffect(gridState, state.hasMore, state.items.size, state.page, state.type) {
        if (!state.hasMore) return@LaunchedEffect
        snapshotFlow {
            val info = gridState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: return@snapshotFlow false
            state.items.isNotEmpty() && last >= info.totalItemsCount - PREFETCH_DISTANCE
        }.collect { nearEnd -> if (nearEnd) onLoadMore() }
    }

    // 换类型/换筛选后回到顶部，否则旧列表的滚动位置会留在半截，看起来像「跳到了别的页」。
    LaunchedEffect(state.type, state.selection) {
        gridState.scrollToItem(0)
    }

    // 顶部已有主类型切换，筛选面板里不再重复「体裁」这一维。
    val groups = remember(state.groups) {
        state.groups.filter { group ->
            val label = group.label
            !label.contains("体裁") && !label.contains("类型") && group.type != "genre"
        }
    }
    val selectedCount = state.selection.count { it.value.isNotBlank() }

    val columns = if (posterColumns in 3..8) posterColumns else if (ui.compact) 5 else 6
    // 类型 chip 的稳定落点：切类型后整屏海报全换 id，原焦点节点必然销毁，
    // 与其赌 focusRestorer 能找到邻居，不如把焦点明确交回「刚点的那个类型」。
    val typeFoci = remember(entryFocus) {
        theaterTypes.associateWith { type ->
            // 第一个类型的落点和导航栏「按下」的目标是同一个节点，复用同一个 requester，
            // 避免一个节点挂两个 requester 时互相覆盖。
            if (type == theaterTypes.first() && entryFocus != null) entryFocus else FocusRequester()
        }
    }
    LaunchedEffect(state.type) {
        repeat(6) {
            val target = typeFoci[state.type]
            if (target != null && target.tryRequestFocus()) return@LaunchedEffect
            withFrameNanos { }
        }
    }

    Box(modifier.fillMaxSize()) {
        when {
            state.loading && state.items.isEmpty() -> LoadingState(label = "正在整理剧场")
            state.error != null && state.items.isEmpty() -> ErrorState(state.error, onRetry)
            else -> LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Fixed(columns),
                modifier = Modifier.fillMaxSize().focusGroup().focusRestorer(),
                contentPadding = PaddingValues(start = ui.gutter, end = ui.gutter, top = d(96), bottom = d(60)),
                horizontalArrangement = Arrangement.spacedBy(d(16)),
                verticalArrangement = Arrangement.spacedBy(d(26)),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column {
                        Row(verticalAlignment = Alignment.Bottom) {
                            Column {
                                Text("剧场", style = MaterialTheme.typography.displaySmall, color = Paper.Pure, fontWeight = FontWeight.Bold)
                                Spacer(Modifier.height(d(6)))
                                Text(
                                    "先选类型，再按题材、年代、受众细挑。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Paper.Tertiary,
                                )
                            }
                            Spacer(Modifier.weight(1f))
                            if (state.items.isNotEmpty()) {
                                Text(
                                    "共 ${state.items.size} 部",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Paper.Tertiary,
                                )
                            }
                        }
                        Spacer(Modifier.height(d(18)))
                        TheaterControlBar(
                            activeType = state.type,
                            onType = onType,
                            filtersOpen = filtersOpen,
                            selectedCount = selectedCount,
                            onToggleFilters = { filtersOpen = !filtersOpen },
                            entryFocus = entryFocus,
                            upFocus = upFocus,
                            typeFoci = typeFoci,
                        )
                        if (state.loading && state.items.isNotEmpty()) {
                            Spacer(Modifier.height(d(10)))
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .height(d(2))
                                    .clip(RoundedCornerShape(99.dp))
                                    .background(Color(0x14FFFFFF)),
                            ) {
                                Box(
                                    Modifier
                                        .fillMaxWidth(0.35f)
                                        .height(d(2))
                                        .clip(RoundedCornerShape(99.dp))
                                        .background(Rouge.Bright),
                                )
                            }
                        }
                        AnimatedVisibility(
                            visible = filtersOpen,
                            enter = expandVertically() + fadeIn(),
                            exit = shrinkVertically() + fadeOut(),
                        ) {
                            FilterPanel(
                                groups = groups,
                                selection = state.selection,
                                onFilter = onFilter,
                                onResetAll = onResetFilters,
                            )
                        }
                        Spacer(Modifier.height(d(18)))
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(d(1))
                                .background(Ink.LineSoft),
                        )
                    }
                }
                items(state.items, key = { it.id }) { series ->
                    PosterCard(
                        series = series,
                        onClick = { onDetail(series) },
                        fillWidth = true,
                        subtitle = series.tags.take(14).ifBlank { series.badge },
                    )
                }
                // 加载中的占位直接铺一行「灰海报」，比一行「正在加载更多…」更像内容、
                // 也让等待有个明确的视觉预期；到底了才用文字收尾。
                if (state.loadingMore) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Row(
                            Modifier.fillMaxWidth().padding(top = d(4)),
                            horizontalArrangement = Arrangement.spacedBy(d(16)),
                        ) {
                            repeat(columns) {
                                Box(
                                    Modifier
                                        .weight(1f)
                                        .aspectRatio(2f / 3f)
                                        .clip(RoundedCornerShape(d(14)))
                                        .background(Color(0x0FFFFFFF)),
                                )
                            }
                        }
                    }
                } else if (!state.hasMore) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Box(Modifier.fillMaxWidth().padding(d(20)), contentAlignment = Alignment.Center) {
                            Text(
                                "已经到底了 · 换个筛选试试",
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

/**
 * 控制带：左侧主类型分段、右侧筛选入口。
 * 一整块磨砂玻璃，替代原先「类型一行 + 四行筛选」的堆叠，海报能提前一屏出现。
 */
@Composable
private fun TheaterControlBar(
    activeType: ContentType,
    onType: (ContentType?) -> Unit,
    filtersOpen: Boolean,
    selectedCount: Int,
    onToggleFilters: () -> Unit,
    entryFocus: FocusRequester? = null,
    upFocus: FocusRequester? = null,
    typeFoci: Map<ContentType, FocusRequester> = emptyMap(),
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(d(999)))
            .background(
                Brush.horizontalGradient(listOf(Color(0x14FFFFFF), Color(0x0AFFFFFF), Color(0x06FFFFFF))),
            )
            .border(d(1f), Color(0x1AFFFFFF), RoundedCornerShape(d(999)))
            .padding(d(6)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        theaterTypes.forEach { type ->
            TypeTab(
                text = type.theaterLabel,
                selected = activeType == type,
                onClick = { onType(type) },
                modifier = Modifier
                    .then(typeFoci[type]?.let { Modifier.focusRequester(it) } ?: Modifier)
                    .then(if (upFocus != null) Modifier.focusProperties { up = upFocus } else Modifier),
            )
        }
        Spacer(Modifier.weight(1f))
        Box(
            Modifier
                .padding(horizontal = d(4))
                .width(d(1))
                .height(d(22))
                .background(Color(0x1FFFFFFF)),
        )
        Spacer(Modifier.width(d(10)))
        FilterEntry(
            expanded = filtersOpen,
            count = selectedCount,
            onClick = onToggleFilters,
            upFocus = upFocus,
        )
    }
}

@Composable
private fun TypeTab(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (focused) 1.05f else 1f, spring(0.65f, 600f), label = "type-tab")
    Box(
        modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(d(999)))
            .background(
                when {
                    selected -> Brush.horizontalGradient(listOf(Rouge.Bright, Rouge.Core, Rouge.Deep))
                    focused -> Brush.horizontalGradient(listOf(Color(0x24FFFFFF), Color(0x14FFFFFF)))
                    else -> Brush.horizontalGradient(listOf(Color.Transparent, Color.Transparent))
                },
            )
            .border(
                d(1.2f),
                when {
                    selected -> Color.White.copy(alpha = 0.55f)
                    focused -> Rouge.Bright
                    else -> Color.Transparent
                },
                RoundedCornerShape(d(999)),
            )
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = d(24), vertical = d(11)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.titleSmall,
            color = if (selected || focused) Color.White else Paper.Secondary,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
        )
    }
}

@Composable
private fun FilterEntry(expanded: Boolean, count: Int, onClick: () -> Unit, upFocus: FocusRequester? = null) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val icon = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore
    Row(
        Modifier
            .then(if (upFocus != null) Modifier.focusProperties { up = upFocus } else Modifier)
            .clip(RoundedCornerShape(d(999)))
            .background(if (focused) Color(0x1FFFFFFF) else Color.Transparent)
            .border(d(1.2f), if (focused) Rouge.Bright else Color.Transparent, RoundedCornerShape(d(999)))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(start = d(16), end = d(12), top = d(11), bottom = d(11)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.FilterAlt, null, tint = if (focused || expanded) Rouge.Bright else Paper.Secondary, modifier = Modifier.size(d(17)))
        Spacer(Modifier.width(d(8)))
        Text(
            if (count > 0) "筛选 · $count" else "筛选",
            style = MaterialTheme.typography.titleSmall,
            color = if (focused || expanded || count > 0) Paper.Pure else Paper.Secondary,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.width(d(6)))
        Icon(icon, null, tint = Paper.Tertiary, modifier = Modifier.size(d(16)))
    }
}

/** 展开后的筛选面板：每组一行，组名固定宽度，选项横向滚动。 */
@Composable
private fun FilterPanel(
    groups: List<TheaterFilterGroup>,
    selection: Map<String, String>,
    onFilter: (TheaterFilterGroup, String) -> Unit,
    onResetAll: () -> Unit,
) {
    if (groups.isEmpty()) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(top = d(12))
                .clip(RoundedCornerShape(d(20)))
                .background(Color(0x0FFFFFFF))
                .border(d(1f), Color(0x14FFFFFF), RoundedCornerShape(d(20)))
                .padding(horizontal = d(20), vertical = d(18)),
        ) {
            Text(
                "当前分类没有更多可筛选条件，换个类型试试。",
                style = MaterialTheme.typography.bodySmall,
                color = Paper.Tertiary,
            )
        }
        return
    }
    val hasSelection = selection.any { it.value.isNotBlank() }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = d(12))
            .clip(RoundedCornerShape(d(20)))
            .background(Color(0x0FFFFFFF))
            .border(d(1f), Color(0x14FFFFFF), RoundedCornerShape(d(20)))
            .padding(horizontal = d(20), vertical = d(16)),
        verticalArrangement = Arrangement.spacedBy(d(10)),
    ) {
        groups.forEach { group ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (selection[group.type].isNullOrBlank()) group.label else "${group.label} · 已选",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (selection[group.type].isNullOrBlank()) Paper.Tertiary else Rouge.Bright,
                    modifier = Modifier.width(d(92)),
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(d(8))) {
                    item {
                        SelectChip(
                            text = "全部",
                            selected = selection[group.type].isNullOrBlank(),
                            onClick = { onFilter(group, "") },
                            accent = Rouge.Core,
                        )
                    }
                    items(group.options, key = { it.id }) { option ->
                        SelectChip(
                            text = option.label,
                            selected = selection[group.type] == option.id,
                            onClick = { onFilter(group, option.id) },
                        )
                    }
                }
            }
        }
        if (hasSelection) {
            Row(
                Modifier.fillMaxWidth().padding(top = d(4)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("已选 ${selection.count { it.value.isNotBlank() }} 个条件", style = MaterialTheme.typography.labelSmall, color = Paper.Tertiary)
                Spacer(Modifier.weight(1f))
                SelectChip(text = "重置全部", selected = false, onClick = onResetAll, accent = Rouge.Core)
            }
        }
    }
}
