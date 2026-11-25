// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp.ui.data

import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/**
 * 投播的分享文案解析层。
 *
 * 三种入口：红果分享链接（直接拿到剧目/集数 ID）、抖音短链（重定向取 aweme_id 再查 Feed 短剧信息）、
 * 抖音短剧详情页（playlet_id）。解析只负责「这句话说的是哪部剧」，不碰播放与界面；
 * 匹配失败时宁可返回候选让用户确认，也不猜一个近似标题直接开播。
 */

internal data class PushShareItem(
    val title:String,
    val url:String,
    val source:String,
    val titleCandidates:List<String> = emptyList()
)
internal data class RedShareIds(val seriesId:String?,val videoId:String?)

/** 抖音短剧分享页使用 playlet_id/series_id，不是 aweme_id；单独请求短剧详情接口取剧名。 */
internal object DouyinPlayletResolver {
    private fun text(obj:JSONObject?,key:String):String =
        obj?.opt(key)?.takeUnless { it==JSONObject.NULL }?.toString()?.trim().orEmpty().takeUnless { it=="null" }.orEmpty()
    private fun findDetail(value:Any?,playletId:String,depth:Int=0):JSONObject? {
        if(depth>24) return null
        when(value) {
            is JSONObject -> {
                val id=listOf("playlet_id","series_id","seriesId","id").map { text(value,it) }.firstOrNull { it.isNotBlank() }
                val hasTitle=listOf("playlet_name","series_name","title","name","mix_name").any { text(value,it).isNotBlank() }
                if(id==playletId && hasTitle) return value
                val keys=value.keys()
                while(keys.hasNext()) findDetail(value.opt(keys.next()),playletId,depth+1)?.let { return it }
                if(depth<=2 && hasTitle) return value
            }
            is JSONArray -> for(i in 0 until value.length()) findDetail(value.opt(i),playletId,depth+1)?.let { return it }
        }
        return null
    }
    fun resolveTitle(playletId:String):String {
        require(Regex("^\\d{8,25}$").matches(playletId)) { "抖音短剧 ID 格式不正确" }
        val endpoint="https://www.iesdouyin.com/web/api/playlet/detail/?playlet_id=$playletId"
        val connection=(URL(endpoint).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects=true
            connectTimeout=8000
            readTimeout=10000
            requestMethod="GET"
            setRequestProperty("User-Agent","Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 Chrome/126.0.0.0 Mobile Safari/537.36")
            setRequestProperty("Accept","application/json, text/plain, */*")
            setRequestProperty("Referer","https://www.iesdouyin.com/")
        }
        try {
            val status=connection.responseCode
            if(status !in 200..299) throw java.io.IOException("抖音短剧详情 HTTP $status")
            val body=connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText().take(2*1024*1024) }
            val root=JSONObject(body)
            val detail=findDetail(root,playletId) ?: root.optJSONObject("data") ?: root
            val title=listOf("playlet_name","series_name","title","name","mix_name")
                .map { text(detail,it) }.firstOrNull { it.isNotBlank() }.orEmpty()
            if(title.isBlank()) throw java.io.IOException("短剧详情接口未返回可识别的剧名")
            return title
        } finally { connection.disconnect() }
    }
}
internal object PushShareParser {
    private val redTitle=Regex("(?:漫剧|短剧)《([^》]{1,80})》")
    private val urlToken=Regex("""https?://[^\s<>\"'，。]+""",RegexOption.IGNORE_CASE)
    private val bracketTitle=Regex("[《【〖]([^》】〗]{1,100})[》】〗]")
    private val authorWorkSuffix=Regex("(?:的作品|作品)$")
    private val explicitTitle=Regex("(?:短剧|漫剧)?《([^》]{2,80})》")
    private val hashtag=Regex("#\\s*([^#\\s]+)")
    private val descriptionStart=Regex("\\s+(?=(?:为(?:了)?|讲述|讲的是|故事讲述|剧情简介|剧情是|本剧讲述))")
    private val adPhrases=listOf(
        "红果海量","海量库存","库存全免费","免费短剧APP","来红果","热门爆款天天更",
        "短剧自由拿捏","免费短剧app","看全集","全集看","爆款天天更","全免费"
    )
    private val genericTags=setOf(
        "抖音短剧","短剧","漫剧","短剧新番计划","新番计划","ai","脑洞","救赎",
        "红果短剧","红果免费短剧","推荐","热门","好剧"
    )
    private fun normalize(value:String)=value.replace(Regex("\\s+")," ").trim()
    private fun isAdvertisement(value:String)=adPhrases.any { value.contains(it,ignoreCase=true) }
    private fun cleanTitle(value:String):String {
        return normalize(value)
            .trim('《','》','【','】','〖','〗','：',':','，',',','。','！','!','？','?','…','·','-')
            .replace(Regex("^[\\s:：，,。！!]+"),"")
            .replace(Regex("[\\s…]+$"),"")
            .trim()
    }
    /**
     * 提取多个候选，而不是猜定整段正文就是剧名：
     * 明确书名号 > 正文标题候选 > 有辨识度的话题标签。
     * 作者括号、营销广告、分享模板永远不作为剧名候选。
     */
    private fun extractDouyinTitles(block:String):List<String> {
        var text=normalize(block)
            .replace(Regex("^\\d+(?:\\.\\d+)?\\s*"),"")
            .replace(Regex("^复制打开抖音[，,、：: ]*"),"")
            .replace(Regex("^看看[，,、：: ]*"),"")
            .replace(Regex("^打开抖音[，,、：: ]*"),"")
            .trim()
        if(text.isBlank()) return emptyList()

        explicitTitle.find(text)?.let { match ->
            val explicit=cleanTitle(match.groupValues[1])
            if(explicit.length>=2 && !isAdvertisement(explicit)) return listOf(explicit)
        }

        val bracket=bracketTitle.find(text)
        if(bracket!=null) {
            val bracketValue=cleanTitle(bracket.groupValues[1])
            if(authorWorkSuffix.containsMatchIn(bracketValue)) {
                // 【作者的作品】是作者标签，必须剥离。
                text=text.substring(bracket.range.last+1).trim()
            } else if(bracketValue.isNotBlank() && !isAdvertisement(bracketValue)) {
                // 非作者括号内容可能是真正标题，作为最高优先级候选。
                return listOf(bracketValue)
            }
        }

        // URL 之前的文案才是标题来源；清除分享模板和口令残留。
        text=urlToken.replace(text," ")
        text=normalize(text)
        val tags=hashtag.findAll(text).map { cleanTitle(it.groupValues[1]) }.toList()
        var body=text.substringBefore('#').trim()
        body=body.replace(Regex("^(?:短剧|漫剧)[：:，,、 ]*"),"").trim()

        val candidates=linkedSetOf<String>()
        if(body.isNotBlank() && !isAdvertisement(body)) {
            // 只在明确的剧情介绍引导词前截断；不按空格或逗号粗暴截断标题。
            body=descriptionStart.split(body,limit=2).firstOrNull().orEmpty()
            val candidate=cleanTitle(body)
            if(candidate.length>=2 && !isAdvertisement(candidate) &&
                !authorWorkSuffix.containsMatchIn(candidate)) candidates+=candidate
        }

        // 话题可能藏有剧名（如 #难逃霖网），但通用分类标签不参与搜索。
        tags.forEach { tag ->
            val normalizedTag=tag.lowercase(Locale.ROOT)
            if(tag.length>=3 && normalizedTag !in genericTags &&
                !isAdvertisement(tag) && !authorWorkSuffix.containsMatchIn(tag) &&
                !tag.startsWith("抖音") && !tag.startsWith("红果")) {
                candidates+=tag
            }
        }
        return candidates.toList()
    }
    private fun classifyUrl(rawUrl:String):String? {
        val candidate=rawUrl.trim().trimEnd('！','!','。','，',',','；',';','）',')','】',']','>','"','\'')
        val uri=runCatching { URI(candidate) }.getOrNull() ?: return null
        if(!uri.scheme.equals("https",true) || uri.rawUserInfo!=null || uri.port !in listOf(-1,443)) return null
        val host=uri.host?.lowercase(Locale.ROOT)?.trimEnd('.') ?: return null
        val path=uri.path.orEmpty()
        return when {
            (host=="novelquickapp.com" || host=="www.novelquickapp.com") &&
                Regex("^/s/[A-Za-z0-9_-]+/?$").matches(path) -> "红果"
            (host=="hongguoduanju.com" || host=="www.hongguoduanju.com") &&
                Regex("^/player/\\d{1,30}(?:/\\d{1,30})?/?$").matches(path) -> "红果"
            host=="v.douyin.com" && Regex("^/[A-Za-z0-9_-]+/?$").matches(path) -> "抖音"
            (host=="www.douyin.com" || host=="www.iesdouyin.com") && Regex("^/(?:video|share/video)/\\d{8,25}/?$").matches(path) -> "抖音"
            host=="www.iesdouyin.com" && Regex("^/share/playlet/detail/\\d{8,25}/?$").matches(path) -> "抖音短剧"
            else -> null
        }
    }
    fun parse(raw:String):List<PushShareItem> {
        val result=mutableListOf<PushShareItem>()
        val redTitles=redTitle.findAll(raw).map { it.groupValues[1].trim() }.toList()
        val classified=urlToken.findAll(raw).mapNotNull { match ->
            val url=match.value.trimEnd('！','!','。','，',',','；',';','）',')','】',']','>','"','\'')
            classifyUrl(url)?.let { Triple(match.range.first,url,it) }
        }.toList()
        val redLinks=classified.filter { it.third=="红果" }
        redLinks.forEachIndexed { i,item -> result+=PushShareItem(redTitles.getOrNull(i).orEmpty(),item.second,"红果") }
        if(redLinks.isEmpty()) redTitles.forEach { result+=PushShareItem(it,"","红果") }
        classified.filter { it.third=="抖音" || it.third=="抖音短剧" }.forEach { item ->
            val marker=raw.lastIndexOf("复制打开抖音",item.first)
            val lineStart=if(marker<0) 0 else (raw.lastIndexOf('\n',marker).takeIf { it>=0 }?.plus(1) ?: 0)
            val block=raw.substring(lineStart,item.first)
            val titles=extractDouyinTitles(block)
            // 即使分享文案没有可用标题，也保留抖音链接，让 Feed 先尝试精确识别。
            result+=PushShareItem(titles.firstOrNull().orEmpty(),item.second,item.third,titles)
        }
        return result.distinctBy { it.source+"|"+it.url+"|"+it.title }
    }
}
/** 抖音分享短链解析：短链重定向提取 aweme_id，再用移动端 Feed 精确匹配作品并读取短剧信息。 */
internal data class DouyinShareInfo(
    val awemeId:String,
    val title:String,
    val author:String,
    val seriesId:String?,
    val totalEpisodes:Int?,
    val updatedEpisodes:Int?
)
internal object DouyinShareResolver {
    /**
     * api5-normal-c-hl.amemv.com 在部分网络环境下会被系统 DNS 污染为 127.0.0.1。
     * 这里仅在该节点系统连接失败时，递归查询公共 DoH 的 CNAME/A/AAAA，
     * 再通过 OkHttp 自定义 Dns 连接公网 IP；请求 URL 主机名不变，因此 TLS SNI、
     * 证书校验和 HTTP Host 仍然使用 api5-normal-c-hl.amemv.com。
     */
    private fun isPublicAddress(address:InetAddress):Boolean {
        if(address.isAnyLocalAddress || address.isLoopbackAddress ||
            address.isLinkLocalAddress || address.isSiteLocalAddress ||
            address.isMulticastAddress) return false
        val bytes=address.address
        if(bytes.size==4) {
            val a=bytes[0].toInt() and 255
            val b=bytes[1].toInt() and 255
            val c=bytes[2].toInt() and 255
            if(a==0 || a==10 || a==127 || a>=224) return false
            if(a==100 && b in 64..127) return false
            if(a==169 && b==254) return false
            if(a==172 && b in 16..31) return false
            if(a==192 && (b==168 || (b==0 && c==0))) return false
            if(a==198 && b in 18..19) return false
            if(a==198 && b==51 && c==100) return false
            if(a==203 && b==0 && c==113) return false
        } else if(bytes.size==16) {
            val first=bytes[0].toInt() and 255
            val second=bytes[1].toInt() and 255
            if((first and 0xfe)==0xfc) return false
            if(first==0x20 && second==0x01 &&
                (bytes[2].toInt() and 255)==0x0d &&
                (bytes[3].toInt() and 255)==0xb8) return false
        }
        return true
    }
    private fun publicDnsAddresses(host:String):List<InetAddress> {
        val resolvers=listOf(
            "https://cloudflare-dns.com/dns-query?",
            "https://dns.google/resolve?"
        )
        val addresses=linkedSetOf<String>()
        resolvers.forEach { base ->
            val pending=java.util.ArrayDeque<Pair<String,Int>>()
            val visited=linkedSetOf<String>()
            pending.add(host to 0)
            while(pending.isNotEmpty() && visited.size<12) {
                val (name,depth)=pending.removeFirst()
                val normalized=name.trimEnd('.').lowercase(Locale.ROOT)
                if(!visited.add(normalized)) continue
                listOf(1,28,5).forEach { type ->
                    val query=base+"name="+java.net.URLEncoder.encode(normalized,"UTF-8")+"&type="+type
                    val connection=(URL(query).openConnection() as HttpURLConnection).apply {
                        connectTimeout=5000
                        readTimeout=6000
                        requestMethod="GET"
                        setRequestProperty("Accept","application/dns-json")
                        setRequestProperty("User-Agent","HongGuoTV/1.0 DNS-Fallback")
                    }
                    try {
                        if(connection.responseCode !in 200..299) return@forEach
                        val root=JSONObject(connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
                        if(root.optInt("Status",-1)!=0) return@forEach
                        val answers=root.optJSONArray("Answer") ?: return@forEach
                        for(index in 0 until answers.length()) {
                            val answer=answers.optJSONObject(index) ?: continue
                            when(answer.optInt("type")) {
                                1,28 -> {
                                    val raw=answer.optString("data").trim()
                                    val ip=runCatching { InetAddress.getByName(raw) }.getOrNull()
                                    if(ip!=null && isPublicAddress(ip)) addresses.add(raw)
                                }
                                5 -> {
                                    val alias=answer.optString("data").trim().trimEnd('.')
                                    if(alias.isNotBlank() && depth<8 &&
                                        alias.lowercase(Locale.ROOT) !in visited) pending.add(alias to depth+1)
                                }
                            }
                        }
                    } catch(_:Exception) {
                        // 单个 DNS 服务或记录类型失败时，继续尝试其他记录和备用解析器。
                    } finally {
                        connection.disconnect()
                    }
                }
            }
        }
        return addresses.mapNotNull { raw ->
            runCatching { InetAddress.getByName(raw) }.getOrNull()?.takeIf { isPublicAddress(it) }
        }
    }
    private fun feedWithPublicDns(awemeId:String,host:String):String {
        val addresses=publicDnsAddresses(host)
        if(addresses.isEmpty()) throw java.io.IOException("公共 DNS 未解析到 $host 的公网 A/AAAA 地址")
        val endpoint="https://$host/aweme/v1/feed/?aweme_id=$awemeId&aid=1128"
        val client=okhttp3.OkHttpClient.Builder()
            .dns(object : okhttp3.Dns {
                override fun lookup(hostname: String): List<InetAddress> =
                    if(hostname.equals(host,true)) addresses
                    else okhttp3.Dns.SYSTEM.lookup(hostname)
            })
            .connectTimeout(8,java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(10,java.util.concurrent.TimeUnit.SECONDS)
            .build()
        val request=okhttp3.Request.Builder()
            .url(endpoint)
            .header("User-Agent","com.ss.android.ugc.aweme/390000 (Linux; U; Android 13; zh_CN; Pixel 7; Build/TQ3A.230805.001; Cronet/TTNetVersion)")
            .header("Accept","application/json, text/plain, */*")
            .header("Referer","https://www.douyin.com/")
            .build()
        client.newCall(request).execute().use { response ->
            if(!response.isSuccessful) throw java.io.IOException("备用 DNS Feed HTTP ${response.code}")
            return response.body?.string().orEmpty()
        }
    }
    private val videoIdPattern=Regex("""/(?:share/video|video)/(\d{8,25})""",RegexOption.IGNORE_CASE)
    private val queryIdPattern=Regex("""(?:[?&]|^)aweme_id=(\d{8,25})""",RegexOption.IGNORE_CASE)
    private val shareTitlePattern=Regex("""[【〖]([^】〗]{1,100})[】〗]""")
    private fun text(obj:JSONObject?,key:String):String =
        obj?.opt(key)?.takeUnless { it==JSONObject.NULL }?.toString()?.trim().orEmpty().takeUnless { it=="null" }.orEmpty()
    private fun findAweme(value:Any?,awemeId:String,depth:Int=0):JSONObject? {
        if(depth>24) return null
        when(value) {
            is JSONObject -> {
                val direct=text(value,"aweme_id")
                val status=value.optJSONObject("status")
                val statistics=value.optJSONObject("statistics")
                if(direct==awemeId || text(status,"aweme_id")==awemeId || text(statistics,"aweme_id")==awemeId) return value
                val keys=value.keys()
                while(keys.hasNext()) {
                    val found=findAweme(value.opt(keys.next()),awemeId,depth+1)
                    if(found!=null) return found
                }
            }
            is JSONArray -> for(index in 0 until value.length()) {
                val found=findAweme(value.opt(index),awemeId,depth+1)
                if(found!=null) return found
            }
        }
        return null
    }
    private fun titleFrom(info:JSONObject?):String {
        if(info==null) return ""
        val direct=listOf(text(info,"series_name"),text(info,"mix_name"),text(info.optJSONObject("next_info"),"name"))
            .firstOrNull { it.isNotBlank() }.orEmpty()
        if(direct.isNotBlank()) return direct
        val share=text(info.optJSONObject("share_info"),"share_desc")
        return shareTitlePattern.find(share)?.groupValues?.getOrNull(1).orEmpty()
    }
    private fun feed(awemeId:String,host:String):DouyinShareInfo {
        val endpoint="https://$host/aweme/v1/feed/?aweme_id=$awemeId&aid=1128"
        val connection=(URL(endpoint).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects=true
            connectTimeout=8000
            readTimeout=10000
            requestMethod="GET"
            setRequestProperty("User-Agent","com.ss.android.ugc.aweme/390000 (Linux; U; Android 13; zh_CN; Pixel 7; Build/TQ3A.230805.001; Cronet/TTNetVersion)")
            setRequestProperty("Accept","application/json, text/plain, */*")
            setRequestProperty("Referer","https://www.douyin.com/")
        }
        var body:String?=null
        var networkProblem:Exception?=null
        try {
            val status=connection.responseCode
            if(status !in 200..299) throw java.io.IOException("Feed HTTP $status")
            body=connection.inputStream.use { input ->
                val out=java.io.ByteArrayOutputStream()
                val buffer=ByteArray(8192)
                while(out.size()<3*1024*1024) {
                    val n=input.read(buffer,0,minOf(buffer.size,3*1024*1024-out.size()))
                    if(n<=0) break
                    out.write(buffer,0,n)
                }
                String(out.toByteArray(),Charsets.UTF_8)
            }
        } catch(problem:Exception) {
            networkProblem=problem
        } finally { connection.disconnect() }
        if(body==null) {
            val dnsFailure=generateSequence<Throwable>(networkProblem) { it.cause }
                .any { it is java.net.UnknownHostException || it is java.net.ConnectException ||
                    it is java.net.NoRouteToHostException || it is java.net.SocketTimeoutException }
            if(host!="api5-normal-c-hl.amemv.com" || !dnsFailure) {
                throw java.io.IOException("Feed 请求失败：${networkProblem?.message ?: "未知错误"}",networkProblem)
            }
            body=try {
                feedWithPublicDns(awemeId,host)
            } catch(fallback:Exception) {
                throw java.io.IOException(
                    "系统 DNS 连接失败（${networkProblem?.message ?: "未知错误"}）；公共 DNS 备用连接也失败（${fallback.message ?: "未知错误"}）",
                    fallback
                )
            }
        }
        val root=JSONObject(body)
        val aweme=findAweme(root,awemeId) ?: throw java.io.IOException("Feed 返回成功，但没有找到匹配作品 ID")
        val series=aweme.optJSONObject("series_info")
        val mix=aweme.optJSONObject("mix_info")
        val title=(titleFrom(series).ifBlank { titleFrom(mix) })
            .removePrefix("短剧 · ").removePrefix("短剧·").removePrefix("漫剧 · ").trim()
        if(title.isBlank()) throw java.io.IOException("作品 ID 已匹配，但 Feed 中没有短剧名称")
        val stats=series?.optJSONObject("stats") ?: mix?.optJSONObject("stats")
        val author=text(aweme.optJSONObject("author"),"nickname")
        return DouyinShareInfo(
            awemeId,title,author,
            (text(series,"series_id").ifBlank { text(mix,"mix_id") }).takeIf { it.isNotBlank() },
            stats?.optInt("total_episode")?.takeIf { it>0 },
            stats?.optInt("updated_to_episode")?.takeIf { it>0 }
        )
    }
    fun resolve(shortUrl:String):DouyinShareInfo {
        val parsed=URI(shortUrl)
        val host=parsed.host?.lowercase(Locale.ROOT)?.trimEnd('.')
        val path=parsed.path.orEmpty()
        require(parsed.scheme.equals("https",true) && parsed.rawUserInfo==null &&
            parsed.port in listOf(-1,443) &&
            ((host=="v.douyin.com" && Regex("^/[A-Za-z0-9_-]+/?$").matches(path)) ||
             ((host=="www.douyin.com" || host=="www.iesdouyin.com") && Regex("^/(?:video|share/video)/\\d{8,25}/?$").matches(path)))) {
            "不是受支持的抖音分享链接"
        }
        // www.douyin.com 的作品页 URL 已包含 aweme_id，直接提取；v.douyin.com 才需要跟随短链跳转。
        val awemeId=if(host=="www.douyin.com" || host=="www.iesdouyin.com") {
            videoIdPattern.find(path)?.groupValues?.getOrNull(1)
                ?: throw java.io.IOException("抖音作品页地址中没有 aweme_id")
        } else {
            val redirect=(parsed.toURL().openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects=true
                connectTimeout=8000
                readTimeout=10000
                requestMethod="GET"
                setRequestProperty("User-Agent","Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 Chrome/126.0.0.0 Mobile Safari/537.36")
                setRequestProperty("Accept","text/html,application/xhtml+xml")
            }
            try {
                val status=redirect.responseCode
                val finalUrl=redirect.url.toString()
                if(status !in 200..399) throw java.io.IOException("抖音短链跳转 HTTP $status")
                videoIdPattern.find(finalUrl)?.groupValues?.getOrNull(1)
                    ?: queryIdPattern.find(finalUrl)?.groupValues?.getOrNull(1)
                    ?: throw java.io.IOException("短链已跳转，但没有从最终地址提取到 aweme_id：$finalUrl")
            } finally { redirect.disconnect() }
        }
        var last:Exception?=null
        listOf("aweme.snssdk.com","api5-normal-c-hl.amemv.com").forEach { host ->
            if(Thread.currentThread().isInterrupted) throw InterruptedException("抖音分享识别已取消")
            try { return feed(awemeId,host) } catch(problem:Exception) { last=problem }
        }
        throw java.io.IOException("作品 $awemeId 的 Feed 解析失败："+(last?.message ?: "未知错误"),last)
    }
}

/** 红果短链专用解析器：只跟随红果 /s/ 短链，不处理抖音链接。 */
internal object RedShareResolver {
    private val seriesPattern=Regex("""["']?(?:video_series_id|series_id|seriesId|series_id_str)["']?\s*[=:]\s*["']?(\d{1,30})""",RegexOption.IGNORE_CASE)
    private val episodePattern=Regex("""["']?(?:share_toast_vid|vid|episode_id|episodeId)["']?\s*[=:]\s*["']?(\d{1,30})""",RegexOption.IGNORE_CASE)
    private val genericVideoPattern=Regex("""["']?video_id["']?\s*[=:]\s*["']?(\d{1,30})""",RegexOption.IGNORE_CASE)
    private val playerPath=Regex("""/player/(\d{1,30})/(\d{1,30})""",RegexOption.IGNORE_CASE)
    private val playerSeriesPath=Regex("""(?:^|/)player/(\d{1,30})/?(?:[?#].*)?$""",RegexOption.IGNORE_CASE)
    private fun decoded(value:String):String {
        var current=value
        repeat(2) { current=runCatching { URLDecoder.decode(current,"UTF-8") }.getOrDefault(current) }
        return current
    }
    fun resolve(shortUrl:String):RedShareIds {
        val parsed=URI(shortUrl)
        val host=parsed.host?.lowercase(Locale.ROOT)?.trimEnd('.')
        val path=parsed.path.orEmpty()
        val novelQuickShare=(host in setOf("novelquickapp.com","www.novelquickapp.com") &&
            Regex("^/s/[A-Za-z0-9_-]+/?$").matches(path))
        val hongguoWebShare=(host in setOf("hongguoduanju.com","www.hongguoduanju.com") &&
            Regex("^/player/\\d{1,30}(?:/\\d{1,30})?/?$").matches(path))
        require(parsed.scheme.equals("https",true) && (novelQuickShare || hongguoWebShare) &&
            parsed.rawUserInfo==null && parsed.port in listOf(-1,443)) { "不是受支持的红果分享链接" }
        val connection=(URL(shortUrl).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects=true
            connectTimeout=8000
            readTimeout=10000
            requestMethod="GET"
            setRequestProperty("User-Agent","Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 Chrome/126.0.0.0 Mobile Safari/537.36")
            setRequestProperty("Accept","text/html,application/xhtml+xml")
        }
        try {
            val status=connection.responseCode
            val finalUrl=connection.url.toString()
            val stream=if(status in 200..299) connection.inputStream else connection.errorStream
            val body=stream?.use { input ->
                val out=java.io.ByteArrayOutputStream()
                val buffer=ByteArray(8192)
                while(out.size()<512*1024) {
                    val n=input.read(buffer,0,minOf(buffer.size,512*1024-out.size()))
                    if(n<=0) break
                    out.write(buffer,0,n)
                }
                String(out.toByteArray(),Charsets.UTF_8)
            }.orEmpty()
            val sources=listOf(finalUrl,decoded(finalUrl),body,decoded(body))
            var seriesId:String?=null
            var episodeId:String?=null
            var genericVideoId:String?=null
            for(source in sources) {
                if(seriesId==null) seriesId=seriesPattern.find(source)?.groupValues?.getOrNull(1)
                if(episodeId==null) episodeId=episodePattern.find(source)?.groupValues?.getOrNull(1)
                if(genericVideoId==null) genericVideoId=genericVideoPattern.find(source)?.groupValues?.getOrNull(1)
                val episodePath=playerPath.find(source)
                if(episodePath!=null) {
                    if(seriesId==null) seriesId=episodePath.groupValues[1]
                    if(episodeId==null) episodeId=episodePath.groupValues[2]
                }
                val seriesPath=playerSeriesPath.find(source)
                if(seriesPath!=null && seriesId==null) seriesId=seriesPath.groupValues[1]
            }
            // 部分红果整剧分享把 series ID 放在 schemeParams.video_id；独立 vid / share_toast_vid 才优先视为单集 ID。
            if(seriesId==null) seriesId=genericVideoId
            val videoId=episodeId ?: genericVideoId?.takeIf { it!=seriesId }
            return RedShareIds(seriesId,videoId)
        } finally {
            connection.disconnect()
        }
    }
}


/** 标题归一：只留文字与数字，全角半角、空格和标点都不参与比较。 */
internal fun normalizePushTitle(value: String): String =
    value.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }

/**
 * 候选排序分：完全同名 1000，互相包含 700，其余按字符重合比例给分。
 * 分数低于 1000 的一律不算「确定命中」，宁可让用户在弹窗里确认。
 */
internal fun pushTitleScore(title: String, queries: List<String>): Int {
    val candidate = normalizePushTitle(title)
    return queries.maxOfOrNull { query ->
        val key = normalizePushTitle(query)
        when {
            key.isBlank() -> 0
            candidate == key -> 1000
            candidate.contains(key) || key.contains(candidate) -> 700
            else -> key.toSet().intersect(candidate.toSet()).size * 100 / key.toSet().size.coerceAtLeast(1)
        }
    } ?: 0
}
