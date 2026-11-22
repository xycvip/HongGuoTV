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

internal fun MainActivity.loadSameNameInBackground(snapshot: Detail) {
        val id=snapshot.series.id
        if(snapshot.sameName.isNotEmpty() || sameNameLoadingId==id) return
        sameNameLoadingId=id
        io.execute {
            val seasons=runCatching {
                repository.sameNameSeasons(snapshot.series,snapshot.related)
            }.getOrDefault(emptyList())
            main.post {
                if(sameNameLoadingId==id) sameNameLoadingId=""
                if(isDestroyed || screen!="detail" || detail?.series?.id!=id || detailSeasonLine==null || seasons.isEmpty()) return@post
                detail=detail?.copy(sameName=seasons)
                appendSeasonRecommendations(seasons)
            }
        }
    }

    internal fun MainActivity.appendSeasonRecommendations(items: List<Series>) {
        val line=detailSeasonLine ?: return
        if(line.childCount>0 || items.isEmpty()) return
        val width=((resources.displayMetrics.widthPixels*.9f-dp(70))/6).toInt().coerceAtLeast(dp(100))
        val posterHeight=((width-dp(8))/TvStyle.POSTER_ASPECT).toInt()
        val cards=mutableListOf<View>()
        items.take(12).forEach { series ->
            val card=LinearLayout(this).apply {
                orientation=LinearLayout.VERTICAL; id=View.generateViewId()
                isFocusable=true; isFocusableInTouchMode=true
                setPadding(dp(4),dp(4),dp(4),dp(8))
                background=TvStyle.card(this@appendSeasonRecommendations,false)
                contentDescription="系列剧推荐，${series.title}"
            }
            val image=ImageView(this).apply {
                scaleType=ImageView.ScaleType.CENTER_CROP
                importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            card.addView(image,lp(-1,posterHeight))
            card.addView(text(series.title,14f).apply {
                minLines=2; maxLines=2; ellipsize=TextUtils.TruncateAt.END
                setPadding(dp(3),dp(5),dp(3),0)
            })
            card.addView(text(series.badge.ifBlank { series.tags },12f,muted).apply {
                minLines=1; maxLines=1; ellipsize=TextUtils.TruncateAt.END
                setPadding(dp(3),dp(2),0,0)
            })
            card.setOnFocusChangeListener { _,focused ->
                card.background=TvStyle.card(this@appendSeasonRecommendations,focused)
                if(focused) loadCover(image,series.cover)
            }
            card.setOnClickListener { openDetail(series) }
            if(cards.size<6) loadCover(image,series.cover)
            line.addView(card,lp(width,-2).apply { rightMargin=dp(14) })
            cards+=card
        }
        detailSeasonSection?.visibility=View.VISIBLE
        val lastRowStart=((detailEpisodeButtons.size-1)/10)*10
        if(detailEpisodeButtons.isNotEmpty()) {
            detailEpisodeButtons.forEachIndexed { index,episode ->
                if(index>=lastRowStart) {
                    val target=cards[(index-lastRowStart).coerceAtMost(cards.lastIndex)]
                    episode.nextFocusDownId=target.id
                }
            }
        }
        cards.forEachIndexed { index,card ->
            card.nextFocusLeftId=cards[(index-1).coerceAtLeast(0)].id
            card.nextFocusRightId=cards[(index+1).coerceAtMost(cards.lastIndex)].id
            card.nextFocusUpId=detailEpisodeButtons.getOrNull((lastRowStart+index).coerceAtMost(detailEpisodeButtons.lastIndex))?.id ?: View.NO_ID
            card.nextFocusDownId=detailRelatedCards.getOrNull(index.coerceAtMost((detailRelatedCards.size-1).coerceAtLeast(0)))?.id ?: card.id
        }
        detailRelatedCards.forEachIndexed { index,card ->
            card.nextFocusUpId=cards[index.coerceAtMost(cards.lastIndex)].id
        }
    }

    internal fun MainActivity.returnToDetail(forceDetail: Boolean=false) {
        main.removeCallbacks(startupRecommendationTimeout)
        sleepTimer.cancel(); sleepStopped=false; startupAutoPlaybackActive=false; saveProgress(); bootAutoPlaybackPending=false; hideBootSplash(); library.flush(); invalidatePage(); releasePlayer()
        if(pushPlaybackReturnFocus && !forceDetail) {
            // 推送播放来源的视频退出时，恢复推送播放导航项焦点。
            pushPlaybackReturnFocus=false
            directPlayback=false
            pushPlaybackPageActive=true
            showCatalog()
            root.post {
                nav.firstOrNull { it.tag=="nav:push-playback" }
                    ?.takeIf { it.isAttachedToWindow }
                    ?.requestFocus()
            }
        } else if(directPlayback && !forceDetail) {
            showCatalog()
            if(tab==6) navForTab()?.post { if(tab==6 && screen=="catalog") navForTab()?.requestFocus() }
        } else {
            directPlayback=false
            showDetail(true)
            detail?.let { loadSameNameInBackground(it) }
        }
    }
