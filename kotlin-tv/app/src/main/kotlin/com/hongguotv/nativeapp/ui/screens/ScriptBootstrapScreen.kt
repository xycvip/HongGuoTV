// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp.ui.screens

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.hongguotv.nativeapp.ui.components.AmbientBackground
import com.hongguotv.nativeapp.ui.components.PulseDots
import com.hongguotv.nativeapp.ui.components.TvButton
import com.hongguotv.nativeapp.ui.components.tryRequestFocus
import com.hongguotv.nativeapp.ui.data.ScriptSetupState
import com.hongguotv.nativeapp.ui.theme.Gold
import com.hongguotv.nativeapp.ui.theme.Ink
import com.hongguotv.nativeapp.ui.theme.Paper
import com.hongguotv.nativeapp.ui.theme.Rouge
import com.hongguotv.nativeapp.ui.theme.d
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text

/**
 * 首次启动的数据接口引导页。
 *
 * APK 不再内置 main.js，全新安装必须先联网拉一份（约 0.5 MB）。
 * 这条路径只会在「本地一份可用数据接口都没有」时出现：首次安装，或当前版与上一版都损坏。
 * 下载成功后本页卸载，主界面才开始渲染；失败则停在原地等用户重试，不会进到一个空数据层。
 */
@Composable
fun ScriptBootstrapScreen(
    state: ScriptSetupState,
    versionName: String,
    onRetry: () -> Unit,
) {
    val retryFocus = remember { FocusRequester() }
    val failed = state.error != null
    LaunchedEffect(failed) {
        if (failed) {
            withFrameNanos { }
            retryFocus.tryRequestFocus()
        }
    }

    AmbientBackground {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = d(72)),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            BrandMark()
            Spacer(Modifier.height(d(26)))
            BootstrapCard(state = state, onRetry = onRetry, retryFocus = retryFocus)
            Spacer(Modifier.height(d(22)))
            Text(
                text = "红果TV v$versionName · 发布通道 RedGuoAPI",
                style = MaterialTheme.typography.labelSmall,
                color = Paper.Tertiary,
            )
        }
    }
}

@Composable
private fun BrandMark() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(d(9))
                .clip(RoundedCornerShape(d(999)))
                .background(Brush.linearGradient(listOf(Rouge.Bright, Rouge.Core))),
        )
        Spacer(Modifier.width(d(12)))
        Text(
            text = "红果",
            style = MaterialTheme.typography.titleLarge,
            color = Paper.Pure,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.width(d(10)))
        Box(Modifier.width(d(1)).height(d(14)).background(Ink.Line))
        Spacer(Modifier.width(d(10)))
        Text(
            text = "数据接口",
            style = MaterialTheme.typography.titleSmall,
            color = Paper.Tertiary,
        )
    }
}

@Composable
private fun BootstrapCard(
    state: ScriptSetupState,
    onRetry: () -> Unit,
    retryFocus: FocusRequester,
) {
    val failed = state.error != null
    val shape = RoundedCornerShape(d(26))
    Box(
        Modifier
            .widthIn(max = d(760))
            .fillMaxWidth()
            .clip(shape)
            .background(Brush.verticalGradient(listOf(Ink.SurfaceHigh, Ink.Surface)))
            .border(d(1), Ink.Line, shape),
    ) {
        // 右上角一团极淡红光，和「票根」卡片保持一致的气质。
        Box(
            Modifier
                .fillMaxWidth()
                .height(d(180))
                .background(
                    Brush.radialGradient(
                        colors = listOf(Rouge.Veil, Color.Transparent),
                        center = Offset(0f, 0f),
                        radius = 1200f,
                    ),
                ),
        )
        Column(Modifier.padding(horizontal = d(40), vertical = d(36))) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusOrb(failed = failed)
                Spacer(Modifier.width(d(30)))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = if (failed) "数据接口下载失败" else "正在准备数据接口",
                        style = MaterialTheme.typography.headlineSmall,
                        color = Paper.Pure,
                    )
                    Spacer(Modifier.height(d(10)))
                    Text(
                        text = if (failed) {
                            state.error
                        } else {
                            "红果的数据接口由云端提供，首次启动需要联网下载一次（约 0.5 MB）。以后数据更新会自动完成，不需要手动操作。"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = Paper.Secondary,
                    )
                    if (!failed) {
                        Spacer(Modifier.height(d(16)))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            PulseDots()
                            Spacer(Modifier.width(d(10)))
                            Text(
                                text = "正在连接发布通道…",
                                style = MaterialTheme.typography.labelMedium,
                                color = Gold.Core,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(d(26)))
            Box(Modifier.fillMaxWidth().height(d(1)).background(Ink.Line))
            Spacer(Modifier.height(d(20)))
            if (failed) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TvButton(
                        text = "重新下载",
                        onClick = onRetry,
                        focusRequester = retryFocus,
                    )
                    Spacer(Modifier.width(d(20)))
                    Text(
                        text = "六条线路会自动择优；若一直失败，请确认电视已连上互联网。",
                        style = MaterialTheme.typography.bodySmall,
                        color = Paper.Tertiary,
                    )
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(d(28)),
                ) {
                    HintItem("体积", "约 0.5 MB")
                    HintItem("线路", "六条自动择优")
                    HintItem("生效", "下载完成立即进入")
                }
            }
        }
    }
}

@Composable
private fun HintItem(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = Paper.Tertiary)
        Spacer(Modifier.height(d(5)))
        Text(value, style = MaterialTheme.typography.titleSmall, color = Paper.Primary)
    }
}

/**
 * 状态环：准备中是沿环旋转的一段胭脂红弧，失败时收成一枚静止的红环。
 * 只用 Canvas 画，不依赖任何图标资源。
 */
@Composable
private fun StatusOrb(failed: Boolean) {
    val transition = rememberInfiniteTransition(label = "script-orb")
    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1500, easing = LinearEasing), RepeatMode.Restart),
        label = "script-orb-rotate",
    )
    val glow by transition.animateFloat(
        initialValue = 0.25f,
        targetValue = 0.6f,
        animationSpec = infiniteRepeatable(tween(1300, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "script-orb-glow",
    )
    val size = d(84)
    val strokeWidth = d(3)
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = strokeWidth.toPx()
            val radius = this.size.minDimension / 2 - stroke
            val topLeft = Offset(this.size.width / 2 - radius, this.size.height / 2 - radius)
            val arcSize = Size(radius * 2, radius * 2)
            if (failed) {
                drawCircle(color = Rouge.Mist, radius = radius, style = Stroke(width = stroke))
                drawCircle(color = Rouge.Core, radius = radius * 0.34f)
            } else {
                drawCircle(color = Rouge.Veil.copy(alpha = Rouge.Veil.alpha * glow), radius = radius, style = Stroke(width = stroke * 2.4f))
                drawCircle(color = Ink.SurfaceTop, radius = radius, style = Stroke(width = stroke))
                rotate(rotation) {
                    drawArc(
                        brush = Brush.sweepGradient(
                            listOf(Color.Transparent, Rouge.Core, Rouge.Bright),
                        ),
                        startAngle = 0f,
                        sweepAngle = 110f,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = Stroke(width = stroke, cap = StrokeCap.Round),
                    )
                }
            }
        }
        Text(
            text = if (failed) "!" else "",
            style = MaterialTheme.typography.titleLarge,
            color = Color.White,
            textAlign = TextAlign.Center,
        )
    }
}
