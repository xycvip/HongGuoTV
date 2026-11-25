// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.hongguotv.core.Series
import com.hongguotv.nativeapp.ui.components.MetaChip
import com.hongguotv.nativeapp.ui.components.RoundIconButton
import com.hongguotv.nativeapp.ui.components.ScreenTopBar
import com.hongguotv.nativeapp.ui.components.SmartImage
import com.hongguotv.nativeapp.ui.components.rememberQrImage
import com.hongguotv.nativeapp.ui.data.CastPhase
import com.hongguotv.nativeapp.ui.data.CastState
import com.hongguotv.nativeapp.ui.theme.Gold
import com.hongguotv.nativeapp.ui.theme.Ink
import com.hongguotv.nativeapp.ui.theme.Paper
import com.hongguotv.nativeapp.ui.theme.Rouge
import com.hongguotv.nativeapp.ui.theme.d
import com.hongguotv.nativeapp.ui.theme.ui

/** 票根纸色：比纯白暖一档，印二维码不会反白刺眼，也和全局 Paper 色系对得上。 */
private val TicketPaper = Color(0xFFF6F1E7)
private val QrInk = Color(0xFF0B0D12)

/**
 * 投播页。
 *
 * 构图取「登机牌」：左边是给远看的大字与状态，右边是一张可以撕下来的二维码票根。
 * 遥控器用户只需要看左边「通道是否开启」，手机用户只需要扫右边那张票，
 * 两种注意力互不打扰，比上下居中堆叠更稳。
 */
@Composable
fun CastScreen(
    state: CastState,
    onBack: () -> Unit,
    onStart: () -> Unit,
    onRestart: () -> Unit,
    onStop: () -> Unit,
    onDismissCandidates: () -> Unit,
    onPick: (Series) -> Unit,
    modifier: Modifier = Modifier,
    /** 屏幕顶部是否显示可点击的返回箭头；遥控器返回键始终可用，不受此开关影响。 */
    showBack: Boolean = true,
) {
    // 进页面先落一次焦点：电视端没有鼠标，首帧必须有可见光标，用户才知道遥控器有响应。
    val entryFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        onStart()
        withFrameNanos { }
        withFrameNanos { }
        runCatching { entryFocus.requestFocus() }
    }
    DisposableEffect(Unit) { onDispose { onStop() } }

    Column(modifier.fillMaxSize()) {
        ScreenTopBar(
            title = "投播",
            subtitle = "PUSH TO TV · 手机直连",
            onBack = onBack,
            showBack = showBack,
            actions = {
                StatusPill(state)
                Spacer(Modifier.width(d(14)))
                RoundIconButton(
                    Icons.Rounded.Refresh,
                    onRestart,
                    size = d(44),
                    iconSize = d(19),
                    contentDescription = "重新生成投播二维码",
                    focusRequester = entryFocus,
                )
            },
        )

        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = ui.gutter),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                Modifier
                    .widthIn(max = d(1040))
                    .fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CastCopy(state, Modifier.weight(1f).padding(end = d(46)))
                TicketQr(state)
            }
        }

        StepStrip(state, Modifier.padding(horizontal = ui.gutter, vertical = d(12)))
    }

    if (state.candidates.isNotEmpty()) {
        CandidateDialog(
            candidates = state.candidates,
            headline = state.lastTitle,
            onPick = onPick,
            onDismiss = onDismissCandidates,
        )
    }
}

// ------------------------------------------------------------------ 左栏：文案与状态

@Composable
private fun CastCopy(state: CastState, modifier: Modifier = Modifier) {
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .width(d(28))
                    .height(d(1.5f))
                    .background(Brush.horizontalGradient(listOf(Gold.Core, Color.Transparent))),
            )
            Spacer(Modifier.width(d(10)))
            Text(
                "扫码直连 · 无需登录",
                style = MaterialTheme.typography.labelSmall,
                color = Gold.Core,
                letterSpacing = 2.sp,
            )
        }
        Spacer(Modifier.height(d(18)))
        Text(
            "手机里的剧",
            style = MaterialTheme.typography.displaySmall,
            color = Paper.Pure,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.4).sp,
        )
        Text(
            "一键推上电视",
            style = MaterialTheme.typography.displaySmall,
            color = Paper.Pure,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.4).sp,
        )
        Spacer(Modifier.height(d(16)))
        Text(
            "整段粘贴红果 / 抖音的分享文案，电视自动识别。\n个别特殊的分享链接可能暂时无法识别播放。",
            style = MaterialTheme.typography.bodyLarge,
            color = Paper.Secondary,
            modifier = Modifier.widthIn(max = d(560)),
        )
        Spacer(Modifier.height(d(26)))
        StatusBlock(state)
        Spacer(Modifier.height(d(16)))
        AddressBar(state)
    }
}

