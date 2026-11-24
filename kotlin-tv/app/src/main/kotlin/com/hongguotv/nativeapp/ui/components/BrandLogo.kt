// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hongguotv.nativeapp.ui.data.ApiStatusState
import com.hongguotv.nativeapp.ui.data.tint
import com.hongguotv.nativeapp.ui.theme.Gold
import com.hongguotv.nativeapp.ui.theme.d

/**
 * 品牌标记：四叶接口状态灯。
 *
 * 四片光叶朝向同一圆心，顺时针分别对应首页、搜索、播放、联想。
 * 正常时是青玉绿，异常胭脂红，检测中鎏金，未检测暖金——未检测不用灰色，
 * 否则冷启动第一眼会像「坏了」。中心保留一颗鎏金核，让它同时是品牌 logo。
 * 检测进行中叶片缓慢旋转；聚焦时外环点亮。
 */
@Composable
fun ApiStatusBrandMark(
    state: ApiStatusState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp? = null,
) {
    val side = size ?: d(46)
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val animation = rememberInfiniteTransition(label = "api-brand")
    val spin by animation.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(5200, easing = LinearEasing), RepeatMode.Restart),
        label = "api-brand-spin",
    )
    val breath by animation.animateFloat(
        initialValue = 0.80f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2400, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "api-brand-breath",
    )
    val accent = state.overall.tint()

    Box(
        modifier
            .size(side)
            .clip(CircleShape)
            .semantics { contentDescription = "接口状态 · ${state.summary}" }
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(side)) {
            val center = Offset(this.size.width / 2f, this.size.height / 2f)
            val radius = this.size.minDimension / 2f

            // 极淡的状态色光晕：让灯在深色顶栏里有「亮着」的感觉，而不是贴上去的图标。
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        accent.copy(alpha = (if (focused) 0.30f else 0.15f) * (0.72f + 0.28f * breath)),
                        Color.Transparent,
                    ),
                    center = center,
                    radius = radius,
                ),
                radius = radius,
                center = center,
            )
            drawCircle(
                color = if (focused) Gold.Bright.copy(alpha = 0.82f) else accent.copy(alpha = 0.20f),
                radius = radius - 1.dp.toPx(),
                center = center,
                style = Stroke(width = if (focused) 1.5.dp.toPx() else 0.9.dp.toPx()),
            )
            drawCircle(
                color = Color(0x12FFFFFF),
                radius = radius * 0.70f,
                center = center,
                style = Stroke(width = 0.7.dp.toPx()),
            )

            val rotation = if (state.running) spin else 45f
            val leafBase = radius * 0.18f
            val leafTip = radius * 0.74f
            val halfWidth = radius * 0.235f
            repeat(4) { leaf ->
                val probe = state.probes.getOrNull(leaf)
                val color = probe?.status?.tint() ?: Gold.Deep
                rotate(degrees = rotation + leaf * 90f, pivot = center) {
                    val path = Path().apply {
                        moveTo(center.x, center.y - leafBase)
                        cubicTo(
                            center.x + halfWidth, center.y - leafBase - radius * 0.10f,
                            center.x + halfWidth * 0.66f, center.y - leafTip + radius * 0.08f,
                            center.x, center.y - leafTip,
                        )
                        cubicTo(
                            center.x - halfWidth * 0.66f, center.y - leafTip + radius * 0.08f,
                            center.x - halfWidth, center.y - leafBase - radius * 0.10f,
                            center.x, center.y - leafBase,
                        )
                        close()
                    }
                    drawPath(
                        path,
                        brush = Brush.linearGradient(
                            colors = listOf(color.copy(alpha = 0.95f), color.copy(alpha = 0.26f)),
                            start = Offset(center.x, center.y - leafBase),
                            end = Offset(center.x, center.y - leafTip),
                        ),
                    )
                    drawPath(path, color = color.copy(alpha = 0.42f), style = Stroke(width = 0.7.dp.toPx()))
                }
            }

            // 中心鎏金核：品牌识别点，也是「四叶朝向同一圆心」的视觉锚。
            drawCircle(Gold.Deep.copy(alpha = 0.32f), radius = radius * 0.20f, center = center)
            drawCircle(Color(0x2EFFFFFF), radius = radius * 0.13f, center = center)
            drawCircle(Gold.Core.copy(alpha = 0.92f), radius = radius * 0.105f, center = center)
            drawCircle(Color.White.copy(alpha = 0.80f), radius = radius * 0.035f, center = center)
        }
    }
}
