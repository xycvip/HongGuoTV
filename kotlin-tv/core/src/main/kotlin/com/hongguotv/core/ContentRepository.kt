// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONObject
import org.json.JSONArray
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit

open class ContentRepository(val http: OkHttpClient = OkHttpClient.Builder().connectTimeout(15,TimeUnit.SECONDS).readTimeout(30,TimeUnit.SECONDS).callTimeout(45,TimeUnit.SECONDS).cookieJar(MemoryCookies()).build(), private val clock: () -> Long = System::currentTimeMillis, private val playbackSession: PlaybackDeviceSession = PlaybackDeviceSession.shared) {
    private val signer = Signer()
    private val site = "https://hongguoduanju.com"
    private val playbackRepository by lazy { PlaybackRepository(http,clock,playbackSession,signer,site) { url,signed -> text(url,signed) } }
    private fun categoryPath(type: ContentType)=when(type) {
        ContentType.COMIC -> "comic-drama"
        ContentType.SHORT -> "real-drama"
        ContentType.AI -> "ai-drama"
    }
    private fun text(url: String, signed: Signer.Request? = null): String {
        val request = Request.Builder().url(url).header("User-Agent",VendorConstants.VIDEO_UA).header("Accept-Language","zh-CN,zh;q=0.9")
        signed?.headers?.forEach { (k,v) -> request.header(k,v) }
        signed?.body?.let { request.post(it.toRequestBody("application/json; charset=utf-8".toMediaType())) }
        return RequestScope.execute(http.newCall(request.build())) { response ->
            if (!response.isSuccessful) throw IOException("内容请求失败（HTTP ${response.code}）")
            val body = response.body ?: throw IOException("内容响应为空")
            if (body.contentLength() > 16*1024*1024) throw IOException("内容响应过大")
            val input=body.byteStream(); val out=java.io.ByteArrayOutputStream(); val buffer=ByteArray(8192)
            while(out.size()<=16*1024*1024) { val n=input.read(buffer); if(n<0) break; out.write(buffer,0,n) }; val bytes=out.toByteArray()
            if(bytes.size > 16*1024*1024) throw IOException("内容响应过大")
            if(bytes.isEmpty()) throw IOException("接口响应为空（HTTP ${response.code}，响应字节数=0），请稍后重试")
            String(bytes,Charsets.UTF_8)
        }
    }
    private fun router(url: String) = extractRouter(text(url)).getJSONObject("loaderData")
    private fun card(item: JSONObject): Series {
        val o=item.optJSONObject("video_data") ?: item
        val count=o.optInt("episode_cnt",o.optJSONObject("series_episode_info")?.optInt("episode_cnt",0) ?: 0)
        val tags=o.optJSONArray("tags") ?: o.optJSONArray("category_list") ?: JSONArray()
        return Series(o.str("series_id"),o.str("series_name").ifEmpty { o.str("series_title").ifEmpty { item.str("name") } },cleanUrl(o.str("series_cover")),o.str("series_intro"),o.str("episode_right_text").ifEmpty { if(count>0) "全 ${count} 集" else "" },(0 until minOf(tags.length(),5)).joinToString(" · ") { tags.optJSONObject(it)?.str("name") ?: tags.optString(it) })
    }
    private fun valid(rows: List<Series>) = rows.filter { it.id.matches(Regex("[0-9]{1,30}")) && it.title.isNotBlank() }.distinctBy { it.id }
    open fun ranking(board: RankBoard, page: Int = 1): CatalogPage {
        require(page in 1..100)
        return ComicRank.parse(text("$site/rank/${board.path}?page=$page"),page,board)
    }
    fun comicRanking(page: Int = 1)=ranking(RankBoard.COMIC,page)
    fun realRanking(page: Int = 1)=ranking(RankBoard.REAL,page)
    private val theaterRepository by lazy { TheaterRepository(http,playbackSession) { url,signed -> text(url,signed) } }
    open fun theater(page:Int=1,type:ContentType?=null,selected:Map<String,String> = emptyMap()):TheaterCatalogPage =
        theaterRepository.theater(page,type,selected)
    /** Website genre picker remains separate from the App theater filter API. */
    open fun theaterFilters(type:ContentType):List<TheaterFilterGroup> = theater(1,type).filters
    /** Backward-compatible flat genre list used by the home-page picker. */
    open fun genres(type: ContentType): List<Genre> = searchRepository.genres(type)
    open fun home(page: Int = 1, type: ContentType = ContentType.SHORT, genre: Genre? = null): CatalogPage {
        require(page in 1..100)
        if(genre!=null) return searchRepository.categoryListing("$site/category/${categoryPath(type)}/${genre.slug}"+if(page>1) "?page=$page" else "",page,"题材",genre)
        if(type==ContentType.COMIC) return comicRanking(page)
        if(type==ContentType.AI) return searchRepository.categoryListing("$site/category/ai-drama"+if(page>1) "?page=$page" else "",page,"AI剧")
        return searchRepository.categoryListing("$site/category?tab=1&sort_type=1"+if(page>1) "&page=$page" else "",page,"首页")
    }
    private val searchRepository by lazy {
        SearchRepository(http,clock,playbackSession,signer,site,{ url,signed -> text(url,signed) },::router,::card,::valid,::categoryPath)
    }
    open fun search(keyword: String, page: Int = 1, type: ContentType = ContentType.SHORT): CatalogPage =
        searchRepository.search(keyword,page,type)
    open fun searchCombined(keyword: String, page: Int = 1): CatalogPage = searchRepository.searchCombined(keyword,page)
    open fun suggest(query: String): List<String> = searchRepository.suggest(query)
    open fun probeSuggest(query: String): Int = searchRepository.probeSuggest(query)

    private val detailRepository by lazy { DetailRepository(site,{ url -> text(url) },::card,::valid,searchRepository) }
    open fun detail(id: String): Detail = detailRepository.detail(id)
    open fun sameNameSeasons(current: Series, related: List<Series>): List<Series> =
        detailRepository.sameNameSeasons(current,related)

    /**
     * New official playback endpoint. It uses the same registered anonymous identity as the legacy
     * POST endpoint, but its request is a GET with the official GetVideoModelRequest fields in query.
     * Keep this first attempt deliberately single-shot: the existing POST path remains the bounded
     * compatibility/recovery path when GET is unavailable or returns an unusable model.
     */
    open fun stream(id: String, maxQuality: Int = 1080): StreamInfo = playbackRepository.stream(id,maxQuality)

    companion object {
        fun extractRouter(html: String): JSONObject {
            val marker=html.indexOf("_ROUTER_DATA"); if(marker<0) throw IOException("页面没有内容数据")
            val first=html.indexOf('{',marker); if(first<0) throw IOException("页面数据格式错误")
            var depth=0; var quoted=false; var escaped=false
            for(i in first until html.length) {
                val ch=html[i]
                if(quoted) { if(escaped) escaped=false else if(ch=='\\') escaped=true else if(ch=='"') quoted=false; continue }
                when(ch) { '"' -> quoted=true; '{','[' -> depth++; '}',']' -> { depth--; if(depth==0) return JSONObject(html.substring(first,i+1)) } }
            }
            throw IOException("页面数据不完整")
        }
        fun qualityNumber(value: String): Int = Regex("2160|1440|1080|720|576|540|480|360").find(value)?.value?.toInt() ?: mapOf("1920" to 1080,"1280" to 720,"1024" to 576,"854" to 480,"640" to 360)[value] ?: value.toIntOrNull() ?: 0
    }
}
