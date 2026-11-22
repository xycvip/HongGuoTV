// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * PosterCard 的 Robolectric 测试：沙箱没有 KVM 起不了模拟器，
 * 用 JVM 上的真实 Android 框架验证卡片构建、进度条与焦点态。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PosterCardTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun `海报卡包含图片、标题、副信息与进度轨道`() {
        val poster = PosterCard.create(context, PosterCard.Mode.POSTER)
        assertNotNull(poster.image)
        assertNotNull(poster.title)
        assertNotNull(poster.badge)
        assertNotNull(poster.rank)
        assertNotNull(poster.progressFill)
        assertNotNull(poster.progressRest)
        assertNull(poster.tile)
        assertSame(poster.card, poster.artwork?.parent)
    }

    @Test
    fun `海报高度按 0-7 宽高比自动测量`() {
        val poster = PosterCard.create(context, PosterCard.Mode.POSTER)
        val widthPx = 300 * context.resources.displayMetrics.density.toInt()
        poster.card.layoutParams = FrameLayout.LayoutParams(widthPx, FrameLayout.LayoutParams.WRAP_CONTENT)
        poster.card.measure(
            View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        // 海报在卡内左右各缩 3dp，高度按海报自身实际宽度（而非卡宽）除以 0.7。
        val expected = (poster.artwork!!.measuredWidth / TvStyle.POSTER_ASPECT).toInt()
        assertEquals(expected, poster.artwork!!.measuredHeight)
    }

    @Test
    fun `显式声明的海报高度优先于比例`() {
        val poster = PosterCard.create(context, PosterCard.Mode.POSTER)
        // 首页货架压矮海报走的就是这条路：fitArtwork 直接改 layoutParams.height。
        poster.artwork!!.layoutParams = FrameLayout.LayoutParams(100, 120)
        poster.card.measure(
            View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        assertEquals(120, poster.artwork!!.measuredHeight)
    }

    @Test
    fun `进度比例为 null 时隐藏轨道`() {
        val poster = PosterCard.create(context, PosterCard.Mode.POSTER)
        poster.setProgressRatio(.5f)
        assertEquals(View.VISIBLE, poster.progressFill!!.visibility)
        poster.setProgressRatio(null)
        assertEquals(View.GONE, poster.progressFill!!.visibility)
        assertEquals(View.GONE, poster.progressRest!!.visibility)
    }

    @Test
    fun `进度比例夹在 0 到 1 之间`() {
        val poster = PosterCard.create(context, PosterCard.Mode.POSTER)
        poster.setProgressRatio(1.7f)
        val fill = poster.progressFill!!.layoutParams as android.widget.LinearLayout.LayoutParams
        val rest = poster.progressRest!!.layoutParams as android.widget.LinearLayout.LayoutParams
        assertTrue(fill.weight in 0f..0.999f)
        assertEquals(1f - fill.weight, rest.weight, 1e-6f)
    }

    @Test
    fun `焦点态画白环并抬升，失焦还原`() {
        val poster = PosterCard.create(context, PosterCard.Mode.POSTER)
        poster.paintFocus(true)
        assertNotNull(poster.card.background)
        assertTrue(poster.card.elevation > 0f)
        poster.paintFocus(false)
        assertNull(poster.card.background)
        assertEquals(0f, poster.card.elevation, 1e-6f)
    }

    @Test
    fun `MORE 与 RANDOM 模式没有海报只有操作面板`() {
        val more = PosterCard.create(context, PosterCard.Mode.MORE)
        val random = PosterCard.create(context, PosterCard.Mode.RANDOM)
        assertNull(more.image)
        assertNotNull(more.tile)
        assertNull(random.image)
        assertNotNull(random.tile)
        // RANDOM 是横向排版、MORE 是纵向排版。
        assertEquals(
            android.widget.LinearLayout.HORIZONTAL,
            (random.tile as android.widget.LinearLayout).orientation,
        )
        assertEquals(
            android.widget.LinearLayout.VERTICAL,
            (more.tile as android.widget.LinearLayout).orientation,
        )
    }

    @Test
    fun `操作卡支持显式尺寸`() {
        val tile = PosterCard.create(context, PosterCard.Mode.MORE, tileWidthPx = 320, tileHeightPx = 120).tile!!
        assertEquals(320, tile.layoutParams.width)
        assertEquals(120, tile.layoutParams.height)
    }

    @Test
    fun `图片尺寸类型与容器一致`() {
        val poster = PosterCard.create(context, PosterCard.Mode.POSTER)
        assertTrue(poster.image is ImageView)
        assertTrue(poster.title is TextView)
        // 图片随容器裁剪：CENTER_CROP 保证任何比例的封面都不留黑边。
        assertEquals(ImageView.ScaleType.CENTER_CROP, poster.image!!.scaleType)
        assertFalse(poster.card.clipChildren)
    }
}
