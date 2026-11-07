// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.widget.Toast
import com.hongguotv.core.*

/** 弹幕生命周期、剧集开关与屏蔽词应用。 */
internal fun MainActivity.startDanmaku() {
        if(activePlatform==ContentPlatform.HIPPO) return
        val layer=danmakuView ?: return
        val data=detail ?: return
        val p=player
        if(!extras.danmakuEnabled(data.series.id,library.danmaku) || p==null || !playbackReady || playError || p.duration<=0) return
        danmakuLoader=null
        val loader=DanmakuLoader(danmakuRepo,data.series.id,data.episodes[episodeIndex],p.duration)
        danmakuLoader=loader
        layer.setBlockedFilter { text -> extras.blocks(text) }
        layer.bind(
            loader,
            { currentPosition() },
            { position -> runCatching { io.execute { runCatching { loader.loadUpTo(position) } } } },
            playing=p.isPlaying
        )
        val startPosition=currentPosition()
        runCatching { io.execute { runCatching { loader.loadUpTo(startPosition) } } }
    }

internal fun MainActivity.toggleSeriesDanmaku(seriesId: String) {
        if(activePlatform==ContentPlatform.HIPPO) {
            Toast.makeText(this,"河马平台暂未接入红果弹幕接口",Toast.LENGTH_SHORT).show()
            return
        }
        if(!library.danmaku) {
            Toast.makeText(this,"弹幕总开关已关闭，请先在设置或播放设置中开启",Toast.LENGTH_SHORT).show(); return
        }
        val on=!extras.danmakuEnabled(seriesId,true)
        extras.setDanmakuEnabled(seriesId,on)
        if(on) startDanmaku() else stopDanmaku()
        Toast.makeText(this,"已为《${detail?.series?.title.orEmpty()}》${if(on) "开启" else "关闭"}弹幕",Toast.LENGTH_SHORT).show()
    }

internal fun MainActivity.stopDanmaku() {
        danmakuLoader=null
        danmakuView?.unbind()
    }

internal fun MainActivity.applyBlockedWords() = danmakuView?.setBlockedFilter { text -> extras.blocks(text) }
