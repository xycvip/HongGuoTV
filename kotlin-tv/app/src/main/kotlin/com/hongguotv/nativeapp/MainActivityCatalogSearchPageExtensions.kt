// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.graphics.drawable.GradientDrawable
import android.view.*
import android.view.inputmethod.InputMethodManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Uri
import android.net.Network
import android.net.NetworkCapabilities
import android.widget.*
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.media3.common.*
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.source.preload.DefaultPreloadManager
import androidx.media3.ui.PlayerView
import com.hongguotv.core.*
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/** 搜索页键盘、历史/联想布局与结果网格挂载。 */
internal fun MainActivity.renderCatalogSearchPage(container: LinearLayout) {
            // 机型分级：≥900dp 三栏（键盘+历史 / 联想 / 结果），700-900dp 两栏，<700dp 回退纵向堆叠
            val mode=when { widthDp()>=900 -> 3; widthDp()>=700 -> 2; else -> 0 }
            val tall=heightDp()>=520
            val roomy=widthDp()>=1100
            searchColumns=when(mode) {
                3 -> (((widthDp()-(if(roomy) 430f else 360f)-(if(roomy) 180f else 140f)-24f)+14f)/118f).toInt().coerceIn(3,gridColumns)
                2 -> (((widthDp()-360f-24f)+14f)/118f).toInt().coerceIn(3,gridColumns)
                else -> gridColumns
            }
            // 无联想词时中栏整列消失，结果网格按全宽重新算列数
            fullSearchColumns=if(mode==3) (((widthDp()-(if(roomy) 430f else 360f)-24f)+14f)/118f).toInt().coerceIn(3,gridColumns) else searchColumns
            val searchRow=row(); val input=EditText(this).apply { id=View.generateViewId(); hint="输入剧名，或用字母键拼首拼"; setText(query); textSize=16f; setTextColor(white); setHintTextColor(muted); isSingleLine=true; maxLines=1; filters=arrayOf(android.text.InputFilter.LengthFilter(80)); imeOptions=android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH }
            searchRow.addView(input,lp(0,dp(45)).apply { weight=1f; rightMargin=dp(10) })
            searchInput=input; searchButton=addButton(searchRow,"搜索") { submitCatalogSearch(input) }
            // 搜索栏作为左侧键盘区的顶部内容；右侧结果区从同一高度开始展示，不留下搜索栏缩短后的空白带。
            searchRow.layoutParams=lp(-1)
            searchButton?.apply {
                nextFocusLeftId=input.id
                nextFocusRightId=searchActions.firstOrNull()?.id ?: id
                // 搜索按钮文字始终在自身焦点背景框内水平、垂直居中。
                gravity=Gravity.CENTER
                setPadding(dp(14),dp(10),dp(14),dp(10))
                (layoutParams as? LinearLayout.LayoutParams)?.let { params ->
                    params.rightMargin=0
                    layoutParams=params
                }
                nextFocusUpId=input.id
            }
            input.nextFocusUpId=navForTab().id
            nav.forEach { it.nextFocusDownId=input.id }
            if(mode==0) {
                lateinit var recent: TextView
                recent=addButton(searchRow,"历史") { showSearchHistory(recent) }
                recent.nextFocusLeftId=searchButton?.id ?: input.id
                recent.nextFocusUpId=input.nextFocusUpId
                searchButton?.nextFocusRightId=recent.id
                searchActions.addAll(listOf(recent))
            }
            // 搜索页不再显示类型切换栏，导航直接进入搜索输入框
            input.setOnEditorActionListener { _,_,_-> submitCatalogSearch(input); true }
            if(mode==0) container.addView(searchRow)
            input.addTextChangedListener(object: android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?,start: Int,count: Int,after: Int) {}
                override fun onTextChanged(s: CharSequence?,start: Int,before: Int,count: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    // 用户编辑输入框后解除手机输入的联想抑制，恢复常规联想
                    if(phoneSearched && s?.toString()?.trim()!=query) phoneSearched=false
                    if(s?.toString()?.trim().isNullOrEmpty() && query.isNotBlank()) {
                        query=""; catalog=emptyList(); ranking=null; hasMore=false
                        searchHotLoaded=false; searchHotLoading=false
                        showCatalog()
                        return
                    }
                    scheduleSuggest()
                }
            })
            val keyHeight=if(mode==0) dp(((heightDp()-220f)/16f).toInt().coerceIn(22,38)) else if(tall) dp(32) else dp(29)

            // 电视遥控器搜索键盘：每行最多 4 个键，字母和数字固定显示，不分页。
            val keyboard=column().apply { setPadding(0,dp(2),0,dp(2)) }
            val keyLines=mutableListOf<List<TextView>>()
            val keyViews=linkedMapOf<String,TextView>()
            listOf("ABCD","EFGH","IJKL","MNOP","QRST","UVWX","YZ01","2345","6789").forEach { keys ->
                val line=row().apply { setPadding(0,0,0,dp(3)); gravity=Gravity.CENTER_VERTICAL }
                val views=keys.map { key -> catalogAzKey(key.toString(),keyHeight).also { line.addView(it,lp(0,if(mode==0) -2 else -1).apply { weight=1f; rightMargin=dp(4) }); keyViews[key.toString()]=it } }
                if(views.size<4) {
                    line.addView(View(this),lp(0,if(mode==0) dp(1) else -1).apply { weight=(4-views.size).toFloat() })
                }
                keyboard.addView(line,if(mode==0) lp(-1,keyHeight+dp(3)) else lp(-1,0).apply { weight=1f }); keyLines+=views
            }
            val lastLine=row().apply { setPadding(0,0,0,dp(3)); gravity=Gravity.CENTER_VERTICAL }
            val del=catalogAzKey("删除",keyHeight).also { lastLine.addView(it,lp(0,if(mode==0) -2 else -1).apply { weight=1f; rightMargin=dp(4) }); keyViews["删除"]=it }
            val clear=catalogAzKey("清空",keyHeight).also { lastLine.addView(it,lp(0,if(mode==0) -2 else -1).apply { weight=1f; rightMargin=dp(4) }); keyViews["清空"]=it }
            val searchKey=catalogAzKey("搜索",keyHeight).also { it.setOnClickListener { submitCatalogSearch(input) }; lastLine.addView(it,lp(0,if(mode==0) -2 else -1).apply { weight=1f }); keyViews["搜索"]=it }
            keyboard.addView(lastLine,if(mode==0) lp(-1,keyHeight+dp(3)) else lp(-1,0).apply { weight=1f })
            val lastViews=listOf(del,clear,searchKey)
            keyLines+=lastViews
            keyLines.forEachIndexed { r,views ->
                views.forEachIndexed { c,key ->
                    key.nextFocusLeftId=views[if(c==0) 0 else c-1].id
                    key.nextFocusRightId=views[if(c==views.size-1) c else c+1].id
                    val up=keyLines.getOrNull(r-1); val down=keyLines.getOrNull(r+1)
                    key.nextFocusUpId=up?.get(((c+0.5f)*up.size/views.size).toInt().coerceIn(0,up.size-1))?.id ?: input.id
                    key.nextFocusDownId=down?.get(((c+0.5f)*down.size/views.size).toInt().coerceIn(0,down.size-1))?.id ?: key.id
                }
            }
            azFirst=keyLines.first().first(); azLastRow.clear(); azLastRow.addAll(lastViews)
            keyboardKeys=keyViews; keyboardRows=keyLines.toList()
            var catalogBody: LinearLayout?=null
            if(mode==0) {
                container.addView(keyboard)
                val suggestScroll=HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled=false; isFocusable=false }
                val suggestLine=row(); suggestScroll.addView(suggestLine); suggestRow=suggestLine; suggestVertical=false
                container.addView(suggestScroll,lp(-1,-2).apply { bottomMargin=dp(4) })
            } else {
                // 历史与键盘分开滚动，历史条数不会挤压键盘高度；焦点移动时各自滚入可视区。
                val contentRow=row().apply { gravity=Gravity.TOP }
                val leftPane=column()
                val history=library.searches()
                // 搜索框位置固定：无论是否存在搜索历史，左边缘都保持在“无历史记录”时的位置，
                // 不跟随左侧历史栏/屏幕键盘整体偏移；仅保持统一的左右安全边距。
                leftPane.addView(searchRow,lp(-1,-2).apply {
                    leftMargin=dp(14)
                    rightMargin=dp(14)
                })
                val leftColumn=row().apply { gravity=Gravity.TOP; setPadding(0,dp(2),dp(10),0) }
                if(history.isNotEmpty()) {
                    val historyScroll=ScrollView(this).apply {
                        isVerticalScrollBarEnabled=false; clipToPadding=false; isFillViewport=true
                    }
                    val block=column().apply { setPadding(0,0,dp(4),0) }
                    val head=row().apply { setPadding(0,dp(2),0,dp(6)) }
                    head.addView(text("搜索历史",14f).apply { setTypeface(null,Typeface.BOLD) },lp(0,-2).apply { weight=1f })
                    lateinit var clearHistory: TextView
                    clearHistory=button("清空历史") { tvTools.confirm("清空搜索历史","清除本机保存的搜索词？",clearHistory) { library.clearSearches(); showCatalog(); main.post { searchInput?.requestFocus() } } }
                        .apply { textSize=12f; minHeight=dp(30); setPadding(dp(6),dp(4),dp(6),dp(4)) }
                    historyClear=clearHistory
                    head.addView(clearHistory,lp(-2,-2))
                    block.addView(head)
                    // 根据屏幕高度动态决定历史显示条数，剩余记录可从“全部历史”进入。
                    // 搜索页只保留少量最近记录；其余统一放进“全部历史”，避免历史栏挤占键盘空间。
                    val historyAvailable=(heightDp()-180f).coerceAtLeast(220f)
                    val historyItemHeight=((historyAvailable-70f)/10f).toInt().coerceIn(38,52)
                    val visibleHistoryCount=((historyAvailable-70f)/(historyItemHeight+8f)).toInt().coerceIn(2,10)
                    val items=history.take(visibleHistoryCount).map { entry ->
                        button("${entry.type.label} · ${entry.query}") { runSearch(entry.query,entry.type) }.apply {
                            textSize=13f; minHeight=dp(historyItemHeight); setPadding(dp(8),dp(5),dp(8),dp(5)); gravity=Gravity.CENTER_VERTICAL
                            maxLines=2; ellipsize=android.text.TextUtils.TruncateAt.END
                        }
                    }
                    items.forEachIndexed { index,item ->
                        block.addView(item,lp(-1,-2).apply { bottomMargin=dp(6) })
                        item.nextFocusUpId=if(index==0) clearHistory.id else items[index-1].id
                    }
                    historyItems=items
                    clearHistory.nextFocusDownId=items.firstOrNull()?.id ?: clearHistory.id
                    if(history.size>visibleHistoryCount) {
                        lateinit var all: TextView
                        all=button("全部历史（${history.size}）›") { showSearchHistory(all) }.apply { textSize=12f; minHeight=dp(34); setPadding(dp(8),dp(4),dp(8),dp(4)) }
                        block.addView(all,lp(-1,-2)); historyItems=items+all
                    }
                    historyScroll.addView(block)
                    leftColumn.addView(historyScroll,lp(dp(if(roomy) 160 else 145),-1))
                    historyFirst=items.firstOrNull() ?: clearHistory
                    clearHistory.nextFocusRightId=azFirst?.id ?: View.NO_ID
                    clearHistory.nextFocusUpId=(keyboardLastLabel?.let { keyViews[it] } ?: del).id
                } else {
                    historyFirst=null; historyClear=null; historyItems=emptyList()
                }
                val keyboardScroll=ScrollView(this).apply {
                    isVerticalScrollBarEnabled=false; clipToPadding=false; isFillViewport=true
                    descendantFocusability=android.view.ViewGroup.FOCUS_AFTER_DESCENDANTS
                }
                keyboardScroll.addView(keyboard,lp(-1,-1))
                leftColumn.addView(keyboardScroll,lp(0,-1).apply { weight=1f; leftMargin=dp(14) })
                leftPane.addView(leftColumn,lp(-1,0).apply { weight=1f })
                contentRow.addView(leftPane,lp(if(mode==3) dp(if(roomy) 430 else 360) else dp(360),-1))
                val midScroll=ScrollView(this).apply {
                    isVerticalScrollBarEnabled=false
                    clipToPadding=false
                    isFillViewport=true
                }
                // 让联想列表的纵向容器占满实际可用高度，词条再按空间均分，避免栏内大块空白。
                val midColumn=column().apply { setPadding(0,dp(2),dp(10),0) }
                midScroll.addView(midColumn,ViewGroup.LayoutParams(-1,-1))
                val rightColumn=SearchResultsColumn(this)
                if(mode==3) {
                    suggestRow=midColumn; suggestVertical=true; suggestScrollRef=midScroll
                    contentRow.addView(midScroll,lp(if(roomy) dp(180) else dp(140),-1))
                    contentRow.addView(rightColumn,lp(0,-1).apply { weight=1f })
                } else {
                    contentRow.addView(rightColumn,lp(0,-1).apply { weight=1f })
                    val suggestScroll=HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled=false; isFocusable=false }
                    val suggestLine=row(); suggestScroll.addView(suggestLine); suggestRow=suggestLine; suggestVertical=false
                    rightColumn.addView(suggestScroll,lp(-1,-2).apply { bottomMargin=dp(4) })
                }
                container.addView(contentRow,lp(-1,0).apply { weight=1f })
                catalogBody=rightColumn
            }
            searchBody=catalogBody
            // 键盘末行先指向历史（结果区建立后由 wireSearchPanel 补全联想与网格指向）
            azLastRow.forEach { it.nextFocusDownId=historyFirst?.id ?: View.NO_ID }
}
