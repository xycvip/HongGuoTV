// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View

/** 手机播放器中央半透明播放/暂停按钮；常驻于控制栏显示期间并可独立点击。 */
internal class PlaybackTapFeedbackView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var playing = false

    init {
        visibility = GONE
        isClickable = true
        isFocusable = false
        contentDescription = "播放视频"
    }

    fun show(isPlaying: Boolean) {
        setPlaying(isPlaying)
        visibility = VISIBLE
        alpha = 1f
        invalidate()
    }

    fun setPlaying(isPlaying: Boolean) {
        playing = isPlaying
        contentDescription = if(playing) "暂停视频" else "播放视频"
        invalidate()
    }

    fun hide() {
        visibility = GONE
        alpha = 1f
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
