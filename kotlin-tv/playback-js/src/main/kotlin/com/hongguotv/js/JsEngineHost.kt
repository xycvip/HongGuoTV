// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.js

import com.hongguotv.core.RequestScope
import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.define
import com.dokar.quickjs.binding.function
import com.dokar.quickjs.binding.toJsObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.io.InterruptedIOException
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * main.js 的宿主桥接实现（对应 bridge.js 的 host.* 契约）。
 *
 * 关键约定（改之前先读 /workspace/hongguotv-js/src/bridge.js 头部注释）：
 *  - 所有绑定都是**同步** function：host.fetch 抛出的异常会被 bridge 转成
 *    Promise.reject，JS 侧 await fetch() 正常捕获，与桌面端语义一致；
 *  - 二进制一律走 UInt8Array ↔ UByteArray（quickjs-kt 把 ByteArray 映射成
 *    Int8Array，直接返回 ByteArray 会让 JS 侧索引出负数）；
 *  - fetch 的 spec 由 bridge 构造：{method,url,headers,body,followRedirects:false,
 *    anyStatus:true,limit,timeoutMs?}，任何状态码都必须返回，不抛业务异常。
 */
class JsEngineHost(
    private val http: OkHttpClient,
    private val store: JsKeyValueStore,
    private val onLog: (String) -> Unit = {},
) {
    fun QuickJs.install() {
        define("host") {
            function("fetch") { args -> fetchSpec(args.firstOrNull()) }
            function("utf8Encode") { args ->
                (args.firstOrNull()?.toString() ?: "").toByteArray(Charsets.UTF_8).toUByteArray()
            }
            function("utf8Decode") { args ->
                args.firstOrNull().asUByteArray().toByteArray().toString(Charsets.UTF_8)
            }
            function("base64Encode") { args ->
                Base64.getEncoder().encodeToString(args.firstOrNull().asUByteArray().toByteArray())
            }
            function("base64Decode") { args ->
                Base64.getDecoder().decode(args.firstOrNull()?.toString() ?: "").toUByteArray()
            }
            function("randomBytes") { args ->
                val size = (args.firstOrNull() as? Number)?.toInt() ?: 0
                require(size in 0..65536) { "randomBytes 长度越界" }
                ByteArray(size).also { SecureRandom().nextBytes(it) }.toUByteArray()
            }
            function("cacheGet") { args ->
                store.get(args.firstOrNull()?.toString() ?: "")
            }
            function("cacheSet") { args ->
                val key = args.firstOrNull()?.toString() ?: return@function null
                val value = args.getOrNull(1)?.toString()
                val ttl = (args.getOrNull(2) as? Number)?.toLong()
                store.set(key, value, ttl)
                null
            }
        }
    }

    /**
     * 在 main.js evaluate 之前注入设备身份持久化 bootstrap（suspend：evaluate 是 suspend）。
     *  - 载入上次注册结果写回 globalThis.__hongguoCachedDevice，让 vendor 的 Ot() 命中缓存分支，
     *    跳过每次新建 runtime 都要走的 device_register 网络往返（延迟 + 风控双重收益）；
     *  - 补上 JS 侧缺失的 __native_save_device 桥接，Ot() 注册成功后把新身份回写 store。
     * 任何注入失败都不该让 runtime 创建失败，用 runCatching 兜底退回原逻辑。
     */
    suspend fun QuickJs.injectDeviceIdentity() {
        store.get(DEVICE_IDENTITY_KEY)?.let { cached ->
            runCatching { evaluate<Any?>("globalThis.__hongguoCachedDevice = $cached;") }
        }
        runCatching {
            evaluate<Any?>(
                "globalThis.__native_save_device = function(json) { " +
                    "host.cacheSet('$DEVICE_IDENTITY_KEY', json); " +
                    "};"
            )
        }
    }

    // ------------------------------------------------------------------ fetch --

    private fun fetchSpec(rawSpec: Any?): Map<String, Any?> {
        val spec = rawSpec as? Map<*, *> ?: throw IOException("host.fetch 需要 spec 对象")
        val method = spec["method"]?.toString()?.uppercase() ?: "GET"
        val url = spec["url"]?.toString()?.trim().orEmpty()
        if (url.isEmpty()) throw IOException("host.fetch 缺少 url")
        if (!isAllowedScriptUrl(url)) throw IOException("host.fetch 拒绝非公网 HTTPS 地址")
        val headers = linkedMapOf<String, String>()
        (spec["headers"] as? Map<*, *>)?.forEach { (key, value) ->
            if (key != null && value != null) headers[key.toString()] = value.toString()
        }
        val body = spec["body"]?.toString() ?: ""
        val timeoutMs = (spec["timeoutMs"] as? Number)?.toLong() ?: 20_000L
        // 默认 24 MiB，硬上限 32 MiB，避免脚本请求把内存限额抬到不可控。
        val limit = ((spec["limit"] as? Number)?.toLong() ?: (24L * 1024 * 1024))
            .coerceIn(1L, 32L * 1024 * 1024)
        RequestScope.ensureActive()
        val client = http.newBuilder()
            .callTimeout(timeoutMs.coerceIn(1_000L, 60_000L), TimeUnit.MILLISECONDS)
            .followRedirects(false)
            .build()
        val builder = Request.Builder().url(url)
        headers.forEach { (key, value) -> builder.header(key, value) }
        val contentType = headers.entries.firstOrNull { it.key.equals("Content-Type", true) }
            ?.value?.toMediaType()
        val hasBody = body.isNotEmpty() || method == "POST" || method == "PUT" || method == "PATCH"
        builder.method(method, if (hasBody) body.toRequestBody(contentType) else null)
        val result: Map<String, Any?> = RequestScope.execute(client.newCall(builder.build())) { response ->
            val responseBody = response.body
            val contentLength = responseBody?.contentLength() ?: 0L
            if (contentLength > limit) throw IOException("响应体超过 limit（$limit 字节）")
            val bytes = if (responseBody == null) {
                ByteArray(0)
            } else {
                // 不使用 bytes() 整体读入：按块读取，最多触碰 limit + 1 字节。
                val output = java.io.ByteArrayOutputStream(minOf(contentLength, limit).toInt().coerceAtLeast(0))
                val buffer = ByteArray(8 * 1024)
                var total = 0L
                responseBody.byteStream().use { input ->
                    while (true) {
                        val allowed = (limit - total + 1L).coerceAtMost(buffer.size.toLong()).toInt()
                        val count = input.read(buffer, 0, allowed)
                        if (count < 0) break
                        if (count == 0) continue
                        total += count
                        if (total > limit) throw IOException("响应体超过 limit（$limit 字节）")
                        output.write(buffer, 0, count)
                    }
                }
                output.toByteArray()
            }
            val outHeaders = linkedMapOf<String, String>()
            response.headers.toMultimap().forEach { (name, values) ->
                if (values.isNotEmpty()) {
                    outHeaders[name.lowercase()] = values.joinToString(", ")
                }
            }
            // 返回值必须包成 JsObject：quickjs-kt 的 JNI 转换器只把 JsObject 映射成普通
            // JS 对象，裸 Map 会变成 JS Map（Object.keys 为空，属性全读不到）。
            mapOf(
                "status" to response.code,
                "headers" to outHeaders.toJsObject(),
                "body" to String(bytes, Charsets.UTF_8),
                "url" to response.request.url.toString(),
            ).toJsObject()
        }
        return result
    }

    private fun Any?.asUByteArray(): UByteArray = when (this) {
        is UByteArray -> this
        is ByteArray -> this.toUByteArray()
        is List<*> -> UByteArray(size) { (this[it] as? Number)?.toInt()?.toUByte() ?: 0u }
        else -> UByteArray(0)
    }

    companion object {
        /** 设备身份在 KV store 里的键；设置页「重置设备身份」也是清这个键。 */
        const val DEVICE_IDENTITY_KEY = "__device_identity"

        /** 脚本只应访问公网 HTTPS；阻断 localhost 与内网地址，防脚本被篡改后打内网。 */
        fun isAllowedScriptUrl(url: String): Boolean {
            val uri = runCatching { java.net.URI(url) }.getOrElse { return false }
            if (uri.scheme?.lowercase() != "https") return false
            if (uri.userInfo != null) return false
            val host = uri.host?.lowercase() ?: return false
            if (host == "localhost" || host.endsWith(".localhost")) return false
            // 内网 IPv4 字面量（DNS 解析由系统网络层负责，这里只挡字面量，避免每次请求多一次 DNS）。
            val ipv4 = Regex("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$").find(host) ?: return true
            val octets = ipv4.groupValues.drop(1).map { it.toInt() }
            if (octets.any { it > 255 }) return false
            val (a, b) = octets
            return when {
                a == 127 -> false // loopback
                a == 10 -> false // 10.0.0.0/8
                a == 192 && b == 168 -> false // 192.168.0.0/16
                a == 172 && b in 16..31 -> false // 172.16.0.0/12
                a == 169 && b == 254 -> false // link-local
                a == 0 -> false
                else -> true
            }
        }
    }
}
