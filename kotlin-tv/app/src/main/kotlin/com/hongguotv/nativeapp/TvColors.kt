// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

/**
 * 设计令牌 · 颜色。
 *
 * 全应用只从这里取色，界面代码不应再出现 `Color.rgb(...)` / `Color.parseColor(...)` 之类的字面量。
 * 分三层，改一层不会牵连其他层：
 *
 *  1. [TvPalette] —— 原始色阶。十六进制常量，只描述"是什么颜色"，不含任何业务含义。
 *  2. [TvColors]  —— 语义令牌。描述"这个颜色用来做什么"，深色 / 浅色各一份。
 *  3. `TvStyle.colors` —— 当前主题的语义令牌，是界面代码唯一的取色入口。
 *
 * 用法：
 * ```kotlin
 * view.setBackgroundColor(TvStyle.colors.surfaceRaised)
 * title.setTextColor(TvStyle.colors.textPrimary)
 * subtitle.setTextColor(TvStyle.colors.textSecondary)
 * divider.setBackgroundColor(TvStyle.colors.strokeSubtle)
 * ```
 *
 * 命名约定：
 *  - `background / surface / surfaceRaised / surfaceSunken` 由深到浅表示层级，不要跨层跳用。
 *  - `textPrimary / textSecondary / textTertiary` 是文字的三级层次，不要再自造第四级灰色。
 *  - `accent` 只用于"当前选中 / 进度 / 主操作"，一屏内出现面积不超过 10%。
 *  - `scrim*` 是压暗用的半透明遮罩，铺在图片上保证文字可读。
 */
object TvPalette {
    // ── 中性色 · 墨（深色主题，由深到浅）──────────────────────────────
    const val INK_950 = 0xFF07080B.toInt()
    const val INK_900 = 0xFF0B0F15.toInt()
    const val INK_850 = 0xFF14181F.toInt()
    const val INK_800 = 0xFF171E28.toInt()
    const val INK_700 = 0xFF202A36.toInt()
    const val INK_600 = 0xFF2B3746.toInt()
    const val INK_500 = 0xFF3A4759.toInt()
    const val INK_400 = 0xFF93A0B2.toInt()
    const val INK_300 = 0xFFC3CBD6.toInt()
    const val INK_100 = 0xFFF3F4F6.toInt()

    // ── 中性色 · 纸（浅色主题，由浅到深）──────────────────────────────
    const val PAPER_50 = 0xFFFFFFFF.toInt()
    const val PAPER_100 = 0xFFF7F3EC.toInt()
    const val PAPER_200 = 0xFFF3ECE0.toInt()
    const val PAPER_300 = 0xFFE8DFCE.toInt()
    const val PAPER_400 = 0xFFE2D9C8.toInt()
    const val PAPER_500 = 0xFFCEC3B0.toInt()
    const val PAPER_600 = 0xFFB5A78F.toInt()
    const val PAPER_700 = 0xFF7A6C5C.toInt()
    const val PAPER_800 = 0xFF635749.toInt()
    const val PAPER_900 = 0xFF3A342C.toInt()

    // ── 强调色 · 暖橙（当前主色）──────────────────────────────────────
    const val EMBER_300 = 0xFFFFA487.toInt()
    const val EMBER_400 = 0xFFFF8964.toInt()
    const val EMBER_500 = 0xFFF2703F.toInt()
    const val EMBER_700 = 0xFFC4532E.toInt()
    const val EMBER_800 = 0xFFA8431F.toInt()

    // ── 强调色 · 暖金（备选主色）──────────────────────────────────────
    // 想切「墨黑 + 暖金」时，只改 TvColors.Dark.accent 与 focusGlow 两处即可，
    // 其余界面全部跟着变色，不需要逐页调。
    const val GOLD_300 = 0xFFF0C77E.toInt()
    const val GOLD_400 = 0xFFE8B15C.toInt()
    const val GOLD_500 = 0xFFD39445.toInt()

    // ── 状态色 ───────────────────────────────────────────────────────
    const val RED_400 = 0xFFEB4B4B.toInt()
    const val RED_700 = 0xFFC62828.toInt()
    const val AMBER_400 = 0xFFEEBE37.toInt()
    const val AMBER_500 = 0xFFF4A640.toInt()
    const val AMBER_700 = 0xFFB26A00.toInt()
    const val GREEN_400 = 0xFF4CAF50.toInt()
    const val GREEN_700 = 0xFF2E7D32.toInt()
    const val BLUE_400 = 0xFF4696FF.toInt()
    const val BLUE_700 = 0xFF1565C0.toInt()
}

/** 保留色相，只改透明度。[ratio] 取值 0f..1f。 */
fun withAlpha(color: Int, ratio: Float): Int {
    val alpha = (ratio.coerceIn(0f, 1f) * 255f).toInt()
    return (color and 0x00FFFFFF) or (alpha shl 24)
}

/**
 * 一套完整的语义色板。深色与浅色各一个实例（[Dark] / [Light]），
 * 界面代码通过 `TvStyle.colors` 取当前主题的那一份。
 */
