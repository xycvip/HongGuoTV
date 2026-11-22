package com.hongguotv.nativeapp

import android.content.Context
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.LinearLayout
import androidx.recyclerview.widget.RecyclerView

internal class SearchResultsColumn(context: Context): LinearLayout(context) {
    var scrollTarget: RecyclerView?=null
    private val touchSlop=ViewConfiguration.get(context).scaledTouchSlop
    private var downX=0f
    private var downY=0f
    private var lastY=0f
    private var dragging=false

    init { orientation=VERTICAL }

    override fun requestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {
        if(!disallowIntercept) super.requestDisallowInterceptTouchEvent(false)
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        when(event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX=event.x; downY=event.y; lastY=event.y; dragging=false
            }
            MotionEvent.ACTION_MOVE -> {
                val dx=kotlin.math.abs(event.x-downX)
                val dy=kotlin.math.abs(event.y-downY)
                if(dy>touchSlop && dy>dx*1.15f) {
                    val direction=if(event.y<downY) 1 else -1
                    if(scrollTarget?.canScrollVertically(direction)==true) {
                        dragging=true
                        parent?.requestDisallowInterceptTouchEvent(true)
                        return true
                    }
                }
            }
            MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL -> dragging=false
        }
        return false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if(!dragging) return super.onTouchEvent(event)
        when(event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                val delta=(lastY-event.y).toInt()
                if(delta!=0) scrollTarget?.scrollBy(0,delta)
                lastY=event.y
            }
            MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL -> {
                dragging=false
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return true
    }
}
