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



/** 主目录页面内容路由：按当前标签构建账户、设置、搜索、榜单与目录主体。 */
internal fun MainActivity.renderCatalogContent(
    container: LinearLayout,
    load: Boolean,
    focusNav: Boolean,
    focusType: Boolean,
    focusFilter: Boolean,
    compactHeader: Boolean,
) {
        if(tab==0) container.post { if(screen=="catalog" && tab==0) runAutomaticApiChecks() }
        if(tab==6) {
            val host = FrameLayout(this)
            accountContentHost = host
            container.addView(host, lp(-1,0).apply { weight=1f })
            if (activePlatform == ContentPlatform.HIPPO) renderHippoAccountContent(host)
            else renderAccountContent(host)
            return
        }
        if(tab==5) {
            // 设置页：内容可滚动，页脚固定在屏幕底部居中，始终可见；去掉容器下内边距让页脚真正贴底
            container.setPadding(container.paddingLeft,container.paddingTop,container.paddingRight,0)
            val page=column()
            val scroll=ScrollView(this); val body=column(); scroll.addView(body); scroll.setOnScrollChangeListener { _,_,scrollY,_,oldY -> onCatalogContentScroll(scrollY>dp(1)) }; page.addView(scroll,lp(-1,0).apply { weight=1f })
            val footerHost=column().apply { gravity=Gravity.CENTER_HORIZONTAL; setPadding(0,dp(12),0,0) }
            page.addView(footerHost,lp(-1,-2))
            container.addView(page,lp(-1,0).apply { weight=1f })
            settings(body,footerHost)
            if(focusNav) navForTab().requestFocus()
            return
        }
        if(tab==1) {
            renderCatalogSearchPage(container)

        } else if(tab==2) {
            renderRankingCatalogHeader(container,nav,load)
            // 榜单顶部控件重建后恢复上次实际获得焦点的选项。
            if(!focusNav && !focusType && pendingRefreshFocus==null) rankFocusTag?.let { focusTag ->
                root.post {
                    if(screen=="catalog" && tab==2) {
                        val current=root.findFocus()
                        val target=root.findViewWithTag<View>(focusTag)
                        if((current==null || current===navForTab()) && target?.isAttachedToWindow==true) target.requestFocus()
                    }
                }
            }
        } else if(tab>2 && tab!=7) {
            renderCatalogLibraryHeader(container)
        }
        if(tab==7) {
            val host=column()
            theaterFilterHost=host
            container.addView(host,lp(-1,-2))
            renderTheaterFilters(host)
            // 重建剧场页面时立即恢复筛选焦点，避免网络结果返回前焦点短暂落到顶部「剧场」导航。
            // 仅在没有明确导航/体裁焦点指令时恢复上次筛选焦点，避免旧记录覆盖新导航。
            if(!focusNav && !focusType) theaterFocusTag?.let { focusTag ->
                root.post {
                    if(screen=="catalog" && tab==7) {
                        val current=root.findFocus()
                        val target=root.findViewWithTag<View>(focusTag)
                        if((current==null || current===navForTab()) && target?.isAttachedToWindow==true) target.requestFocus()
                    }
                }
            }
        }
        // 三栏/两栏搜索页的结果区已在 contentRow 内挂载，其余页面走全宽纵向容器
        val body=searchBody ?: column().also { container.addView(it,lp(-1,0).apply { weight=1f }) }
        if(tab==3) {
            val groups=library.groups()
            if(favoriteGroupFilter!=null && favoriteGroupFilter !in groups) favoriteGroupFilter=null
            if(groups.isNotEmpty()) {
                val filterRow=row().apply { setPadding(0,0,0,dp(6)) }
                val options=listOf<String?>(null)+groups
                favoriteFilterButtons=options.mapIndexed { index,group ->
                    addButton(filterRow,group ?: "全部",favoriteGroupFilter==group) {
                        favoriteGroupFilter=group; favoriteFilterFocus=index; showCatalog(focusFilter=true)
                    }.apply { nextFocusUpId=favoriteCheck?.id ?: navForTab().id }
                }
                favoriteFilterButtons.forEachIndexed { index, view ->
                    view.nextFocusLeftId=favoriteFilterButtons.getOrNull(index-1)?.id ?: view.id
                    view.nextFocusRightId=favoriteFilterButtons.getOrNull(index+1)?.id ?: view.id
                    view.nextFocusDownId=catalogRecycler?.id ?: View.NO_ID
                }
                favoriteFilterFirst=favoriteFilterButtons.first()
                container.addView(filterRow,container.indexOfChild(favoriteStatus))
            }
        }
        if(localCatalog()) {
            catalog=localItems(); ranking=null; page=1; hasMore=false
        }
        if(load) {
            if(tab==0 || tab==7) catalogGrid(body,focusNav,focusType,loading=true)
            else {
                message(body,"正在加载…")
                if(focusType) (if(tab==1) searchInput else navForTab())?.requestFocus()
                else if(pendingRefreshFocus==null && !(tab==2 && rankFocusTag!=null)) navForTab().requestFocus()
            }
            val requestTab=tab; val requestPage=page; val requestQuery=query; val requestType=library.contentType
            val requestAggregate=requestTab==1 && aggregateSearch
            val requestBoard=rankBoard; val requestGenre=if(requestTab==0) genre else null
            val requestTheaterType=theaterContentType; val requestTheaterSelection=theaterSelections.toMap()
            val requestAi=if(requestTab==2) aiCatalog else false; val requestAiGenre=if(requestAi) aiGenre else null
            var responseTheaterFilters:List<TheaterFilterGroup> = emptyList()
            work({
                when(requestTab) {
                    0 -> repository.home(requestPage,requestType,requestGenre)
                    7 -> repository.theater(requestPage,requestTheaterType,requestTheaterSelection).let { responseTheaterFilters=it.filters; it.catalog }
                    2 -> if(requestAi) repository.home(requestPage,ContentType.AI,requestAiGenre) else repository.ranking(requestBoard,requestPage)
                    else -> if(requestAggregate) repository.searchCombined(requestQuery,requestPage) else repository.search(requestQuery,requestPage,requestType)
                }
            }, { result ->
                if(requestTab==7 && responseTheaterFilters.isNotEmpty()) {
                    val filterKey=theaterFilterKey(requestTheaterType)
                    val previousFilters=theaterFiltersByType[filterKey]
                    theaterFiltersByType[filterKey]=responseTheaterFilters
                    // 筛选标签定义没有变化时，不要 removeAllViews 后整组重建。
                    // 否则用户刚点中的标签会被销毁，焦点短暂掉到导航栏，再被异步拉回，表现为闪烁。
                    if(previousFilters!=responseTheaterFilters) {
                        theaterFilterHost?.takeIf { it.isAttachedToWindow }?.let { host ->
                            // 官方筛选项首次返回或定义变化时需要重建；先记住当前真正持有焦点的筛选项，
                            // 避免 removeAllViews 后焦点掉到导航栏，再被异步拉回造成“乱跳”。
                            val focusedTag=host.findFocus()?.tag?.toString()
                                ?: theaterFocusTag
                            renderTheaterFilters(host)
                            val restoreTag=focusedTag
                            if(!restoreTag.isNullOrBlank()) {
                                root.post {
                                    if(screen=="catalog" && tab==7) {
                                        val current=root.findFocus()
                                        val target=root.findViewWithTag<View>(restoreTag)
                                        // 若用户已将焦点移到其他控件，不再用过期恢复任务抢焦点。
                                        if((current==null || current===navForTab()) && target?.isAttachedToWindow==true && !target.hasFocus()) target.requestFocus()
                                    }
                                }
                            }
                        }
                    }
                }
                if(requestTab==0 && requestPage==1 && requestGenre==null) {
                    library.cacheHome(requestType,result.items,result.hasMore)
                }
                    catalog=result.items; hasMore=result.hasMore; ranking=result.ranking
                    // 手机输入的精确剧名搜索零结果时自动恢复联想，帮助用户纠正剧名
                    if(requestTab==1 && result.items.isEmpty()) { phoneSearched=false; scheduleSuggest() }
                    val keepNav=nav.any { it.hasFocus() }; val keepType=typeButtons.values.any { it.hasFocus() }
                    if(requestTab==2) rankSubtitle?.text=if(requestAi) "来源：红果AI剧分类" else ranking?.updatedText?.ifBlank { rankBoard.sourceText } ?: rankBoard.sourceText
                    if(requestTab==2 || requestTab==7) {
                        // 首屏数据到达后重建一次页面，让筛选头与海报网格在同一 RecyclerView 中组装。
                        val restore=pendingRefreshFocus
                        pendingRefreshFocus=null
                        showCatalog(focusNav=keepNav || focusNav,focusType=keepType || focusType)
                        if(!restore.isNullOrBlank()) root.post {
                            if(screen=="catalog" && tab==requestTab) {
                                val target=when {
                                    restore.startsWith("rank:") -> root.findViewWithTag<View>(restore)
                                    restore.startsWith("focus:") -> root.findViewWithTag<View>(restore.removePrefix("focus:"))
                                    else -> null
                                }
                                val current=root.findFocus()
                                // 网络结果返回较晚时，不用过期刷新任务覆盖用户已移动到的控件。
                                if((current==null || current===navForTab()) && target?.isAttachedToWindow==true) target.requestFocus()
                            }
                        }
                        return@work
                    }
                    body.removeAllViews(); catalogGrid(body,keepNav || focusNav,keepType || focusType)
                    pendingRefreshFocus?.let { restore ->
                        pendingRefreshFocus=null
                        root.post {
                            if(screen=="catalog" && tab==requestTab) {
                                val target=when { restore.startsWith("rank:") -> root.findViewWithTag<View>(restore); restore.startsWith("focus:") -> root.findViewWithTag<View>(restore.removePrefix("focus:")); else -> navForTab() }
                                if(target?.isAttachedToWindow==true) target.requestFocus()
                            }
                        }
                    }
                    if(requestTab==0 && requestPage==1 && requestGenre==null) {
                        // 焦点已由 showCatalog 的 focusNav 或 pendingRefreshFocus 恢复；
                        // 此处只结束刷新状态，不能再次把焦点抢到随机入口或刷新控件。
                        refreshHomePending=false
                    }
                    // 搜索完成后焦点落结果首卡；零结果回输入框，避免页面重建后焦点丢失
                    if(requestTab==1 && searchFocusPending) {
                        searchFocusPending=false
                        if(result.items.isEmpty()) searchInput?.requestFocus()
                        else catalogRecycler?.post { (resultFirstCard ?: searchInput)?.requestFocus() }
                    }
            }, { problem ->
                if(requestTab==0 && requestPage==1 && requestGenre==null) {
                    refreshHomePending=false
                    pendingRefreshFocus?.let { restore ->
                        pendingRefreshFocus=null
                        root.post {
                            if(screen=="catalog" && tab==requestTab) {
                                val target=if(restore.startsWith("focus:")) root.findViewWithTag<View>(restore.removePrefix("focus:")) else navForTab()
                                if(target?.isAttachedToWindow==true) target.requestFocus()
                            }
                        }
                    }
                    return@work
                }
                catalog=emptyList(); hasMore=false; ranking=null; rankSubtitle?.text=if(requestAi) "AI剧分类加载失败" else "榜单加载失败"
                pendingRefreshFocus?.let { restore ->
                    pendingRefreshFocus=null
                    root.post {
                        if(screen=="catalog" && tab==requestTab) {
                            val target=when { restore.startsWith("rank:") -> root.findViewWithTag<View>(restore); restore.startsWith("focus:") -> root.findViewWithTag<View>(restore.removePrefix("focus:")); else -> navForTab() }
                            if(target?.isAttachedToWindow==true) target.requestFocus()
                        }
                    }
                }
                // 搜索失败时焦点回输入框，避免页面重建后焦点丢失
                if(requestTab==1 && searchFocusPending) { searchFocusPending=false; searchInput?.requestFocus() }
                if(problem is SearchSessionExpiredException) {
                    page=1; catalogFocus=""
                    Toast.makeText(this,"搜索结果已过期，已回到第 1 页刷新",Toast.LENGTH_LONG).show()
                    showCatalog(true)
                } else {
                    body.removeAllViews()
                    error(body,problem) { showCatalog(true) }
                    if(resumeCards.isNotEmpty()) resumeCards.first().requestFocus()
                }
            })
        } else catalogGrid(body,focusNav,focusType,focusFilter=focusFilter)
        // 剧场与排行榜使用单一 RecyclerView：筛选区作为全宽 Header，海报卡片仍由 CatalogAdapter 回收。
        // 收藏/最近观看等页面保留原有 ScrollView 结构。
        if(tab==2 || tab==3 || tab==4 || tab==7) {
            val fixedCount=1
            val pageContent=column()
            while(container.childCount>fixedCount) {
                val child=container.getChildAt(fixedCount)
                container.removeViewAt(fixedCount)
                pageContent.addView(child)
            }
            val posterRecycler=catalogRecycler
            if((tab==2 || tab==7) && posterRecycler!=null && catalogAdapter!=null) {
                val header=column()
                while(pageContent.childCount>0) {
                    val child=pageContent.getChildAt(0)
                    pageContent.removeViewAt(0)
                    if(child===body) {
                        while(body.childCount>0) {
                            val item=body.getChildAt(0)
                            body.removeViewAt(0)
                            if(item!==posterRecycler) header.addView(item)
                        }
                    } else header.addView(child)
                }
                val headerAdapter=object: RecyclerView.Adapter<RecyclerView.ViewHolder>() {
                    override fun getItemCount()=1
                    override fun onCreateViewHolder(parent:ViewGroup,viewType:Int)=object:RecyclerView.ViewHolder(header) {}
                    override fun onBindViewHolder(holder:RecyclerView.ViewHolder,position:Int) {}
                }
                val cardsAdapter=catalogAdapter!!
                posterRecycler.adapter=androidx.recyclerview.widget.ConcatAdapter(headerAdapter,cardsAdapter)
                val layout=posterRecycler.layoutManager as? GridLayoutManager
                layout?.spanSizeLookup=object:GridLayoutManager.SpanSizeLookup() {
                    override fun getSpanSize(position:Int)=if(position==0 || cardsAdapter.isFooter(position-1)) cardsAdapter.cols else 1
                }
                posterRecycler.addOnScrollListener(object:RecyclerView.OnScrollListener() {
                    override fun onScrolled(recyclerView:RecyclerView,dx:Int,dy:Int) {
                        onCatalogContentScroll(recyclerView.canScrollVertically(-1))
                        if(!recyclerView.canScrollVertically(1)) maybeLoadMore()
                    }
                })
                posterRecycler.isNestedScrollingEnabled=true
                container.addView(posterRecycler,lp(-1,0).apply { weight=1f })
                // ConcatAdapter 已安装且 RecyclerView 已加入页面；焦点连接函数会等 Header 与网格完成布局后统一连线。
                if(tab==7) theaterFilterHost?.let { wireTheaterFilterFocus(it,posterRecycler) }
                val target=catalogAdapter?.displayed?.indexOfFirst { it.id==catalogFocus } ?: -1
                if(target>=0) posterRecycler.post { (posterRecycler.layoutManager as? GridLayoutManager)?.scrollToPositionWithOffset(target+1,0) }
            } else {
                val viewportDp=(heightDp()-(if(compactHeader) 91f else 51f)-24f).coerceAtLeast(180f)
                body.layoutParams=lp(-1,dp(viewportDp))
                val pageScroll=ScrollView(this).apply {
                    isVerticalScrollBarEnabled=false
                    clipToPadding=false
                    isFillViewport=true
                    overScrollMode=View.OVER_SCROLL_NEVER
                    descendantFocusability=android.view.ViewGroup.FOCUS_AFTER_DESCENDANTS
                }
                pageScroll.setOnScrollChangeListener { view:View,_,scrollY,_,_ ->
                    val scrollView=view as ScrollView
                    onCatalogContentScroll(scrollY>dp(1))
                }
                pageScroll.addView(pageContent,ViewGroup.LayoutParams(-1,-2))
                container.addView(pageScroll,lp(-1,0).apply { weight=1f })
            }
        }
        // 页面重建后初始化联想区：回显点击联想词前的列表，或无词时收起中栏
        if(tab==1) { fetchSuggest(); if(query.isBlank()) loadSearchHot() }
}

internal fun MainActivity.showCatalog(load: Boolean=false,focusNav: Boolean=false,focusType: Boolean=false,focusFilter: Boolean=false) {
        // Any new catalog render supersedes an older homepage refresh request.
        refreshHomePending=false
        initialCatalogPending=false; restoreStoppedPage=false; pendingDetail=null
        // Never cache a new page number with the previous page's results while a request is pending.
        if(load) {
            val cached=if(tab==0 && page==1 && genre==null) library.cachedHome(library.contentType) else null
            catalog=cached?.items ?: emptyList(); hasMore=cached?.hasMore ?: false; ranking=null; homeFromCache=cached!=null
        }
        invalidatePage(); screen="catalog"; applyDeviceOrientationPolicy(); releaseCatalogViews(); val container=base()
        if(foreground && library.isLoaded && !favoriteMonitor.running) main.post { if(foreground && screen=="catalog") favoriteMonitor.check() }
        val compactHeader=widthDp()<900
        renderCatalogHeader(container, compactHeader)

        renderCatalogContent(container, load, focusNav, focusType, focusFilter, compactHeader)
    }
