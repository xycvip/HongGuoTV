// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.hongguotv.core.*

/** 追剧页、排行榜页的页内头部（页签式导航已上收到左侧竖栏）。 */
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
            // 竖栏里"下"应该继续在竖栏内换页，这里不再把下键硬接到内容上。
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
}
