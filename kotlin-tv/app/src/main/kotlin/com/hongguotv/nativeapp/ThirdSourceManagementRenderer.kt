// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.app.Activity
import android.graphics.Typeface
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** 第三方片源管理列表 UI：只负责布局、焦点和点击分发，不持有存储或业务状态。 */
internal class ThirdSourceManagementRenderer(
    private val activity: Activity,
    private val ui: ThirdSourceUi,
    private val muted: Int,
    private val disclaimer: String,
    private val maxSources: Int,
    private val capsuleDots: MutableMap<String, View>,
    private val statusViews: MutableMap<String, TextView>,
    private val checkViews: MutableMap<String, TextView>,
    private val onBatchButtons: (TextView?, TextView?) -> Unit
) {
    fun render(
        container: LinearLayout,
        sources: List<ThirdSource>,
        currentId: String?,
        batchMode: Boolean,
        selected: Set<String>,
        dotColor: (String) -> Int,
        probeLabel: (String) -> Pair<String, Int>,
        onBack: () -> Unit,
        onToggleBatch: () -> Unit,
        onAdd: () -> Unit,
        onImport: () -> Unit,
        onTransfer: () -> Unit,
        onSpeedTest: () -> Unit,
        onToggleSelection: (String) -> Unit,
        onToggleAllSelection: () -> Unit,
        onSwitch: (ThirdSource) -> Unit,
        onDelete: (ThirdSource) -> Unit,
        onBatchDelete: () -> Unit
    ): Int {
        val body = ui.column().apply { setPadding(0, ui.dp(2), 0, 0) }
        val head = ui.row()
        ui.addButton(head, "‹ 返回") { onBack() }
        head.addView(ui.text("源管理", 20f).apply {
            setTypeface(null, Typeface.BOLD)
            setPadding(ui.dp(8), 0, 0, 0)
            gravity = Gravity.CENTER_VERTICAL
        }, ui.lp(0, ui.dp(46)).apply { weight = 1f })
        ui.addButton(head, if(batchMode) "退出批量" else "批量删除") { onToggleBatch() }
        body.addView(head, ui.lp(-1, -2).apply { bottomMargin = ui.dp(8) })

        val actions = ui.row()
        val add = ui.addButton(actions, "＋ 添加源") { onAdd() }
        val importTvbox = ui.addButton(actions, "＋ 导入配置") { onImport() }
        val transfer = ui.addButton(actions, "导入/导出配置（扫码）") { onTransfer() }
        val speed = ui.addButton(actions, "测速优选") { onSpeedTest() }
        body.addView(actions, ui.lp(-1, -2).apply { bottomMargin = ui.dp(6) })

        capsuleDots.clear()
        statusViews.clear()
        checkViews.clear()
        onBatchButtons(null, null)
        val listScroll = ScrollView(activity).apply { isVerticalScrollBarEnabled = false }
        val list = ui.column()
        listScroll.addView(list, FrameLayout.LayoutParams(-1, -2))
        val rows = mutableListOf<View>()
        sources.forEach { source ->
            val isCurrent = source.id == currentId
            val isSelected = source.id in selected
            val line = ui.row().apply {
                setPadding(ui.dp(10), ui.dp(8), ui.dp(10), ui.dp(8))
                background = ui.rounded(if(isSelected) TvStyle.selectedSurface else TvStyle.surface)
            }
            if(batchMode) {
                val check = ui.text(if(isSelected) "✓" else "○", 16f, if(isSelected) TvStyle.accent else muted)
                checkViews[source.id] = check
                line.addView(check, ui.lp(-2, -2).apply { rightMargin = ui.dp(10) })
            }
            val dot = View(activity).apply {
                background = ui.rounded(dotColor(source.id), radius = 3)
                layoutParams = LinearLayout.LayoutParams(ui.dp(7), ui.dp(7)).apply { rightMargin = ui.dp(8) }
            }
            capsuleDots[source.id] = dot
            line.addView(dot)
            val nameColumn = ui.column()
            nameColumn.addView(ui.text(source.name, 15f, if(isCurrent) TvStyle.accent else TvStyle.text))
            nameColumn.addView(ui.text(ui.hostOf(source.api), 12f, muted))
            line.addView(nameColumn, ui.lp(0, -2).apply { weight = 1f })
            val (label, color) = probeLabel(source.id)
            val status = ui.text(label, 12f, color)
            statusViews[source.id] = status
            line.addView(status, ui.lp(-2, -2).apply { rightMargin = ui.dp(8) })
            if(isCurrent) line.addView(ui.text("使用中", 12f, TvStyle.accent))
            ui.focusStyle(line, selected = isCurrent || isSelected)
            line.setOnClickListener {
                if(batchMode) onToggleSelection(source.id) else onSwitch(source)
            }
            line.setOnKeyListener { _, key, event ->
                if(!batchMode && key == KeyEvent.KEYCODE_DPAD_LEFT && event.action == KeyEvent.ACTION_UP) {
                    onDelete(source)
                    true
                } else false
            }
            list.addView(line, ui.lp(-1, -2).apply { bottomMargin = ui.dp(6) })
            rows += line
        }
        if(sources.isEmpty()) ui.message(list, "暂无片源信息，请添加源或导入配置")
        body.addView(listScroll, ui.lp(-1, 0).apply { weight = 1f })

        var selectAllRef: TextView? = null
        var removeRef: TextView? = null
        if(batchMode) {
            val bar = ui.row().apply { setPadding(0, ui.dp(6), 0, 0) }
            val selectAllButton = ui.addButton(
                bar,
                if(ThirdSourceBatchSelection.allSelected(selected, sources.map { it.id })) "取消全选" else "全选",
            ) { onToggleAllSelection() }
            selectAllRef = selectAllButton
            val removeButton = ui.addButton(bar, "删除所选（${selected.size}）") { onBatchDelete() }
            removeRef = removeButton
            removeButton.isEnabled = selected.isNotEmpty()
            onBatchButtons(removeButton, selectAllButton)
            body.addView(bar)
            body.addView(ui.text("点击条目勾选 · 「全选」一键勾选全部 · 删除前二次确认", 12f, muted).apply {
                setPadding(0, ui.dp(4), 0, 0)
            })
        } else {
            body.addView(ui.text("点击源条目切换使用 · 条目上按 ← 删除该源 · 顶部「批量删除」可多选 · 上限 $maxSources 个", 12f, muted).apply {
                setPadding(0, ui.dp(6), 0, 0)
            })
        }
        body.addView(ui.text(disclaimer, 12f, muted).apply { setPadding(0, ui.dp(8), 0, ui.dp(2)) })
        container.addView(body, ui.lp(-1, 0).apply { weight = 1f })

        rows.forEachIndexed { index, view ->
            view.nextFocusUpId = rows.getOrNull(index - 1)?.id ?: add.id
            view.nextFocusDownId = rows.getOrNull(index + 1)?.id ?: View.NO_ID
        }
        if(selectAllRef != null || removeRef != null) {
            rows.lastOrNull()?.let { last -> last.nextFocusDownId = selectAllRef?.id ?: removeRef?.id ?: View.NO_ID }
            selectAllRef?.let { all -> all.nextFocusRightId = removeRef?.id ?: View.NO_ID }
            removeRef?.let { remove ->
                remove.nextFocusLeftId = selectAllRef?.id ?: View.NO_ID
                remove.nextFocusUpId = rows.lastOrNull()?.id ?: View.NO_ID
            }
        }
        add.nextFocusRightId = importTvbox.id
        importTvbox.nextFocusLeftId = add.id
        importTvbox.nextFocusRightId = transfer.id
        transfer.nextFocusLeftId = importTvbox.id
        transfer.nextFocusRightId = speed.id
        speed.nextFocusLeftId = transfer.id
        speed.nextFocusRightId = View.NO_ID
        speed.nextFocusDownId = rows.firstOrNull()?.id ?: View.NO_ID
        transfer.nextFocusDownId = rows.firstOrNull()?.id ?: View.NO_ID
        return rows.firstOrNull()?.id ?: add.id
    }

}
