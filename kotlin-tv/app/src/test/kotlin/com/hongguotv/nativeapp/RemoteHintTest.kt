package com.hongguotv.nativeapp

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 播放器右下角的按键说明必须完全由设置推导。
 * 之前是一句写死的「左右快进 · 确定暂停 · 下键选集」，用户改了按键映射后这里不跟着变。
 */
class RemoteHintTest {

    @Test
    fun hintFollowsCustomBindings() {
        val lines = PlaybackExtras.remoteHintLines(
            up = PlaybackExtras.REMOTE_PREVIOUS,
            down = PlaybackExtras.REMOTE_EPISODES,
            confirm = PlaybackExtras.REMOTE_PLAY_PAUSE,
            menu = PlaybackExtras.REMOTE_MENU,
            seekSeconds = 15,
        )
        assertEquals(
            listOf(
                "左右 ±15s · 上 上一集 · 下 打开选集",
                "确定 播放 / 暂停 · 菜单 打开播放设置",
            ),
            lines,
        )
    }

    @Test
    fun unboundKeysAreHidden() {
        val lines = PlaybackExtras.remoteHintLines(
            up = PlaybackExtras.REMOTE_NONE,
            down = PlaybackExtras.REMOTE_NEXT,
            confirm = PlaybackExtras.REMOTE_NONE,
            menu = PlaybackExtras.REMOTE_NONE,
            seekSeconds = 10,
        )
        assertEquals(listOf("左右 ±10s · 下 下一集"), lines)
    }

    @Test
    fun labelFallsBackForUnknownAction() {
        assertEquals("无操作", PlaybackExtras.actionLabel("not_an_action"))
    }
}
