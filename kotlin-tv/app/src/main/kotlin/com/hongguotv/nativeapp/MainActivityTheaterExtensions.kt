// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.view.View
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import com.hongguotv.core.*

    internal fun MainActivity.renderTheaterFilters(host:LinearLayout) {
        host.removeAllViews()
        val focusRows=mutableListOf<List<View>>()
        val types=row().apply { id=View.generateViewId(); setPadding(0,dp(6),0,dp(8)) }
        val typeItems=mutableListOf<View>()
        addButton(types,"全部体裁",theaterContentType==null).apply {
            typeItems+=this
            tag="theater-filter:type:all"
            setOnClickListener {
                theaterFocusTag=tag.toString()
                if(theaterContentType!=null) { theaterContentType=null; theaterSelections.clear(); page=1; catalogFocus=""; tabState.remove(7); showCatalog(load=true) }
            }
        }
        listOf(ContentType.SHORT to "真人剧",ContentType.COMIC to "漫剧",ContentType.AI to "AI剧").forEach { (type,label) ->
            addButton(types,label,theaterContentType==type).apply {
                typeItems+=this
                tag="theater-filter:type:${type.name}"
                setOnClickListener {
                    theaterFocusTag=tag.toString()
                    if(theaterContentType!=type) { theaterContentType=type; theaterSelections.clear(); page=1; catalogFocus=""; tabState.remove(7); showCatalog(load=true) }
                }
            }
        }
        if(theaterSelections.isNotEmpty()) {
            addButton(types,"清除筛选").apply {
                typeItems+=this
                tag="theater-filter:clear"
                setOnClickListener {
                    theaterFocusTag=tag.toString()
                    theaterSelections.clear(); page=1; catalogFocus=""; tabState.remove(7); showCatalog(load=true)
                }
            }
        }
        host.addView(types)
        focusRows+=typeItems
        val groups=theaterFiltersByType[theaterFilterKey(theaterContentType)].orEmpty().filterNot { it.type=="genre" }
        if(groups.isEmpty()) host.addView(text("正在读取红果官方筛选条件…",13f,muted).apply { setPadding(0,dp(5),0,dp(10)) })
        else groups.forEach { group ->
            val line=row().apply { gravity=Gravity.CENTER_VERTICAL; setPadding(0,dp(2),0,dp(2)) }
            val scroll=VerticalFriendlyHorizontalScrollView(this).apply { isHorizontalScrollBarEnabled=false; clipToPadding=false }
            val choices=row().apply { gravity=Gravity.CENTER_VERTICAL }
            val rowItems=mutableListOf<View>()
            val selected=theaterSelections[group.type]
            val allLabel=if(group.label.startsWith("全部")) group.label else "全部${group.label}"
            addButton(choices,allLabel,selected.isNullOrBlank()).apply {
                rowItems+=this
                tag="theater-filter:${group.type}:all"
                setOnClickListener {
                    theaterFocusTag=tag.toString()
                    if(theaterSelections.remove(group.type)!=null) { page=1; catalogFocus=""; tabState.remove(7); showCatalog(load=true) }
                }
            }
            group.options.forEach { option ->
                addButton(choices,option.label,selected==option.id).apply {
                    rowItems+=this
                    tag="theater-filter:${group.type}:${option.id}"
                    setOnClickListener {
                        theaterFocusTag=tag.toString()
                        if(selected!=option.id) {
                            if(group.type in setOf("category_dim_theme","category_dim_role","category_dim_epoch","creation_status")) {
                                listOf("category_dim_theme","category_dim_role","category_dim_epoch","creation_status").forEach { theaterSelections.remove(it) }
                            }
                            theaterSelections[group.type]=option.id; page=1; catalogFocus=""; tabState.remove(7); showCatalog(load=true)
                        }
                    }
                }
            }
            scroll.addView(choices,ViewGroup.LayoutParams(-2,dp(40)))
            line.addView(scroll,lp(0,dp(40)).apply { weight=1f })
            host.addView(line)
            focusRows+=rowItems
        }
        // 横向筛选行的上下焦点按屏幕上的实际中心点匹配，不能按按钮数量比例猜测。
        // 各行按钮宽度不同，按索引比例会让遥控器上下移动时明显跳到远处选项。
        focusRows.forEachIndexed { rowIndex, items ->
            items.forEachIndexed { index, item ->
                item.nextFocusLeftId=items.getOrNull(index-1)?.id ?: item.id
                item.nextFocusRightId=items.getOrNull(index+1)?.id ?: item.id
                item.nextFocusUpId=navForTab().id
                item.nextFocusDownId=catalogRecycler?.id ?: item.id
            }
        }
        host.post {
            if(!host.isAttachedToWindow || screen!="catalog" || tab!=7) return@post
            fun centerX(view:View):Float {
                val location=IntArray(2)
                view.getLocationOnScreen(location)
                return location[0]+view.width/2f
            }
            focusRows.forEachIndexed { rowIndex, items ->
                val previous=focusRows.getOrNull(rowIndex-1)
                val next=focusRows.getOrNull(rowIndex+1)
                items.forEach { item ->
                    val x=centerX(item)
                    item.nextFocusUpId=previous?.filter { it.isAttachedToWindow }
                        ?.minByOrNull { kotlin.math.abs(centerX(it)-x) }?.id ?: navForTab().id
                    item.nextFocusDownId=next?.filter { it.isAttachedToWindow }
                        ?.minByOrNull { kotlin.math.abs(centerX(it)-x) }?.id ?: catalogRecycler?.id ?: item.id
                }
            }
        }
    }
internal fun MainActivity.theaterFilterKey(type:ContentType?)=type?.name ?: "ALL"
internal fun MainActivity.theaterFilterSummary()=theaterSelections.entries.joinToString(" · ") { (key,value) ->
    theaterFiltersByType[theaterFilterKey(theaterContentType)].orEmpty().firstOrNull { it.type==key }?.options?.firstOrNull { it.id==value }?.label ?: value
}
