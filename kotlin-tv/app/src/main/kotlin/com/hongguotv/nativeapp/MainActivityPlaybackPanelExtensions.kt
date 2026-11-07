// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import android.graphics.Color
import android.text.TextUtils
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.*
import android.widget.*
import androidx.media3.common.*
import com.hongguotv.core.*
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.DecoderMode

internal fun MainActivity.showFramePicker(anchor: TextView,inPlayer: Boolean) {
        tvTools.choose("全局画面模式",listOf("完整画面 · 保留比例与全部内容","等比铺满 · 会裁掉部分画面与字幕","拉伸铺满 · 画面比例会改变"),anchor,{ index ->
            library.frameMode=VideoFrameMode.entries[index]; playerView?.resizeMode=activeFrameResizeMode()
            if(anchor.tag!="playback-settings") anchor.text=(if(inPlayer) "画面 " else "画面模式  ·  ")+library.frameMode.label
        })
    }

    internal fun MainActivity.showPanel() {
        if(playError) return
        panel=true; showHud(); controls.visibility=View.VISIBLE; controls.removeAllViews()
        fun actionStrip(): Pair<HorizontalScrollView,LinearLayout> {
            val strip=HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled=false
                clipToPadding=false
                descendantFocusability=ViewGroup.FOCUS_AFTER_DESCENDANTS
                overScrollMode=View.OVER_SCROLL_NEVER
            }
            val line=row()
            strip.addView(line,lp(-2,-2))
            return strip to line
        }
        val (primaryStrip,transport)=actionStrip()
        val secondaryTransport: LinearLayout
        // 手机仅竖屏使用双行触控布局；手机横屏与电视统一使用单行横向滚动布局。
        if(isPhoneDevice && heightDp()>widthDp()) {
            val (secondaryStrip,secondary)=actionStrip()
            secondaryTransport=secondary
            controls.addView(primaryStrip,lp(-1,-2))
            controls.addView(secondaryStrip,lp(-1,-2))
        } else {
            secondaryTransport=transport
            controls.addView(primaryStrip,lp(-1,-2))
        }
        addButton(transport,"评论") { openOfficialComments(transport.findViewWithTag<View>("player:comments")) }.apply { tag="player:comments" }
        val play=addButton(transport,if(player?.playWhenReady==true) "暂停" else "播放") { togglePlayback() }.also { transportPlay=it }
        addButton(transport,"重播") { playEpisode(episodeIndex,0,autoplay=true,forceRebuild=true) }
        addButton(transport,"上集") { if(episodeIndex>0) playEpisode(episodeIndex-1) }.apply { isEnabled=episodeIndex>0; isFocusable=episodeIndex>0; alpha=if(episodeIndex>0) 1f else .4f }
        addButton(transport,"下集") { if(episodeIndex<(detail?.episodes?.lastIndex ?: 0)) playEpisode(episodeIndex+1) }.apply { val enabled=episodeIndex<(detail?.episodes?.lastIndex ?: 0); isEnabled=enabled; isFocusable=enabled; alpha=if(enabled) 1f else .4f }
        lateinit var episodes: TextView
        episodes=addButton(transport,"选集") { showEpisodePanel(episodes) }
        // 方向切换仅手机端显示，按钮文字表示当前方向；切换剧集时保留本次播放会话选择。
        if(isPhoneDevice) {
            lateinit var orientationButton: TextView
            orientationButton=addButton(secondaryTransport,phonePlaybackOrientationLabel()) {
                togglePhonePlaybackOrientation()
                orientationButton.text=phonePlaybackOrientationLabel()
                showHud()
            }
        }
        fun speedLabel()=String.format(java.util.Locale.US,"%.2f",library.playbackSpeed.toDouble())
        lateinit var speedButton: TextView
        speedButton=addButton(secondaryTransport,speedLabel()) { showSpeedPicker(speedButton) }

        // 播放工具栏与设置页保持一致，提供四种解码模式。
        // 系统软解是 MediaCodec 的 softwareOnly 解码器；FFmpeg 软解使用独立原生运行库。
        val decoderModes=listOf(DecoderMode.AUTO,DecoderMode.HARDWARE,DecoderMode.SOFTWARE,DecoderMode.FFMPEG)
        val decoderLabels=mapOf(
            DecoderMode.AUTO to "自动",
            DecoderMode.HARDWARE to "硬解",
            DecoderMode.SOFTWARE to "系统软解",
            DecoderMode.FFMPEG to "FFmpeg软解"
        )
        val decoderButtons=mutableMapOf<DecoderMode,TextView>()
        fun refreshDecoderButtons() {
            val selected=FfmpegPlayerRegistry.videoMode
            decoderModes.forEach { mode ->
                decoderButtons[mode]?.text=(if(selected==mode) "✓ " else "")+decoderLabels.getValue(mode)
            }
        }
        decoderModes.forEach { mode ->
            val button=addButton(secondaryTransport,decoderLabels.getValue(mode)) {
                if(mode==DecoderMode.FFMPEG && !FfmpegRuntimeLoader.isReady) {
                    Toast.makeText(this,"FFmpeg软解运行库尚未安装，请先到设置→播放→FFmpeg运行库下载",Toast.LENGTH_LONG).show()
                    return@addButton
                }
                val previous=FfmpegPlayerRegistry.videoMode
                FfmpegPlayerRegistry.setVideoMode(this,mode)
                debugLog("播放器工具栏切换解码模式：$previous -> $mode；FFmpeg ready=${FfmpegRuntimeLoader.isReady}；当前解码器=${activeDecoderName.ifBlank { "尚未初始化" }}")
                refreshDecoderButtons()
                Toast.makeText(this,"解码方式：${decoderLabels.getValue(mode)}（动态切换）",Toast.LENGTH_SHORT).show()
            }
            decoderButtons[mode]=button
        }
        refreshDecoderButtons()

        lateinit var qualityButton: TextView
        qualityButton=addButton(secondaryTransport,"画质") { showQualityPicker(qualityButton,true) }
        lateinit var timerButton: TextView
        timerButton=addButton(secondaryTransport,if(sleepTimer.active) "定时 ${sleepTimer.label()}" else "定时") { showSleepPicker(timerButton) }.also { sleepButton=it }
        val seriesId=detail?.series?.id.orEmpty()
        lateinit var danmakuButton: TextView
        fun refreshDanmakuLabel() { danmakuButton.text="弹幕" }
        danmakuButton=addButton(secondaryTransport,"弹幕") {
            toggleSeriesDanmaku(seriesId)
            refreshDanmakuLabel()
        }
        val mark=library.skipMark(seriesId)
        lateinit var introButton: TextView
        introButton=addButton(secondaryTransport,skipButtonLabel(true,mark?.introSeconds ?: 0)) { showSkipPicker(introButton,true) }.apply { tag="player:skip-intro" }
        lateinit var outroButton: TextView
        outroButton=addButton(secondaryTransport,skipButtonLabel(false,mark?.outroSeconds ?: 0)) { showSkipPicker(outroButton,false) }.apply { tag="player:skip-outro" }
        play.requestFocus()
    }

    internal fun MainActivity.continueToNextSeason(): Boolean {
        val data=detail ?: return false
        if(!library.autoNext || !library.autoSeason || episodeIndex<data.episodes.lastIndex) return false
        val next=data.nextSeason ?: return false
        Toast.makeText(this,"即将播放《${next.title}》",Toast.LENGTH_SHORT).show()
        openDetail(next,resume=true,autoplay=true)
        return true
    }

    internal fun MainActivity.showEndedPanel() {
        val data=detail
        if(playError || data==null || !library.autoNext || !library.autoSeason || episodeIndex<data.episodes.lastIndex) {
            playbackText.text="本集已结束"; showPanel(); return
        }
        val cached=library.cachedHome(library.contentType)?.items.orEmpty().filter { it.id!=data.series.id }
        if(cached.isNotEmpty()) { showRecommendationPanel(cached); return }
        playbackText.text="本集已结束"; showPanel()
        // The home cache can be empty on a cold start that went straight into playback; fetch once and upgrade the panel.
        work({ repository.home(1,library.contentType) }, { page ->
            if(screen=="player" && player?.playbackState==Player.STATE_ENDED && detail?.series?.id==data.series.id) {
                val items=page.items.filter { it.id!=data.series.id }
                if(items.isNotEmpty()) showRecommendationPanel(items)
            }
        }, { /* keep the plain ended panel */ })
    }

    internal fun MainActivity.showRecommendationPanel(items: List<Series>) {
        if(playError || screen!="player") return
        panel=true; controls.visibility=View.VISIBLE; controls.removeAllViews()
        controls.addView(text("《${detail?.series?.title}》已播完 · 为你推荐热门${library.contentType.label}",14f,muted).apply { setPadding(dp(4),0,0,dp(8)) })
        val strip=HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled=false; clipToPadding=false }
        val line=row(); strip.addView(line); controls.addView(strip)
        items.take(10).forEach { item ->
            val card=column(); card.setPadding(dp(4),dp(4),dp(4),dp(8)); focusStyle(card)
            val image=ImageView(this).apply { scaleType=ImageView.ScaleType.CENTER_CROP; importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO }
            card.addView(image,lp(dp(96),(dp(96)/TvStyle.POSTER_ASPECT).toInt()))
            loadCover(image,item.cover)
            card.addView(text(item.title,12f).apply { maxLines=2; minLines=2; ellipsize=TextUtils.TruncateAt.END; setPadding(dp(2),dp(4),dp(2),0) },lp(dp(96),-2))
            card.contentDescription=item.title
            card.setOnClickListener { openDetail(item) }
            line.addView(card,lp(-2,-2).apply { rightMargin=dp(10) })
        }
        val actions=row().apply { setPadding(0,dp(10),0,0) }; controls.addView(actions)
        addButton(actions,"返回详情") { returnToDetail(true) }
        line.getChildAt(0)?.requestFocus()
        showHud()
    }

    internal fun MainActivity.showSkipPicker(anchor: TextView,intro: Boolean) {
        val data=detail ?: return
        val options=listOf(0,15,30,45,60,90,120)
        val current=library.skipMark(data.series.id)?.let { if(intro) it.introSeconds else it.outroSeconds } ?: 0
        tvTools.choose((if(intro) "跳过片头" else "跳过片尾")+" · 仅对本剧生效",options.map { if(it==0) "关闭" else "$it 秒" },anchor,{ index ->
            val old=library.skipMark(data.series.id) ?: SkipMark(0,0)
            val next=if(intro) old.copy(introSeconds=options[index]) else old.copy(outroSeconds=options[index])
            library.setSkipMark(data.series.id,next)
            if(!intro) { outroSkipSeconds=next.outroSeconds; outroTriggered=false }
            if(anchor.isAttachedToWindow) anchor.text=skipButtonLabel(intro,options[index])
            Toast.makeText(this,"已保存，仅对《${data.series.title}》生效",Toast.LENGTH_SHORT).show()
            showHud()
        },"当前 ${if(current>0) "$current 秒" else "关闭"}") {}
    }

    internal fun MainActivity.showSpeedPicker(anchor: TextView,inPlayer: Boolean=true) {
        val speeds=PlaybackSpeed.options
        tvTools.choose("全局默认倍速",speeds.map(PlaybackSpeed::label),anchor,{ index ->
            library.playbackSpeed=speeds[index]
            if(inPlayer) player?.setPlaybackSpeed(library.playbackSpeed)
            if(anchor.tag!="playback-settings") anchor.text=String.format(java.util.Locale.US,"%.2f",library.playbackSpeed.toDouble()) else "默认倍速  ·  "+PlaybackSpeed.label(library.playbackSpeed)
            if(inPlayer) updatePlaybackText()
        })
    }

    internal fun MainActivity.showQualityPicker(anchor: TextView,inPlayer: Boolean) {
        val options=PlaybackQuality.options
        tvTools.choose("全局默认清晰度",options.map { "${it}P" },anchor,{ index ->
            val target=options[index]
            val changed=library.maxQuality!=target
            library.maxQuality=target
            if(anchor.tag!="playback-settings") anchor.text=if(inPlayer) "画质" else "默认清晰度  ·  ${target}P"
            if(inPlayer && changed) playEpisode(episodeIndex,currentPosition(),player?.playWhenReady ?: requestedAutoplay)
        })
    }

    internal fun MainActivity.showEpisodePanel(anchor: View) {
        val data=detail ?: return
        if(episodePanel!=null) return
        val ticket=generation
        val overlayWasVisible=hud.visibility==View.VISIBLE
        requestedAutoplay=player?.playWhenReady ?: requestedAutoplay
        player?.pause(); saveProgress()
        var resumeOnClose=true
        val next=EpisodePanel(this,data.episodes.size,episodeIndex,
            { current,view,located -> tvTools.episodePicker(data.episodes.size,current,view,located) },
            { selected ->
                if(selected==episodeIndex) episodePanel?.dismiss()
                else { resumeOnClose=false; episodePanel?.dismiss(); playEpisode(selected) }
            },
            {
                episodePanel=null
                if(resumeOnClose && foreground && generation==ticket && screen=="player") {
                    if(requestedAutoplay && !sleepStopped && !pausedForLifecycle) player?.play()
                    if(anchor.isAttachedToWindow) anchor.requestFocus()
                    if(overlayWasVisible) showHud() else hidePlaybackOverlay()
                }
            })
        episodePanel=next; next.show()
    }
