package com.hongguotv.nativeapp

import android.content.Context
import com.dokar.quickjs.binding.JsObject
import com.hongguotv.quickjs.QuickJsHostAdapter
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Android implementation of the host contract consumed by RedGuoAPI/modules/hongguo/main.js.
 * All calls are synchronous because the module itself uses a synchronous host API.
 * Call the JS runtime on Dispatchers.IO so network and crypto work never block the UI thread.
 */
internal class HongguoHostAdapter(context: Context) : QuickJsHostAdapter {
    private val preferences = context.applicationContext.getSharedPreferences("hongguo_js_host", Context.MODE_PRIVATE)
    private val random = SecureRandom()

    override val source: String = "hongguo"

    override fun invoke(name: String, args: Array<Any?>): Any? {
        return when (name) {
            "fetch" -> fetch(objectArg(args, 0))
            "fetchText" -> fetchText(objectArg(args, 0))
            "sleep" -> {
                val millis = (args.getOrNull(0) as? Number)?.toLong() ?: 0L
                Thread.sleep(millis.coerceIn(0L, 60_000L))
                null
            }
            "reserve" -> 0L
            "finishMedia" -> objectArg(args, 0)
            "catalogContext" -> mapOf("more" to false, "background" to false)
            "isKnown" -> false
            "reportProgress" -> null
            "cacheGet" -> cacheGet(args.getOrNull(0)?.toString().orEmpty())
            "cacheSet" -> cacheSet(args)
            "dramaId" -> "hongguo:" + args.getOrNull(0)?.toString().orEmpty().trim()
            "chapterId" -> "hongguo:" + args.getOrNull(0)?.toString().orEmpty().trim() + ":" + args.getOrNull(1)?.toString().orEmpty().trim()
            "exclusive" -> null // The module wrapper replaces this with a JS callback passthrough; one QuickJS context serializes calls.
            "maxPages" -> 30
            "retries" -> 2
            "parseUrl" -> parseUrl(args.getOrNull(0)?.toString().orEmpty())
            "parseHtml" -> parseHtml(args.getOrNull(0)?.toString().orEmpty())
            "preserveIntegers" -> preserveIntegers(args.getOrNull(0)?.toString().orEmpty())
            "randomBytes" -> {
                val size = (args.getOrNull(0) as? Number)?.toInt() ?: 0
                require(size in 0..1024) { "随机字节数无效" }
                ByteArray(size).also(random::nextBytes)
            }
            "utf8Encode" -> args.getOrNull(0)?.toString().orEmpty().toByteArray(StandardCharsets.UTF_8)
            "utf8Decode" -> bytes(args.getOrNull(0)).toString(StandardCharsets.UTF_8)
            "hexEncode" -> bytes(args.getOrNull(0)).joinToString("") { "%02x".format(it.toInt() and 0xff) }
            "hexDecode" -> decodeHex(args.getOrNull(0)?.toString().orEmpty())
            "base64Encode" -> Base64.getEncoder().encodeToString(bytes(args.getOrNull(0)))
            "base64Decode" -> decodeBase64(args.getOrNull(0)?.toString().orEmpty())
            "md5" -> digest("MD5", args.getOrNull(0))
            "sha256" -> digest("SHA-256", args.getOrNull(0))
            "sha512" -> digest("SHA-512", args.getOrNull(0))
            "aesCbcEncrypt" -> aesCbc(Cipher.ENCRYPT_MODE, args)
            "aesCbcDecrypt" -> aesCbc(Cipher.DECRYPT_MODE, args)
            else -> error("未实现的红果 JS Host 方法：$name")
        }
    }

