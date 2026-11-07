// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.view.View
import android.widget.LinearLayout
import com.hongguotv.core.*

    internal fun MainActivity.renderHome(body: LinearLayout,focusNav: Boolean,focusType: Boolean,loading: Boolean) {
        homeUpdateCount=library.updatedFavorites()
        val preview=SeriesPreview(this); selectionPreview=preview; body.addView(preview)
        val top=navByTab[0] ?: root
        val home=HomeScreen(this,top,::loadCover,{ item,label -> preview.show(item,if(label.startsWith("第 ")) selectionStatus(item) else listOf(label,selectionStatus(item)).filter { it.isNotBlank() }.joinToString(" · ")) },
            { item,resume -> openDetail(item,resume) },::quickActions,{ catalogFocus=it },::onCatalogContentScroll)
        homeScreen=home; body.addView(home,lp(-1,0).apply { weight=1f })
        val hot=catalog.filterNot { it.id in library.hidden() }
        home.render(homeShelves(),catalogFocus,!focusNav && !focusType,null,homePosition)
        // 首页导航按下方向固定进入首页首排第一张海报。
        nav.forEach { it.nextFocusDownId=home.firstId() }
        if(focusNav) navByTab[0]?.requestFocus() else if(focusType) (genreButton ?: home.firstId().let { root.findViewById<View>(it) })?.requestFocus()
        loadHomeShelf(if(library.contentType==ContentType.SHORT) ContentType.COMIC else ContentType.SHORT,false)
        loadHomeShelf(ContentType.AI,false)
        if(hot.isNotEmpty() && !homeContentLogged) {
            homeContentLogged=true
            body.post { android.util.Log.i("HongguoTV","Home content ready cached=$homeFromCache elapsedMs=${android.os.SystemClock.elapsedRealtime()-activityStarted}") }
        }
        if(launchStarted>0) {
            val started=launchStarted; launchStarted=0
            body.post { android.util.Log.i("HongguoTV","Home displayed cached=$homeFromCache elapsedMs=${android.os.SystemClock.elapsedRealtime()-started}") }
        }
    }

    internal fun MainActivity.refreshAllHome(focusNav:Boolean=false) {
        if(refreshHomePending) {
            if(focusNav) navByTab[0]?.requestFocus()
            return
        }
        apiStatusCheckStarted=false
        showCatalog(true,focusNav=focusNav)
        refreshHomePending=true
        homeScreen?.reloadArtwork()
        loadHomeShelf(if(library.contentType==ContentType.SHORT) ContentType.COMIC else ContentType.SHORT,true)
        loadHomeShelf(ContentType.AI,true)
        main.post { if(screen=="catalog" && tab==0) runAutomaticApiChecks() }
    }

    internal fun MainActivity.loadHomeShelf(type: ContentType,force: Boolean) {
        if(type!=ContentType.SHORT && type!=ContentType.COMIC && type!=ContentType.AI) return
        if(!force && !library.cachedHome(type)?.items.isNullOrEmpty()) return
        if(!homeShelfLoads.add(type)) { if(force) queuedHomeShelfRefresh.add(type); return }
        io.execute {
            val result=runCatching { if(type==ContentType.AI) repository.ranking(RankBoard.AI,1) else repository.home(1,type,null) }
            main.post {
                homeShelfLoads.remove(type)
                if(isDestroyed || resettingAllData) return@post
                val refreshAgain=queuedHomeShelfRefresh.remove(type)
                result.onSuccess { loaded ->
                    library.cacheHome(type,loaded.items,loaded.hasMore)
                    if(screen=="catalog" && tab==0 && collection==null && genre==null) {
                        homeScreen?.updateShelves(homeShelves(),requestFocus=false)
                    }
                }.onFailure { problem ->
                    android.util.Log.i("HongguoTV","Homepage shelf refresh failed for ${type.label}: ${problem.message}")
                }
                if(refreshAgain) loadHomeShelf(type,true)
            }
        }
    }

    internal fun MainActivity.homeShelves(): List<HomeScreen.Shelf> {
        val recent=library.history().filterNot { library.watched(it.series.id) }
        val updates=library.favorites().filter { library.favoriteUpdate(it.id)?.hasUpdate == true }
        val later=library.later()
        val hidden=library.hidden()
        if(activePlatform==ContentPlatform.HIPPO) {
            val items=(if(library.contentType==ContentType.SHORT) catalog else library.cachedHome(ContentType.SHORT)?.items.orEmpty()).filterNot { it.id in hidden }
            return listOf(
                HomeScreen.Shelf("resume","接着看",recent.take(8).map { p -> HomeScreen.Entry(p.series,
                    "第 ${p.episodeIndex+1} 集 · ${if(p.completed) "接着看下一集" else formatTime(p.position)}",true,
                    if(p.duration>0) (p.position*100/p.duration).toInt().coerceIn(0,100) else null) },
                    if(recent.size>8) { { showCollection("resume") } } else null),
                HomeScreen.Shelf("updates","收藏有更新",updates.take(8).map { HomeScreen.Entry(it,library.favoriteLabel(it.id),true) },
                    if(updates.size>8) { { showCollection("updates") } } else null),
                HomeScreen.Shelf("later","稍后看",later.take(10).map { HomeScreen.Entry(it,"稍后看 · 长按管理") },
                    if(later.size>10) { { showCollection("later") } } else null),
                HomeScreen.Shelf("hot-hippo","河马推荐",items.take(10).map { HomeScreen.Entry(it,it.badge.ifBlank { "查看剧集" }) })
            )
        }
        val shortItems=(if(library.contentType==ContentType.SHORT) catalog else library.cachedHome(ContentType.SHORT)?.items.orEmpty()).filterNot { it.id in hidden }
        val comicItems=(if(library.contentType==ContentType.COMIC) catalog else library.cachedHome(ContentType.COMIC)?.items.orEmpty()).filterNot { it.id in hidden }
        val aiItems=library.cachedHome(ContentType.AI)?.items.orEmpty().filterNot { it.id in hidden }
        return listOf(
            HomeScreen.Shelf("resume","接着看",recent.take(8).map { p -> HomeScreen.Entry(p.series,
                "第 ${p.episodeIndex+1} 集 · ${if(p.completed) "接着看下一集" else formatTime(p.position)}",true,
                if(p.duration>0) (p.position*100/p.duration).toInt().coerceIn(0,100) else null) },
                if(recent.size>8) { { showCollection("resume") } } else null),
            HomeScreen.Shelf("updates","收藏有更新",updates.take(8).map { HomeScreen.Entry(it,library.favoriteLabel(it.id),true) },
                if(updates.size>8) { { showCollection("updates") } } else null),
            HomeScreen.Shelf("later","稍后看",later.take(10).map { HomeScreen.Entry(it,"稍后看 · 长按管理") },
                if(later.size>10) { { showCollection("later") } } else null),
            HomeScreen.Shelf("hot-short","热门发现 · 短剧",shortItems.take(10).map { HomeScreen.Entry(it,it.badge.ifBlank { "查看剧集" }) },
                { showCollection("hot-short") }),
            HomeScreen.Shelf("hot-comic","热门发现 · 漫剧",comicItems.take(10).map { HomeScreen.Entry(it,it.badge.ifBlank { "查看剧集" }) },
                { showCollection("hot-comic") }),
            HomeScreen.Shelf("hot-ai","热门发现 · AI剧",aiItems.take(10).map { HomeScreen.Entry(it,it.badge.ifBlank { "查看剧集" }) },
                { showCollection("hot-ai") }))
    }

    internal fun MainActivity.refreshLibraryCards(changedId: String,removed: Boolean=false) {
        if(screen!="catalog") return
        val home=homeScreen
        if(home!=null) { home.updateShelves(homeShelves(),requestFocus=true); home.refreshSelection(); return }
        val adapter=catalogAdapter
        if(removed && (localCatalog() || tab==0) && adapter!=null) {
            val index=adapter.displayed.indexOfFirst { it.id==changedId }
            if(localCatalog()) catalog=localItems()
            if(index>=0) {
                adapter.displayed.removeAt(index)
                adapter.notifyItemRemoved(index)
                if(adapter.displayed.isEmpty()) { catalogFocus=""; showCatalog(); return }
                val next=minOf(index,adapter.displayed.size-1)
                catalogFocus=adapter.displayed[next].id
                val recycler=catalogRecycler
                recycler?.post { if(catalogRecycler===recycler) recycler.findViewHolderForAdapterPosition(next+if(tab==2 || tab==7) 1 else 0)?.itemView?.requestFocus() }
            }
        } else {
            catalog.firstOrNull { it.id==changedId }?.let { selectionPreview?.show(it,selectionStatus(it)) }
            favoriteBadges[changedId]?.text=library.favoriteLabel(changedId)
        }
    }
