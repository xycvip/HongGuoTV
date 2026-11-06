// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ReplacementSpan
import android.widget.TextView

/**
 * Renders each genre as an individual orange-red outlined chip.
 * The source genre text is kept intact; only presentation is changed.
 */
internal fun setGenreTagChips(view: TextView, rawTags: String, density: Float) {
    val tags = rawTags
        .split(Regex("\\s*[·|，,、]\\s*"))
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinct()
        .take(3)

    if (tags.isEmpty()) {
        view.text = ""
        view.visibility = android.view.View.INVISIBLE
        return
    }

    val text = SpannableStringBuilder()
    tags.forEachIndexed { index, tag ->
        if (index > 0) text.append(" ")
        val start = text.length
        text.append(tag)
        text.setSpan(
            GenreTagChipSpan(density),
            start,
            text.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
    }
    view.text = text
    view.visibility = android.view.View.VISIBLE
}

private class GenreTagChipSpan(density: Float) : ReplacementSpan() {
    private val horizontalPadding = 5f * density
    private val verticalInset = 1f * density
    private val radius = 3f * density
    private val strokeWidth = 1f * density
    private val fillColor = android.graphics.Color.rgb(255, 241, 233)
    private val borderColor = android.graphics.Color.rgb(235, 105, 61)
    private val textColor = android.graphics.Color.rgb(220, 83, 43)

    override fun getSize(
        paint: Paint,
        text: CharSequence,
        start: Int,
        end: Int,
        fm: Paint.FontMetricsInt?
    ): Int {
        return (paint.measureText(text, start, end) + horizontalPadding * 2f + strokeWidth * 2f).toInt()
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
        val right = left + textWidth + horizontalPadding * 2f + strokeWidth
        val metrics = paint.fontMetrics
        val rectTop = y + metrics.ascent - verticalInset
        val rectBottom = y + metrics.descent + verticalInset
        val rect = RectF(left, rectTop, right, rectBottom)

        paint.style = Paint.Style.FILL
        paint.color = fillColor
        canvas.drawRoundRect(rect, radius, radius, paint)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = strokeWidth
        paint.color = borderColor
        canvas.drawRoundRect(rect, radius, radius, paint)

        paint.style = Paint.Style.FILL
        paint.color = textColor
        canvas.drawText(text, start, end, left + horizontalPadding + strokeWidth / 2f, y, paint)

        paint.color = oldColor
        paint.style = oldStyle
        paint.strokeWidth = oldStrokeWidth
        paint.isAntiAlias = oldAntiAlias
    }
}
