// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * 「夜色鎏金」色板。
 *
 * 底色不是纯黑，而是带一点冷调的墨色，让海报的高光更干净；
 * 主色取红果的胭脂红，辅助色用低饱和的鎏金，只在评分、冠军、焦点描边等位置出现，
 * 避免大面积金色带来的廉价感。
 */
object Ink {
    val Abyss = Color(0xFF05060A)
    val Deep = Color(0xFF080A10)
    val Base = Color(0xFF0C0E15)
    val Surface = Color(0xFF11141C)
    val SurfaceHigh = Color(0xFF181C26)
    val SurfaceTop = Color(0xFF212633)
    val Line = Color(0xFF262C39)
    val LineSoft = Color(0x1FFFFFFF)
}

object Rouge {
    val Core = Color(0xFFF2415A)
    val Bright = Color(0xFFFF6B7E)
    val Deep = Color(0xFFB3162F)
    val Mist = Color(0x33F2415A)
    val Veil = Color(0x1AF2415A)
}

object Gold {
    val Core = Color(0xFFE7BE7C)
    val Bright = Color(0xFFF7DCA9)
    val Deep = Color(0xFFA97C3A)
}

object Paper {
    val Pure = Color(0xFFFDFBF7)
    val Primary = Color(0xFFF4F1EA)
    val Secondary = Color(0xFFA8AEBC)
    val Tertiary = Color(0xFF6E7686)
    val Disabled = Color(0xFF454B58)
}

object Signal {
    val Jade = Color(0xFF57D3A3)
    val Amber = Color(0xFFF0A24B)
    val Sky = Color(0xFF63A9FF)
    val Rose = Color(0xFFF2415A)
}

/** 半透明遮罩，用于图片上的文字与播放器控制层。 */
object Veil {
    val Scrim = Color(0xB3000000)
    val ScrimSoft = Color(0x66000000)
    val Glass = Color(0xCC10131A)
    val GlassLight = Color(0x99FFFFFF)
}
