// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.app.AlertDialog
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.hongguotv.core.PhoneField
import com.hongguotv.core.PhoneForm
import com.hongguotv.core.Series
import java.util.Locale

private data class PushShareItem(val title: String, val url: String, val source: String)

private object PushShareParser {
    private val redFruitTitle=Regex("漫剧《([^》]{1,80})》")
    private val redFruitUrl=Regex("https?://(?:www\\.)?novelquickapp\\.com/s/[A-Za-z0-9_-]+",RegexOption.IGNORE_CASE)
    private val douyinUrl=Regex("https?://v\\.douyin\\.com/[A-Za-z0-9_/-]+/?",RegexOption.IGNORE_CASE)
    private val douyinAuthor=Regex("【([^】]{1,50})的作品】")
    private val bracketTitle=Regex("《([^》]{1,80})》")
    private fun clean(value:String)=value.replace(Regex("#[^\\n]*"),"").replace(Regex("\\s+")," ").trim().trimEnd('！','!','。','，',',','…')
    fun parse(raw:String):List<PushShareItem> {
        val result=mutableListOf<PushShareItem>()
        val redUrls=redFruitUrl.findAll(raw).map { it.value }.toList()
        val redTitles=redFruitTitle.findAll(raw).map { it.groupValues[1].trim() }.toList()
        redUrls.forEachIndexed { i,url ->
            val title=redTitles.getOrNull(i).orEmpty()
            if(title.isNotBlank()) result+=PushShareItem(title,url,"红果")
        }
        val douyinBlocks=raw.split(Regex("(?=\\d+(?:\\.\\d+)?\\s+复制打开抖音)"))
        for(block in douyinBlocks) {
            val url=douyinUrl.find(block)?.value ?: continue
            val author=douyinAuthor.find(block)?.groupValues?.getOrNull(1).orEmpty()
            val afterAuthor=block.substringAfter("】",block)
            val explicit=bracketTitle.find(afterAuthor)?.groupValues?.getOrNull(1).orEmpty()
            val beforeUrl=afterAuthor.substringBefore(url)
            val title=if(explicit.isNotBlank()) explicit else clean(beforeUrl)
            if(title.isNotBlank()) result+=PushShareItem(title,url,"抖音")
        }
        return result.distinctBy { it.url }
    }
}

internal fun MainActivity.showPushPlayback() {
    val form=PhoneForm(
        heading="推送播放",
        description="在手机上打开红果短剧或抖音的分享内容，复制完整分享文案后粘贴到这里，再发送到电视。手机与电视需连接同一局域网。",
        fields=listOf(PhoneField("share","分享文案或链接","粘贴红果短剧 / 抖音分享文案或链接",10000,true,true)),
        submitLabel="发送到电视",
        doneMessage="已发送到电视",
        doneHint="电视正在识别分享内容，请回到电视查看。",
        invalidMessage="请粘贴有效的分享文案或链接"
    )
    tvTools.phoneForm(nav.lastOrNull(),form) { values ->
        val raw=values["share"].orEmpty()
        if(raw.isNotBlank()) acceptPushShare(raw)
    }
}

private fun MainActivity.acceptPushShare(raw:String) {
    val shares=PushShareParser.parse(raw)
    if(shares.isEmpty()) {
        tvTools.info("推送播放","没有识别到受支持的红果或抖音分享格式。请复制完整分享文案后重试。",nav.lastOrNull())
        return
    }
    val revision=++pushPlaybackRevision
    pushPlaybackTask?.cancel(true)
    Toast.makeText(this,"已收到推送，正在搜索：" + shares.joinToString("、") { it.title }.take(80),Toast.LENGTH_SHORT).show()
    pushPlaybackTask=io.submit {
        try {
            val candidates=mutableListOf<Pair<PushShareItem,Series>>()
            val failures=mutableListOf<String>()
            shares.take(4).forEach { share ->
                if(Thread.currentThread().isInterrupted) return@submit
                try {
                    repository.searchCombined(share.title,1).items.take(12).forEach { series ->
                        if(candidates.none { it.second.id==series.id }) candidates+=share to series
                    }
                } catch(problem:Exception) {
                    failures+= share.title + "：" + (problem.message ?: "搜索失败")
                }
            }
            val ranked=candidates.distinctBy { it.second.id }
                .sortedByDescending { (_,series) -> pushTitleScore(series.title,shares.map { it.title }) }.take(12)
            val exact=ranked.filter { (_,series) -> shares.any { normalizePushTitle(series.title)==normalizePushTitle(it.title) } }
            main.post {
                if(isDestroyed || revision!=pushPlaybackRevision) return@post
                pushPlaybackTask=null
                when {
                    shares.size==1 && exact.size==1 -> openDetail(exact.first().second,resume=true)
                    ranked.isNotEmpty() -> showPushCandidates(ranked.map { it.second },shares.joinToString("、") { it.title })
                    else -> tvTools.info("推送播放 · 匹配失败",
                        if(failures.isNotEmpty()) "已识别分享内容，但片名搜索暂不可用。\n" + failures.take(2).joinToString("\n") else "已识别分享链接，但没有找到可确认的剧集。为避免误播，未开始播放。",nav.lastOrNull())
                }
            }
        } catch(problem:Exception) {
            main.post {
                if(!isDestroyed && revision==pushPlaybackRevision) {
                    pushPlaybackTask=null
                    tvTools.info("推送播放 · 匹配失败","识别或搜索失败：" + (problem.message ?: "未知错误") + "\n没有开始播放。",nav.lastOrNull())
                }
            }
        }
    }
}

