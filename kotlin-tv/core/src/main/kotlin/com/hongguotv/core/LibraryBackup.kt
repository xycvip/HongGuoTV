// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

object LibraryBackup {
    const val MAX_BYTES=2*1024*1024
    const val MAX_THIRD_HISTORY=100
    const val MAX_THIRD_FAVORITES=100
    private val idPattern=Regex("[0-9]{1,30}")
    private fun id(value: String)=value.also { require(idPattern.matches(it)) { "剧集标识无效" } }
    private fun cleanCover(value: String): String=runCatching {
        val uri=URI(value); val host=uri.host.orEmpty().lowercase()
        value.takeIf { it.length<=2048 && uri.scheme=="https" && uri.userInfo==null && host.isNotEmpty() && !host.contains(':') && !host.matches(Regex("[0-9.]+")) && host!="localhost" && !host.endsWith(".local") && !host.endsWith(".lan") && value.none(Char::isISOControl) }.orEmpty()
    }.getOrDefault("")
    private fun cleanText(value: String,max: Int)=value.map { if(it.isISOControl()) ' ' else it }.joinToString("").take(max)
    fun seriesJson(series: Series)=series.copy(title=cleanText(series.title,200),cover=cleanCover(series.cover),description="",badge=cleanText(series.badge,100),tags=cleanText(series.tags,200)).toJson()
    fun encode(data: BackupData): String {
        val groups=JSONObject()
        data.favoriteGroups.forEach { (key,name) -> val clean=cleanText(name,20); if(clean.isNotBlank()) groups.put(key,clean) }
        val result=JSONObject().put("format","hongguotv.library").put("version",1).put("createdAt",System.currentTimeMillis())
            .put("later",JSONArray(data.later.map(::seriesJson))).put("hidden",JSONArray(data.hidden.toList()))
            .put("favorites",JSONArray(data.favorites.map(::seriesJson))).put("history",JSONArray(data.history.map { it.json() }))
            .put("favoriteGroups",groups)
            .put("thirdSources",JSONArray(data.thirdSources.take(ThirdSourceConfig.MAX).map { source ->
                JSONObject().put("name",cleanText(source.name,40)).put("api",cleanText(source.api,500))
            }))
            .put("thirdHistory",JSONArray(data.thirdHistory.take(MAX_THIRD_HISTORY).map { hit -> JSONObject()
                .put("sourceApi",cleanText(hit.sourceApi,500)).put("sourceName",cleanText(hit.sourceName,40))
                .put("videoId",cleanText(hit.videoId,80)).put("title",cleanText(hit.title,200)).put("cover",cleanText(hit.cover,2048))
                .put("episodeIndex",hit.episodeIndex.coerceIn(0,100_000)).put("episodeLabel",cleanText(hit.episodeLabel,60))
                .put("positionMs",hit.positionMs.coerceAtLeast(0)).put("durationMs",hit.durationMs.coerceAtLeast(0))
                .put("updatedAt",hit.updatedAt.coerceAtLeast(0)).put("completed",hit.completed)
            }))
            .put("thirdFavorites",JSONArray(data.thirdFavorites.take(MAX_THIRD_FAVORITES).map { fav -> JSONObject()
                .put("sourceApi",cleanText(fav.sourceApi,500)).put("sourceName",cleanText(fav.sourceName,40))
                .put("videoId",cleanText(fav.videoId,80)).put("title",cleanText(fav.title,200)).put("cover",cleanText(fav.cover,2048))
                .put("episodeIndex",fav.episodeIndex.coerceIn(0,100_000)).put("episodeLabel",cleanText(fav.episodeLabel,60))
                .put("positionMs",fav.positionMs.coerceAtLeast(0)).put("updatedAt",fav.updatedAt.coerceAtLeast(0))
            }))
            .put("watched",JSONArray(data.watched.intersect((data.favorites.map { it.id }+data.history.map { it.series.id }).toSet()).take(500)))
            .put("searches",JSONArray(SearchHistory.encode(data.searches.map { it.copy(query=cleanText(it.query,80)) }))).put("settings",data.settings.json())
            .put("accountState", data.accountState?.takeIf { it.isNotBlank() }?.let { JSONObject(it) } ?: JSONObject.NULL)
            .put("playbackExtras", data.playbackExtras?.json() ?: JSONObject.NULL).toString()
        require(result.toByteArray(Charsets.UTF_8).size<=MAX_BYTES) { "备份超过 2 MB 限制" }
        return result
    }
    fun decode(value: String): BackupData = LibraryBackupDecoder.decode(value)
    /** Existing favorites stay first; only newer imported progress replaces a local record. */
    fun merge(local: BackupData,incoming: BackupData,restoreSettings: Boolean): BackupData {
        val favorites=(local.favorites+incoming.favorites).distinctBy { it.id }.take(500)
        val records=local.history.associateBy { it.series.id }.toMutableMap()
        val watched=local.watched.toMutableSet()
        incoming.history.forEach { candidate ->
            val id=candidate.series.id; val prior=records[id]
            if(prior==null || candidate.updatedAt>prior.updatedAt) {
                records[id]=candidate
                if(id in incoming.watched) watched.add(id) else watched.remove(id)
            }
        }
        incoming.watched.filter { id -> id !in records && local.favorites.none { it.id==id } }.forEach { watched.add(it) }
        val history=records.values.sortedByDescending { it.updatedAt }.take(200)
        val retained=(favorites.map { it.id }+history.map { it.series.id }).toSet()
        val favoriteIds=favorites.map { it.id }.toSet()
        val favoriteGroups=(local.favoriteGroups+incoming.favoriteGroups).filterKeys { it in favoriteIds }
        // 三方源：本地优先，只补充备份里新增的接口，同接口不覆盖
        val thirdSources=(local.thirdSources+incoming.thirdSources).distinctBy { it.api }.take(ThirdSourceConfig.MAX)
        // 三方观看历史：同「源接口 + 影片」取进度较新的一条，按时间来排序并截断
        val thirdHistory=(local.thirdHistory+incoming.thirdHistory)
            .groupBy { it.sourceApi to it.videoId }
            .mapNotNull { (_,rows) -> rows.maxByOrNull { it.updatedAt } }
            .sortedByDescending { it.updatedAt }.take(MAX_THIRD_HISTORY)
        // 三方收藏：同「源接口 + 影片」同样保留较新的一条
        val thirdFavorites=(local.thirdFavorites+incoming.thirdFavorites)
            .groupBy { it.sourceApi to it.videoId }
            .mapNotNull { (_,rows) -> rows.maxByOrNull { it.updatedAt } }
            .sortedByDescending { it.updatedAt }.take(MAX_THIRD_FAVORITES)
        return BackupData(favorites,history,watched.intersect(retained).take(500).toSet(),(local.searches+incoming.searches).distinct().take(20),if(restoreSettings) incoming.settings else local.settings,(local.later+incoming.later).distinctBy { it.id }.take(100),(local.hidden+incoming.hidden).take(500).toSet(),favoriteGroups,thirdSources,thirdHistory,thirdFavorites,incoming.accountState ?: local.accountState,if(restoreSettings) incoming.playbackExtras ?: local.playbackExtras else local.playbackExtras)
    }
}
