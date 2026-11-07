// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.graphics.Typeface
import android.view.View
import android.widget.FrameLayout
import android.widget.ScrollView

/** 河马平台的本机资料页；不展示红果云账户、二维码或云端同步状态。 */
internal fun MainActivity.renderHippoAccountContent(host: FrameLayout) {
    host.removeAllViews()
    val scroll = ScrollView(this).apply { isFillViewport = true }
    val body = column().apply { setPadding(dp(24), dp(18), dp(24), dp(24)) }
    body.addView(text("我的", 25f).apply { setTypeface(null, Typeface.BOLD) }, lp(-1, -2))
    body.addView(text("河马剧场的收藏、观看记录和搜索记录独立保存在本机，不与红果账户同步。", 14f, muted).apply {
        setPadding(0, dp(8), 0, dp(18))
    }, lp(-1, -2))
    val favorites = library.favorites()
    val history = library.history()
    val favoriteButton = addButton(body, "我的收藏 · ${favorites.size}") { switchTab(3) }
    favoriteButton.nextFocusUpId = navForTab().id
    val historyButton = addButton(body, "观看历史 · ${history.size}") { switchTab(4) }
    historyButton.nextFocusUpId = favoriteButton.id
    if (history.isNotEmpty()) {
        val clear = addButton(body, "清空河马观看记录") {
            tvTools.confirm("清空河马观看记录", "只删除河马剧场本机观看进度，不影响红果历史、收藏和账户。", clear) {
                library.clearHistory()
                showCatalog()
            }
        }
        clear.nextFocusUpId = historyButton.id
    }
    scroll.addView(body)
    host.addView(scroll, FrameLayout.LayoutParams(-1, -1))
    favoriteButton.requestFocus()
}
