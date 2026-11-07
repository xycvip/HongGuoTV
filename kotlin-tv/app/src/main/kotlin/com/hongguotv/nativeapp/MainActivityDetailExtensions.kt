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

    internal fun MainActivity.manageHistory(series: Series,anchor: View?) {
        tvTools.choose(series.title,listOf("继续观看",if(library.watched(series.id)) "恢复未看状态" else "标记整剧已看","删除这条记录"),anchor,{ index ->
            when(index) {
                0 -> openDetail(series,true)
                1 -> { library.setWatched(series.id,!library.watched(series.id)); catalogFocus=series.id; showCatalog() }
                2 -> tvTools.confirm("删除观看记录","删除《${series.title}》的本机观看进度？收藏会保留。",anchor) { library.removeHistory(series.id); showCatalog() }
            }
        })
    }

    internal fun MainActivity.openDetail(series: Series,resume: Boolean=false,autoplay: Boolean=true,randomLoading: Boolean=false) {
        if(activePlatform==ContentPlatform.HIPPO && series.id.startsWith("hippo-playlist:")) {
            openHippoPlaylist(series)
            return
        }
        debugLog("页面导航：打开剧集详情 title=${series.title} resume=$resume autoplay=$autoplay random=$randomLoading")
        val startupAttempt=bootAutoPlaybackPending
        if(activePlatform==ContentPlatform.HIPPO) hippoRepository.rememberSeries(series)
        pendingDetail=series; detailAutoplay=autoplay
        invalidatePage(); releaseCatalogViews(); screen="detail"; directPlayback=resume; detail=null; synopsisExpanded=false
        val reuseRandomWaiting=randomLoading && randomLoadingLabel?.isAttachedToWindow==true
        val body=if(reuseRandomWaiting) (root.getChildAt(0) as? LinearLayout) ?: base() else if(randomLoading) base() else base(series.title)
        if(bootAutoPlaybackPending || randomLoading) {
            // 开机自动播放和手动随机播放均不展示剧集加载页；随机播放沿用同一等待画面并更新片名。
            if(!reuseRandomWaiting) {
                body.removeAllViews()
                body.setBackgroundColor(Color.BLACK)
                if(randomLoading && !bootAutoPlaybackPending) {
                    body.gravity=Gravity.CENTER
                    val waiting=column().apply { gravity=Gravity.CENTER }
                    waiting.addView(ProgressBar(this).apply { isIndeterminate=true },lp(dp(42),dp(42)).apply { gravity=Gravity.CENTER })
                    randomLoadingLabel=text("正在准备播放：${series.title}",15f,Color.WHITE).apply {
                        gravity=Gravity.CENTER
                        setPadding(0,dp(16),0,0)
                    }
                    waiting.addView(randomLoadingLabel,lp(-2,-2))
                    body.addView(waiting,lp(-1,-1))
                }
            } else {
                randomLoadingLabel?.text="正在准备播放：${series.title}"
            }
        } else message(body,"正在加载剧集…")
        work({ repository.detail(series.id) }, { result ->
            if(startupAttempt && startupRecommendationExpired) return@work
            detail=result; library.observeFavorite(series.id,result.episodes.size,System.currentTimeMillis(),acknowledge=true)
            val progress=library.progress(series.id)
            val cloud = pendingCloudProgress?.takeIf { it.series.id == series.id }
            pendingCloudProgress = null
            val target=ResumePlayback.target(result.episodes,cloud?.episodeId ?: progress?.episodeId,cloud?.position ?: progress?.position ?: 0,progress?.completed==true)
            episodeIndex=target.index; group=episodeIndex/20
            if(resume) playEpisode(target.index,target.position,autoplay=detailAutoplay)
            else {
                showDetail(false)
                loadSameNameInBackground(result)
            }
        }, { problem ->
            if(startupAttempt && startupRecommendationExpired) return@work
            if(startupAutoPlaybackActive) failStartupRecommendation()
            else error(body,problem) { openDetail(series,resume,detailAutoplay,randomLoading=randomLoading) }
        })
    }

    internal fun MainActivity.showDetail(focusEpisode: Boolean) {
        debugLog("页面渲染：剧集详情 focusEpisode=$focusEpisode title=${detail?.series?.title.orEmpty()}")
        detailSeasonLine=null; detailSeasonSection=null
        detailEpisodeButtons.clear(); detailRelatedCards.clear()
        invalidatePage(); releaseCatalogViews(); screen="detail"; applyDeviceOrientationPolicy(); val data=detail ?: return showCatalog()
        val body=base("剧集详情")
        val detailBack=(body.getChildAt(0) as? ViewGroup)?.getChildAt(0)
        val scroll=ScrollView(this).apply { isVerticalScrollBarEnabled=false }; val content=column(); scroll.addView(content); body.addView(scroll,lp(-1,0).apply { weight=1f })
        val hero=row(); hero.gravity=Gravity.TOP; hero.setPadding(0,dp(12),0,dp(12))
        val image=ImageView(this).apply { scaleType=ImageView.ScaleType.CENTER_CROP }; hero.addView(image,lp(dp(123),dp(174)).apply { rightMargin=dp(24) }); loadCover(image,data.series.cover)
        val info=column(); hero.addView(info,lp(0,-2).apply { weight=1f })
        info.addView(text(data.series.title,26f).apply { setTypeface(null,Typeface.BOLD); maxLines=2; ellipsize=TextUtils.TruncateAt.END })
        info.addView(text("${data.episodes.size} 集  ·  ${data.series.tags}"+library.progress(data.series.id)?.let { "  ·  看到第 ${it.episodeIndex+1} 集" }.orEmpty(),13f,muted).apply { maxLines=1; setPadding(0,dp(8),0,dp(8)) })
        // 评分与收藏数来自详情页同一份数据，卡片列表没有这些字段；缺失时整行不显示
        if(!data.social.isEmpty) {
            val metrics=mutableListOf<String>()
            val score=if(data.social.rating>0) String.format(Locale.CHINA,"%.1f 分",data.social.rating) else ""
            if(score.isNotEmpty()) metrics+=score+data.social.ratingCount.takeIf { it.isNotBlank() }?.let { "（$it 人评分）" }.orEmpty()
            if(data.social.favoriteText.isNotBlank()) metrics+=data.social.favoriteText
            if(data.social.heatText.isNotBlank()) metrics+=data.social.heatText
            val line=SpannableString(metrics.joinToString("  ·  "))
            if(score.isNotEmpty()) {
                line.setSpan(ForegroundColorSpan(accent),0,score.length,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                line.setSpan(StyleSpan(Typeface.BOLD),0,score.length,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            info.addView(TextView(this).apply { text=line; textSize=13f; setTextColor(muted); includeFontPadding=false; maxLines=1; ellipsize=TextUtils.TruncateAt.END; setPadding(0,0,0,dp(6)) })
        }
        val description=data.series.description.ifBlank { "选择剧集开始观看" }
        val synopsis=text(description,15f,muted).apply { maxLines=if(synopsisExpanded) 20 else 2; ellipsize=TextUtils.TruncateAt.END }
        info.addView(synopsis)
        val actions=row(); actions.setPadding(0,dp(12),0,0); info.addView(actions)
        val progress=library.progress(data.series.id)
        val resumeIndex=progress?.let { data.episodes.indexOf(it.episodeId).takeIf { n -> n>=0 } } ?: 0
        val resumePosition=progress?.takeIf { !it.completed && resumeIndex==it.episodeIndex }?.position ?: 0
        val watched=library.watched(data.series.id)
        val play=addButton(actions,if(watched) "重新观看" else if(progress!=null && !progress.completed) "继续第 ${resumeIndex+1} 集" else "开始观看") { playEpisode(if(watched) 0 else if(progress?.completed==true) (resumeIndex+1).coerceAtMost(data.episodes.lastIndex) else resumeIndex,if(watched) 0 else resumePosition) }
        lateinit var favorite: TextView
        favorite=addButton(actions,if(library.favorite(data.series.id)) "已收藏" else "收藏") {
            favoriteMonitor.stop()
            val saved=library.toggle(data.series)
            if(saved) {
                library.observeFavorite(data.series.id,data.episodes.size,System.currentTimeMillis(),acknowledge=true)
                cloudAccount.syncLocalFavorites(library.favorites())
            }
            favorite.text=if(saved) "已收藏" else "收藏"
        }
        lateinit var synopsisButton: TextView
        synopsisButton=addButton(actions,if(synopsisExpanded) "收起简介" else "完整简介") {
            synopsisExpanded=!synopsisExpanded
            synopsis.maxLines=if(synopsisExpanded) 20 else 2
            synopsisButton.text=if(synopsisExpanded) "收起简介" else "完整简介"
            synopsisButton.post { synopsisButton.requestRectangleOnScreen(android.graphics.Rect(0,0,synopsisButton.width,synopsisButton.height),false) }
        }
        val detailActionButtons=listOf(play,favorite,synopsisButton)
        detailActionButtons.forEachIndexed { index, view ->
            view.nextFocusLeftId=detailActionButtons.getOrNull(index-1)?.id ?: view.id
            view.nextFocusRightId=detailActionButtons.getOrNull(index+1)?.id ?: view.id
            view.nextFocusUpId=detailBack?.id ?: view.id
        }
        detailBack?.nextFocusDownId=play.id
        detailBack?.let { back ->
            back.nextFocusUpId=back.id
            back.nextFocusLeftId=back.id
            back.nextFocusRightId=back.id
        }
        content.addView(hero)
        // 系列剧季导航：同名条目按季号排序，快速跳季并回显每季已看到的集数
        val seasons=(listOf(data.series)+data.sameName).distinctBy { it.id }
            .sortedBy { val number=seasonNumber(it.title); if(number==Int.MAX_VALUE) 0 else number }
        val seasonButtons=mutableListOf<View>()
        if(seasons.size>1) {
            val seasonRow=row(); seasonRow.setPadding(0,dp(3),0,dp(6))
            seasonRow.addView(text("系列 ${seasons.size} 季",13f,muted),lp(dp(78),dp(42)))
            val strip=HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled=false; clipToPadding=false }
            val line=row(); strip.addView(line)
            seasons.forEach { season ->
                val number=seasonNumber(season.title)
                val seen=library.progress(season.id)?.let { " · 看到${it.episodeIndex+1}集" }.orEmpty()
                val title=if(number==Int.MAX_VALUE) season.title.take(12) else "第 ${number} 季"
                seasonButtons+=addButton(line,title+seen,season.id==data.series.id) { if(season.id!=data.series.id) openDetail(season) }
            }
            seasonRow.addView(strip,lp(0,dp(42)).apply { weight=1f })
            content.addView(seasonRow)
        }
        val groupRow=row(); groupRow.setPadding(0,dp(3),0,dp(5))
        val maxGroup=data.episodes.lastIndex/20; group=group.coerceIn(0,maxGroup)
        val previous=addButton(groupRow,"‹ 上一组") { if(group>0) { group--; showDetail(true) } }.apply { isEnabled=group>0; isFocusable=group>0; alpha=if(group>0) 1f else .4f }
        groupRow.addView(text("第 ${group*20+1}—${minOf((group+1)*20,data.episodes.size)} 集",16f).apply { gravity=Gravity.CENTER },lp(dp(190),dp(42)))
        addButton(groupRow,"下一组 ›") { if(group<maxGroup) { group++; showDetail(true) } }.apply { isEnabled=group<maxGroup; isFocusable=group<maxGroup; alpha=if(group<maxGroup) 1f else .4f }
        lateinit var jump: TextView
        jump=addButton(groupRow,"跳转集数") { tvTools.episodePicker(data.episodes.size,episodeIndex,jump) { target -> episodeIndex=target; group=target/20; showDetail(true) } }
        content.addView(groupRow)
        val groupButtons=listOf(previous,groupRow.getChildAt(2) as View,jump).filter { it.isFocusable && it.isEnabled }
        groupButtons.forEachIndexed { index, view ->
            view.nextFocusLeftId=groupButtons.getOrNull(index-1)?.id ?: view.id
            view.nextFocusRightId=groupButtons.getOrNull(index+1)?.id ?: view.id
            view.nextFocusUpId=seasonButtons.lastOrNull()?.id ?: synopsisButton.id
        }
        seasonButtons.forEachIndexed { index, view ->
            view.nextFocusLeftId=seasonButtons.getOrNull(index-1)?.id ?: view.id
            view.nextFocusRightId=seasonButtons.getOrNull(index+1)?.id ?: view.id
            view.nextFocusUpId=synopsisButton.id
            view.nextFocusDownId=if(previous.isFocusable) previous.id else jump.id
        }
        val episodes=mutableListOf<View>()
        (group*20 until minOf((group+1)*20,data.episodes.size)).toList().chunked(10).forEach { numbers ->
            val line=row()
            numbers.forEach { index -> val item=button("${index+1}",index==episodeIndex) { playEpisode(index,if(progress?.episodeId==data.episodes[index] && !progress.completed) progress.position else 0) }; item.contentDescription="第 ${index+1} 集"; line.addView(item,lp(0,dp(42)).apply { weight=1f; rightMargin=dp(6) }); episodes+=item }
            repeat(10-numbers.size) { line.addView(Space(this),lp(0,dp(42)).apply { weight=1f; rightMargin=dp(6) }) }
            content.addView(line,lp(-1,dp(49)))
        }
        groupButtons.forEach { it.nextFocusDownId=episodes.firstOrNull()?.id ?: it.id }
        detailActionButtons.forEach { it.nextFocusDownId=seasonButtons.firstOrNull()?.id ?: groupButtons.firstOrNull()?.id ?: it.id }
        detailEpisodeButtons.addAll(episodes)
        val extraRows=mutableListOf<MutableList<View>>()
        fun recommendationRow(title: String,items: List<Series>,seasonSection: Boolean=false) {
            if(items.isEmpty() && !seasonSection) return
            val section=column()
            val heading=row(); heading.setPadding(0,dp(20),0,dp(9))
            heading.addView(View(this).apply { background=TvStyle.shape(this@showDetail,TvStyle.accent,radius=2) },lp(dp(3),dp(14)).apply { rightMargin=dp(8) })
            heading.addView(text(title,16f).apply { setTypeface(null,Typeface.BOLD) },lp(0,-2).apply { weight=1f })
            heading.addView(text("左右查看更多",10f,muted))
            section.addView(heading)
            val horizontal=HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled=false; clipToPadding=false }
            val line=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.TOP }
            val width=((resources.displayMetrics.widthPixels*.9f-dp(70))/6).toInt().coerceAtLeast(dp(100))
            val posterHeight=((width-dp(8))/TvStyle.POSTER_ASPECT).toInt()
            val cards=mutableListOf<View>()
            items.take(12).forEach { series ->
                val card=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; id=View.generateViewId(); isFocusable=true; isFocusableInTouchMode=true; setPadding(dp(4),dp(4),dp(4),dp(8)); background=TvStyle.card(this@showDetail,false); contentDescription="$title，${series.title}" }
                val image=ImageView(this).apply { scaleType=ImageView.ScaleType.CENTER_CROP; importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO }
                card.addView(image,lp(-1,posterHeight))
                card.addView(text(series.title,14f).apply { minLines=2; maxLines=2; ellipsize=TextUtils.TruncateAt.END; setPadding(dp(3),dp(5),dp(3),0) })
                card.addView(text(series.badge.ifBlank { series.tags },12f,muted).apply { minLines=1; maxLines=1; ellipsize=TextUtils.TruncateAt.END; setPadding(dp(3),dp(2),0,0) })
                card.setOnFocusChangeListener { _,focused -> card.background=TvStyle.card(this@showDetail,focused); if(focused) loadCover(image,series.cover) }
                card.setOnClickListener { openDetail(series) }
                if(cards.size<6) loadCover(image,series.cover)
                line.addView(card,lp(width,-2).apply { rightMargin=dp(14) })
                cards+=card
            }
            horizontal.addView(line); section.addView(horizontal)
            content.addView(section,lp(-1,-2).apply { topMargin=dp(4) })
            if(cards.isNotEmpty()) extraRows+=cards
            if(seasonSection) {
                detailSeasonLine=line
                detailSeasonSection=section
                if(items.isEmpty()) section.visibility=View.GONE
            }
            if(title=="相关推荐") detailRelatedCards.addAll(cards)
        }
        recommendationRow("系列剧推荐",data.sameName,seasonSection=true)
        recommendationRow("相关推荐",data.related)
        val lastRowStart=((episodes.size-1)/10)*10
        val firstRow=extraRows.firstOrNull()
        episodes.forEachIndexed { i,v ->
            v.nextFocusLeftId=if(i%10==0) v.id else episodes[i-1].id
            v.nextFocusRightId=if(i%10==9 || i==episodes.lastIndex) v.id else episodes[i+1].id
            v.nextFocusUpId=if(i>=10) episodes[i-10].id else jump.id
            v.nextFocusDownId=when {
                i+10<episodes.size -> episodes[i+10].id
                firstRow!=null && i>=lastRowStart -> firstRow[(i-lastRowStart).coerceAtMost(firstRow.lastIndex)].id
                else -> v.id
            }
        }
        extraRows.forEachIndexed { rowIndex,cards ->
            cards.forEachIndexed { i,v ->
                v.nextFocusLeftId=cards[(i-1).coerceAtLeast(0)].id
                v.nextFocusRightId=cards[(i+1).coerceAtMost(cards.lastIndex)].id
                v.nextFocusUpId=if(rowIndex>0) extraRows[rowIndex-1].getOrNull(i.coerceAtMost(extraRows[rowIndex-1].lastIndex))?.id ?: play.id
                    else episodes.getOrNull((lastRowStart+i).coerceAtMost(episodes.lastIndex))?.id ?: play.id
                v.nextFocusDownId=if(rowIndex<extraRows.lastIndex) extraRows[rowIndex+1].getOrNull(i.coerceAtMost(extraRows[rowIndex+1].lastIndex))?.id ?: v.id else v.id
            }
        }
        if(focusEpisode) episodes.getOrNull((episodeIndex-group*20).coerceIn(0,episodes.lastIndex))?.requestFocus() else play.requestFocus()
    }
