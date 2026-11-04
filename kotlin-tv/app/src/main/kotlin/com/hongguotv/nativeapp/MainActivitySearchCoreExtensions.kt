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

    internal fun MainActivity.runSearch(value: String,type: ContentType=library.contentType,fromPhone: Boolean=false,aggregate: Boolean=true) {
        val clean=value.trim(); if(clean.isEmpty() || clean.length>80) return
        (searchInput as? EditText)?.let { (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(it.windowToken,0) }
        aggregateSearch=aggregate
        if(!aggregate && library.contentType!=type) { library.contentType=type; for(index in 0..1) { tabState.remove(index); tabFocus.remove(index) } }
        // 手机输入的精确剧名默认不再联想，纯首拼或编辑输入后恢复
        phoneSearched=fromPhone
        library.rememberSearch(clean,type); query=clean; tab=1; page=1; catalogFocus=""; searchFocusPending=true; showCatalog(true)
    }

    internal fun MainActivity.onAzKey(key: String) {
        val input=searchInput as? EditText ?: return
        when(key) {
            "删除" -> { val length=input.text.length; if(length>0) input.text.delete(length-1,length) }
            "清空" -> input.setText("")
            else -> if(input.text.length<20) input.append(key.lowercase())
        }
        input.setSelection(input.text.length)
        scheduleSuggest()
    }

    internal fun MainActivity.scheduleSuggest() {
        main.removeCallbacks(pendingSuggest)
        main.postDelayed(pendingSuggest,300)
    }

    internal fun MainActivity.fetchSuggest() {
        if(isDestroyed || tab!=1 || screen!="catalog") return
        val key=(searchInput as? EditText)?.text?.toString()?.trim().orEmpty()
        if(key.isEmpty() || !key.matches(Regex("[a-zA-Z0-9]+"))) {
            suggestTicket++
            // 已提交搜索后（输入框显示的就是搜索词）隐藏联想栏：否则联想卡片在结果未加载时渲染，
            // nextFocusRight 指向自身，导致右键回不到搜索结果、左键又跳进联想列形成焦点陷阱。
            // 用户重新编辑输入框时联想自然恢复
            val keep=if(!phoneSearched && lastSuggestWords.isNotEmpty() && key==lastSuggestKey && key!=query) lastSuggestWords else emptyList()
            renderSuggest(keep); return
        }
        // 手机输入的精确剧名直接出结果，不展示联想；纯首拼仍提供联想辅助
        if(phoneSearched && !key.matches(Regex("[a-zA-Z]+"))) { suggestTicket++; renderSuggest(emptyList()); return }
        val ticket=++suggestTicket
        io.execute {
            val words=repository.suggest(key)
            main.post { if(!isDestroyed && ticket==suggestTicket && tab==1 && screen=="catalog") renderSuggest(words,key) }
        }
    }

    internal fun MainActivity.renderSuggest(words: List<String>,key: String="") {
        val container=suggestRow ?: return
        val refocus=container.hasFocus()
        val restore=suggestFocusIndex.takeIf { it in words.indices }
        container.removeAllViews(); suggestFirst=null
        // 记录最近的联想列表，供点击联想词搜索后回显；空列表同时隐藏中栏整列
        lastSuggestWords=words; lastSuggestKey=if(words.isEmpty()) "" else key
        if(words.isEmpty()) {
            suggestFocusIndex=-1
            syncSuggestPanel(false)
            wireSearchPanel(catalogRecycler)
            return
        }
        syncSuggestPanel(true)
        // setSingleLine 开启横向滚动模式，marquee 才按全文宽度布局滚得起来；仅 maxLines=1 只会截断省略
        val availableSuggestDp=(suggestScrollRef?.height?.takeIf { it>0 }?.div(resources.displayMetrics.density) ?: (heightDp()-240f)).coerceAtLeast(1f)
        val suggestItemHeight=if(suggestVertical) dp((availableSuggestDp/words.size-2f).toInt().coerceIn(32,46)) else dp(((heightDp()-480f)/20f).toInt().coerceIn(26,34))
        val suggestGap=dp(if(suggestVertical) ((availableSuggestDp-words.size*32f)/(words.size*2f)).toInt().coerceIn(2,7) else ((heightDp()-480f)/100f).toInt().coerceIn(2,7))
        val views=words.map { word -> button(word) { runSearch(word) }.apply { textSize=14f; minHeight=suggestItemHeight; setSingleLine(true); marqueeRepeatLimit=-1; ellipsize=android.text.TextUtils.TruncateAt.END; setPadding(dp(10),dp(5),dp(10),dp(5)) } }
        views.forEachIndexed { index,view ->
            if(suggestVertical) {
                // height=0 + weight 让少量联想词均分整栏；词条过多时 minHeight 保底并由 ScrollView 滚动。
                container.addView(view,lp(-1,0).apply { weight=1f; bottomMargin=suggestGap })
            } else container.addView(view,lp().apply { width=LinearLayout.LayoutParams.WRAP_CONTENT; height=suggestItemHeight; rightMargin=suggestGap })
            // 竖排联想：左回键盘离开位置、右/下进结果区（结果首卡优先，否则指向结果网格，避免原地自锁）；横排保持词间左右互连
            view.nextFocusLeftId=if(suggestVertical) (keyboardLastLabel?.let { keyboardKeys[it] } ?: azFirst)?.id ?: historyFirst?.id ?: View.NO_ID else views[if(index==0) 0 else index-1].id
            view.nextFocusRightId=if(suggestVertical) resultFirstCard?.id ?: catalogRecycler?.id ?: view.id else views[if(index==views.size-1) index else index+1].id
            view.nextFocusUpId=if(index==0) searchInput?.id ?: View.NO_ID else views[index-1].id
            view.nextFocusDownId=if(index==views.size-1) resultFirstCard?.id ?: catalogRecycler?.id ?: view.id else views[index+1].id
            // 保留 focusStyle 的焦点绘制，追加词条索引记忆；超长联想词获得焦点时跑马灯滚动全名，失焦回退省略号
            val paint=view.onFocusChangeListener
            view.setOnFocusChangeListener { v,focused ->
                paint?.onFocusChange(v,focused)
                if(focused) suggestFocusIndex=index
                view.ellipsize=if(focused) android.text.TextUtils.TruncateAt.MARQUEE else android.text.TextUtils.TruncateAt.END
                view.isSelected=focused
                view.marqueeRepeatLimit=-1
            }
        }
        suggestFirst=views.firstOrNull()
        wireSearchPanel(catalogRecycler)
        // 联想刷新后优先回到记忆中的词条，避免焦点跳回第一条
        val target=restore?.let { views[it] } ?: suggestFirst
        if(refocus) (target ?: azLastRow.firstOrNull() ?: searchInput)?.requestFocus()
    }

    internal fun MainActivity.syncSuggestPanel(hasWords: Boolean) {
        val scroll=suggestScrollRef ?: return
        val target=if(hasWords) View.VISIBLE else View.GONE
        if(scroll.visibility!=target) {
            scroll.visibility=target
            (catalogRecycler?.layoutManager as? GridLayoutManager)?.spanCount=if(hasWords) searchColumns else fullSearchColumns
            catalogAdapter?.notifyDataSetChanged()
        }
    }

    internal fun MainActivity.wireSearchPanel(recycler: View?) {
        // 键盘每行末列按右移出键盘：有联想词进联想，否则直达结果网格
        keyboardRows.forEach { row -> row.last().nextFocusRightId=suggestFirst?.id ?: recycler?.id ?: row.last().id }
        azLastRow.forEach { it.nextFocusDownId=historyFirst?.id ?: suggestFirst?.id ?: recycler?.id ?: searchInput?.id ?: View.NO_ID }
        historyClear?.let { clear ->
            clear.nextFocusRightId=azFirst?.id ?: View.NO_ID
            clear.nextFocusDownId=historyFirst?.id ?: clear.id
            clear.nextFocusUpId=searchInput?.id ?: View.NO_ID
        }
        historyFirst?.let { first ->
            first.nextFocusUpId=historyClear?.id ?: searchInput?.id ?: View.NO_ID
            first.nextFocusRightId=azFirst?.id ?: suggestFirst?.id ?: recycler?.id ?: View.NO_ID
        }
        historyItems.forEachIndexed { index,item ->
            if(index>0) item.nextFocusUpId=historyItems[index-1].id
            item.nextFocusDownId=historyItems.getOrNull(index+1)?.id ?: recycler?.id ?: searchInput?.id ?: View.NO_ID
            item.nextFocusRightId=azFirst?.id ?: suggestFirst?.id ?: recycler?.id ?: View.NO_ID
        }
        // 遥控器从键盘左边缘按左键进入历史栏，并按高度映射到附近的历史项。
        val clear=historyClear
        if(clear!=null) {
            keyboardRows.forEachIndexed { rowIndex,row ->
                val leftTarget=if(rowIndex==0) clear.id else historyItems.getOrNull((rowIndex-1).coerceAtMost(historyItems.lastIndex))?.id ?: clear.id
                row.first().nextFocusLeftId=leftTarget
            }
        }
    }

internal fun MainActivity.submitCatalogSearch(input: EditText) {
    val next=input.text.toString().trim()
    if(next.isBlank()) { input.requestFocus(); return }
    runSearch(next)
}

internal fun MainActivity.catalogAzKey(label: String,keyHeight: Int): TextView {
    val view=button(label) { onAzKey(label) }
                    view.textSize=14f; view.minHeight=keyHeight; view.setPadding(dp(2),dp(3),dp(2),dp(3))
                    val paint=view.onFocusChangeListener
                    view.setOnFocusChangeListener { v,focused -> paint?.onFocusChange(v,focused); if(focused) keyboardLastLabel=label }
                    return view
}

internal fun MainActivity.loadSearchHot() {
        if(searchHotLoaded || searchHotLoading || tab!=1 || query.isNotBlank() || isDestroyed) return
        searchHotLoading=true
        io.execute {
            val result=runCatching {
                val real=runCatching { repository.ranking(RankBoard.REAL,1) }.getOrNull()
                val comic=runCatching { repository.ranking(RankBoard.COMIC,1) }.getOrNull()
                check(real!=null || comic!=null) { "暂时无法获取热搜推荐" }
                val mixed=mutableListOf<Series>()
                repeat(maxOf(real?.items?.size ?: 0,comic?.items?.size ?: 0)) { index ->
                    real?.items?.getOrNull(index)?.let { mixed+=it.copy(badge="短剧") }
                    comic?.items?.getOrNull(index)?.let { mixed+=it.copy(badge="漫剧") }
                }
                CatalogPage(mixed.distinctBy { it.id },false,real?.ranking ?: comic?.ranking)
            }
            main.post {
                searchHotLoading=false
                if(isDestroyed || resettingAllData || tab!=1 || query.isNotBlank() || screen!="catalog") return@post
                searchHotLoaded=true
                result.onSuccess { hot ->
                    catalog=hot.items; ranking=hot.ranking; hasMore=false
                    showCatalog()
                }.onFailure { android.util.Log.i("HongguoTV","Hot search recommendations unavailable: ${it.message}") }
            }
        }
    }

    internal fun MainActivity.showSearchHistory(anchor: View) {
        val history=library.searches()
        if(history.isEmpty()) { tvTools.info("搜索历史","搜索过的剧名会保存在这里，最多保留 20 条。",anchor); return }
        tvTools.choose("搜索历史",history.map { "${it.type.label} · ${it.query}" },anchor,{ index -> runSearch(history[index].query,history[index].type) },"清空历史") {
            tvTools.confirm("清空搜索历史","清除本机保存的搜索词？",anchor) { library.clearSearches() }
        }
    }
