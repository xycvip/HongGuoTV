// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.view.*
import android.widget.*
import com.hongguotv.core.*

    internal fun MainActivity.selectionStatus(series: Series): String {
        val progress=library.progress(series.id)
        return listOf(if(library.favorite(series.id)) "已收藏" else "",if(library.queued(series.id)) "稍后看" else "",
            if(library.watched(series.id)) "整剧已看完" else progress?.let { "上次第 ${it.episodeIndex+1} 集 · ${formatTime(it.position)}" }.orEmpty()).filter { it.isNotBlank() }.joinToString(" · ")
    }

    internal fun MainActivity.quickActions(series: Series,anchor: View) {
        val grouped=library.favorite(series.id)
        val labels=mutableListOf(if(library.progress(series.id)!=null) "继续观看" else "直接播放",
            if(grouped) "取消收藏" else "收藏这部剧",
            if(library.queued(series.id)) "移出稍后看" else "加入稍后看")
        if(grouped) labels+="设置分组 · ${library.groupOf(series.id) ?: "未分组"}"
        labels+="查看剧集详情"
        if(tab==0) labels+="不在热门推荐中显示"
        if(tab==4 || collection=="resume") labels+="管理观看记录"
        tvTools.choose(series.title,labels,anchor,{ choice ->
            val detailIndex=if(grouped) 4 else 3
            when(choice) {
                0 -> openDetail(series,true)
                1 -> {
                    val added=library.toggle(series)
                    Toast.makeText(this,if(added) "已收藏" else "已取消收藏",Toast.LENGTH_SHORT).show()
                    refreshLibraryCards(series.id,!added && (tab==3 || collection=="updates"))
                    if(added) {
                        cloudAccount.syncLocalFavorites(library.favorites())
                        favoriteMonitor.check()
                    }
                }
                2 -> { val added=library.toggleLater(series); Toast.makeText(this,if(added) "已加入稍后看" else "已移出稍后看",Toast.LENGTH_SHORT).show(); refreshLibraryCards(series.id,!added && collection=="later") }
                3 -> if(grouped) showGroupPicker(series,anchor) else openDetail(series)
                else -> if(choice==detailIndex) openDetail(series) else if(tab==4 || collection=="resume") manageHistory(series,anchor) else { library.hide(series.id); Toast.makeText(this,"已隐藏热门推荐，可在设置中恢复",Toast.LENGTH_SHORT).show(); refreshLibraryCards(series.id,true) }
            }
        })
    }

    internal fun MainActivity.showGroupPicker(series: Series,anchor: View) {
        val options=listOf("未分组")+library.groups()+listOf("＋ 新建分组…")
        fun apply() { if(tab==3) showCatalog() else refreshLibraryCards(series.id) }
        tvTools.choose("选择分组 · ${series.title}",options,anchor,{ index ->
            when {
                index==0 -> { library.setGroup(series.id,null); apply() }
                index==options.lastIndex -> tvTools.phoneForm(anchor,PhoneForm(
                    heading="新建收藏分组",
                    description="输入分组名称后发送到电视，电视会立即创建该分组。",
                    fields=listOf(PhoneField("name","分组名称","最多 12 个字",12)),
                    doneHint="请回到电视查看新建的分组。",
                    invalidMessage="请输入 1—12 个字符的分组名称"
                )) { values ->
                    val name=values["name"].orEmpty()
                    if(!library.setGroup(series.id,name)) Toast.makeText(this,"分组数量已达上限或名称无效",Toast.LENGTH_SHORT).show()
                    apply()
                }
                else -> { library.setGroup(series.id,options[index]); apply() }
            }
        })
    }

    internal fun MainActivity.refreshFavoriteLabels() {
        if(screen!="catalog" || !library.isLoaded) return
        favoriteBadges.forEach { (id,label) -> val value=library.favoriteLabel(id); if(label.text.toString()!=value) label.text=value }
        favoriteStatus?.text=favoriteMonitor.status
        favoriteCheck?.text=if(favoriteMonitor.running) "正在检查…" else "检查更新"
        homeUpdates?.text="收藏更新 ${library.updatedFavorites()} 部"
        val home=homeScreen
        if(screen=="catalog" && tab==0 && page==1 && collection==null && home!=null && library.updatedFavorites()!=homeUpdateCount) {
            // A stable resume key can be restored without moving the user's selection.
            if(!tvTools.showing && !updater.showing && currentFocus?.tag?.toString()?.startsWith("resume:")==true) {
                homeUpdateCount=library.updatedFavorites(); home.updateShelves(homeShelves())
            }
        }
    }

internal fun MainActivity.localCatalog()=tab==3 || tab==4 || collection!=null
internal fun MainActivity.localItems(): List<Series> = when(collection) {
    "actor-works" -> actorWorksItems
    "hot" -> collectionReturn?.items.orEmpty().filterNot { it.id in library.hidden() }
    "hot-short" -> library.cachedHome(ContentType.SHORT)?.items.orEmpty().filterNot { it.id in library.hidden() }
    "hot-comic" -> library.cachedHome(ContentType.COMIC)?.items.orEmpty().filterNot { it.id in library.hidden() }
    "hot-ai" -> library.cachedHome(ContentType.AI)?.items.orEmpty().filterNot { it.id in library.hidden() }
    "later" -> library.later()
    "updates" -> library.favorites().filter { library.favoriteUpdate(it.id)?.hasUpdate == true }
    "resume" -> library.history().filterNot { library.watched(it.series.id) }.map { it.series }
    else -> if(tab==3) library.favorites().filter { favoriteGroupFilter==null || library.groupOf(it.id)==favoriteGroupFilter } else library.history().map { it.series }
}


internal fun MainActivity.showWatchStats(anchor: TextView) {
    val rows=library.history()
    if(rows.isEmpty()) {
        tvTools.info("观看统计 · 本机","还没有观看记录，播放几集后再来看。",anchor)
        return
    }
    val totalMs=rows.sumOf { it.position.coerceAtLeast(0) }
    val minutes=totalMs/60_000
    val finished=rows.count { it.completed }
    val tags=rows.flatMap { it.series.tags.split(" · ").map(String::trim).filter(String::isNotEmpty) }
        .groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(5)
    val body=buildString {
        append("累计观看 ${rows.size} 集，共 ${minutes} 分钟（约 ${minutes/60} 小时 ${minutes%60} 分钟）。\n")
        append("完整看完 $finished 集，涉及 ${rows.map { it.series.id }.distinct().size} 部剧。\n")
        if(tags.isNotEmpty()) {
            append("\n题材分布\n")
            tags.forEach { (tag,count) -> append("· $tag  $count 集\n") }
        }
        append("\n统计来自本机观看记录，不上传、不联网。")
    }
    tvTools.info("观看统计 · 本机",body,anchor)
}
