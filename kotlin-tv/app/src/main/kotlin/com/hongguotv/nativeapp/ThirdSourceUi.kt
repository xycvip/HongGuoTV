// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.app.Activity
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/** 三方片源页面通用控件工厂，集中处理尺寸、焦点态与基础文案样式。 */
internal class ThirdSourceUi(private val activity: Activity) {
    fun dp(value: Number) = (value.toFloat() * activity.resources.displayMetrics.density).toInt()
    fun widthDp() = activity.resources.displayMetrics.widthPixels / activity.resources.displayMetrics.density
    fun lp(width: Int, height: Int) = LinearLayout.LayoutParams(width, height)
    fun rounded(color: Int, border: Int = Color.TRANSPARENT, radius: Int = 10) =
        TvStyle.shape(activity, color, border, radius)
    fun text(value: String, size: Float = TvMetric.TEXT_BODY, color: Int = TvStyle.colors.textPrimary) = TextView(activity).apply {
        text = value; textSize = size; setTextColor(color); includeFontPadding = false
    }
    fun column() = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    fun row() = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }

    /**
     * 三方片源页的焦点态。
     *
     * 原来是"灰底 + 橙色 2dp 描边"，和主站的白环不是一套语言；这里改成白环 + 抬升 + 轻微放大，
     * 和 [MainActivity.focusStyle] 保持同一种反馈。
     */
    fun focusStyle(view: View, selected: Boolean = false) {
        view.id = View.generateViewId(); view.isFocusable = true; view.isFocusableInTouchMode = true
        fun paint(focused: Boolean) {
            view.background = rounded(
                when {
                    focused -> TvStyle.colors.surfaceRaised
                    selected -> TvStyle.colors.selectedSurface
                    else -> TvStyle.colors.surface
                },
                if(focused) TvStyle.colors.focusRing else Color.TRANSPARENT,
                TvMetric.RADIUS_M,
            )
            view.elevation = if(focused) dp(6).toFloat() else 0f
            if(view is TextView) view.setTextColor(
                when {
                    focused -> TvStyle.colors.textPrimary
                    selected -> TvStyle.colors.accent
                    else -> TvStyle.colors.textPrimary
                },
            )
            view.animateFocus(focused)
        }
        paint(false)
        view.setOnFocusChangeListener { _, focused -> paint(focused) }
    }

    fun addButton(parent: LinearLayout, label: String, selected: Boolean = false, onClick: () -> Unit): TextView =
        text(label, TvMetric.TEXT_LABEL, if(selected) TvStyle.colors.accent else TvStyle.colors.textPrimary).apply {
            gravity = Gravity.CENTER
            setPadding(dp(TvMetric.GAP_L), dp(10), dp(TvMetric.GAP_L), dp(10))
            minHeight = dp(TvMetric.ROW_HEIGHT_COMPACT)
            focusStyle(this, selected)
            setOnClickListener { onClick() }
        }.also { view ->
            parent.addView(view, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(TvMetric.GAP_M) })
        }

    fun message(parent: LinearLayout, label: String) {
        parent.addView(text(label, TvMetric.TEXT_BODY, TvStyle.colors.textTertiary).apply {
            setPadding(0, dp(TvMetric.GAP_XL), 0, dp(TvMetric.GAP_M))
        })
    }

    fun editText(hint: String) = EditText(activity).apply {
        this.hint = hint
        textSize = TvMetric.TEXT_LABEL
        setTextColor(TvStyle.colors.textPrimary)
        setHintTextColor(TvStyle.colors.textTertiary)
        isSingleLine = true; maxLines = 1
        setPadding(dp(TvMetric.GAP_L), 0, dp(TvMetric.GAP_L), 0)
        minHeight = dp(48)
        background = rounded(TvStyle.colors.surface, TvStyle.colors.strokeSubtle, TvMetric.RADIUS_M)
        setOnFocusChangeListener { _, focused ->
            background = rounded(
                if(focused) TvStyle.colors.surfaceRaised else TvStyle.colors.surface,
                if(focused) TvStyle.colors.focusRing else TvStyle.colors.strokeSubtle,
                TvMetric.RADIUS_M,
            )
            if(focused) {
                val manager = activity.getSystemService(InputMethodManager::class.java)
                manager?.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
            }
        }
    }
    fun hostOf(api: String): String = try {
        java.net.URI(api).host ?: api.removePrefix("https://").removePrefix("http://").substringBefore('/')
    } catch (_: Exception) { api.substringBefore('/') }
    fun formatCount(total: Int): String = when {
        total >= 10000 -> String.format(java.util.Locale.CHINA, "%.1f万", total / 10000.0)
        total > 0 -> total.toString()
        else -> "可连接"
    }
    fun formatTime(ms: Long): String {
        val totalSeconds = ms / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if(hours > 0) String.format(java.util.Locale.CHINA, "%d:%02d:%02d", hours, minutes, seconds)
        else String.format(java.util.Locale.CHINA, "%02d:%02d", minutes, seconds)
    }
    fun browseCols(): Int = when {
        widthDp() >= 1100 -> 7; widthDp() >= 900 -> 6; widthDp() >= 700 -> 5; else -> 4
    }
    fun episodeCols(): Int = when {
        widthDp() >= 1100 -> 7; widthDp() >= 900 -> 6; widthDp() >= 700 -> 5; else -> 4
    }
}
