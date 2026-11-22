// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import android.graphics.Typeface
import android.text.TextUtils
import android.view.ViewConfiguration
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.hongguotv.core.Series

// 首页横向手势容器与选中剧集预览控件。
/** Lets the vertical page container arbitrate gestures; this view only handles horizontal drags. */
class VerticalFriendlyHorizontalScrollView(context: Context): HorizontalScrollView(context) {
    private val touchSlop=ViewConfiguration.get(context).scaledTouchSlop
    private var downX=0f
    private var downY=0f

    override fun onInterceptTouchEvent(event: android.view.MotionEvent): Boolean {
        when(event.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                downX=event.x
                downY=event.y
                // Clear any stale disallow-intercept request before a new gesture.
                parent?.requestDisallowInterceptTouchEvent(false)
            }
            android.view.MotionEvent.ACTION_MOVE -> {
                val dx=kotlin.math.abs(event.x-downX)
                val dy=kotlin.math.abs(event.y-downY)
                if(dy>touchSlop && dy>dx*1.15f) {
                    // Do not claim vertical gestures: let the outer page ScrollView intercept.
                    parent?.requestDisallowInterceptTouchEvent(false)
                    return false
                }
                // Intentionally do not disallow parent interception for horizontal gestures.
                // The vertical ScrollView naturally ignores predominantly horizontal movement,
                // while this HorizontalScrollView still handles and scrolls its own shelf.
            }
            android.view.MotionEvent.ACTION_UP,android.view.MotionEvent.ACTION_CANCEL ->
                parent?.requestDisallowInterceptTouchEvent(false)
        }
        return super.onInterceptTouchEvent(event)
    }
}

/** Fixed text slots keep D-pad movement from resizing the content below the preview. */
class SeriesPreview(context: Context): LinearLayout(context) {
    private fun label(size: Float,color: Int)=TextView(context).apply { textSize=size; setTextColor(color); includeFontPadding=false }
    private val title=label(27f,TvStyle.text).apply { minLines=1; maxLines=1; ellipsize=TextUtils.TruncateAt.END; typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL) }
    private val meta=label(12f,TvStyle.muted).apply { minLines=1; maxLines=1; ellipsize=TextUtils.TruncateAt.END }
    init {
        // 不再占用页面顶部的剧名预览区域，避免删除提示文字后留下整块空白。
        orientation=VERTICAL
        visibility=GONE
    }
    fun show(series: Series?,status: String) {
        // 未选中海报时保持信息区为空；选中海报后仅显示对应剧名和简介信息。
        val nextTitle=series?.title ?: ""
        val nextMeta=if(series==null) "" else listOf(status,series.badge,series.tags).flatMap { it.split('·') }.map(String::trim).filter { it.isNotBlank() && !it.all(Char::isDigit) }.distinct().joinToString("  /  ")
        if(title.text.toString()!=nextTitle) title.text=nextTitle
        if(meta.text.toString()!=nextMeta) meta.text=nextMeta
    }
}