private fun normalizePushTitle(value:String)=value.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }

private fun pushTitleScore(title:String,queries:List<String>):Int {
    val candidate=normalizePushTitle(title)
    return queries.maxOfOrNull { query ->
        val key=normalizePushTitle(query)
        when {
            key.isBlank() -> 0
            candidate==key -> 1000
            candidate.contains(key) || key.contains(candidate) -> 700
            else -> {
                val common=key.toSet().intersect(candidate.toSet()).size
                common*100/(key.toSet().size.coerceAtLeast(1))
            }
        }
    } ?: 0
}

private fun MainActivity.showPushCandidates(candidates:List<Series>,query:String) {
    val revision=pushPlaybackRevision
    val content=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(18),dp(8),dp(18),dp(12)) }
    val heading=TextView(this).apply {
        text="分享内容：" + query + "\n找到多个可能匹配的剧集，请选择要播放的内容。"
        textSize=15f; setTextColor(TvStyle.muted); setPadding(0,0,0,dp(12))
    }
    content.addView(heading)
    val scroll=ScrollView(this).apply { isVerticalScrollBarEnabled=false }
    val grid=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
    scroll.addView(grid)
    val columns=3
    val cards=mutableListOf<View>()
    lateinit var picker: AlertDialog
    candidates.forEachIndexed { index,series ->
        if(index%columns==0) grid.addView(LinearLayout(this).apply { gravity=Gravity.START; orientation=LinearLayout.HORIZONTAL })
        val line=grid.getChildAt(grid.childCount-1) as LinearLayout
        val card=LinearLayout(this).apply {
            id=View.generateViewId()
            orientation=LinearLayout.VERTICAL; gravity=Gravity.CENTER_HORIZONTAL
            isFocusable=true; isFocusableInTouchMode=true; isClickable=true
            setPadding(dp(7),dp(7),dp(7),dp(8))
            background=GradientDrawable().apply { setColor(TvStyle.surface); cornerRadius=dp(10).toFloat(); setStroke(dp(1),TvStyle.outline) }
            contentDescription="选择剧集：" + series.title
            setOnFocusChangeListener { view,focused ->
                view.background=GradientDrawable().apply {
                    setColor(if(focused) TvStyle.selectedSurface else TvStyle.surface)
                    cornerRadius=dp(10).toFloat()
                    setStroke(dp(if(focused) 2 else 1),if(focused) TvStyle.accent else TvStyle.outline)
                }
            }
            setOnClickListener {
                if(revision!=pushPlaybackRevision) return@setOnClickListener
                pushPlaybackRevision++
                pushPlaybackTask?.cancel(true); pushPlaybackTask=null
                picker.dismiss()
                openDetail(series,resume=true)
            }
        }
        val poster=ImageView(this).apply { scaleType=ImageView.ScaleType.CENTER_CROP; setBackgroundColor(TvStyle.badgeSurface) }
        card.addView(poster,LinearLayout.LayoutParams(dp(104),(dp(104)/TvStyle.POSTER_ASPECT).toInt()))
        loadCover(poster,series.cover)
        val title=TextView(this).apply {
            text=series.title; textSize=13f; setTextColor(TvStyle.text); gravity=Gravity.CENTER
            maxLines=2; setPadding(0,dp(6),0,0); typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL)
        }
        card.addView(title,LinearLayout.LayoutParams(-1,dp(42)))
        val type=TextView(this).apply {
            text=series.badge.ifBlank { series.tags.ifBlank { "剧集" } }; textSize=10f; setTextColor(TvStyle.muted)
            gravity=Gravity.CENTER; maxLines=1
        }
        card.addView(type,LinearLayout.LayoutParams(-1,dp(20)))
        line.addView(card,LinearLayout.LayoutParams(0,-2,1f).apply { setMargins(dp(5),dp(5),dp(5),dp(5)) })
        cards+=card
    }
    content.addView(scroll,LinearLayout.LayoutParams(-1,dp(360)))
    picker=AlertDialog.Builder(this).setTitle("请选择要播放的剧集").setView(content).setNegativeButton("取消",null).create()
    TvDialog.styleWindow(this,picker)
    picker.setOnDismissListener { if(revision==pushPlaybackRevision) nav.lastOrNull()?.requestFocus() }
    picker.show()
    cards.forEachIndexed { index,view ->
        view.nextFocusLeftId=cards.getOrNull(index-1)?.id ?: view.id
        view.nextFocusRightId=cards.getOrNull(index+1)?.id ?: view.id
        view.nextFocusUpId=cards.getOrNull(index-columns)?.id ?: view.id
        view.nextFocusDownId=cards.getOrNull(index+columns)?.id ?: view.id
    }
    cards.firstOrNull()?.requestFocus()
}
