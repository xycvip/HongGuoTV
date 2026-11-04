// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import org.json.JSONObject
import com.hongguotv.core.ThirdSourceConfig
import java.net.URLEncoder

/** CMS v10 协议与三方配置解析（纯函数，可单测）。
 *  播放地址格式实测：vod_play_from=`liangzi$$$lzm3u8`，vod_play_url=`第01集$https://a#第02集$https://b$$$第01集$https://c`。 */
object ThirdCms {
    const val PAGE_LIMIT = 20          // 常规单页条目数，page/pagecount 缺失时按「返回条数 < 此值」判尾页
    const val MAX_SOURCES = 30         // 源列表上限
    const val SUGGEST_LIMIT = 10       // 联想词条数上限
    const val TIMEOUT_SECONDS = 10L

    /** 聚合搜索去重键：片名去掉《》与空白、统一大小写，再拼年份；年份缺失时只按片名。 */
    fun searchKey(name: String, year: String?): String =
        name.replace("《", "").replace("》", "").replace(Regex("\\s+"), "").lowercase() +
            "|" + year?.trim().orEmpty()

    fun episodeCount(detail: ThirdDetail): Int = detail.groups.sumOf { it.second.size }

    /** 聚合搜索去重：同键优先保留当前源，其次保留集数更多者；保持首次出现顺序。 */
    fun dedupeHits(hits: List<ThirdSearchHit>, preferredApi: String?): List<ThirdSearchHit> {
        val chosen = LinkedHashMap<String, ThirdSearchHit>()
        hits.forEach { hit ->
            val key = searchKey(hit.detail.video.name, hit.detail.year)
            val prior = chosen[key]
            chosen[key] = when {
                prior == null -> hit
                prior.source.api == preferredApi -> prior
                hit.source.api == preferredApi -> hit
                episodeCount(hit.detail) > episodeCount(prior.detail) -> hit
                else -> prior
            }
        }
        return chosen.values.toList()
    }

    /** 接口地址拼参数：源地址可能自带 query，按 ?/& 正确衔接。 */
    fun apiUrl(api: String, params: List<Pair<String, String>>): String {
        val query = params.joinToString("&") { (key, value) -> "$key=${URLEncoder.encode(value, "UTF-8")}" }
        return if(api.contains('?')) "$api&$query" else "$api?$query"
    }

    /** 编码兜底：UTF-8 解出替换符（U+FFFD）说明源站为 GBK，按 GBK 重试。 */
    fun decode(bytes: ByteArray): String {
        val utf8 = String(bytes, Charsets.UTF_8)
        return if(utf8.contains('\uFFFD')) String(bytes, charset("GBK")) else utf8
    }

    /** 列表解析（ac=videolist）：class[] 分类 + list[] 条目；单条字段缺失跳过不崩溃。 */
    fun parseList(body: String): ThirdListPage {
        val json = JSONObject(body)
        val categories = mutableListOf<ThirdCategory>()
        json.optJSONArray("class")?.let { array ->
            for(index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val id = item.optString("type_id").trim(); val name = item.optString("type_name").trim()
                if(id.isNotEmpty() && name.isNotEmpty()) categories += ThirdCategory(id, name)
            }
        }
        val videos = mutableListOf<ThirdVideo>()
        json.optJSONArray("list")?.let { array ->
            for(index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val id = item.optString("vod_id").trim(); val name = item.optString("vod_name").trim()
                if(id.isEmpty() || name.isEmpty()) continue
                videos += ThirdVideo(id, name, item.optString("vod_pic").trim(), item.optString("vod_remarks").trim())
            }
        }
        val page = json.optInt("page", 1); val pageCount = json.optInt("pagecount", 0)
        // 分页容错：page/pagecount 缺失（0）时按「返回条数 < 单页上限」判断尾页
        val hasMore = if(pageCount > 0) page < pageCount else videos.size >= PAGE_LIMIT
        return ThirdListPage(categories, videos, page, pageCount, hasMore && videos.isNotEmpty(), json.optInt("total", videos.size))
    }

    /** 详情解析（ac=detail&ids=）：取 list[] 首条完整字段。 */
    fun parseDetail(body: String): ThirdDetail? = parseDetailList(body).firstOrNull()

    /** 详情列表解析（ac=detail&wd= 搜索直接含播放地址，点击即可播）。 */
    fun parseDetailList(body: String): List<ThirdDetail> {
        val json = JSONObject(body)
        val array = json.optJSONArray("list") ?: return emptyList()
        val details = mutableListOf<ThirdDetail>()
        for(index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val video = ThirdVideo(
                item.optString("vod_id").trim(), item.optString("vod_name").trim(),
                item.optString("vod_pic").trim(), item.optString("vod_remarks").trim())
            if(video.id.isEmpty() || video.name.isEmpty()) continue
            // 简介去 HTML 标签并解码常见实体，纯文本展示
            val synopsis = item.optString("vod_content")
                .replace(Regex("<[^>]+>"), "")
                .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<")
                .replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
                .trim()
            details += ThirdDetail(
                video,
                item.optString("type_name").trim().ifEmpty { null },
                item.optString("vod_year").trim().ifEmpty { null },
                item.optString("vod_area").trim().ifEmpty { null },
                synopsis.ifEmpty { null },
                parsePlayGroups(item.optString("vod_play_from"), item.optString("vod_play_url"))
            )
        }
        return details
    }

