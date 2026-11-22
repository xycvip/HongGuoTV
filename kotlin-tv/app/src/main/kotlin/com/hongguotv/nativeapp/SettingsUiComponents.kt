// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.graphics.Color
import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 设置页左侧分类与右侧说明的联动。
 *
 * 改造前左侧是一列大卡片，焦点落到右侧某一项时，说明文字塞进左侧卡片里，
 * 于是分类卡的高度随说明长度变化，整列跟着上下跳。现在说明回到它所属分组的标题下，
 * 左侧只负责"我在哪一类"，位置稳定。
 *
 * 视觉分层（三种状态互不混淆）：
 *  - 焦点：卡片提亮 + 白环描边，遥控器上永远看得见自己停在哪；
 *  - 当前分类：浅强调色底 + 徽章反色，表示"右侧正在编辑的是它"；
 *  - 其余：安静的资源色卡片，编号徽章只作引导。
 */
internal class SettingsCategoryController(
    private val activity: MainActivity,
    private val categoryHeadingCards: List<LinearLayout>,
    private val categoryIndicators: List<TextView>,
    private val sectionNotes: List<TextView>,
    private val defaultNotes: List<String>,
) {
    var focusedCategory: Int = -1
        private set
    private var headingFocusIndex: Int = -1

    fun markFocusedCategory(category: Int) {
        if (category !in categoryHeadingCards.indices) return
        focusedCategory = category
        repaint()
    }

    /** 左侧分类标题获得/失去遥控器焦点时整体重画，焦点与"当前分类"分层表达。 */
    fun paintHeadingFocus(index: Int, focused: Boolean) {
        headingFocusIndex = if (focused) index else -1
        repaint()
    }

    private fun repaint() {
        categoryHeadingCards.forEachIndexed { index, card ->
            val active = index == focusedCategory
            val headingFocused = index == headingFocusIndex
            card.background = when {
                headingFocused -> TvStyle.shape(
                    activity,
                    TvStyle.colors.surfaceRaised,
                    TvStyle.colors.focusRing,
                    TvMetric.RADIUS_M,
                )
                active -> TvStyle.shape(
                    activity,
                    withAlpha(TvStyle.colors.accent, 0.12f),
                    Color.TRANSPARENT,
                    TvMetric.RADIUS_M,
                )
                else -> TvStyle.shape(
                    activity,
                    TvStyle.colors.surface,
                    TvStyle.colors.strokeSubtle,
                    TvMetric.RADIUS_M,
                )
            }
            card.findViewWithTag<TextView>("settings:cat-chip")?.apply {
                background = if (headingFocused || active) {
                    TvStyle.shape(activity, TvStyle.colors.accent, Color.TRANSPARENT, TvMetric.RADIUS_S)
                } else {
                    TvStyle.shape(activity, TvStyle.colors.surfaceRaised, Color.TRANSPARENT, TvMetric.RADIUS_S)
                }
                setTextColor(if (headingFocused || active) TvStyle.colors.onAccent else TvStyle.colors.textTertiary)
            }
            card.findViewWithTag<TextView>("settings:cat-title")?.apply {
                setTextColor(if (headingFocused || active) TvStyle.colors.textPrimary else TvStyle.colors.textSecondary)
                typeface = Typeface.create(
                    if (active) "sans-serif-medium" else "sans-serif",
                    Typeface.NORMAL,
                )
            }
        }
    }

    /** 把某个设置项的说明写到它所属分组标题下；其余分组恢复成默认副标题。 */
    fun showSettingDescription(category: Int, label: String) {
        if (category !in sectionNotes.indices) return
        val key = label.substringBefore("·").trim()
        // 播放线路选项标签为"线路首选 · Native/Phoenix"，文案表登记名为"播放线路"。
        val descriptionKey = if (key == "线路首选") "播放线路" else key
        val description = SETTINGS_DESCRIPTIONS[descriptionKey] ?: return
        sectionNotes.indices.filter { it != category }.forEach { restoreCategoryDescription(it) }
        sectionNotes[category].text = description.second
    }

    fun restoreCategoryDescription(category: Int) {
        if (category !in sectionNotes.indices) return
        sectionNotes[category].text = defaultNotes.getOrNull(category).orEmpty()
    }
}

/**
 * 设置项工厂。一行就是 [SettingsRow]：左标题、右当前值、右端箭头，永远单列。
 *
 * 改造前是 2/3/4 列自适应网格，每个格子是居中文字按钮。1080p 电视上一行四个，
 * 单格约 176dp，「默认清晰度 · 自动（1080P 优先）」只能显示到「自动（…」。
 * 电视是远距离观看，设置页应该横向铺开、一行一件事，而不是把手机上的网格搬过来。
 */
