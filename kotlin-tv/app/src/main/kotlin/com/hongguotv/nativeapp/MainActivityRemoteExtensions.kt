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

    internal fun MainActivity.showExitConfirm() {
        if(exitDialog?.isShowing==true) return
        val updates=library.updates()
        val updated=library.favorites().filter { (updates[it.id]?.added ?: 0) > 0 }.take(3)
        val fromUpdates=updated.isNotEmpty()
        // 收藏更新优先，其次随机荐剧池，最后退化到首页 7 天缓存随机抽 3 部，全部为本地数据
        val items=if(fromUpdates) updated else randomRecommender.pick(3).ifEmpty { library.cachedHome(library.contentType)?.items?.shuffled()?.take(3) ?: emptyList() }
        val compactDialog=widthDp()<600f || heightDp()<500f
        val body=column().apply { setPadding(0,0,0,0) }
        val heading=TvDialog.title(this,if(fromUpdates) "收藏更新，看完再走？" else if(items.isNotEmpty()) "猜你喜欢，再看一会儿？" else "确定要退出红果TV吗？")
        heading.gravity=Gravity.CENTER
        heading.textSize=if(compactDialog) 18f else 20f
        heading.setPadding(0,0,0,dp(if(compactDialog) 8 else 14))
        body.addView(heading,lp(-1,-2))

        val cardWidth=if(compactDialog) dp(((widthDp()-100f)/items.size.coerceAtLeast(1)-12f).toInt().coerceIn(68,120)) else dp(120)
        val posterHeight=(cardWidth/TvStyle.POSTER_ASPECT).toInt()
        val content=column()
        if(items.isNotEmpty()) {
            val cards=row().apply { gravity=Gravity.CENTER }
            items.forEach { item ->
                val card=column(); card.setPadding(dp(4),dp(4),dp(4),dp(9)); focusStyle(card)
                val image=ImageView(this).apply { scaleType=ImageView.ScaleType.FIT_XY; importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO }
                card.addView(image,lp(-1,posterHeight))
                val cardTitle=text("",if(compactDialog) 11f else 13f).apply { maxLines=2; minLines=2; ellipsize=TextUtils.TruncateAt.END; setPadding(dp(3),dp(6),dp(3),0) }
                val badge=text("",10f,muted).apply { maxLines=1; minLines=1; minHeight=dp(17); ellipsize=TextUtils.TruncateAt.END; setPadding(dp(3),0,0,0) }
                card.addView(cardTitle,lp(-1,-2)); card.addView(badge,lp(-1,-2))
                card.contentDescription=item.title
                loadCover(image,item.cover)
                cardTitle.text=item.title; badge.text=if(fromUpdates) library.favoriteLabel(item.id) else item.badge
                card.setOnClickListener { exitDialog?.dismiss(); openDetail(item,false) }
                card.setOnFocusChangeListener { _,focused -> card.background=TvStyle.card(this,focused); if(focused) loadCover(image,item.cover) }
                cards.addView(card,lp(cardWidth,-2).apply { rightMargin=dp(if(compactDialog) 4 else 12) })
            }
            content.addView(cards,lp(-1,-2))
        } else {
            content.addView(ImageView(this).apply { setImageResource(R.drawable.exit_placeholder); importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO },
                lp(dp(if(compactDialog) 120 else 200),dp(if(compactDialog) 120 else 200)).apply { gravity=Gravity.CENTER_HORIZONTAL; bottomMargin=dp(8) })
        }

        if(compactDialog) {
            // 手机/矮屏将推荐内容放进可滚动区域，底部两个操作按钮固定可见。
            val scroll=ScrollView(this).apply {
                isVerticalScrollBarEnabled=false
                clipToPadding=false
                isFillViewport=false
                addView(content,ViewGroup.LayoutParams(-1,-2))
            }
            body.addView(scroll,lp(-1,0).apply { weight=1f })
        } else body.addView(content,lp(-1,-2))

        val buttons=row().apply { gravity=Gravity.CENTER_HORIZONTAL; setPadding(0,dp(if(compactDialog) 8 else 14),0,0) }
        val exitButton=TvDialog.capsuleButton(this,"退出不看了") { killOnDestroy=true; library.flush(); exitDialog?.dismiss(); finish() }
        val stayButton=TvDialog.capsuleButton(this,"再看一会儿") { exitDialog?.dismiss() }
        if(compactDialog) {
            exitButton.textSize=13f; stayButton.textSize=13f
            exitButton.setPadding(dp(10),0,dp(10),0); stayButton.setPadding(dp(10),0,dp(10),0)
        }
        buttons.addView(exitButton,lp(-2,-2).apply { rightMargin=dp(if(compactDialog) 6 else 12) })
        buttons.addView(stayButton,lp(-2,-2))
        body.addView(buttons,lp(-1,-2).apply { topMargin=dp(if(compactDialog) 6 else 14); bottomMargin=dp(if(compactDialog) 2 else 6) })

        val dlg=AlertDialog.Builder(this).setView(TvDialog.card(this,body)).create()
        TvDialog.styleWindow(this,dlg,
            widthFraction=if(compactDialog) 0.94f else 0.55f,
            maxWidthDp=620,
            onShown={
                if(compactDialog) {
                    val windowWidth=(resources.displayMetrics.widthPixels*0.94f).toInt()
                    val windowHeight=(resources.displayMetrics.heightPixels*0.88f).toInt()
                    dlg.window?.setLayout(windowWidth,windowHeight)
                }
            })
        exitDialog=dlg
        dlg.show()
        dlg.window?.decorView?.post {
            // 保持默认焦点在“再看一会儿”，手机上按钮无需滚动到底部才能触达。
            if(dlg.isShowing) stayButton.requestFocus()
        }
        dlg.setOnDismissListener { exitDialog=null }
    }

    internal fun MainActivity.seek(direction: Int,repeat: Int) {
        val p=player ?: return; if(!playbackReady) return
        val duration=p.duration; if(duration<=0) return
        val step=if(repeat>4) 30000 else extras.seekStepSeconds*1000
        pendingSeek=((pendingSeek ?: p.currentPosition)+direction*step).coerceIn(0,(duration-500).coerceAtLeast(0))
        main.removeCallbacks(seekRunnable); main.postDelayed(seekRunnable,250); updatePlaybackText(); showHud()
    }

    internal fun MainActivity.startFastForward() {
        val p=player ?: return
        if(ffActive || !playbackReady || p.duration<=0) return
        ffActive=true; ffSpeedBefore=p.playbackParameters.speed
        p.setPlaybackSpeed(extras.longPressSpeed); showHud(); updatePlaybackText()
    }

    /** 手机触屏长按固定临时 2 倍速，松手由 stopFastForward 恢复原倍速。 */
    internal fun MainActivity.startPhoneTouchFastForward() {
        val p=player ?: return
        if(ffActive || !playbackReady || p.duration<=0) return
        ffActive=true
        ffSpeedBefore=p.playbackParameters.speed
        // 与 OOIUa 手机播放器一致：触屏长按临时 2 倍速，松手恢复原倍速。
        p.setPlaybackSpeed(2f)
        updatePlaybackText()
    }

    /** 手机长按松手只恢复原倍速，不唤起播放工具栏；电视遥控快进仍使用 stopFastForward。 */
    internal fun MainActivity.stopPhoneTouchFastForward() {
        if(!ffActive) return
        ffActive=false
        player?.setPlaybackSpeed(ffSpeedBefore)
        updatePlaybackText()
    }

    internal fun MainActivity.stopFastForward() {
        if(!ffActive) return
        ffActive=false; player?.setPlaybackSpeed(ffSpeedBefore)
        updatePlaybackText(); showHud()
    }

    internal fun MainActivity.goBack() {
        // 返回键将焦点送回顶部导航时，导航栏与四叶接口灯同步展开。
        if(bootAutoPlaybackPending || startupAutoPlaybackActive) { leaveStartupRecommendation(); return }
        if(!library.isLoaded) { finish(); return }
        if(screen=="player" && commentPanel!=null) { closeOfficialComments(); return }
        when(screen) {
            "player" -> if(player?.playbackState==Player.STATE_ENDED) returnToDetail() else if(hud.visibility==View.VISIBLE && !playError) hidePlaybackOverlay() else returnToDetail()
            "detail" -> {
                if(pushPlaybackReturnFocus) {
                    pushPlaybackReturnFocus=false
                    // 推送播放进入的视频退出后，恢复推送播放导航项焦点。
                    pushPlaybackPageActive=true
                    showCatalog()
                    root.post {
                        nav.firstOrNull { it.tag=="nav:push-playback" }
                            ?.takeIf { it.isAttachedToWindow }
                            ?.requestFocus()
                    }
                } else {
                    showCatalog()
                    if(tab==6) navForTab()?.requestFocus()
                }
            }
            "account" -> { showCatalog(); if(tab==6) navForTab()?.requestFocus() }
            else -> {
                // 统一所有标签页的返回层级：
                // 1. 焦点已经在顶部任意标签上时，返回首页；首页则执行退出确认。
                // 2. 焦点仍在标签内容区时，先关闭该标签内的子页面/筛选。
                // 3. 回到当前标签的导航项；再次返回才回首页。
                // 不再把搜索/剧场/账户写成特殊分支，避免焦点在其他标签时被强行拉回搜索。
                if(nav.any { it.hasFocus() } || searchNavShortcut?.hasFocus()==true) {
                    if(tab!=0) {
                        switchTab(0)
                        navByTab[0]?.requestFocus()
                    } else showExitConfirm()
                } else if(tab==2 && aiCatalog) {
                    aiCatalog=false; aiGenre=null; page=1; catalogFocus=""
                    tabState.remove(2); tabFocus.remove(2)
                    showCatalog(true)
                    aiEntry?.post { if(aiEntry?.isAttachedToWindow==true) aiEntry?.requestFocus() }
                } else if(tab==0 && genre!=null) {
                    genre=null; page=1; catalogFocus=""
                    tabState.remove(0); tabFocus.remove(0)
                    showCatalog(true,focusNav=true)
                } else if(collection!=null) {
                    leaveCollection()
                    showCatalog()
                } else {
                    // 内容区（包括海报网格）返回导航栏时，焦点和画面都回到顶部。
                    if(runCatching { root }.isSuccess) resetCatalogContentToTop(root)
                    revealCatalogNavForFocus()
                    navForTab().requestFocus()
                }
            }
        }
    }

    internal fun MainActivity.performRemoteAction(action: String) {
        when(action) {
            PlaybackExtras.REMOTE_PREVIOUS -> skipEpisode(-1)
            PlaybackExtras.REMOTE_NEXT -> skipEpisode(1)
            PlaybackExtras.REMOTE_MENU -> showPanel()
            PlaybackExtras.REMOTE_COMMENTS -> openOfficialComments(null)
            PlaybackExtras.REMOTE_PLAY_PAUSE -> togglePlayback()
            PlaybackExtras.REMOTE_EPISODES -> showEpisodePanel(hud)
            PlaybackExtras.REMOTE_SLEEP_TIMER -> showSleepPicker(playbackText)
            PlaybackExtras.REMOTE_NONE -> Unit
        }
    }