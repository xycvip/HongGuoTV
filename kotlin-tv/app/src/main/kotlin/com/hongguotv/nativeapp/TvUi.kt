// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 视觉尺度令牌。
 *
 * 改造前全站有 11 种字号（11/12/13/14/15/16/18/19/20/21/42sp）和 253 处手写 padding。
 * 相邻两级只差 1sp，眼睛分辨不出层级，远看就是一片平——这是"小家子气"最根本的来源，
 * 不是配色问题。
 *
 * 这里把可用档位收敛成有限集合：字号 7 档、间距 6 档、圆角 5 档。
 * 新增界面只从这些档位取值；需要新档位时，先回答一个问题：
 * "现有的 7 档为什么不够用？" 而不是顺手写一个 15.5f。
 */
internal object TvMetric {
    /** 间距（dp）。8 的倍数为主，4 只用于图标与文字之间的微调。 */
    const val GAP_XS = 4
    const val GAP_S = 8
    const val GAP_M = 12
    const val GAP_L = 16
    const val GAP_XL = 24
    const val GAP_XXL = 32
    const val GAP_SECTION = 40

    /** 字号（sp）。caption 用于辅助信息，body 用于正文，label 用于可点元素。 */
    const val TEXT_CAPTION = 12.5f
    const val TEXT_BODY = 14f
    const val TEXT_LABEL = 15f
    const val TEXT_SUBTITLE = 17f
    const val TEXT_TITLE = 22f
    const val TEXT_HEADLINE = 30f
    const val TEXT_DISPLAY = 34f

    /** 圆角（dp）。 */
    const val RADIUS_S = 8
    const val RADIUS_M = 12
    const val RADIUS_L = 16
    const val RADIUS_XL = 20
    const val RADIUS_PILL = 28

    /** 行高（dp）。设置行、列表行统一这两个值，不再逐处试数字。 */
    const val RAIL_WIDE = 104
    const val RAIL_NARROW = 96
    const val ROW_HEIGHT = 56
    const val ROW_HEIGHT_COMPACT = 44

    fun dp(context: Context, value: Number): Int =
        (value.toFloat() * context.resources.displayMetrics.density).toInt()
}

/**
 * 焦点位移：遥控器逐格移动时给一点空间反馈。
 *
 * 电视上 80% 的"高级感"来自焦点反馈是否跟手。原来只有背景换色，焦点像"网页 tab 切换"；
 * 加上 150ms 减速的轻微放大后，视线能跟住光标落在哪一格。
 *
 * 注意：放大需要父容器 clipChildren=false，否则边缘会被裁成方角。
 * 调用方如果放在 RecyclerView 或 ScrollView 里，应确认外层已经放开裁剪。
 */
internal fun View.animateFocus(focused: Boolean, scale: Float = 1.035f) {
    animate().cancel()
    animate()
        .scaleX(if (focused) scale else 1f)
        .scaleY(if (focused) scale else 1f)
        .setDuration(if (focused) 150L else 110L)
        .setInterpolator(DecelerateInterpolator())
        .start()
}

/**
 * 按钮语气。
 *
 * 改造前所有按钮长得一样：灰底 + 橙色描边，主次不分。一屏里出现七八个同等权重的按钮，
 * 用户的视线没有落点。分级之后，主操作（开始观看）用实心强调色，次操作用描边，
 * 纯文字操作（取消/更多）用 ghost，筛选条件用 chip。
 */
internal enum class TvTone { Primary, Secondary, Ghost, Chip }

internal fun MainActivity.toneButton(
    label: String,
    tone: TvTone = TvTone.Secondary,
    selected: Boolean = false,
    onClick: () -> Unit = {},
): TextView {
    val colors = TvStyle.colors
    val view = text(label, if (tone == TvTone.Chip) TvMetric.TEXT_BODY else TvMetric.TEXT_LABEL)
    view.apply {
        gravity = Gravity.CENTER
        includeFontPadding = false
        id = View.generateViewId()
        isFocusable = true
        isFocusableInTouchMode = true
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
        when (tone) {
            TvTone.Chip -> {
                setPadding(dp(TvMetric.GAP_M), dp(8), dp(TvMetric.GAP_M), dp(8))
                minHeight = dp(36)
            }
            else -> {
                setPadding(dp(TvMetric.GAP_L), dp(10), dp(TvMetric.GAP_L), dp(10))
                minHeight = dp(TvMetric.ROW_HEIGHT_COMPACT)
            }
        }
        fun paint(focused: Boolean) {
            val fill: Int
            val ink: Int
            val stroke: Int
            val radius: Int
            when (tone) {
                TvTone.Primary -> {
                    fill = if (focused) colors.accentPressed else colors.accent
                    ink = colors.onAccent
                    stroke = if (focused) colors.focusRing else Color.TRANSPARENT
                    radius = TvMetric.RADIUS_M
                }
                TvTone.Secondary -> {
                    fill = if (focused) colors.surfaceRaised else colors.surface
                    ink = colors.textPrimary
                    stroke = if (focused) colors.focusRing else colors.strokeSubtle
                    radius = TvMetric.RADIUS_M
                }
                TvTone.Ghost -> {
                    fill = if (focused) withAlpha(colors.textPrimary, 0.14f) else Color.TRANSPARENT
                    ink = if (focused || selected) colors.textPrimary else colors.textSecondary
                    stroke = Color.TRANSPARENT
                    radius = TvMetric.RADIUS_M
                }
                TvTone.Chip -> {
                    fill = when {
                        focused -> colors.accent
                        selected -> withAlpha(colors.accent, 0.16f)
                        else -> colors.surface
                    }
                    ink = when {
                        focused -> colors.onAccent
                        selected -> colors.accent
                        else -> colors.textSecondary
                    }
                    stroke = if (selected && !focused) withAlpha(colors.accent, 0.45f) else Color.TRANSPARENT
                    radius = TvMetric.RADIUS_PILL
                }
            }
            background = TvStyle.shape(this@toneButton, fill, stroke, radius)
            setTextColor(ink)
            typeface = Typeface.create(
                if (focused || selected) "sans-serif-medium" else "sans-serif",
                Typeface.NORMAL,
            )
            elevation = if (focused) dp(6).toFloat() else 0f
            animateFocus(focused)
        }
        paint(false)
        setOnFocusChangeListener { _, focused -> paint(focused) }
        setOnClickListener { onClick() }
    }
    return view
}

