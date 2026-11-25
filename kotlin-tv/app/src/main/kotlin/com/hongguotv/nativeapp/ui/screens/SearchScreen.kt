// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.automirrored.rounded.TrendingUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hongguotv.core.ContentType
import com.hongguotv.core.Series
import com.hongguotv.nativeapp.ui.components.ButtonTone
import com.hongguotv.nativeapp.ui.components.EmptyState
import com.hongguotv.nativeapp.ui.components.LoadingState
import com.hongguotv.nativeapp.ui.components.PosterCard
import com.hongguotv.nativeapp.ui.components.RoundIconButton
import com.hongguotv.nativeapp.ui.components.SelectChip
import com.hongguotv.nativeapp.ui.components.TvButton
import com.hongguotv.nativeapp.ui.components.tryRequestFocus
import com.hongguotv.nativeapp.ui.data.SearchState
import com.hongguotv.nativeapp.ui.theme.Ink
import com.hongguotv.nativeapp.ui.theme.Paper
import com.hongguotv.nativeapp.ui.theme.Rouge
import com.hongguotv.nativeapp.ui.theme.d
import com.hongguotv.nativeapp.ui.theme.ui

@Composable
fun SearchScreen(
    state: SearchState,
    onQuery: (String) -> Unit,
    onSubmit: (String) -> Unit,
    onType: (ContentType?) -> Unit,
    onKeyboard: () -> Unit,
    onLoadMore: () -> Unit,
    onClearHistory: () -> Unit,
    onClear: () -> Unit,
    onHistory: (String, ContentType) -> Unit,
    onDetail: (Series) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val gridState = rememberLazyGridState()
    val focusManager = LocalFocusManager.current
    val ime = LocalSoftwareKeyboardController.current
    var fieldFocused by remember { mutableStateOf(false) }
    val shouldLoadMore by remember {
        derivedStateOf {
            val last = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= gridState.layoutInfo.totalItemsCount - 8
        }
    }
    androidx.compose.runtime.LaunchedEffect(shouldLoadMore, state.results.size) {
        if (shouldLoadMore && state.submitted) onLoadMore()
    }

    // 返回键分层：先退出输入态（收起系统输入法），再收起屏幕键盘，最后才离开搜索页。
    BackHandler {
        when {
            fieldFocused -> {
                focusManager.clearFocus()
                ime?.hide()
            }
            state.keyboardVisible -> onKeyboard()
            else -> onBack()
        }
    }

    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = ui.gutter, end = ui.gutter, top = d(26)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 原来的返回按钮改成键盘开关：返回交给遥控器返回键，位置让给更常用的输入。
                KeyboardToggle(
                    active = state.keyboardVisible,
                    onClick = {
                        focusManager.clearFocus()
                        ime?.hide()
                        onKeyboard()
                    },
                )
                Spacer(Modifier.width(d(16)))
                SearchField(
                    query = state.query,
                    onQuery = onQuery,
                    onClear = onClear,
                    onFocusChanged = { fieldFocused = it },
                    modifier = Modifier.widthIn(min = d(420), max = d(760)).weight(1f),
                )
                Spacer(Modifier.width(d(14)))
                TvButton("搜索", onClick = { onSubmit(state.query) }, tone = ButtonTone.Primary)
            }

            if (!state.submitted) {
                IdleSearchBody(state, onHistory, onSubmit, onClearHistory)
            } else {
                Row(
                    Modifier.padding(start = ui.gutter, end = ui.gutter, top = d(18)),
                    horizontalArrangement = Arrangement.spacedBy(d(10)),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SelectChip("全部", state.type == null, onClick = { onType(null) })
                    SelectChip("短剧", state.type == ContentType.SHORT, onClick = { onType(ContentType.SHORT) })
                    SelectChip("漫剧", state.type == ContentType.COMIC, onClick = { onType(ContentType.COMIC) })
                    Spacer(Modifier.weight(1f))
                    if (state.results.isNotEmpty()) {
                        Text("找到 ${state.results.size} 部相关作品", style = MaterialTheme.typography.labelSmall, color = Paper.Tertiary)
                    }
                }
                when {
                    state.searching && state.results.isEmpty() -> LoadingState(label = "正在搜索「${state.query}」")
                    state.error != null && state.results.isEmpty() -> EmptyState(
                        title = "没有搜到结果",
                        subtitle = state.error.orEmpty(),
                        action = { TvButton("换个关键词", { onClear() }, tone = ButtonTone.Ghost) },
                    )
                    state.results.isEmpty() -> EmptyState(
                        title = "没有找到「${state.query}」",
                        subtitle = "试试更短的关键词，或用拼音首字母搜索。",
                        action = { TvButton("重新搜索", { onClear() }, tone = ButtonTone.Ghost) },
                    )
                    else -> LazyVerticalGrid(
                        state = gridState,
                        columns = GridCells.Fixed(if (ui.compact) 5 else 6),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = ui.gutter, end = ui.gutter, top = d(18), bottom = d(190)),
                        horizontalArrangement = Arrangement.spacedBy(d(16)),
                        verticalArrangement = Arrangement.spacedBy(d(24)),
                    ) {
                        items(state.results, key = { it.id }) { series ->
                            PosterCard(
                                series = series,
                                onClick = { onDetail(series) },
                                fillWidth = true,
                                subtitle = series.tags.take(14).ifBlank { series.badge },
                            )
                        }
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = state.keyboardVisible,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            KeyboardPanel(
                onKey = { onQuery(state.query + it) },
                onDelete = { onQuery(state.query.dropLast(1)) },
                onSearch = { onSubmit(state.query) },
                onClose = onKeyboard,
            )
        }
    }
}

