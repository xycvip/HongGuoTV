// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/** Official App theater/filter API and its cursor/session state, isolated from the website catalog. */
internal class TheaterRepository(
    private val http: OkHttpClient,
    private val playbackSession: PlaybackDeviceSession,
    private val requestText: (String, Signer.Request?) -> String
) {
    private data class TheaterCursor(val offset:Int,val sessionId:String)
    private data class TheaterSession(var filters:List<TheaterFilterGroup>,val cursors:MutableMap<Int,TheaterCursor>,val shown:LinkedHashSet<String>)
    private val theaterSessions=linkedMapOf<String,TheaterSession>()
    private val theaterLog=java.util.logging.Logger.getLogger("com.hongguotv.core.theater")
    private fun theaterKey(type:ContentType?,selected:Map<String,String>) =
        (type?.name ?: "ALL")+"|"+selected.toSortedMap().entries.joinToString("|") { "${it.key}=${it.value}" }
    private fun theaterScene(type:ContentType?)=when(type) {
        ContentType.COMIC -> "comic_series"
        ContentType.AI -> "ai_series"
        else -> "default"
    }
    private fun theaterGenre(type:ContentType?)=when(type) {
        ContentType.SHORT -> "short_play"
        ContentType.COMIC -> "comic_series"
        ContentType.AI -> "ai_series"
        else -> ""
    }
    private fun theaterValues(profile:PlaybackDeviceSession.Profile):MutableMap<String,String> {
        // Unlike web SSR, the App catalog endpoint requires a server-registered anonymous device.
        val values=profile.values.toMutableMap()
        for(k in listOf("version_code","manifest_version_code","update_version_code","pv_player")) values[k]="73532"
        values["version_name"]="7.3.5.32"
        return values
    }
    /** Official App discovery/filter API. The website's SSR selectorList is deliberately not used here. */
    fun theater(page:Int=1,type:ContentType?=null,selected:Map<String,String> = emptyMap()):TheaterCatalogPage {
        require(page in 1..100)
        val allowed=setOf("category_dim_theme","category_dim_role","category_dim_epoch","sort","gender","online_time","creation_status")
        if(selected.keys.any { it !in allowed } || selected.values.any { it.isBlank() }) throw IOException("剧场筛选条件无效")
        val key=theaterKey(type,selected)
        val session=synchronized(theaterSessions) {
            if(page==1) {
                TheaterSession(emptyList(),mutableMapOf(1 to TheaterCursor(0,"")),linkedSetOf()).also {
                    theaterSessions[key]=it
                    while(theaterSessions.size>24) theaterSessions.remove(theaterSessions.keys.first())
                }
            } else theaterSessions[key] ?: throw IOException("剧场分页已失效，请重新加载")
        }
        val cursor=synchronized(theaterSessions) { session.cursors[page] } ?: throw IOException("剧场分页游标缺失，请重新加载")
        val select=JSONObject().apply {
            put("genre",JSONArray().apply { theaterGenre(type).takeIf { it.isNotBlank() }?.let { put(it) } })
            put("sort",JSONArray().apply { selected["sort"]?.let { put(it) } })
            for(field in listOf("category_dim_theme","category_dim_role","category_dim_epoch","gender","online_time","creation_status")) {
                put(field,JSONArray().apply { selected[field]?.let { put(it) } })
            }
        }
        val body=JSONObject().apply {
            put("filter_ids",synchronized(theaterSessions) { session.shown.joinToString(",") })
            put("req_scene",theaterScene(type))
            put("offset",cursor.offset)
            put("need_selector_panel",page==1)
            put("limit",15)
            put("select_items",select)
            put("session_id",cursor.sessionId)
            put("req_type",if(page==1) "default" else "only_content")
            put("client_req_type",3)
        }.toString().toByteArray(Charsets.UTF_8)
        val endpoint="/reading/distribution/category/landpage/v/"
        val hosts=listOf(
            "api5-normal-sinfonlinea.fqnovel.com",
            "api5-normal-sinfonlinec.fqnovel.com",
            "api5-normal-sinfonlineb.fqnovel.com",
            "api5-normal-lf.fqnovel.com",
            "api5-normal-lq.fqnovel.com",
            "api5-normal-hl.fqnovel.com",
        )
        var profile=playbackSession.profile(http)
        var payload:JSONObject?=null
        var lastError:IOException?=null
        var sawEmpty=false
        var sawIdentityFailure=false
        // Try all official hosts first. If they consistently return an empty body or reject the
        // registered identity, refresh that anonymous identity exactly once and repeat the host set.
        for(round in 0..1) {
            val values=theaterValues(profile)
            val requestSigner=Signer(values)
            for(host in hosts) {
                val signedBase=requestSigner.sign("https://$host$endpoint",values,body)
                val signed=if(profile.cookie.isBlank()) signedBase else signedBase.copy(headers=signedBase.headers + ("Cookie" to profile.cookie))
                try {
                    val raw=requestText(signed.url,signed)
                    val candidate=JSONObject(raw)
                    val code=candidate.optInt("code",-1)
                    if(code==0) {
                        theaterLog.info("剧场接口命中域名 $host；轮次=${round+1}；响应字符数=${raw.length}；code=0")
                        payload=candidate
                        break
                    }
                    lastError=IOException("剧场接口 HTTP 200，响应字符数=${raw.length}，code=$code")
                    theaterLog.warning("剧场接口域名 $host 返回业务错误：${lastError.message}")
                } catch(error:java.io.InterruptedIOException) {
                    throw error
                } catch(error:Exception) {
                    val failure=if(error is IOException) error else IOException("剧场响应解析失败：${error.message}",error)
                    lastError=failure
                    if(failure.message.orEmpty().contains("响应为空")) sawEmpty=true
                    if(Regex("""HTTP\s+(401|403)\b""").containsMatchIn(failure.message.orEmpty())) sawIdentityFailure=true
                    theaterLog.warning("剧场接口域名 $host 失败；轮次=${round+1}；原因=${failure.message}")
                }
            }
            if(payload!=null) break
            if(round==0 && (sawEmpty || sawIdentityFailure)) {
                theaterLog.info("剧场接口六个域名均未成功，检测到空响应/身份拒绝，重新注册匿名设备身份后重试一次")
                profile=try {
                    playbackSession.renew(http)
                } catch(error:IOException) {
                    error.addSuppressed(lastError ?: IOException("剧场接口首轮请求失败"))
                    throw IOException("剧场接口设备身份刷新失败",error)
                }
            } else break
        }
        val response=payload ?: throw IOException("红果剧场接口暂不可用",lastError)
        val data=response.optJSONObject("data") ?: throw IOException("红果剧场返回缺少 data")
        val filters=if(page==1) {
            val rows=data.optJSONArray("selector_rows") ?: throw IOException("红果剧场未返回筛选面板")
            rows.objects().mapNotNull { row ->
                val field=row.str("type")
                val label=row.str("row_name").ifBlank { field }
                if(field.isBlank()) null else {
                    val options=row.optJSONArray("items")?.objects().orEmpty().mapNotNull { item ->
                        val id=item.str("selector_item_id"); val name=item.str("show_name")
                        if(id.isBlank() || name.isBlank()) null else TheaterFilterOption(id,name)
                    }.distinctBy { it.id }
                    TheaterFilterGroup(field,label,options)
                }
            }.also { if(it.isEmpty()) throw IOException("红果剧场筛选面板为空") }
        } else synchronized(theaterSessions) { session.filters }
        val rows=data.optJSONArray("video_data") ?: throw IOException("红果剧场未返回剧集列表")
        val items=rows.objects().mapNotNull { item ->
            val id=item.str("series_id")
            val title=item.str("title").ifBlank { item.str("series_title").ifBlank { item.str("series_name") } }
            if(!id.matches(Regex("[0-9]{1,30}")) || title.isBlank()) null else {
                val count=item.optInt("episode_cnt",0)
                val tags=item.optJSONArray("category_schema") ?: item.optJSONArray("sub_title_list") ?: JSONArray()
                val tagText=(0 until minOf(tags.length(),5)).mapNotNull { i ->
                    val tag=tags.opt(i)
                    when(tag) { is JSONObject -> tag.str("name").ifBlank { tag.str("content") }.takeIf { it.isNotBlank() }; is String -> tag.takeIf { it.isNotBlank() }; else -> null }
                }.joinToString(" · ")
                Series(id,title,cleanUrl(item.str("cover").ifBlank { item.str("series_cover") }),item.str("video_desc").ifBlank { item.str("series_intro") },if(count>0) "全 $count 集" else "",tagText)
            }
        }.distinctBy { it.id }
        val hasMore=data.optBoolean("has_more",false)
        val nextOffset=if(data.has("next_offset") && !data.isNull("next_offset")) data.optInt("next_offset",-1) else -1
        val nextSession=data.str("session_id")
        if(hasMore && (nextOffset<=cursor.offset || nextSession.isBlank())) throw IOException("红果剧场分页游标无效")
        synchronized(theaterSessions) {
            if(page==1) { session.filters=filters }
            items.forEach { session.shown.add(it.id) }
            if(hasMore && page<100) session.cursors[page+1]=TheaterCursor(nextOffset,nextSession)
            else session.cursors.remove(page+1)
        }
        return TheaterCatalogPage(CatalogPage(items,hasMore && page<100),filters,if(hasMore) nextOffset else null,nextSession)
    }
}
