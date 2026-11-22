// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.graphics.Typeface
import android.view.*
import android.widget.*
import android.text.TextUtils
import androidx.media3.common.*
import com.hongguotv.core.*

/**
 * 搜索页键盘、历史/联想布局与结果网格挂载。
 *
 * 上一版在宽屏下把联想词放进独立中栏：联想出现/消失会横向增减结果区宽度，
 * 同时用 setSpanCount 在两组列数间切换。真机上抓到第一张结果卡残留旧列宽的
 * 测量尺寸——海报比同排高出一半、还越界画到了键盘下面。这一版彻底删掉中栏：
 *  - 结果网格列数从建立起恒定不变，联想词条横排在结果区顶部，出现/消失只
 *    让网格整体下移一行，列宽永不重算；
 *  - 左侧仍是「搜索框 + 历史 + 键盘」三段，右侧整列留给结果；
 *  - 键盘末行「搜索」用主色底，与「删除/清空」中性键区分。
 */
internal fun MainActivity.renderCatalogSearchPage(container: LinearLayout) {
    // 分栏按「竖栏之后的内容宽度」判断，不能按整屏宽度：
    // 1080p 电视整屏 960dp、内容区只有 788dp，用整屏宽度会误判成三栏。
    val contentDp=contentWidthDp()
    val mode=if(contentDp>=600f) 1 else 0
    val roomy=contentDp>=1150f
    val tall=heightDp()>=520
    val leftWidth=if(roomy) 400f else 340f
    // 联想栏固定占位（宽度永不增减，空的时候只是不显示字），结果网格列数从
    // 建立起一次都不变——列宽不重算，卡片宽度就不会失常。
    val midWidth=if(roomy) 170f else 130f
    searchColumns=(((contentDp-leftWidth-midWidth-24f)+14f)/118f).toInt().coerceIn(2,gridColumns)
    fullSearchColumns=searchColumns
    // 输入框原来没有任何底色，在深色背景上只剩一行白字，看不出"这里可以输入"。
    val searchRow=row(); val input=EditText(this).apply {
        id=View.generateViewId(); hint="输入剧名，或用字母键拼首拼"
        setText(query); textSize=TvMetric.TEXT_LABEL; setTextColor(TvStyle.colors.textPrimary)
        setHintTextColor(TvStyle.colors.textTertiary)
        isSingleLine=true; maxLines=1
        filters=arrayOf(android.text.InputFilter.LengthFilter(80))
        imeOptions=android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
        background=TvStyle.shape(this@renderCatalogSearchPage,TvStyle.colors.surface,TvStyle.colors.strokeSubtle,TvMetric.RADIUS_M)
        setPadding(dp(16),0,dp(16),0)
    }
    searchRow.addView(input,lp(0,dp(48)).apply { weight=1f; rightMargin=dp(10) })
    searchInput=input; searchButton=addToneButton(searchRow,"搜索",TvTone.Primary) { submitCatalogSearch(input) }
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
    // 键盘末行第三键是「搜索历史」：历史不再占左侧整栏，统一收进弹窗
    // （列表 + 选中搜索 + 清空入口），键盘区给字母键让出更多宽度。
    lateinit var historyKey: TextView
    historyKey=catalogAzKey("搜索历史",keyHeight).also {
        it.setOnClickListener { showSearchHistory(historyKey) }
        lastLine.addView(it,lp(0,if(mode==0) -2 else -1).apply { weight=1f })
        keyViews["搜索历史"]=it
    }
    keyboard.addView(lastLine,if(mode==0) lp(-1,keyHeight+dp(3)) else lp(-1,0).apply { weight=1f })
    val lastViews=listOf(del,clear,historyKey)
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
        // 三栏定式：左=搜索框+键盘，中=竖排联想词，右=结果网格。
        // 中栏宽度固定占位（无联想词时只清空内容、不改几何），
        // 结果网格的列数与列宽整页不变，卡片永远等大。
        historyFirst=null; historyClear=null; historyItems=emptyList()
        val contentRow=row().apply { gravity=Gravity.TOP }
        val leftPane=column()
        leftPane.addView(searchRow,lp(-1,-2).apply {
            leftMargin=dp(14)
            rightMargin=dp(14)
        })
        val keyboardScroll=ScrollView(this).apply {
            clipChildren=false
            isVerticalScrollBarEnabled=false; clipToPadding=false; isFillViewport=true
            descendantFocusability=android.view.ViewGroup.FOCUS_AFTER_DESCENDANTS
        }
        keyboardScroll.addView(keyboard,lp(-1,-1))
        leftPane.addView(keyboardScroll,lp(-1,0).apply { weight=1f; topMargin=dp(4) })
        contentRow.addView(leftPane,lp(dp(leftWidth.toInt()),-1))

        val midScroll=ScrollView(this).apply {
            clipChildren=false
            isVerticalScrollBarEnabled=false
            clipToPadding=false
            isFillViewport=true
        }
        // 让联想列表的纵向容器占满实际可用高度，词条再按空间均分，避免栏内大块空白。
        val midColumn=column().apply { setPadding(0,dp(2),dp(10),0) }
        midScroll.addView(midColumn,ViewGroup.LayoutParams(-1,-1))
        suggestRow=midColumn; suggestVertical=true; suggestScrollRef=midScroll
        contentRow.addView(midScroll,lp(dp(midWidth.toInt()),-1))

        val rightColumn=SearchResultsColumn(this)
        contentRow.addView(rightColumn,lp(0,-1).apply { weight=1f })
        container.addView(contentRow,lp(-1,0).apply { weight=1f })
        catalogBody=rightColumn
    }
    searchBody=catalogBody
    // 键盘末行先指向历史（结果区建立后由 wireSearchPanel 补全联想与网格指向）
    azLastRow.forEach { it.nextFocusDownId=historyFirst?.id ?: View.NO_ID }
}
