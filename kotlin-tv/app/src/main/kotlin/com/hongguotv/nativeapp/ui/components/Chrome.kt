// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBackIosNew
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hongguotv.nativeapp.ui.data.ApiStatusState
import com.hongguotv.nativeapp.ui.nav.Tab
import com.hongguotv.nativeapp.ui.theme.Gold
import com.hongguotv.nativeapp.ui.theme.Ink
import com.hongguotv.nativeapp.ui.theme.Paper
import com.hongguotv.nativeapp.ui.theme.Rouge
import com.hongguotv.nativeapp.ui.theme.d
import com.hongguotv.nativeapp.ui.theme.ui

/** 品牌标记：四叶接口状态灯。四片叶同时承载四项接口状态，不再使用文字 logo。 */
@Composable
fun BrandMark(
    modifier: Modifier = Modifier,
    status: ApiStatusState = ApiStatusState(),
    onClick: () -> Unit = {},
) {
    ApiStatusBrandMark(
        state = status,
        onClick = onClick,
        modifier = modifier,
    )
}

/**
 * 顶栏。焦点所在项：磨砂玻璃胶囊 + 白字；
 * 当前页：底部一条极短的胭脂红指示条，两者可以同时存在，遥控器操作时不会迷失。
 */
@Composable
fun TopBar(
    current: Tab,
    onSelect: (Tab) -> Unit,
    onSearch: () -> Unit,
    onCast: () -> Unit = {},
    modifier: Modifier = Modifier,
    elevated: Boolean = false,
    clock: String = "",
    accountName: String? = null,
    onAccount: () -> Unit = {},
    apiStatus: ApiStatusState = ApiStatusState(),
    onApiStatus: () -> Unit = {},
    /** 当前页面的第一个可聚焦元素：选中项按「下」时直接落到这里，不依赖几何搜索。 */
    contentEntry: FocusRequester? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(
                if (elevated) Brush.verticalGradient(listOf(Ink.Deep.copy(alpha = 0.96f), Ink.Deep.copy(alpha = 0.78f), Color.Transparent))
                else Brush.verticalGradient(listOf(Ink.Deep.copy(alpha = 0.72f), Color.Transparent)),
            )
            .padding(horizontal = ui.gutter, vertical = d(18)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BrandMark(status = apiStatus, onClick = onApiStatus)
        Spacer(Modifier.width(d(30)))
        Row(horizontalArrangement = Arrangement.spacedBy(d(6)), verticalAlignment = Alignment.CenterVertically) {
            Tab.entries.filter { it.inTopBar }.forEach { tab ->
                NavItem(
                    tab = tab,
                    selected = tab == current,
                    onClick = { onSelect(tab) },
                    downEntry = if (tab == current) contentEntry else null,
                )
            }
        }
        Spacer(Modifier.weight(1f))
        if (clock.isNotBlank()) {
            Text(clock, style = MaterialTheme.typography.labelMedium, color = Paper.Tertiary)
            Spacer(Modifier.width(d(16)))
        }
        CastEntry(onCast)
        Spacer(Modifier.width(d(10)))
        RoundIconButton(Icons.Rounded.Search, onSearch, size = d(40), iconSize = d(18), contentDescription = "搜索")
        Spacer(Modifier.width(d(14)))
        AccountChip(accountName, onAccount)
    }
}

