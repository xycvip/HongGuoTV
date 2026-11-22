// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import okhttp3.OkHttpClient
import okhttp3.Request
import com.hongguotv.core.ThirdSourceConfig
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

// 三方 CMS 网络访问、取消控制与短期缓存。
data class ThirdHttp(val body: String?, val error: String?)

/** 三方片源专用 HTTP 客户端：独立连接池与超时（10 秒），不走红果签名/拦截器/UA。 */
class ThirdCmsClient {
    private val http = OkHttpClient.Builder()
        .connectTimeout(ThirdCms.TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(ThirdCms.TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .build()

    // 列表/分类/详情内存缓存：10 分钟 TTL，不落盘
    private val cache = ConcurrentHashMap<String, Pair<Long, Any>>()
    private val cacheTtlNanos = TimeUnit.MINUTES.toNanos(10)
    private val maxCacheEntries = 500

    // 在途 catalog 请求登记：切换分类/片源时统一取消，避免慢请求占满线程池导致界面长时间空态
    private val catalogCalls = java.util.Collections.synchronizedList(mutableListOf<okhttp3.Call>())

    // 在途聚合搜索请求：返回搜索页或切换关键字时取消，避免旧源结果串入新关键字
    private val searchCalls = java.util.Collections.synchronizedList(mutableListOf<okhttp3.Call>())

    fun cancelCatalog() {
        synchronized(catalogCalls) { catalogCalls.forEach { it.cancel() }; catalogCalls.clear() }
    }

    fun cancelSearch() {
        synchronized(searchCalls) { searchCalls.forEach { it.cancel() }; searchCalls.clear() }
    }

    /** 单请求：区分 HTTP 错误、空响应与网络异常，给出明确原因。catalog/search 时登记可取消。 */
    fun get(url: String, catalog: Boolean = false, search: Boolean = false): ThirdHttp {
        if (!ThirdSourceConfig.validApi(url)) return ThirdHttp(null, "接口地址格式无效")
        val call = http.newCall(Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 10; TV) AppleWebKit/537.36")
            .build())
        if(catalog) catalogCalls += call
        if(search) searchCalls += call
        return try {
            call.execute().use { response ->
                if (!response.isSuccessful) return@use ThirdHttp(null, "HTTP ${response.code}")
                val body = response.body ?: return@use ThirdHttp(null, "源站返回空响应")
                if (body.contentLength() > MAX_RESPONSE_BYTES) return@use ThirdHttp(null, "源站响应过大")
                val bytes = body.byteStream().use { readBoundedResponse(it, MAX_RESPONSE_BYTES) }
                    ?: return@use ThirdHttp(null, "源站响应过大")
                if (bytes.isEmpty()) ThirdHttp(null, "源站返回空响应")
                else ThirdHttp(ThirdCms.decode(bytes), null)
            }
        } catch (problem: Exception) {
            ThirdHttp(null, when(problem) {
                is SocketTimeoutException -> "连接超时"
                is UnknownHostException -> "域名无法解析"
                else -> "连接失败（响应被中断）"
            })
        } finally {
            if(catalog) catalogCalls -= call
            if(search) searchCalls -= call
        }
    }

    /** 分类列表（ac=list 首页响应含完整 class[]；videolist 响应不返回分类）。 */
    fun categories(api: String): List<ThirdCategory> {
        val key = "$api|categories"
        @Suppress("UNCHECKED_CAST")
        (cached(key) as? List<ThirdCategory>)?.let { return it }
        val response = get(ThirdCms.apiUrl(api, listOf("ac" to "list", "pg" to "1")), catalog = true)
        val parsed = response.body?.let { body -> try { ThirdCms.parseList(body).categories } catch (_: Exception) { null } } ?: emptyList()
        store(key, parsed); return parsed
    }

    /** 分类列表与条目分页（ac=videolist，含封面与角标；ac=list 是轻量响应缺 vod_pic）。 */
    fun list(api: String, categoryId: String?, page: Int): ThirdListPage? {
        val key = "$api|list|$categoryId|$page"
        (cached(key) as? ThirdListPage)?.let { return it }
        val params = buildList {
            add("ac" to "videolist")
            if(!categoryId.isNullOrBlank()) add("t" to categoryId)
            add("pg" to page.toString())
        }
        val response = get(ThirdCms.apiUrl(api, params), catalog = true)
        val parsed = response.body?.let { body -> try { ThirdCms.parseList(body) } catch (_: Exception) { null } } ?: return null
        store(key, parsed); return parsed
    }

    /** 单条详情（ac=detail&ids=）。 */
    fun detail(api: String, id: String): ThirdDetail? {
        val key = "$api|detail|$id"
        (cached(key) as? ThirdDetail)?.let { return it }
        val response = get(ThirdCms.apiUrl(api, listOf("ac" to "detail", "ids" to id)))
        val parsed = response.body?.let { body -> try { ThirdCms.parseDetail(body) } catch (_: Exception) { null } } ?: return null
        store(key, parsed); return parsed
    }

    /** 搜索联想（ac=list&wd=，轻量响应只有片名）；接口不支持搜索或网络失败时静默返回空。 */
    fun suggest(api: String, word: String): List<String> {
        val key = "$api|suggest|$word"
        @Suppress("UNCHECKED_CAST")
        (cached(key) as? List<String>)?.let { return it }
        val response = get(ThirdCms.apiUrl(api, listOf("ac" to "list", "wd" to word, "pg" to "1")))
        val parsed = response.body?.let { body -> try { ThirdCms.parseSuggestions(body) } catch(_: Exception) { null } } ?: emptyList()
        store(key, parsed); return parsed
    }

    /** 片源内搜索分页（ac=detail&wd=，结果直接含封面与播放地址）。
     *  部分源未开启搜索时会返回「暂不支持搜索」等纯文本，这里给出明确原因而不是笼统的加载失败。 */
    fun searchPage(api: String, word: String, page: Int): ThirdSearchResult {
        val key = "$api|search|$word|$page"
        (cached(key) as? ThirdSearchPage)?.let { return ThirdSearchResult(it, null) }
        val response = get(ThirdCms.apiUrl(api, listOf("ac" to "detail", "wd" to word, "pg" to page.toString())), search = true)
        response.body ?: return ThirdSearchResult(null, response.error ?: "连接失败")
        val parsed = try { ThirdCms.parseDetailPage(response.body!!) } catch(_: Exception) { null }
            ?: return ThirdSearchResult(null, "该片源未开启搜索")
        store(key, parsed); return ThirdSearchResult(parsed, null)
    }

    /** 探活：HTTP 200 + JSON 含 list 字段即通过，返回条目数；失败带明确原因。 */
    fun probe(api: String): ThirdProbe {
        val response = get(ThirdCms.apiUrl(api, listOf("ac" to "list", "pg" to "1")))
        response.body ?: return ThirdProbe(false, 0, response.error ?: "连接失败")
        val total = response.body?.let { body -> try { ThirdCms.probeTotal(body) } catch (_: Exception) { null } }
            ?: return ThirdProbe(false, 0, "非 CMS 接口（缺少 list 字段）")
        return ThirdProbe(true, total, null)
    }

    /** 拉取并提取 TVBox 明文配置；parse 为 null 时 error 给出原因（含加密配置提示）。 */
    fun importTvbox(url: String): Pair<ThirdTvboxParse?, String?> {
        val response = get(url)
        response.body ?: return null to "无法获取配置：${response.error ?: "未知错误"}。请先确认该链接能在手机浏览器里直接打开"
        // 部分配置带 UTF-8 BOM，直接交给 JSONObject 会解析失败
        val body = response.body!!.trim().removePrefix("\uFEFF")
        return try {
            ThirdCms.parseTvboxConfig(body) to null
        } catch (_: Exception) {
            null to "该配置不支持导入（仅支持明文 JSON，加密或二进制配置无法解析）"
        }
    }

    /** 释放独立连接池与线程池。evictAll 关闭 SSL socket 会触发网络 IO，必须在后台线程执行，
     *  否则 onDestroy（主线程）调用时 StrictMode 抛 NetworkOnMainThreadException。 */
    fun shutdown() {
        // 同步 execute() 请求也登记在 OkHttp Dispatcher 的 runningSyncCalls 中；
        // cancelAll 能覆盖详情、探活、导入、联想和测速，不只取消 catalog/search。
        http.dispatcher.cancelAll()
        cache.clear()
        Thread({
            http.dispatcher.executorService.shutdown()
            http.connectionPool.evictAll()
        }, "thirdcms-shutdown").start()
    }

    private fun cached(key: String): Any? {
        val hit = cache[key] ?: return null
        return if (System.nanoTime() - hit.first <= cacheTtlNanos) hit.second
            else { cache.remove(key, hit); null }
    }

    private fun store(key: String, value: Any) {
        val now = System.nanoTime()
        synchronized(cache) {
            cache.entries.removeIf { now - it.value.first > cacheTtlNanos }
            while (cache.size >= maxCacheEntries && !cache.containsKey(key)) {
                val oldest = cache.keys.firstOrNull() ?: break
                cache.remove(oldest)
            }
            cache[key] = now to value
        }
    }

    companion object {
        internal const val MAX_RESPONSE_BYTES = 8 * 1024 * 1024

        internal fun readBoundedResponse(input: InputStream, maxBytes: Int): ByteArray? {
            require(maxBytes >= 0)
            val output = ByteArrayOutputStream(minOf(maxBytes, 8192))
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) return output.toByteArray()
                if (count == 0) continue
                if (output.size().toLong() + count > maxBytes) return null
                output.write(buffer, 0, count)
            }
        }
    }
}
