// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.app.Activity
import android.os.Build
import android.graphics.Color
import android.graphics.Typeface
import android.view.*
import android.widget.*
import androidx.recyclerview.widget.GridLayoutManager
import androidx.media3.common.*
import com.hongguotv.core.*
import java.util.Locale

    /**
     * 全应用统一使用横屏方向。手机播放时不再根据设备姿态或视频宽高/旋转元数据切换方向，
     * 避免视频方向回调触发 Activity 配置变化，干扰双播放器交接和播放生命周期。
     */
    internal fun MainActivity.applyDeviceOrientationPolicy() {
        requestedOrientation = if (isPhoneDevice && screen == "player") {
            phonePlaybackOrientation
        } else {
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
    }

    /** 手机播放器内手动切换固定方向；选择仅在当前 App 进程内保留。 */
    internal fun MainActivity.togglePhonePlaybackOrientation(): Int {
        if (!isPhoneDevice || screen != "player") return phonePlaybackOrientation
        phonePlaybackOrientation = if (
            phonePlaybackOrientation == android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        ) {
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } else {
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
        requestedOrientation = phonePlaybackOrientation
        return phonePlaybackOrientation
    }

    internal fun MainActivity.phonePlaybackOrientationLabel(): String =
        if (phonePlaybackOrientation == android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT) "竖屏" else "横屏"

    internal fun MainActivity.focusStyle(view: View,selected: Boolean=false) {
        view.id=View.generateViewId(); view.isFocusable=true; view.isFocusableInTouchMode=true
        fun paint(focused: Boolean) {
            // 选项框统一走 FocusFrame：外圈柔光 + 内圈白环，零越界（不缩放不投影）。
            FocusFrame.paint(
                view, focused,
                normalFill = if(selected) TvStyle.colors.selectedSurface else TvStyle.colors.surface,
            )
            // 选中项用强调色文字标出"当前在哪"，焦点移上去时回到主文字色，避免同色叠同色。
            if(view is TextView) view.setTextColor(
                when {
                    focused -> TvStyle.colors.textPrimary
                    selected -> TvStyle.colors.accent
                    else -> TvStyle.colors.textPrimary
                }
            )
        }
        paint(false); view.setOnFocusChangeListener { focusedView,focused ->
            paint(focused)
            val focusTag=focusedView.tag?.toString()
            if(focused && focusTag?.startsWith("theater-filter:")==true) theaterFocusTag=focusTag
            if(focused && focusTag?.startsWith("rank:")==true) rankFocusTag=focusTag
        }
    }

    internal fun MainActivity.addButton(parent: LinearLayout,label: String,selected: Boolean=false,onClick: ()->Unit = {}): TextView {
        val view=button(label,selected,onClick)
        if (screen=="player" && isPhoneDevice) {
            view.textSize=TvMetric.TEXT_BODY
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

    /**
     * 页面骨架：左侧竖栏 + 右侧内容。
     *
     * 竖栏在这里统一挂载，而不是由各页面自己调 —— 详情页、片源页、设置页都是同一套外壳，
     * 从首页点进详情不会出现"导航忽然消失、按返回才回来"。播放器不走这个骨架。
     */
    internal fun MainActivity.base(title: String?=null): LinearLayout {
        root.removeAllViews(); root.setBackgroundColor(bg)
        val container=column()
        val wide=widthDp() >= 780f
        val shell=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; clipChildren=false }
        val railHost=FrameLayout(this).apply {
            id=View.generateViewId(); setBackgroundColor(TvStyle.colors.rail); clipChildren=false
        }
        shell.addView(railHost,LinearLayout.LayoutParams(dp(railWidthDp()),-1))
        container.setPadding(
            dp(if(wide) 28 else 20),
            dp(if(wide) 22 else 16),
            dp(if(wide) 40 else 24),
            dp(if(wide) 22 else 16),
        )
        shell.addView(container,LinearLayout.LayoutParams(0,-1,1f))
        // 竖栏与内容之间只留一条 1dp 细线，不用阴影堆层次。
        railHost.addView(
            View(this).apply { setBackgroundColor(TvStyle.colors.railStroke) },
            FrameLayout.LayoutParams(dp(1),-1,Gravity.END),
        )
        root.addView(shell,FrameLayout.LayoutParams(-1,-1))
        // 页面重建时清空上一轮的导航引用，再按当前页面重新登记。
        nav.clear(); navByTab.clear(); searchNavShortcut=null
        stopApiStatusAnimations(); apiStatusViews.clear()
        renderNavigationRail(railHost,container)
        // 焦点环画在 View 边界之外：页面建完统一放开整棵树的裁剪，
        // 边缘海报/按钮的选项框不再缺一角（FocusFrame 是唯一入口）。
        main.post { if(!isDestroyed) FocusFrame.unclipTree(root) }
        if(title!=null) {
            // 返回是次要操作，用纯文字语气；原来给它一块灰底，和页面标题抢了同一行的高度。
            val heading=row(); addToneButton(heading,"‹ 返回",TvTone.Ghost) { goBack() }
            heading.addView(text(title,TvMetric.TEXT_TITLE).apply { setTypeface(null,Typeface.BOLD); gravity=Gravity.CENTER_VERTICAL; includeFontPadding=false },lp(0,dp(44)).apply { weight=1f })
            container.addView(heading)
        }
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
            val view=text("",clockSize,TvStyle.colors.textSecondary).apply {
                importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
                isFocusable=false; isClickable=false
                // 真机反馈不要背景块。压在亮画面上的可读性改用文字阴影解决：
                // 1px 黑影 + 轻模糊，暗背景上隐形、亮画面上托住笔画。
                setShadowLayer(dp(1).toFloat(),0f,0f,0xB4000000.toInt())
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
        // 目录页面重建或离开时，停止四叶灯动画并解除旧 View 引用。
        stopApiStatusAnimations()
        apiStatusViews.clear()
        catalogNavFlower=null
        homeScreen?.let { homePosition=it.capturePosition() }
        (catalogRecycler?.layoutManager as? GridLayoutManager)?.let { val first=it.findFirstVisibleItemPosition(); scrollPositions[catalogPageKey]=if(tab==2 || tab==7) (first-1).coerceAtLeast(0) else first }
        homeScreen=null; catalogRecycler=null; catalogAdapter=null; loadingMore=false
        nav.clear(); navByTab.clear(); searchNavShortcut=null; theaterFilterHost=null; aggregateButton=null; searchInput=null; searchButton=null; searchActions.clear()
        azFirst=null; azLastRow.clear(); suggestRow=null; suggestFirst=null; suggestTicket++; main.removeCallbacks(pendingSuggest)
        suggestVertical=false; searchBody=null; keyboardKeys=emptyMap(); keyboardRows=emptyList(); historyFirst=null; historyItems=emptyList(); suggestScrollRef=null; resultFirstCard=null
        historyManage=null; rankSwitch=null; actorRankButton=null; rankRefresh=null; rankSubtitle=null; collectionBack=null; favoriteBadges.clear()
        favoriteStatus=null; favoriteCheck=null; favoriteFilterFirst=null; favoriteFilterButtons=emptyList(); homeUpdates=null; resumeCards.clear()
    }

    internal fun MainActivity.message(parent: LinearLayout,label: String) {
        parent.addView(text(label,TvMetric.TEXT_LABEL,TvStyle.colors.textSecondary).apply {
            setPadding(0,dp(40),0,dp(24)); setLineSpacing(0f,1.35f)
        })
    }

    /**
     * 加载态。改造前只有一行灰字，电视上屏幕大半是空的，很难判断是"在加载"还是"卡住"。
     * 现在给一个转圈 + 文案，位置在内容区上方而不是垂直居中，避免内容到位时大幅跳动。
     */
    internal fun MainActivity.loading(parent: LinearLayout,label: String) {
        val box=column().apply { gravity=Gravity.CENTER_HORIZONTAL; setPadding(0,dp(52),0,dp(36)) }
        box.addView(ProgressBar(this).apply {
            isIndeterminate=true
            indeterminateTintList=android.content.res.ColorStateList.valueOf(TvStyle.colors.accent)
        },lp(dp(32),dp(32)))
        box.addView(text(label,TvMetric.TEXT_BODY,TvStyle.colors.textSecondary).apply {
            gravity=Gravity.CENTER; setPadding(0,dp(14),0,0)
        },lp(-1,-2))
        parent.addView(box,lp(-1,-2))
    }

    internal fun MainActivity.error(parent: LinearLayout,problem: Throwable,retry: ()->Unit) {
        debugLogError("页面加载",problem)
        val box=column().apply { setPadding(0,dp(52),0,dp(10)) }
        box.addView(text("暂时无法加载",TvMetric.TEXT_TITLE).apply {
            setTypeface(null,Typeface.BOLD); includeFontPadding=false
        })
        box.addView(text("请检查网络后重试",TvMetric.TEXT_BODY,TvStyle.colors.textSecondary).apply {
            setPadding(0,dp(8),0,0)
        })
        parent.addView(box)
        val detail=if(problem is java.net.UnknownHostException) "无法连接内容网站" else if(problem is java.net.SocketTimeoutException) "连接超时" else problem.message.orEmpty().replace(Regex("https?://\\S+"),"[地址]").take(160)
        parent.addView(text(detail,TvMetric.TEXT_CAPTION,TvStyle.colors.textTertiary).apply { setPadding(0,0,0,dp(12)) })
        addToneButton(parent,"重试",TvTone.Primary) { retry() }.requestFocus()
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
// 容器默认放开 clipChildren：焦点放大的按钮/卡片需要画到自身边界之外，
// 否则 3% 的放大刚好被父容器裁掉四个角，看起来像"边框被啃掉"。
internal fun MainActivity.column()=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; clipChildren=false }
internal fun MainActivity.row()=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL; clipChildren=false }

internal fun MainActivity.button(label: String,selected: Boolean=false,onClick: ()->Unit): TextView = text(label,15f).apply {
    gravity=Gravity.CENTER; setPadding(dp(14),dp(10),dp(14),dp(10)); minHeight=dp(42); focusStyle(this,selected); setOnClickListener { onClick() }
}


internal fun MainActivity.formatTime(millis: Long): String {
    val seconds = millis.coerceAtLeast(0) / 1000
    return "%02d:%02d".format(seconds / 60, seconds % 60)
}
