// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.app.Activity
import android.view.View
import android.widget.Toast
import androidx.media3.exoplayer.ExoPlayer

/** 单部影片片头/片尾跳过策略：设置入口、当前播放位置应用及片尾自动衔接。 */
internal class ThirdSourcePlaybackSkipController(
    private val activity: Activity,
    private val store: ThirdStore,
    private val tools: TvTools,
    private val currentDetail: () -> ThirdDetail?,
    private val currentSourceApi: () -> String?,
    private val currentPlayer: () -> ExoPlayer?,
    private val currentGroup: () -> Int,
    private val currentEpisode: () -> Int,
    private val jumpEpisode: (Int) -> Unit,
    private val showOverlay: () -> Unit
) {
    private var outroTriggered = false

    fun resetOutro() {
        outroTriggered = false
    }

    fun storageKey(videoId: String): String =
        ThirdPlaybackSkipRules.storageKey(currentSourceApi(), videoId)

    fun showDialog(anchor: View) {
        val videoId = currentDetail()?.video?.id ?: return
        val key = storageKey(videoId)
        val mark = store.skipMark(key) ?: ThirdSkipMark(0, 0)
        tools.choose("跳过片头片尾 · 仅当前影片", listOf(
            "片头  ${if(mark.intro > 0) "${mark.intro} 秒" else "关闭"}",
            "片尾  ${if(mark.outro > 0) "${mark.outro} 秒" else "关闭"}",
            "关闭全部"
        ), anchor, { choice ->
            when(choice) {
                0 -> pickSeconds(anchor, "跳过片头") { seconds ->
                    store.setSkipMark(key, ThirdSkipMark(seconds, mark.outro))
                    if(seconds > 0) applyIntro(seconds)
                }
                1 -> pickSeconds(anchor, "跳过片尾") { seconds ->
                    store.setSkipMark(key, ThirdSkipMark(mark.intro, seconds))
                    resetOutro()
                    Toast.makeText(activity, if(seconds > 0) "片尾将跳过最后 $seconds 秒" else "已关闭片尾跳过", Toast.LENGTH_SHORT).show()
                }
                else -> {
                    store.setSkipMark(key, null)
                    resetOutro()
                    Toast.makeText(activity, "已关闭片头片尾跳过", Toast.LENGTH_SHORT).show()
                }
            }
        })
    }

    private fun pickSeconds(anchor: View, title: String, onPick: (Int) -> Unit) {
        val options = listOf(0, 30, 60, 90, 120)
        tools.choose("$title · 仅当前影片", options.map { if(it == 0) "关闭" else "$it 秒" }, anchor, { index ->
            onPick(options.getOrElse(index) { 0 })
        })
    }

    /** 设置片头后立刻生效：当前播放位置还在片头范围内时直接跳到新位置。 */
    private fun applyIntro(seconds: Int) {
        val instance = currentPlayer() ?: return
        val target = ThirdPlaybackSkipRules.introTargetMs(seconds)
        if(ThirdPlaybackSkipRules.shouldSkipIntro(instance.currentPosition, seconds)) instance.seekTo(target)
        Toast.makeText(activity, "已跳过片头 $seconds 秒", Toast.LENGTH_SHORT).show()
    }

    /** 每秒检查一次片尾：到达片尾窗口且正在播放时自动跳下一集，只触发一次。 */
    fun checkOutro() {
        val instance = currentPlayer() ?: return
        val data = currentDetail() ?: return
        val mark = store.skipMark(storageKey(data.video.id)) ?: return
        if(!ThirdPlaybackSkipRules.shouldSkipOutro(
                isPlaying = instance.isPlaying,
                durationMs = instance.duration,
                currentPositionMs = instance.currentPosition,
                seconds = mark.outro,
                alreadyTriggered = outroTriggered
            )) return
        outroTriggered = true
        val episodes = data.groups.getOrNull(currentGroup())?.second ?: return
        if(currentEpisode() + 1 < episodes.size) {
            Toast.makeText(activity, "已跳过片尾，播放下一集", Toast.LENGTH_SHORT).show()
            jumpEpisode(1)
        } else showOverlay()
    }
}
