// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThirdSourcePlaybackKeyControllerTest {
    private fun handle(
        action: Int,
        key: Int,
        overlay: Boolean = false,
        scrub: Boolean = false,
        seekBar: Boolean = false,
        focus: Boolean = false,
        repeat: Int = 0,
        events: MutableList<String> = mutableListOf(),
    ) = ThirdSourcePlaybackKeyController().handle(
        action, key, repeat, overlay, scrub, seekBar, focus,
        onStartScrub = { events += "start:$it" },
        onEndScrub = { events += "end" },
        onTogglePlay = { events += "toggle" },
        onShowOverlay = { events += "show" },
        onJumpEpisode = { events += "jump:$it" },
        onStop = { events += "stop" },
    )

    @Test
    fun `左右键在控制层隐藏时启动快进快退预览`() {
        val events = mutableListOf<String>()
        assertTrue(handle(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT, events = events))
        assertEquals(listOf("start:1"), events)
    }

    @Test
    fun `快进快退按住时不重复启动松开时结束预览`() {
        val events = mutableListOf<String>()
        assertTrue(handle(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, repeat = 1, events = events))
        assertTrue(events.isEmpty())
        assertTrue(handle(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_LEFT, scrub = true, events = events))
        assertEquals(listOf("end"), events)
    }

    @Test
    fun `控制层按钮焦点保留原生左右导航`() {
        val events = mutableListOf<String>()
        assertFalse(handle(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, overlay = true, focus = true, events = events))
        assertTrue(events.isEmpty())
    }

    @Test
    fun `中心键和媒体键委托播放动作`() {
        val events = mutableListOf<String>()
        assertTrue(handle(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, events = events))
        assertTrue(handle(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_NEXT, events = events))
        assertEquals(listOf("show", "jump:1"), events)
    }
}
