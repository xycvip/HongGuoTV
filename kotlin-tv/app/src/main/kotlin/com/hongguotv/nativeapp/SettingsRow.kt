// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 设置页的一行：左标题、右当前值、右端箭头。
 *
 * 改造前每个设置项是四列网格里的一个居中按钮，「标题 · 当前值」全挤在同一种字号里，
 * 于是「默认清晰度 · 自动（1080P 优先）」在 176dp 宽的格子里被截成「默认清晰度 · 自动（…」——
 * 标题完整、状态被砍，恰好砍掉了用户最想看的那一半。
 *
 * 拆成三栏后：值单独成栏、长文本只截值不截标题，右端箭头表明"还能继续选"。
 *
 * 兼容性：[text] 的读写语义与原来的 TextView 一致（仍是"标题 · 值"的完整串），
 * 所以设置页里二十多处 `row.text = "画质 · ${...}"` 的更新代码一行都不用改。
 */
internal class SettingsRow(context: Context) : LinearLayout(context) {

    private val bar = View(context)
    private val titleView = TextView(context)
    private val valueView = TextView(context)
    private val chevron = TextView(context)
    private var titleText = ""
    private var valueText = ""
    private var focusedNow = false

    /** 焦点变化回调。不能直接用 setOnFocusChangeListener：本类需要先重绘自己。 */
    var onFocusChanged: ((Boolean) -> Unit)? = null

    /** 与改造前一致的完整文案；写入时自动拆成标题与值两栏。 */
    var text: CharSequence
        get() = if (valueText.isEmpty()) titleText else "$titleText · $valueText"
        set(value) = setContent(value.toString())

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        // 焦点放大 1.2% 会越过自身边界，必须放开裁剪，否则左右两端被啃掉。
        clipChildren = false
        isFocusable = true
        isFocusableInTouchMode = true
        minimumHeight = TvMetric.dp(context, TvMetric.ROW_HEIGHT)
        setPadding(TvMetric.dp(context, 17), 0, TvMetric.dp(context, 12), 0)

        bar.setBackgroundColor(Color.TRANSPARENT)
        addView(bar, LayoutParams(TvMetric.dp(context, 3), TvMetric.dp(context, 20)).apply {
            rightMargin = TvMetric.dp(context, 13)
        })

        titleView.apply {
            textSize = TvMetric.TEXT_LABEL
            setTextColor(TvStyle.colors.textPrimary)
            includeFontPadding = false
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        addView(titleView, LayoutParams(0, -2, 1f))

        valueView.apply {
            textSize = TvMetric.TEXT_BODY
            setTextColor(TvStyle.colors.textSecondary)
            includeFontPadding = false
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            // 值栏上限跟着屏幕走：1080p 电视上约 400dp，720p 上约 270dp，
            // 固定 360dp 会在窄屏把左侧标题挤到只剩几个字。
            maxWidth = TvMetric.dp(
                context,
                (
                    context.resources.displayMetrics.widthPixels /
                        context.resources.displayMetrics.density * 0.42f
                    ).toInt().coerceIn(200, 420),
            )
            setPadding(TvMetric.dp(context, 12), 0, TvMetric.dp(context, 6), 0)
        }
        addView(valueView, LayoutParams(-2, -2))

        chevron.apply {
            text = "›"
            textSize = 17f
            setTextColor(TvStyle.colors.textTertiary)
            includeFontPadding = false
            gravity = Gravity.CENTER
        }
        addView(chevron, LayoutParams(TvMetric.dp(context, 16), -2))

        setOnFocusChangeListener { _, focused ->
            paint(focused)
            onFocusChanged?.invoke(focused)
        }
        paint(false)
    }

    /** 焦点态需要重新绘制时调用（例如主题切换后）。 */
    fun refreshTone() = paint(hasFocus())

    private fun setContent(raw: String) {
        val separator = raw.indexOf('·')
        if (separator < 0) {
            titleText = raw.trim()
            valueText = ""
        } else {
            titleText = raw.substring(0, separator).trim()
            valueText = raw.substring(separator + 1).trim()
        }
        titleView.text = titleText
        valueView.text = valueText
        valueView.visibility = if (valueText.isEmpty()) GONE else VISIBLE
    }

    private fun paint(focused: Boolean) {
        focusedNow = focused
        background = TvStyle.shape(
            context,
            if (focused) TvStyle.colors.surfaceRaised else TvStyle.colors.surface,
            if (focused) TvStyle.colors.focusRing else Color.TRANSPARENT,
            TvMetric.RADIUS_M,
        )
        // 左侧竖条只在获得焦点时亮起，用强调色标出"当前这一行"，代替整块高亮。
        bar.setBackgroundColor(if (focused) TvStyle.colors.accent else Color.TRANSPARENT)
        titleView.typeface = Typeface.create(
            if (focused) "sans-serif-medium" else "sans-serif",
            Typeface.NORMAL,
        )
        valueView.setTextColor(
            if (focused) TvStyle.colors.textPrimary else TvStyle.colors.textSecondary,
        )
        chevron.setTextColor(
            if (focused) TvStyle.colors.textSecondary else TvStyle.colors.textTertiary,
        )
        elevation = if (focused) TvMetric.dp(context, 6).toFloat() else 0f
        // 整行接近整屏宽，放大比例必须比按钮小得多，否则会顶到两侧页边距。
        animateFocus(focused, 1.008f)
    }
}
