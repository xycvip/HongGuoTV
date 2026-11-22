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

    /**
     * 当前主题的语义色板。新代码请一律从这里取色，不要再写 `Color.rgb(...)`。
     *
     * 下面的 Drawable 工厂里统一用 [currentColors] 而不是 [colors]：在 `setStroke(...)` 的实参位置，
     * Kotlin 会把 `colors.xxx` 解析到宿主 object 的同名成员上（`focusRing` 恰好同名），
     * 导致编译期报 Unresolved。显式函数调用可以稳定拿到 TvColors 的字段。
     */
    val colors: TvColors get() = currentColors()

    private fun currentColors(): TvColors =
        if (theme == Theme.NIGHT) TvColors.Dark else TvColors.Light

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

    const val POSTER_ASPECT=.7f
    fun shape(context: Context,color: Int,border: Int=Color.TRANSPARENT,radius: Int=10)=GradientDrawable().apply {
        setColor(color); cornerRadius=radius*context.resources.displayMetrics.density
        setStroke((2*context.resources.displayMetrics.density).toInt(),border)
    }

    /**
     * 页面底：纵向渐变（顶亮底沉）。纯平色在大屏上像一块平板，
     * 一点纵深就有"幕布"的质感，且所有页面共用同一块底。
     */
    fun pageBackground(context: Context)=GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM,
        intArrayOf(colors.backgroundTop,colors.backgroundBottom)
    )

    /** 竖栏底：同方向渐变但更深一档，框架从底色上浮出来。 */
    fun railBackground(context: Context)=GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM,
        intArrayOf(colors.railTop,colors.railBottom)
    )
    /**
     * 卡片填充（不含选项框）。选项框由 [FocusFrame.frame] / [FocusFrame.paintCard]
     * 画在前景——画在背景会被卡内的封面图盖掉（真机：四角被啃/整圈缺失）。
     */
    fun card(context: Context,focused: Boolean)=shape(
        context,
        if(focused) colors.surfaceRaised else colors.surface,
        Color.TRANSPARENT,
        TvMetric.RADIUS_M
    )

    /**
     * 焦点描边：兼容旧调用点。新代码请直接用 [FocusFrame.ring]（柔光+白环双层，零越界）。
     */
    fun focusRing(context: Context,radius: Int=12)=FocusFrame.ring(context,Color.TRANSPARENT,radius)
}