data class TvColors(
    // 层级：由深到浅
    val background: Int,
    val surface: Int,
    val surfaceRaised: Int,
    val surfaceSunken: Int,
    // 背景/竖栏的渐变端色（顶部略亮、底部沉下去，给大屏一层纵深）。
    // 默认回落到纯色，不破坏既有构造。
    val backgroundTop: Int = background,
    val backgroundBottom: Int = background,
    val railTop: Int = background,
    val railBottom: Int = background,
    // 文字三级层次
    val textPrimary: Int,
    val textSecondary: Int,
    val textTertiary: Int,
    // 强调与主操作
    val accent: Int,
    val accentPressed: Int,
    val onAccent: Int,
    // 焦点（电视端遥控器焦点环与光晕）
    val focusRing: Int,
    val focusGlow: Int,
    // 分隔线 / 描边
    val strokeSubtle: Int,
    val strokeStrong: Int,
    // 遮罩
    val scrimPage: Int,
    val scrimPoster: Int,
    // 状态
    val success: Int,
    val warning: Int,
    val danger: Int,
    val info: Int,
    // 局部组件
    val badgeSurface: Int,
    val selectedSurface: Int,
    // 主导航竖栏：比页面底色更沉一层，靠一条细线切出"框架"感
    val rail: Int,
    val railStroke: Int,
    val railSelected: Int
) {
    companion object {
        /** 深夜影院（默认）。色值与改造前完全一致，改令牌不改变现有观感。 */
        val Dark = TvColors(
            background = TvPalette.INK_900,
            surface = TvPalette.INK_800,
            surfaceRaised = TvPalette.INK_700,
            surfaceSunken = TvPalette.INK_850,
            backgroundTop = 0xFF10151D.toInt(),
            backgroundBottom = 0xFF050609.toInt(),
            railTop = TvPalette.INK_900,
            railBottom = 0xFF050608.toInt(),
            textPrimary = TvPalette.INK_100,
            textSecondary = TvPalette.INK_400,
            textTertiary = 0xFF6E7A8C.toInt(),
            accent = TvPalette.EMBER_400,
            accentPressed = TvPalette.EMBER_500,
            onAccent = 0xFF1A0F0A.toInt(),
            focusRing = 0xFFFFFFFF.toInt(),
            focusGlow = withAlpha(TvPalette.EMBER_400, 0.30f),
            strokeSubtle = TvPalette.INK_600,
            strokeStrong = TvPalette.INK_500,
            scrimPage = 0xE608090C.toInt(),
            scrimPoster = 0xEB000000.toInt(),
            success = TvPalette.GREEN_400,
            warning = TvPalette.AMBER_400,
            danger = TvPalette.RED_400,
            info = TvPalette.BLUE_400,
            badgeSurface = 0xFF1C1F27.toInt(),
            selectedSurface = 0xFF3D2722.toInt(),
            rail = TvPalette.INK_950,
            railStroke = 0x14FFFFFF,
            railSelected = 0x0FFFFFFF,
        )

        /** 暖玉白。色值与改造前一致。 */
        val Light = TvColors(
            background = TvPalette.PAPER_400,
            surface = TvPalette.PAPER_50,
            surfaceRaised = TvPalette.PAPER_200,
            surfaceSunken = TvPalette.PAPER_300,
            backgroundTop = 0xFFEFE8DA.toInt(),
            backgroundBottom = 0xFFD7CBB6.toInt(),
            railTop = 0xFFEEE5D4.toInt(),
            railBottom = 0xFFDDD0BA.toInt(),
            textPrimary = TvPalette.PAPER_900,
            textSecondary = TvPalette.PAPER_800,
            textTertiary = TvPalette.PAPER_700,
            accent = TvPalette.EMBER_700,
            accentPressed = TvPalette.EMBER_800,
            onAccent = TvPalette.PAPER_50,
            focusRing = TvPalette.PAPER_900,
            focusGlow = withAlpha(TvPalette.EMBER_700, 0.26f),
            strokeSubtle = TvPalette.PAPER_500,
            strokeStrong = TvPalette.PAPER_600,
            scrimPage = 0xE6E2D9C8.toInt(),
            scrimPoster = 0xEB000000.toInt(),
            success = TvPalette.GREEN_700,
            warning = TvPalette.AMBER_700,
            danger = TvPalette.RED_700,
            info = TvPalette.BLUE_700,
            badgeSurface = 0xFF50463C.toInt(),
            selectedSurface = 0xFFE0CEBC.toInt(),
            rail = TvPalette.PAPER_300,
            railStroke = 0x1A000000,
            railSelected = 0x0F000000,
        )

        /** 备选：墨黑 + 暖金。切换时把 [Dark] 里的 accent / focusGlow 换成这两个即可。 */
        fun goldAccentOf(dark: Boolean): Pair<Int, Int> =
            if (dark) TvPalette.GOLD_400 to withAlpha(TvPalette.GOLD_400, 0.30f)
            else TvPalette.GOLD_500 to withAlpha(TvPalette.GOLD_500, 0.26f)
    }
}