@Composable
private fun NavItem(
    tab: Tab,
    selected: Boolean,
    onClick: () -> Unit,
    downEntry: FocusRequester? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (focused) 1.06f else 1f, spring(0.65f, 600f), label = "nav")
    val bg by animateColorAsState(if (focused) Color(0x1FFFFFFF) else Color.Transparent, tween(150), label = "nav-bg")
    val color by animateColorAsState(
        when {
            focused -> Paper.Pure
            selected -> Paper.Pure
            else -> Paper.Secondary
        },
        tween(150),
        label = "nav-color",
    )
    Box(
        Modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(d(999)))
            .background(bg)
            .border(d(1f), if (focused) Color(0x33FFFFFF) else Color.Transparent, RoundedCornerShape(d(999)))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            // 选中项按「下」直接跳到页面首个可聚焦元素。默认几何搜索在 hero 这类
            // 大块内容前经常落空，表现就是「焦点卡在导航栏」。
            .then(
                if (downEntry != null) {
                    Modifier.onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown) {
                            downEntry.tryRequestFocus()
                        } else {
                            false
                        }
                    }
                } else {
                    Modifier
                },
            ),
    ) {
        // 结构与内边距分开：线不能跟着文字的 padding 一起被算进内容区，
        // 否则指示条会压在字上（上一版就是这个问题）。
        Column(
            modifier = Modifier.padding(start = d(16), end = d(16), top = d(10), bottom = d(11)),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                tab.label,
                style = MaterialTheme.typography.titleSmall,
                color = color,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            )
            Spacer(Modifier.height(d(7)))
            Box(
                Modifier
                    .width(d(16))
                    .height(d(2))
                    .clip(RoundedCornerShape(99.dp))
                    .background(if (selected) Rouge.Bright else Color.Transparent),
            )
        }
    }
}

@Composable
private fun CastEntry(onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (focused) 1.06f else 1f, spring(0.62f, 620f), label = "cast-entry")
    val line by animateColorAsState(if (focused) Gold.Core else Ink.LineSoft, tween(150), label = "cast-line")
    Row(
        Modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(d(999)))
            .background(if (focused) Color(0x1FE7BE7C) else Color(0x0DFFFFFF))
            .border(d(1.2f), line, RoundedCornerShape(d(999)))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(start = d(13), end = d(15), top = d(8), bottom = d(8)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 金色只给这一处：投播是「把手机内容带进来」的入口，
        // 和普通导航区分开，用户不用第二次找它在哪。
        Icon(Icons.Rounded.Cast, null, tint = Gold.Core, modifier = Modifier.size(d(17)))
        Spacer(Modifier.width(d(7)))
        Text(
            "投播",
            style = MaterialTheme.typography.labelSmall,
            color = if (focused) Paper.Pure else Paper.Secondary,
            fontWeight = if (focused) FontWeight.SemiBold else FontWeight.Medium,
        )
    }
}

@Composable
private fun AccountChip(name: String?, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Row(
        Modifier
            .clip(RoundedCornerShape(d(999)))
            .background(if (focused) Color(0x1FFFFFFF) else Color(0x0DFFFFFF))
            .border(d(1f), if (focused) Rouge.Bright else Ink.LineSoft, RoundedCornerShape(d(999)))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(start = d(6), end = d(13), top = d(5), bottom = d(5)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(d(24))
                .clip(CircleShape)
                .background(Brush.linearGradient(listOf(Color(0xFF3A4152), Color(0xFF1D2230)))),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                name?.take(1) ?: "客",
                style = MaterialTheme.typography.labelSmall,
                color = Paper.Secondary,
            )
        }
        Spacer(Modifier.width(d(8)))
        Text(
            name ?: "登录",
            style = MaterialTheme.typography.labelSmall,
            color = Paper.Secondary,
        )
    }
}

/** 二级页面顶部：返回 + 标题 + 右侧动作。 */
@Composable
fun ScreenTopBar(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    actions: (@Composable () -> Unit)? = null,
    showBack: Boolean = true,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = ui.gutter, vertical = d(16)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showBack) {
            RoundIconButton(Icons.Rounded.ArrowBackIosNew, onBack, size = d(42), iconSize = d(17), contentDescription = "返回")
            Spacer(Modifier.width(d(18)))
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Text(title, style = MaterialTheme.typography.headlineMedium, color = Paper.Pure, fontWeight = FontWeight.SemiBold)
            if (!subtitle.isNullOrBlank()) {
                Spacer(Modifier.width(d(12)))
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Paper.Tertiary, modifier = Modifier.padding(bottom = d(3)))
            }
        }
        Spacer(Modifier.weight(1f))
        actions?.invoke()
    }
}