/** 屏幕键盘开关。展开时点亮成胭脂红，和输入框的聚焦描边同一语言。 */
@Composable
private fun KeyboardToggle(active: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (focused) 1.08f else 1f, spring(0.62f, 620f), label = "kbd")
    val bg by animateColorAsState(
        when {
            active -> Rouge.Core
            focused -> Color(0x26FFFFFF)
            else -> Color(0x14FFFFFF)
        },
        label = "kbd-bg",
    )
    val line by animateColorAsState(
        when {
            active -> Color.White.copy(alpha = 0.75f)
            focused -> Rouge.Bright
            else -> Ink.LineSoft
        },
        label = "kbd-line",
    )
    Box(
        Modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .size(d(52))
            .clip(RoundedCornerShape(d(16)))
            .background(bg)
            .border(d(1.4f), line, RoundedCornerShape(d(16)))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Rounded.Keyboard,
            "屏幕键盘",
            tint = if (active) Color.White else Paper.Primary,
            modifier = Modifier.size(d(22)),
        )
    }
}

@Composable
private fun SearchField(
    query: String,
    onQuery: (String) -> Unit,
    onClear: () -> Unit,
    onFocusChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    Row(
        modifier
            .height(d(52))
            .clip(RoundedCornerShape(d(999)))
            .background(if (focused) Color(0x1FFFFFFF) else Color(0x0DFFFFFF))
            .border(d(1.4f), if (focused) Rouge.Bright else Ink.LineSoft, RoundedCornerShape(d(999)))
            .padding(horizontal = d(20)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Search, null, tint = if (focused) Rouge.Bright else Paper.Tertiary, modifier = Modifier.size(d(20)))
        Spacer(Modifier.width(d(12)))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (query.isEmpty()) {
                Text(
                    "搜索剧名、演员，或输入拼音首字母",
                    style = MaterialTheme.typography.bodyLarge,
                    color = Paper.Tertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            BasicTextField(
                value = query,
                onValueChange = onQuery,
                singleLine = true,
                textStyle = MaterialTheme.typography.titleLarge.copy(color = Paper.Pure, fontWeight = FontWeight.Medium),
                cursorBrush = SolidColor(Rouge.Bright),
                interactionSource = interaction,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    // 遥控器选中输入框即唤起系统输入法；没有系统输入法的设备仍可用屏幕键盘。
                    .onFocusChanged { info ->
                        onFocusChanged(info.isFocused)
                        if (info.isFocused) keyboard?.show()
                    },
            )
        }
        if (query.isNotEmpty()) {
            Spacer(Modifier.width(d(8)))
            Box(
                Modifier
                    .size(d(28))
                    .clip(RoundedCornerShape(999.dp))
                    .background(Color(0x1AFFFFFF))
                    .clickable(onClick = onClear),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Close, "清空", tint = Paper.Secondary, modifier = Modifier.size(d(16)))
            }
        }
    }
}

