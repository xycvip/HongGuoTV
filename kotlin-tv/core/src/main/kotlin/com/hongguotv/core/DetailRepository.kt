// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.Locale

/** Detail-page parsing, social metadata and same-name season expansion. */
internal class DetailRepository(
    private val site: String,
    private val requestText: (String) -> String,
    private val card: (JSONObject) -> Series,
    private val valid: (List<Series>) -> List<Series>,
    private val searchRepository: SearchRepository
) {
    fun detail(id: String): Detail {
        require(id.matches(Regex("[0-9]{1,30}")))
        val html=requestText("$site/detail?series_id=$id")
        val loader=ContentRepository.extractRouter(html).getJSONObject("loaderData")
        val o=loader.optJSONObject("detail_page")?.optJSONObject("seriesDetail") ?: throw IOException("剧集详情暂不可用")
        val raw=o.optJSONArray("vid_list") ?: throw IOException("分集暂不可用")
        val episodes=(0 until raw.length()).map { raw.getString(it) }
        if(episodes.isEmpty() || episodes.any { !it.matches(Regex("[0-9]{1,30}")) } || episodes.distinct().size!=episodes.size) throw IOException("分集数据异常")
        val series=card(o).copy(id=id)
        val related=related(html,series)
        val social=social(loader.optJSONObject("detail_page")?.optJSONObject("seriesSocialInfo"))
        return Detail(series,episodes,related,emptyList(),social)
    }
    // 评分与收藏数只在详情页 SSR 里下发，卡片数据源没有这些字段，因此只读取不追加请求。
    private fun social(o: JSONObject?): SeriesSocial {
        if(o==null) return SeriesSocial()
        val favorite=compactCount(o.str("series_favorite_count"))
        return SeriesSocial(
            rating=o.optDouble("rating",0.0).takeIf { it>0 } ?: 0.0,
            ratingCount=compactCount(o.str("rating_count")),
            favoriteText=if(favorite.isBlank()) "" else "${favorite}收藏",
            heatText=o.optJSONObject("hot_score_data")?.str("text").orEmpty()
        )
    }
    private fun compactCount(raw: String): String {
        val value=raw.trim().toLongOrNull() ?: return ""
        if(value<1) return ""
        fun trim(number: Double, unit: String)=String.format(Locale.CHINA,"%.1f",number).removeSuffix(".0")+unit
        return when {
            value>=100_000_000 -> trim(value/100_000_000.0,"亿")
            value>=10_000 -> trim(value/10_000.0,"万")
            else -> value.toString()
        }
    }
    // The detail page has no dedicated same-name group, so other seasons are collected from its related
    // payload and filled with the same website/suggestion pipeline used by search.
    private fun sameName(current: Series, related: List<Series>): List<Series> {
        val base=baseName(current.title); if(base.isBlank()) return emptyList()
        val siblings=related.filter { it.id!=current.id && baseName(it.title)==base }.distinctBy { it.id }
        val extra=searchRepository.collectSeasons(base,listOf(current)+siblings,null,listOf(base,base.take(4)),"detail|$base",20)
        return (siblings+extra).distinctBy { it.id }.filter { it.id!=current.id }
            .sortedBy { seasonNumber(it.title).let { n -> if(n==Int.MAX_VALUE) 1 else n } }.take(20)
    }
    /** Called by the UI after the basic detail page is already visible. */
    fun sameNameSeasons(current: Series, related: List<Series>): List<Series> =
        sameName(current,related)

    // The detail page embeds its recommendation payload as an HTML-escaped JSON array inside the
    // `data-fn-args` attribute, separate from _ROUTER_DATA. Same-name entries (other seasons) sort first.
    private fun related(html: String,current: Series): List<Series> {
        val marker="data-fn-args=\""
        val start=html.indexOf(marker); if(start<0) return emptyList()
        val from=start+marker.length; val end=html.indexOf('"',from); if(end<0) return emptyList()
        val args=runCatching {
            JSONArray(decodeEntities(html.substring(from,end)))
                .optJSONArray(1)?.optJSONObject(0)?.optJSONArray("routerDataFnArgs")?.optString(0).orEmpty()
        }.getOrNull() ?: return emptyList()
        if(args.isEmpty()) return emptyList()
        val groups=runCatching { JSONObject(args).optJSONObject("recommendationGroups") }.getOrNull() ?: return emptyList()
        val rows=groups.optJSONArray("related_list") ?: groups.optJSONArray("hot_list") ?: return emptyList()
        val items=valid(rows.objects().map(card)).filter { it.id!=current.id }
        val key=current.title.trim()
        if(key.isEmpty()) return items
        return items.sortedBy { item ->
            when {
                item.title.equals(key,ignoreCase=true) -> 0
                item.title.startsWith(key) || key.startsWith(item.title) -> 1
                else -> 2
            }
        }
    }
    private fun decodeEntities(value: String) = value.replace("&quot;","\"").replace("&lt;","<").replace("&gt;",">").replace("&#39;","'").replace("&amp;","&")
}
