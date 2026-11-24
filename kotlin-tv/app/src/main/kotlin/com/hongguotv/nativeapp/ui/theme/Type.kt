// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * 中文界面靠字重与字距建立层次：大标题收紧字距、正文留白充足、
 * 数字统一使用等宽感更强的 Medium，避免遥控器距离下的糊成一团。
 */
fun hongguoTypography(scale: Float): Typography {
    val s = scale.coerceIn(0.72f, 2.4f)
    fun style(size: Float, weight: FontWeight, tracking: Float = 0f, height: Float = 1.28f) = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = weight,
        fontSize = (size * s).sp,
        lineHeight = (size * s * height).sp,
        letterSpacing = (tracking * s).sp,
    )
    return Typography(
        displayLarge = style(58f, FontWeight.Bold, (-1.2f), 1.1f),
        displayMedium = style(46f, FontWeight.Bold, (-0.8f), 1.12f),
        displaySmall = style(36f, FontWeight.SemiBold, (-0.4f), 1.18f),
        headlineLarge = style(32f, FontWeight.SemiBold, (-0.2f), 1.2f),
        headlineMedium = style(26f, FontWeight.SemiBold, 0f, 1.24f),
        headlineSmall = style(22f, FontWeight.Medium, 0f, 1.26f),
        titleLarge = style(20f, FontWeight.SemiBold, 0f, 1.3f),
        titleMedium = style(17f, FontWeight.Medium, 0f, 1.32f),
        titleSmall = style(15f, FontWeight.Medium, 0.2f, 1.32f),
        bodyLarge = style(16f, FontWeight.Normal, 0.1f, 1.55f),
        bodyMedium = style(14f, FontWeight.Normal, 0.2f, 1.55f),
        bodySmall = style(12f, FontWeight.Normal, 0.25f, 1.5f),
        labelLarge = style(14f, FontWeight.SemiBold, 0.6f, 1.2f),
        labelMedium = style(12f, FontWeight.Medium, 0.8f, 1.2f),
        labelSmall = style(11f, FontWeight.Medium, 1.0f, 1.2f),
    )
}
