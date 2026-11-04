// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** TVBox 候选片源选择弹窗：管理手动勾选、自动探活勾选与列表焦点。 */
internal class ThirdSourceImportChecklist(
    private val activity: Activity,
    private val ui: ThirdSourceUi,
    private val muted: Int,
    private val probeRequests: ThirdSourceProbeRequestCoordinator,
    private val currentGeneration: () -> Int,
    private val existingApis: Set<String>,
    private val onImport: (List<ThirdTvboxSite>) -> Unit
) {
    fun show(rawCandidates: List<ThirdTvboxSite>, skipped: Int) {
        val candidates = rawCandidates.distinctBy { it.api }
        val existing = BooleanArray(candidates.size) { candidates[it].api in existingApis }
        val checked = BooleanArray(candidates.size)
        val touched = BooleanArray(candidates.size)
        val checkViews = arrayOfNulls<TextView>(candidates.size)
        val statusViews = arrayOfNulls<TextView>(candidates.size)
        lateinit var dialog: AlertDialog
        val listContent = ui.column().apply { setPadding(ui.dp(8), ui.dp(4), ui.dp(8), ui.dp(8)) }

        fun paintCheck(index: Int) {
            val view = checkViews[index] ?: return
            view.text = if(checked[index]) "✓" else "○"
            view.setTextColor(if(checked[index]) TvStyle.accent else muted)
        }

        var refreshAction: () -> Unit = {}
        val rows = candidates.mapIndexed { index, site ->
            val line = ui.row().apply { setPadding(ui.dp(10), ui.dp(8), ui.dp(10), ui.dp(8)) }
            val check = ui.text("○", 16f, muted)
            checkViews[index] = check
            val nameColumn = ui.column()
            nameColumn.addView(ui.text(site.name, 15f))
            nameColumn.addView(ui.text(ui.hostOf(site.api), 12f, muted))
            val status = ui.text(if(existing[index]) "已存在" else "探活中…", 12f, muted)
            line.addView(check, ui.lp(-2, -2).apply { rightMargin = ui.dp(8) })
            line.addView(nameColumn, ui.lp(0, -2).apply { weight = 1f })
            line.addView(status)
            ui.focusStyle(line)
            line.setOnClickListener {
                if(existing[index]) return@setOnClickListener
                touched[index] = true
                checked[index] = !checked[index]
                paintCheck(index)
                refreshAction()
            }
            listContent.addView(line, ui.lp(-1, -2).apply { bottomMargin = ui.dp(6) })
            statusViews[index] = status
            line
        }

        listContent.addView(
            ui.text("已自动跳过 $skipped 个不支持源 · 仅默认勾选可连接的源，已存在的源不会重复导入", 12f, muted)
                .apply { setPadding(0, ui.dp(6), 0, 0) }
        )
        val content = ui.column()
        content.addView(TvDialog.title(activity, "选择要导入的源（${candidates.size}）"))
        val scroll = ScrollView(activity).apply { addView(listContent) }
        TvDialog.capHeight(activity, scroll)
        content.addView(scroll, ui.lp(-1, 0).apply { weight = 1f })
        val actions = ui.row().apply { gravity = Gravity.END; setPadding(0, ui.dp(14), 0, 0) }
        val cancel = TvDialog.capsuleButton(activity, "取消") { dialog.dismiss() }
        val importButton = TvDialog.capsuleButton(activity, "导入所选") {
            val selected = candidates.filterIndexed { index, _ -> checked[index] }
            dialog.dismiss()
            onImport(selected)
        }
        importButton.id = View.generateViewId()
        actions.addView(cancel, ui.lp(-2, -2).apply { rightMargin = ui.dp(10) })
        actions.addView(importButton, ui.lp(-2, -2))
        content.addView(actions)
        dialog = AlertDialog.Builder(activity).setView(TvDialog.card(activity, content)).create()
        TvDialog.styleWindow(activity, dialog)
        rows.lastOrNull()?.let { last ->
            last.nextFocusDownId = importButton.id
            importButton.nextFocusUpId = last.id
            cancel.nextFocusUpId = last.id
        }

        fun refreshActionImpl() {
            val count = checked.count { it }
            importButton.text = "导入所选（$count）"
            importButton.isEnabled = count > 0
        }
        refreshAction = { refreshActionImpl() }
        dialog.show()
        refreshAction()
        rows.firstOrNull()?.requestFocus()

        candidates.forEachIndexed { index, site ->
            if(existing[index]) return@forEachIndexed
            val token = currentGeneration()
            val source = ThirdSource(
                "import-" + site.api.hashCode().toUInt().toString(),
                site.name,
                site.api
            )
            probeRequests.request(source, isCurrent = { token == currentGeneration() }) { probe ->
                statusViews[index]?.let { view ->
                    view.text = if(probe.ok) "可连接 · ${ui.formatCount(probe.total)}" else (probe.reason ?: "不可用")
                    view.setTextColor(if(probe.ok) Color.rgb(76, 175, 80) else TvStyle.muted)
                }
                if(!touched[index]) {
                    checked[index] = probe.ok
                    paintCheck(index)
                    refreshAction()
                }
            }
        }
    }
}
