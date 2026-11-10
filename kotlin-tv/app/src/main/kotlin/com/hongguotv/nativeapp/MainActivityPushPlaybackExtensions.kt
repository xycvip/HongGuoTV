// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.NetworkCapabilities
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.hongguotv.core.PhoneField
import com.hongguotv.core.PhoneForm
import com.hongguotv.core.PhoneInputServer
import com.hongguotv.core.Series
import java.net.Inet4Address
import java.util.Locale

private data class PushShareItem(val title:String,val url:String,val source:String)
private object PushShareParser {
    private val redTitle=Regex("(?:漫剧|短剧)《([^》]{1,80})》")
    private val redUrl=Regex("https?://(?:www\\.)?novelquickapp\\.com/s/[A-Za-z0-9_-]+",RegexOption.IGNORE_CASE)
    private val douyinUrl=Regex("https?://v\\.douyin\\.com/[A-Za-z0-9_/-]+/?",RegexOption.IGNORE_CASE)
    private val bracketTitle=Regex("《([^》]{1,80})》")
    private fun clean(v:String)=v.replace(Regex("#[^\\n]*"),"").replace(Regex("\\s+")," ").trim().trimEnd('！','!','。','，',',','…')
    fun parse(raw:String):List<PushShareItem> {
        val result=mutableListOf<PushShareItem>()
        val titles=redTitle.findAll(raw).map { it.groupValues[1].trim() }.toList()
        redUrl.findAll(raw).forEachIndexed { i,m -> titles.getOrNull(i)?.takeIf { it.isNotBlank() }?.let { result+=PushShareItem(it,m.value,"红果") } }
        douyinUrl.findAll(raw).forEach { match ->
            val marker=raw.lastIndexOf("复制打开抖音",match.range.first)
            val lineStart=if(marker<0) 0 else (raw.lastIndexOf('\n',marker).takeIf { it>=0 }?.plus(1) ?: 0)
            val block=raw.substring(lineStart,match.range.first).substringAfter("】",raw.substring(lineStart,match.range.first))
            val title=bracketTitle.find(block)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() } ?: clean(block)
            if(title.isNotBlank()) result+=PushShareItem(title,match.value,"抖音")
        }
        return result.distinctBy { it.url }
    }
}
private fun MainActivity.pushPlaybackAnchor():View?=nav.firstOrNull { it.tag=="nav:push-playback" } ?: nav.lastOrNull()
internal fun MainActivity.showPushPlayback() {
    if(pushPlaybackPageActive) return
    pushPlaybackPageActive=true
    showCatalog()
    root.post { pushPlaybackAnchor()?.takeIf { it.isAttachedToWindow }?.requestFocus() }
}
internal fun MainActivity.closePushPlaybackService() {
    pushPlaybackServer?.close()
    pushPlaybackServer=null
    pushPlaybackQrView?.setImageDrawable(null)
    pushPlaybackUrlView?.text="访问地址：服务未开启"
    pushPlaybackStatusView?.text="服务已关闭。需要推送时，点击「开启服务」即可重新启动。"
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
        pushPlaybackStatusView?.text="服务运行中 · 端口 3309\n手机和电视连接同一 Wi-Fi，扫码后粘贴分享文案。"
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
    val page=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(dp(28),dp(22),dp(28),dp(30)) }
    scroll.addView(page)
    container.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
    page.addView(pushText("推送播放",27f).apply { typeface=Typeface.create("sans-serif-medium",Typeface.BOLD) })
    page.addView(pushText("手机扫码 · 粘贴分享 · 电视即刻播放",14f,TvStyle.muted).apply { setPadding(0,dp(5),0,dp(18)) })
    val hero=LinearLayout(this).apply {
        orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;setPadding(dp(20),dp(18),dp(20),dp(18))
        background=GradientDrawable().apply { setColor(TvStyle.surface);cornerRadius=dp(20).toFloat();setStroke(dp(1),TvStyle.outline) }
    }
    val qrCard=LinearLayout(this).apply {
        gravity=Gravity.CENTER;setPadding(dp(10),dp(10),dp(10),dp(10))
        background=GradientDrawable().apply { setColor(Color.WHITE);cornerRadius=dp(14).toFloat() }
    }
    val qr=ImageView(this).apply { scaleType=ImageView.ScaleType.FIT_CENTER;contentDescription="手机扫码打开推送播放网页" }
    qrCard.addView(qr,LinearLayout.LayoutParams(dp(208),dp(208)))
    hero.addView(qrCard,LinearLayout.LayoutParams(-2,-2))
    val info=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(dp(26),0,0,0) }
    info.addView(pushText("手机操作步骤",19f).apply { typeface=Typeface.DEFAULT_BOLD })
    listOf("01  手机连接与电视相同的 Wi-Fi","02  扫描二维码，打开推送网页","03  复制红果 / 抖音分享文案并粘贴","04  点击发送，电视自动搜索并显示结果").forEach {
        info.addView(pushText(it,14f,TvStyle.muted).apply { setPadding(0,dp(12),0,0) })
    }
    val urlLabel=pushText("访问地址：等待服务启动",13f,TvStyle.accent).apply { setPadding(0,dp(16),0,0) }
    pushPlaybackUrlView=urlLabel
    info.addView(urlLabel)
    hero.addView(info,LinearLayout.LayoutParams(0,-2,1f))
    page.addView(hero,LinearLayout.LayoutParams(-1,-2))
    val serviceRow=LinearLayout(this).apply { gravity=Gravity.CENTER_VERTICAL;orientation=LinearLayout.HORIZONTAL }
    val status=pushText("正在开启推送服务…",14f,TvStyle.muted)
    pushPlaybackStatusView=status
    serviceRow.addView(status,LinearLayout.LayoutParams(0,-2,1f))
    val serviceButton=TextView(this).apply {
        text=if(pushPlaybackServer==null) "开启服务" else "关闭服务"
        textSize=15f;gravity=Gravity.CENTER;setTextColor(Color.WHITE);isFocusable=true;isClickable=true
        setPadding(dp(20),dp(12),dp(20),dp(12))
        background=GradientDrawable().apply { setColor(TvStyle.accent);cornerRadius=dp(12).toFloat() }
        setOnClickListener {
            if(pushPlaybackServer==null) startPushPlaybackService() else closePushPlaybackService()
            text=if(pushPlaybackServer==null) "开启服务" else "关闭服务"
        }
    }
    serviceRow.addView(serviceButton)
    page.addView(serviceRow,LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(14) })
    pushPlaybackQrView=qr
    val result=LinearLayout(this).apply {
        orientation=LinearLayout.VERTICAL;setPadding(dp(18),dp(16),dp(18),dp(18))
        background=GradientDrawable().apply { setColor(TvStyle.surface);cornerRadius=dp(18).toFloat();setStroke(dp(1),TvStyle.outline) }
    }
    result.addView(pushText("推送识别结果",18f).apply { typeface=Typeface.DEFAULT_BOLD })
    result.addView(pushText("发送分享文案后，匹配状态和候选剧集会显示在这里。",13f,TvStyle.muted).apply { setPadding(0,dp(6),0,dp(12)) })
    val candidates=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
    pushPlaybackCandidateHost=candidates
    result.addView(candidates,LinearLayout.LayoutParams(-1,-2))
    page.addView(result,LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(18) })
    if(pushPlaybackServer==null) startPushPlaybackService() else {
        qr.setImageBitmap(pushQr(pushPlaybackServer!!.url))
        urlLabel.text="访问地址："+pushPlaybackServer!!.url
        status.text="服务运行中 · 端口 3309\n手机和电视连接同一 Wi-Fi，扫码后粘贴分享文案。"
    }
    pushAction(page,"关闭推送播放页面") {
        pushPlaybackPageActive=false;closePushPlaybackService();showCatalog()
        root.post { navForTab().takeIf { it.isAttachedToWindow }?.requestFocus() }
    }
}
private fun MainActivity.acceptPushShare(raw:String) {
    val shares=PushShareParser.parse(raw)
    if(shares.isEmpty()) { pushPlaybackStatusView?.text="没有识别到红果或抖音分享格式。请粘贴完整分享文案（包含分享链接）后重试。";return }
    val revision=++pushPlaybackRevision
    pushPlaybackTask?.cancel(true)
    pushPlaybackStatusView?.text="已收到 ${shares.size} 条分享，正在搜索剧名…"
    pushPlaybackCandidateHost?.removeAllViews()
    pushPlaybackTask=io.submit {
        try {
            val candidates=mutableListOf<Pair<PushShareItem,Series>>()
            val failures=mutableListOf<String>()
            shares.take(4).forEach { share ->
                if(Thread.currentThread().isInterrupted) return@submit
                try { repository.searchCombined(share.title,1).items.take(12).forEach { series -> if(candidates.none { it.second.id==series.id }) candidates+=share to series } }
                catch(problem:Exception) { failures+=share.title+"："+(problem.message ?: "搜索失败") }
            }
            val ranked=candidates.distinctBy { it.second.id }.sortedByDescending { (_,series) -> pushTitleScore(series.title,shares.map { it.title }) }.take(12)
            val exact=ranked.filter { (_,series) -> shares.any { normalizePushTitle(series.title)==normalizePushTitle(it.title) } }
            main.post {
                if(isDestroyed || !pushPlaybackPageActive || revision!=pushPlaybackRevision) return@post
                pushPlaybackTask=null
                when {
                    shares.size==1 && ranked.size==1 && exact.size==1 -> { pushPlaybackPageActive=false;closePushPlaybackService();pushPlaybackStatusView?.text="已找到唯一匹配：${exact.first().second.title}，正在打开剧集…";openDetail(exact.first().second,resume=true) }
                    ranked.isNotEmpty() -> { pushPlaybackStatusView?.text="找到 ${ranked.size} 个可能匹配的剧集，请在下方选择。";showPushCandidates(ranked.map { it.second }) }
                    else -> pushPlaybackStatusView?.text=if(failures.isNotEmpty()) "搜索暂时失败："+failures.take(2).joinToString("；") else "没有找到匹配剧集。为避免误播，未开始播放。"
                }
            }
        } catch(problem:Exception) {
            main.post { if(!isDestroyed && pushPlaybackPageActive && revision==pushPlaybackRevision) pushPlaybackStatusView?.text="搜索失败：${problem.message ?: "未知错误"}。没有开始播放。" }
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
    val host=pushPlaybackCandidateHost ?: return
    host.removeAllViews()
    val columns=4
    val cards=mutableListOf<View>()
    candidates.forEachIndexed { index,series ->
        if(index%columns==0) host.addView(LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL;gravity=Gravity.START })
        val line=host.getChildAt(host.childCount-1) as LinearLayout
        val card=LinearLayout(this).apply {
            id=View.generateViewId();orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER_HORIZONTAL
            isFocusable=true;isClickable=true;setPadding(dp(8),dp(8),dp(8),dp(9))
            background=GradientDrawable().apply { setColor(TvStyle.background);cornerRadius=dp(12).toFloat();setStroke(dp(1),TvStyle.outline) }
            contentDescription="选择剧集：${series.title}"
            setOnFocusChangeListener { v,focused ->
                v.background=GradientDrawable().apply { setColor(if(focused) TvStyle.selectedSurface else TvStyle.background);cornerRadius=dp(12).toFloat();setStroke(dp(if(focused) 2 else 1),if(focused) TvStyle.accent else TvStyle.outline) }
            }
            setOnClickListener {
                pushPlaybackRevision++;pushPlaybackTask?.cancel(true);pushPlaybackPageActive=false;closePushPlaybackService();openDetail(series,resume=true)
            }
        }
        val poster=ImageView(this).apply { scaleType=ImageView.ScaleType.CENTER_CROP;setBackgroundColor(TvStyle.badgeSurface) }
        card.addView(poster,LinearLayout.LayoutParams(dp(112),(dp(112)/TvStyle.POSTER_ASPECT).toInt()))
        loadCover(poster,series.cover)
        card.addView(TextView(this).apply { text=series.title;textSize=13f;setTextColor(TvStyle.text);gravity=Gravity.CENTER;maxLines=2;setPadding(0,dp(7),0,0);typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL) },LinearLayout.LayoutParams(-1,dp(42)))
        card.addView(TextView(this).apply { text=series.badge.ifBlank { series.tags.ifBlank { "剧集" } };textSize=10f;setTextColor(TvStyle.muted);gravity=Gravity.CENTER;maxLines=1 },LinearLayout.LayoutParams(-1,dp(20)))
        line.addView(card,LinearLayout.LayoutParams(0,-2,1f).apply { setMargins(dp(5),dp(5),dp(5),dp(5)) })
        cards+=card
    }
    cards.forEachIndexed { i,v ->
        v.nextFocusLeftId=cards.getOrNull(i-1)?.id ?: v.id;v.nextFocusRightId=cards.getOrNull(i+1)?.id ?: v.id
        v.nextFocusUpId=cards.getOrNull(i-columns)?.id ?: v.id;v.nextFocusDownId=cards.getOrNull(i+columns)?.id ?: v.id
    }
    cards.firstOrNull()?.requestFocus()
}
