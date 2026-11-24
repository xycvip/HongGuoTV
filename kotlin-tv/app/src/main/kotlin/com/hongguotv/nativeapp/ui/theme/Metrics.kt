// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 电视、盒子、手机的屏幕密度差异很大：同一份 dp 在 960dp 宽的面板和 1920dp 宽的面板上
 * 观感完全不同。这里以 1280×720dp 为设计基准，按较短边等比缩放，
 * 让字号、圆角、留白在所有设备上保持同样的视觉比例。
 */
data class UiMetrics(val scale: Float, val compact: Boolean) {
    val gutter: Dp get() = (if (compact) 28.dp else 44.dp) * scale
    val railGap: Dp get() = 24.dp * scale
    val cardRadius: Dp get() = 14.dp * scale
    val heroRadius: Dp get() = 26.dp * scale

    fun dp(value: Float): Dp = (value * scale).dp
    fun dp(value: Int): Dp = (value * scale).dp
    fun sp(value: Float): TextUnit = (value * scale).sp
}

val LocalUiMetrics = compositionLocalOf { UiMetrics(1f, false) }

val ui: UiMetrics
    @Composable @ReadOnlyComposable get() = LocalUiMetrics.current

/** 设计稿数值 → 当前屏幕尺寸。 */
@Composable
@ReadOnlyComposable
fun d(value: Number): Dp = ui.dp(value.toFloat())
