// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.graphics.drawable.GradientDrawable
import android.view.*
import android.view.inputmethod.InputMethodManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Uri
import android.net.Network
import android.net.NetworkCapabilities
import android.widget.*
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.media3.common.*
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.source.preload.DefaultPreloadManager
import androidx.media3.ui.PlayerView
import com.hongguotv.core.*
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

    internal fun MainActivity.setApiStatus(name: String,color: Int,detail: String) {
        apiStatusAnimators.remove(name)?.cancel()
        apiStatusViews[name]?.alpha=1f
        apiStatusResults[name]=color to detail
        val chip=apiStatusViews[name] ?: return
        chip.setLeafColor(name,color)
        chip.setLeafAlpha(name,1f)
    }

    internal fun MainActivity.animateApiStatus(name: String,color: Int,detail: String) {
        setApiStatus(name,color,detail)
        val chip=apiStatusViews[name] ?: return
        val animator=ValueAnimator.ofFloat(1f,0.28f,1f).apply {
            duration=900L
            repeatCount=ValueAnimator.INFINITE
            repeatMode=ValueAnimator.RESTART
            addUpdateListener { chip.setLeafAlpha(name,it.animatedValue as Float) }
        }
        apiStatusAnimators[name]=animator
        animator.start()
    }

    internal fun MainActivity.runAutomaticApiChecks() {
        if(apiStatusCheckStarted || !library.isLoaded || isDestroyed || tab!=0) return
        if(!hasValidatedInternet()) {
            val failed=Color.rgb(235,75,75)
            listOf("首页","搜索","播放","联想").forEach { setApiStatus(it,failed,"网络不可用") }
            apiNetworkUnavailable=true
            return
        }
        apiNetworkUnavailable=false
        apiStatusCheckStarted=true
        val checking=Color.rgb(70,150,255)
        val retrying=Color.rgb(244,166,64)
        val ok=Color.rgb(70,190,110)
        val failed=Color.rgb(235,75,75)
        val unknown=Color.rgb(238,190,55)
        listOf("首页","搜索","播放","联想").forEach { animateApiStatus(it,checking,"检测中") }
        val progress=runCatching { library.history().firstOrNull() }.getOrNull()

        fun probe(name: String,action: ()->String) {
            io.execute {
                var final: Pair<Int,String>?=null
                var lastError="请求失败"
                for(attempt in 0..3) {
                    if(attempt>0) {
                        main.post { if(!isDestroyed) animateApiStatus(name,retrying,"重试中（$attempt/3）") }
                        try { Thread.sleep(650L) } catch (_: InterruptedException) { Thread.currentThread().interrupt(); return@execute }
                    }
                    val result=runCatching(action)
                    if(result.isSuccess) {
                        final=ok to result.getOrThrow()
                        break
                    }
                    lastError=(result.exceptionOrNull()?.message ?: result.exceptionOrNull()?.javaClass?.simpleName ?: "请求失败").take(100)
                }
                val result=final ?: (failed to lastError)
                main.post {
                    if(!isDestroyed) {
                        if(hasValidatedInternet()) setApiStatus(name,result.first,result.second)
                        else setApiStatus(name,failed,"网络已断开")
                    }
                }
            }
        }

        probe("首页") { "请求成功 · 返回 ${repository.home(1,library.contentType).items.size} 条" }
        probe("搜索") { "请求成功 · 返回 ${repository.search("修仙",1,library.contentType).items.size} 条" }
        if(progress==null) {
            setApiStatus("播放",unknown,"暂无观看记录，无法验证播放地址")
        } else {
            probe("播放") { "已获取播放地址 · ${repository.stream(progress.episodeId,library.maxQuality).quality}" }
        }
        probe("联想") { "官方接口直连成功 · 返回 ${repository.probeSuggest("xx")} 个词（已绕过缓存）" }
    }

    internal fun MainActivity.showInterfaceCheck(anchor: TextView) {
        val content=column()
        val status=text("选择一项开始检测",14f,muted).apply { setPadding(dp(8),0,dp(8),dp(10)) }
        content.addView(status)
        lateinit var dialog: AlertDialog
        val actions=row()
        val buttons=mutableListOf<TextView>()
        val dotIdle=TvStyle.outline
        val dotOk=Color.rgb(86,196,122)
        val dotFail=Color.rgb(235,95,95)
        // 每个按钮独立的冷却与在检状态，互不影响
        val lastAt=mutableMapOf<String,Long>()
        val busy=mutableSetOf<String>()
        fun dotLabel(name: String,color: Int): CharSequence {
            val span=android.text.SpannableString("●  $name")
            span.setSpan(object: android.text.style.ReplacementSpan() {
                override fun getSize(paint: android.graphics.Paint,text: CharSequence?,start: Int,end: Int,fm: android.graphics.Paint.FontMetricsInt?): Int =
                    (paint.textSize*0.55f).toInt()
                override fun draw(canvas: android.graphics.Canvas,text: CharSequence?,start: Int,end: Int,x: Float,top: Int,y: Int,bottom: Int,paint: android.graphics.Paint) {
                    val r=paint.textSize*0.22f
                    val cy=(top+bottom)/2f
                    val oldColor=paint.color
                    paint.color=color
                    canvas.drawCircle(x+r,cy,r,paint)
                    paint.color=oldColor
                }
            },0,1,android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            return span
        }
        fun check(name: String,btn: TextView,probe: ()->String) {
            val now=System.currentTimeMillis()
            if(name in busy || now-(lastAt[name] ?: 0L)<3_000) { status.text="$name 请稍候再试"; return }
            lastAt[name]=now
            busy+=name; status.text="正在检测 $name…"
            btn.text=dotLabel(name,accent)
            val started=android.os.SystemClock.elapsedRealtime()
            io.execute {
                val result=runCatching(probe)
                main.post {
                    if(!dialog.isShowing) return@post
                    val elapsed=android.os.SystemClock.elapsedRealtime()-started
                    result.fold(
                        { detail -> status.text="$name · 正常 · $detail · ${elapsed}ms"; btn.text=dotLabel(name,dotOk) },
                        { problem -> status.text="$name · 失败 · ${(problem.message ?: problem.javaClass.simpleName).take(80)}"; btn.text=dotLabel(name,dotFail) })
                    busy-=name
                }
            }
        }
        fun add(name: String,probe: ()->String): TextView {
            lateinit var btn: TextView
            btn=button(name) { check(name,btn,probe) }
            btn.text=dotLabel(name,dotIdle)
            buttons+=btn
            actions.addView(btn,LinearLayout.LayoutParams(0,-2,1f).apply { rightMargin=dp(6) })
            return btn
        }
        add("首页") { val page=repository.home(1,library.contentType); "返回 ${page.items.size} 条" }
        add("搜索") { val page=repository.search("修仙",1,library.contentType); "返回 ${page.items.size} 条" }
        add("播放") {
            val progress=library.history().firstOrNull() ?: throw IllegalStateException("暂无观看记录，先播放一集")
            val info=repository.stream(progress.episodeId,library.maxQuality); "获取成功 · ${info.quality}"
        }
        add("联想") { "官方接口直连成功 · 返回 ${repository.probeSuggest("修仙")} 个词（已绕过缓存）" }
        content.addView(actions)
        content.addView(text("说明：每项只发一次请求，签名搜索接口被平台停用时「搜索」会走网站兜底。",12f,muted).apply { setPadding(dp(8),dp(10),dp(8),0) })
        dialog=AlertDialog.Builder(this).setView(TvDialog.card(this,content)).create()
        TvDialog.styleWindow(this,dialog)
        dialog.setOnDismissListener { if(anchor.isAttachedToWindow) anchor.requestFocus() }
        dialog.show(); buttons.first().requestFocus()
    }
