// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.app.Activity
import android.app.AlertDialog
import android.widget.ScrollView

/** TVBox 明文配置导入入口：电视端链接输入、后台解析与候选清单交接。 */
internal class ThirdSourceImportDialog(
    private val activity: Activity,
    private val ui: ThirdSourceUi,
    private val requests: ThirdSourceImportRequestCoordinator,
    private val generation: () -> Int,
    private val onParsed: (List<ThirdTvboxSite>, Int) -> Unit,
) {
    fun show() {
        val form = ui.column().apply { setPadding(ui.dp(8), ui.dp(4), ui.dp(8), ui.dp(8)) }
        val urlEdit = ui.editText("TVBox 配置链接（明文 JSON）")
        val errorText = ui.text("", 13f, TvStyle.accent).apply { setPadding(0, ui.dp(6), 0, 0) }
        form.addView(urlEdit); form.addView(errorText)
        form.addView(ui.text("自动跳过 spider jar（type=0）与加密 app 源（type=3/4）。", 12f, TvStyle.muted).apply { setPadding(0, ui.dp(6), 0, 0) })
        val actions = ui.row().apply { setPadding(0, ui.dp(12), 0, 0) }
        lateinit var dialog: AlertDialog
        val parse = TvDialog.capsuleButton(activity, "解析") { }
        val cancel = TvDialog.capsuleButton(activity, "取消") { dialog.dismiss() }
        actions.addView(parse, ui.lp(-2, -2).apply { rightMargin = ui.dp(10) })
        actions.addView(cancel, ui.lp(-2, -2))
        form.addView(actions)
        val content = ui.column()
        content.addView(TvDialog.title(activity, "一键导入 TVBox 配置"))
        content.addView(ScrollView(activity).apply { addView(form) }, ui.lp(-1, 0).apply { weight = 1f })

        dialog = AlertDialog.Builder(activity).setView(TvDialog.card(activity, content)).create()
        TvDialog.styleWindow(activity, dialog)
        fun runParse(rawUrl: String) {
            val url = rawUrl.trim()
            if(!url.startsWith("http")) { errorText.text = "请填写以 http 开头的配置链接"; return }
            errorText.text = "正在获取配置…"; parse.isFocusable = false; parse.isEnabled = false
            val token = generation()
            requests.request(url, isCurrent = { token == generation() }) { result ->
                parse.isFocusable = true; parse.isEnabled = true
                val (parsed, error) = result
                if(parsed == null) { errorText.text = error ?: "无法获取配置"; return@request }
                if(parsed.candidates.isEmpty()) { errorText.text = "没有可导入的 CMS 源（已跳过 ${parsed.skipped} 个不支持源）"; return@request }
                dialog.dismiss()
                onParsed(parsed.candidates, parsed.skipped)
            }
        }
        parse.setOnClickListener { runParse(urlEdit.text.toString()) }
        dialog.show()
        // 默认聚焦「解析」，不主动弹出软键盘
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        parse.requestFocus()
    }
}
