// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.NetworkCapabilities
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.hongguotv.core.Detail
import com.hongguotv.core.PhoneField
import com.hongguotv.core.PhoneForm
import com.hongguotv.core.PhoneInputServer
import com.hongguotv.core.Series
import java.net.Inet4Address
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.util.Locale

private data class PushShareItem(val title:String,val url:String,val source:String)
private data class RedShareIds(val seriesId:String?,val videoId:String?)
private object PushShareParser {
    private val redTitle=Regex("(?:漫剧|短剧)《([^》]{1,80})》")
    private val redUrl=Regex("https?://(?:www\\.)?novelquickapp\\.com/s/[A-Za-z0-9_-]+/?",RegexOption.IGNORE_CASE)
    private val douyinUrl=Regex("https?://v\\.douyin\\.com/[A-Za-z0-9_/-]+/?",RegexOption.IGNORE_CASE)
    private val bracketTitle=Regex("[《【〖]([^》】〗]{1,100})[》】〗]")
    private fun clean(v:String)=v.replace(Regex("#[^\\n]*"),"").replace(Regex("\\s+")," ").trim().trimEnd('！','!','。','，',',','…')
    fun parse(raw:String):List<PushShareItem> {
        val result=mutableListOf<PushShareItem>()
        val redTitles=redTitle.findAll(raw).map { it.groupValues[1].trim() }.toList()
        val redLinks=redUrl.findAll(raw).toList()
        redLinks.forEachIndexed { i,m -> result+=PushShareItem(redTitles.getOrNull(i).orEmpty(),m.value,"红果") }
        if(redLinks.isEmpty()) redTitles.forEach { result+=PushShareItem(it,"","红果") }
        douyinUrl.findAll(raw).forEach { match ->
            val marker=raw.lastIndexOf("复制打开抖音",match.range.first)
            val lineStart=if(marker<0) 0 else (raw.lastIndexOf('\n',marker).takeIf { it>=0 }?.plus(1) ?: 0)
            // 抖音分享标题常用【作者的作品】或〖作者的作品〗；不能先截掉右括号前的内容。
            val block=raw.substring(lineStart,match.range.first)
            val title=bracketTitle.find(block)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() } ?: clean(block).removePrefix(Regex("^\\d+(?:\\.\\d+)?\\s*").find(clean(block))?.value.orEmpty()).removePrefix("复制打开抖音").removePrefix("看看").trim()
            if(title.isNotBlank()) result+=PushShareItem(title,match.value,"抖音")
        }
        return result.distinctBy { it.source+"|"+it.url+"|"+it.title }
    }
}

