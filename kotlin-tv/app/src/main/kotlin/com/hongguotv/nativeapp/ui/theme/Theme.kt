// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp

private val MidnightScheme = darkColorScheme(
    primary = Rouge.Core,
    onPrimary = Color.White,
    primaryContainer = Rouge.Deep,
    onPrimaryContainer = Color.White,
    secondary = Gold.Core,
    onSecondary = Color(0xFF211603),
    tertiary = Signal.Sky,
    background = Ink.Deep,
    onBackground = Paper.Primary,
    surface = Ink.Base,
    onSurface = Paper.Primary,
    surfaceVariant = Ink.SurfaceHigh,
    onSurfaceVariant = Paper.Secondary,
    outline = Ink.Line,
    outlineVariant = Ink.SurfaceTop,
    error = Rouge.Core,
    onError = Color.White,
    scrim = Color.Black,
)

/**
 * 全局主题。刻意只保留一套夜间配色：影视内容在暗底上才有影院感，
 * 浅色主题会让海报失去沉浸度，也会破坏焦点描边的层次。
 */
@Composable
fun HongguoTheme(content: @Composable () -> Unit) {
    val configuration = LocalConfiguration.current
    val widthDp = configuration.screenWidthDp.toFloat()
    val heightDp = configuration.screenHeightDp.toFloat()
    // 系统字体缩放一并计入，避免大字号辅助模式下布局溢出。
    val fontScale = configuration.fontScale
    val rawScale = minOf(widthDp / 1280f, heightDp / 720f).coerceIn(0.62f, 2.6f) * fontScale.coerceIn(0.85f, 1.3f)
    val metrics = remember(rawScale, widthDp) {
        UiMetrics(scale = rawScale, compact = widthDp < 1000f)
    }
    val typography = remember(metrics.scale) { hongguoTypography(metrics.scale) }
    val shapes = remember(metrics.scale) {
        Shapes(
            extraSmall = RoundedCornerShape(6.dp * metrics.scale),
            small = RoundedCornerShape(10.dp * metrics.scale),
            medium = RoundedCornerShape(16.dp * metrics.scale),
            large = RoundedCornerShape(22.dp * metrics.scale),
            extraLarge = RoundedCornerShape(30.dp * metrics.scale),
        )
    }
    CompositionLocalProvider(
        LocalUiMetrics provides metrics,
    ) {
        MaterialTheme(colorScheme = MidnightScheme, typography = typography, shapes = shapes, content = content)
    }
}
