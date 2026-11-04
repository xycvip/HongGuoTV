// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.view.Gravity
import android.widget.LinearLayout

/** 三方片源观看历史与收藏共用的列表页布局、空态和网格挂载。 */
internal class ThirdSourceSavedListRenderer(
    private val ui: ThirdSourceUi,
    private val muted: Int,
    private val mountGrid: (LinearLayout, (ThirdVideo) -> Unit) -> Unit,
    private val setTopFocusId: (Int) -> Unit,
    private val gridId: () -> Int?,
    private val onBack: () -> Unit,
) {
    fun render(
        container: LinearLayout,
        title: String,
        emptyTitle: String,
        emptyDescription: String,
        hint: String,
        videos: List<ThirdVideo>,
        clearLabel: String?,
        onClear: (() -> Unit)?,
        onOpen: (ThirdVideo) -> Unit,
    ) {
        val body = ui.column().apply { setPadding(0, ui.dp(4), 0, 0) }
        val head = ui.row()
        val back = ui.addButton(head, "‹ 返回", onClick = onBack)
        head.addView(ui.text(title, 20f).apply {
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(ui.dp(8), 0, 0, 0)
            gravity = Gravity.CENTER_VERTICAL
        }, ui.lp(0, ui.dp(46)).apply { weight = 1f })
        if(clearLabel != null && onClear != null) ui.addButton(head, clearLabel, onClick = onClear)
        body.addView(head, ui.lp(-1, -2).apply { bottomMargin = ui.dp(6) })
        if(videos.isEmpty()) {
            ui.message(body, emptyTitle)
            body.addView(ui.text(emptyDescription, 12f, muted))
        } else {
            body.addView(ui.text(hint, 12f, muted).apply {
                setPadding(0, 0, 0, ui.dp(4))
            })
        }
        container.addView(body, ui.lp(-1, 0).apply { weight = 1f })
        if(videos.isEmpty()) {
            setTopFocusId(back.id)
            back.requestFocus()
            return
        }
        mountGrid(body, onOpen)
        setTopFocusId(gridId() ?: back.id)
    }
}
