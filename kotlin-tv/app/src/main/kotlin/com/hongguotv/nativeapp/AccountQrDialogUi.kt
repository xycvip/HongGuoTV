// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Bitmap
import android.widget.ImageView
import android.widget.LinearLayout

/** 传统扫码登录弹窗的纯 UI 构建，与二维码轮询/账户状态分离。 */
internal object AccountQrDialogUi {
    fun create(activity: Activity, bitmap: Bitmap): Pair<AlertDialog, android.view.View> {
        val density = activity.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val metrics = activity.resources.displayMetrics
        // 预留标题、说明、按钮和卡片边距；短屏手机优先缩小二维码，避免弹窗超出屏幕。
        val maxQrWidth = (metrics.widthPixels - dp(88)).coerceAtLeast(dp(56))
        val maxQrHeight = (metrics.heightPixels - dp(172)).coerceAtLeast(dp(56))
        val size = minOf(dp(300), maxQrWidth, maxQrHeight)
        val image =
            android.widget.ImageView(activity).apply {
                setImageBitmap(bitmap)
                scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                adjustViewBounds = true
                setBackgroundColor(android.graphics.Color.WHITE)
            }
        val panel = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER_HORIZONTAL
            addView(TvDialog.title(activity, "红果官方扫码登录"))
            addView(image, android.widget.LinearLayout.LayoutParams(size, size).apply {
                gravity = android.view.Gravity.CENTER_HORIZONTAL
                bottomMargin = (12 * density).toInt()
            })
            addView(TvDialog.body(activity, "用手机红果 App：我的 → 右上角菜单 → 扫一扫\n扫码后请在手机确认登录。"))
        }
        val actions = android.widget.LinearLayout(activity).apply { gravity = android.view.Gravity.END }
        val cancelButton = TvDialog.capsuleButton(activity, "取消") {}
        actions.addView(cancelButton)
        panel.addView(actions)
        val qrDialog = AlertDialog.Builder(activity).setView(TvDialog.card(activity, panel)).create()
        val phoneLayout = activity.resources.configuration.smallestScreenWidthDp < 600
        TvDialog.styleWindow(
            activity,
            qrDialog,
            if (phoneLayout) 0.94f else 0.62f,
            if (phoneLayout) 520 else 620
        ) { cancelButton.requestFocus() }
        return qrDialog to cancelButton
    }
}