@Composable
private fun StatusBlock(state: CastState) {
    val accent = when (state.phase) {
        CastPhase.FAILED -> Rouge.Bright
        CastPhase.RECEIVING -> Rouge.Bright
        CastPhase.READY -> Gold.Core
        else -> Paper.Tertiary
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        BreathingDot(accent, active = state.running || state.phase == CastPhase.RECEIVING)
        Spacer(Modifier.width(d(12)))
        Column {
            Text(
                state.message,
                style = MaterialTheme.typography.titleMedium,
                color = if (state.phase == CastPhase.FAILED) Rouge.Bright else Paper.Primary,
            )
            if (!state.detail.isNullOrBlank()) {
                Spacer(Modifier.height(d(3)))
                Text(
                    state.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = Paper.Tertiary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun AddressBar(state: CastState) {
    Row(
        Modifier
            .clip(RoundedCornerShape(d(14)))
            .background(Color(0x0DFFFFFF))
            .border(d(1f), Ink.LineSoft, RoundedCornerShape(d(14)))
            .padding(horizontal = d(18), vertical = d(12)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("手机访问地址", style = MaterialTheme.typography.labelSmall, color = Paper.Tertiary)
            Spacer(Modifier.height(d(4)))
            Text(
                state.url.ifBlank { "通道未开启" },
                style = MaterialTheme.typography.titleMedium,
                fontFamily = FontFamily.Monospace,
                color = if (state.url.isBlank()) Paper.Disabled else Paper.Primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (state.port > 0) {
            Spacer(Modifier.width(d(12)))
            MetaChip("端口 ${state.port}", color = Gold.Core, background = Gold.Core.copy(alpha = 0.12f))
        }
    }
}

@Composable
private fun StatusPill(state: CastState) {
    val (label, color) = when (state.phase) {
        CastPhase.READY -> "通道已开启" to Gold.Core
        CastPhase.STARTING -> "正在开启" to Paper.Secondary
        CastPhase.RECEIVING -> "正在识别" to Rouge.Bright
        CastPhase.FAILED -> "通道异常" to Rouge.Bright
        CastPhase.OFF -> "已停止" to Paper.Tertiary
    }
    Row(
        Modifier
            .clip(RoundedCornerShape(d(999)))
            .background(color.copy(alpha = 0.12f))
            .border(d(1f), color.copy(alpha = 0.35f), RoundedCornerShape(d(999)))
            .padding(horizontal = d(14), vertical = d(7)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BreathingDot(color, active = state.running)
        Spacer(Modifier.width(d(8)))
        Text(label, style = MaterialTheme.typography.labelSmall, color = color)
    }
}

@Composable
private fun BreathingDot(color: Color, active: Boolean) {
    val transition = rememberInfiniteTransition(label = "cast-dot")
    val alpha by transition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Reverse),
        label = "cast-dot-alpha",
    )
    Box(
        Modifier
            .size(d(9))
            .clip(CircleShape)
            .background(color.copy(alpha = if (active) alpha else 0.45f)),
    )
}

// ------------------------------------------------------------------ 右栏：二维码票根

@Composable
private fun TicketQr(state: CastState) {
    val bitmap = rememberQrImage(state.url)
    val notch = Color(0xFF07080D)
    Box {
        Column(
            Modifier
                .width(d(338))
                .clip(RoundedCornerShape(d(24)))
                .background(TicketPaper)
                .border(d(1f), Gold.Core.copy(alpha = 0.55f), RoundedCornerShape(d(24)))
                .padding(horizontal = d(22), vertical = d(18)),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "HONGGUO TV",
                    style = MaterialTheme.typography.labelSmall,
                    color = QrInk.copy(alpha = 0.72f),
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.4.sp,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "投播票根",
                    style = MaterialTheme.typography.labelSmall,
                    color = QrInk.copy(alpha = 0.42f),
                )
            }
            Spacer(Modifier.height(d(14)))
            DashedDivider(QrInk.copy(alpha = 0.2f))
            Spacer(Modifier.height(d(16)))

            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(d(8)))
                    .background(Color.White)
                    .padding(d(12)),
                contentAlignment = Alignment.Center,
            ) {
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap,
                        contentDescription = "投播二维码",
                        modifier = Modifier.fillMaxSize(),
                        // 720px 位图缩到票根里，用双线性而不是最近邻：最近邻会丢掉细模块，扫码失败。
                        filterQuality = FilterQuality.Medium,
                    )
                    ScanSweep(active = state.phase == CastPhase.READY && state.received == 0)
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Rounded.Cast,
                            null,
                            tint = QrInk.copy(alpha = 0.28f),
                            modifier = Modifier.size(d(46)),
                        )
                        Spacer(Modifier.height(d(10)))
                        Text(
                            if (state.phase == CastPhase.STARTING) "正在生成…" else "通道未开启",
                            style = MaterialTheme.typography.labelSmall,
                            color = QrInk.copy(alpha = 0.42f),
                        )
                    }
                }
            }

            Spacer(Modifier.height(d(14)))
            Text(
                "用微信或浏览器扫一扫",
                style = MaterialTheme.typography.titleSmall,
                color = QrInk.copy(alpha = 0.82f),
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(d(4)))
            Text(
                "手机与电视连同一个 Wi-Fi",
                style = MaterialTheme.typography.labelSmall,
                color = QrInk.copy(alpha = 0.45f),
            )
            Spacer(Modifier.height(d(14)))
            DashedDivider(QrInk.copy(alpha = 0.2f))
            Spacer(Modifier.height(d(12)))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "直连 · 不经云端",
                    style = MaterialTheme.typography.labelSmall,
                    color = QrInk.copy(alpha = 0.5f),
                    letterSpacing = 0.8.sp,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    if (state.url.isBlank()) "—" else "READY",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (state.url.isBlank()) QrInk.copy(alpha = 0.3f) else Gold.Deep,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 1.2.sp,
                )
            }
        }

        // 两侧半圆缺口：正好落在顶部虚线上，读起来是一张可以撕开的票。
        TicketNotch(notch, Alignment.TopStart, Modifier.offset(x = -d(8), y = d(39)))
        TicketNotch(notch, Alignment.TopEnd, Modifier.offset(x = d(8), y = d(39)))
    }
}

