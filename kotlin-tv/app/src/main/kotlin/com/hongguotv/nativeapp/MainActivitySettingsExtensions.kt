// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.app.Activity
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

    internal fun MainActivity.settings(parent: LinearLayout, footerHost: LinearLayout) {
        val settingRows=mutableListOf<TextView>()
        val categoryItems=mutableListOf<MutableList<TextView>>()
        val categoryIndicators=mutableListOf<TextView>()
        val categoryHeadingCards=mutableListOf<LinearLayout>()
        // 左侧主分类只显示分类名称；获得右侧具体设置项焦点时，才显示该子项说明。
        val categoryHeadingSubtitles=mutableListOf<TextView>()
        val categoryHeadingTitles=mutableListOf<TextView>()

        // Settings sections are listed in the same order as the left navigation.

        val columns=when {
            widthDp()<600f -> 2
            widthDp()<1100f -> 3
            else -> 4
        }
        // A single seamless settings surface: subtle separators define sections instead of nested cards.
        val contentHost=row().apply { gravity=Gravity.TOP }
        parent.addView(contentHost,lp())
        // 左侧分类栏按当前可用屏幕尺寸自适应，避免手机端过窄、电视端比例失衡。
        val screenWidthDp=widthDp().coerceAtLeast(1f)
        val screenHeightDp=heightDp().coerceAtLeast(1f)
        val headingWidth=(screenWidthDp*0.125f).toInt().coerceIn(84,168)
        val categoryScale=minOf(
            (screenWidthDp/800f).coerceIn(0.88f,1.16f),
            (screenHeightDp/600f).coerceIn(0.88f,1.12f)
        )
        // 设置页采用更克制的字号层级，避免分类标题过大、说明文字过小。
        val categoryTitleSize=(16.5f*categoryScale).coerceIn(15f,19f)
        val categoryTitleHeight=(headingWidth*0.40f*categoryScale).toInt().coerceIn(44,60)
        val categorySubtitleSize=(12.5f*categoryScale).coerceIn(11.5f,14f)
        val categoryController = SettingsCategoryController(
            activity = this@settings,
            categoryHeadingCards = categoryHeadingCards,
            categoryIndicators = categoryIndicators,
            categoryHeadingSubtitles = categoryHeadingSubtitles,
            categoryHeadingTitles = categoryHeadingTitles,
            categoryScale = categoryScale,
            categoryTitleSize = categoryTitleSize,
            categoryTitleHeight = categoryTitleHeight,
            categorySubtitleSize = categorySubtitleSize,
            muted = muted,
            white = white,
            dp = { value -> dp(value) },
            rounded = { color -> rounded(color) },
        )
        val categoryCardExtension=(8f*categoryScale).toInt().coerceIn(6,10)
        val categoryCard=column().apply {
            // 分类背景卡片向右适度延伸；允许子卡片绘制到列宽之外。
            clipChildren=false
            clipToPadding=false
        }
        contentHost.addView(categoryCard,lp(dp(headingWidth),-2))
        // 左右两栏保留与设置项外边距一致的留白，不绘制竖向分割线。
        val columnGap=(5f*categoryScale).toInt().coerceIn(3,7)
        contentHost.addView(Space(this),lp(dp(columnGap),1))
        val sectionsHost=column()
        contentHost.addView(sectionsHost,LinearLayout.LayoutParams(0,-2,1f))

        val sectionFactory = SettingsSectionFactory(
            activity = this@settings,
            categoryIndicators = categoryIndicators,
            categoryHeadingSubtitles = categoryHeadingSubtitles,
            categoryHeadingTitles = categoryHeadingTitles,
            categoryHeadingCards = categoryHeadingCards,
            categoryItems = categoryItems,
            categoryController = categoryController,
            categoryScale = categoryScale,
            categoryTitleSize = categoryTitleSize,
            categoryTitleHeight = categoryTitleHeight,
            categorySubtitleSize = categorySubtitleSize,
            categoryCardExtension = categoryCardExtension,
            headingWidth = headingWidth,
            columns = columns,
            categoryCard = categoryCard,
            sectionsHost = sectionsHost,
            white = white,
            muted = muted,
            dp = { value -> dp(value) },
            createText = { value, size, color -> text(value, size, color) },
            createColumn = { column() },
            layoutParams = { width, height -> lp(width, height) },
            rounded = { color -> rounded(color) }
        )
        val section: (String, String) -> Pair<LinearLayout, GridLayout> = sectionFactory::section
        val playback=section("播放","清晰度、倍速与连播设置").second
        val danmaku=section("弹幕","官方弹幕、显示效果与屏蔽词设置").second
        val data=section("数据","备份记录、统计与缓存管理").second
        val remote=section("按键","自定义方向键与确认键操作").second
        val appearance=section("外观","界面风格与播放画面显示").second
        val system=section("关于","软件信息与恢复设置").second
        val debugSection=section("调试模式","运行状态、诊断信息与日志管理")
        val debug=debugSection.second

        // 左侧主设置分类卡片的右边缘，动态对齐导航栏搜索按钮背景框的右边缘。
        fun alignCategoryCardsToSearchShortcut() {
            val search=searchNavShortcut ?: return
            if(categoryCard.width<=0 || search.width<=0) return
            val categoryLocation=IntArray(2)
            val searchLocation=IntArray(2)
            categoryCard.getLocationOnScreen(categoryLocation)
            search.getLocationOnScreen(searchLocation)
            val alignedWidth=(searchLocation[0]+search.width-categoryLocation[0]).coerceAtLeast(dp(1))
            val categoryParams=categoryCard.layoutParams as? LinearLayout.LayoutParams
            if(categoryParams!=null && categoryParams.width!=alignedWidth) {
                categoryParams.width=alignedWidth
                categoryCard.layoutParams=categoryParams
            }
            categoryHeadingCards.forEach { card ->
                val params=card.layoutParams as? LinearLayout.LayoutParams ?: return@forEach
                if(params.width!=alignedWidth || params.rightMargin!=0) {
                    params.width=alignedWidth
                    params.rightMargin=0
                    card.layoutParams=params
                }
            }
        }
        categoryCard.addOnLayoutChangeListener { _,_,_,_,_,_,_,_,_ -> alignCategoryCardsToSearchShortcut() }
        searchNavShortcut?.addOnLayoutChangeListener { _,_,_,_,_,_,_,_,_ -> alignCategoryCardsToSearchShortcut() }
        parent.post { alignCategoryCardsToSearchShortcut() }

        // 右侧设置项与左侧分类栏共用屏幕缩放系数，保持两侧视觉比例一致。
        val settingTextSize=(13.5f*categoryScale).coerceIn(12.5f,14.5f)
        val settingItemHeight=(64f*categoryScale).toInt().coerceIn(54,72)
        val settingHorizontalPadding=(10f*categoryScale).toInt().coerceIn(7,13)
        val settingVerticalPadding=(8f*categoryScale).toInt().coerceIn(6,11)
        val settingFactory = SettingsItemFactory(
            activity = this@settings,
            settingRows = settingRows,
            categoryItems = categoryItems,
            categoryController = categoryController,
            settingTextSize = settingTextSize,
            settingItemHeight = settingItemHeight,
            settingHorizontalPadding = settingHorizontalPadding,
            settingVerticalPadding = settingVerticalPadding,
            categoryScale = categoryScale,
            dp = { value -> dp(value) },
            createButton = { label, action -> button(label, onClick = action) },
        )
        fun setting(group:GridLayout,category:Int,label:String,action:()->Unit):TextView =
            settingFactory.setting(group,category,label,action)


        var rechainSettings:()->Unit={}
        fun setDebugCategoryVisible(visible:Boolean) {
            debugSettingRevealedThisSession=visible
            categoryHeadingCards.getOrNull(6)?.visibility=if(visible) View.VISIBLE else View.GONE
            debugSection.first.visibility=if(visible) View.VISIBLE else View.GONE
            categoryItems.getOrNull(6)?.forEach { it.visibility=if(visible) View.VISIBLE else View.GONE }
            rechainSettings()
        }

        // 调试模式关闭时，在「启动时播放推荐」设置项上 5 秒内连续点击 4 次，临时显示调试入口。
        // 每次点击仍正常切换启动推荐开关；四次点击后开关状态自然回到原值。
        fun recordDebugRevealTap() {
            if(debugEnabled || debugSettingRevealedThisSession) return
            val now=android.os.SystemClock.elapsedRealtime()
            if(debugRevealTapCount==0 || now-debugRevealLastTapAt>5000L) {
                debugRevealTapCount=0
            }
            debugRevealLastTapAt=now
            debugRevealTapCount++
            if(debugRevealTapCount>=4) {
                debugRevealTapCount=0
                setDebugCategoryVisible(true)
                Toast.makeText(this@settings,"调试入口已显示，本次运行期间不会自动隐藏",Toast.LENGTH_LONG).show()
            }
        }

        lateinit var startupAutoplayButton:TextView
        lateinit var qualityButton:TextView
        lateinit var playbackApiButton:TextView
        lateinit var defaultSpeed:TextView
        lateinit var frame:TextView
        lateinit var videoRendererButton:TextView
        lateinit var autoNextButton:TextView
        lateinit var autoSeasonButton:TextView
        lateinit var danmakuButton:TextView
        lateinit var danmakuOpacity:TextView
        lateinit var danmakuFont:TextView
        lateinit var danmakuColor:TextView
        lateinit var danmakuSpeed:TextView
        lateinit var danmakuRows:TextView
        lateinit var danmakuReset:TextView
        lateinit var blockedWords:TextView
        lateinit var keySettings:TextView
        lateinit var themeButton:TextView
        lateinit var clockButton:TextView
        lateinit var sourceDisplayButton:TextView
        lateinit var fileBackup:TextView
        lateinit var backup:TextView
        lateinit var hidden:TextView
        lateinit var resetButton:TextView
        lateinit var aboutButton:TextView

        fun paintStartupAutoplayButton(focused:Boolean=startupAutoplayButton.hasFocus()) {
            val enabled=startupAutoplayEnabled()
            startupAutoplayButton.text="启动时播放推荐  ·  ${if(enabled) "开启" else "关闭"}"
            // 开启/关闭仅作为文字状态显示，不在失焦后留下类似光标的选中底色。
            startupAutoplayButton.background=TvStyle.card(this@settings,focused)
        }
        startupAutoplayButton=setting(playback,0,"启动时播放推荐") {
            val enabled=!startupAutoplayEnabled()
            getSharedPreferences("startup_playback",Context.MODE_PRIVATE).edit().putBoolean("autoplay_recommendation",enabled).apply()
            paintStartupAutoplayButton()
            recordDebugRevealTap()
        }
        paintStartupAutoplayButton(false)
        startupAutoplayButton.setOnFocusChangeListener { _,focused ->
            paintStartupAutoplayButton(focused)
            if(focused) {
                categoryController.markFocusedCategory(0)
                categoryController.showSettingDescription(0,"启动时播放推荐")
            } else {
                startupAutoplayButton.post {
                    if(settingRows.none { it.hasFocus() }) categoryController.restoreCategoryDescription(categoryController.focusedCategory)
                }
            }
        }
        lateinit var sleepActionButton: TextView
        sleepActionButton=setting(playback,0,"定时关闭·${sleepActionLabel()}") {
            if(shouldShowSleepSettingHint()) {
                Toast.makeText(this@settings,"请在播放视频页面启用定时功能",Toast.LENGTH_LONG).show()
            }
            val next=(sleepActionMode+1)%2
            getSharedPreferences("playback-settings-v1", Context.MODE_PRIVATE).edit().putInt("sleep_action_mode",next).apply()
            sleepActionButton.text="定时关闭·${sleepActionLabel(next)}"
        }
        qualityButton=setting(playback,0,"画质  ·  ${PlaybackQuality.label(library.maxQuality)}") { showQualityPicker(qualityButton,false) }
        playbackApiButton=setting(playback,0,playbackApiLabel()) {
            val next=if(playbackApiSelection()=="native") "phoenix" else "native"
            PlaybackRouteSelection.set(this@settings,next)
            // 线路切换时丢弃旧线路的请求、单播放器预加载和隐藏备用播放器。
            cancelPrefetch()
            clearStandbyPlayer("播放线路切换")
            apiStatusUiState.checkBatchId++
            apiStatusUiState.cancelPlaybackProbes()
            if(screen=="player" && player!=null && detail!=null) {
                val resume=player?.playWhenReady==true
                val position=currentPosition()
                playEpisode(episodeIndex,position,autoplay=resume,forceRebuild=true)
            }
            playbackApiButton.text=playbackApiLabel(next)
            Toast.makeText(this@settings,"线路已切换至 ${if(next=="phoenix") "Phoenix" else "Native"}",Toast.LENGTH_SHORT).show()
                    }
        defaultSpeed=setting(playback,0,"默认倍速  ·  ${PlaybackSpeed.label(library.playbackSpeed)}") { showSpeedPicker(defaultSpeed,false) }
        frame=setting(playback,0,"画面模式  ·  ${library.frameMode.label}") { showFramePicker(frame,false) }
        videoRendererButton=setting(playback,0,"渲染方式  ·  ${videoRendererModeLabel()}") {
            val next=if(videoRendererMode==1) 2 else 1
            getSharedPreferences("playback-renderer-v1", Context.MODE_PRIVATE).edit()
                .putInt("video_renderer_mode",next).apply()
            videoRendererButton.text="渲染方式  ·  ${videoRendererModeLabel(next)}"
            applyVideoRenderer()
            val actual="当前已切换为 ${videoRendererModeLabel(next)}"
            Toast.makeText(this@settings,actual,Toast.LENGTH_LONG).show()
        }
        lateinit var seamlessPlaybackButton:TextView
        seamlessPlaybackButton=setting(playback,0,"无感连播  ·  ${if(seamlessPlaybackEnabled) "开启" else "关闭"}") {
            val enabled=!seamlessPlaybackEnabled
            getSharedPreferences("playback-settings-v1",Context.MODE_PRIVATE)
                .edit().putBoolean("seamless_playback",enabled).apply()
            seamlessPlaybackButton.text="无感连播  ·  ${if(enabled) "开启" else "关闭"}"
            if(enabled) {
                standbyFallbackEpisodeId=""
                cancelPrefetch()
                preloadManager?.reset()
                if(screen=="player" && playbackReady && detail!=null && episodeIndex<detail!!.episodes.lastIndex) {
                    prepareStandbyEpisode(episodeIndex+1)
                }
                Toast.makeText(this@settings,"无感连播已开启，将从下一集开始预备",Toast.LENGTH_SHORT).show()
            } else {
                standbyFallbackEpisodeId=""
                clearStandbyPlayer("用户关闭无感连播")
                Toast.makeText(this@settings,"无感连播已关闭，恢复单播放器预加载",Toast.LENGTH_SHORT).show()
            }
        }
        autoNextButton=setting(playback,0,"自动连播  ·  ${if(library.autoNext) "开启" else "关闭"}") {
            library.autoNext=!library.autoNext
            autoNextButton.text="自动连播  ·  ${if(library.autoNext) "开启" else "关闭"}"
        }
        autoSeasonButton=setting(playback,0,"系列剧连播  ·  ${if(library.autoSeason) "开启" else "关闭"}") {
            library.autoSeason=!library.autoSeason
            autoSeasonButton.text="系列剧连播  ·  ${if(library.autoSeason) "开启" else "关闭"}"
        }

        lateinit var interfaceStatusButton:TextView
        interfaceStatusButton=setting(playback,0,"接口状态") { showInterfaceCheck(interfaceStatusButton) }

        danmakuButton=setting(danmaku,1,"官方弹幕  ·  ${if(library.danmaku) "开启" else "关闭"}") {
            library.danmaku=!library.danmaku
            danmakuButton.text="官方弹幕  ·  ${if(library.danmaku) "开启" else "关闭"}"
        }
        danmakuOpacity=setting(danmaku,1,"不透明度  ·  ${library.danmakuOpacity}%") { showDanmakuOpacityPicker(danmakuOpacity) }
        danmakuFont=setting(danmaku,1,"字体大小  ·  ${DanmakuPrefs.fontLabel(library.danmakuFont)}") { showDanmakuFontPicker(danmakuFont) }
        danmakuColor=setting(danmaku,1,"弹幕颜色  ·  ${danmakuColorModeLabel()}") { showDanmakuColorPicker(danmakuColor) }
        danmakuSpeed=setting(danmaku,1,"移动速度  ·  ${DanmakuPrefs.speedLabel(library.danmakuSpeed)}") { showDanmakuSpeedPicker(danmakuSpeed) }
        danmakuRows=setting(danmaku,1,"展示行数  ·  ${library.danmakuRows} 行") { showDanmakuRowsPicker(danmakuRows) }
        danmakuReset=setting(danmaku,1,"恢复弹幕默认") {
            resetDanmakuPrefs()
            danmakuOpacity.text="不透明度  ·  ${library.danmakuOpacity}%"
            danmakuFont.text="字体大小  ·  ${DanmakuPrefs.fontLabel(library.danmakuFont)}"
            danmakuColor.text="弹幕颜色  ·  ${danmakuColorModeLabel()}"
            danmakuSpeed.text="移动速度  ·  ${DanmakuPrefs.speedLabel(library.danmakuSpeed)}"
            danmakuRows.text="展示行数  ·  ${library.danmakuRows} 行"
            Toast.makeText(this,"弹幕设置已恢复默认",Toast.LENGTH_SHORT).show()
        }
        blockedWords=setting(danmaku,1,"屏蔽词  ·  ${extras.blockedWords.size} 个") { showBlockedWordDialog(blockedWords) }

        val remoteActions = SETTINGS_REMOTE_ACTIONS
        val remoteActionLabels = SETTINGS_REMOTE_ACTION_LABELS
        fun remoteActionLabel(value:String)=remoteActionLabels.getOrNull(remoteActions.indexOf(value)) ?: "无"
        fun remoteKeySetting(title:String,current:()->String,update:(String)->Unit):TextView {
            lateinit var item:TextView
            fun sync() { item.text="$title  ·  ${remoteActionLabel(current())}" }
            item=setting(remote,3,"$title  ·  ${remoteActionLabel(current())}") {
                TvDialog.choose(this,"${title}功能",remoteActionLabels,{ index ->
                    update(remoteActions[index])
                    sync()
                    item.requestFocus()
                },selectedIndex=remoteActions.indexOf(current())).show()
            }
            return item
        }
        remoteKeySetting("上方向键",{ extras.remoteUpAction },{ extras.remoteUpAction=it })
        remoteKeySetting("下方向键",{ extras.remoteDownAction },{ extras.remoteDownAction=it })
        remoteKeySetting("确认键",{ extras.remoteConfirmAction },{ extras.remoteConfirmAction=it })
        remoteKeySetting("菜单键",{ extras.remoteMenuAction },{ extras.remoteMenuAction=it })

        themeButton=setting(appearance,4,"系统风格  ·  ${TvStyle.theme.label}") {
            library.theme=TvStyle.toggle().name
            settingsFocusKey="系统风格"
            showCatalog()
        }
        clockButton=setting(appearance,4,"显示系统时间  ·  ${if(library.showClock) "开启" else "关闭"}") {
            library.showClock=!library.showClock
            clockButton.text="显示系统时间  ·  ${if(library.showClock) "开启" else "关闭"}"
            applyClock()
        }
        sourceDisplayButton=setting(appearance,4,"片源显示  ·  ${if(getSharedPreferences("appearance-settings-v1",Context.MODE_PRIVATE).getBoolean("show_third_source",false)) "开启" else "关闭"}") {
            val prefs=getSharedPreferences("appearance-settings-v1",Context.MODE_PRIVATE)
            val enabled=!prefs.getBoolean("show_third_source",false)
            prefs.edit().putBoolean("show_third_source",enabled).apply()
            sourceDisplayButton.text="片源显示  ·  ${if(enabled) "开启" else "关闭"}"
            settingsFocusKey="片源显示"
            showCatalog()
        }
        fileBackup=setting(data,2,"备份与恢复") { showFileBackupDialog(fileBackup) }
        backup=setting(data,2,"远程文件管理") {
            pendingBackupAction=3
            pendingBackupAnchor=backup
            ensureBackupStoragePermission()
        }
        hidden=setting(data,2,"恢复隐藏推荐  ·  ${library.hidden().size} 部") {
            library.unhideAll(); hidden.text="恢复隐藏推荐  ·  0 部"; Toast.makeText(this,"热门推荐已恢复",Toast.LENGTH_SHORT).show()
        }
        resetButton=setting(system,5,"重置软件") { showResetOptions(resetButton) }
        aboutButton=setting(system,5,"关于软件") { updater.show(aboutButton,aboutMode=true) }

        // 调试能力独立成左侧主分类，避免藏在“关于”设置项中。
        debugSettingButton=setting(debug,6,debugSettingLabel()) {
            if(debugEnabled) {
                // 关闭只停止日志并清除持久化开关；入口保留到本次进程结束。
                // 下次启动时进程级临时状态会重置，因此入口自动隐藏。
                stopDebugLogging()
                setDebugCategoryVisible(true)
            } else {
                // 用户主动开启调试后，入口随调试状态常驻；重启后也会根据持久化开关显示。
                setDebugCategoryVisible(true)
                pendingBackupAction=2
                pendingBackupAnchor=debugSettingButton
                ensureBackupStoragePermission()
            }
        }
        setting(debug,6,"查看运行诊断") {
            TvDialog.textViewer(this,"运行诊断信息",debugEnvironmentSnapshot()).show()
        }
        setting(debug,6,"复制诊断信息") {
            val clipboard=getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("HongGuoTV 运行诊断",debugEnvironmentSnapshot()))
            Toast.makeText(this,"诊断信息已复制",Toast.LENGTH_SHORT).show()
        }
        setting(debug,6,"分享日志文件") {
            val target=debugFile ?: runCatching {
                backupFolder().listFiles()?.filter { it.isFile && it.name.startsWith("hongguotv-debug-") && it.name.endsWith(".log") }
                    ?.maxByOrNull { it.lastModified() }
            }.getOrNull()
            if(target==null || !target.exists()) {
                Toast.makeText(this,"暂时没有调试日志",Toast.LENGTH_SHORT).show()
            } else io.execute {
                val result=runCatching {
                    val length=target.length()
                    if(length>512L*1024) {
                        java.io.RandomAccessFile(target,"r").use { raf ->
                            raf.seek(length-512L*1024)
                            val bytes=ByteArray((length-raf.filePointer).toInt())
                            raf.readFully(bytes)
                            "[日志较长，仅分享最后 512KB]\n"+String(bytes,Charsets.UTF_8)
                        }
                    } else target.readText(Charsets.UTF_8)
                }
                main.post {
                    result.onSuccess { content ->
                        runCatching {
                            val share=Intent(Intent.ACTION_SEND).apply {
                                type="text/plain"
                                putExtra(Intent.EXTRA_SUBJECT,"HongGuoTV 调试日志")
                                putExtra(Intent.EXTRA_TEXT,content)
                            }
                            startActivity(Intent.createChooser(share,"分享调试日志"))
                        }.onFailure { Toast.makeText(this,"无法分享日志：${it.message}",Toast.LENGTH_LONG).show() }
                    }.onFailure { Toast.makeText(this,"读取日志失败：${it.message}",Toast.LENGTH_LONG).show() }
                }
            }
        }
        setting(debug,6,"显示日志") {
            val target=debugFile ?: runCatching {
                backupFolder().listFiles()?.filter { it.isFile && it.name.startsWith("hongguotv-debug-") && it.name.endsWith(".log") }
                    ?.maxByOrNull { it.lastModified() }
            }.getOrNull()
            if(target==null || !target.exists()) {
                Toast.makeText(this,"暂时没有调试日志",Toast.LENGTH_SHORT).show()
            } else {
                io.execute {
                    val content=runCatching {
                        val length=target.length()
                        if(length>2L*1024*1024) {
                            java.io.RandomAccessFile(target,"r").use { raf ->
                                raf.seek(length-2L*1024*1024)
                                val bytes=ByteArray((length-raf.filePointer).toInt())
                                raf.readFully(bytes)
                                String(bytes,Charsets.UTF_8)
                            }
                        } else target.readText(Charsets.UTF_8)
                    }.getOrElse { "读取日志失败：${it.message}" }
                    main.post {
                        if(isDestroyed) return@post
                        TvDialog.textViewer(this,"调试日志",content,monospace=true).show()
                    }
                }
            }
        }
        setting(debug,6,"复制日志") {
            val target=debugFile ?: runCatching {
                backupFolder().listFiles()?.filter { it.isFile && it.name.startsWith("hongguotv-debug-") && it.name.endsWith(".log") }
                    ?.maxByOrNull { it.lastModified() }
            }.getOrNull()
            if(target==null || !target.exists()) {
                Toast.makeText(this,"暂时没有调试日志",Toast.LENGTH_SHORT).show()
            } else {
                // 在主线程先采集环境快照；即使尚未打开播放器，复制日志也有可用诊断内容。
                val environmentSnapshot=debugEnvironmentSnapshot()
                io.execute {
                val result=runCatching {
                    val content=target.readText(Charsets.UTF_8)
                    // 复制完整日志时间线，不按关键词筛选；焦点、按键、网络、接口、页面、
                    // 片源、播放器A/B、缓存、异常和重试等已记录事件全部保留。
                    // 剪贴板有长度上限时，保留最新的连续日志片段，绝不只挑播放器关键字。
                    val environment=environmentSnapshot
                    val header=buildString {
                        append("红果TV 全面诊断日志\n")
                        append("日志文件：${target.name}\n")
                        append("采集环境：\n")
                        append(environment)
                        append("\n\n完整运行日志（最近连续片段）：\n")
                    }
                    val maxLogChars=(9000-header.length-80).coerceAtLeast(1000)
                    val rawLog=if(content.length>maxLogChars) {
                        "（前面的较早日志因剪贴板长度限制未复制，完整内容仍保留在日志文件中）\n"+
                            content.takeLast(maxLogChars)
                    } else content
                    (header+rawLog).take(9000).let {
                        if(header.length+rawLog.length>9000) it.dropLast(40)+"\n…（达到剪贴板长度上限）" else it
                    }
                }
                main.post {
                    result.onSuccess { content ->
                        val clipboard=getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("HongGuoTV 全面诊断日志",content))
                        Toast.makeText(this,"完整诊断日志已复制（较早内容可能受剪贴板长度限制，原始日志文件完整保留）",Toast.LENGTH_SHORT).show()
                    }.onFailure { Toast.makeText(this,"复制失败：${it.message}",Toast.LENGTH_LONG).show() }
                }
                }
            }
        }
        setting(debug,6,"清理日志") {
            val folder=backupFolder()
            debugLogExecutor.execute {
                val result=runCatching {
                    synchronized(debugLock) {
                        val currentLogs=folder.listFiles()?.filter {
                            it.isFile && it.name.startsWith("hongguotv-debug-") && it.name.endsWith(".log",true)
                        }.orEmpty()
                        val failed=mutableListOf<String>()
                        currentLogs.forEach { file ->
                            if(file.exists() && !file.delete()) failed+=file.name
                        }
                        if(debugEnabled) {
                            if(!folder.exists() && !folder.mkdirs()) error("无法创建日志目录")
                            val stamp=java.text.SimpleDateFormat("yyyyMMdd-HHmmss",Locale.US).format(java.util.Date())
                            val fresh=java.io.File(folder,"hongguotv-debug-$stamp.log")
                            fresh.appendText("===== 红果TV 调试日志重新开始 ${java.util.Date()} =====\\n",Charsets.UTF_8)
                            debugFile=fresh
                        } else {
                            debugFile=null
                        }
                        if(failed.isNotEmpty()) error("以下日志删除失败：${failed.joinToString("、")}")
                        currentLogs.size
                    }
                }
                main.post {
                    result.onSuccess { count ->
                        val suffix=if(debugEnabled) "，调试模式仍开启，已创建新的日志文件" else ""
                        val message=if(count==0) "没有历史日志文件" else "已清理全部调试日志（$count 个）"
                        Toast.makeText(this,message+suffix,Toast.LENGTH_LONG).show()
                    }.onFailure { Toast.makeText(this,"清理日志失败：${it.message}",Toast.LENGTH_LONG).show() }
                }
            }
        }

        // 开关优先级最高：开启时跨重启显示；关闭时只读取本进程的临时解锁状态。
        val revealDebug=debugEnabled || debugSettingRevealedThisSession
        categoryHeadingCards.getOrNull(6)?.visibility=if(revealDebug) View.VISIBLE else View.GONE
        debugSection.first.visibility=if(revealDebug) View.VISIBLE else View.GONE
        categoryItems.getOrNull(6)?.forEach { it.visibility=if(revealDebug) View.VISIBLE else View.GONE }

        rechainSettings={
            val visibleByCategory=categoryItems.map { items -> items.filter { it.visibility==View.VISIBLE } }
            visibleByCategory.forEachIndexed { category,visible ->
                visible.forEachIndexed { index,view ->
                    val col=index%columns
                    val rowStart=(index/columns)*columns
                    val rowEnd=minOf(rowStart+columns,visible.size)-1
                    view.nextFocusLeftId=if(col>0) visible[index-1].id else categoryIndicators[category].id
                    // At the row edge, return to the category label instead of trapping focus.
                    view.nextFocusRightId=if(index<rowEnd) visible[index+1].id else categoryIndicators[category].id
                    view.nextFocusUpId=visible.getOrNull(index-columns)?.id ?: navForTab().id
                    view.nextFocusDownId=visible.getOrNull(index+columns)?.id
                        ?: categoryIndicators.getOrNull(category+1)?.id ?: navForTab().id
                }
                val indicator=categoryIndicators[category]
                indicator.nextFocusLeftId=navForTab().id
                indicator.nextFocusRightId=visible.firstOrNull()?.id ?: navForTab().id
                indicator.nextFocusUpId=categoryIndicators.getOrNull(category-1)?.id ?: navForTab().id
                indicator.nextFocusDownId=categoryIndicators.getOrNull(category+1)?.id ?: navForTab().id
            }
            navForTab().nextFocusDownId=settingRows.firstOrNull()?.id ?: navForTab().id
        }
        rechainSettings()

        val wanted=settingsFocusKey.also { settingsFocusKey=null }
        val target=wanted?.let { key -> settingRows.firstOrNull { it.text.toString().startsWith(key) } }
        val targetCategory=target?.let { view -> categoryItems.indexOfFirst { view in it } } ?: 0
        categoryController.markFocusedCategory(targetCategory.coerceAtLeast(0))
        if(target!=null) {
            target.requestFocus()
        } else {
            settingRows.firstOrNull()?.requestFocus()
        }
    }