/**
 * 筛选条件胶囊。
 *
 * 剧场/排行榜/追剧里的筛选项原来和"开始播放"共用同一套灰底描边按钮：
 * 一屏十几颗同等重量的按钮，用户分不清哪个是条件、哪个是动作。
 * 胶囊用更小的体量和全圆角明确表示"可切换的标签"，选中态只改文字与浅底，
 * 焦点态才填充强调色——和主按钮的实心强调色拉开距离。
 */
internal fun MainActivity.addChip(
    parent: LinearLayout,
    label: String,
    selected: Boolean = false,
    onClick: () -> Unit = {},
): TextView {
    val view = toneButton(label, TvTone.Chip, selected, onClick)
    parent.addView(view, lp().apply {
        width = LinearLayout.LayoutParams.WRAP_CONTENT
        rightMargin = dp(TvMetric.GAP_S)
    })
    return view
}

/** 一整行分隔线。用 1dp 的 strokeSubtle，比一堆圆角卡片更安静。 */
internal fun MainActivity.hairline(): View = View(this).apply {
    setBackgroundColor(TvStyle.colors.strokeSubtle)
    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
}

/** 区块标题：左侧标题，右侧补充说明。两者共用一条基线，避免"提示文字贴住标题"。 */
internal fun MainActivity.sectionHeader(
    title: String,
    hint: String? = null,
    size: Float = TvMetric.TEXT_SUBTITLE,
): LinearLayout {
    val line = row()
    line.addView(
        text(title, size).apply {
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        },
        lp(0, -2).apply { weight = 1f },
    )
    if (!hint.isNullOrBlank()) {
        line.addView(
            text(hint, TvMetric.TEXT_CAPTION, TvStyle.colors.textTertiary).apply {
                gravity = Gravity.CENTER_VERTICAL
            },
        )
    }
    return line
}

/**
 * 统一的文案写入。设置项从 TextView 换成 [SettingsRow] 之后，弹窗回调里仍要更新"当前值"，
 * 用它同时兼容两种载体，避免每处都写 when。
 */
internal fun setLabel(view: View?, value: CharSequence) {
    when (view) {
        is SettingsRow -> view.text = value
        is TextView -> view.text = value
    }
}

/** 海报底部渐隐。终点用墨黑而非主题背景，浅色主题下也能压住浅色画面。 */
internal fun MainActivity.posterScrim(alpha: Float = .90f) = android.graphics.drawable.GradientDrawable(
    android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
    intArrayOf(Color.TRANSPARENT, withAlpha(TvPalette.INK_950, alpha)),
)

/** 按语气添加一个按钮，并统一右侧间距。 */
internal fun MainActivity.addToneButton(
    parent: LinearLayout,
    label: String,
    tone: TvTone,
    selected: Boolean = false,
    onClick: () -> Unit = {},
): TextView {
    val view = toneButton(label, tone, selected, onClick)
    parent.addView(view, lp().apply {
        width = LinearLayout.LayoutParams.WRAP_CONTENT
        rightMargin = dp(TvMetric.GAP_M)
    })
    return view
}

/**
 * 顶部导航项的统一画法。
 *
 * 改造前的焦点态是"整块纯白底 + 深色字"，在磨砂胶囊里像贴了一张白标签；
 * 当前页用棕褐色块，同样抢眼。现在焦点是"半透明白底 + 白色描边环 + 6% 放大"，
 * 当前页是"强调色文字 + 14% 强调底色"，两者层次分明，也不会盖住品牌色。
 */
internal fun MainActivity.paintNavItem(item: TextView, focused: Boolean, active: Boolean) {
    item.setTextColor(
        when {
            focused -> TvStyle.colors.textPrimary
            active -> TvStyle.colors.accent
            else -> TvStyle.colors.textSecondary
        },
    )
    item.background = when {
        focused -> TvStyle.shape(
            this,
            withAlpha(TvStyle.colors.textPrimary, 0.16f),
            TvStyle.colors.focusRing,
            TvMetric.RADIUS_S,
        )
        active -> TvStyle.shape(this, withAlpha(TvStyle.colors.accent, 0.14f), Color.TRANSPARENT, TvMetric.RADIUS_S)
        else -> null
    }
    item.typeface = Typeface.create(
        if (focused || active) "sans-serif-medium" else "sans-serif",
        Typeface.NORMAL,
    )
    item.elevation = if (focused) dp(5).toFloat() else 0f
    item.animateFocus(focused, 1.06f)
}
