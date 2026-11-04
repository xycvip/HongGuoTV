// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.app.Activity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

internal data class ThirdSourceSearchPageViews(
    val inputView: TextView,
    val suggestionHost: LinearLayout,
    val keyViews: List<View>,
    val historyViews: List<View>,
    val suggestionDownTargets: List<View>,
    val firstFocusId: Int
)

/** 第三方片源搜索页渲染器：三栏历史/屏幕键盘/联想及遥控器焦点关系。 */
internal class ThirdSourceSearchPageRenderer(
    private val activity: Activity,
    private val ui: ThirdSourceUi,
    private val store: ThirdStore,
    private val tvTools: TvTools,
    private val muted: Int
) {
    fun render(
        container: LinearLayout,
        searchInput: String,
        onBack: () -> Unit,
        onSubmit: (String) -> Unit,
        onTypeChar: (String) -> Unit,
        onDelete: () -> Unit,
        onClear: () -> Unit,
        onSearch: () -> Unit,
        onShowAllHistory: (List<String>) -> Unit,
        onClearHistory: () -> Unit
    ): ThirdSourceSearchPageViews {
        val body = ui.column()
        val head = ui.row()
        ui.addButton(head, "‹ 返回") { onBack() }
        val display = ui.text(
            searchInput.ifEmpty { "输入片名或关键词" },
            19f,
            if(searchInput.isEmpty()) muted else TvStyle.text
        ).apply {
            setPadding(ui.dp(14), 0, ui.dp(14), 0)
            gravity = android.view.Gravity.CENTER_VERTICAL
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.START
        }
        head.addView(display, ui.lp(0, ui.dp(46)).apply { weight = 1f; leftMargin = ui.dp(6) })
        body.addView(head, ui.lp(-1, -2).apply { bottomMargin = ui.dp(6) })

        val columns = ui.row().apply { gravity = android.view.Gravity.TOP }
        val history = store.searches()
        val historyViews = mutableListOf<View>()
        val historyColumn = ui.column()
        historyColumn.addView(sectionTitle("搜索历史"))
        if(history.isEmpty()) {
            historyColumn.addView(ui.text("暂无历史", 13f, muted).apply {
                setPadding(ui.dp(10), ui.dp(6), 0, 0)
            })
        }
        history.take(8).forEach { word ->
            val chip = ui.text(word, 14f).apply {
                setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(8))
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                ui.focusStyle(this)
                setOnClickListener { onSubmit(word) }
            }
            historyViews += chip
            historyColumn.addView(chip, ui.lp(-1, -2).apply { bottomMargin = ui.dp(4) })
        }
        if(history.isNotEmpty()) {
            if(history.size > 8) {
                historyViews += ui.addButton(historyColumn, "全部历史") { onShowAllHistory(history) }
            }
            historyViews += ui.addButton(historyColumn, "清空历史") {
                tvTools.confirm("清空搜索历史", "清除本机保存的三方片源搜索词？", historyColumn) {
                    onClearHistory()
                }
            }
        }
        columns.addView(columnScroll(historyColumn), ui.lp(0, -1).apply { weight = 0.24f })

        val keyViews = mutableListOf<TextView>()
        fun key(label: String, action: () -> Unit): TextView =
            ui.text(label, 15f).apply {
                gravity = android.view.Gravity.CENTER
                minHeight = ui.dp(40)
                ui.focusStyle(this)
                setOnClickListener { action() }
            }.also { keyViews += it }

        val keyboardColumn = ui.column()
        listOf("ABCDEF", "GHIJKL", "MNOPQR", "STUVWX", "YZ0123", "456789").forEach { letters ->
            val line = ui.row().apply { setPadding(0, 0, 0, ui.dp(4)) }
            letters.forEach { character ->
                val letter = character.toString().lowercase()
                line.addView(key(letter) { onTypeChar(letter) }, ui.lp(0, -2).apply {
                    weight = 1f
                    rightMargin = ui.dp(4)
                })
            }
            keyboardColumn.addView(line)
        }
        val lastRow = ui.row().apply { setPadding(0, 0, 0, ui.dp(4)) }
        val delete = key("删除", onDelete)
        val clear = key("清空", onClear)
        val search = key("搜索", onSearch)
        lastRow.addView(delete, ui.lp(0, -2).apply { weight = 2f; rightMargin = ui.dp(4) })
        lastRow.addView(clear, ui.lp(0, -2).apply { weight = 2f; rightMargin = ui.dp(4) })
        lastRow.addView(search, ui.lp(0, -2).apply { weight = 2f })
        keyboardColumn.addView(lastRow)
        columns.addView(columnScroll(keyboardColumn), ui.lp(0, -1).apply {
            weight = 0.5f
            leftMargin = ui.dp(12)
            rightMargin = ui.dp(12)
        })

        val suggestionHost = ui.column()
        val suggestionColumn = ui.column().apply {
            addView(sectionTitle("联想"))
            addView(suggestionHost)
        }
        columns.addView(columnScroll(suggestionColumn), ui.lp(0, -1).apply { weight = 0.26f })
        body.addView(columns, ui.lp(-1, 0).apply { weight = 1f })
        container.addView(body, ui.lp(-1, 0).apply { weight = 1f })

        keyViews.forEachIndexed { index, view ->
            view.nextFocusUpId = keyViews.getOrNull(index - 6)?.id ?: View.NO_ID
            view.nextFocusDownId = keyViews.getOrNull(index + 6)?.id ?: View.NO_ID
            if(index < 36 && index % 6 == 0) {
                view.nextFocusLeftId = historyViews.getOrNull(index / 6)?.id
                    ?: historyViews.lastOrNull()?.id ?: View.NO_ID
            }
        }
        historyViews.forEachIndexed { index, view ->
            view.nextFocusRightId = keyViews.getOrNull(index * 6)?.id ?: keyViews.firstOrNull()?.id ?: View.NO_ID
            view.nextFocusUpId = historyViews.getOrNull(index - 1)?.id ?: View.NO_ID
            view.nextFocusDownId = historyViews.getOrNull(index + 1)?.id ?: View.NO_ID
        }
        return ThirdSourceSearchPageViews(
            display,
            suggestionHost,
            keyViews,
            historyViews,
            listOf(delete, clear, search),
            keyViews.firstOrNull()?.id ?: View.NO_ID
        )
    }

    fun renderSuggestions(
        views: ThirdSourceSearchPageViews,
        suggestions: List<String>,
        searchInput: String,
        onSubmit: (String) -> Unit
    ) {
        val host = views.suggestionHost
        host.removeAllViews()
        val suggestionViews = mutableListOf<View>()
        if(suggestions.isEmpty()) {
            host.addView(
                ui.text(
                    if(searchInput.isBlank()) "输入关键词后显示联想词" else "没有联想词，可直接按「搜索」",
                    13f,
                    muted
                ).apply { setPadding(ui.dp(10), ui.dp(6), 0, ui.dp(8)) }
            )
        } else {
            suggestions.forEach { name ->
                val item = ui.text(name, 15f).apply {
                    setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(8))
                    setSingleLine(true)
                    marqueeRepeatLimit = -1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    ui.focusStyle(this)
                    setOnClickListener { onSubmit(name) }
                    val focusStyle = onFocusChangeListener
                    onFocusChangeListener = View.OnFocusChangeListener { view, hasFocus ->
                        focusStyle?.onFocusChange(view, hasFocus)
                        val text = view as TextView
                        text.ellipsize = if(hasFocus) android.text.TextUtils.TruncateAt.MARQUEE
                        else android.text.TextUtils.TruncateAt.END
                        text.isSelected = hasFocus
                    }
                }
                suggestionViews += item
                host.addView(item, ui.lp(-1, -2).apply { bottomMargin = ui.dp(4) })
            }
        }
        views.keyViews.forEachIndexed { index, keyView ->
            if(index < 36 && index % 6 == 5) {
                keyView.nextFocusRightId = suggestionViews.getOrNull(index / 6)?.id
                    ?: suggestionViews.firstOrNull()?.id ?: View.NO_ID
            }
        }
        suggestionViews.forEachIndexed { index, view ->
            view.nextFocusLeftId = views.keyViews.getOrNull((index % 6) * 6 + 5)?.id ?: View.NO_ID
            view.nextFocusUpId = suggestionViews.getOrNull(index - 1)?.id ?: View.NO_ID
            view.nextFocusDownId = suggestionViews.getOrNull(index + 1)?.id ?: View.NO_ID
        }
        views.suggestionDownTargets.forEach { it.nextFocusDownId = suggestionViews.firstOrNull()?.id ?: View.NO_ID }
    }

    private fun columnScroll(content: LinearLayout): ScrollView =
        ScrollView(activity).apply {
            isVerticalScrollBarEnabled = false
            addView(content, FrameLayout.LayoutParams(-1, -2))
        }

    private fun sectionTitle(label: String) =
        ui.text(label, 13f, muted).apply { setPadding(0, ui.dp(6), 0, ui.dp(4)) }
}
