// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/** Search, genre browsing, suggestion caching and multi-season completion. */
internal class SearchRepository(
    private val http: OkHttpClient,
    private val clock: () -> Long,
    private val playbackSession: PlaybackDeviceSession,
    private val signer: Signer,
    private val site: String,
    private val requestText: (String, Signer.Request?) -> String,
    private val router: (String) -> JSONObject,
    private val card: (JSONObject) -> Series,
    private val valid: (List<Series>) -> List<Series>,
    private val categoryPath: (ContentType) -> String
) {
private data class SearchCursor(val searchId: String, val passback: String, val offset: Int?, val updatedAt: Long)
    private val searchCursors=linkedMapOf<Triple<ContentType,String,Int>,SearchCursor>()
    private val genreCache=mutableMapOf<ContentType,Pair<Long,List<Genre>>>()
    private val suggestCache=linkedMapOf<String,Pair<Long,List<String>>>()
    // 签名搜索接口被平台关停时会稳定返回空体，短期内不再重复尝试，避免每次搜索都白等两次往返
    @Volatile private var appSearchRetryAt=0L
    private data class SeasonFill(val at: Long, val items: List<Series>)
    private val seasonFills=mutableMapOf<String, SeasonFill>()

    private fun websiteGenres(type:ContentType):List<Genre> {
        val data=router("$site/category/${categoryPath(type)}")
        val section=data.optJSONObject("category_page") ?: data.optJSONObject("category_$") ?: throw IOException("题材暂不可用")
        val rows=section.optJSONArray("selectorList") ?: throw IOException("题材暂不可用")
        return rows.objects().flatMap { row ->
            row.optJSONArray("items")?.objects().orEmpty().mapNotNull { item ->
                val ids=item.optJSONArray("category_json_ids") ?: return@mapNotNull null
                val list=(0 until ids.length()).map { ids.optString(it) }.filter { it.isNotBlank() }
                val slug=item.str("selector_item_id"); val name=item.str("show_name")
                if(slug.isBlank() || name.isBlank() || list.isEmpty()) null else Genre(slug,name,list)
            }
        }.distinctBy { it.slug }
    }

    fun genres(type: ContentType): List<Genre> {
        synchronized(genreCache) { genreCache[type]?.let { if(clock()-it.first<600_000) return it.second } }
        return websiteGenres(type).also { list -> synchronized(genreCache) { genreCache[type]=clock() to list } }
    }

    internal fun categoryListing(url: String,page: Int,label: String,genre: Genre? = null): CatalogPage {
        val data=router(url)
        val section=data.optJSONObject("category_page") ?: data.optJSONObject("category_$") ?: throw IOException("${label}数据结构已变化")
        if(genre!=null) {
            // The site silently ignores filters it does not recognize; only the echoed ids prove the genre applied.
            val applied=section.optJSONObject("apiQuery")?.optJSONArray("category_json_ids")
            val matched=applied!=null && genre.ids.any { id -> (0 until applied.length()).any { applied.optString(it)==id } }
            if(!matched) throw IOException("题材筛选暂不可用，请稍后重试")
        }
        val rows=section.optJSONArray("recommendList") ?: section.optJSONObject("categoryData")?.optJSONArray("recommendList") ?: throw IOException("${label}数据暂不可用")
        val items=valid(rows.objects().map(card))
        val lastPage=section.optJSONObject("pagination")?.optInt("totalPages",100) ?: 100
        return CatalogPage(items,items.isNotEmpty() && page<minOf(lastPage,100))
    }
    fun search(keyword: String, page: Int = 1, type: ContentType = ContentType.SHORT): CatalogPage {
        require(keyword.isNotBlank() && keyword.length<=80 && page in 1..100)
        // AI 剧没有独立搜索口径，官网把它归在漫剧搜索结果里
        if(type==ContentType.AI) return search(keyword,page,ContentType.COMIC)
        if(type==ContentType.COMIC) {
            try { return fillSeasons(appSearch(keyword,page,type),page,type,keyword) }
            catch(e: Exception) { /* App search API rejected the request or the cursor expired; the website paginates independently. */ }
            return fillSeasons(webSearch(keyword,page,COMIC_WEB_GENRE),page,type,keyword)
        }
        var appError: Exception? = null
        try { val result=appSearch(keyword,page,type); if(result.items.isNotEmpty() || !result.hasMore) return fillSeasons(result,page,type,keyword) } catch (e: Exception) { if(e is SearchSessionExpiredException) throw e; appError=e }
        return try { fillSeasons(webSearch(keyword,page,SHORT_WEB_GENRE),page,type,keyword) }
        catch(e: Exception) { throw IOException("搜索暂不可用（App接口：${appError?.message ?: "无结果"}；网站：${e.message}）",e) }
    }
    /**
     * Search comics and short dramas together. Results are globally ranked by title similarity;
     * each card keeps its content-type label so users can distinguish the two catalogs.
     * A temporary failure in one catalog does not hide successful results from the other.
     */
    fun searchCombined(keyword: String, page: Int = 1): CatalogPage {
        require(keyword.isNotBlank() && keyword.length<=80 && page in 1..100)

        // 准确度优先：漫剧、短剧各自最多取前 3 页；两路都完成后再统一去重、评分和排序。
        // 单一路线后续分页失败时保留已拿到的结果，避免丢掉整路首屏数据。
        fun fetchPages(type: ContentType): List<CatalogPage> {
            val pages=mutableListOf<CatalogPage>()
            for(nextPage in 1..3) {
                val result=try { search(keyword,nextPage,type) } catch(problem: Exception) {
                    if(pages.isEmpty()) throw problem else break
                }
                pages+=result
                if(!result.hasMore) break
            }
            return pages
        }
        val pool=Executors.newFixedThreadPool(2)
        val comicFuture=pool.submit(Callable { runCatching { fetchPages(ContentType.COMIC) } })
        val shortFuture=pool.submit(Callable { runCatching { fetchPages(ContentType.SHORT) } })
        val comicResult: Result<List<CatalogPage>>
        val shortResult: Result<List<CatalogPage>>
        try {
            comicResult=comicFuture.get()
            shortResult=shortFuture.get()
        } finally {
            pool.shutdownNow()
        }
        if(comicResult.exceptionOrNull() is SearchSessionExpiredException || shortResult.exceptionOrNull() is SearchSessionExpiredException) {
            throw SearchSessionExpiredException()
        }
        val comic=comicResult.getOrNull()
        val short=shortResult.getOrNull()
        if(comic==null && short==null) {
            throw IOException("漫剧和短剧搜索均暂不可用（漫剧：${comicResult.exceptionOrNull()?.message ?: "未知错误"}；短剧：${shortResult.exceptionOrNull()?.message ?: "未知错误"}）")
        }
        val candidates=buildList {
            comic.orEmpty().flatMap { it.items }.forEach { add(ContentType.COMIC to it) }
            short.orEmpty().flatMap { it.items }.forEach { add(ContentType.SHORT to it) }
        }
        fun normalizeTitle(value: String)=value.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }
        val ranked=candidates.withIndex()
            .sortedWith(Comparator { left,right ->
                val leftItem=left.value
                val rightItem=right.value
                val score=titleMatchScore(rightItem.second.title,keyword).compareTo(titleMatchScore(leftItem.second.title,keyword))
                if(score!=0) score
                else {
                    val titleOrder=normalizeTitle(leftItem.second.title).compareTo(normalizeTitle(rightItem.second.title))
                    if(titleOrder!=0) titleOrder
                    else if(leftItem.first!=rightItem.first) {
                        if(leftItem.first==ContentType.SHORT) -1 else 1
                    } else left.index.compareTo(right.index)
                }
            })
        val seenIds=mutableSetOf<String>()
        val seenTitles=mutableSetOf<String>()
        val unique=ranked.map { it.value }.filter { (type,series) ->
            val title=normalizeTitle(series.title)
            val newId=seenIds.add(series.id)
            val newTitle=if(title.isBlank()) true else seenTitles.add(title)
            newId && newTitle
        }.map { (type,series) ->
            val detail=series.badge.trim()
            series.copy(badge=if(detail.isBlank()) type.label else "${type.label} · ${detail}")
        }
        return CatalogPage(unique,false)
    }

    // 站点搜索页已改为 SPA，数据由 /api/search/result/ 下发（返回 searchList/totalPages/hasMore，10 条一页）。
    private fun webSearch(keyword: String, page: Int, genre: String?): CatalogPage {
        val url="$site/api/search/result/?query=${Signer.encode(keyword)}&page=$page"
        // 该接口偶发 502，重试一次再判定失败，避免搜索页直接报错
        val body=runCatching { requestText(url,null) }.getOrElse { first ->
            Thread.sleep(300)
            runCatching { requestText(url,null) }.getOrElse { throw first }
        }
        val data=runCatching { JSONObject(body) }.getOrElse { throw IOException("搜索数据暂不可用") }
        val rows=data.optJSONArray("searchList") ?: throw IOException("搜索数据结构已变化")
        val mapped=rows.objects().let { list -> if(genre==null) list else list.filter { (it.optJSONObject("video_data") ?: it).optString("genre")==genre } }.map(card)
        // 站点把漫剧/短剧混在同一结果里，按 genre 过滤后单页条数会减少；分页仍以站点页数为准。
        val lastPage=data.optInt("totalPages",page)
        return CatalogPage(valid(mapped),data.optBoolean("hasMore",false) && page<minOf(lastPage,100))
    }
    /** 网站搜索固定只返回前 10 条，多季剧必然缺季；联想接口未被拦截，用同名季的精确标题回查网站补齐。 */
    internal fun collectSeasons(base: String, known: List<Series>, genre: String?, hints: List<String>, cacheKey: String, budget: Int = 10): List<Series> {
        val cached=synchronized(seasonFills) { seasonFills[cacheKey]?.takeIf { clock()-it.at<86_400_000 }?.items }
        if(cached!=null) return cached
        val knownIds=known.map { it.id }.toSet()
        val found=mutableListOf<Series>()
        fun absorb(page: CatalogPage?) {
            page?.items?.filter { it.id !in knownIds && baseName(it.title)==base && found.none { s -> s.id==it.id } }?.let { found+=it }
        }
        // 基底整词、基底前 4 字与联想互不依赖，并发取回，避免串行等待
        val queries=hints.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        // 线程数保持与改造前一致，仅把串行的联想查询改为并发，不额外放大站点并发请求
        val inheritedScope = RequestScope.currentScope()
        fun <T> inRequestScope(action: () -> T): T =
            if (inheritedScope == null) action() else inheritedScope.run(action)
        val pool=Executors.newFixedThreadPool(4)
        try {
            val pages=listOf(base,base.take(4)).distinct().map { query -> pool.submit(Callable { inRequestScope { runCatching { webSearch(query,1,genre) }.getOrNull() } }) }
            // 联想查询彼此独立，逐个并发提交，避免串行等待多轮往返
            val suggestions=queries.map { query -> pool.submit(Callable { inRequestScope { runCatching { suggestRaw(query) }.getOrDefault(emptyList()) } }) }
            pages.forEach { absorb(it.get()) }
            // 多个 query 的联想结果互补，能覆盖更多季（联想每次返回的子集并不稳定）
            val knownTitles=(known.map { it.title }+found.map { it.title }).map { it.trim() }.toSet()
            val names=suggestions.flatMap { runCatching { it.get() }.getOrDefault(emptyList()) }.map { it.trim() }
                .filter { it.isNotBlank() && baseName(it)==base && it !in knownTitles }.distinct()
            // 逐季回查同样互不依赖，3 路并发把最耗时的这段压到原来的三分之一
            val lookups=names.map { name ->
                val season=seasonNumber(name)
                pool.submit(Callable {
                    inRequestScope {
                        runCatching { webSearch(name,1,genre) }.getOrNull()
                            ?.items?.firstOrNull { it.id !in knownIds && baseName(it.title)==base && seasonNumber(it.title)==season }
                    }
                })
            }
            for(task in lookups) {
                if(found.size>=budget) break
                val match=runCatching { task.get() }.getOrNull()
                if(match!=null && found.none { it.id==match.id }) found+=match
            }
        } finally {
            pool.shutdownNow()
        }
        val final=found.take(budget)
        synchronized(seasonFills) {
            seasonFills[cacheKey]=SeasonFill(clock(),final)
            while(seasonFills.size>50) seasonFills.remove(seasonFills.keys.first())
        }
        return final
    }
    private fun fillSeasons(page: CatalogPage, pageNumber: Int, type: ContentType, keyword: String): CatalogPage {
        if(pageNumber!=1 || page.items.size<2) return page
        val groups=page.items.groupBy { baseName(it.title) }.filter { (base,rows) ->
            base.isNotBlank() && (rows.size>1 || rows.any { seasonNumber(it.title)!=Int.MAX_VALUE })
        }
        if(groups.isEmpty()) return page
        val additions=mutableMapOf<String,List<Series>>()
        var budget=10
        for((base,rows) in groups.entries.sortedByDescending { it.value.size }.take(2)) {
            if(budget<=0) break
            val genre=if(type==ContentType.COMIC) COMIC_WEB_GENRE else SHORT_WEB_GENRE
            // 基底词、用户输入、基底前缀各取一次联想，多个 query 的结果互补
            val extra=collectSeasons(base,page.items,genre,listOf(base,keyword,base.take(4)),"$type|$base",budget)
            budget-=extra.size
            additions[base]=extra
        }
        if(additions.values.all { it.isEmpty() }) return page
        // 原位合并：同一基底按季号排列，其余条目保持原有相关性顺序
        val output=mutableListOf<Series>()
        val placed=mutableSetOf<String>()
        for(item in page.items) {
            val base=baseName(item.title)
            if(base in additions) {
                if(!placed.add(base)) continue
                val group=(page.items.filter { baseName(it.title)==base }+additions.getValue(base)).distinctBy { it.id }
                output+=group.sortedBy { seasonNumber(it.title).let { n -> if(n==Int.MAX_VALUE) 1 else n } }
            } else output+=item
        }
        return page.copy(items=output)
    }
    /** App 搜索/联想接口和播放接口一样按注册设备放行：未注册的随机身份只会拿到 HTTP 200 空体。 */
    private fun searchValues(): MutableMap<String,String> {
        val values=VendorConstants.device.toMutableMap()
        for(k in listOf("version_code","manifest_version_code","update_version_code","pv_player")) values[k]="72232"
        values["version_name"]="7.2.2.32"
        runCatching { playbackSession.profile(http) }.getOrNull()?.values?.let { identity ->
            for(k in listOf("device_id","iid","cdid")) identity[k]?.let { values[k]=it }
        }
        return values
    }
    private fun suggestRaw(query: String,bypassCache: Boolean = false,strict: Boolean = false): List<String> {
        if(!bypassCache) synchronized(suggestCache) { suggestCache[query]?.takeIf { clock()-it.first<300_000 }?.let { return it.second } }
        val result=runCatching {
            if(query.isBlank() || query.length>40) throw IOException("联想关键词无效")
            VendorConstants.regenerate()
            val values=searchValues()
            values["query"]=query
            val signed=signer.sign("https://api5-normal-sinfonlinea.fqnovel.com/reading/bookapi/search/suggest/v",values)
            val data=JSONObject(requestText(signed.url,signed))
            if(data.optInt("code",-1)!=0) throw IOException("联想接口返回异常（code=" + data.optInt("code",-1) + "）")
            val rows=data.optJSONObject("data")?.optJSONArray("query_result")
                ?: throw IOException("联想接口响应格式异常")
            (0 until rows.length()).map { rows.optString(it).trim() }.filter { it.isNotEmpty() }.distinct().take(20)
        }.getOrElse { problem -> if(strict) throw problem else emptyList() }
        if(!bypassCache && result.isNotEmpty()) synchronized(suggestCache) {
            suggestCache[query]=clock() to result
            while(suggestCache.size>200) suggestCache.remove(suggestCache.keys.first())
        }
        return result
    }
    private fun appSearch(keyword: String, page: Int, type: ContentType): CatalogPage {
        if(clock()<appSearchRetryAt) throw IOException("搜索接口暂不可用")
        val tabType=type.searchTab ?: throw IOException("${type.label}暂不支持搜索")
        VendorConstants.regenerate()
        val values=searchValues()
        values.putAll(linkedMapOf("query" to keyword,"count" to "20","offset" to ((page-1)*20).toString(),"tab_type" to tabType.toString(),"bookshelf_search_plan" to "4","use_correct" to "true","user_is_login" to "0"))
        val cursor=synchronized(searchCursors) {
            val now=clock()
            searchCursors.entries.removeAll { now-it.value.updatedAt>300000 || (page==1 && it.key.first==type && it.key.second==keyword) }
            searchCursors[Triple(type,keyword,page-1)]
        }
        if(type==ContentType.COMIC && page>1 && cursor==null) throw SearchSessionExpiredException()
        cursor?.let {
            if(it.searchId.isNotBlank()) values["search_id"]=it.searchId
            if(it.passback.isNotBlank()) values["passback"]=it.passback
            it.offset?.takeIf { offset -> offset>=0 }?.let { offset -> values["offset"]=offset.toString() }
        }
        // 官方 APK 逆向确认主网关为 reading.snssdk.com；签名只覆盖参数与主机无关，被网关拦截时换主机兜底
        var data: JSONObject? = null
        for(host in listOf("https://api5-normal-sinfonlinea.fqnovel.com","https://reading.snssdk.com")) {
            val signed=signer.sign("$host/reading/bookapi/search/tab/v",values)
            val candidate=runCatching { JSONObject(requestText(signed.url,signed)) }.getOrNull() ?: continue
            if(candidate.optInt("code",-1)==0) { data=candidate; break }
        }
        if(data==null) {
            appSearchRetryAt=clock()+300_000
            throw IOException("搜索接口暂不可用")
        }
        val tabs=data.optJSONArray("search_tabs") ?: data.optJSONObject("data")?.optJSONArray("search_tabs") ?: throw IOException("搜索格式异常")
        val tab=tabs.objects().firstOrNull { it.optInt("tab_type")==type.searchTab } ?: throw IOException("${type.label}搜索暂不可用")
        val list=mutableListOf<Series>()
        for(cell in (tab.optJSONArray("data") ?: JSONArray()).objects()) {
            val rows=mutableListOf(cell)
            runCatching { JSONObject(cell.str("lynx_data")).optJSONArray("cell_data")?.objects() }.getOrNull()?.let { rows.addAll(it) }
            for(row in rows) {
                val video=row.optJSONArray("video_data")?.objects()?.firstOrNull() ?: continue
                val id=video.str("series_id").ifEmpty { row.str("book_id").ifEmpty { row.str("search_result_id") } }
                val title=row.optJSONObject("search_high_light")?.optJSONObject("title")?.str("text").orEmpty().ifEmpty { video.str("title").ifEmpty { row.str("cell_name") } }
                val count=video.optInt("episode_cnt")
                list+=Series(id,title,cleanUrl(video.str("cover").ifEmpty { video.str("cover_url") }),video.str("video_desc"),if(count>0) "全 $count 集" else video.str("rec_text"),video.str("sub_title"))
            }
        }
        synchronized(searchCursors) {
            searchCursors[Triple(type,keyword,page)]=SearchCursor(tab.str("search_id"),tab.str("passback"),tab.str("next_offset").toIntOrNull(),clock())
            while(searchCursors.size>100) searchCursors.remove(searchCursors.keys.first())
        }
        return CatalogPage(valid(list),tab.optBoolean("has_more",false) && page<100)
    }
    /** Pinyin-initial suggestions from the signed app API; every failure returns an empty list so the TV keyboard keeps working. */
    fun suggest(query: String): List<String> {
        val key=query.trim().lowercase()
        if(key.isEmpty() || key.length>20 || !key.matches(Regex("[a-z0-9]+"))) return emptyList()
        return suggestRaw(key)
    }
    /** 接口健康检查专用：绕过联想缓存，要求收到合法的官方接口响应。 */
    fun probeSuggest(query: String): Int {
        val key=query.trim().lowercase()
        if(key.isEmpty() || key.length>20 || !key.matches(Regex("[a-z0-9]+"))) throw IOException("联想检测关键词无效")
        return suggestRaw(key,bypassCache=true,strict=true).size
    }
    private companion object {
        // 网站搜索混合两类内容：video_data.genre 205 是漫剧，203 是短剧。
        const val COMIC_WEB_GENRE="205"
        const val SHORT_WEB_GENRE="203"
    }
}
