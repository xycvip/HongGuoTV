// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

private fun Context.dp(n: Int)=(n*resources.displayMetrics.density).toInt()

/**
 * 全站统一的海报卡构建器。
 *
 * 此前首页货架（HomeScreen）与海报网格（CatalogAdapter）各养了一套卡片代码：
 * 焦点环画法、遮罩高度、进度条、字号全都各自维护，改一处忘一处。现在统一到这里，
 * 两个页面只负责「数据怎么绑、焦点怎么跳」，视觉一律走本类。
 *
 *  - 卡片本体是透明 FrameLayout，焦点白环画在卡背景上，海报外缩 3dp 留出环的呼吸位；
 *  - 海报按宽度 0.7 比例自动测高，首页需要压矮时直接改 layoutParams.height；
 *  - 标题/副信息压在底部压暗层上，进度条贴海报底边，名次方牌压左上角；
 *  - 「查看全部 / 随机推荐」用 MORE / RANDOM 模式，只有操作面板没有海报。
 */
internal class PosterCard private constructor(
    val card: FrameLayout,
    val artwork: View?,
    val image: ImageView?,
    val title: TextView?,
    val badge: TextView?,
    val rank: TextView?,
    val progressFill: View?,
    val progressRest: View?,
    val tile: View?,
) {
    /** 海报卡：剧场/榜单/追剧/搜索/首页货架共用。 */
    enum class Mode { POSTER, MORE, RANDOM }

    /**
     * 焦点态：选项框交给 [FocusFrame] 统一绘制（外圈柔光 + 内圈白环）。
     *
     * 铁律：卡片自身不缩放、不投影，每一个像素都不越出边界 —— 滚动容器
     * 必须保留裁剪（防半滚出的海报叠上竖栏），任何越界都会被裁成缺角。
     * 海报的轻微放大只作用于卡内 artwork（封顶 1.02，收在 3dp 内缩里）。
     *
     * 环画在**前景**而不是背景：artwork 子视图盖在背景之上，会把背景环啃掉——
     * 3dp 内缩的卡片四角圆弧被 artwork 直角盖住（真机：首页四个角）。
     * 前景环在所有内容之上，四边四角永远完整。
     */
    fun paintFocus(focused: Boolean, scale: Float = 1.02f, ringRadius: Int = TvMetric.RADIUS_M) {
        card.background = null
        FocusFrame.frame(card, focused, ringRadius)
        card.elevation = 0f
        card.animate().cancel(); card.scaleX = 1f; card.scaleY = 1f
        (artwork ?: tile)?.animateFocus(focused, minOf(scale, 1.02f))
    }

    /** 观看进度：null 隐藏；否则按比例填充海报底边进度条。 */
    fun setProgressRatio(ratio: Float?) {
        val fill = progressFill ?: return
        val rest = progressRest ?: return
        if (ratio == null) {
            fill.visibility = View.GONE; rest.visibility = View.GONE
            return
        }
        val value = ratio.coerceIn(0f, .999f)
        (fill.layoutParams as? LinearLayout.LayoutParams)?.let { it.weight = value; fill.layoutParams = it }
        (rest.layoutParams as? LinearLayout.LayoutParams)?.let { it.weight = 1f - value; rest.layoutParams = it }
        fill.visibility = View.VISIBLE; rest.visibility = View.VISIBLE
    }

    companion object {
        /** [tileWidthPx]/[tileHeightPx]：操作卡（MORE/RANDOM）的显式尺寸；不传按内容自适应。 */
        fun create(context: Context, mode: Mode, tileWidthPx: Int? = null, tileHeightPx: Int? = null): PosterCard {
            val card = FrameLayout(context).apply {
                setPadding(context.dp(3), context.dp(3), context.dp(3), context.dp(3))
                clipChildren = false
                isFocusable = true
                isFocusableInTouchMode = true
            }
            if (mode != Mode.POSTER) {
                val tile = actionTile(context, mode)
                val params = if (tileWidthPx != null || tileHeightPx != null) {
                    FrameLayout.LayoutParams(tileWidthPx ?: -1, tileHeightPx ?: -2, Gravity.CENTER)
                } else FrameLayout.LayoutParams(-1, -2, Gravity.CENTER)
                card.addView(tile, params)
                return PosterCard(card, tile, null, null, null, null, null, null, tile)
            }
            // 海报容器按自身实际测量宽度定高，回收复用、列宽变化都不会出现同屏高低不一；
            // 首页需要压矮海报时直接改 layoutParams.height，这里优先尊重显式高度。
            // 高度必须来自本轮 super.onMeasure 得到的 measuredWidth：若直接取
            // MeasureSpec 规格，布局中途列宽变化（换列/容器加宽）会把旧规格的高度
            // 残留到新宽度上，真机上出现过首张卡比同排高一半的废版式。
            val artwork = object : FrameLayout(context) {
                override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                    val declared = layoutParams?.height ?: -2
                    if (declared >= 0) {
                        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(declared, MeasureSpec.EXACTLY))
                        return
                    }
                    if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.EXACTLY) {
                        val h = (MeasureSpec.getSize(widthMeasureSpec) / TvStyle.POSTER_ASPECT).toInt()
                        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
                    } else {
                        // 宽度不确定（wrap 内容）：先量一遍拿实际宽度，再按海报比例定高重量。
                        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
                        val h = (measuredWidth / TvStyle.POSTER_ASPECT).toInt()
                        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
                    }
                }
            }
            val image = ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            // 海报本身裁成圆角：底板圆角而图片方角会在边缘露出一圈直角。
            artwork.background = TvStyle.shape(context, TvStyle.colors.surface, radius = 12)
            artwork.clipToOutline = true
            artwork.addView(image, FrameLayout.LayoutParams(-1, -1))
            // 底部压暗：剧照亮度不可控（白底、雪景、高光都常见），标题必须始终读得出来。
            artwork.addView(
                View(context).apply {
                    background = posterScrim(context, .96f)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                },
                FrameLayout.LayoutParams(-1, context.dp(104), Gravity.BOTTOM),
            )
            // 进度条贴海报底边：远距离也能看出看到哪了。
            val track = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            val fill = View(context).apply { setBackgroundColor(TvStyle.colors.accent) }
            val rest = View(context).apply { setBackgroundColor(withAlpha(TvPalette.INK_100, .20f)) }
            track.addView(fill, LinearLayout.LayoutParams(0, context.dp(4), 0f))
            track.addView(rest, LinearLayout.LayoutParams(0, context.dp(4), 1f))
            artwork.addView(track, FrameLayout.LayoutParams(-1, context.dp(4), Gravity.BOTTOM))

            val info = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(context.dp(11), 0, context.dp(11), context.dp(9))
            }
            val title = TextView(context).apply {
                textSize = TvMetric.TEXT_LABEL; includeFontPadding = false
                setLines(1); ellipsize = TextUtils.TruncateAt.END
                setTextColor(withAlpha(TvPalette.INK_100, .97f))
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            }
            // 副信息一行封顶：有无副信息都不会让标题上下跳。
            val badge = TextView(context).apply {
                textSize = TvMetric.TEXT_CAPTION; includeFontPadding = false
                maxLines = 1; minLines = 1
                setPadding(0, context.dp(3), 0, 0)
                ellipsize = TextUtils.TruncateAt.END
                setTextColor(withAlpha(TvPalette.INK_300, .92f))
            }
            info.addView(title, LinearLayout.LayoutParams(-1, -2))
            info.addView(badge, LinearLayout.LayoutParams(-1, -2))
            artwork.addView(info, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { bottomMargin = context.dp(3) })

            // 名次方牌：榜单页用，海报页保持 GONE。
            val rank = TextView(context).apply {
                textSize = TvMetric.TEXT_BODY; gravity = Gravity.CENTER; includeFontPadding = false
                minWidth = context.dp(24); minHeight = context.dp(24)
                setPadding(context.dp(7), context.dp(2), context.dp(7), context.dp(3))
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(TvPalette.INK_100)
                visibility = View.GONE
            }
            artwork.addView(rank, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply {
                leftMargin = context.dp(9); topMargin = context.dp(9)
            })

            card.addView(artwork, FrameLayout.LayoutParams(-1, -2))
            return PosterCard(card, artwork, image, title, badge, rank, fill, rest, null)
        }

        /** 「查看全部」与随机推荐的操作面板：渐变底 + 描边，两种排版各占一个模式。 */
        private fun actionTile(context: Context, mode: Mode): LinearLayout {
            fun label(value: String, size: Float, color: Int) = TextView(context).apply {
                text = value; textSize = size; setTextColor(color); includeFontPadding = false
                gravity = Gravity.CENTER
            }
            val tile = LinearLayout(context).apply {
                gravity = Gravity.CENTER
                setPadding(context.dp(8), context.dp(10), context.dp(8), context.dp(10))
                background = android.graphics.drawable.GradientDrawable(
                    android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                    intArrayOf(TvStyle.surface, TvStyle.background),
                ).apply {
                    cornerRadius = context.dp(8).toFloat()
                    setStroke(context.dp(1), TvStyle.outline)
                }
            }
            if (mode == Mode.RANDOM) {
                tile.orientation = LinearLayout.HORIZONTAL
                tile.addView(label("✦", 25f, TvStyle.accent).apply { setPadding(0, 0, context.dp(14), 0) })
                tile.addView(
                    label("不知道看什么·点一下", 16f, TvStyle.text).apply {
                        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    },
                )
                tile.addView(label("  ›", 20f, TvStyle.accent))
            } else {
                tile.orientation = LinearLayout.VERTICAL
                tile.addView(label("✦", 30f, TvStyle.accent).apply { setPadding(0, 0, 0, context.dp(8)) })
                tile.addView(
                    label("探索更多", 14f, TvStyle.text).apply {
                        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    },
                )
                tile.addView(label("发现精彩剧集  ›", 10f, TvStyle.muted).apply { setPadding(0, context.dp(6), 0, 0) })
            }
            return tile
        }

        /** 海报底部渐隐；终点用墨黑而非主题背景，浅色主题下也能压住浅色画面。 */
        private fun posterScrim(context: Context, endAlpha: Float) = android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Color.TRANSPARENT, withAlpha(TvPalette.INK_950, endAlpha)),
        )
    }
}
