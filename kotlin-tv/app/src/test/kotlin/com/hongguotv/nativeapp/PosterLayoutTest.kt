// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 海报列数是全站唯一一处"按屏幕算布局"的逻辑，算错的表现是最后一列被裁掉半边，
 * 而这种问题在编译期和单页截图里都看不出来，所以单独锁住几个关键分辨率。
 */
class PosterLayoutTest {

    /** 1080p 电视：内容区 788dp，五列每张 143dp。 */
    @Test
    fun `1080p resolves to five columns`() {
        assertEquals(5, PosterLayout.autoColumns(788f, 540f))
    }

    /** 720p 电视：内容区 508dp，三列每张 154dp；矮屏用更小的目标宽度。 */
    @Test
    fun `720p resolves to three columns`() {
        assertEquals(3, PosterLayout.autoColumns(508f, 360f))
    }

    /** 4K 电视：内容区 1716dp，自动档封顶 5 列（真机反馈 6/7 列太碎），手动档仍可选 7。 */
    @Test
    fun `4k auto is capped at five columns`() {
        assertEquals(5, PosterLayout.autoColumns(1716f, 1080f))
    }

    /** 极窄内容区（手机竖屏）退到两列，但不会掉到 1 列或 0 列。 */
    @Test
    fun `narrow width never drops below two columns`() {
        assertEquals(2, PosterLayout.autoColumns(280f, 780f))
        assertEquals(2, PosterLayout.autoColumns(60f, 360f))
    }

    @Test
    fun `column count stays inside allowed range for every width`() {
        var width = 200f
        while (width <= 2600f) {
            val auto = PosterLayout.autoColumns(width, 540f)
            assertTrue("auto=$auto at $width", auto in PosterLayout.MIN_COLUMNS..PosterLayout.MAX_COLUMNS)
            val affordable = PosterLayout.affordableColumns(width)
            assertTrue("affordable=$affordable at $width", affordable in PosterLayout.MIN_COLUMNS..PosterLayout.MAX_COLUMNS)
            width += 17f
        }
    }

    /** 手动选 7 列、屏幕只放得下 6 列时，按 6 列排，不会出现半张卡。 */
    @Test
    fun `manual choice is clamped by affordable width`() {
        assertEquals(6, PosterLayout.affordableColumns(788f))
        // 508dp 内容区在保证单张 ≥112dp 的前提下能挤下 4 列，再往上就不允许了。
        assertEquals(4, PosterLayout.affordableColumns(508f))
    }
}
