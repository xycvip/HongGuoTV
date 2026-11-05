// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View

/** 官方视频风格的半透明中央播放/暂停反馈图标。 */
internal class PlaybackTapFeedbackView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var playing = false

    init {
        alpha = 0f
    }

    fun show(isPlaying: Boolean) {
        playing = isPlaying
        visibility = VISIBLE
        animate().cancel()
        alpha = 0f
        scaleX = 0.82f
        scaleY = 0.82f
        invalidate()
        animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(140L)
            .withEndAction {
                animate()
                    .alpha(0f)
                    .setStartDelay(520L)
                    .setDuration(260L)
                    .withEndAction { visibility = GONE }
                    .start()
            }
            .start()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val radius = minOf(width, height) * 0.48f
        paint.style = Paint.Style.FILL
        paint.color = 0x99000000.toInt()
        canvas.drawCircle(cx, cy, radius, paint)

        paint.color = 0xF2FFFFFF.toInt()
        if (playing) {
            val barWidth = 5.5f * density
            val barHeight = 23f * density
            val gap = 5f * density
            canvas.drawRoundRect(cx - gap / 2f - barWidth, cy - barHeight / 2f,
                cx - gap / 2f, cy + barHeight / 2f, 2f * density, 2f * density, paint)
            canvas.drawRoundRect(cx + gap / 2f, cy - barHeight / 2f,
                cx + gap / 2f + barWidth, cy + barHeight / 2f, 2f * density, 2f * density, paint)
        } else {
            val size = 27f * density
            val path = Path().apply {
                moveTo(cx - size * 0.34f, cy - size * 0.5f)
                lineTo(cx + size * 0.48f, cy)
                lineTo(cx - size * 0.34f, cy + size * 0.5f)
                close()
            }
            canvas.drawPath(path, paint)
        }
    }
}
