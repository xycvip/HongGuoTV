// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.WebView
import android.webkit.WebViewClient
import com.hongguotv.core.MobilePlaybackSigner
import com.hongguotv.core.MobileSignedRequest
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONObject

/** Executes the exact THL portable signer locally for the Native N playback route only. */
internal object ThlSigningRuntime : MobilePlaybackSigner {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val lock = Any()
    @Volatile private var appContext: Context? = null
    @Volatile private var source: String? = null
    @Volatile private var view: WebView? = null
    @Volatile private var pageReady = false

    fun configure(context: Context) {
        val app = context.applicationContext
        if (appContext === app && source != null) return
        synchronized(lock) {
            if (appContext === app && source != null) return
            val js = app.assets.open("thl_signing.js").bufferedReader(Charsets.UTF_8).use { it.readText() }
            if (!js.contains("globalThis.__hongguoSignPlayback") ||
                !js.contains("createSigningProfile({id:S.from(builtInConfiguration.id")
            ) throw IOException("THL 签名脚本未包含完整本地签名配置")
            appContext = app
            source = js.replace("</script", "<\\/script")
        }
    }

    override fun sign(url: String, body: ByteArray, device: Map<String, String>, now: Long): MobileSignedRequest {
        if (Looper.myLooper() == Looper.getMainLooper()) throw IOException("THL 签名不能在主线程执行")
        synchronized(lock) {
            val app = appContext ?: throw IOException("THL 签名运行环境尚未初始化")
            ensurePage(app)
            val base64 = Base64.encodeToString(body, Base64.NO_WRAP)
            val deviceJson = JSONObject(device).toString()
            val expression = """
                (function() {
                  try {
                    return globalThis.__hongguoSignPlayback(
                      ${JSONObject.quote(url)},
                      ${JSONObject.quote(base64)},
                      $deviceJson,
                      $now
                    );
                  } catch (error) {
                    return { "__thl_error": String(error && (error.stack || error.message) || error) };
                  }
                })()
            """.trimIndent()
            val result = evaluate(expression)
            result.optString("__thl_error").takeIf { it.isNotBlank() }?.let {
                throw IOException("THL 官方签名失败：$it")
            }
            val signedUrl = result.optString("url")
            val headersJson = result.optJSONObject("headers") ?: throw IOException("THL 签名未返回请求头")
            if (signedUrl.isBlank()) throw IOException("THL 签名未返回请求地址")
            val headers = linkedMapOf<String, String>()
            val keys = headersJson.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                headers[key] = headersJson.optString(key)
            }
            for (required in listOf("x-khronos", "x-gorgon", "x-ladon", "x-argus", "x-helios", "x-medusa")) {
                if (headers[required].isNullOrBlank()) throw IOException("THL 签名缺少 $required")
            }
            return MobileSignedRequest(signedUrl, headers)
        }
    }

    private fun ensurePage(context: Context) {
        if (pageReady && view != null) return
        val latch = CountDownLatch(1)
        val failure = arrayOfNulls<Throwable>(1)
        mainHandler.post {
            try {
                if (view == null) {
                    val web = WebView(context)
                    web.settings.javaScriptEnabled = true
                    web.settings.blockNetworkLoads = true
                    web.settings.blockNetworkImage = true
                    web.settings.allowFileAccess = false
                    web.settings.allowContentAccess = false
                    web.settings.domStorageEnabled = false
                    web.webViewClient = object : WebViewClient() {
                        override fun onPageFinished(webView: WebView, url: String) {
                            pageReady = true
                            latch.countDown()
                        }
                    }
                    view = web
                    val html = "<!doctype html><html><head><meta charset=\"utf-8\"><script>" +
                        (source ?: throw IOException("THL 签名脚本未加载")) +
                        "</script></head><body></body></html>"
                    web.loadDataWithBaseURL("https://localhost/", html, "text/html", "UTF-8", null)
                } else if (pageReady) {
                    latch.countDown()
                } else {
                    view?.post { if (pageReady) latch.countDown() }
                }
            } catch (error: Throwable) {
                failure[0] = error
                latch.countDown()
            }
        }
        if (!latch.await(20, TimeUnit.SECONDS)) throw IOException("THL 签名引擎初始化超时")
        failure[0]?.let { throw IOException("THL 签名引擎初始化失败", it) }
        if (!pageReady || view == null) throw IOException("THL 签名引擎未就绪")
    }

    private fun evaluate(script: String): JSONObject {
        val latch = CountDownLatch(1)
        val output = arrayOfNulls<String>(1)
        val failure = arrayOfNulls<Throwable>(1)
        mainHandler.post {
            try {
                val web = view ?: throw IOException("THL 签名 WebView 不存在")
                web.evaluateJavascript(script) { value ->
                    output[0] = value
                    latch.countDown()
                }
            } catch (error: Throwable) {
                failure[0] = error
                latch.countDown()
            }
        }
        if (!latch.await(15, TimeUnit.SECONDS)) throw IOException("THL 签名计算超时")
        failure[0]?.let { throw IOException("THL 签名执行失败", it) }
        val raw = output[0]?.takeIf { it.isNotBlank() && it != "null" }
            ?: throw IOException("THL 签名返回空结果")
        return try { JSONObject(raw) } catch (error: Exception) {
            throw IOException("THL 签名结果格式错误：$raw", error)
        }
    }
}
