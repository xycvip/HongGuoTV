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

    internal fun MainActivity.showLibraryError(problem: Throwable) {
        val body=base(); error(body,problem) { message(base(),"正在读取本机记录…"); loadLibrary() }
    }

    internal fun MainActivity.markStartupRecommendationPlaying() {
        if(!startupAutoPlaybackActive || !bootAutoPlaybackPending) return
        bootAutoPlaybackPending=false
        main.removeCallbacks(startupRecommendationTimeout)
        android.util.Log.i("HongguoTV","Startup recommendation playback started; 8-second wait limit cleared")
    }

    internal fun MainActivity.leaveStartupRecommendation() {
        if(!bootAutoPlaybackPending && !startupAutoPlaybackActive) return
        startupRecommendationExpired=true
        main.removeCallbacks(startupRecommendationTimeout)
        bootAutoPlaybackPending=false
        startupAutoPlaybackActive=false
        startupPreviewEpisodeId=null
        startupPreviewPlayedMs=0L
        startupPreviewLastTickAt=0L
        initialCatalogPending=false
        pendingDetail=null
        detail=null
        directPlayback=false
        cancelWork()
        cancelRecovery()
        cancelPrefetch()
        player?.pause()
        releasePlayer()
        hideBootSplash()
        showCatalog(load=true)
    }

    internal fun MainActivity.refreshHomeDataInBackground(force:Boolean=false) {
        if(isDestroyed || !library.isLoaded || homeRefreshInFlight) return
        if(!force && startupHomeRefreshStarted) return
        if(!force) startupHomeRefreshStarted=true
        homeRefreshInFlight=true
        val type=library.contentType
        io.execute {
            val loaded=runCatching { repository.home(1,type,null) }
            main.post {
                homeRefreshInFlight=false
                if(isDestroyed || resettingAllData) return@post
                loaded.onSuccess { resultPage ->
                    library.cacheHome(type,resultPage.items,resultPage.hasMore)
                    if(library.contentType==type && tab==0 && collection==null && screen=="catalog") {
                        if(page==1) {
                            catalog=resultPage.items; hasMore=resultPage.hasMore; homeFromCache=false
                            homeScreen?.updateShelves(homeShelves(),requestFocus=false)
                        }
                    }
                    android.util.Log.i("HongguoTV","Background home refresh completed force=$force items=${resultPage.items.size}")
                }.onFailure { problem ->
                    // 保留已有缓存与首页模型；后台刷新失败不打断用户操作。
                    android.util.Log.i("HongguoTV","Background home refresh failed; cached content retained: ${problem.message}")
                }
            }
        }
    }

    internal fun MainActivity.launchRandomPlayback() {
        if(launchRandomPlaybackStarted || isDestroyed || resettingAllData || !library.isLoaded || !foreground) return
        launchRandomPlaybackStarted=true
        startupRecommendationExpired=false
        refreshHomeDataInBackground()
        bootAutoPlaybackPending=true
        initialCatalogPending=false
        main.removeCallbacks(startupRecommendationTimeout)
        main.postDelayed(startupRecommendationTimeout,8000L)
        fun playRandomOrFallback() {
            if(isDestroyed || resettingAllData || startupRecommendationExpired || !bootAutoPlaybackPending) return
            if(!foreground) {
                main.removeCallbacks(startupRecommendationTimeout)
                launchRandomPlaybackStarted=false
                initialCatalogPending=true
                return
            }
            val picked=randomRecommender.pick(1).firstOrNull()
                ?: library.cachedHome(library.contentType)?.items?.shuffled()?.firstOrNull()
            if(picked!=null) {
                startupAutoPlaybackActive=true
                openDetail(picked,resume=true,autoplay=true)
            } else {
                startupAutoPlaybackActive=false
                main.removeCallbacks(startupRecommendationTimeout)
                bootAutoPlaybackPending=false
                showCatalog(load=true)
                Toast.makeText(this,"暂时无法获取随机推荐，请检查网络后重试",Toast.LENGTH_LONG).show()
            }
        }
        val cached=randomRecommender.pick(1).firstOrNull()
        if(cached!=null) {
            startupAutoPlaybackActive=true
            openDetail(cached,resume=true,autoplay=true)
        } else replenishRandomPool { playRandomOrFallback() }
    }

    internal fun MainActivity.showInitialScreen() {
        if(!isDestroyed && !resettingAllData && foreground && library.isLoaded && initialCatalogPending) {
            // 每次冷启动首页统一从短剧开始；用户仍可在本次使用中切换到漫剧。
            library.contentType = ContentType.SHORT
            if(startupAutoplayEnabled()) launchRandomPlayback()
            else {
                initialCatalogPending=false
                // 冷启动进入首页时明确把焦点交给「首页」导航，避免系统按视图树顺序
                // 默认聚焦第一个快捷入口（四叶状态灯/随便播放）。
                showCatalog(load=true,focusNav=true)
            }
        }
    }

    internal fun MainActivity.loadLibrary() {
        libraryLoadError=null
        library.loadAsync(onFailure={ problem ->
            if(!isDestroyed && !resettingAllData) { libraryLoadError=problem; if(foreground) showLibraryError(problem) }
        }) {
            if(!isDestroyed && !resettingAllData && foreground) {
                cloudAccount.syncLocalFavorites(library.favorites())
                showInitialScreen()
                favoriteMonitor.check()
            }
        }
    }

    internal fun MainActivity.showBootSplash() {
        // 首次启动已显示启动动画时复用同一层，避免自动播放切页时闪一下。
        if(bootSplash?.parent===root) return
        hideBootSplash()
        val overlay=FrameLayout(this).apply {
            background=GradientDrawable(GradientDrawable.Orientation.TL_BR,intArrayOf(Color.rgb(4,7,16),Color.rgb(11,20,39),Color.rgb(3,5,12)))
            isClickable=false; isFocusable=false; importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val content=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; gravity=Gravity.CENTER; setPadding(dp(32),dp(24),dp(32),dp(24)) }
        val title=TextView(this).apply {
            text="红果TV"; textSize=42f; setTextColor(Color.WHITE); gravity=Gravity.CENTER
            setTypeface(Typeface.create("sans-serif",Typeface.BOLD)); setShadowLayer(dp(18).toFloat(),0f,0f,accent)
        }
        val tagline=TextView(this).apply {
            text="精彩即将呈现"; textSize=16f; setTextColor(Color.rgb(190,202,225)); gravity=Gravity.CENTER
            letterSpacing=0.18f; setPadding(0,dp(12),0,dp(18))
        }
        val sweep=View(this).apply { background=GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,intArrayOf(Color.TRANSPARENT,accent,Color.TRANSPARENT)) }
        content.addView(title,LinearLayout.LayoutParams(-2,-2))
        content.addView(tagline,LinearLayout.LayoutParams(-2,-2))
        content.addView(sweep,LinearLayout.LayoutParams(dp(240),dp(2)))
        overlay.addView(content,FrameLayout.LayoutParams(-2,-2,Gravity.CENTER))
        root.addView(overlay,FrameLayout.LayoutParams(-1,-1))
        bootSplash=overlay
        val glow=ObjectAnimator.ofFloat(title,View.ALPHA,0.76f,1f).apply { duration=1500; repeatCount=ValueAnimator.INFINITE; repeatMode=ValueAnimator.REVERSE }
        val lift=ObjectAnimator.ofFloat(title,View.TRANSLATION_Y,dp(4).toFloat(),0f).apply { duration=1500; repeatCount=ValueAnimator.INFINITE; repeatMode=ValueAnimator.REVERSE }
        val sweepAnim=ObjectAnimator.ofFloat(sweep,View.TRANSLATION_X,-dp(250).toFloat(),dp(250).toFloat()).apply { duration=2300; repeatCount=ValueAnimator.INFINITE; repeatMode=ValueAnimator.RESTART }
        bootSplashAnimator=AnimatorSet().apply { playTogether(glow,lift,sweepAnim); start() }
    }

    internal fun MainActivity.hideBootSplash() {
        bootSplashAnimator?.cancel(); bootSplashAnimator=null
        bootSplash?.let { (it.parent as? android.view.ViewGroup)?.removeView(it) }
        bootSplash=null
    }

internal fun MainActivity.startupAutoplayEnabled() =
    getSharedPreferences("startup_playback",Context.MODE_PRIVATE).getBoolean("autoplay_recommendation",false)
