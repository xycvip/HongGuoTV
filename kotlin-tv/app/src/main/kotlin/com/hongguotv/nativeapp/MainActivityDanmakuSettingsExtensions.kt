// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.app.AlertDialog
import android.view.*
import android.content.Context
import android.widget.*
import androidx.media3.common.*
import com.hongguotv.core.*

    internal fun MainActivity.applyDanmakuPrefs() {
        danmakuView?.applyPrefs(library.danmakuOpacity,library.danmakuFont,library.danmakuSpeed,library.danmakuRows)
        danmakuView?.setColorMode(danmakuColorMode)
    }

    internal fun MainActivity.resetDanmakuPrefs() {
        library.danmakuOpacity=DanmakuPrefs.OPACITY_DEFAULT; library.danmakuFont=DanmakuPrefs.FONT_DEFAULT
        library.danmakuSpeed=DanmakuPrefs.SPEED_DEFAULT; library.danmakuRows=DanmakuPrefs.ROWS_DEFAULT
        getSharedPreferences("playback-settings-v1", Context.MODE_PRIVATE).edit()
            .putInt("danmaku_color_mode", DanmakuView.COLOR_MODE_DEFAULT).apply()
        applyDanmakuPrefs()
    }

    internal fun MainActivity.showDanmakuOpacityPicker(anchor: View,done: (()->Unit)?=null) {
        val options=listOf(100,90,80,70,60,50,40,30,21)
        tvTools.choose("弹幕不透明度 · ${library.danmakuOpacity}%",options.map { "$it%" },anchor,{ choice ->
            library.danmakuOpacity=options[choice]; applyDanmakuPrefs()
            if(anchor.tag!="playback-settings") setLabel(anchor, "弹幕不透明度  ·  ${library.danmakuOpacity}%")
            done?.invoke()
        },selectedIndex=options.indexOf(library.danmakuOpacity))
    }

    internal fun MainActivity.showDanmakuFontPicker(anchor: View,done: (()->Unit)?=null) {
        tvTools.choose("弹幕字体大小 · ${DanmakuPrefs.fontLabel(library.danmakuFont)}",DanmakuPrefs.FONT_LABELS,anchor,{ choice ->
            library.danmakuFont=choice; applyDanmakuPrefs()
            if(anchor.tag!="playback-settings") setLabel(anchor, "弹幕字体大小  ·  ${DanmakuPrefs.fontLabel(library.danmakuFont)}")
            done?.invoke()
        },selectedIndex=DanmakuPrefs.font(library.danmakuFont))
    }

    internal fun MainActivity.showDanmakuSpeedPicker(anchor: View,done: (()->Unit)?=null) {
        tvTools.choose("弹幕移动速度 · ${DanmakuPrefs.speedLabel(library.danmakuSpeed)}",DanmakuPrefs.SPEED_LABELS,anchor,{ choice ->
            library.danmakuSpeed=choice; applyDanmakuPrefs()
            if(anchor.tag!="playback-settings") setLabel(anchor, "弹幕移动速度  ·  ${DanmakuPrefs.speedLabel(library.danmakuSpeed)}")
            done?.invoke()
        },selectedIndex=DanmakuPrefs.speed(library.danmakuSpeed))
    }

    internal fun MainActivity.showDanmakuColorPicker(anchor: View,done: (()->Unit)?=null) {
        val options = listOf("默认", "多彩") + DanmakuView.COLOR_NAMES
        tvTools.choose("弹幕颜色 · ${danmakuColorModeLabel()}",options,anchor,{ choice ->
            val mode = when(choice) {
                1 -> DanmakuView.COLOR_MODE_COLORFUL
                in 2..options.lastIndex -> DanmakuView.COLOR_MODE_FIRST + choice - 2
                else -> DanmakuView.COLOR_MODE_DEFAULT
            }
            getSharedPreferences("playback-settings-v1", Context.MODE_PRIVATE).edit()
                .putInt("danmaku_color_mode",mode).apply()
            danmakuView?.setColorMode(mode)
            if(anchor.tag!="playback-settings") setLabel(anchor, "弹幕颜色  ·  ${danmakuColorModeLabel(mode)}")
            done?.invoke()
        },selectedIndex=when(danmakuColorMode) {
            DanmakuView.COLOR_MODE_COLORFUL -> 1
            in DanmakuView.COLOR_MODE_FIRST..(DanmakuView.COLOR_MODE_FIRST+DanmakuView.COLOR_NAMES.lastIndex) -> danmakuColorMode-DanmakuView.COLOR_MODE_FIRST+2
            else -> 0
        })
    }

    internal fun MainActivity.showDanmakuRowsPicker(anchor: View,done: (()->Unit)?=null) {
        tvTools.choose("弹幕展示行数 · ${library.danmakuRows} 行",(DanmakuPrefs.ROWS_MIN..DanmakuPrefs.ROWS_MAX).map { "$it 行" },anchor,{ choice ->
            library.danmakuRows=choice+DanmakuPrefs.ROWS_MIN; applyDanmakuPrefs()
            if(anchor.tag!="playback-settings") setLabel(anchor, "弹幕展示行数  ·  ${library.danmakuRows} 行")
            done?.invoke()
        },selectedIndex=library.danmakuRows-DanmakuPrefs.ROWS_MIN)
    }

    internal fun MainActivity.showDanmakuPrefsDialog(anchor: View) {
        fun labels()=listOf("不透明度  ·  ${library.danmakuOpacity}%","字体大小  ·  ${DanmakuPrefs.fontLabel(library.danmakuFont)}","颜色  ·  ${danmakuColorModeLabel()}","移动速度  ·  ${DanmakuPrefs.speedLabel(library.danmakuSpeed)}","展示行数  ·  ${library.danmakuRows} 行","恢复默认设置")
        // 用「标题 · 当前值」的两栏读法，和设置页里的弹幕分类保持同一套文案。
        tvTools.choose("弹幕设置",labels(),anchor,{ choice ->
            main.post {
                if(screen!="player" || !anchor.isAttachedToWindow) return@post
                when(choice) {
                    0 -> showDanmakuOpacityPicker(anchor) { showDanmakuPrefsDialog(anchor) }
                    1 -> showDanmakuFontPicker(anchor) { showDanmakuPrefsDialog(anchor) }
                    2 -> showDanmakuColorPicker(anchor) { showDanmakuPrefsDialog(anchor) }
                    3 -> showDanmakuSpeedPicker(anchor) { showDanmakuPrefsDialog(anchor) }
                    4 -> showDanmakuRowsPicker(anchor) { showDanmakuPrefsDialog(anchor) }
                    5 -> { resetDanmakuPrefs(); showDanmakuPrefsDialog(anchor) }
                }
            }
        },extra="弹幕设置只影响播放画面，不改动评论与剧集数据。")
    }

    internal fun MainActivity.showBlockedWordDialog(anchor: View) {
        val presets=listOf("加群","微信","公众号","QQ群","QQ","广告","私聊","代看","抖音","快手")
        val current=extras.blockedWords
        val words=(presets+current.filterNot { it in presets }).distinct()
        val checked=BooleanArray(words.size) { words[it] in current }
        val content=column()
        content.addView(TvDialog.title(this,"弹幕屏蔽词"))
        val list=ListView(this).apply {
            adapter=TvDialog.choiceAdapter(this@showBlockedWordDialog,words,multiple=true,checkedStates=checked)
            setOnItemClickListener { _,_,index,_ ->
                // 不依赖 ListView 的临时 choice 状态；以数据数组作为唯一勾选来源。
                checked[index] = !checked[index]
                (adapter as? android.widget.BaseAdapter)?.notifyDataSetChanged()
            }
        }
        content.addView(list,lp(-1,0).apply { weight=1f })
        val buttons=row().apply { gravity=Gravity.END; setPadding(0,dp(14),0,0) }
        val clear=TvDialog.capsuleButton(this,"清空") {
            extras.blockedWords=emptySet(); applyBlockedWords(); setLabel(anchor, "弹幕屏蔽词  ·  0 个")
            checked.fill(false)
            list.post { (list.adapter as? android.widget.BaseAdapter)?.notifyDataSetChanged(); list.invalidateViews(); list.requestLayout() }
        }
        val save=TvDialog.capsuleButton(this,"保存",primary=true) {
            extras.blockedWords=words.filterIndexed { index,_ -> checked[index] }.toSet()
            applyBlockedWords(); setLabel(anchor, "弹幕屏蔽词  ·  ${extras.blockedWords.size} 个")
            Toast.makeText(this@showBlockedWordDialog,"屏蔽词已保存，立即生效",Toast.LENGTH_SHORT).show()
        }
        buttons.addView(clear,lp(-2,-2).apply { rightMargin=dp(12) })
        buttons.addView(save,lp(-2,-2))
        content.addView(buttons)
        val dialog=AlertDialog.Builder(this).setView(TvDialog.card(this,content)).create()
        TvDialog.styleWindow(this,dialog)
        dialog.setOnDismissListener { if(anchor.isAttachedToWindow) anchor.requestFocus() }
        dialog.show()
        list.post {
            list.invalidateViews()
            list.requestFocus()
        }
    }