@Composable
private fun BoxScope.TicketNotch(color: Color, alignment: Alignment, modifier: Modifier) {
    Box(
        modifier
            .align(alignment)
            .size(d(16))
            .clip(CircleShape)
            .background(color),
    )
}

@Composable
private fun DashedDivider(color: Color) {
    Canvas(Modifier.fillMaxWidth().height(d(1))) {
        drawLine(
            color = color,
            start = Offset(0f, size.height / 2f),
            end = Offset(size.width, size.height / 2f),
            strokeWidth = 1.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 5.dp.toPx())),
        )
    }
}

/** 等待扫码时的金色扫光：只在「通道就绪且还没人投」时出现，成功投播后自然停。 */
@Composable
private fun BoxScope.ScanSweep(active: Boolean) {
    if (!active) return
    val transition = rememberInfiniteTransition(label = "cast-sweep")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2600, easing = LinearEasing), RepeatMode.Restart),
        label = "cast-sweep-progress",
    )
    Canvas(Modifier.fillMaxSize()) {
        val y = size.height * progress
        val band = size.height * 0.10f
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(Color.Transparent, Gold.Core.copy(alpha = 0.18f), Color.Transparent),
                startY = y - band,
                endY = y + band,
            ),
        )
        drawRect(
            color = Gold.Core.copy(alpha = 0.7f),
            topLeft = Offset(0f, y),
            size = Size(size.width, 1.dp.toPx()),
        )
    }
}

// ------------------------------------------------------------------ 底部：四步流程

private data class CastStep(val title: String, val hint: String)

private val castSteps = listOf(
    CastStep("连同一 Wi-Fi", "手机与电视在同一网络"),
    CastStep("扫码打开投播页", "微信或浏览器扫一扫"),
    CastStep("粘贴分享文案", "红果 / 抖音整段分享"),
    CastStep("电视自动开播", "识别剧名与集数"),
)

private fun activeStep(state: CastState): Int = when {
    state.phase == CastPhase.RECEIVING -> 2
    state.lastTitle.isNotBlank() -> 3
    state.phase == CastPhase.FAILED -> if (state.received > 0) 2 else 0
    state.received > 0 -> 2
    state.phase == CastPhase.READY -> 1
    else -> 0
}

