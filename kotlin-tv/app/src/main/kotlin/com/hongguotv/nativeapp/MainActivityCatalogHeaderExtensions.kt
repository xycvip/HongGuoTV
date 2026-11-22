// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.hongguotv.core.*

/** 首页/剧场等主页面共用的顶部导航与四叶接口状态入口。 */
internal fun MainActivity.renderCatalogHeader(container: LinearLayout, compactHeader: Boolean) {
        // 页面重建时清空上一轮导航引用，确保显示开关关闭后不残留隐藏入口或旧焦点。
        nav.clear()
        navByTab.clear()
        searchNavShortcut=null
        // 四叶状态灯位于导航栏的随便播放快捷入口内；点击仍执行随机播放。
        stopApiStatusAnimations()
        apiStatusViews.clear()
        val flower=ApiStatusFlowerView(this).apply {
            isFocusable=false; isFocusableInTouchMode=false; isClickable=false
            background=null
            contentDescription="四项接口状态指示灯"
        }
        listOf("首页","搜索","播放","联想").forEach { name ->
            apiStatusViews[name]=flower
            apiStatusResults[name]?.let { (color,detail) ->
                if(detail.startsWith("检测中") || detail.startsWith("重试中")) animateApiStatus(name,color,detail)
                else setApiStatus(name,color,detail)
            }
        }
        catalogNavFlower=flower
        // 指示灯直接挂在导航栏容器内，使用 CENTER_VERTICAL 对齐导航栏中心线。
        // 叶片绘制尺寸变化不会参与定位计算，缩放时中心坐标始终固定。

        // 四叶接口状态灯迁移到原随机播放快捷入口，点击仍执行随机播放。
        val shortcuts=row().apply {
            gravity=Gravity.CENTER_VERTICAL
            setPadding(dp(4),dp(4),dp(4),dp(4))
            background=null
        }
        val randomShortcut=FrameLayout(this).apply {
            id=View.generateViewId()
            contentDescription="随便播放"
            isFocusable=true
            isFocusableInTouchMode=true
            isClickable=true
            minimumWidth=dp(40)
            minimumHeight=dp(40)
            background=rounded(Color.TRANSPARENT)
            // 四叶指示灯调整为 25sp；仍以快捷入口背景框中心定位，不改变点击区域与焦点布局。
            val flowerSize=android.util.TypedValue.applyDimension(
                android.util.TypedValue.COMPLEX_UNIT_SP,25f,resources.displayMetrics
            ).toInt().coerceAtLeast(1)
            addView(flower,FrameLayout.LayoutParams(flowerSize,flowerSize,Gravity.CENTER))
            setOnClickListener { showRandomPicks() }
            fun paint(focused:Boolean) {
                background=if(focused) TvStyle.shape(this@renderCatalogHeader,withAlpha(TvStyle.colors.textPrimary,.16f),TvStyle.colors.focusRing,TvMetric.RADIUS_PILL) else null
                elevation=if(focused) dp(4).toFloat() else 0f
                animateFocus(focused,1.05f)
            }
            paint(false)
            setOnFocusChangeListener { _,focused ->
                paint(focused)
                if(focused) revealCatalogNavForFocus()
            }
        }
        shortcuts.addView(randomShortcut,lp(dp(40),dp(40)))
        nav+=randomShortcut
        val searchShortcut=addButton(shortcuts,"⌕",tab==1) { switchTab(1) }.apply {
            contentDescription="搜索"
            gravity=Gravity.CENTER
            textSize=TvMetric.TEXT_TITLE
            minWidth=dp(40)
            minHeight=dp(40)
            setPadding(dp(8),dp(5),dp(8),dp(5))
            (layoutParams as? LinearLayout.LayoutParams)?.let { params ->
                params.width=LinearLayout.LayoutParams.WRAP_CONTENT
                params.height=LinearLayout.LayoutParams.WRAP_CONTENT
                params.rightMargin=0
                layoutParams=params
            }
            fun paint(focused:Boolean) {
                paintNavItem(this,focused,tab==1)
            }
            // 与首页、剧场等导航标签使用完全相同的未选中透明背景规则。
            paint(false)
            setOnFocusChangeListener { _,focused ->
                paint(focused)
                if(focused) revealCatalogNavForFocus()
            }
        }
        searchNavShortcut=searchShortcut

        // 所有页面导航共享同一个连续背景，不再让每个标签各自成为独立卡片。
        val pageNav=row().apply {
            setPadding(dp(5),dp(4),dp(5),dp(4))
            gravity=Gravity.CENTER_VERTICAL
            background=null
        }
        val pageNavItems=mutableListOf<View>()
        listOf(0 to "首页",7 to "剧场",2 to "排行榜",9 to "追剧",5 to "设置",-1 to "推送播放",6 to "红果账户").forEach { (tabId,label) ->
            val active=if(pushPlaybackPageActive) tabId==-1 else when(tabId) { -1 -> false; 9 -> tab==3 || tab==4; 6 -> screen=="account"; else -> tabId==tab }
            val item=addButton(pageNav,label,active) { if(tabId==-1) showPushPlayback() else switchTab(tabId) }
            if(tabId==-1) item.tag="nav:push-playback"
            item.textSize=if(compactHeader) TvMetric.TEXT_BODY else TvMetric.TEXT_LABEL
            item.minHeight=dp(40)
            item.setPadding(dp(if(compactHeader) 11 else 14),dp(8),dp(if(compactHeader) 11 else 14),dp(8))
            fun paint(focused:Boolean) {
                val active=if(pushPlaybackPageActive) tabId==-1 else when(tabId) { -1 -> false; 9 -> tab==3 || tab==4; 6 -> screen=="account"; else -> tabId==tab }
                paintNavItem(item,focused,active)
            }
            paint(false)
            item.setOnFocusChangeListener { _,focused ->
                paint(focused)
                if(focused) revealCatalogNavForFocus()
            }
            nav+=item
            if(tabId!=-1) navByTab[tabId]=item
            if(tabId==9) { navByTab[3]=item; navByTab[4]=item }
            pageNavItems+=item
        }
        // 片源入口紧跟「红果账户」；headerContent/pageNav 均按实际子项宽度测量，整体自动居中。
        val showThirdSource=getSharedPreferences("appearance-settings-v1",android.content.Context.MODE_PRIVATE)
            .getBoolean("show_third_source",false)
        if(showThirdSource) {
            val sourceItem=addButton(pageNav,"片源",screen=="third_source") { pushPlaybackPageActive=false; closePushPlaybackService(); openThirdSource() }
            sourceItem.textSize=if(compactHeader) TvMetric.TEXT_BODY else TvMetric.TEXT_LABEL
            sourceItem.minHeight=dp(40)
            sourceItem.setPadding(dp(if(compactHeader) 11 else 14),dp(8),dp(if(compactHeader) 11 else 14),dp(8))
            fun paintSource(focused:Boolean) {
                paintNavItem(sourceItem,focused,!pushPlaybackPageActive && screen=="third_source")
            }
            paintSource(false)
            sourceItem.setOnFocusChangeListener { _,focused -> paintSource(focused); if(focused) revealCatalogNavForFocus() }
            nav+=sourceItem
            navByTab[8]=sourceItem
            pageNavItems+=sourceItem
        }
        val navFrame=FrameLayout(this)
        val headerContent=row().apply {
            // 快捷入口与页面导航共用一整条主题磨砂玻璃底，形成连续的导航栏表面。
            gravity=Gravity.CENTER
            setPadding(dp(7),0,dp(7),0)
            background=TvStyle.navGlass(this@renderCatalogHeader)
            addView(shortcuts,lp(-2,dp(48)))
            addView(pageNav,lp(-2,dp(48)))
        }
        if(compactHeader) {
            val navScroll=HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled=false; isFillViewport=true; clipToPadding=false
                setPadding(0,0,0,dp(2))
            }
            // 内容未超出屏幕时让导航整体居中；超出时按实际内容宽度滚动，不把新增/隐藏项留下偏移。
            val scrollContent=FrameLayout(this).apply {
                clipChildren=false
                addView(headerContent,FrameLayout.LayoutParams(-2,dp(50),Gravity.CENTER))
            }
            navScroll.addView(scrollContent,ViewGroup.LayoutParams(-1,dp(50)))
            navScroll.post {
                if(!navScroll.isAttachedToWindow) return@post
                scrollContent.measure(
                    View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED),
                    View.MeasureSpec.makeMeasureSpec(dp(50),View.MeasureSpec.EXACTLY)
                )
                val requiredWidth=maxOf(navScroll.width,scrollContent.measuredWidth)
                val params=scrollContent.layoutParams
                if(params.width!=requiredWidth) {
                    params.width=requiredWidth
                    scrollContent.layoutParams=params
                }
            }
            navFrame.addView(navScroll,FrameLayout.LayoutParams(-1,dp(52),Gravity.CENTER))
        } else {
            navFrame.addView(headerContent,FrameLayout.LayoutParams(-2,dp(50),Gravity.CENTER))
        }
        // 顶部导航建立固定的单行焦点链：随机 → 搜索 → 首页 → 其他页面。
        randomShortcut.nextFocusLeftId=randomShortcut.id
        randomShortcut.nextFocusRightId=searchShortcut.id
        searchShortcut.nextFocusLeftId=randomShortcut.id
        searchShortcut.nextFocusRightId=pageNavItems.firstOrNull()?.id ?: searchShortcut.id
        pageNavItems.forEachIndexed { index, item ->
            item.nextFocusLeftId=if(index==0) searchShortcut.id else pageNavItems[index-1].id
            item.nextFocusRightId=pageNavItems.getOrNull(index+1)?.id ?: item.id
        }
        nav.forEach { it.nextFocusUpId=it.id }
        catalogNavFrame=navFrame
        catalogNavVisible=true
        container.addView(navFrame,lp(-1,dp(52)))
}

