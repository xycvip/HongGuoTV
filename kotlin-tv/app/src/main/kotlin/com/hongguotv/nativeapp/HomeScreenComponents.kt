// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import android.view.ViewConfiguration
import android.widget.HorizontalScrollView

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
