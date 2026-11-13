package com.hongguotv.core

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class ContentRepositoryTest {
    @Test fun theaterRetriesAllHostsOnceAfterEmptyResponsesAndRenewsAnonymousIdentity() {
        val calls=mutableListOf<Pair<String,String>>()
        var registrations=0
        val session=PlaybackDeviceSession {
            registrations++
            DeviceRegister.Registered("190589259538258$registrations","190589259537849$registrations")
        }
        val repo=ContentRepository(
            OkHttpClient.Builder().addInterceptor { chain ->
                val request=chain.request()
                val buffer=okio.Buffer()
                request.body?.writeTo(buffer)
                val reqType=JSONObject(buffer.readUtf8()).optString("req_type")
                calls+=request.url.encodedPath to reqType
                val body=if(calls.size<=12) "" else when(reqType) {
                    "only_panel" -> """{"code":0,"data":{"selector_rows":[{"type":"category_dim_theme","row_name":"题材","items":[{"selector_item_id":"all","show_name":"全部"}]}]}}"""
                    "only_content" -> """{"code":0,"data":{"video_data":[],"has_more":false,"session_id":""}}"""
                    else -> ""
                }
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body(body.toResponseBody()).build()
            }.build(),
            System::currentTimeMillis,
            session
        )
        val result=repo.theater()
        assertTrue(result.catalog.items.isEmpty())
        assertEquals(2,registrations)
        assertEquals(14,calls.size)
        assertEquals(List(6) { "/reading/distribution/category/landpage/v1/" to "only_panel" },calls.take(6))
        assertEquals(List(6) { "/reading/distribution/category/landpage/v/" to "default" },calls.drop(6).take(6))
        assertEquals(listOf(
            "/reading/distribution/category/landpage/v1/" to "only_panel",
            "/reading/distribution/category/landpage/v1/" to "only_content"
        ),calls.takeLast(2))
    }

    @Test fun theaterDoesNotReregisterIdentityForOrdinaryNetworkFailures() {
        var registrations=0
        var requests=0
        val session=PlaybackDeviceSession {
            registrations++
            DeviceRegister.Registered("190589259538258$registrations","190589259537849$registrations")
        }
        val repo=ContentRepository(
            OkHttpClient.Builder().addInterceptor {
                requests++
                throw IOException("connection reset")
            }.build(),
            System::currentTimeMillis,
            session
        )
        assertThrows(IOException::class.java) { repo.theater() }
        assertEquals(1,registrations)
        assertEquals(12,requests)
    }

    private fun streamRepository(vararg variants: Triple<Int,String,String>) = repository { request ->
        if(request.url.host=="example.com") {
            val rows=JSONObject()
            variants.forEachIndexed { index,(height,codec,url) -> rows.put("video_$index",JSONObject().put("vheight",height).put("codec_type",codec).put("main_url",url)) }
            JSONObject().put("video_info",JSONObject().put("data",JSONObject().put("video_list",rows))).toString()
        } else """{"data":{"123":{"video_model":{"fallback_api":"https://example.com/stream"}}}}"""
    }

    @Test fun nestedVideoMetaPrefersH264EvenWhenHevcHasHigherResolution() {
        val repo=repository { request ->
            if(request.url.host.endsWith("fqnovel.com")) {
                """{"data":{"video_model":{"fallback_api":"https://example.com/stream"}}}"""
            } else {
                """{"video_info":{"data":{"video_list":{
                    "hevc":{"main_url":"https://example.com/hevc","video_meta":{"codec_type":"h265","definition":"1080p","vwidth":1920,"vheight":1080}},
                    "avc":{"main_url":"https://example.com/avc","video_meta":{"codec_type":"h264","definition":"720p","vwidth":1280,"vheight":720}}
                }}}}"""
            }
        }
        val result=repo.stream("123",1080)
        assertEquals("https://example.com/avc",result.url)
        assertEquals("720P",result.quality)
    }

    @Test fun directDataVideoModelResponseDoesNotRequireFallbackApi() {
        val repo=repository {
            """{"data":{"video_model":{"video_list":{"one":{"main_url":"https://example.com/direct","video_meta":{"codec_type":"avc1","vwidth":1280,"vheight":720}}}}}}"""
        }
        val result=repo.stream("123",720)
        assertEquals("https://example.com/direct",result.url)
        assertEquals("720P",result.quality)
    }

    @Test fun newVideoTabGetIsPreferredAndParsesVideoModelData() {
        val requests=mutableListOf<Request>()
        val repo=repository { request ->
            requests+=request
            assertEquals("GET",request.method)
            assertEquals("/reading/bookapi/video_tab/multi_video_model/v1/",request.url.encodedPath)
            assertEquals("123",request.url.queryParameter("video_id"))
            assertEquals("0",request.url.queryParameter("video_id_type"))
            assertEquals("1",request.url.queryParameter("device_level"))
            assertEquals("1",request.url.queryParameter("need_all_video_definition"))
            assertEquals("0",request.url.queryParameter("need_mp4_align"))
            assertEquals("0",request.url.queryParameter("source"))
            """{"code":0,"data":{"video_model_data":{"123":{"video_model":{"video_list":[
                {"main_url":"https://cdn.example.com/get-first.mp4","video_meta":{"codec_type":"h264","definition":"720p","vwidth":1280,"vheight":720}}
            ]}}}}}"""
        }
        val result=repo.stream("123",720)
        assertEquals("https://cdn.example.com/get-first.mp4",result.url)
        assertEquals("720P",result.quality)
        assertEquals(1,requests.size)
    }

    @Test fun invalidVideoTabResponseFallsBackToLegacyPost() {
        val methods=mutableListOf<String>()
        val repo=repository { request ->
            methods+=request.method
            if(request.url.encodedPath.contains("video_tab")) {
                """{"code":100103,"message":"PARAM_INVALID"}"""
            } else {
                assertEquals("POST",request.method)
                """{"data":{"123":{"video_model":{"video_list":{"one":{"main_url":"https://cdn.example.com/legacy.mp4","video_meta":{"codec_type":"h264","definition":"480p","vwidth":854,"vheight":480}}}}}}}"""
            }
        }
        val result=repo.stream("123",720)
        assertEquals("https://cdn.example.com/legacy.mp4",result.url)
        // 两个官方 GET 域名都返回无效模型后，才回退到旧 POST 通道。
        assertEquals(listOf("GET","GET","POST"),methods)
    }

    @Test fun newGetEndpointRotatesToSecondOfficialDomainAfterNetworkFailure() {
        val hosts = mutableListOf<String>()
        val repo = ContentRepository(
            OkHttpClient.Builder().addInterceptor { chain ->
                val request = chain.request()
                hosts += request.url.host
                if (request.url.host == "api5-normal-sinfonlineb.fqnovel.com") {
                    throw IOException("connection reset")
                }
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                    .code(200).message("OK").body("""{"code":0,"data":{"video_model_data":{"123":{"video_model":{"video_list":[{"main_url":"https://cdn.example.com/rotated.mp4","video_meta":{"codec_type":"h264","definition":"720p"}}]}}}}}""".toResponseBody()).build()
            }.build(),
            System::currentTimeMillis,
            PlaybackDeviceSession { DeviceRegister.Registered("1905892595382586","1905892595378490") }
        )
        val result = repo.stream("123",720)
        assertEquals("https://cdn.example.com/rotated.mp4",result.url)
        assertEquals(listOf("api5-normal-sinfonlineb.fqnovel.com","api5-normal-sinfonlinea.fqnovel.com"),hosts)
    }

    @Test fun legacyPostRotatesDomainsAfterRetryableNetworkFailure() {
        val calls = mutableListOf<Pair<String,String>>()
        val repo = ContentRepository(
            OkHttpClient.Builder().addInterceptor { chain ->
                val request = chain.request()
                calls += request.method to request.url.host
                if (request.url.encodedPath.contains("video_tab")) {
                    Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                        .body("""{"code":100103}""".toResponseBody()).build()
                } else if (request.url.host == "api5-normal-sinfonlineb.fqnovel.com") {
                    throw IOException("connection reset")
                } else {
                    Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                        .body("""{"data":{"123":{"video_model":{"video_list":{"one":{"main_url":"https://cdn.example.com/post-rotated.mp4","video_meta":{"codec_type":"h264","definition":"480p"}}}}}}}""".toResponseBody()).build()
                }
            }.build(),
            System::currentTimeMillis,
            PlaybackDeviceSession { DeviceRegister.Registered("1905892595382586","1905892595378490") }
        )
        val result = repo.stream("123",720)
        assertEquals("https://cdn.example.com/post-rotated.mp4",result.url)
        assertTrue(calls.contains("POST" to "api5-normal-sinfonlinea.fqnovel.com"))
    }

    @Test fun incompatibleByteVc2DoesNotBecomeAudioOnlyPlayback() {
        val repo=streamRepository(Triple(720,"bytevc2","https://example.com/unsupported"),Triple(1080,"bytevc1","https://example.com/hevc"))
        val result=repo.stream("123",720)
        assertEquals("https://example.com/hevc",result.url)
        assertEquals("1080P（兼容资源）",result.quality)
    }

    @Test fun compatibleLowerQualityWinsBeforeHigherResolutionFallback() {
        val repo=streamRepository(Triple(720,"bytevc2","https://example.com/unsupported"),Triple(480,"h264","https://example.com/avc"),Triple(1080,"bytevc1","https://example.com/hevc"))
        assertEquals("480P",repo.stream("123",720).quality)
    }

    @Test fun entirelyIncompatibleSourceFailsBeforeStartingAudio() {
        val repo=streamRepository(Triple(720,"ByteVC2","https://example.com/unsupported"),Triple(480,"bvc2","https://example.com/unsupported2"))
        assertThrows(UnplayableVideoException::class.java) { repo.stream("123",720) }
    }

    @Test fun legacyMissingCodecLabelKeepsQualityLimit() {
        val repo=streamRepository(Triple(720,"","https://example.com/legacy"),Triple(1080,"bytevc1","https://example.com/hevc"))
        assertEquals("720P",repo.stream("123",720).quality)
    }

    private fun repository(clock: () -> Long = System::currentTimeMillis, respond: (Request) -> String) = ContentRepository(
        OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body(respond(chain.request()).toResponseBody()).build()
        }.build(),clock,
        // 播放身份固定，测试不真的去注册设备。
        PlaybackDeviceSession { DeviceRegister.Registered("1905892595382586","1905892595378490") }
    )

    private fun rankPayload(page: Int = 1, totalPages: Int = 2) = JSONObject()
        .put("isSuccess",true)
        .put("rankList",JSONArray().put(JSONObject().put("seriesId","123").put("title","测试漫剧")
            .put("cover","https:\\u002F\\u002Fexample.com\\u002Fcover.jpg")
            .put("episodeCount",12).put("tags",JSONArray().put("玄幻"))))
        .put("pagination",JSONObject().put("pageNum",page).put("totalPages",totalPages))

    private fun script(name: String, args: JSONArray) =
        "<script data-fn-name=\"$name\" data-fn-args=\"${args.toString().replace("&","&amp;").replace("\"","&quot;")}\"></script>"

    private fun currentRank(payload: JSONObject) = script("mergeLoaderData",JSONArray()
        .put("rank_hot-comic-drama/page").put(JSONArray().put(JSONObject().put("key","content")
            .put("routerDataFnName","p").put("routerDataFnArgs",JSONArray().put(payload.toString())))))

    @Test fun comicRankingPreservesSourceRankHeatAndUpdateRatherThanRenumbering() {
        val payload=rankPayload(2,5)
        payload.getJSONArray("rankList").getJSONObject(0).put("rank",23).put("heatText","5746万热度")
        val repo=repository { request ->
            assertEquals("/rank/hot-comic-drama",request.url.encodedPath)
            assertEquals("2",request.url.queryParameter("page"))
            """<script>_ROUTER_DATA={"loaderData":{"rank_hot-comic-drama/page":{"rankKey":"comic","pageNum":2,"updatedText":"9月22日已更新"}}};</script>"""+currentRank(payload)
        }
        val result=repo.comicRanking(2)
        assertNotNull(result.ranking)
        assertEquals(RankPosition(23,"5746万热度"),result.ranking!!.positions["123"])
        assertEquals(5,result.ranking.totalPages)
        assertEquals("9月22日已更新",result.ranking.updatedText)
        assertTrue(result.hasMore)
        assertFalse(result.items.single().toJson().has("rank"))
    }

    @Test fun missingRankIsDerivedFromOrderAndLastPageStops() {
        val result=repository { currentRank(rankPayload(5,5)) }.comicRanking(5)
        assertNotNull(result.ranking)
        assertEquals(RankPosition(5,""),result.ranking!!.positions["123"])
        assertFalse(result.hasMore)
    }

    @Test fun renderedRankingPreservesPageTwoRankAndHeat() {
        val repo=repository {
            """<script>_ROUTER_DATA={"loaderData":{"rank_hot-comic-drama/page":{"rankKey":"comic","pageNum":2,"updatedText":"今日已更新"}}};</script>
                <article aria-labelledby="rank-title-123"><span class="pc-badge-number-example">21</span>
                <h2 id="rank-title-123">漫剧</h2><p class="pc-metrics-example"><span>5746万热度</span></p></article>
                <nav aria-label="榜单分页"><a href="/rank/hot-comic-drama">1</a><span aria-current="page">2</span><a href="/rank/hot-comic-drama?page=5">5</a></nav>"""
        }
        val result=repo.comicRanking(2)
        assertNotNull(result.ranking)
        assertEquals(RankPosition(21,"5746万热度"),result.ranking!!.positions["123"])
        assertEquals(5,result.ranking.totalPages)
        assertEquals("今日已更新",result.ranking.updatedText)
    }

    @Test fun rankingRejectsUnexpectedPageInsteadOfShowingWrongRanks() {
        val repo=repository { currentRank(rankPayload(1,5)) }
        assertThrows(IOException::class.java) { repo.comicRanking(2) }
        assertThrows(IllegalArgumentException::class.java) { repo.comicRanking(0) }
    }

    @Test fun renderedLastPageCountsCurrentPageWithoutAnAnchor() {
        val repo=repository {
            """<script>_ROUTER_DATA={"loaderData":{"rank_hot-comic-drama/page":{"rankKey":"comic","pageNum":5}}};</script>
                <article aria-labelledby="rank-title-123"><span class="pc-badge-number-example">81</span><h2 id="rank-title-123">漫剧</h2></article>
                <nav aria-label="榜单分页"><a href="/rank/hot-comic-drama?page=4">4</a><span aria-current="page">5</span></nav>"""
        }
        val result=repo.comicRanking(5)
        assertEquals(5,result.ranking!!.totalPages)
        assertFalse(result.hasMore)
    }

    @Test fun comicHomeUsesDedicatedRankAndRealPagination() {
        val repo=repository { request ->
            assertEquals("/rank/hot-comic-drama",request.url.encodedPath)
            assertEquals("2",request.url.queryParameter("page"))
            currentRank(rankPayload(2,2))
        }
        val page=repo.home(2,ContentType.COMIC)
        assertEquals("测试漫剧",page.items.single().title)
        assertEquals("https://example.com/cover.jpg",page.items.single().cover)
        assertEquals("全 12 集",page.items.single().badge)
        assertFalse(page.hasMore)
    }

    @Test fun legacyRankIsStillSupported() {
        val repo=repository { script("r",JSONArray().put("rank_hot-comic-drama/page").put("content").put(rankPayload())) }
        assertTrue(repo.home(1,ContentType.COMIC).hasMore)
    }

    @Test fun comicHomeDoesNotUseUnrelatedRankData() {
        val repo=repository { script("r",JSONArray().put("rank_hot-real-drama/page").put("content").put(rankPayload())) }
        assertThrows(IOException::class.java) { repo.home(1,ContentType.COMIC) }
    }

    @Test fun rejectedComicRankRemainsAnError() {
        val repo=repository { currentRank(rankPayload().put("isSuccess",false)) }
        assertThrows(IOException::class.java) { repo.home(1,ContentType.COMIC) }
    }

    @Test fun renderedComicRankWorksWhenStreamedPayloadIsMissing() {
        val repo=repository {
            """<script>_ROUTER_DATA={"loaderData":{"rank_hot-comic-drama/page":{"rankKey":"comic","pageNum":2}}};</script>
                <article aria-labelledby="rank-title-123"><a href="/detail?series_id=123"><img src="https://example.com/a.jpg"></a>
                <h2 id="rank-title-123">测试 &amp; 漫剧</h2><p class="pc-description-example">简介</p><p class="pc-metrics-example">123万热度</p></article>
                <nav aria-label="榜单分页"><a href="/rank/hot-comic-drama?page=1">1</a><span aria-current="page">2</span><a href="/rank/hot-comic-drama?page=3">3</a></nav>"""
        }
        val page=repo.home(2,ContentType.COMIC)
        assertEquals("测试 & 漫剧",page.items.single().title)
        assertEquals("https://example.com/a.jpg",page.items.single().cover)
        assertTrue(page.hasMore)
    }

    @Test fun comicSearchCarriesCursorAndKeepsTypesAndQueriesSeparate() {
        val requests=mutableListOf<Request>()
        val repo=repository { request ->
            requests+=request
            if(request.url.host.endsWith("fqnovel.com")) JSONObject().put("code",0).put("search_tabs",JSONArray().put(searchTab(19,"999")
                .put("search_id","comic-session").put("passback","comic-cursor").put("next_offset",25))).toString()
            else """{"searchList":[{"video_data":{"series_id":"333","series_name":"漫画网站结果","genre":"205"}}]}"""
        }
        repo.search("修仙",1,ContentType.COMIC)
        repo.search("修仙",2,ContentType.COMIC)
        assertEquals("comic-session",requests[1].url.queryParameter("search_id"))
        assertEquals("comic-cursor",requests[1].url.queryParameter("passback"))
        assertEquals("25",requests[1].url.queryParameter("offset"))
        repo.search("重生",2,ContentType.COMIC)
        assertEquals("2",requests[2].url.queryParameter("page"))
        assertEquals(3,requests.size)
    }

    @Test fun expiredCursorRequestsFreshSearchInsteadOfDuplicateOffsetPage() {
        var now=0L
        var requests=0
        val repo=repository(clock={ now }) { request ->
            requests++
            if(request.url.host.endsWith("fqnovel.com")) JSONObject().put("code",0).put("search_tabs",JSONArray().put(searchTab(19,"999")
                .put("search_id","session").put("passback","cursor"))).toString()
            else """{"searchList":[{"video_data":{"series_id":"444","series_name":"漫画兜底","genre":"205"}}]}"""
        }
        repo.search("修仙",1,ContentType.COMIC)
        now=300001L
        assertEquals("444",repo.search("修仙",2,ContentType.COMIC).items.single().id)
        assertEquals(2,requests)
        repo.search("修仙",1,ContentType.COMIC)
        repo.search("修仙",2,ContentType.COMIC)
        assertEquals(4,requests)
    }

    @Test fun comicSearchFallsBackToWebsiteAndKeepsOnlyComicGenreRows() {
        val repo=repository { request ->
            if(request.url.host.endsWith("fqnovel.com")) ""
            else """{"searchList":[{"video_data":{"series_id":"111","series_name":"短剧测试","genre":"203"}},{"video_data":{"series_id":"222","series_name":"漫画测试","genre":"205"}}]}"""
        }
        val result=repo.search("修仙",1,ContentType.COMIC)
        assertEquals(listOf("222"),result.items.map { it.id })
        // 站点没有 hasMore 时不再提示翻页
        assertFalse(result.hasMore)
    }

    @Test fun multiSeasonSearchFillsMissingSeasonsFromSuggestions() {
        val repo=repository { request ->
            val url=request.url
            val keyword=url.queryParameter("query").orEmpty()
            when {
                url.host.endsWith("fqnovel.com") && url.encodedPath.contains("suggest") ->
                    JSONObject().put("code",0).put("data",JSONObject().put("query_result",JSONArray().put("测试剧第二季"))).toString()
                url.host.endsWith("fqnovel.com") -> ""
                keyword.contains("第二季") ->
                    """{"searchList":[{"video_data":{"series_id":"222","series_name":"测试剧第二季","genre":"205"}}]}"""
                else ->
                    """{"searchList":[{"video_data":{"series_id":"111","series_name":"测试剧第一季","genre":"205"}},{"video_data":{"series_id":"113","series_name":"测试剧第三季","genre":"205"}}]}"""
            }
        }
        val result=repo.search("测试剧",1,ContentType.COMIC)
        assertEquals(listOf("111","222","113"),result.items.map { it.id })
    }

    @Test fun shortSearchFallsBackToWebsiteAndKeepsOnlyShortGenreRows() {
        val repo=repository { request ->
            if(request.url.host.endsWith("fqnovel.com")) ""
            else """{"searchList":[{"video_data":{"series_id":"111","series_name":"短剧测试","genre":"203"}},{"video_data":{"series_id":"222","series_name":"漫画测试","genre":"205"}}]}"""
        }
        val result=repo.search("总裁",1,ContentType.SHORT)
        assertEquals(listOf("111"),result.items.map { it.id })
    }

    @Test fun websiteSearchPaginatesWithSitePageAndHasMore() {
        val pages=mutableListOf<String>()
        val repo=repository { request ->
            if(request.url.host.endsWith("fqnovel.com")) ""
            else {
                assertEquals("/api/search/result/",request.url.encodedPath)
                assertEquals("修仙",request.url.queryParameter("query"))
                val page=request.url.queryParameter("page").orEmpty()
                pages+=page
                """{"searchList":[{"video_data":{"series_id":"90$page","series_name":"网站结果第${page}页","genre":"205"}}],"totalPages":2,"hasMore":${page=="1"}}"""
            }
        }
        val first=repo.search("修仙",1,ContentType.COMIC)
        assertEquals(listOf("901"),first.items.map { it.id })
        assertTrue(first.hasMore)
        val second=repo.search("修仙",2,ContentType.COMIC)
        assertEquals(listOf("902"),second.items.map { it.id })
        assertFalse(second.hasMore)
        assertEquals(listOf("1","2"),pages)
    }

    @Test fun shortHomeStillUsesCategoryAndStopsOnLastPage() {
        val repo=repository { request ->
            assertEquals("1",request.url.queryParameter("tab"))
            """<script>_ROUTER_DATA={"loaderData":{"category_$":{"recommendList":[{"series_id":"456","series_name":"测试短剧"}],"pagination":{"totalPages":1}}}};</script>"""
        }
        val result=repo.home(1,ContentType.SHORT)
        assertEquals("456",result.items.single().id)
        assertFalse(result.hasMore)
    }

    @Test fun actorRankingUsesSignedCellChangeAndCarriesSessionAcrossPages() {
        val requests = mutableListOf<Request>()
        val session = PlaybackDeviceSession {
            DeviceRegister.Registered("1905892595382586", "1905892595378490")
        }
        val repo = ContentRepository(
            OkHttpClient.Builder().addInterceptor { chain ->
                val request = chain.request()
                requests += request
                assertEquals("GET", request.method)
                assertEquals("/reading/bookapi/bookmall/cell/change/v1/", request.url.encodedPath)
                assertEquals("7470092475068071998", request.url.queryParameter("cell_id"))
                assertEquals(if (requests.size == 1) "26" else "18", request.url.queryParameter("tab_type"))
                assertEquals("20", request.url.queryParameter("limit"))
                assertEquals("2", request.url.queryParameter("gender"))
                assertEquals("0", request.url.queryParameter("unlimited_selector_change_type"))
                assertEquals("ranklist_celebrity", request.url.queryParameter("selected_items"))
                assertEquals("ranklist_series_actor", request.url.queryParameter("sub_selected_items"))
                assertEquals("0", request.url.queryParameter("user_is_login"))
                val offset = request.url.queryParameter("offset")
                val body = if (offset == "0") {
                    """{"code":0,"data":{"has_more":true,"next_offset":1,"session_id":"actor-session","rank_version":"v1","cell_view":{"cell_data":[{"ugc_user_data":[{"user_info":{"user_id":"7100000000001301","base_info":{"actor_id_str":"7100000000001401","user_name":"首屏演员","user_avatar":"https://example.com/actor.jpg"}},"rec_text_item":{"RecommendText":"700万人气"}}]}]}}}"""
                } else {
                    assertEquals("1", offset)
                    assertEquals("actor-session", request.url.queryParameter("session_id"))
                    """{"code":0,"data":{"has_more":false,"next_offset":2,"session_id":"actor-session","rank_version":"v1","cell_view":{"cell_data":[{"ugc_user_data":[{"user_info":{"user_id":"7100000000001302","base_info":{"actor_id_str":"7100000000001402","user_name":"第二页演员"}}}] }]}}}"""
                }
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                    .code(200).message("OK").body(body.toResponseBody()).build()
            }.build(),
            System::currentTimeMillis,
            session
        )

        val first = repo.actorRanking()
        assertEquals("首屏演员", first.items.single().name)
        assertTrue(first.hasMore)
        assertEquals(1, first.nextOffset)
        assertEquals("actor-session", first.sessionId)

        val second = repo.actorRanking(first.nextOffset, first.sessionId)
        assertEquals("第二页演员", second.items.single().name)
        assertFalse(second.hasMore)
        assertEquals(2, second.nextOffset)
        assertEquals(2, requests.size)
    }

    @Test fun actorRankingCanRequestStarBoardAsAnInlineSubBoard() {
        var requestedSubBoard = ""
        val repo = ContentRepository(
            OkHttpClient.Builder().addInterceptor { chain ->
                requestedSubBoard = chain.request().url.queryParameter("sub_selected_items").orEmpty()
                val request = chain.request()
                assertEquals("ranklist_celebrity", request.url.queryParameter("selected_items"))
                assertEquals("ranklist_actor_fans", request.url.queryParameter("sub_selected_items"))
                val body = """{"code":0,"data":{"has_more":false,"next_offset":1,"cell_view":{"cell_data":[{"ugc_user_data":[{"user_info":{"user_id":"7100000000001303","base_info":{"actor_id_str":"7100000000001403","user_name":"星光榜演员","user_avatar":"https://p3-reading-sign.fqnovelpic.com/novel-pic/star-avatar~tplv-81nmtwyey9.heic"}},"rec_text_item":{"RecommendText":"900万人气"},"intro":"星光榜演员简介"}]}]}}}"""
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(200).message("OK").body(body.toResponseBody()).build()
            }.build(),
            System::currentTimeMillis,
            PlaybackDeviceSession { DeviceRegister.Registered("1905892595382586", "1905892595378490") }
        )

        val page = repo.actorRanking(0, "", "ranklist_actor_fans")
        assertEquals("ranklist_actor_fans", requestedSubBoard)
        assertEquals("星光榜演员", page.items.single().name)
        assertEquals("7100000000001303", page.items.single().userId)
        assertEquals("7100000000001403", page.items.single().actorId)
        assertEquals("https://p3-novel.byteimg.com/novel-pic/star-avatar~tplv-shrink:640:0.image", page.items.single().avatar)
        assertEquals("900万人气", page.items.single().popularity)
        assertEquals("星光榜演员简介", page.items.single().intro)
        assertFalse(page.hasMore)
    }

    @Test fun actorWorksUsesOfficialCelebrityWorksEndpointAndPagesCumulativeResults() {
        val requests = mutableListOf<Request>()
        val repo = ContentRepository(
            OkHttpClient.Builder().addInterceptor { chain ->
                val request = chain.request()
                requests += request
                assertEquals("GET", request.method)
                assertEquals("/reading/user/celebrity/works/v1/", request.url.encodedPath)
                assertEquals("7100000000001401", request.url.queryParameter("encrypted_celebrity_ids"))
                assertEquals(if (requests.size == 1) "0" else "1", request.url.queryParameter("offset"))
                assertEquals("0", request.url.queryParameter("req_scene"))
                val hasMore = request.url.queryParameter("offset") == "0"
                val body = """{"code":0,"data":{"works":{"7100000000001401":{"total_count":2,"has_more":$hasMore,"video_list":[{"series_id":"7700000000000001","title":"演员作品甲","cover":"https://example.com/a.jpg"},{"series_id":"7700000000000002","title":"演员作品乙","cover":"https://example.com/b.jpg"}]}}}}"""
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                    .code(200).message("OK").body(body.toResponseBody()).build()
            }.build(),
            System::currentTimeMillis,
            PlaybackDeviceSession { DeviceRegister.Registered("1905892595382586", "1905892595378490") }
        )

        val first = repo.actorWorks("7100000000001401", 0, 1)
        assertEquals("演员作品甲", first.items.single().title)
        assertEquals(1, first.nextOffset)
        assertTrue(first.hasMore)

        val second = repo.actorWorks("7100000000001401", first.nextOffset, 1)
        assertEquals("演员作品乙", second.items.single().title)
        assertEquals(2, second.nextOffset)
        assertFalse(second.hasMore)
        assertEquals(2, requests.size)
    }

    @Test fun actorStarBoardAcceptsAlternateListAndNestedAvatarShape() {
        val repo = ContentRepository(
            OkHttpClient.Builder().addInterceptor { chain ->
                val body = """{"code":0,"data":{"has_more":false,"next_offset":1,"rank_list":[{"user_id":"7100000000001304","user_name":"巨星榜演员","avatar_thumb":{"url_list":["https://example.com/star.jpg"]},"fans_count_text":"900万人气","intro":"测试演员介绍"}]}}"""
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(200).message("OK").body(body.toResponseBody()).build()
            }.build(),
            System::currentTimeMillis,
            PlaybackDeviceSession { DeviceRegister.Registered("1905892595382586", "1905892595378490") }
        )

        val page = repo.actorRanking(0, "", "ranklist_actor_fans")
        assertEquals("巨星榜演员", page.items.single().name)
        assertEquals("https://example.com/star.jpg", page.items.single().avatar)
        assertEquals("900万人气", page.items.single().popularity)
        assertEquals("测试演员介绍", page.items.single().intro)
        assertFalse(page.hasMore)
    }

    private fun searchTab(type: Int,id: String) = JSONObject().put("tab_type",type).put("has_more",true)
        .put("data",JSONArray().put(JSONObject().put("video_data",JSONArray().put(JSONObject()
            .put("series_id",id).put("title","测试剧集").put("episode_cnt",8)))))

    @Test fun comicSearchRequestsAndSelectsOnlyComicTab() {
        var requests=0
        val repo=repository { request ->
            requests++
            assertEquals("19",request.url.queryParameter("tab_type"))
            assertEquals(if(requests==1) "0" else "20",request.url.queryParameter("offset"))
            assertEquals("修仙",request.url.queryParameter("query"))
            JSONObject().put("code",0).put("search_tabs",JSONArray()
                .put(searchTab(11,"111")).put(searchTab(19,"999"))).toString()
        }
        repo.search("修仙",1,ContentType.COMIC)
        assertEquals(listOf("999"),repo.search("修仙",2,ContentType.COMIC).items.map { it.id })
    }

    @Test fun emptyComicSearchDoesNotFallBackToShortResults() {
        var requests=0
        val repo=repository { requests++; """{"code":0,"search_tabs":[{"tab_type":19,"data":[],"has_more":false}]}""" }
        assertTrue(repo.search("无结果",1,ContentType.COMIC).items.isEmpty())
        assertEquals(1,requests)
    }

    @Test fun missingComicTabFallsBackToWebsiteWithoutShortResults() {
        var requests=0
        val repo=repository { request ->
            requests++
            if(request.url.host.endsWith("fqnovel.com")) """{"code":0,"search_tabs":[{"tab_type":11,"data":[]}]}"""
            else """{"searchList":[{"video_data":{"series_id":"111","series_name":"短剧测试","genre":"203"}}]}"""
        }
        assertTrue(repo.search("修仙",1,ContentType.COMIC).items.isEmpty())
        assertEquals(2,requests)
    }

    @Test fun shortSearchKeepsWebsiteFallback() {
        val paths=mutableListOf<String>()
        val repo=repository { request ->
            paths+=request.url.encodedPath
            if(paths.size<=2) throw IOException("offline")
            """{"searchList":[{"video_data":{"series_id":"456","series_name":"测试短剧","genre":"203"}}]}"""
        }
        assertEquals("456",repo.search("短剧",1,ContentType.SHORT).items.single().id)
        assertEquals(3,paths.size)
    }

    private fun relatedItem(id: String,name: String)=JSONObject().put("series_id",id).put("series_name",name)
        .put("series_cover","https://example.com/$id.jpg").put("episode_cnt",10).put("episode_right_text","全 10 集")
    private fun detailHtml(series: JSONObject,recommended: JSONArray): String {
        val recommendations=JSONObject().put("videoList",JSONArray())
            .put("recommendationGroups",JSONObject().put("related_list",recommended))
        val args=JSONArray().put("detail_page").put(JSONArray().put(JSONObject().put("key","recommendations")
            .put("routerDataFnName","p").put("routerDataFnArgs",JSONArray().put(recommendations.toString()))))
        val attribute=args.toString().replace("&","&amp;").replace("\"","&quot;")
        return """<script>_ROUTER_DATA={"loaderData":{"detail_page":{"seriesDetail":$series}}};</script><script data-fn-args="$attribute"></script>"""
    }

    @Test fun detailRelatedHoistsSameNameSeasonsAndSkipsCurrentSeries() {
        val series=JSONObject().put("series_id","123").put("series_name","测试剧").put("vid_list",JSONArray().put("900").put("901"))
        val html=detailHtml(series,JSONArray()
            .put(relatedItem("111","别人的剧"))
            .put(relatedItem("222","测试剧"))
            .put(relatedItem("333","测试剧 第二季"))
            .put(relatedItem("123","测试剧")))
        val detail=repository { html }.detail("123")
        assertEquals(listOf("900","901"),detail.episodes)
        assertEquals(listOf("222","333","111"),detail.related.map { it.id })
    }

    @Test fun detailWithoutRecommendationPayloadStillLoadsEpisodes() {
        val html="""<script>_ROUTER_DATA={"loaderData":{"detail_page":{"seriesDetail":{"series_id":"123","series_name":"测试剧","vid_list":["900","901"]}}}};</script>"""
        val detail=repository { html }.detail("123")
        assertEquals(listOf("900","901"),detail.episodes)
        assertTrue(detail.related.isEmpty())
    }

    @Test fun detailCollectsSameNameSeasonsFromWebsiteSearchOrderedBySeason() {
        val series=JSONObject().put("series_id","123").put("series_name","测试剧 第二季").put("vid_list",JSONArray().put("900"))
        val repo=repository { request ->
            if(request.url.encodedPath=="/detail") detailHtml(series,JSONArray())
            else """{"searchList":[
                {"video_data":{"series_id":"222","series_name":"测试剧 第三季","genre":"203"}},
                {"video_data":{"series_id":"111","series_name":"别人的剧","genre":"203"}},
                {"video_data":{"series_id":"333","series_name":"测试剧 第一季","genre":"203"}},
                {"video_data":{"series_id":"444","series_name":"测试剧 第十季","genre":"203"}},
                {"video_data":{"series_id":"123","series_name":"测试剧 第二季","genre":"203"}}]}"""
        }
        val detail=repo.detail("123")
        val seasons=repo.sameNameSeasons(detail.series,detail.related)
        assertEquals(listOf("333","222","444"),seasons.map { it.id })
    }

    @Test fun detailFillsMissingSeasonsFromSuggestionsLikeSearch() {
        val series=JSONObject().put("series_id","123").put("series_name","测试剧 第一季").put("vid_list",JSONArray().put("900"))
        val repo=repository { request ->
            when {
                request.url.encodedPath=="/detail" -> detailHtml(series,JSONArray())
                request.url.host.endsWith("fqnovel.com") -> JSONObject().put("code",0)
                    .put("data",JSONObject().put("query_result",JSONArray().put("测试剧 第二季").put("测试剧 第三季"))).toString()
                else -> {
                    val keyword=request.url.queryParameter("query").orEmpty()
                    val row=when(keyword) {
                        "测试剧" -> """{"video_data":{"series_id":"123","series_name":"测试剧 第一季","genre":"203"}}"""
                        "测试剧 第二季" -> """{"video_data":{"series_id":"222","series_name":"测试剧 第二季","genre":"203"}}"""
                        "测试剧 第三季" -> """{"video_data":{"series_id":"333","series_name":"测试剧 第三季","genre":"203"}}"""
                        else -> ""
                    }
                    if(row.isEmpty()) """{"searchList":[]}""" else """{"searchList":[${row}]}"""
                }
            }
        }
        // 网站只返回季 1，联想给出的季 2、季 3 需要回查补齐。
        // 同名季按页面设计在详情显示后异步加载，不属于 detail() 的同步返回值。
        val detail=repo.detail("123")
        val seasons=repo.sameNameSeasons(detail.series,detail.related)
        assertEquals(listOf("222","333"),seasons.map { it.id })
    }

    private fun currentTotalRank(payload: JSONObject) = script("mergeLoaderData",JSONArray()
        .put("rank_hot-drama/page").put(JSONArray().put(JSONObject().put("key","content")
            .put("routerDataFnName","p").put("routerDataFnArgs",JSONArray().put(payload.toString())))))

    @Test fun totalRankingUsesTheOfficialHongguoHotDramaRoute() {
        val payload=rankPayload(1,4)
        payload.getJSONArray("rankList").getJSONObject(0).put("rank",1).put("heatText","8339万热度")
        val repo=repository { request ->
            assertEquals("/rank/hot-drama",request.url.encodedPath)
            assertEquals("1",request.url.queryParameter("page"))
            """<script>_ROUTER_DATA={"loaderData":{"rank_hot-drama/page":{"pageNum":1,"updatedText":"今日已更新"}}};</script>"""+currentTotalRank(payload)
        }
        val result=repo.ranking(RankBoard.TOTAL,1)
        assertEquals("测试漫剧",result.items.single().title)
        assertEquals(RankPosition(1,"8339万热度"),result.ranking!!.positions["123"])
        assertEquals(4,result.ranking!!.totalPages)
        assertEquals("今日已更新",result.ranking!!.updatedText)
        assertTrue(result.hasMore)
    }

    @Test fun totalRankingRenderedFallbackDoesNotRequireAnUnverifiedRankKey() {
        val repo=repository {
            """<script>_ROUTER_DATA={"loaderData":{"rank_hot-drama/page":{"pageNum":1,"updatedText":"今日已更新"}}};</script>
                <article aria-labelledby="rank-title-789"><span class="pc-badge-number-example">1</span>
                <h2 id="rank-title-789">综合榜剧集</h2><p class="pc-metrics-example">8339万热度</p></article>
                <nav aria-label="榜单分页"><span aria-current="page">1</span><a href="/rank/hot-drama?page=4">4</a></nav>"""
        }
        val result=repo.ranking(RankBoard.TOTAL,1)
        assertEquals("综合榜剧集",result.items.single().title)
        assertEquals(RankPosition(1,"8339万热度"),result.ranking!!.positions["789"])
        assertEquals(4,result.ranking!!.totalPages)
    }

    private fun currentRealRank(payload: JSONObject) = script("mergeLoaderData",JSONArray()
        .put("rank_hot-real-drama/page").put(JSONArray().put(JSONObject().put("key","content")
            .put("routerDataFnName","p").put("routerDataFnArgs",JSONArray().put(payload.toString())))))

    @Test fun realRankingParsesTheShortDramaBoard() {
        val payload=rankPayload(1,5)
        payload.getJSONArray("rankList").getJSONObject(0).put("rank",1).put("heatText","9999万热度")
        val repo=repository { request ->
            assertEquals("/rank/hot-real-drama",request.url.encodedPath)
            """<script>_ROUTER_DATA={"loaderData":{"rank_hot-real-drama/page":{"rankKey":"real","pageNum":1,"updatedText":"今日已更新"}}};</script>"""+currentRealRank(payload)
        }
        val result=repo.realRanking(1)
        assertNotNull(result.ranking)
        assertEquals(RankPosition(1,"9999万热度"),result.ranking!!.positions["123"])
        assertEquals("今日已更新",result.ranking!!.updatedText)
        assertTrue(result.hasMore)
    }

    @Test fun realRankingRejectsTheComicBoardPayload() {
        val repo=repository { currentRank(rankPayload()) }
        assertThrows(IOException::class.java) { repo.realRanking(1) }
    }

    @Test fun renderedRealRankingWorksWithoutStreamedPayload() {
        val repo=repository {
            """<script>_ROUTER_DATA={"loaderData":{"rank_hot-real-drama/page":{"rankKey":"real","pageNum":2,"updatedText":"今日已更新"}}};</script>
                <article aria-labelledby="rank-title-456"><span class="pc-badge-number-example">22</span>
                <h2 id="rank-title-456">短剧</h2><p class="pc-metrics-example"><span>100万热度</span></p></article>
                <nav aria-label="榜单分页"><a href="/rank/hot-real-drama">1</a><span aria-current="page">2</span><a href="/rank/hot-real-drama?page=5">5</a></nav>"""
        }
        val result=repo.realRanking(2)
        assertEquals(RankPosition(22,"100万热度"),result.ranking!!.positions["456"])
        assertEquals(5,result.ranking!!.totalPages)
        assertEquals("今日已更新",result.ranking!!.updatedText)
    }

    private fun selector(slug: String,name: String,vararg ids: String)=JSONObject().put("selector_item_id",slug).put("show_name",name)
        .put("category_json_ids",JSONArray().apply { ids.forEach { put(it) } })

    private fun categoryHtml(recommendList: JSONArray = JSONArray(),selectorList: JSONArray? = null,apiIds: JSONArray? = null,totalPages: Int = 1): String {
        val section=JSONObject().put("recommendList",recommendList).put("pagination",JSONObject().put("totalPages",totalPages))
        if(selectorList!=null) section.put("selectorList",selectorList)
        if(apiIds!=null) section.put("apiQuery",JSONObject().put("category_json_ids",apiIds))
        return """<script>_ROUTER_DATA={"loaderData":{"category_$":${section}}};</script>"""
    }

    @Test fun genresComeFromTheCategorySelectorAndAreCached() {
        var requests=0
        val repo=repository { request ->
            requests++
            assertEquals("/category/real-drama",request.url.encodedPath)
            categoryHtml(selectorList=JSONArray().put(JSONObject().put("row_name","全部分类").put("items",JSONArray()
                .put(selector("romance","爱情","5000")).put(selector("costume","古装","5007")))))
        }
        val genres=repo.genres(ContentType.SHORT)
        assertEquals(listOf("romance","costume"),genres.map { it.slug })
        assertEquals(listOf("爱情","古装"),genres.map { it.name })
        assertEquals(listOf("5000"),genres.first().ids)
        assertEquals(genres,repo.genres(ContentType.SHORT))
        assertEquals(1,requests)
    }

    @Test fun genreHomeUsesTheSlugPathAndValidatesAppliedIds() {
        val rows=JSONArray().put(JSONObject().put("series_id","456").put("series_name","爱情短剧"))
        val repo=repository { request ->
            assertEquals("/category/real-drama/romance",request.url.encodedPath)
            assertEquals("2",request.url.queryParameter("page"))
            categoryHtml(rows,apiIds=JSONArray().put("5000"),totalPages=9)
        }
        val page=repo.home(2,ContentType.SHORT,Genre("romance","爱情",listOf("5000")))
        assertEquals("456",page.items.single().id)
        assertTrue(page.hasMore)
    }

    @Test fun genreHomeFailsWhenTheSiteIgnoresTheFilter() {
        val repo=repository { categoryHtml(apiIds=JSONArray()) }
        assertThrows(IOException::class.java) { repo.home(1,ContentType.SHORT,Genre("romance","爱情",listOf("5000"))) }
    }

    @Test fun comicGenresUseTheComicCategoryPath() {
        val rows=JSONArray().put(JSONObject().put("series_id","789").put("series_name","脑洞漫剧"))
        val repo=repository { request ->
            assertEquals("/category/comic-drama/creative",request.url.encodedPath)
            categoryHtml(rows,apiIds=JSONArray().put("755"))
        }
        assertEquals("789",repo.home(1,ContentType.COMIC,Genre("creative","脑洞",listOf("755"))).items.single().id)
    }

    @Test fun suggestParsesQueryResultWordsAndDeduplicates() {
        val repo=repository { request ->
            assertEquals("/reading/bookapi/search/suggest/v",request.url.encodedPath)
            assertEquals("jywn",request.url.queryParameter("query"))
            """{"code":0,"data":{"query_key":"jywn","query_result":["家有五女"," 今夜为你无眠 ","家有五女",""]}}"""
        }
        assertEquals(listOf("家有五女","今夜为你无眠"),repo.suggest(" JYWN "))
    }

    @Test fun suggestFallsBackToEmptyOnBlankBodyErrorCodeOrNetworkFailure() {
        var requests=0
        val blank=repository { requests++; "" }
        assertTrue(blank.suggest("jywn").isEmpty())
        val rejected=repository { requests++; """{"code":-1}""" }
        assertTrue(rejected.suggest("jywn").isEmpty())
        val offline=repository { requests++; throw IOException("offline") }
        assertTrue(offline.suggest("jywn").isEmpty())
        assertEquals(3,requests)
    }

    @Test fun suggestSkipsNonAlphabetInputWithoutARequest() {
        var requests=0
        val repo=repository { requests++; """{"code":0,"data":{"query_result":["a"]}}""" }
        assertTrue(repo.suggest("").isEmpty())
        assertTrue(repo.suggest("家有").isEmpty())
        assertTrue(repo.suggest("a".repeat(21)).isEmpty())
        assertEquals(0,requests)
    }
}
