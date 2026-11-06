// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ReplacementSpan
import android.view.Gravity
import android.view.View
import android.widget.TextView
import kotlin.math.ceil
import kotlin.math.floor

/** Poster-width genre chips: theme accent outline/text, complete borders and adaptive spacing. */
internal fun setGenreTagChips(view: TextView, rawTags: String, density: Float) {
    val tags = rawTags
        .split(Regex("\\s*[·|，,、]\\s*"))
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinct()
        .take(4)

    if (tags.isEmpty()) {
        view.text = ""
        view.visibility = View.INVISIBLE
        return
    }

    view.maxLines = 1
    view.minLines = 1
    view.ellipsize = null
    view.gravity = Gravity.CENTER
    view.setTextColor(TvStyle.accent)
    // 标签区域与海报内容区域同宽；不要在 TextView 内额外缩进。
    view.setPadding(0, 0, 0, 0)

    fun renderForWidth() {
        if (view.width <= 0) return
        val available = (view.width - view.paddingLeft - view.paddingRight).toFloat()
        val horizontalPadding = 2f * density
        val stroke = 1f * density
        val maxSize = 10.5f
        val minSize = 8f
        val paint = view.paint

        // 先尽可能保留4项，再依次尝试3项、2项；每项文字完整显示。
        var chosenCount = 0
        var chosenSize = maxSize
        var chosenGap = 0f
        for (count in minOf(4, tags.size) downTo 1) {
            var size = maxSize
            var widths = FloatArray(count)
            fun measure(): Float {
                paint.textSize = size * density
                widths = FloatArray(count) { index ->
                    paint.measureText(tags[index]) + horizontalPadding * 2f + stroke
                }
                return widths.sum()
            }
            var total = measure()
            while (total > available && size > minSize) {
                size = (size - 0.25f).coerceAtLeast(minSize)
                total = measure()
            }
            if (total <= available) {
                chosenCount = count
                chosenSize = size
                chosenGap = if (count > 1) ((available - total) / (count - 1)).coerceAtLeast(0f) else 0f
                break
            }
        }

        if (chosenCount == 0) {
            view.text = ""
            view.visibility = View.INVISIBLE
            return
        }

        view.textSize = chosenSize
        view.gravity = if (chosenCount == 1) Gravity.CENTER else Gravity.START
        val builder = SpannableStringBuilder()
        tags.take(chosenCount).forEachIndexed { index, tag ->
            val start = builder.length
            builder.append(tag)
            builder.setSpan(
                GenreTagChipSpan(
                    density = density,
                    horizontalPadding = horizontalPadding,
                    strokeWidth = stroke,
                    trailingGap = if (index < chosenCount - 1) chosenGap else 0f
                ),
                start,
                builder.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        view.text = builder
        view.visibility = View.VISIBLE
    }

    view.visibility = View.VISIBLE
    view.post { renderForWidth() }
}

private class GenreTagChipSpan(
    density: Float,
    private val horizontalPadding: Float,
    private val strokeWidth: Float,
    private val trailingGap: Float
) : ReplacementSpan() {
    private val radius = 2.5f * density
    private val verticalInset = 1f * density
    private val borderColor = TvStyle.accent
    private val textColor = TvStyle.accent

    override fun getSize(
        paint: Paint,
        text: CharSequence,
        start: Int,
        end: Int,
        fm: Paint.FontMetricsInt?
    ): Int {
        val width = paint.measureText(text, start, end) + horizontalPadding * 2f + strokeWidth + trailingGap
        if (fm != null) {
            val metrics = paint.fontMetrics
            val verticalExtra = verticalInset + strokeWidth / 2f
            fm.top = minOf(fm.top, floor(metrics.ascent - verticalExtra).toInt())
            fm.ascent = minOf(fm.ascent, floor(metrics.ascent - verticalExtra).toInt())
            fm.descent = maxOf(fm.descent, ceil(metrics.descent + verticalExtra).toInt())
            fm.bottom = maxOf(fm.bottom, ceil(metrics.descent + verticalExtra).toInt())
        }
        return ceil(width).toInt()
    }

    override fun draw(
        canvas: Canvas,
        text: CharSequence,
        start: Int,
        end: Int,
        x: Float,
        top: Int,
        y: Int,
        bottom: Int,
        paint: Paint
    ) {
        val oldColor = paint.color
        val oldStyle = paint.style
        val oldStrokeWidth = paint.strokeWidth
        val oldAntiAlias = paint.isAntiAlias
        paint.isAntiAlias = true

        val textWidth = paint.measureText(text, start, end)
        val left = x + strokeWidth / 2f
        val right = left + textWidth + horizontalPadding * 2f
        val metrics = paint.fontMetrics
        val verticalExtra = verticalInset + strokeWidth / 2f
        val rect = RectF(
            left,
            y + metrics.ascent - verticalExtra,
            right,
            y + metrics.descent + verticalExtra
        )

        // 只绘制描边，不填充底色；四边完整闭合。
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = strokeWidth
        paint.color = borderColor
        canvas.drawRoundRect(rect, radius, radius, paint)

        paint.style = Paint.Style.FILL
        paint.color = textColor
        val chipText = text.subSequence(start, end).toString()
        canvas.drawText(chipText, left + horizontalPadding, y.toFloat(), paint)

        paint.color = oldColor
        paint.style = oldStyle
        paint.strokeWidth = oldStrokeWidth
        paint.isAntiAlias = oldAntiAlias
    }
}