internal fun MainActivity.renderCatalogLibraryHeader(container: LinearLayout) {

            // 追剧页内部切换入口：收藏与最近观看共用同一内容页面，不创建独立页面栈。
            // 原来两个"标签"自己画白底/深色字，和全站语气分级脱节；现在统一用胶囊。
            val sectionRow=row().apply {
                gravity=Gravity.CENTER_VERTICAL
                setPadding(0,dp(TvMetric.GAP_S),0,dp(TvMetric.GAP_XS))
            }
            val sectionButtons=listOf(
                addChip(sectionRow,"最近观看",tab==4) { if(tab!=4) switchTab(4) },
                addChip(sectionRow,"收藏",tab==3) { if(tab!=3) switchTab(3) }
            )
            navForTab().nextFocusDownId=sectionButtons.first().id
            sectionButtons.forEachIndexed { index,button ->
                button.tag=if(index==0) "watch:recent" else "watch:favorite"
                button.nextFocusUpId=navForTab().id
                button.nextFocusLeftId=sectionButtons.getOrNull(index-1)?.id ?: button.id
                button.nextFocusRightId=sectionButtons.getOrNull(index+1)?.id ?: button.id
            }
            container.addView(sectionRow)
            val heading=sectionHeader(
                if(tab==3) "我的收藏" else "接着上次看",
                if(tab==3) {
                    val count=library.favorites().size
                    if(count==0) "还没有收藏" else "共 $count 部"
                } else {
                    val count=library.history().size
                    if(count==0) "还没有观看记录" else "共 $count 部"
                },
                TvMetric.TEXT_TITLE,
            ).apply { setPadding(0,dp(TvMetric.GAP_S),0,dp(TvMetric.GAP_XS)) }
            if(tab==3) {
                favoriteCheck=addToneButton(heading,"检查更新",TvTone.Ghost) {
                    if(favoriteMonitor.running) Toast.makeText(this,"正在检查收藏更新…",Toast.LENGTH_SHORT).show()
                    else if(library.favorites().isEmpty()) Toast.makeText(this,"先收藏几部剧，再来检查更新",Toast.LENGTH_SHORT).show()
                    else { favoriteMonitor.check(force=true); Toast.makeText(this,"开始检查收藏更新…",Toast.LENGTH_SHORT).show() }
                }.apply { nextFocusUpId=navForTab().id }
            }
            if(tab==4) {
                historyManage=null
                lateinit var stats:TextView
                stats=addToneButton(heading,"观看统计",TvTone.Ghost) { showWatchStats(stats) }.apply {
                    tag="recent:watch-stats"
                    nextFocusUpId=navForTab().id
                }
                if(library.history().isNotEmpty()) historyManage=addToneButton(heading,"管理记录",TvTone.Ghost) {
                    val rows=library.history()
                    tvTools.choose("选择要管理的观看记录",rows.map { it.series.title },historyManage,{ index -> manageHistory(rows[index].series,historyManage) },"清空记录") {
                        tvTools.confirm("清空观看记录","将删除本机所有观看进度，收藏会保留。",historyManage) { library.clearHistory(); catalogFocus=""; showCatalog() }
                    }
                }.apply {
                    tag="recent:manage-history"
                    nextFocusUpId=stats.id
                    nextFocusLeftId=stats.id
                }
                stats.nextFocusRightId=historyManage?.id ?: View.NO_ID
            }
            container.addView(heading)
            if(tab==3) {
                favoriteStatus=text(favoriteMonitor.status,TvMetric.TEXT_CAPTION,TvStyle.colors.textTertiary).apply { setPadding(dp(2),0,0,dp(8)) }; container.addView(favoriteStatus)
            }

}

