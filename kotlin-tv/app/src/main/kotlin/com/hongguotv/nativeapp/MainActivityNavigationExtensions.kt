// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.EditText
import com.hongguotv.core.*

/** 榜单按枚举顺序循环切换，新增榜单只需在 RankBoard 里加一项。 */
internal fun MainActivity.nextRankBoard()=RankBoard.entries[(rankBoard.ordinal+1)%RankBoard.entries.size]

/** 首页筛选布局仅在宽屏且字体缩放适中时内联显示。 */
internal fun MainActivity.inlineHomeFilters()=tab==0 && resources.configuration.fontScale<=1.2f && widthDp()>=900

// 直接按 tab ID 获取导航项；调整顶部展示顺序时无需同步维护数字索引映射。
internal fun MainActivity.navForTab():View=if(tab==1) searchNavShortcut ?: root else navByTab[if(tab==3 || tab==4) 3 else tab] ?: root

/** 顶部导航栏固定显示，不再跟随内容滚动执行高度动画或透明度动画。 */
// 保留滚动回调接口兼容现有页面，但不再因滚动改变顶部导航栏状态。
internal fun MainActivity.onCatalogContentScroll(contentHasReachedNav:Boolean) = Unit
internal fun MainActivity.onCatalogContentScroll(dy:Int) = Unit
internal fun MainActivity.revealCatalogNavForFocus() = Unit

/** 递归将目录页内的滚动容器恢复到顶部。 */
internal fun MainActivity.resetCatalogContentToTop(view: View) {
    when(view) {
        is ScrollView -> view.scrollTo(0,0)
        is androidx.recyclerview.widget.RecyclerView -> {
            if(view.layoutManager?.canScrollVertically()==true) view.scrollToPosition(0)
        }
    }
    if(view is ViewGroup) {
        for(index in 0 until view.childCount) resetCatalogContentToTop(view.getChildAt(index))
    }
}

    internal fun MainActivity.switchTab(next: Int) {
        val targetTab=if(next==9) 4 else next
        val now=android.os.SystemClock.elapsedRealtime()
        val doubleTapHome=targetTab==0 && tab==0 && navigationUiState.lastHomeNavTapAt>0L &&
            now-navigationUiState.lastHomeNavTapAt in 1..650
        navigationUiState.lastHomeNavTapAt=if(targetTab==0 && !doubleTapHome) now else 0L
        debugLog("页面导航：主导航页签 $tab -> $targetTab；首页双击刷新=$doubleTapHome")
        if(pushPlaybackPageActive || pushPlaybackServer!=null) {
            pushPlaybackPageActive=false
            closePushPlaybackService()
        }
        leaveCollection()
        tabState[tab]=CatalogState(page,catalog,hasMore,ranking,rankBoard,genre,aiCatalog,aiGenre); tabFocus[tab]=catalogFocus
        // 从其他模块切回搜索页时重置为全新搜索状态，不保留上次的搜索词与结果
        if(targetTab==1) {
            query=""; aggregateSearch=true; phoneSearched=false; lastSuggestWords=emptyList<String>(); lastSuggestKey=""; suggestFocusIndex=-1; keyboardLastLabel=null
            searchHotLoaded=false; searchHotLoading=false
            tabState.remove(1); tabFocus.remove(1)
        }
        tab=targetTab; val saved=tabState[targetTab]; page=saved?.page ?: 1; catalog=saved?.items ?: emptyList(); hasMore=saved?.hasMore ?: false; ranking=saved?.ranking; catalogFocus=tabFocus[targetTab].orEmpty()
        rankBoard=saved?.board ?: rankBoard; genre=saved?.genre ?: genre
        aiCatalog=false; aiGenre=null
        if(targetTab==0) {
            if(doubleTapHome) {
                // 首页连续双击才执行强制刷新；单击仅进入/停留首页，不打断当前内容。
                refreshAllHome(focusNav=true)
            } else {
                // 单击首页只恢复页面；仅在尚无首页数据时执行首次加载。
                showCatalog(load=catalog.isEmpty(),focusNav=true)
            }
            if(!apiStatusCheckStarted) main.post { runAutomaticApiChecks() }
        } else {
            showCatalog(load=(targetTab==7 || (targetTab<=2 && catalog.isEmpty() && (targetTab!=1 || query.isNotBlank()))))
        }
        // 切回搜索页时焦点落输入框，避免页面重建后焦点丢失
        if(targetTab==1) searchInput?.requestFocus()
        if(targetTab==3 || targetTab==4) {
            val focusTag=if(targetTab==3) "watch:favorite" else "watch:recent"
            root.post {
                if(screen=="catalog" && tab==targetTab) {
                    root.findViewWithTag<View>(focusTag)?.takeIf { it.isAttachedToWindow }?.requestFocus()
                }
            }
        }
    }

    internal fun MainActivity.switchContentType(type: ContentType) {
        debugLog("内容类型切换：$type；当前页签=$tab")
        if(library.contentType==type && (tab!=1 || !aggregateSearch)) return
        if(tab==1) aggregateSearch=false
        leaveCollection()
        (searchInput as? EditText)?.let { query=it.text.toString().trim() }
        library.contentType=type
        // 切换内容类型后旧联想不再适用，清空回显记忆
        lastSuggestWords=emptyList<String>(); lastSuggestKey=""; phoneSearched=false
        for(index in 0..1) { tabState.remove(index); tabFocus.remove(index) }
        releaseCatalogViews()
        page=1; catalog=emptyList(); hasMore=false; ranking=null; catalogFocus=""; homePosition=null; genre=null
        showCatalog(load=(tab==0 || query.isNotBlank()),focusType=true)
    }

    internal fun MainActivity.switchAggregateSearch() {
        debugLog("搜索模式切换：聚合搜索；关键词长度=${query.length}")
        if(tab!=1 || aggregateSearch) return
        leaveCollection()
        aggregateSearch=true
        lastSuggestWords=emptyList<String>(); lastSuggestKey=""; phoneSearched=false
        for(index in 0..1) { tabState.remove(index); tabFocus.remove(index) }
        releaseCatalogViews()
        page=1; catalog=emptyList(); hasMore=false; ranking=null; catalogFocus=""; homePosition=null; genre=null
        showCatalog(load=query.isNotBlank(),focusType=true)
    }

    internal fun MainActivity.leaveCollection() {
        if(collection==null) return
        collection=null
        collectionReturn?.let { page=it.page; catalog=it.items; hasMore=it.hasMore; ranking=it.ranking }
        catalogFocus=collectionFocus; collectionReturn=null
    }

    internal fun MainActivity.showCollection(kind: String) {
        collectionReturn=CatalogState(page,catalog,hasMore,ranking); collectionFocus=catalogFocus
        collection=kind; page=1; catalogFocus=""; showCatalog()
    }

    internal fun MainActivity.setCatalogNavVisible(visible:Boolean=true,animated:Boolean=true) {
        val frame=catalogNavFrame ?: return
        if(catalogNavVisible && frame.layoutParams.height==dp(52)) return
        catalogNavVisible=true
        frame.layoutParams=frame.layoutParams.apply { height=dp(52) }
        catalogNavFlower?.apply {
            translationY=0f
            alpha=1f
        }
    }