/** 红果短链专用解析器：只跟随红果 /s/ 短链，不处理抖音链接。 */
private object RedShareResolver {
    private val seriesPattern=Regex("""["']?(?:video_series_id|series_id|seriesId|series_id_str)["']?\s*[=:]\s*["']?(\d{1,30})""",RegexOption.IGNORE_CASE)
    private val episodePattern=Regex("""["']?(?:share_toast_vid|vid|episode_id|episodeId)["']?\s*[=:]\s*["']?(\d{1,30})""",RegexOption.IGNORE_CASE)
    private val genericVideoPattern=Regex("""["']?video_id["']?\s*[=:]\s*["']?(\d{1,30})""",RegexOption.IGNORE_CASE)
    private val playerPath=Regex("""/player/(\d{1,30})/(\d{1,30})""",RegexOption.IGNORE_CASE)
    private fun decoded(value:String):String {
        var current=value
        repeat(2) { current=runCatching { URLDecoder.decode(current,"UTF-8") }.getOrDefault(current) }
        return current
    }
    fun resolve(shortUrl:String):RedShareIds {
        require(shortUrl.startsWith("https://novelquickapp.com/s/",true) || shortUrl.startsWith("https://www.novelquickapp.com/s/",true)) { "不是红果短链" }
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
                val path=playerPath.find(source)
                if(path!=null) {
                    if(seriesId==null) seriesId=path.groupValues[1]
                    if(episodeId==null) episodeId=path.groupValues[2]
                }
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

private fun MainActivity.pushPlaybackAnchor():View?=nav.firstOrNull { it.tag=="nav:push-playback" } ?: nav.lastOrNull()
internal fun MainActivity.showPushPlayback() {
    if(pushPlaybackPageActive) return
    // 先让旧导航项收到失焦回调，再重建导航栏，避免旧焦点样式残留在页面上。
    root.findFocus()?.clearFocus()
    nav.forEach { it.clearFocus() }
    pushPlaybackPageActive=true
    showCatalog()
    root.post { pushPlaybackAnchor()?.takeIf { it.isAttachedToWindow }?.requestFocus() }
}
internal fun MainActivity.closePushPlaybackService() {
    pushPlaybackServer?.close()
    pushPlaybackServer=null
    pushPlaybackQrView?.setImageDrawable(null)
    pushPlaybackUrlView?.text="访问地址：服务未开启"
    pushPlaybackStatusView?.text="推送服务已停止。"
}
private fun MainActivity.pushForm()=PhoneForm(
    heading="推送播放",
    description="复制红果短剧或抖音 App 中的完整分享文案，粘贴到下方输入框，再发送到电视。",
    fields=listOf(PhoneField("share","分享文案 / 分享链接","请粘贴完整分享文案，支持多行内容……",10000,true,true)),
    submitLabel="发送到电视",doneMessage="发送成功",doneHint="电视已收到分享内容，正在搜索匹配剧集。"
)
private fun MainActivity.pushLocalAddress():String? {
    val network=connectivity.activeNetwork ?: return null
    val caps=connectivity.getNetworkCapabilities(network) ?: return null
    if(!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && !caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return null
    return connectivity.getLinkProperties(network)?.linkAddresses?.map { it.address }
        ?.firstOrNull { it is Inet4Address && it.isSiteLocalAddress }?.hostAddress
}
private fun MainActivity.startPushPlaybackService() {
    if(pushPlaybackServer!=null) return
    val address=pushLocalAddress()
    if(address==null) { pushPlaybackStatusView?.text="当前没有可用的局域网 IPv4 地址。请让电视连接 Wi-Fi 或网线后重试。"; return }
    try {
        lateinit var session:PhoneInputServer
        session=PhoneInputServer(address,pushForm(),{ values ->
            main.post { if(!isDestroyed && pushPlaybackPageActive && pushPlaybackServer===session) acceptPushShare(values["share"].orEmpty()) }
        },ttlMillis=0,fixedPort=3309,tokenRequired=false,singleUse=false)
        pushPlaybackServer=session
        pushPlaybackQrView?.setImageBitmap(pushQr(session.url))
        pushPlaybackUrlView?.text="访问地址："+session.url
        pushPlaybackStatusView?.text="服务运行中 · 端口 3309"
    } catch(problem:Exception) {
        pushPlaybackServer=null
        pushPlaybackQrView?.setImageDrawable(null)
        pushPlaybackStatusView?.text=if(problem is java.net.BindException) "端口 3309 已被占用，请关闭占用端口的服务后重试。"
            else "启动推送服务失败：${problem.message ?: "请检查网络设置"}"
    }
}
private fun pushQr(url:String):Bitmap {
    val matrix=QRCodeWriter().encode(url,BarcodeFormat.QR_CODE,512,512)
    val pixels=IntArray(512*512)
    for(y in 0 until 512) for(x in 0 until 512) pixels[y*512+x]=if(matrix[x,y]) Color.BLACK else Color.WHITE
    return Bitmap.createBitmap(pixels,512,512,Bitmap.Config.ARGB_8888)
}
private fun MainActivity.pushText(value:String,size:Float,color:Int=TvStyle.text)=TextView(this).apply {
    text=value;textSize=size;setTextColor(color);setLineSpacing(dp(3).toFloat(),1f)
}
private fun MainActivity.pushAction(parent:LinearLayout,title:String,action:()->Unit):TextView {
    val view=TextView(this).apply {
        text=title;textSize=15f;gravity=Gravity.CENTER;setTextColor(Color.WHITE)
        isFocusable=true;isClickable=true;setPadding(dp(22),dp(13),dp(22),dp(13))
        background=GradientDrawable().apply { setColor(TvStyle.accent);cornerRadius=dp(12).toFloat() }
        setOnFocusChangeListener { v,focused -> v.scaleX=if(focused) 1.04f else 1f;v.scaleY=if(focused) 1.04f else 1f;v.elevation=if(focused) dp(6).toFloat() else 0f }
        setOnClickListener { action() }
    }
    parent.addView(view,LinearLayout.LayoutParams(-2,-2).apply { topMargin=dp(12) })
    return view
}
internal fun MainActivity.renderPushPlaybackPage(container:LinearLayout) {
    val scroll=ScrollView(this).apply { isFillViewport=true;isVerticalScrollBarEnabled=false }
    val page=LinearLayout(this).apply {
        orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER
        setPadding(dp(28),dp(24),dp(28),dp(24))
    }
    scroll.addView(page,ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT))
    container.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
    val hero=LinearLayout(this).apply {
        orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL
        setPadding(dp(26),dp(20),dp(26),dp(20))
        background=GradientDrawable().apply { setColor(TvStyle.surface);cornerRadius=dp(20).toFloat();setStroke(dp(1),TvStyle.outline) }
    }
    val qrCard=LinearLayout(this).apply {
        gravity=Gravity.CENTER;setPadding(dp(8),dp(8),dp(8),dp(8))
        background=GradientDrawable().apply { setColor(Color.WHITE);cornerRadius=dp(14).toFloat() }
    }
    val qr=ImageView(this).apply { scaleType=ImageView.ScaleType.FIT_CENTER;contentDescription="手机扫码打开推送播放网页" }
    qrCard.addView(qr,LinearLayout.LayoutParams(dp(200),dp(200)))
    hero.addView(qrCard,LinearLayout.LayoutParams(-2,-2))
    val info=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(dp(28),0,dp(4),0) }
    fun oneLine(value:String,size:Float,color:Int)=pushText(value,size,color).apply {
        setSingleLine(false);maxLines=4;ellipsize=null
    }
    val status=oneLine("服务运行中 · 端口 3309",18f,TvStyle.text).apply { typeface=Typeface.DEFAULT_BOLD }
    info.addView(status)
    info.addView(oneLine("手机和电视连接同一 Wi-Fi，扫码后粘贴分享文案。",18f,TvStyle.text).apply { setPadding(0,dp(8),0,0) })
    val divider=View(this).apply { setBackgroundColor(TvStyle.outline) }
    info.addView(divider,LinearLayout.LayoutParams(-1,dp(1)).apply { topMargin=dp(12);bottomMargin=dp(11) })
    info.addView(oneLine("手机操作步骤",18f,TvStyle.text).apply { typeface=Typeface.DEFAULT_BOLD })
    listOf(
        "01  手机与电视连接同一 Wi-Fi",
        "02  扫码打开推送网页",
        "03  复制红果 / 抖音分享文案并粘贴",
        "04  点击发送，电视自动搜索并显示结果"
    ).forEach { step ->
        info.addView(oneLine(step,16f,TvStyle.muted).apply { setPadding(0,dp(6),0,0) })
    }
    val urlLabel=oneLine("访问地址：等待服务启动",16f,TvStyle.accent).apply { setPadding(0,dp(10),0,0) }
    pushPlaybackUrlView=urlLabel
    info.addView(urlLabel)
    hero.addView(info,LinearLayout.LayoutParams(0,-2,1f))
    hero.minimumHeight=dp(300)
    page.addView(hero,LinearLayout.LayoutParams(-2,-2).apply { gravity=Gravity.CENTER })
    pushPlaybackStatusView=status
    pushPlaybackQrView=qr
    // 卡片在可用区域内水平、垂直居中；内容增高时由 ScrollView 自然滚动。
    page.post {
        val availableWidth=page.width-page.paddingLeft-page.paddingRight
        val cardWidth=minOf(availableWidth,dp(820))
        hero.layoutParams=hero.layoutParams.apply { width=cardWidth;height=ViewGroup.LayoutParams.WRAP_CONTENT }
        hero.requestLayout()
    }
    // 搜索结果改为独立弹窗，二维码卡片始终保持原位和原尺寸。
    pushPlaybackCandidateHost=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
    if(pushPlaybackServer==null) startPushPlaybackService() else {
        qr.setImageBitmap(pushQr(pushPlaybackServer!!.url))
        urlLabel.text="访问地址："+pushPlaybackServer!!.url
        status.text="服务运行中 · 端口 3309"
    }
}
private fun MainActivity.acceptPushShare(raw:String) {
    val shares=PushShareParser.parse(raw)
    if(shares.isEmpty()) { pushPlaybackStatusView?.text="没有识别到红果或抖音分享格式。请粘贴完整分享文案或分享链接后重试。";return }
    val revision=++pushPlaybackRevision
    pushPlaybackTask?.cancel(true)
    pushPlaybackStatusView?.text="已收到 ${shares.size} 条分享，正在分别识别来源…"
    pushPlaybackCandidateHost?.removeAllViews()
    pushPlaybackTask=io.submit {
        try {
            val candidates=mutableListOf<Pair<PushShareItem,Series>>()
            val failures=mutableListOf<String>()
            var directDetail:Detail?=null
            var directVideoId:String?=null
            var directForceFirst=false
            shares.take(4).forEach { share ->
                if(Thread.currentThread().isInterrupted) return@submit
                var resolved=false
                if(share.source=="红果" && share.url.isNotBlank()) {
                    try {
                        val ids=RedShareResolver.resolve(share.url)
                        if(!ids.seriesId.isNullOrBlank()) {
                            val detail=repository.detail(ids.seriesId)
                            val episodeIndex=ids.videoId?.let { detail.episodes.indexOf(it) } ?: -1
                            if(ids.videoId.isNullOrBlank() || episodeIndex>=0) {
                                if(directDetail==null) {
                                    directDetail=detail
                                    directVideoId=ids.videoId
                                    directForceFirst=ids.videoId.isNullOrBlank()
                                }
                                resolved=true
                            } else failures+="红果链接已解析剧目 ID，但集数 ID 不在该剧分集列表中"
                        }
                    } catch(problem:Exception) {
                        failures+="红果链接解析/剧目读取失败："+(problem.message ?: "未知错误")
                    }
                }
                // 红果解析失败才使用红果文案兜底；抖音始终只走抖音文案搜索，不调用红果解析器。
                if(!resolved && share.title.isNotBlank()) {
                    try {
                        repository.searchCombined(share.title,1).items.take(12).forEach { series ->
                            if(candidates.none { it.second.id==series.id }) candidates+=share to series
                        }
                    } catch(problem:Exception) { failures+=share.source+"《"+share.title+"》："+(problem.message ?: "搜索失败") }
                }
            }
            val ranked=candidates.distinctBy { it.second.id }.sortedByDescending { (_,series) -> pushTitleScore(series.title,shares.map { it.title }.filter { it.isNotBlank() }) }.take(12)
            val exact=ranked.filter { (_,series) -> shares.any { it.title.isNotBlank() && normalizePushTitle(series.title)==normalizePushTitle(it.title) } }
            val readyDetail=directDetail
            val readyVideoId=directVideoId
            val forceFirst=directForceFirst
            main.post {
                if(isDestroyed || !pushPlaybackPageActive || revision!=pushPlaybackRevision) return@post
                pushPlaybackTask=null
                when {
                    readyDetail!=null && shares.size==1 -> {
                        pushPlaybackPageActive=false;closePushPlaybackService()
                        val episodeLabel=if(forceFirst) "第 1 集" else "分享指定集"
                        pushPlaybackStatusView?.text="红果链接解析成功：${readyDetail.series.title}，${episodeLabel}，正在播放…"
                        openDetail(readyDetail.series,resume=true,startEpisodeId=readyVideoId,forceFirstEpisode=forceFirst,resolvedDetail=readyDetail)
                    }
                    ranked.size==1 && exact.size==1 && shares.size==1 -> {
                        pushPlaybackPageActive=false;closePushPlaybackService()
                        pushPlaybackStatusView?.text="已找到唯一匹配：${exact.first().second.title}，正在打开剧集…"
                        openDetail(exact.first().second,resume=true)
                    }
                    ranked.isNotEmpty() -> { pushPlaybackStatusView?.text="找到 ${ranked.size} 个可能匹配的剧集，请在弹窗中选择。";showPushCandidates(ranked.map { it.second }) }
                    else -> pushPlaybackStatusView?.text=if(failures.isNotEmpty()) failures.take(2).joinToString("；") else "没有找到匹配剧集。为避免误播，未开始播放。"
                }
            }
        } catch(problem:Exception) {
            main.post { if(!isDestroyed && pushPlaybackPageActive && revision==pushPlaybackRevision) pushPlaybackStatusView?.text="识别失败：${problem.message ?: "未知错误"}。没有开始播放。" }
        }
    }
}
private fun normalizePushTitle(value:String)=value.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }
private fun pushTitleScore(title:String,queries:List<String>):Int {
    val candidate=normalizePushTitle(title)
    return queries.maxOfOrNull { query ->
        val key=normalizePushTitle(query)
        when { key.isBlank()->0;candidate==key->1000;candidate.contains(key)||key.contains(candidate)->700;else->key.toSet().intersect(candidate.toSet()).size*100/key.toSet().size.coerceAtLeast(1) }
    } ?: 0
}
private fun MainActivity.showPushCandidates(candidates:List<Series>) {
    if(candidates.isEmpty() || isFinishing || isDestroyed) return
    pushPlaybackCandidateDialog?.dismiss()
    val dialog=android.app.Dialog(this)
    pushPlaybackCandidateDialog=dialog
    val panel=LinearLayout(this).apply {
        orientation=LinearLayout.VERTICAL
        setPadding(dp(22),dp(18),dp(22),dp(18))
        background=GradientDrawable().apply {
            setColor(TvStyle.surface)
            cornerRadius=dp(20).toFloat()
            setStroke(dp(1),TvStyle.outline)
        }
    }
    val heading=LinearLayout(this).apply {
        orientation=LinearLayout.HORIZONTAL
        gravity=Gravity.CENTER_VERTICAL
    }
    heading.addView(TextView(this).apply {
        text="搜索结果"
        textSize=20f
        setTextColor(TvStyle.text)
        typeface=Typeface.DEFAULT_BOLD
    },LinearLayout.LayoutParams(0,-2,1f))
    heading.addView(TextView(this).apply {
        text="\${candidates.size} 个匹配结果"
        textSize=13f
        setTextColor(TvStyle.muted)
    })
    panel.addView(heading,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(14) })
    val grid=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
    val columns=4
    val cards=mutableListOf<View>()
    candidates.forEachIndexed { index,series ->
        if(index%columns==0) grid.addView(LinearLayout(this).apply {
            orientation=LinearLayout.HORIZONTAL
            gravity=Gravity.START
        })
        val line=grid.getChildAt(grid.childCount-1) as LinearLayout
        val card=LinearLayout(this).apply {
            id=View.generateViewId()
            orientation=LinearLayout.VERTICAL
            gravity=Gravity.CENTER_HORIZONTAL
            isFocusable=true
            isClickable=true
            setPadding(dp(8),dp(8),dp(8),dp(9))
            background=TvStyle.card(this@showPushCandidates,false)
            contentDescription="选择剧集：\${series.title}"
            setOnFocusChangeListener { v,focused -> v.background=TvStyle.card(this@showPushCandidates,focused) }
            setOnClickListener {
                pushPlaybackCandidateDialog?.dismiss()
                pushPlaybackCandidateDialog=null
                pushPlaybackRevision++
                pushPlaybackTask?.cancel(true)
                pushPlaybackPageActive=false
                closePushPlaybackService()
                openDetail(series,resume=true)
            }
        }
        val poster=ImageView(this).apply {
            scaleType=ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(TvStyle.badgeSurface)
        }
        card.addView(poster,LinearLayout.LayoutParams(dp(112),(dp(112)/TvStyle.POSTER_ASPECT).toInt()))
        loadCover(poster,series.cover)
        card.addView(TextView(this).apply {
            text=series.title
            textSize=13f
            setTextColor(TvStyle.text)
            gravity=Gravity.CENTER
            maxLines=2
            setPadding(0,dp(7),0,0)
            typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL)
        },LinearLayout.LayoutParams(-1,dp(42)))
        card.addView(TextView(this).apply {
            text=series.badge.ifBlank { series.tags.ifBlank { "剧集" } }
            textSize=10f
            setTextColor(TvStyle.muted)
            gravity=Gravity.CENTER
            maxLines=1
        },LinearLayout.LayoutParams(-1,dp(20)))
        line.addView(card,LinearLayout.LayoutParams(0,-2,1f).apply {
            setMargins(dp(5),dp(5),dp(5),dp(5))
        })
        cards+=card
    }
    cards.forEachIndexed { i,v ->
        v.nextFocusLeftId=cards.getOrNull(i-1)?.id ?: v.id
        v.nextFocusRightId=cards.getOrNull(i+1)?.id ?: v.id
        v.nextFocusUpId=cards.getOrNull(i-columns)?.id ?: View.NO_ID
        v.nextFocusDownId=cards.getOrNull(i+columns)?.id ?: View.NO_ID
    }
    val scroll=ScrollView(this).apply {
        isVerticalScrollBarEnabled=false
        addView(grid,ViewGroup.LayoutParams(-1,-2))
    }
    panel.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
    panel.minimumHeight=dp(180)
    dialog.setContentView(panel)
    dialog.setCancelable(true)
    dialog.setCanceledOnTouchOutside(false)
    dialog.setOnDismissListener {
        if(pushPlaybackCandidateDialog===dialog) pushPlaybackCandidateDialog=null
    }
    dialog.show()
    dialog.window?.let { window ->
        val metrics=resources.displayMetrics
        window.setBackgroundDrawableResource(android.R.color.transparent)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.setDimAmount(0.58f)
        window.setLayout((metrics.widthPixels*0.82f).toInt(),(metrics.heightPixels*0.76f).toInt())
    }
    cards.firstOrNull()?.requestFocus()
}
