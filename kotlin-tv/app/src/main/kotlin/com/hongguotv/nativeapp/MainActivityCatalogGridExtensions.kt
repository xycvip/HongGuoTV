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
import java.io.IOException
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

    internal fun MainActivity.catalogGrid(body: LinearLayout,focusNav: Boolean,focusType: Boolean=false,loading: Boolean=false,focusFilter: Boolean=false) {
        if(tab==0 && page==1 && collection==null && genre==null) { renderHome(body,focusNav,focusType,loading); return }
        collectionBack=null; genreClear=null
        if(collection!=null) {
            val heading=row()
            collectionBack=addButton(heading,"‹ 返回首页") { leaveCollection(); showCatalog() }.apply {
                nextFocusUpId=typeButtons[library.contentType]?.id ?: navForTab().id
                nextFocusDownId=id
            }
            heading.addView(text(when(collection) { "later" -> "稍后看"; "updates" -> "收藏有更新"; "hot" -> "热门发现"; "hot-short" -> "热门发现 · 短剧"; "hot-comic" -> "热门发现 · 漫剧"; "hot-ai" -> "热门发现 · AI剧"; else -> "接着看" },22f))
            body.addView(heading)
            typeButtons.values.forEach { it.nextFocusDownId=collectionBack!!.id }
        }
        catalogPageKey="$tab:${if(actorRanking) "actor" else ""}:${collection.orEmpty()}:${if(tab==0) genre?.slug.orEmpty() else ""}:${if(tab==7) theaterFilterKey(theaterContentType)+theaterSelections.toSortedMap() else ""}:$page:$query"
        if(tab==0 && genre!=null) {
            val bar=row().apply { setPadding(0,0,0,dp(6)) }
            bar.addView(text("题材 · ${genre?.name}",16f).apply { setTypeface(null,Typeface.BOLD) },lp(0,-2).apply { weight=1f })
            genreClear=addButton(bar,"全部题材") {
                pendingRefreshFocus="focus:home:genre"
                genre=null; page=1; catalogFocus=""; tabState.remove(0); tabFocus.remove(0); showCatalog(true)
            }.apply {
                tag="home:genre-clear"
                nextFocusUpId=genreButton?.id ?: typeButtons[library.contentType]?.id ?: navForTab().id
            }
            body.addView(bar)
            typeButtons.values.forEach { it.nextFocusDownId=genreClear!!.id }
            genreButton?.nextFocusDownId=genreClear!!.id
        }
        val preview=SeriesPreview(this); selectionPreview=preview; body.addView(preview)
        val displayed=mutableListOf<Series>()
        displayed+=if(localCatalog()) catalog else if(tab==0) catalog.filterNot { it.id in library.hidden() } else catalog
        if(displayed.isEmpty()) {
            message(body,if(loading) "正在加载推荐…" else when(tab) { 1 -> if(query.isBlank()) "输入名称关键词，或用字母键拼首拼选择联想词" else if(aggregateSearch) "没有找到相关漫剧或短剧，换个关键词试试" else "没有找到相关${library.contentType.label}，换个关键词试试"; 3 -> if(favoriteGroupFilter!=null) "「$favoriteGroupFilter」分组暂无收藏" else "在剧集详情中选择收藏，喜欢的剧就会出现在这里"; 4 -> "播放过的剧集会自动保存在这里"; else -> "本页没有更多内容" })
            wireSearchPanel(null)
            if(focusType) (if(tab==1) searchInput else navForTab())?.requestFocus()
            else if(pendingRefreshFocus==null && !(tab==2 && rankFocusTag!=null)) {
                if(focusFilter && favoriteFilterButtons.isNotEmpty()) favoriteFilterButtons[favoriteFilterFocus.coerceIn(0,favoriteFilterButtons.size-1)].requestFocus()
                else if(collectionBack!=null && !focusNav) collectionBack?.requestFocus()
                else navForTab().requestFocus()
            }
            return
        }
        val cols=if(tab==1) searchColumns else gridColumns
        val adapter=CatalogAdapter(this,displayed,cols)
        val grid=GridLayoutManager(this,cols)
        grid.spanSizeLookup=object: GridLayoutManager.SpanSizeLookup() { override fun getSpanSize(position: Int)=if(adapter.isFooter(position)) cols else 1 }
        val recycler=RecyclerView(this).apply {
            id=View.generateViewId(); clipToPadding=false; isVerticalScrollBarEnabled=false; itemAnimator=null; isNestedScrollingEnabled=true
            layoutManager=grid; this.adapter=adapter
            val focusRecycler=this
            // 筛选头把焦点交给 RecyclerView 时，必须先由容器自身接住，
            // 再由下面的焦点监听明确转交给海报；否则默认 AFTER_DESCENDANTS
            // 可能优先回到 Header 内的筛选按钮，造成海报已显示却无法向下选中。
            descendantFocusability=ViewGroup.FOCUS_BEFORE_DESCENDANTS
            // Focus entering the grid lands on the first visible card, keeping the user's place.
            isFocusable=true; isFocusableInTouchMode=true
            setOnFocusChangeListener { _,focused ->
                if(focused) focusRecycler.post {
                    if(!focusRecycler.isAttachedToWindow) return@post
                    // 剧场/排行榜的 ConcatAdapter 第 0 项是筛选/榜单 Header，
                    // 首张海报从全局 position 1 开始。Header 可能占满视口，
                    // 此时第一张海报尚未创建 ViewHolder，直接 requestFocus 会失败。
                    val first=grid.findFirstVisibleItemPosition().coerceAtLeast(if(tab==2 || tab==7) 1 else 0)
                    val target=focusRecycler.findViewHolderForAdapterPosition(first)?.itemView
                    if(target!=null) {
                        target.requestFocus()
                    } else {
                        // 先滚动并等待目标卡片真正 attach，再交接焦点，不能只 post 一次赌布局时序。
                        val attachListener=object:RecyclerView.OnChildAttachStateChangeListener {
                            override fun onChildViewAttachedToWindow(view:View) {
                                if(focusRecycler.getChildAdapterPosition(view)==first) {
                                    focusRecycler.removeOnChildAttachStateChangeListener(this)
                                    view.post { if(focusRecycler.isAttachedToWindow && view.isAttachedToWindow) view.requestFocus() }
                                }
                            }
                            override fun onChildViewDetachedFromWindow(view:View) = Unit
                        }
                        focusRecycler.addOnChildAttachStateChangeListener(attachListener)
                        grid.scrollToPositionWithOffset(first,0)
                    }
                }
            }
            // 长按下键到达最后一项后拦截焦点搜索，避免焦点跳出网格跑到顶部导航栏形成循环
            setOnKeyListener { _,keyCode,event ->
                if(keyCode==KeyEvent.KEYCODE_DPAD_DOWN && event.action==KeyEvent.ACTION_DOWN) {
                    val focusedChild=focusedChild
                    if(focusedChild!=null) {
                        val pos=getChildAdapterPosition(focusedChild)
                        val total=adapter.itemCount+if(tab==2 || tab==7) 1 else 0
                        if(pos>=0 && pos>=total-1) return@setOnKeyListener true
                    }
                }
                false
            }
        }
        catalogRecycler=recycler; catalogAdapter=adapter
        recycler.addOnScrollListener(object: RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView:RecyclerView,dx:Int,dy:Int) {
                // 剧场与排行榜由外层 pageScroll 独占滚动状态；内部网格不得反向显示导航栏。
                if(tab !in setOf(2,7)) onCatalogContentScroll(recyclerView.canScrollVertically(-1))
            }
        })
        (searchBody as? SearchResultsColumn)?.scrollTarget=recycler
        adapter.footer=when {
            localCatalog() -> if(collection=="hot" && collectionReturn?.hasMore==true) FOOTER_MORE_HOT else FOOTER_NONE
            hasMore -> FOOTER_NONE
            else -> FOOTER_END
        }
        if(tab==7 || tab==2) {
            // 剧场与排行榜稍后会把筛选头与海报网格合并进同一个 RecyclerView。
            body.addView(recycler,lp(-1,-2))
        } else body.addView(recycler,lp(-1,0).apply { weight=1f })
        nav.forEach {
            it.nextFocusDownId=when {
                tab==2 -> (if(actorRanking) actorRankButton else root.findViewWithTag<View>("rank:board:${rankBoard.name}") ?: rankSwitch)?.id ?: recycler.id
                tab==1 -> searchInput?.id ?: recycler.id
                tab==0 -> collectionBack?.id ?: navForTab().id ?: recycler.id
                tab==7 -> ((theaterFilterHost?.getChildAt(0) as? ViewGroup)?.getChildAt(0)?.id ?: recycler.id)
                tab==4 -> root.findViewWithTag<View>("recent:watch-stats")?.id ?: historyManage?.id ?: recycler.id
                else -> favoriteCheck?.id ?: historyManage?.id ?: recycler.id
            }
        }
        rankSwitch?.nextFocusDownId=recycler.id; rankRefresh?.nextFocusDownId=recycler.id; historyManage?.nextFocusDownId=recycler.id; favoriteCheck?.nextFocusDownId=favoriteFilterFirst?.id ?: recycler.id
        if(tab==2) RankBoard.entries.forEach { board -> root.findViewWithTag<View>("rank:board:${board.name}")?.nextFocusDownId=recycler.id }
        root.findViewWithTag<View>("recent:watch-stats")?.nextFocusDownId=recycler.id
        actorRankButton?.nextFocusDownId=recycler.id
        favoriteFilterButtons.forEach { it.nextFocusDownId=recycler.id }
        collectionBack?.nextFocusDownId=recycler.id; genreClear?.nextFocusDownId=recycler.id
        typeButtons.values.forEach { it.nextFocusDownId=searchInput?.id ?: collectionBack?.id ?: genreClear?.id ?: recycler.id }
        genreButton?.nextFocusDownId=genreClear?.id ?: recycler.id

        searchInput?.nextFocusDownId=azFirst?.id ?: recycler.id
        searchButton?.nextFocusDownId=azFirst?.id ?: recycler.id
        searchActions.forEach { it.nextFocusDownId=azFirst?.id ?: recycler.id }
        wireSearchPanel(recycler)
        if(focusType) (if(tab==1) searchInput else navForTab())?.requestFocus() else if(focusNav) navForTab().requestFocus()
        else if(focusFilter && favoriteFilterButtons.isNotEmpty()) favoriteFilterButtons[favoriteFilterFocus.coerceIn(0,favoriteFilterButtons.size-1)].requestFocus()
        else {
            val found=displayed.indexOfFirst { it.id==catalogFocus }
            val target=if(found>=0) found else scrollPositions[catalogPageKey]?.coerceIn(0,displayed.size-1) ?: 0
            val adapterOffset=if(tab==2 || tab==7) 1 else 0
            grid.scrollToPositionWithOffset(target+adapterOffset,0)
            recycler.post {
                if(catalogRecycler!==recycler || !recycler.isAttachedToWindow) return@post
                // 排行榜顶部按钮直接指向首张海报，绕过 RecyclerView 容器的二次焦点转交。
                if(tab==2) {
                    val firstPoster=recycler.findViewHolderForAdapterPosition(1)?.itemView
                    if(firstPoster?.isFocusable==true && firstPoster.isAttachedToWindow) {
                        val selectedBoard=if(actorRanking) actorRankButton else root.findViewWithTag<View>("rank:board:${rankBoard.name}") ?: rankSwitch
                        val firstRowLastPosition=minOf(cols,displayed.size)
                        // 顶部每张演员/短剧榜卡片按上键都明确回到当前选中的榜单入口。
                        for(position in 1..firstRowLastPosition) {
                            recycler.findViewHolderForAdapterPosition(position)?.itemView?.nextFocusUpId=selectedBoard?.id ?: View.NO_ID
                        }
                        (listOfNotNull(rankSwitch,actorRankButton,rankRefresh) +
                            RankBoard.entries.mapNotNull { root.findViewWithTag<View>("rank:board:${it.name}") })
                            .forEach { it.nextFocusDownId=firstPoster.id }
                    }
                }
                recycler.findViewHolderForAdapterPosition(target+adapterOffset)?.itemView?.requestFocus()
            }
        }
    }

    internal fun MainActivity.maybeLoadMore() {
        val adapter=catalogAdapter ?: return
        if(randomDialog?.isShowing==true || loadingMore || !hasMore || localCatalog() || screen!="catalog") return
        if(page>=waterfallMaxPages || catalog.size>=waterfallMaxPages*20) { hasMore=false; adapter.updateFooter(FOOTER_END); return }
        val requestTab=tab; val requestPage=page+1; val requestQuery=query; val requestType=library.contentType
        val requestBoard=rankBoard; val requestGenre=if(requestTab==0) genre else null
        val requestActor=requestTab==2 && actorRanking
        val requestActorOffset=actorRankNextOffset
        val requestActorSession=actorRankSessionId
        val requestTheaterType=theaterContentType; val requestTheaterSelection=theaterSelections.toMap()
        var responseActorPage:ActorRankPage? = null
        loadingMore=true; adapter.updateFooter(FOOTER_LOADING)
        work({
            when(requestTab) {
                0 -> repository.home(requestPage,requestType,requestGenre)
                7 -> repository.theater(requestPage,requestTheaterType,requestTheaterSelection).catalog
                2 -> if(requestActor) repository.actorRanking(requestActorOffset,requestActorSession).let { actorPage ->
                    responseActorPage=actorPage
                    CatalogPage(actorPage.items.map { it.toSeries() },actorPage.hasMore)
                } else repository.ranking(requestBoard,requestPage)
                else -> repository.search(requestQuery,requestPage,requestType)
            }
        }, { result ->
            loadingMore=false
            page=requestPage
            hasMore=result.hasMore
            if(requestActor) {
                val actorPage=responseActorPage ?: throw IOException("演员榜分页响应丢失")
                val knownActors=actorRankItems.mapTo(HashSet()) { it.userId }
                actorRankItems=actorRankItems+actorPage.items.filter { it.userId !in knownActors }
                actorRankNextOffset=actorPage.nextOffset
                actorRankSessionId=actorPage.sessionId
                actorRankVersion=actorPage.version
                ranking=null
                rankSubtitle?.text="来源：红果演员剧星榜"
            } else {
                result.ranking?.let { r -> ranking=ComicRanking((ranking?.positions.orEmpty())+r.positions,r.totalPages,r.updatedText.ifBlank { ranking?.updatedText.orEmpty() }) }
                if(requestTab==2) rankSubtitle?.text=ranking?.updatedText?.ifBlank { rankBoard.sourceText } ?: rankBoard.sourceText
            }
            val known=catalog.mapTo(HashSet()) { it.id }
            val fresh=result.items.filter { it.id !in known }
            catalog=catalog+fresh
            val current=catalogAdapter
            if(current!=null) {
                val visible=fresh.filter { tab!=0 || it.id !in library.hidden() }
                val insertAt=current.displayed.size
                current.displayed.addAll(visible)
                if(visible.isNotEmpty()) current.notifyItemRangeInserted(insertAt,visible.size)
                current.updateFooter(if(hasMore && page<waterfallMaxPages && catalog.size<waterfallMaxPages*20) FOOTER_NONE else FOOTER_END)
            }
        }, { problem ->
            loadingMore=false
            if(problem is SearchSessionExpiredException) {
                page=1; catalogFocus=""
                Toast.makeText(this,"搜索结果已过期，已回到第 1 页刷新",Toast.LENGTH_LONG).show()
                showCatalog(true)
            } else {
                Toast.makeText(this,"加载失败，继续浏览会自动重试",Toast.LENGTH_SHORT).show()
                catalogAdapter?.updateFooter(FOOTER_ERROR)
            }
        })
    }