internal fun MainActivity.renderRankingCatalogHeader(container: LinearLayout,nav: List<View>,loading: Boolean) {

            // 排行榜页直接从榜单筛选栏开始，不再显示重复的「热播榜」标题及其占位空间。
            val boardRow=row().apply { gravity=Gravity.CENTER_VERTICAL; setPadding(0,dp(2),0,dp(8)) }
            val boardFocusItems=mutableListOf<View>()
            listOf(RankBoard.TOTAL, RankBoard.REAL, RankBoard.AI, RankBoard.COMIC).forEachIndexed { index, board ->
                val focusTag = "rank:board:${board.name}"
                val boardButton=addChip(boardRow, board.buttonLabel, !actorRanking && rankBoard == board) {
                    if (rankBoard != board || actorRanking) {
                        pendingRefreshFocus=focusTag
                        rankFocusTag=focusTag
                        rankBoard=board
                        actorRanking=false
                        page=1
                        catalogFocus=""
                        tabState.remove(2)
                        tabFocus.remove(2)
                        showCatalog(true)
                    }
                }.apply {
                    tag=focusTag
                    nextFocusUpId=navForTab().id
                }
                boardFocusItems+=boardButton
                if(index==0) rankSwitch=boardButton
            }
            actorRankButton=addChip(boardRow,"演员榜",actorRanking) {
                if(!actorRanking) {
                    pendingRefreshFocus="rank:actor"
                    rankFocusTag="rank:actor"
                    actorRanking=true
                    actorRankItems=emptyList()
                    actorRankNextOffset=0
                    actorRankSessionId=""
                    actorRankVersion=""
                    page=1
                    catalogFocus=""
                    tabState.remove(2)
                    tabFocus.remove(2)
                    showCatalog(true)
                }
            }.apply {
                tag="rank:actor"
                nextFocusUpId=navForTab().id
            }.also { boardFocusItems+=it }
            if(actorRanking) {
                listOf(
                    "ranklist_series_actor" to "剧星榜",
                    "ranklist_actor_fans" to "星光榜",
                ).forEach { (subBoard,label) ->
                    val focusTag="rank:actor:$subBoard"
                    val button=addChip(boardRow,label,actorRankSubBoard==subBoard) {
                        if(actorRankSubBoard!=subBoard) {
                            pendingRefreshFocus=focusTag
                            rankFocusTag=focusTag
                            actorRankSubBoard=subBoard
                            actorRankItems=emptyList()
                            actorRankNextOffset=0
                            actorRankSessionId=""
                            actorRankVersion=""
                            page=1
                            catalogFocus=""
                            tabState.remove(2)
                            tabFocus.remove(2)
                            showCatalog(true)
                        }
                    }.apply {
                        tag=focusTag
                        nextFocusUpId=actorRankButton?.id ?: navForTab().id
                    }
                    boardFocusItems+=button
                }
            }
            rankRefresh=addChip(boardRow,"刷新") {
                pendingRefreshFocus="rank:refresh"
                rankFocusTag="rank:refresh"
                page=1
                catalogFocus=""
                if(actorRanking) {
                    actorRankItems=emptyList()
                    actorRankNextOffset=0
                    actorRankSessionId=""
                    actorRankVersion=""
                }
                showCatalog(true)
            }.apply {
                tag="rank:refresh"
                nextFocusUpId=navForTab().id
            }.also { boardFocusItems+=it }
            boardFocusItems.forEachIndexed { index, view ->
                view.nextFocusLeftId=boardFocusItems.getOrNull(index-1)?.id ?: view.id
                view.nextFocusRightId=boardFocusItems.getOrNull(index+1)?.id ?: view.id
            }
            val boardScroll=HorizontalScrollView(this).apply {
                isFocusable=false
                isFocusableInTouchMode=false
                isHorizontalScrollBarEnabled=false
                clipToPadding=false
                overScrollMode=View.OVER_SCROLL_NEVER
                addView(boardRow,FrameLayout.LayoutParams(-2,-2))
            }
            container.addView(boardScroll,lp(-1,-2))
            val defaultSource=if(actorRanking) if(actorRankSubBoard=="ranklist_actor_fans") "来源：红果演员星光榜" else "来源：红果演员剧星榜" else rankBoard.sourceText
            rankSubtitle=text(if(loading) "正在读取最新榜单…" else if(actorRanking) defaultSource else ranking?.updatedText?.ifBlank { defaultSource } ?: defaultSource,TvMetric.TEXT_CAPTION,TvStyle.colors.textTertiary).apply { setPadding(dp(2),0,0,dp(10)) }
            container.addView(rankSubtitle)
            nav.forEach { it.nextFocusDownId=boardRow.getChildAt(0)?.id ?: View.NO_ID }
}
