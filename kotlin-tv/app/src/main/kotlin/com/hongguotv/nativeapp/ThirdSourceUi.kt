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
    fun text(value: String, size: Float = 16f, color: Int = TvStyle.text) = TextView(activity).apply {
        text = value; textSize = size; setTextColor(color); includeFontPadding = false
    }
    fun column() = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    fun row() = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    fun focusStyle(view: View, selected: Boolean = false) {
        view.id = View.generateViewId(); view.isFocusable = true; view.isFocusableInTouchMode = true
        fun paint(focused: Boolean) {
            view.background = rounded(if(focused) TvStyle.raised else if(selected) TvStyle.selectedSurface else TvStyle.surface,
                if(focused) TvStyle.accent else Color.TRANSPARENT)
        }
        paint(false)
        view.setOnFocusChangeListener { _, focused -> paint(focused) }
    }
    fun addButton(parent: LinearLayout, label: String, selected: Boolean = false, onClick: () -> Unit): TextView =
        text(label, 15f).apply {
            gravity = Gravity.CENTER; setPadding(dp(14), dp(10), dp(14), dp(10)); minHeight = dp(42)
            focusStyle(this, selected); setOnClickListener { onClick() }
        }.also { view ->
            parent.addView(view, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(8) })
        }
    fun message(parent: LinearLayout, label: String) {
        parent.addView(text(label, 16f, TvStyle.muted).apply { setPadding(0, dp(28), 0, dp(12)) })
    }
    fun editText(hint: String) = EditText(activity).apply {
        this.hint = hint; textSize = 15f; setTextColor(TvStyle.text); setHintTextColor(TvStyle.muted)
        isSingleLine = true; maxLines = 1
        background = rounded(TvStyle.raised, TvStyle.outline)
        setPadding(dp(10), dp(10), dp(10), dp(10))
        setOnFocusChangeListener { _, focused ->
            background = rounded(if(focused) TvStyle.raised else TvStyle.surface, if(focused) TvStyle.accent else TvStyle.outline)
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
