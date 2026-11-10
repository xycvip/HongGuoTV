// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/** The random token in the URL path is the real access gate; Origin is only defense in depth.
 *  In-app WebViews and privacy tools often post forms with "null" or port-less origins,
 *  so only a genuinely different host counts as foreign. */
internal fun foreignOrigin(origin: String?, localAddress: String): Boolean {
    if(origin == null || origin == "null") return false
    val host = runCatching { java.net.URI(origin).host }.getOrNull() ?: return true
    return host.removePrefix("[").removeSuffix("]") != localAddress
}

/** 手机填写页的单个输入项；key 为表单字段名，只使用 ASCII，避免编码歧义。 */
data class PhoneField(
    val key: String,
    val label: String,
    val hint: String,
    val maxLength: Int = 80,
    val required: Boolean = true,
    val multiline: Boolean = false
)

/** 手机填写页表单定义：搜索剧名、添加三方片源、导入配置等扫码场景共用同一套服务，只替换表单与文案。 */
data class PhoneForm(
    val heading: String,
    val description: String,
    val fields: List<PhoneField>,
    val submitLabel: String = "发送到电视",
    val doneMessage: String = "已发送到电视",
    val doneHint: String = "请回到电视查看结果。",
    val invalidMessage: String = "请检查填写内容"
) {
    companion object {
        /** 电视搜索页的手机输入（沿用原有文案与校验规则）。 */
        fun search() = PhoneForm(
            heading = "在电视上搜索",
            description = "输入剧名，电视会按当前选择的漫剧或短剧分类搜索。",
            fields = listOf(PhoneField("query", "剧名或关键词", "输入剧名或关键词", 80)),
            doneHint = "请回到电视查看搜索结果。",
            invalidMessage = "请输入 1—80 个字符的剧名"
        )
    }
}