internal class SettingsItemFactory(
    private val activity: MainActivity,
    private val settingRows: MutableList<SettingsRow>,
    private val categoryItems: MutableList<MutableList<SettingsRow>>,
    private val categoryController: SettingsCategoryController,
    private val rowHeight: Int,
    private val dp: (Int) -> Int,
) {
    fun setting(group: LinearLayout, category: Int, label: String, action: () -> Unit): SettingsRow {
        val view = SettingsRow(activity).apply {
            id = View.generateViewId()
            text = label
            minimumHeight = rowHeight
            setOnClickListener { action() }
            onFocusChanged = { focused ->
                if (focused) {
                    categoryController.markFocusedCategory(category)
                    categoryController.showSettingDescription(category, label)
                } else {
                    // 焦点在组内移动时会先 after 再 before，等一帧再判断是否已离开整组。
                    post {
                        if (settingRows.none { it.hasFocus() }) {
                            categoryController.restoreCategoryDescription(categoryController.focusedCategory)
                        }
                    }
                }
            }
        }
        group.addView(
            view,
            LinearLayout.LayoutParams(-1, rowHeight).apply {
                val gap = dp(2)
                setMargins(gap, gap, gap, gap)
            },
        )
        settingRows += view
        categoryItems[category] += view
        return view
    }
}

/**
 * 设置分组：左侧一个分类入口，右侧「标题 + 说明 + 若干行」。
 *
 * 左侧入口是编号徽章卡片：两位编号 + 类别名，整列像一册目录，
 * 不再是裸文字列表。卡片等分整列高度，间距由高度预算控制。
 */
internal class SettingsSectionFactory(
    private val activity: MainActivity,
    private val categoryIndicators: MutableList<TextView>,
    private val categoryHeadingCards: MutableList<LinearLayout>,
    private val sectionNotes: MutableList<TextView>,
    private val defaultNotes: MutableList<String>,
    private val categoryItems: MutableList<MutableList<SettingsRow>>,
    private val categoryCard: LinearLayout,
    private val sectionsHost: LinearLayout,
    private val dp: (Int) -> Int,
    private val createText: (String, Float, Int) -> TextView,
) {
    fun section(title: String, subtitle: String): Pair<LinearLayout, LinearLayout> {
        val index = categoryHeadingCards.size
        // ── 左：编号徽章卡片。
        val heading = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            setPadding(dp(11), dp(6), dp(9), dp(6))
            isFocusable = false
        }
        val chip = TextView(activity).apply {
            tag = "settings:cat-chip"
            text = String.format("%02d", index + 1)
            textSize = TvMetric.TEXT_CAPTION
            gravity = Gravity.CENTER
            includeFontPadding = false
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            background = TvStyle.shape(activity, TvStyle.colors.surfaceRaised, Color.TRANSPARENT, TvMetric.RADIUS_S)
            setTextColor(TvStyle.colors.textTertiary)
        }
        heading.addView(chip, LinearLayout.LayoutParams(dp(30), dp(30)).apply { rightMargin = dp(12) })

        val headingTitle = createText(title, TvMetric.TEXT_LABEL, TvStyle.colors.textSecondary).apply {
            tag = "settings:cat-title"
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            textAlignment = View.TEXT_ALIGNMENT_VIEW_START
            includeFontPadding = false
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            isFocusable = true
            isFocusableInTouchMode = true
            setPadding(0, dp(11), dp(4), dp(11))
        }
        categoryIndicators += headingTitle
        headingTitle.setOnFocusChangeListener { _, focused ->
            val category = categoryIndicators.indexOf(headingTitle)
            categoryControllerRef?.paintHeadingFocus(category, focused)
        }
        headingTitle.setOnClickListener {
            val category = categoryIndicators.indexOf(headingTitle)
            categoryItems.getOrNull(category)?.firstOrNull { it.visibility == View.VISIBLE }?.requestFocus()
        }
        heading.addView(headingTitle, LinearLayout.LayoutParams(0, -2, 1f))
        categoryHeadingCards += heading
        // 等分剩余高度：调试分类可隐藏，隐藏后其余分类自动重新均分。
        categoryCard.addView(heading, LinearLayout.LayoutParams(-1, 0, 1f).apply { rightMargin = dp(6) })

        // ── 右：分组标题 + 说明 + 行列表
        val panel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            setPadding(0, 0, 0, dp(14))
        }
        panel.addView(
            createText(title, TvMetric.TEXT_SUBTITLE, TvStyle.colors.textPrimary).apply {
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                includeFontPadding = false
                setPadding(dp(3), dp(16), 0, dp(3))
            },
            LinearLayout.LayoutParams(-1, -2),
        )
        val note = createText(subtitle, TvMetric.TEXT_CAPTION, TvStyle.colors.textTertiary).apply {
            includeFontPadding = false
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(3), 0, 0, dp(10))
        }
        panel.addView(note, LinearLayout.LayoutParams(-1, -2))
        sectionNotes += note
        defaultNotes += subtitle

        val grid = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
        }
        panel.addView(grid, LinearLayout.LayoutParams(-1, -2))
        sectionsHost.addView(panel, LinearLayout.LayoutParams(-1, -2))
        categoryItems.add(mutableListOf())
        return panel to grid
    }

    /** 由外部注入，避免 section() 用不上 controller 时还要多传一个参数。 */
    var categoryControllerRef: SettingsCategoryController? = null
}
