// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.view.KeyEvent

/** 第三方播放器遥控器按键分发；不持有播放器或 View，只通过回调执行动作。 */
internal class ThirdSourcePlaybackKeyController {
    fun handle(
        action: Int,
        keyCode: Int,
        repeatCount: Int,
        overlayVisible: Boolean,
        scrubActive: Boolean,
        seekBarFocused: Boolean,
        focusInOverlay: Boolean,
        onStartScrub: (Int) -> Unit,
        onEndScrub: () -> Unit,
        onTogglePlay: () -> Unit,
        onShowOverlay: () -> Unit,
        onJumpEpisode: (Int) -> Unit,
        onStop: () -> Unit,
    ): Boolean {
        if(keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            val direction = if(keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1
            if(action == KeyEvent.ACTION_UP) {
                if(scrubActive) { onEndScrub(); return true }
                return false
            }
            if(action != KeyEvent.ACTION_DOWN) return false
            if(!overlayVisible || seekBarFocused) {
                if(repeatCount == 0) onStartScrub(direction)
                return true
            }
            if(scrubActive) onEndScrub()
            return false
        }
        if(action != KeyEvent.ACTION_DOWN) return false
        if(scrubActive) onEndScrub()
        if(overlayVisible && focusInOverlay) return false
        return when(keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER ->
                if(overlayVisible) { onTogglePlay(); true } else { onShowOverlay(); true }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_MENU ->
                if(!overlayVisible) { onShowOverlay(); true } else false
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE -> { onTogglePlay(); true }
            KeyEvent.KEYCODE_MEDIA_NEXT -> { onJumpEpisode(1); true }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> { onJumpEpisode(-1); true }
            KeyEvent.KEYCODE_MEDIA_STOP -> { onStop(); true }
            else -> false
        }
    }
}