/** A one-use, short-lived LAN form. No cookies, platform credentials or external resources. */
class PhoneInputServer(
    address: String,
    private val form: PhoneForm,
    private val onSubmit: (Map<String, String>)->Unit,
    private val onExpired: ()->Unit = {},
    ttlMillis: Long = 300_000,
    fixedPort: Int = 0,
    tokenRequired: Boolean = true,
    singleUse: Boolean = true
): Closeable {
    private val stopped=AtomicBoolean(false)
    private val consumed=AtomicBoolean(false)
    private val localAddress=InetAddress.getByName(address).also { require(it.isLoopbackAddress || it.isSiteLocalAddress) }.hostAddress
    private val listener=if(fixedPort>0) ServerSocket().apply { reuseAddress=true; bind(java.net.InetSocketAddress(fixedPort),4); soTimeout=500 } else ServerSocket(0,4).apply { soTimeout=500 }
    private val deadline=if(ttlMillis<=0) Long.MAX_VALUE else System.nanoTime()+ttlMillis.coerceIn(1,300_000)*1_000_000
    private val token=if(tokenRequired) ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it.toInt() and 255) } else ""
    private val host="$localAddress:${listener.localPort}"
    private val pagePath=if(tokenRequired) "/$token" else "/"
    private val submitPath=if(tokenRequired) "/$token/submit" else "/submit"
    val url="http://$host$pagePath"
    private val thread=Thread({ serve() },"hongguotv-phone-input").apply { isDaemon=true }
    init {
        thread.start()
    }
    override fun close() { stopped.set(true); runCatching { listener.close() } }
    private fun serve() {
        try {
            while(!stopped.get() && System.nanoTime()<deadline) {
                val client=try { listener.accept() } catch(_: SocketTimeoutException) { continue }
                client.use { socket ->
                    socket.soTimeout=2000
                    try { handle(socket) } catch(_: Exception) { /* Discard malformed or disconnected requests. */ }
                }
            }
        } catch(_: Exception) { /* Closing the dialog interrupts accept(). */ }
        finally {
            val expired=!stopped.get() && ttlMillis>0 && !consumed.get()
            close()
            if(expired) onExpired()
        }
    }
    private fun line(input: InputStream, max: Int): String {
        val out=ByteArrayOutputStream()
        while(out.size()<max) {
            val next=input.read()
            if(next<0) throw java.io.EOFException()
            if(next==10) return out.toString("US-ASCII").trimEnd('\r')
            out.write(next)
        }
        throw java.io.IOException("Header too large")
    }
    private fun handle(socket: Socket) {
        if(!socket.inetAddress.let { it.isLoopbackAddress || it.isSiteLocalAddress }) return
        val input=socket.getInputStream().buffered()
        val request=line(input,1024).split(' ')
        if(request.size!=3) return
        val headers=mutableMapOf<String,String>()
        var bytes=0
        while(true) {
            val next=line(input,4096); bytes+=next.length+2
            if(bytes>8192) return respond(socket,431,"请求过大")
            if(next.isEmpty()) break
            val at=next.indexOf(':'); if(at<=0) return respond(socket,400,"请求格式错误")
            val key=next.substring(0,at).lowercase(Locale.ROOT)
            if(headers.put(key,next.substring(at+1).trim())!=null) return respond(socket,400,"重复请求头")
        }
        if(stopped.get() || (singleUse && consumed.get()) || System.nanoTime()>=deadline) return respond(socket,410,"服务已关闭，请回到电视重新开启")
        if(headers["host"]!=host || request[1] !in listOf(pagePath,submitPath)) return respond(socket,404,"页面不存在")
        if(request[0]=="GET" && request[1]==pagePath) return respond(socket,200,formPage())
        if(request[0]!="POST" || request[1]!=submitPath) return respond(socket,405,"不支持的操作")
        if(foreignOrigin(headers["origin"], localAddress)) return respond(socket,403,"来源不匹配")
        if(headers.containsKey("transfer-encoding") || headers["content-type"]?.substringBefore(';')!="application/x-www-form-urlencoded") return respond(socket,400,"请求格式错误")
        val length=headers["content-length"]?.toIntOrNull()?.takeIf { it in 1..65536 } ?: return respond(socket,413,"输入内容过长")
        val body=ByteArray(length); var offset=0
        while(offset<body.size) { val n=input.read(body,offset,body.size-offset); if(n<0) return; offset+=n }
        val values=parseFields(String(body,Charsets.UTF_8)) ?: return respond(socket,400,form.invalidMessage)
        if(stopped.get() || System.nanoTime()>=deadline || (singleUse && !consumed.compareAndSet(false,true))) return respond(socket,410,"服务已关闭")
        respond(socket,200,"<h1>${escapeHtml(form.doneMessage)}</h1><p>${escapeHtml(form.doneHint)}</p><p>可以返回上页继续发送其他分享内容。</p>")
        onSubmit(values)
        if(singleUse) close()
    }
    /** 表单校验：字段名必须与表单定义完全一致；重复、缺失、超长、控制字符一律拒绝且不消耗会话。 */
    private fun parseFields(body: String): Map<String,String>? {
        val values=LinkedHashMap<String,String>()
        for(pair in body.split('&')) {
            val at=pair.indexOf('=')
            if(at<=0) return null
            val key=pair.substring(0,at)
            val value=runCatching { URLDecoder.decode(pair.substring(at+1),"UTF-8") }.getOrNull() ?: return null
            if(values.put(key,value)!=null) return null
        }
        val expected=form.fields.mapTo(LinkedHashSet<String>()) { it.key }
        if(values.keys!=expected) return null
        for(field in form.fields) {
            val value=values[field.key].orEmpty().trim()
            if(value.any { it.isISOControl() && it != '\n' && it != '\r' && it != '\t' }) return null
            if(field.required && value.isEmpty()) return null
            if(value.length>field.maxLength) return null
            values[field.key]=value
        }
        return values
    }
    private fun formPage(): String = buildString {
        append("<main class=\"panel\"><div class=\"eyebrow\">HONGGUO TV · PUSH PLAY</div><h1>").append(escapeHtml(form.heading)).append("</h1>")
        append("<p>").append(escapeHtml(form.description)).append("</p>")
        append("<form method=\"post\" action=\"").append(submitPath).append("\">")
        form.fields.forEachIndexed { index, field ->
            append("<label for=\"field").append(index).append("\">").append(escapeHtml(field.label)).append("</label>")
            if(field.multiline) {
                append("<textarea id=\"field").append(index).append("\" name=\"").append(field.key).append('"')
                append(" maxlength=\"").append(field.maxLength.coerceIn(1,10000)).append('"')
                append(" placeholder=\"").append(escapeHtml(field.hint)).append('"')
                if(field.required) append(" required")
                append(" rows=\"8\" autocomplete=\"off\"></textarea>")
            } else {
                append("<input id=\"field").append(index).append("\" name=\"").append(field.key).append('"')
                append(" maxlength=\"").append(field.maxLength.coerceIn(1,10000)).append('"')
                append(" placeholder=\"").append(escapeHtml(field.hint)).append('"')
                if(field.required) append(" required")
                append(" autocomplete=\"off\"")
                append('>')
            }
            if(index==0) append("<script>document.getElementById('field0').focus()</script>")
        }
        append("<button>").append(escapeHtml(form.submitLabel)).append("</button></form>")
        append("<p class=\"foot\">请确认手机与电视连接同一 Wi-Fi。提交后电视会立即识别并显示匹配结果。</p></main>")
    }
    private fun respond(socket: Socket, code: Int, message: String) {
        val body="""<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover"><meta name="theme-color" content="#10131b"><title>红果 TV · 推送播放</title><style>
:root{color-scheme:dark;--bg:#0c1018;--line:#2b3547;--text:#f6f7fb;--muted:#aab4c5;--accent:#ff654f}
*{box-sizing:border-box}body{margin:0;min-height:100vh;padding:28px 18px calc(28px + env(safe-area-inset-bottom));font-family:system-ui,-apple-system,"PingFang SC","Microsoft YaHei",sans-serif;color:var(--text);background:radial-gradient(ellipse at 85% 0%,#45251f 0,transparent 42%),linear-gradient(160deg,#111927 0%,var(--bg) 62%)}
main{width:100%;max-width:620px;margin:0 auto}.panel{padding:clamp(22px,6vw,38px);border:1px solid rgba(255,255,255,.09);border-radius:26px;background:linear-gradient(145deg,rgba(29,39,55,.96),rgba(19,25,36,.96));box-shadow:0 22px 70px rgba(0,0,0,.34)}
.eyebrow{font-size:11px;letter-spacing:2.6px;font-weight:800;color:var(--accent);margin-bottom:12px}h1{font-size:clamp(28px,7vw,36px);letter-spacing:.5px;margin:0 0 10px;font-weight:800}p{font-size:15px;line-height:1.75;color:var(--muted);margin:0 0 22px}
label{display:block;font-size:15px;font-weight:700;color:#e8edf6;margin:22px 0 10px}
textarea{display:block;width:100%;min-height:280px;resize:vertical;border:1px solid #3b485d;border-radius:17px;padding:18px;background:#0d1420;color:var(--text);font:16px/1.8 system-ui,-apple-system,"PingFang SC",sans-serif;outline:none;box-shadow:inset 0 2px 8px rgba(0,0,0,.18)}
input{display:block;width:100%;min-height:56px;border:1px solid #3b485d;border-radius:14px;padding:14px;background:#0d1420;color:var(--text);font:16px system-ui,-apple-system,"PingFang SC",sans-serif;outline:none}
textarea:focus,input:focus{border-color:var(--accent);box-shadow:0 0 0 3px rgba(255,101,79,.15)}textarea::placeholder,input::placeholder{color:#738198}
button{width:100%;min-height:58px;margin-top:20px;border:0;border-radius:16px;background:linear-gradient(105deg,#ff795d,#f04e43);color:white;font-size:18px;font-weight:800;letter-spacing:1px;box-shadow:0 10px 28px rgba(240,78,67,.22);cursor:pointer}
button:active{transform:scale(.99)}.foot{margin:20px 0 0;padding-top:17px;border-top:1px solid var(--line);font-size:13px}
@media(min-width:520px){body{padding-top:7vh}textarea{min-height:320px}}
</style></head><body>$message</body></html>""".toByteArray(Charsets.UTF_8)
        val header="HTTP/1.1 $code Response\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${body.size}\r\nConnection: close\r\nCache-Control: no-store\r\nReferrer-Policy: no-referrer\r\nX-Content-Type-Options: nosniff\r\nContent-Security-Policy: default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; frame-ancestors 'none'; base-uri 'none'\r\n\r\n"
        socket.getOutputStream().apply { write(header.toByteArray(Charsets.US_ASCII)); write(body); flush() }
    }
}

private fun escapeHtml(value: String): String = value
    .replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;")

/** 「输入剧名」场景的薄封装：等价于 PhoneInputServer + PhoneForm.search()。 */
class PhoneSearchServer(
    address: String,
    onQuery: (String)->Unit,
    onExpired: ()->Unit = {},
    ttlMillis: Long = 300_000
): Closeable {
    private val server=PhoneInputServer(address,PhoneForm.search(),{ values->onQuery(values["query"].orEmpty()) },onExpired,ttlMillis)
    val url: String get()=server.url
    override fun close() { server.close() }
}