    /** 搜索联想解析（ac=list&wd=）：响应只含片名等轻量字段，去重后最多返回 SUGGEST_LIMIT 条。 */
    fun parseSuggestions(body: String): List<String> {
        val json = JSONObject(body)
        val array = json.optJSONArray("list") ?: return emptyList()
        val names = LinkedHashSet<String>()
        for(index in 0 until array.length()) {
            // 部分 CMS 在 vod_name 里嵌入 <em> 高亮标签与 HTML 实体，剥离后再展示与搜索
            val name = array.optJSONObject(index)?.optString("vod_name").orEmpty()
                .replace(Regex("<[^>]+>"), "")
                .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'")
                .trim()
            if(name.isNotEmpty()) names += name
            if(names.size >= SUGGEST_LIMIT) break
        }
        return names.toList()
    }

    /** 搜索结果分页解析（ac=detail&wd=）：条目带封面与播放地址，分页字段与列表一致。 */
    fun parseDetailPage(body: String): ThirdSearchPage {
        val details = parseDetailList(body)
        val json = JSONObject(body)
        val page = json.optInt("page", 1)
        val pageCount = json.optInt("pagecount", 0)
        val hasMore = if(pageCount > 0) page < pageCount else details.size >= PAGE_LIMIT
        return ThirdSearchPage(details, page, hasMore && details.isNotEmpty(), json.optInt("total", details.size))
    }

    /** 播放地址三层解析：$$$ 分组 → # 分集 → 名称$地址。
     *  容错：空段跳过、无 $ 的整段视为地址、组名缺失兜底「线路 N」。 */
    fun parsePlayGroups(from: String, urls: String): List<Pair<String, List<ThirdEpisode>>> {
        val names = from.split("$$$").map { it.trim() }
        val groups = mutableListOf<Pair<String, List<ThirdEpisode>>>()
        urls.split("$$$").forEachIndexed { groupIndex, rawGroup ->
            var counter = 0
            val episodes = rawGroup.split("#").mapNotNull { raw ->
                val segment = raw.trim()
                if(segment.isEmpty()) return@mapNotNull null
                counter += 1
                val sep = segment.indexOf('$')
                when {
                    sep > 0 -> ThirdEpisode(segment.substring(0, sep).trim().ifEmpty { "第${counter}集" }, segment.substring(sep + 1).trim())
                    sep == 0 -> ThirdEpisode("第${counter}集", segment.substring(1).trim())
                    else -> ThirdEpisode("第${counter}集", segment)
                }
            }.filter { it.url.isNotEmpty() }
            if(episodes.isNotEmpty()) {
                val name = names.getOrNull(groupIndex)?.ifEmpty { null } ?: "线路${groupIndex + 1}"
                groups += name to episodes
            }
        }
        return groups
    }

    /** 默认播放组：m3u8 组优先（名字或首集地址含 m3u8），否则取第一组。 */
    fun defaultGroupIndex(groups: List<Pair<String, List<ThirdEpisode>>>): Int =
        groups.indexOfFirst { (_, episodes) -> episodes.any { it.url.contains("m3u8", true) } }
            .let { if(it >= 0) it else 0 }

    /** 探活判定：JSON 含 list 字段即通过；返回条目总数用于源管理展示。 */
    fun probeTotal(body: String): Int? {
        val json = JSONObject(body)
        if(!json.has("list")) return null
        val total = json.optInt("total", -1)
        return if(total >= 0) total else json.optJSONArray("list")?.length() ?: 0
    }

    /** TVBox 明文配置提取：只取 type=1 且 api 为 http(s) 链接的 CMS 源；
     *  spider jar（type=0）与加密 app 源（type=3/4）自动跳过并计数。 */
    fun parseTvboxConfig(body: String): ThirdTvboxParse {
        val sites = JSONObject(body).optJSONArray("sites") ?: return ThirdTvboxParse(emptyList(), 0)
        val candidates = mutableListOf<ThirdTvboxSite>()
        var skipped = 0
        for(index in 0 until sites.length()) {
            val item = sites.optJSONObject(index) ?: continue
            val name = item.optString("name").trim()
            val api = item.optString("api").trim()
            val type = item.optInt("type", -1)
            if(type == 1 && ThirdSourceConfig.validApi(api)) candidates += ThirdTvboxSite(name.ifEmpty { "未命名源" }, api)
            else skipped += 1
        }
        return ThirdTvboxParse(candidates, skipped)
    }
}

/** 单次请求结果：body 为解码后的响应体，error 为失败原因（两者互斥）。 */
