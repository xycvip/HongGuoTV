// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 真机/模拟器上的仪器化测试。沙箱没有 KVM 无法执行；
 * 上 TV 实机后用 `./gradlew :app:connectedDebugAndroidTest` 跑。
 */
@RunWith(AndroidJUnit4::class)
class PosterCardInstrumentedTest {

    @Test
    fun posterCardBuildsOnDevice() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val poster = PosterCard.create(context, PosterCard.Mode.POSTER)
        assertNotNull(poster.image)
        assertNotNull(poster.title)
        // 真机分辨率下海报高度应严格等于宽度的 0.7 比例。
        poster.card.layoutParams = android.widget.FrameLayout.LayoutParams(700, android.widget.FrameLayout.LayoutParams.WRAP_CONTENT)
        poster.card.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(700, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED),
        )
        assertEquals(1000, poster.artwork!!.measuredHeight)
    }

    @Test
    fun posterLayoutResolvesRealScreen() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val metrics = context.resources.displayMetrics
        val widthDp = metrics.widthPixels / metrics.density
        val heightDp = metrics.heightPixels / metrics.density
        val columns = PosterLayout.autoColumns(widthDp, heightDp)
        assertTrue("1080p 电视应为 5 列，实际 $columns", columns in 2..7)
    }
}
