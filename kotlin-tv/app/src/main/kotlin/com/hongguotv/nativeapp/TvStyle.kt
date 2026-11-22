// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable

/**
 * 主题与形状的统一入口。
 *
 * 颜色不再写在这里，而是来自 [TvColors]（见 TvColors.kt）：本对象只负责"当前是哪套主题"，
 * 以及把语义令牌包成可直接用的 Drawable。
 */
object TvStyle {
    enum class Theme(val label: String) { NIGHT("深夜影院"), PORCELAIN("暖玉白");
        companion object { fun of(raw: String?) = entries.firstOrNull { it.name == raw } ?: NIGHT }
    }
    var theme = Theme.NIGHT
        private set
    fun apply(raw: String?) { theme = Theme.of(raw) }
    fun toggle(): Theme { theme = if (theme == Theme.NIGHT) Theme.PORCELAIN else Theme.NIGHT; return theme }

    /** 当前主题的语义色板。新代码请一律从这里取色，不要再写 `Color.rgb(...)`。 */
    val colors: TvColors get() = if (theme == Theme.NIGHT) TvColors.Dark else TvColors.Light

    // ── 兼容旧调用点的别名 ─────────────────────────────────────────────
    // 逐条指向语义令牌，行为与改造前一致；后续可逐个替换为 TvStyle.colors.xxx。
    val background get() = colors.background
    val surface get() = colors.surface
    val raised get() = colors.surfaceRaised
    val accent get() = colors.accent
    val text get() = colors.textPrimary
    val muted get() = colors.textSecondary
    val outline get() = colors.strokeSubtle
    val selectedSurface get() = colors.selectedSurface
    val badgeSurface get() = colors.badgeSurface

    /**
     * 顶部导航磨砂玻璃材质：按当前主题提供半透明渐变与柔和描边。
     * 使用原生 GradientDrawable，兼容 Android 8.0+，不依赖新系统模糊 API。
     */
    fun navGlass(context: Context)=GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM,
        intArrayOf(colors.navTop, colors.navMid, colors.navBottom)
    ).apply {
        cornerRadius=16*context.resources.displayMetrics.density
        setStroke(
            (1*context.resources.displayMetrics.density).toInt().coerceAtLeast(1),
            colors.navStroke
        )
    }

    const val POSTER_ASPECT=.7f
    fun shape(context: Context,color: Int,border: Int=Color.TRANSPARENT,radius: Int=10)=GradientDrawable().apply {
        setColor(color); cornerRadius=radius*context.resources.displayMetrics.density
        setStroke((2*context.resources.displayMetrics.density).toInt(),border)
    }
    fun card(context: Context,focused: Boolean)=shape(context,if(focused) colors.surfaceRaised else colors.surface,if(focused) colors.accent else Color.TRANSPARENT)

    /**
     * 焦点描边：透明底 + 白色圆环，供卡片/按钮在获得遥控器焦点时叠加使用。
     * 电视端远距离观看，2dp 是最低可见宽度。
     */
    fun focusRing(context: Context,radius: Int=12)=GradientDrawable().apply {
        setColor(Color.TRANSPARENT)
        cornerRadius=radius*context.resources.displayMetrics.density
        setStroke((2*context.resources.displayMetrics.density).toInt(),colors.focusRing)
    }
}