    private fun fetch(input: Map<String, Any?>): Map<String, Any?> {
        val url = input["url"]?.toString().orEmpty()
        require(url.startsWith("https://") || url.startsWith("http://")) { "请求地址无效" }
        val timeout = ((input["timeoutMs"] as? Number)?.toLong() ?: 15_000L).coerceIn(1_000L, 60_000L)
        val client = OkHttpClient.Builder()
            .connectTimeout(timeout, TimeUnit.MILLISECONDS)
            .readTimeout(timeout, TimeUnit.MILLISECONDS)
            .writeTimeout(timeout, TimeUnit.MILLISECONDS)
            .followRedirects(input["followRedirects"] as? Boolean ?: true)
            .followSslRedirects(input["followRedirects"] as? Boolean ?: true)
            .build()
        val builder = Request.Builder().url(url)
        (input["headers"] as? Map<*, *>)?.forEach { (key, value) ->
            if (key != null && value != null) builder.header(key.toString(), value.toString())
        }
        val method = input["method"]?.toString()?.uppercase() ?: "GET"
        val payload = input["body"]?.let(::bytes)
        val body = payload?.takeIf { method !in setOf("GET", "HEAD") }?.toRequestBody(
            ((input["headers"] as? Map<*, *>)?.entries?.firstOrNull { it.key.toString().equals("Content-Type", true) }?.value?.toString()
                ?: "application/octet-stream").toMediaType()
        )
        builder.method(method, if (method in setOf("GET", "HEAD")) null else body ?: ByteArray(0).toRequestBody(null))
        client.newCall(builder.build()).execute().use { response ->
            val data = response.body?.bytes() ?: ByteArray(0)
            val limit = ((input["limit"] as? Number)?.toLong() ?: (4L shl 20)).coerceIn(1L, 16L shl 20)
            require(data.size.toLong() <= limit) { "接口返回的数据过大" }
            val headers = response.headers.toMultimap()
            val binary = input["binary"] as? Boolean ?: false
            return mapOf(
                "status" to response.code,
                "url" to response.request.url.toString(),
                "headers" to headers,
                "body" to if (binary) data else data.toString(StandardCharsets.UTF_8),
                "blocked" to false,
                "oversized" to false,
            )
        }
    }

    private fun fetchText(input: Map<String, Any?>): Map<String, Any?> {
        val request = linkedMapOf<String, Any?>(
            "url" to input["url"],
            "method" to if (input["body"] != null) "POST" else "GET",
            "headers" to buildMap {
                input["referer"]?.toString()?.takeIf(String::isNotBlank)?.let { put("Referer", it) }
                input["userAgent"]?.toString()?.takeIf(String::isNotBlank)?.let { put("User-Agent", it) }
                input["cookie"]?.toString()?.takeIf(String::isNotBlank)?.let { put("Cookie", it) }
                if (input["noCache"] == true) put("Cache-Control", "no-cache")
            },
            "body" to input["body"],
            "timeoutMs" to 20_000,
            "limit" to (8L shl 20),
            "followRedirects" to true,
        )
        val response = fetch(request)
        val status = response["status"] as Int
        require(status in 200..299) { "网页请求失败：HTTP $status" }
        return mapOf("body" to response["body"].toString(), "url" to response["url"].toString())
    }

    private fun cacheGet(key: String): String? {
        val expires = preferences.getLong("$key.expires", 0L)
        if (expires > 0L && System.currentTimeMillis() >= expires) {
            preferences.edit().remove(key).remove("$key.expires").apply()
            return null
        }
        return preferences.getString(key, null)
    }

    private fun cacheSet(args: Array<Any?>): Any? {
        val key = args.getOrNull(0)?.toString().orEmpty()
        require(key.isNotBlank() && key.length <= 512) { "缓存键无效" }
        val value = args.getOrNull(1)
        if (value == null) {
            preferences.edit().remove(key).remove("$key.expires").apply()
        } else {
            val text = value.toString()
            require(text.length <= (1 shl 20)) { "缓存内容过大" }
            val ttl = (args.getOrNull(2) as? Number)?.toLong() ?: 0L
            preferences.edit().putString(key, text)
                .putLong("$key.expires", if (ttl > 0) System.currentTimeMillis() + ttl else 0L)
                .apply()
        }
        return null
    }

