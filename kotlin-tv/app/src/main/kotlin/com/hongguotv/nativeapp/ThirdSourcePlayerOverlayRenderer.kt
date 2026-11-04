// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.app.Activity
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/** 三方片源播放器上下控制层的纯 UI 构建器。 */
internal class ThirdSourcePlayerOverlayRenderer(
    private val activity: Activity,
    private val ui: ThirdSourceUi,
) {
    data class Views(
        val bottom: LinearLayout,
        val top: LinearLayout,
        val fill: View,
        val track: FrameLayout,
        val thumb: View,
        val bubble: TextView,
        val seekBar: FrameLayout,
        val time: TextView,
        val buttons: List<View>,
    )

    fun render(
        host: FrameLayout,
        data: ThirdDetail,
        episodeLabel: String,
        sourceName: String,
        groupName: String,
        speedLabel: String,
        resizeLabel: String,
        onPrevious: () -> Unit,
        onRewind: () -> Unit,
        onForward: () -> Unit,
        onCycleSpeed: (TextView) -> Unit,
        onCycleResize: (TextView) -> Unit,
        onSkip: (View) -> Unit,
        onNext: () -> Unit,
        onBack: () -> Unit,
        onProgressFocus: () -> Unit,
    ): Views {
        val top = ui.row().apply {
            setBackgroundColor(Color.argb(150, 0, 0, 0))
            setPadding(ui.dp(14), ui.dp(8), ui.dp(14), ui.dp(8))
        }
        top.addView(ui.text("${data.video.name} · $episodeLabel", 15f), ui.lp(0, -2).apply { weight = 1f })
        top.addView(ui.text("$sourceName · $groupName", 12f, TvStyle.muted))
        top.visibility = View.GONE
        host.addView(top, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))

        val bottom = ui.column().apply {
            setBackgroundColor(Color.argb(170, 0, 0, 0))
            setPadding(ui.dp(14), ui.dp(10), ui.dp(14), ui.dp(12))
        }
        val track = FrameLayout(activity).apply {
            background = ui.rounded(Color.argb(70, 255, 255, 255), radius = 6)
            clipChildren = false
        }
        val fill = View(activity).apply { background = ui.rounded(TvStyle.accent, radius = 6) }
        track.addView(fill, FrameLayout.LayoutParams(0, -1))
        val thumb = View(activity).apply {
            background = ui.rounded(TvStyle.accent, Color.WHITE, 12)
            visibility = View.GONE
        }
        track.addView(thumb, FrameLayout.LayoutParams(ui.dp(14), ui.dp(14), Gravity.START or Gravity.CENTER_VERTICAL))
        val bubble = ui.text("", 13f).apply {
            background = ui.rounded(Color.argb(210, 0, 0, 0), radius = 8)
            setPadding(ui.dp(10), ui.dp(4), ui.dp(10), ui.dp(4))
            visibility = View.INVISIBLE
        }
        val seekBar = FrameLayout(activity).apply {
            id = View.generateViewId()
            isFocusable = true
            tag = "seekbar"
            setPadding(ui.dp(2), ui.dp(6), ui.dp(2), ui.dp(6))
        }
        seekBar.addView(bubble, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        seekBar.addView(track, FrameLayout.LayoutParams(-1, ui.dp(4), Gravity.CENTER_VERTICAL))
        seekBar.setOnFocusChangeListener { _, focused ->
            (track.layoutParams as FrameLayout.LayoutParams).height = ui.dp(if(focused) 8 else 4)
            track.requestLayout()
            thumb.visibility = if(focused) View.VISIBLE else View.GONE
            bubble.visibility = if(focused) View.VISIBLE else View.INVISIBLE
            if(focused) onProgressFocus()
        }
        bottom.addView(seekBar, ui.lp(-1, -2))

        val controls = ui.row().apply { setPadding(0, ui.dp(8), 0, 0) }
        val previous = ui.addButton(controls, "上一集", onClick = onPrevious)
        val rewind = ui.addButton(controls, "快退", onClick = onRewind)
        val forward = ui.addButton(controls, "快进", onClick = onForward)
        lateinit var speed: TextView
        speed = ui.addButton(controls, "倍速 $speedLabel") { onCycleSpeed(speed) }
        lateinit var ratio: TextView
        ratio = ui.addButton(controls, "比例 $resizeLabel") { onCycleResize(ratio) }
        lateinit var skip: TextView
        skip = ui.addButton(controls, "跳过") { onSkip(skip) }
        val next = ui.addButton(controls, "下一集", onClick = onNext)
        val back = ui.addButton(controls, "返回详情", onClick = onBack)
        val time = ui.text("", 13f)
        controls.addView(time, ui.lp(0, -2).apply {
            weight = 1f
            gravity = Gravity.END
            leftMargin = ui.dp(10)
        })
        bottom.addView(controls)
        bottom.visibility = View.GONE
        host.addView(bottom, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))

        val buttons = listOf(previous, rewind, forward, speed, ratio, skip, next, back)
        buttons.forEachIndexed { index, view ->
            view.nextFocusRightId = buttons.getOrNull(index + 1)?.id ?: View.NO_ID
            view.nextFocusLeftId = buttons.getOrNull(index - 1)?.id ?: View.NO_ID
            view.nextFocusUpId = seekBar.id
        }
        seekBar.nextFocusDownId = previous.id
        seekBar.nextFocusLeftId = seekBar.id
        seekBar.nextFocusRightId = seekBar.id
        return Views(bottom, top, fill, track, thumb, bubble, seekBar, time, buttons)
    }
}
