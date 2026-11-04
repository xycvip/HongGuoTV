// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.app.Activity
import android.app.AlertDialog
import android.widget.ScrollView
import android.widget.Toast
import com.hongguotv.core.ThirdSourceConfig

/** 添加第三方 CMS 源的电视端表单与探活保存流程。 */
internal class ThirdSourceAddSourceDialog(
    private val activity: Activity,
    private val ui: ThirdSourceUi,
    private val probeRequests: ThirdSourceProbeRequestCoordinator,
    private val store: ThirdStore,
    private val sources: () -> List<ThirdSource>,
    private val probes: MutableMap<String, ThirdSourceProbeState>,
    private val generation: () -> Int,
    private val resetBrowseState: () -> Unit,
    private val backupRequest: () -> Unit,
    private val rebuild: () -> Unit,
) {
    fun show() {
        val form = ui.column().apply { setPadding(ui.dp(8), ui.dp(4), ui.dp(8), ui.dp(8)) }
        val nameEdit = ui.editText("源名称（如：量子资源）")
        val apiEdit = ui.editText("接口地址，如 https://…/api.php/provide/vod")
        val errorText = ui.text("", 13f, TvStyle.accent).apply { setPadding(0, ui.dp(6), 0, 0) }
        form.addView(ui.text("名称", 13f, TvStyle.muted)); form.addView(nameEdit)
        form.addView(ui.text("接口地址", 13f, TvStyle.muted).apply { setPadding(0, ui.dp(8), 0, 0) }); form.addView(apiEdit)
        form.addView(errorText)
        val actions = ui.row().apply { setPadding(0, ui.dp(12), 0, 0) }
        lateinit var dialog: AlertDialog
        val save = TvDialog.capsuleButton(activity, "保存并探活") { }
        val cancel = TvDialog.capsuleButton(activity, "取消") { dialog.dismiss() }
        actions.addView(save, ui.lp(-2, -2).apply { rightMargin = ui.dp(10) })
        actions.addView(cancel, ui.lp(-2, -2))
        form.addView(actions)
        val content = ui.column()
        content.addView(TvDialog.title(activity, "添加片源"))
        content.addView(ScrollView(activity).apply { addView(form) }, ui.lp(-1, 0).apply { weight = 1f })

        dialog = AlertDialog.Builder(activity).setView(TvDialog.card(activity, content)).create()
        TvDialog.styleWindow(activity, dialog)
        save.setOnClickListener {
            val name = nameEdit.text.toString().trim()
            val api = apiEdit.text.toString().trim()
            when {
                api.isEmpty() -> errorText.text = "请填写接口地址"
                !ThirdSourceConfig.validApi(api) -> errorText.text = "请输入完整有效的 HTTP/HTTPS 接口地址"
                else -> {
                    errorText.text = "正在探活…"; save.isFocusable = false; save.isEnabled = false
                    val gen = generation()
                    val source = ThirdSource(
                        "u" + api.hashCode().toUInt().toString(),
                        name.ifEmpty { ui.hostOf(api) },
                        api
                    )
                    probeRequests.request(source, isCurrent = { gen == generation() }) { probe ->
                        if(!probe.ok) {
                            errorText.text = "探活失败：${probe.reason}"
                            save.isFocusable = true; save.isEnabled = true
                            return@request
                        }
                        val currentSources = sources()
                        if(currentSources.size >= ThirdCms.MAX_SOURCES) {
                            errorText.text = "源数量已达上限（${ThirdCms.MAX_SOURCES}）"
                            save.isFocusable = true; save.isEnabled = true
                            return@request
                        }
                        if(currentSources.any { it.api == api }) {
                            errorText.text = "该源已存在"
                            save.isFocusable = true; save.isEnabled = true
                            return@request
                        }
                        store.saveSources(currentSources + source); store.setCurrent(source.id)
                        probes[source.id] = ThirdSourceProbeState(ThirdSourceProbeDot.OK, probe.total)
                        resetBrowseState()
                        dialog.dismiss()
                        Toast.makeText(activity, "已添加「${source.name}」", Toast.LENGTH_SHORT).show()
                        backupRequest(); rebuild()
                    }
                }
            }
        }
        dialog.show()
        // 默认不弹出软键盘，用户可主动选择输入框或使用遥控器/USB键盘
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        save.requestFocus()
    }
}
