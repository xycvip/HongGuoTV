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

    internal fun MainActivity.setApiStatus(name: String,color: Int,detail: String,elapsedMs: Long? = null) {
        apiStatusAnimators.remove(name)?.let { animator ->
            animator.removeAllUpdateListeners()
            animator.cancel()
        }
        apiStatusViews[name]?.alpha=1f
        apiStatusResults[name]=color to detail
        apiStatusUpdatedAt[name]=System.currentTimeMillis()
        apiStatusElapsedMs[name]=elapsedMs
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

    /** 页面重建、离开或 Activity 销毁时统一释放四片叶子的闪烁动画。 */
    internal fun MainActivity.stopApiStatusAnimations() {
        apiStatusAnimators.values.toList().forEach { animator ->
            animator.removeAllUpdateListeners()
            animator.cancel()
        }
        apiStatusAnimators.clear()
        apiStatusViews.values.distinct().forEach { flower ->
            listOf("首页","搜索","播放","联想").forEach { flower.setLeafAlpha(it,1f) }
            flower.alpha=1f
        }
    }

    internal fun MainActivity.runAutomaticApiChecks(force: Boolean = false) {
        if(!library.isLoaded || isDestroyed || (!force && (tab!=0 || apiStatusCheckStarted))) return
        val batch=++apiStatusUiState.checkBatchId
        apiStatusUiState.cancelPlaybackProbes()
        if(!hasValidatedInternet()) {
            val failed=Color.rgb(235,75,75)
            listOf("首页","搜索","播放","联想").forEach { setApiStatus(it,failed,"网络不可用") }
            apiNetworkUnavailable=true
            apiStatusCheckStarted=false
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
        val playbackSource=playbackStreamSource(repository.http)

        fun probe(name: String,action: ()->String) {
            val started=android.os.SystemClock.elapsedRealtime()
            val requestScope=if(name=="播放") RequestScope().also { apiStatusUiState.playbackProbeScopes.add(it) } else null
            lateinit var task: java.util.concurrent.Future<*>
            task=io.submit {
                try {
                    var final: Pair<Int,String>?=null
                    var lastError="请求失败"
                    for(attempt in 0..3) {
                        if(Thread.currentThread().isInterrupted) return@submit
                        if(attempt>0) {
                            main.post {
                                if(!isDestroyed && batch==apiStatusUiState.checkBatchId) {
                                    animateApiStatus(name,retrying,"重试中（$attempt/3）")
                                }
                            }
                            try { Thread.sleep(650L) } catch (_: InterruptedException) {
                                Thread.currentThread().interrupt()
                                return@submit
                            }
                        }
                        val result=runCatching {
                            if(requestScope!=null) requestScope.run(action) else action()
                        }
                        if(result.isSuccess) {
                            final=ok to result.getOrThrow()
                            break
                        }
                        lastError=(result.exceptionOrNull()?.message ?: result.exceptionOrNull()?.javaClass?.simpleName ?: "请求失败").take(100)
                    }
                    val result=final ?: (failed to lastError)
                    val elapsed=android.os.SystemClock.elapsedRealtime()-started
                    main.post {
                        if(!isDestroyed && batch==apiStatusUiState.checkBatchId) {
                            if(hasValidatedInternet()) setApiStatus(name,result.first,result.second,elapsed)
                            else setApiStatus(name,failed,"网络已断开",elapsed)
                        }
                    }
                } finally {
                    if(requestScope!=null) {
                        apiStatusUiState.playbackProbeScopes.remove(requestScope)
                        main.post { apiStatusUiState.playbackProbeJobs.remove(task) }
                    }
                }
            }
            if(requestScope!=null) apiStatusUiState.playbackProbeJobs.add(task)
        }

        probe("首页") { "请求成功 · 返回 ${repository.home(1,library.contentType).items.size} 条" }
        probe("搜索") { "请求成功 · 返回 ${repository.search("修仙",1,library.contentType).items.size} 条" }
        if(progress==null) {
            setApiStatus("播放",unknown,"暂无观看记录，无法验证播放地址")
        } else {
            probe("播放") { "已获取播放地址 · ${playbackSource.stream(progress.episodeId,library.maxQuality).quality}" }
        }
        probe("联想") { "官方接口直连成功 · 返回 ${repository.probeSuggest("xx")} 个词（已绕过缓存）" }
    }

    internal fun MainActivity.showInterfaceCheck(anchor: TextView) {
        val content=column()
        content.addView(TvDialog.title(this,"接口状态详情"))
        val status=text("可查看最近一次自动检测结果，也可手动重新检测。",13f,muted)
            .apply { setPadding(dp(8),0,dp(8),dp(8)) }
        content.addView(status)

        val snapshot=column().apply {
            setPadding(dp(8),dp(8),dp(8),dp(8))
            background=rounded(Color.rgb(31,34,40))
        }
        val names=listOf("首页","搜索","播放","联想")
        fun stateLabel(color:Int):String = when(color) {
            Color.rgb(70,150,255) -> "检测中"
            Color.rgb(244,166,64) -> "重试中"
            Color.rgb(70,190,110) -> "正常"
            Color.rgb(235,75,75) -> "异常"
            Color.rgb(238,190,55) -> "未验证"
            else -> "未知"
        }
        fun refreshSnapshot() {
            snapshot.removeAllViews()
            names.forEach { name ->
                val result=apiStatusResults[name]
                val color=result?.first ?: Color.rgb(238,190,55)
                val detail=result?.second ?: "尚无检测记录"
                val elapsed=apiStatusElapsedMs[name]?.let { " · ${it}ms" }.orEmpty()
                val updated=apiStatusUpdatedAt[name]?.let {
                    java.text.SimpleDateFormat("HH:mm:ss",Locale.getDefault()).format(java.util.Date(it))
                } ?: "暂无"
                val heading=row().apply {
                    addView(text(name,14f,white).apply { typeface=Typeface.DEFAULT_BOLD },LinearLayout.LayoutParams(0,-2,1f))
                    addView(text(stateLabel(color)+elapsed,12f,color))
                }
                snapshot.addView(heading)
                snapshot.addView(text(detail,12f,muted).apply { setPadding(0,dp(3),0,dp(2)) })
                snapshot.addView(text("最近更新：$updated",10.5f,muted).apply { setPadding(0,0,0,dp(8)) })
            }
        }
        refreshSnapshot()
        // 四项接口状态数量固定，直接完整展开；电视遥控器无需在状态框内滚动。
        snapshot.isFocusable=false
        snapshot.isFocusableInTouchMode=false
        snapshot.descendantFocusability=ViewGroup.FOCUS_BLOCK_DESCENDANTS
        content.addView(snapshot,LinearLayout.LayoutParams(-1,-2))

        lateinit var dialog:AlertDialog
        val refreshAll=TvDialog.capsuleButton(this,"重新检测全部") {
            runAutomaticApiChecks(force=true)
            status.text="已开始新一轮检测；正在等待四项接口返回。"
            refreshSnapshot()
        }
        refreshAll.id=View.generateViewId()
        content.addView(refreshAll,LinearLayout.LayoutParams(-1,-2).apply {
            topMargin=dp(10)
        })
        content.addView(text("单项手动检测",13f,muted).apply { setPadding(dp(8),dp(12),dp(8),dp(6)) })
        val actions=row().apply {
            isFocusable=false
            descendantFocusability=ViewGroup.FOCUS_AFTER_DESCENDANTS
        }
        val dotIdle=TvStyle.outline
        val dotOk=Color.rgb(86,196,122)
        val dotFail=Color.rgb(235,95,95)
        val lastAt=mutableMapOf<String,Long>()
        val busy=mutableSetOf<String>()
        val actionButtons=mutableListOf<Button>()
        fun dotLabel(name: String,color: Int): CharSequence {
            val span=android.text.SpannableString("●  $name")
            span.setSpan(object: android.text.style.ReplacementSpan() {
                override fun getSize(paint: android.graphics.Paint,text: CharSequence?,start: Int,end: Int,fm: android.graphics.Paint.FontMetricsInt?): Int =
                    (paint.textSize*0.55f).toInt()
                override fun draw(canvas: android.graphics.Canvas,text: CharSequence?,start: Int,end: Int,x: Float,top: Int,y: Int,bottom: Int,paint: android.graphics.Paint) {
                    val radius=paint.textSize*0.22f
                    val cy=(top+bottom)/2f
                    val oldColor=paint.color
                    paint.color=color
                    canvas.drawCircle(x+radius,cy,radius,paint)
                    paint.color=oldColor
                }
            },0,1,android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            return span
        }
        fun check(name: String,btn: TextView,probe: ()->String) {
            val now=System.currentTimeMillis()
            if(name in busy || now-(lastAt[name] ?: 0L)<3_000) {
                status.text="$name 请稍候再试"
                return
            }
            lastAt[name]=now
            busy+=name
            status.text="正在检测 $name…"
            btn.text=dotLabel(name,accent)
            val started=android.os.SystemClock.elapsedRealtime()
            val requestScope=if(name=="播放") RequestScope().also { apiStatusUiState.playbackProbeScopes.add(it) } else null
            lateinit var task: java.util.concurrent.Future<*>
            task=io.submit {
                try {
                    val result=runCatching { if(requestScope!=null) requestScope.run(probe) else probe() }
                    main.post {
                        if(!dialog.isShowing || isDestroyed) return@post
                        val elapsed=android.os.SystemClock.elapsedRealtime()-started
                        result.fold(
                            { detail -> status.text="$name · 正常 · $detail · ${elapsed}ms"; btn.text=dotLabel(name,dotOk) },
                            { problem -> status.text="$name · 失败 · ${(problem.message ?: problem.javaClass.simpleName).take(80)}"; btn.text=dotLabel(name,dotFail) }
                        )
                        busy-=name
                    }
                } finally {
                    if(requestScope!=null) {
                        apiStatusUiState.playbackProbeScopes.remove(requestScope)
                        main.post { apiStatusUiState.playbackProbeJobs.remove(task) }
                    }
                }
            }
            if(requestScope!=null) apiStatusUiState.playbackProbeJobs.add(task)
        }
        fun add(name: String,probe: ()->String) {
            lateinit var btn:Button
            btn=TvDialog.capsuleButton(this,name) { check(name,btn,probe) }
            btn.id=View.generateViewId()
            btn.text=dotLabel(name,dotIdle)
            btn.textSize=13f
            btn.nextFocusUpId=refreshAll.id
            btn.nextFocusDownId=refreshAll.id
            actionButtons+=btn
            btn.minWidth=0
            btn.setPadding(dp(6),0,dp(6),0)
            actions.addView(btn,LinearLayout.LayoutParams(0,-2,1f).apply { rightMargin=dp(5) })
        }
        add("首页") { "返回 ${repository.home(1,library.contentType).items.size} 条" }
        add("搜索") { "返回 ${repository.search("修仙",1,library.contentType).items.size} 条" }
        add("播放") {
            val progress=library.history().firstOrNull() ?: throw IllegalStateException("暂无观看记录，先播放一集")
            val info=playbackStreamSource(repository.http).stream(progress.episodeId,library.maxQuality)
            "获取成功 · ${info.quality}（${playbackApiLabel()}线路）"
        }
        add("联想") { "官方接口直连成功 · 返回 ${repository.probeSuggest("修仙")} 个词（已绕过缓存）" }
        actionButtons.forEachIndexed { index,btn ->
            btn.nextFocusLeftId=actionButtons.getOrNull(index-1)?.id ?: actionButtons.last().id
            btn.nextFocusRightId=actionButtons.getOrNull(index+1)?.id ?: actionButtons.first().id
        }
        content.addView(actions)
        content.addView(text("自动检测结果会随新一轮检测实时更新；单项手动检测不会改变顶部四叶灯的自动检测状态。",11.5f,muted)
            .apply { setPadding(dp(8),dp(10),dp(8),0) })

        dialog=AlertDialog.Builder(this).setView(TvDialog.card(this,content)).create()
        TvDialog.styleWindow(this,dialog,widthFraction=0.72f,maxWidthDp=760) {
            refreshAll.requestFocus()
        }
        val refreshTask=object:Runnable {
            override fun run() {
                if(dialog.isShowing && !isDestroyed) {
                    refreshSnapshot()
                    main.postDelayed(this,500L)
                }
            }
        }
        dialog.setOnDismissListener {
            main.removeCallbacks(refreshTask)
            if(anchor.isAttachedToWindow) anchor.requestFocus()
        }
        dialog.show()
        main.post(refreshTask)
    }