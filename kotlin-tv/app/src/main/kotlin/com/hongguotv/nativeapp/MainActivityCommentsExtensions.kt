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

    internal fun MainActivity.openOfficialComments(anchor: View?) {
        if(activePlatform==ContentPlatform.HIPPO || screen!="player" || commentPanel!=null) return
        val data=detail ?: return
        commentAnchor=anchor
        panel=false
        controls.visibility=View.GONE
        controls.clearFocus()
        val signedIn=cloudAccount.signedIn
        val view=OfficialCommentPanel(this,signedIn,object: OfficialCommentPanel.Listener {
            override fun close()=closeOfficialComments()
            override fun previousEpisode()=skipEpisode(-1)
            override fun nextEpisode()=skipEpisode(1)
            override fun loadMore()=loadOfficialComments(true)
            override fun like(comment: OfficialComment)=toggleOfficialCommentLike(comment)
            override fun reply(comment: OfficialComment) {
                if(!cloudAccount.signedIn) requireOfficialCommentLogin()
                else commentPanel?.setReplyTarget(comment)
            }
            override fun openReplies(comment: OfficialComment)=loadOfficialCommentReplies(comment,false)
            override fun loadMoreReplies(comment: OfficialComment)=loadOfficialCommentReplies(comment,true)
            override fun send(text: String)=sendOfficialComment(text)
            override fun login()=requireOfficialCommentLogin()
        }, avatarLoader={ image, url -> artwork.load(image,url,ArtworkLoader.PRIORITY_VISIBLE,dp(34),dp(34)) })
        commentPanel=view
        val screenWidth=resources.displayMetrics.widthPixels
        val screenHeight=resources.displayMetrics.heightPixels
        val portraitScreen=screenHeight>screenWidth
        if(portraitScreen) {
            // 手机竖屏采用底部弹层：严格不超过屏幕高度的 50%，同时限制在 520dp 内。
            // 小屏设备也不设置强制最小高度，确保任何情况下都不会突破一半屏幕。
            val panelHeight=minOf((screenHeight*.5f).toInt(),dp(520)).coerceAtLeast(dp(1))
            val panelWidth=(screenWidth-dp(24)).coerceAtLeast(dp(1))
            view.tag="comment-bottom-sheet"
            view.background=GradientDrawable().apply {
                setColor(Color.rgb(20,24,31))
                cornerRadii=floatArrayOf(dp(18).toFloat(),dp(18).toFloat(),dp(18).toFloat(),dp(18).toFloat(),0f,0f,0f,0f)
            }
            view.elevation=dp(18).toFloat()
            root.addView(view,FrameLayout.LayoutParams(panelWidth,panelHeight,Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                bottomMargin=dp(8)
            })
            view.translationY=panelHeight.toFloat()
            view.animate().translationY(0f).setDuration(240).start()
        } else {
            // 横屏保持原有右侧评论面板尺寸与进场方式，不改变电视端布局。
            val portraitVideoWidth=(screenHeight*9f/16f).toInt().coerceAtMost(screenWidth)
            val rightSpace=((screenWidth-portraitVideoWidth)/2).coerceAtLeast(0)
            val rightMargin=dp(18)
            val videoGap=dp(24)
            val width=minOf(dp(520),(rightSpace-rightMargin-videoGap).coerceAtLeast(dp(1)))
            view.tag="comment-side-panel"
            root.addView(view,FrameLayout.LayoutParams(width,-1,Gravity.END).apply { this.rightMargin=rightMargin })
            view.translationX=width.toFloat()
            view.animate().translationX(0f).setDuration(220).start()
        }
        view.setEpisode(data.series.title,episodeIndex+1)
        loadOfficialComments(false)
        view.post { if(commentPanel===view) view.focusCommentArea() }
        // 评论界面打开时立即隐藏播放进度 HUD 和下方播放选项栏，评论面板自身不受影响。
        hidePlaybackOverlay()
    }

    internal fun MainActivity.closeOfficialComments() {
        val view=commentPanel ?: return
        commentGeneration++
        commentListScope?.cancel(); commentListScope=null
        if(view.parent===root) {
            val closing=if(view.tag=="comment-bottom-sheet") {
                view.animate().translationY(view.height.toFloat())
            } else {
                view.animate().translationX(view.width.toFloat())
            }
            closing.setDuration(180).withEndAction {
                if(view.parent===root) root.removeView(view)
            }.start()
        }
        commentPanel=null
        commentReplyCursors.clear()
        if(screen=="player" && !isFinishing) {
            showPanel()
            panel=false
            showHud()
            val target=controls.findViewWithTag<View>("player:comments")
            target?.requestFocus()
        } else commentAnchor?.takeIf { it.isAttachedToWindow }?.requestFocus()
        commentAnchor=null
    }

    internal fun MainActivity.currentOfficialCommentCredentials(): AccountCredentials? {
        if(!cloudAccount.signedIn) return null
        // A restored session may still be refreshing its profile; anonymous reads remain available.
        return cloudAccount.recommendationCredentials()
    }

    internal fun MainActivity.loadOfficialComments(append: Boolean) {
        val view=commentPanel ?: return
        val data=detail ?: return
        val seriesId=data.series.id
        val vid=data.episodes.getOrNull(episodeIndex) ?: return
        val cursor=if(append) commentCursor else "0"
        val token=++commentGeneration
        commentListScope?.cancel()
        val scope=RequestScope()
        commentListScope=scope
        if(!append) commentCursor="0"
        view.showLoading(if(append) "正在加载更多评论…" else "正在加载本集评论…")
        val credentials=runCatching { currentOfficialCommentCredentials() }
        if(credentials.isFailure) { view.showError(credentials.exceptionOrNull()?.message ?: "账号状态暂不可用"); return }
        io.execute {
            val result=runCatching { scope.run { officialCommentRepository.list(credentials.getOrNull(),seriesId,vid,cursor) } }
            main.post {
                if(commentListScope===scope) commentListScope=null
                if(token!=commentGeneration || commentPanel!==view || screen!="player" ||
                    detail?.series?.id!=seriesId || detail?.episodes?.getOrNull(episodeIndex)!=vid) return@post
                result.fold({ page ->
                    commentCursor=page.nextCursor
                    view.setPage(page,append)
                    // setPage 会自行恢复原焦点；仅首次加载时才落到列表首个可操作项。
                },{ error -> view.showError("评论加载失败：${error.message.orEmpty().take(90)}") })
            }
        }
    }

    internal fun MainActivity.loadOfficialCommentReplies(comment: OfficialComment, append: Boolean) {
        val view=commentPanel ?: return
        val data=detail ?: return
        val seriesId=data.series.id
        val vid=data.episodes.getOrNull(episodeIndex) ?: return
        val cursor=if(append) commentReplyCursors[comment.id].orEmpty() else "0"
        val token=commentGeneration
        val credentials=runCatching { currentOfficialCommentCredentials() }
        if(credentials.isFailure) { Toast.makeText(this,credentials.exceptionOrNull()?.message ?: "账号状态暂不可用",Toast.LENGTH_SHORT).show(); return }
        view.setReplyLoading(comment.id,true)
        io.execute {
            val result=runCatching { officialCommentRepository.replies(credentials.getOrNull(),seriesId,vid,comment.id,cursor) }
            main.post {
                if(token!=commentGeneration || commentPanel!==view || detail?.series?.id!=seriesId ||
                    detail?.episodes?.getOrNull(episodeIndex)!=vid) return@post
                result.fold({ page ->
                    commentReplyCursors[comment.id]=page.nextCursor
                    // 回复区域展开后保留「查看回复」原焦点，不把光标送回列表顶部。
                    view.setReplies(comment.id,page,append)
                },{
                    view.setReplyLoading(comment.id,false)
                    Toast.makeText(this,"回复加载失败：${it.message.orEmpty()}",Toast.LENGTH_SHORT).show()
                })
            }
        }
    }

    internal fun MainActivity.toggleOfficialCommentLike(comment: OfficialComment) {
        if(!cloudAccount.signedIn) { requireOfficialCommentLogin(); return }
        val view=commentPanel ?: return
        val data=detail ?: return
        val seriesId=data.series.id
        val vid=data.episodes.getOrNull(episodeIndex) ?: return
        val credentials=runCatching { currentOfficialCommentCredentials() }
        if(credentials.isFailure || credentials.getOrNull()==null) { Toast.makeText(this,"红果账号正在验证或已失效，请重新登录",Toast.LENGTH_SHORT).show(); return }
        val token=commentGeneration
        io.execute {
            val result=runCatching { officialCommentRepository.setLiked(credentials.getOrNull(),seriesId,comment.id,!comment.liked) }
            main.post {
                if(token!=commentGeneration || commentPanel!==view || detail?.episodes?.getOrNull(episodeIndex)!=vid) return@post
                result.fold({ loadOfficialComments(false) },{ Toast.makeText(this,"点赞操作失败：${it.message.orEmpty()}",Toast.LENGTH_SHORT).show() })
            }
        }
    }

    internal fun MainActivity.sendOfficialComment(raw: String) {
        if(!cloudAccount.signedIn) { requireOfficialCommentLogin(); return }
        val view=commentPanel ?: return
        val data=detail ?: return
        val text=raw.trim()
        if(text.isEmpty()) { Toast.makeText(this,"先写点评论内容",Toast.LENGTH_SHORT).show(); return }
        val seriesId=data.series.id
        val vid=data.episodes.getOrNull(episodeIndex) ?: return
        val replyTarget=view.replyTarget()
        val credentials=runCatching { currentOfficialCommentCredentials() }
        if(credentials.isFailure || credentials.getOrNull()==null) { Toast.makeText(this,"红果账号正在验证或已失效，请重新登录",Toast.LENGTH_SHORT).show(); return }
        val token=commentGeneration
        view.setSending(true)
        io.execute {
            val result=runCatching {
                if(replyTarget==null) officialCommentRepository.publish(credentials.getOrNull(),seriesId,vid,text)
                else officialCommentRepository.reply(credentials.getOrNull(),seriesId,vid,replyTarget.id,text)
            }
            main.post {
                if(token!=commentGeneration || commentPanel!==view || detail?.episodes?.getOrNull(episodeIndex)!=vid) return@post
                view.setSending(false)
                result.fold({
                    view.clearDraft()
                    Toast.makeText(this,if(replyTarget==null) "评论已提交，审核通过后显示" else "回复已提交，审核通过后显示",Toast.LENGTH_SHORT).show()
                    loadOfficialComments(false)
                },{ Toast.makeText(this,"提交失败：${it.message.orEmpty()}",Toast.LENGTH_LONG).show() })
            }
        }
    }

    internal fun MainActivity.requireOfficialCommentLogin() {
        closeOfficialComments()
        TvDialog.confirm(
            this,
            "需要登录",
            "登录红果账号后即可发表评论、回复和点赞。是否现在登录？",
            action = { cloudAccount.show() }
        ).show()
    }
