// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp.ui

import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.hongguotv.nativeapp.ui.data.AppViewModel
import com.hongguotv.nativeapp.ui.player.PlaybackDiagnostics
import com.hongguotv.nativeapp.ui.theme.HongguoTheme

/**
 * 全应用唯一的 Activity。所有页面由 Compose 承载，
 * 播放器、详情、搜索之间共享同一个 ViewModel，切换页面不再重建整棵视图树。
 */
class ComposeActivity : ComponentActivity() {

    private val viewModel: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PlaybackDiagnostics.initialize(applicationContext)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        applyFullscreen()
        setContent {
            HongguoTheme {
                HongguoApp(viewModel)
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyFullscreen()
    }

    private fun applyFullscreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        // 刘海屏适配：允许画面延伸到屏幕短边（横屏时即左右两侧）的挖孔/刘海区域，
        // 消除黑边。SHORT_EDGES 只铺满短边、不碰长边状态栏，最适合横屏全屏播放。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
    }
}
