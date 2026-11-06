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

/** Compact orange-red outline chips, fitted to the actual card width without truncation. */
internal fun setGenreTagChips(view: TextView, rawTags: String, density: Float) {
    val tags = rawTags
        .split(Regex("\\s*[·|，,、]\\s*"))
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinct()
        .take(3)

    if (tags.isEmpty()) {
        view.text = ""
        view.visibility = View.INVISIBLE
        return
    }

    view.maxLines = 1
    view.minLines = 1
    view.ellipsize = null
    view.gravity = Gravity.CENTER
    view.setTextColor(android.graphics.Color.rgb(220, 83, 43))
    view.setPadding((2f * density).toInt(), 0, (2f * density).toInt(), 0)

    fun renderForWidth() {
        if (view.width <= 0) return
        val available = (view.width - view.paddingLeft - view.paddingRight).toFloat()
        val gap = 3f * density
        val horizontalPadding = 2f * density
        val stroke = 0.8f * density
        val maxSize = 10.5f
        val minSize = 8f

        fun requiredWidth(count: Int, size: Float): Float {
            val paint = view.paint
            paint.textSize = size * density
            return tags.take(count).sumOf { tag ->
                (paint.measureText(tag) + horizontalPadding * 2f + stroke).toDouble()
            }.toFloat() + gap * (count - 1).coerceAtLeast(0)
        }

        var count = minOf(3, tags.size)
        var textSize = maxSize
        while (count > 0 && requiredWidth(count, minSize) > available) count--
        if (count == 0) {
            view.text = ""
            view.visibility = View.INVISIBLE
            return
        }
        while (textSize > minSize && requiredWidth(count, textSize) > available) {
            textSize -= 0.25f
        }
        // 优先完整显示三项；若宽度不足则退到两项，不截断标签文字。
        if (count == 3 && requiredWidth(3, textSize) > available) count = 2

        view.textSize = textSize
        val builder = SpannableStringBuilder()
        tags.take(count).forEachIndexed { index, tag ->
            if (index > 0) builder.append(" ")
            val start = builder.length
            builder.append(tag)
            builder.setSpan(
                GenreTagChipSpan(density, horizontalPadding, stroke),
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
    private val strokeWidth: Float
) : ReplacementSpan() {
    private val radius = 2.5f * density
    private val verticalInset = 0.5f * density
    private val borderColor = android.graphics.Color.rgb(235, 105, 61)
    private val textColor = android.graphics.Color.rgb(220, 83, 43)

    override fun getSize(
        paint: Paint,
        text: CharSequence,
        start: Int,
        end: Int,
        fm: Paint.FontMetricsInt?
    ): Int = (paint.measureText(text, start, end) + horizontalPadding * 2f + strokeWidth).toInt()

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
        val rect = RectF(
            left,
            y + metrics.ascent - verticalInset,
            right,
            y + metrics.descent + verticalInset
        )

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
