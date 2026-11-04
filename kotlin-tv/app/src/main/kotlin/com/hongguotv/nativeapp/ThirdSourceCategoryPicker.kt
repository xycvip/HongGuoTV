// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Rect
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** 分类网格弹窗及遥控器焦点导航。 */
internal class ThirdSourceCategoryPicker(private val activity: Activity, private val ui: ThirdSourceUi) {
    fun show(categories: List<ThirdCategory>, currentId: String?, onPick: (String?) -> Unit) {
        if (categories.isEmpty()) {
            Toast.makeText(activity, "分类还在加载，稍后再试", Toast.LENGTH_SHORT).show()
            return
        }
        val options = listOf(ThirdCategory("", "全部")) + categories
        val columns = 5
        val buttons = mutableListOf<android.widget.TextView>()
        val grid = ui.column()
        lateinit var dialog: AlertDialog
        options.chunked(columns).forEach { chunk ->
            val row = ui.row()
            chunk.forEach { option ->
                val id = option.id.ifEmpty { null }
                val chip = ui.text(option.name, 15f, if (id == currentId) TvStyle.accent else TvStyle.text).apply {
                    gravity = Gravity.CENTER
                    setPadding(ui.dp(10), ui.dp(9), ui.dp(10), ui.dp(9))
                    minHeight = ui.dp(40)
                    ui.focusStyle(this, selected = id == currentId)
                    setOnClickListener { dialog.dismiss(); onPick(id) }
                }
                buttons += chip
                row.addView(chip, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = ui.dp(8) })
            }
            grid.addView(row)
        }
        buttons.forEachIndexed { index, button ->
            button.nextFocusLeftId = buttons[if (index % columns == 0) index else index - 1].id
            button.nextFocusRightId = buttons[if (index % columns == columns - 1 || index == buttons.lastIndex) index else index + 1].id
            button.nextFocusUpId = if (index < columns) button.id else buttons[index - columns].id
            button.nextFocusDownId = if (index + columns <= buttons.lastIndex) buttons[index + columns].id else button.id
        }
        val content = ui.column()
        content.addView(TvDialog.title(activity, "选择分类"))
        val scroll = ScrollView(activity).apply { addView(grid) }
        content.addView(scroll, ui.lp(-1, 0).apply { weight = 1f })
        val cancel = TvDialog.capsuleButton(activity, "取消") { dialog.dismiss() }
        content.addView(ui.row().apply {
            gravity = Gravity.END
            setPadding(0, ui.dp(14), 0, 0)
            addView(cancel, ui.lp(-2, -2))
        })
        dialog = AlertDialog.Builder(activity).setView(TvDialog.card(activity, content)).create()
        buttons.forEach { if (it.nextFocusDownId == it.id) it.nextFocusDownId = cancel.id }
        TvDialog.styleWindow(activity, dialog, widthFraction = 0.68f, maxWidthDp = 900) {
            val currentIndex = options.indexOfFirst { it.id.ifEmpty { null } == currentId }.coerceAtLeast(0)
            scroll.post {
                buttons.getOrNull(currentIndex)?.let { button ->
                    button.requestFocus()
                    scroll.requestChildRectangleOnScreen(button, Rect(0, 0, button.width, button.height), false)
                }
            }
        }
        dialog.show()
    }
}
