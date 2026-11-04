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

    internal fun MainActivity.showRandomPicks() {
        // 首页随机入口直接播放，不再展示随机荐剧列表供用户二次选择。
        if(screen!="catalog" || randomPlaybackLaunching || isDestroyed || resettingAllData) return
        randomPlaybackLaunching=true
        // 先给出明确反馈；选中视频后只更新片名，不重建页面，避免等待画面闪烁。
        val waiting=base()
        waiting.setBackgroundColor(Color.BLACK)
        waiting.gravity=Gravity.CENTER
        val waitingContent=column().apply { gravity=Gravity.CENTER; setPadding(dp(24),dp(24),dp(24),dp(24)) }
        waitingContent.addView(ProgressBar(this).apply { isIndeterminate=true },lp(dp(42),dp(42)).apply { gravity=Gravity.CENTER })
        randomLoadingLabel=text("正在为你挑选精彩内容…",16f,Color.WHITE).apply {
            gravity=Gravity.CENTER
            setPadding(0,dp(18),0,0)
        }
        waitingContent.addView(randomLoadingLabel,lp(-2,-2))
        waiting.addView(waitingContent,lp(-1,-1))
        fun playPickedVideo() {
            if(isDestroyed || resettingAllData) { randomPlaybackLaunching=false; randomLoadingLabel=null; return }
            val picked=randomRecommender.pick(1).firstOrNull()
                ?: library.cachedHome(library.contentType)?.items?.shuffled()?.firstOrNull()
            if(picked!=null) {
                randomPlaybackLaunching=false
                openDetail(picked,resume=true,autoplay=true,randomLoading=true)
            } else {
                randomPlaybackLaunching=false
                randomLoadingLabel=null
                showCatalog(load=false)
                Toast.makeText(this,"暂时无法获取随机视频，请检查网络后重试",Toast.LENGTH_SHORT).show()
            }
        }
        val cached=randomRecommender.pick(1).firstOrNull()
        if(cached!=null) {
            randomPlaybackLaunching=false
            openDetail(cached,resume=true,autoplay=true,randomLoading=true)
        } else {
            replenishRandomPool { playPickedVideo() }
        }
    }

    internal fun MainActivity.replenishRandomPool(onDone: () -> Unit) {
        if(randomReplenishing || resettingAllData) return
        randomReplenishing=true
        io.execute {
            val added=runCatching { randomRecommender.replenish() }.getOrDefault(0)
            if(added>0 && !resettingAllData) {
                val encoded=RandomPoolSnapshot.encode(randomRecommender.snapshot(), System.currentTimeMillis())
                getSharedPreferences("random_pool", Context.MODE_PRIVATE).edit().putString("pool", encoded).apply()
            }
            main.post { randomReplenishing=false; if(!isDestroyed && !resettingAllData) onDone() }
        }
    }

    internal fun MainActivity.showRandomDialog(items: List<Series>, loading: Boolean=false) {
        randomDialog?.dismiss()
        var randomRecycler: RecyclerView?=null
        var randomFooterFocusId=View.NO_ID
        val body=column().apply { setPadding(0,0,0,0) }
        val title=TvDialog.title(this,if(loading) "正在随机挑选…" else "随机荐剧")
        body.addView(title)
        val content=FrameLayout(this)
        body.addView(content,lp(-1,dp(420)).apply { bottomMargin=dp(10) })
        val dlg=AlertDialog.Builder(this).setView(TvDialog.card(this,body)).create()
        TvDialog.styleWindow(this,dlg)
        randomDialog=dlg
        dlg.show()
        if(items.isEmpty() && !loading) {
            content.addView(text("候选池为空，正在补充…",15f,muted).apply { setPadding(0,dp(20),0,0) })
        } else if(items.isNotEmpty()) {
            // 弹窗专用固定尺寸卡片：宽120dp，上图（3:4固定高）下名称+集数，小图拉伸撑满
            val cardWidth=dp(120)
            val posterHeight=(cardWidth/TvStyle.POSTER_ASPECT).toInt()
            val randomAdapter=object: RecyclerView.Adapter<RecyclerView.ViewHolder>() {
                override fun getItemCount()=items.size
                override fun onCreateViewHolder(parent: ViewGroup,viewType: Int): RecyclerView.ViewHolder {
                    val card=column(); card.setPadding(dp(4),dp(4),dp(4),dp(9)); focusStyle(card)
                    val image=ImageView(this@showRandomDialog).apply { scaleType=ImageView.ScaleType.FIT_XY; importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO }
                    card.addView(image,lp(-1,posterHeight))
                    val title=text("",13f).apply { maxLines=2; minLines=2; ellipsize=TextUtils.TruncateAt.END; setPadding(dp(3),dp(6),dp(3),0) }
                    val badge=text("",11f,muted).apply { maxLines=1; minLines=1; minHeight=dp(17); ellipsize=TextUtils.TruncateAt.END; setPadding(dp(3),0,0,0) }
                    card.addView(title,lp(-1,-2)); card.addView(badge,lp(-1,-2))
                    card.layoutParams=RecyclerView.LayoutParams(cardWidth,-2).apply { rightMargin=dp(12); bottomMargin=dp(10) }
                    return object: RecyclerView.ViewHolder(card) {}
                }
                override fun onBindViewHolder(holder: RecyclerView.ViewHolder,position: Int) {
                    val item=items[position]; val card=holder.itemView as LinearLayout
                    val image=card.getChildAt(0) as ImageView
                    val title=card.getChildAt(1) as TextView
                    val badge=card.getChildAt(2) as TextView
                    card.contentDescription=item.title
                    card.nextFocusDownId=if(position>=items.size-4 && randomFooterFocusId!=View.NO_ID) randomFooterFocusId else card.id
                    loadCover(image,item.cover)
                    title.text=item.title; badge.text=item.badge
                    card.setOnClickListener { randomDialog?.dismiss(); openDetail(item,false) }
                    card.setOnFocusChangeListener { _,focused -> card.background=TvStyle.card(this@showRandomDialog,focused); if(focused) loadCover(image,item.cover) }
                }
            }
            val grid=GridLayoutManager(this,4)
            val recycler=RecyclerView(this).apply {
                clipToPadding=false; itemAnimator=null
                isFocusable=true; isFocusableInTouchMode=true
                setOnFocusChangeListener { _,focused ->
                    if(focused) post { findViewHolderForAdapterPosition(grid.findFirstVisibleItemPosition().coerceAtLeast(0))?.itemView?.requestFocus() }
                }
            }
            recycler.layoutManager=grid; recycler.adapter=randomAdapter
            randomRecycler=recycler
            content.addView(recycler,lp(-1,-1))
        }
        // 「换一批」按钮
        val buttons=row().apply { gravity=Gravity.END; setPadding(0,dp(14),0,0) }
        val change=TvDialog.capsuleButton(this,"换一批") {
            dlg.dismiss()
            val picks=randomRecommender.pick(12)
            if(picks.isNotEmpty()) showRandomDialog(picks)
            else { showRandomDialog(emptyList(), loading = true); replenishRandomPool { val n=randomRecommender.pick(12); if(n.isNotEmpty()) showRandomDialog(n) } }
        }
        val close=TvDialog.capsuleButton(this,"关闭") { dlg.dismiss() }
        buttons.addView(change,lp(-2,-2).apply { rightMargin=dp(12) })
        buttons.addView(close,lp(-2,-2))
        body.addView(buttons)
        randomFooterFocusId=change.id
        randomRecycler?.adapter?.notifyDataSetChanged()
        change.nextFocusLeftId=change.id
        change.nextFocusRightId=close.id
        change.nextFocusUpId=randomRecycler?.id ?: change.id
        change.nextFocusDownId=close.id
        close.nextFocusLeftId=change.id
        close.nextFocusRightId=close.id
        close.nextFocusUpId=randomRecycler?.id ?: change.id
        close.nextFocusDownId=close.id
        randomRecycler?.nextFocusDownId=change.id
        change.requestFocus()
        dlg.setOnDismissListener { randomDialog=null }
    }
