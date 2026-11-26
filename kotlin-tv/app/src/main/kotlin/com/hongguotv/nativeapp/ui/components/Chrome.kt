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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
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
import kotlinx.coroutines.launch

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
    /** 每个导航项自己的 requester：切页/返回时把光标钉在下划线所在项上。 */
    tabFocus: Map<Tab, FocusRequester> = emptyMap(),
    /** 非当前项按「下」：先切到这一页，再把焦点交给它的内容区。 */
    onEnterContent: (Tab) -> Unit = {},
) {
    // logo 默认不参与焦点搜索：canFocus=false 时几何搜索命中不了它，
    // 只有用户在最左侧的「首页」上再按一次左键，才临时打开入口并请求焦点。
    // 这样做既满足「不自动跳 logo」，又保留了主动可达。
    var logoReachable by remember { mutableStateOf(false) }
    val logoFocus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    fun reachLogo() {
        if (logoReachable) return
        logoReachable = true
        scope.launch {
            // canFocus 变更要经过一次组合 + 布局才生效，多等几帧再请求，避免请求落空。
            repeat(8) {
                withFrameNanos { }
                if (logoFocus.tryRequestFocus()) return@launch
            }
        }
    }

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
        // logo 外层单独兜一个「按下回导航栏」：万一焦点落在 logo 上，
        // 下方是整幅主视觉图，默认几何搜索很可能找不到落点，看起来就是「卡住不动」。
        Box(
            Modifier.onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown) {
                    val target = tabFocus[Tab.entries.first { it.inTopBar }]
                    target?.tryRequestFocus() ?: false
                } else {
                    false
                }
            },
        ) {
            BrandMark(
                modifier = Modifier
                    .focusRequester(logoFocus)
                    .focusProperties { canFocus = logoReachable }
                    .onFocusChanged { state ->
                        // 光标离开后立刻收起入口，避免下一次几何搜索又落到 logo 上。
                        if (!state.isFocused && logoReachable) logoReachable = false
                    },
                status = apiStatus,
                onClick = onApiStatus,
            )
        }
        Spacer(Modifier.width(d(30)))
        Row(horizontalArrangement = Arrangement.spacedBy(d(6)), verticalAlignment = Alignment.CenterVertically) {
            val visible = Tab.entries.filter { it.inTopBar }
            visible.forEachIndexed { index, tab ->
                NavItem(
                    tab = tab,
                    selected = tab == current,
                    onClick = { onSelect(tab) },
                    // 无论焦点停在哪个导航项，「下」都进入当前已选页面，
                    // 不把焦点所在项误当成页面切换指令。
                    downEntry = contentEntry,
                    requester = tabFocus[tab],
                    onLeftEdge = if (index == 0) ({ reachLogo() }) else null,
                    onEnter = { onEnterContent(current) },
                )
            }
        }
        Spacer(Modifier.weight(1f))
        // 右侧工具位（时钟/投播/搜索/账号）按「下」统一交给当前页内容区。
        // 不接管的话几何搜索会落到内容区某个不确定的位置，甚至跨到别页的落点上。
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown && contentEntry != null) {
                    contentEntry.tryRequestFocus()
                } else {
                    false
                }
            },
        ) {
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
}

@Composable
private fun NavItem(
    tab: Tab,
    selected: Boolean,
    onClick: () -> Unit,
    downEntry: FocusRequester? = null,
    requester: FocusRequester? = null,
    onLeftEdge: (() -> Unit)? = null,
    onEnter: (() -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (focused) 1.06f else 1f, tween(150), label = "nav")
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
            // 焦点属性与按键拦截都放在 clickable 之前（官方推荐顺序）。
            .then(if (requester != null) Modifier.focusRequester(requester) else Modifier)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    // 「下」始终进入当前已选页面，而不是焦点所在的导航项对应页面。
                    // 因此即使焦点误落到其他导航项，也不会悄悄切换顶部导航。
                    Key.DirectionDown -> {
                        if (downEntry != null) {
                            // 当前页面内容已就绪：精确请求当前页入口，避免几何搜索跳错页面。
                            downEntry.tryRequestFocus()
                            true
                        } else {
                            // 当前页面尚未就绪：记录当前页的进入意图，待内容 attach 后再落焦点。
                            onEnter?.invoke()
                            true
                        }
                    }
                    // 最左侧再按一次左键才进 logo（见 TopBar 里的 logoReachable）。
                    Key.DirectionLeft -> if (onLeftEdge != null) { onLeftEdge(); true } else false
                    else -> false
                }
            }
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(d(999)))
            .background(bg)
            .border(d(1f), if (focused) Color(0x33FFFFFF) else Color.Transparent, RoundedCornerShape(d(999)))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
    ) {
        // 文字在固定高度里绝对居中；指示条不占布局高度、只贴在底部。
        // 原实现把线当成 Column 的一员，导致「没选中时文字偏上、下方空一截」。
        Box(
            modifier = Modifier
                .height(d(50))
                .padding(horizontal = d(16)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                tab.label,
                style = MaterialTheme.typography.titleSmall,
                color = color,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            )
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .offset(y = d(-9))
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
    val scale by animateFloatAsState(if (focused) 1.06f else 1f, tween(150), label = "cast-entry")
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
