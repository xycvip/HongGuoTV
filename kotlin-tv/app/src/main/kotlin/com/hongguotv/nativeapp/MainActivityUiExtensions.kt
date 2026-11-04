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

    internal fun MainActivity.applyDeviceOrientationPolicy() {
        if (isTelevisionDevice || screen != "player") {
            requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            return
        }
        requestedOrientation = when (phoneOrientationMode) {
            1 -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            2 -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            else -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    internal fun MainActivity.applyVideoOrientation(videoSize: VideoSize) {
        if (!isPhoneDevice || screen != "player" || phoneOrientationMode != 0 || videoSize.width <= 0 || videoSize.height <= 0) return
        val ratio = videoSize.width.toFloat() * videoSize.pixelWidthHeightRatio.coerceAtLeast(0.01f) / videoSize.height
        val rotatedRatio = if (videoSize.unappliedRotationDegrees % 180 != 0) 1f / ratio else ratio
        val target = if (rotatedRatio >= 1.05f) android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        if (requestedOrientation != target) requestedOrientation = target
    }

    internal fun MainActivity.focusStyle(view: View,selected: Boolean=false) {
        view.id=View.generateViewId(); view.isFocusable=true; view.isFocusableInTouchMode=true
        fun paint(focused: Boolean) { view.background=rounded(if(focused) TvStyle.raised else if(selected) TvStyle.selectedSurface else surface,if(focused) accent else Color.TRANSPARENT) }
        paint(false); view.setOnFocusChangeListener { _,focused -> paint(focused) }
    }

    internal fun MainActivity.addButton(parent: LinearLayout,label: String,selected: Boolean=false,onClick: ()->Unit = {}): TextView {
        val view=button(label,selected,onClick)
        if (screen=="player" && isPhoneDevice) {
            view.textSize=14f
            view.minHeight=dp(48)
            view.setPadding(dp(7),dp(12),dp(7),dp(12))
        }
        parent.addView(view,lp().apply { width=LinearLayout.LayoutParams.WRAP_CONTENT; rightMargin=dp(if(screen=="player" && isPhoneDevice) 3 else 8) })
        return view
    }

    @Suppress("DEPRECATION")
    internal fun MainActivity.applyImmersiveFullscreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val params = window.attributes
            params.layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else {
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            window.attributes = params
        }
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.hide(
                android.view.WindowInsets.Type.statusBars() or
                android.view.WindowInsets.Type.navigationBars()
            )
            window.insetsController?.systemBarsBehavior =
                android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    internal fun MainActivity.base(title: String?=null): LinearLayout {
        root.removeAllViews(); root.setBackgroundColor(bg)
        val container=column(); val horizontal=dp(widthDp()*.05f); val vertical=(resources.displayMetrics.heightPixels*.05f).toInt()
        container.setPadding(horizontal,vertical,horizontal,vertical); root.addView(container,FrameLayout.LayoutParams(-1,-1))
        if(title!=null) { val heading=row(); addButton(heading,"‹ 返回") { goBack() }; heading.addView(text(title,22f).apply { setTypeface(null,Typeface.BOLD); gravity=Gravity.CENTER_VERTICAL },lp(0,dp(44)).apply { weight=1f }); container.addView(heading) }
        applyClock()
        // 启动画面覆盖在首个页面之上；自动播放时一直保留到视频首帧。
        bootSplash?.let { splash ->
            if(splash.parent==null) root.addView(splash,FrameLayout.LayoutParams(-1,-1))
            if(!bootAutoPlaybackPending) main.postDelayed({
                if(!isDestroyed && !bootAutoPlaybackPending && bootSplash===splash) hideBootSplash()
            },850)
        }
        return container
    }

    internal fun MainActivity.applyClock() {
        clockTick?.let(main::removeCallbacks); clockTick=null
        clockView?.let { root.removeView(it) }; clockView=null
        if(library.isLoaded && library.showClock) {
            val screenWidth=widthDp()
            val screenHeight=heightDp()
            val mobile=screenWidth < 600f
            val clockSize=if(mobile) 12f else 15f
            val horizontalPadding=if(mobile) 4 else 7
            val verticalPadding=if(mobile) 2 else 3
            val view=text("",clockSize,muted).apply {
                importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
                isFocusable=false; isClickable=false
                alpha=0.88f
                setPadding(dp(horizontalPadding),dp(verticalPadding),dp(horizontalPadding),dp(verticalPadding))
            }
            val topMargin=if(mobile) 8 else (screenHeight*0.025f).toInt().coerceIn(12,26)
            val rightMargin=if(mobile) 14 else (screenWidth*0.03f).toInt().coerceIn(18,36)
            root.addView(view,FrameLayout.LayoutParams(-2,-2,Gravity.TOP or Gravity.END).apply { this.topMargin=dp(topMargin); this.rightMargin=dp(rightMargin) })
            clockView=view
            val tick=object: Runnable {
                override fun run() {
                    if(!foreground || isDestroyed || clockView==null) return
                    clockView?.text=java.text.SimpleDateFormat("HH:mm",java.util.Locale.getDefault()).format(java.util.Date())
                    main.postDelayed(this,60_000L-(System.currentTimeMillis()%60_000L)+100L)
                }
            }
            clockTick=tick
            tick.run()
        }
    }

    internal fun MainActivity.loadCover(view: ImageView,url: String) {
        var ancestor: View?=view
        var focused=false
        while(ancestor!=null && ancestor!==root) {
            if(ancestor.isFocused) { focused=true; break }
            ancestor=ancestor.parent as? View
        }
        artwork.load(view,url,if(focused) ArtworkLoader.PRIORITY_FOCUSED else ArtworkLoader.PRIORITY_VISIBLE)
    }

    internal fun MainActivity.cancelWork() {
        deferredWork?.discard?.invoke(); deferredWork=null
        workScope?.cancel(); workScope=null
        workJob?.cancel(true); workJob=null
        (io as? java.util.concurrent.ThreadPoolExecutor)?.purge()
    }

    internal fun MainActivity.invalidatePage() {
        generation++; cancelWork(); artwork.cancelPage()
    }

    internal fun MainActivity.releaseCatalogViews() {
        homeScreen?.let { homePosition=it.capturePosition() }
        (catalogRecycler?.layoutManager as? GridLayoutManager)?.let { val first=it.findFirstVisibleItemPosition(); scrollPositions[catalogPageKey]=if(tab==2 || tab==7) (first-1).coerceAtLeast(0) else first }
        homeScreen=null; selectionPreview=null; catalogRecycler=null; catalogAdapter=null; loadingMore=false
        nav.clear(); navByTab.clear(); searchNavShortcut=null; theaterFilterHost=null; typeButtons.clear(); aggregateButton=null; searchInput=null; searchButton=null; searchActions.clear()
        azFirst=null; azLastRow.clear(); suggestRow=null; suggestFirst=null; suggestTicket++; main.removeCallbacks(pendingSuggest)
        suggestVertical=false; searchBody=null; keyboardKeys=emptyMap(); keyboardRows=emptyList(); historyFirst=null; historyItems=emptyList(); suggestScrollRef=null; resultFirstCard=null
        historyManage=null; rankSwitch=null; rankRefresh=null; rankSubtitle=null; genreButton=null; genreClear=null; collectionBack=null; favoriteBadges.clear()
        favoriteStatus=null; favoriteCheck=null; favoriteFilterFirst=null; favoriteFilterButtons=emptyList(); homeUpdates=null; resumeCards.clear()
    }

    internal fun MainActivity.message(parent: LinearLayout,label: String) { parent.addView(text(label,17f,muted).apply { setPadding(0,dp(28),0,dp(18)) }) }

    internal fun MainActivity.error(parent: LinearLayout,problem: Throwable,retry: ()->Unit) {
        debugLogError("页面加载",problem)
        message(parent,"暂时无法加载，请检查网络后重试。")
        val detail=if(problem is java.net.UnknownHostException) "无法连接内容网站" else if(problem is java.net.SocketTimeoutException) "连接超时" else problem.message.orEmpty().replace(Regex("https?://\\S+"),"[地址]").take(160)
        parent.addView(text(detail,13f,muted)); addButton(parent,"重试",onClick=retry).requestFocus()
    }

internal fun MainActivity.dp(value: Number)=(value.toFloat()*resources.displayMetrics.density).toInt()
/** 主界面始终横屏；仅进入手机播放页后，才按播放方向偏好/视频宽高比切换。 */


internal fun MainActivity.activeFrameResizeMode() = if (isPhoneDevice)
    androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT else frameResizeMode()
internal fun MainActivity.widthDp()=resources.displayMetrics.widthPixels/resources.displayMetrics.density
internal fun MainActivity.heightDp()=resources.displayMetrics.heightPixels/resources.displayMetrics.density
internal fun MainActivity.lp(w: Int=LinearLayout.LayoutParams.MATCH_PARENT,h: Int=LinearLayout.LayoutParams.WRAP_CONTENT)=LinearLayout.LayoutParams(w,h)
internal fun MainActivity.rounded(color: Int,border: Int=Color.TRANSPARENT)=TvStyle.shape(this,color,border)
internal fun MainActivity.text(value: String,size: Float=16f,color: Int=white)=TextView(this).apply { text=value; textSize=size; setTextColor(color); includeFontPadding=false }
internal fun MainActivity.column()=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
internal fun MainActivity.row()=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL }

internal fun MainActivity.button(label: String,selected: Boolean=false,onClick: ()->Unit): TextView = text(label,15f).apply {
    gravity=Gravity.CENTER; setPadding(dp(14),dp(10),dp(14),dp(10)); minHeight=dp(42); focusStyle(this,selected); setOnClickListener { onClick() }
}


internal fun MainActivity.formatTime(millis: Long): String {
    val seconds = millis.coerceAtLeast(0) / 1000
    return "%02d:%02d".format(seconds / 60, seconds % 60)
}