@Composable
private fun StepStrip(state: CastState, modifier: Modifier = Modifier) {
    val active = activeStep(state)
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(d(18)))
            .background(Color(0x0FFFFFFF))
            .border(d(1f), Ink.LineSoft, RoundedCornerShape(d(18)))
            .padding(horizontal = d(22), vertical = d(14)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        castSteps.forEachIndexed { index, step ->
            val done = index < active
            val current = index == active
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "0${index + 1}",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = when {
                        current -> Gold.Core
                        done -> Gold.Deep
                        else -> Paper.Tertiary
                    },
                    letterSpacing = 1.sp,
                )
                Spacer(Modifier.width(d(10)))
                Column {
                    Text(
                        step.title,
                        style = MaterialTheme.typography.titleSmall,
                        // 未走到的那一步也要读得清：电视是三米外看，Disabled 在这里等于消失。
                        color = when {
                            current -> Paper.Pure
                            done -> Paper.Secondary
                            else -> Paper.Secondary
                        },
                        fontWeight = if (current) FontWeight.SemiBold else FontWeight.Medium,
                        maxLines = 1,
                    )
                    Spacer(Modifier.height(d(2)))
                    Text(
                        step.hint,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (current) Paper.Tertiary else Paper.Tertiary.copy(alpha = 0.88f),
                        maxLines = 1,
                    )
                }
            }
            if (index < castSteps.lastIndex) {
                Box(
                    Modifier
                        .width(d(1))
                        .height(d(28))
                        .background(if (done) Gold.Core.copy(alpha = 0.5f) else Ink.LineSoft),
                )
            }
        }
    }
}

// ------------------------------------------------------------------ 候选弹窗

@Composable
private fun CandidateDialog(
    candidates: List<Series>,
    headline: String,
    onPick: (Series) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        val rows = ((candidates.size + 1) / 2).coerceIn(1, 3)
        Column(
            Modifier
                .fillMaxWidth(0.82f)
                .widthIn(max = d(980))
                .clip(RoundedCornerShape(d(26)))
                .background(Brush.verticalGradient(listOf(Color(0xFF171B24), Color(0xFF0C0F17))))
                .border(d(1f), Gold.Core.copy(alpha = 0.22f), RoundedCornerShape(d(26)))
                .padding(d(26)),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .width(d(22))
                                .height(d(2))
                                .clip(RoundedCornerShape(99.dp))
                                .background(Brush.horizontalGradient(listOf(Rouge.Bright, Gold.Core))),
                        )
                        Spacer(Modifier.width(d(8)))
                        Text("投播匹配", style = MaterialTheme.typography.labelSmall, color = Gold.Core, letterSpacing = 1.6.sp)
                    }
                    Spacer(Modifier.height(d(10)))
                    Text(
                        "选择要播放的剧集",
                        style = MaterialTheme.typography.headlineSmall,
                        color = Paper.Pure,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(d(6)))
                    Text(
                        if (headline.isBlank()) "找到 ${candidates.size} 个可能匹配的剧集，确认后再开播。"
                        else "「$headline」匹配到 ${candidates.size} 部剧，确认后再开播。",
                        style = MaterialTheme.typography.bodySmall,
                        color = Paper.Tertiary,
                    )
                }
                RoundIconButton(Icons.Rounded.Close, onDismiss, size = d(40), iconSize = d(17), contentDescription = "关闭")
            }
            Spacer(Modifier.height(d(18)))
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.fillMaxWidth().height(d(rows * 132)),
                horizontalArrangement = Arrangement.spacedBy(d(12)),
                verticalArrangement = Arrangement.spacedBy(d(12)),
            ) {
                items(candidates, key = { it.id }) { series ->
                    CandidateRow(series) { onPick(series) }
                }
            }
        }
    }
}

@Composable
private fun CandidateRow(series: Series, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (focused) 1.025f else 1f, spring(0.65f, 600f), label = "cand-scale")
    val border by animateColorAsState(if (focused) Gold.Core else Ink.LineSoft, tween(150), label = "cand-border")
    Row(
        Modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(d(16)))
            .background(if (focused) Color(0x1FFFFFFF) else Color(0x0AFFFFFF))
            .border(d(1.2f), border, RoundedCornerShape(d(16)))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(d(8)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(d(68), d(98))
                .clip(RoundedCornerShape(d(10)))
                .background(Ink.SurfaceHigh),
        ) {
            SmartImage(series.cover, Modifier.fillMaxSize(), fallbackTitle = series.title)
        }
        Spacer(Modifier.width(d(12)))
        Column(Modifier.weight(1f)) {
            Text(
                series.title,
                style = MaterialTheme.typography.titleSmall,
                color = Paper.Pure,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(d(5)))
            Text(
                series.tags.ifBlank { series.badge }.take(18).ifBlank { "剧集" },
                style = MaterialTheme.typography.labelSmall,
                color = Paper.Tertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            null,
            tint = if (focused) Gold.Core else Paper.Disabled,
            modifier = Modifier.size(d(18)),
        )
    }
}
