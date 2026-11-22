// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.View
import android.view.ViewGroup

/**
 * 选中时的「选项框」—— 全站唯一绘制入口，推倒重写。
 *
 * 此前焦点框散落在十几处（PosterCard / focusStyle / SettingsRow / toneButton /
 * ThirdSourceGridAdapter ...），各画各的，且多数伴随「整体缩放 + elevation 投影」。
 * 真机反复回归的「选项框缺角」只有一个根因：**焦点态有像素越出 View 自身边界**，
 * 而滚动容器必须保留裁剪（防半滚出的海报叠上竖栏），越界部分就必然被切。
 * 打多少补丁兜呼吸位都兜不住，越界就会被裁 —— 所以重写为一条铁律：
 *
 *  **焦点态不许有任何一个像素越出 View 边界：不缩放、不投影、环画在边界之内。**
 *
 * 视觉上换来的是「外圈柔光 + 内圈白环」双层环（[ring]）：外圈用 focusGlow
 * （强调色 30% 透明度）铺 5dp，白环 2dp 压在最外缘，深浅两套主题下都醒目，
 * 且整个环带都在 View 内 —— 任何容器、任何边缘、任何页面都不可能缺角。
 *
 * **环必须画在前景（[frame]），不能画在背景**：海报/封面等子内容会盖在背景之上，
 * 背景环会被啃掉——卡片有内缩时四角的圆弧被子内容直角盖掉（真机：首页四个角），
 * 图片填满卡片时整圈环被完全盖住（真机：其他页面选项框缺失）。前景画在
 * 所有子内容之上，谁都盖不住它。
 *
 * 本文件同时收敛焦点链与裁剪策略（原 FocusRules）：
 *  - [applyRailChain] 竖栏上下链，只在建栏时写一次，页面渲染不得改写；
 *  - [unclipTree] 非滚动容器放开裁剪（保呼吸位内的细节），滚动容器只放开
 *    clipToPadding（视口外内容不得越界绘制）。
 */
internal object FocusFrame {

    /**
     * 焦点环 drawable：外圈柔光 5dp + 内圈白环 2dp，全部画在 View 边界之内。
     *
     * 两层都是 [GradientDrawable] 描边（从边缘向内画），叠加后从外到内是
     * 「白环 2dp → 柔光 3dp」，不占用边界外的任何像素。
     */
    fun ring(
        context: Context,
        fill: Int = Color.TRANSPARENT,
        radius: Int = TvMetric.RADIUS_M,
        stroke: Int = TvStyle.colors.focusRing,
        halo: Int = TvStyle.colors.focusGlow,
    ): Drawable {
        val density = context.resources.displayMetrics.density
        val outer = GradientDrawable().apply {
            setColor(fill)
            cornerRadius = radius * density
            setStroke((5 * density).toInt(), halo)
        }
        val inner = GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            cornerRadius = radius * density
            setStroke((2 * density).toInt(), stroke)
        }
        return LayerDrawable(arrayOf(outer, inner))
    }

    /**
     * 前景选项框：环画在所有子内容之上（foreground），海报/封面/文字都盖不住它。
     * 失焦时清掉前景，不留任何残影。仍是零越界（环带完全在 View 边界内）。
     */
    fun frame(view: View, focused: Boolean, radius: Int = TvMetric.RADIUS_M) {
        view.foreground = if (focused) {
            ring(view.context, Color.TRANSPARENT, radius)
        } else null
    }

    /**
     * 通用焦点绘制。背景只负责填充，选项框交给前景 [frame]；
     * 卡片自身的缩放与投影一律归零（铁律的执行处）。
     */
    fun paint(
        view: View,
        focused: Boolean,
        normalFill: Int,
        normalStroke: Int = Color.TRANSPARENT,
        focusedFill: Int = TvStyle.colors.surfaceRaised,
        radius: Int = TvMetric.RADIUS_M,
    ) {
        view.background = if (focused) {
            TvStyle.shape(view.context, focusedFill, Color.TRANSPARENT, radius)
        } else {
            TvStyle.shape(view.context, normalFill, normalStroke, radius)
        }
        frame(view, focused, radius)
        view.elevation = 0f
        view.animate().cancel()
        view.scaleX = 1f
        view.scaleY = 1f
    }

    /**
     * 图片卡焦点（详情推荐/远程/随机弹窗/账户作品架等）：背景只管填充，
     * 选项框走前景——卡内的封面图填满整卡也盖不住环。
     */
    fun paintCard(view: View, focused: Boolean, radius: Int = TvMetric.RADIUS_M) {
        view.background = TvStyle.shape(
            view.context,
            if (focused) TvStyle.colors.surfaceRaised else TvStyle.colors.surface,
            Color.TRANSPARENT,
            radius,
        )
        frame(view, focused, radius)
        view.elevation = 0f
        view.animate().cancel()
        view.scaleX = 1f
        view.scaleY = 1f
    }

    /**
     * 竖栏内部焦点链：从品牌灯到工具区是一条连续的上下链，
     * 链尾自指，按过头会停在最后一项而不是跳走。
     */
    fun applyRailChain(items: List<View>) {
        items.forEachIndexed { index, item ->
            item.nextFocusUpId = items.getOrNull(index - 1)?.id ?: item.id
            item.nextFocusDownId = items.getOrNull(index + 1)?.id ?: item.id
            item.nextFocusLeftId = item.id
        }
    }

    /**
     * 页面渲染完成后对整棵内容树放开裁剪。
     *
     * 两类裁剪要区分对待：
     *  - **非滚动容器**：全部放开。焦点环虽已零越界，放开后中间容器的默认
     *    裁剪不会误伤任何边缘细节。
     *  - **滚动容器（RecyclerView/ScrollView/ListView 等）**：只放开
     *    clipToPadding，**保留 clipChildren**。视口外的半张海报属于"还没滚进来"
     *    的内容，一旦越界绘制就会叠到竖栏上（真机实测）。
     */
    fun unclipTree(view: ViewGroup) {
        val scroll = view is android.widget.ScrollView ||
            view is android.widget.HorizontalScrollView ||
            view is androidx.recyclerview.widget.RecyclerView ||
            view is android.widget.ListView ||
            view is android.widget.GridView
        if (!scroll) view.clipChildren = false
        view.clipToPadding = false
        for (index in 0 until view.childCount) {
            val child = view.getChildAt(index)
            if (child is ViewGroup) unclipTree(child)
        }
    }
}