@Composable
private fun IdleSearchBody(
    state: SearchState,
    onHistory: (String, ContentType) -> Unit,
    onSubmit: (String) -> Unit,
    onClearHistory: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(start = ui.gutter, end = ui.gutter, top = d(26)),
        verticalArrangement = Arrangement.spacedBy(d(26)),
    ) {
        if (state.suggestions.isNotEmpty()) {
            Column {
                SectionLabel("搜索建议", Icons.Rounded.Search)
                Spacer(Modifier.height(d(12)))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(d(10))) {
                    items(state.suggestions) { word ->
                        SelectChip(word, selected = false, onClick = { onSubmit(word) })
                    }
                }
            }
        }
        if (state.history.isNotEmpty()) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionLabel("最近搜索", Icons.Rounded.History)
                    Spacer(Modifier.weight(1f))
                    TvButton("清空", onClearHistory, tone = ButtonTone.Quiet)
                }
                Spacer(Modifier.height(d(12)))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(d(10))) {
                    items(state.history) { entry ->
                        SelectChip(
                            text = "${entry.type.label} · ${entry.query}",
                            selected = false,
                            onClick = { onHistory(entry.query, entry.type) },
                        )
                    }
                }
            }
        }
        Column {
            SectionLabel("大家都在搜", Icons.AutoMirrored.Rounded.TrendingUp)
            Spacer(Modifier.height(d(14)))
            if (state.hot.isEmpty() && state.hotLoading) {
                LoadingState(Modifier.height(d(220)))
            } else {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(d(14))) {
                    items(state.hot, key = { it.id }) { series ->
                        PosterCard(
                            series = series,
                            onClick = { onSubmit(series.title) },
                            width = ui.dp(152),
                            subtitle = series.badge.ifBlank { series.tags.take(12) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = Rouge.Bright, modifier = Modifier.size(d(18)))
        Spacer(Modifier.width(d(8)))
        Text(text, style = MaterialTheme.typography.titleMedium, color = Paper.Primary, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun KeyboardPanel(
    onKey: (String) -> Unit,
    onDelete: () -> Unit,
    onSearch: () -> Unit,
    onClose: () -> Unit,
) {
    val rows = listOf("QWERTYUIOP", "ASDFGHJKL", "ZXCVBNM")
    // 键盘展开后焦点要落在正中间的键位（第二行 ASDFGHJKL 的第 5 个 = G）。
    // 面板是滑入动画，键位要几帧才 attach，单次请求容易落空，这里重试到成功。
    val centerKeyFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        repeat(16) {
            if (centerKeyFocus.tryRequestFocus()) return@LaunchedEffect
            withFrameNanos { }
        }
    }
    Column(
        Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color(0xF210131A), Color(0xFA080A10))))
            .border(d(1f), Color(0x1FFFFFFF), RoundedCornerShape(topStart = d(26), topEnd = d(26)))
            .padding(horizontal = d(40), vertical = d(18)),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(d(8)),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("屏幕键盘 · 支持拼音首字母", style = MaterialTheme.typography.labelSmall, color = Paper.Tertiary)
            Spacer(Modifier.weight(1f))
            RoundIconButton(Icons.Rounded.Close, onClose, size = d(32), iconSize = d(16), contentDescription = "收起键盘")
        }
        rows.forEachIndexed { rowIndex, row ->
            Row(horizontalArrangement = Arrangement.spacedBy(d(7))) {
                if (rowIndex == 2) {
                    KeyboardKey(label = "删除", wide = true, icon = Icons.AutoMirrored.Rounded.Backspace, onClick = onDelete)
                }
                row.forEachIndexed { colIndex, letter ->
                    // 第二行（ASDFGHJKL）正中间第 5 个键 G：rowIndex=1、colIndex=4。
                    val isCenter = rowIndex == 1 && colIndex == 4
                    KeyboardKey(
                        letter.toString(),
                        onClick = { onKey(letter.lowercase()) },
                        focusRequester = if (isCenter) centerKeyFocus else null,
                    )
                }
                if (rowIndex == 2) {
                    KeyboardKey(label = "搜索", wide = true, primary = true, onClick = onSearch)
                }
            }
        }
    }
}

@Composable
private fun KeyboardKey(
    label: String,
    onClick: () -> Unit,
    wide: Boolean = false,
    primary: Boolean = false,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    focusRequester: FocusRequester? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Box(
        Modifier
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .width(if (wide) d(92) else d(52))
            .height(d(44))
            .clip(RoundedCornerShape(d(10)))
            .background(
                when {
                    primary -> Brush.horizontalGradient(listOf(Rouge.Bright, Rouge.Deep))
                    focused -> Brush.horizontalGradient(listOf(Color(0x33FFFFFF), Color(0x1FFFFFFF)))
                    else -> Brush.horizontalGradient(listOf(Color(0x0FFFFFFF), Color(0x08FFFFFF)))
                },
            )
            .border(d(1.2f), if (focused) Rouge.Bright else Color(0x14FFFFFF), RoundedCornerShape(d(10)))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (icon != null) {
            Icon(icon, label, tint = Paper.Secondary, modifier = Modifier.size(d(18)))
        } else {
            Text(
                label,
                style = MaterialTheme.typography.titleSmall,
                color = if (primary) Color.White else Paper.Primary,
            )
        }
    }
}