    private fun parseUrl(raw: String): Map<String, Any?> {
        val uri = URI(raw)
        return mapOf(
            "scheme" to (uri.scheme ?: ""),
            "host" to (uri.rawAuthority?.substringAfterLast("@") ?: ""),
            "hostname" to (uri.host ?: ""),
            "port" to if (uri.port >= 0) uri.port.toString() else "",
            "path" to (uri.rawPath ?: ""),
            "query" to (uri.rawQuery ?: ""),
            "fragment" to (uri.rawFragment ?: ""),
            "userInfo" to (uri.rawUserInfo != null),
            "href" to uri.toString(),
        )
    }

    private fun parseHtml(html: String): Map<String, Any?> {
        require(html.length <= (8 shl 20)) { "网页内容过大" }
        val document = Jsoup.parse(html)
        return nodeMap(document)
    }

    private fun nodeMap(node: Node): Map<String, Any?> {
        if (node is TextNode) return mapOf("text" to node.wholeText)
        val attrs = linkedMapOf<String, String>()
        node.attributes().forEach { attrs[it.key] = it.value }
        return buildMap {
            if (node.nodeName() != "#document") put("tag", node.nodeName())
            if (attrs.isNotEmpty()) put("attrs", attrs)
            val children = node.childNodes().map(::nodeMap)
            if (children.isNotEmpty()) put("children", children)
        }
    }

    private fun digest(algorithm: String, value: Any?): ByteArray =
        MessageDigest.getInstance(algorithm).digest(bytes(value))

    private fun aesCbc(mode: Int, args: Array<Any?>): ByteArray {
        val key = bytes(args.getOrNull(0))
        val iv = bytes(args.getOrNull(1))
        val data = bytes(args.getOrNull(2))
        require(key.size in setOf(16, 24, 32) && iv.size == 16 && data.size % 16 == 0) { "AES 向量或数据长度无效" }
        return Cipher.getInstance("AES/CBC/NoPadding").run {
            init(mode, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            doFinal(data)
        }
    }

    private fun bytes(value: Any?): ByteArray = when (value) {
        null -> ByteArray(0)
        is ByteArray -> value
        is String -> value.toByteArray(StandardCharsets.UTF_8)
        is JsObject -> value.values.mapNotNull { (it as? Number)?.toInt()?.toByte() }.toByteArray()
        is List<*> -> value.mapNotNull { (it as? Number)?.toInt()?.toByte() }.toByteArray()
        is Array<*> -> value.mapNotNull { (it as? Number)?.toInt()?.toByte() }.toByteArray()
        else -> error("需要字符串或字节数组")
    }

    private fun decodeHex(raw: String): ByteArray {
        val text = raw.trim()
        require(text.length % 2 == 0 && text.all { it in "0123456789abcdefABCDEF" }) { "十六进制内容无效" }
        return ByteArray(text.length / 2) { index -> text.substring(index * 2, index * 2 + 2).toInt(16).toByte() }
    }

    private fun decodeBase64(raw: String): ByteArray {
        val text = raw.trim()
        return try { Base64.getDecoder().decode(text) } catch (_: IllegalArgumentException) {
            Base64.getUrlDecoder().decode(text)
        }
    }

    private fun preserveIntegers(json: String): String {
        val out = StringBuilder(json.length + 32)
        var inString = false
        var escaped = false
        var i = 0
        while (i < json.length) {
            val c = json[i]
            if (inString) {
                out.append(c)
                if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') inString = false
                i++
            } else if (c == '"') {
                inString = true
                out.append(c)
                i++
            } else if (c == '-' || c.isDigit()) {
                var end = i + 1
                while (end < json.length && json[end] in "0123456789+-.eE") end++
                val token = json.substring(i, end)
                val integer = token.toLongOrNull()
                if (!token.contains('.') && !token.contains('e', true) && !token.contains('+') &&
                    (integer == null || integer > 9_007_199_254_740_991L || integer < -9_007_199_254_740_991L)
                ) out.append('"').append(token).append('"') else out.append(token)
                i = end
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }

    private fun objectArg(args: Array<Any?>, index: Int): Map<String, Any?> {
        val value = args.getOrNull(index)
        return when (value) {
            is JsObject -> value
            is Map<*, *> -> value.entries.associate { it.key.toString() to it.value }
            else -> emptyMap()
        }
    }
}
