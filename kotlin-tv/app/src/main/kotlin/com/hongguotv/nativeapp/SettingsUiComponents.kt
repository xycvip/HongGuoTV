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

    fun markFocusedCategory(category: Int) {
        if (category !in categoryHeadingCards.indices) return
        focusedCategory = category
        categoryHeadingCards.forEachIndexed { index, card ->
            val active = index == category
            card.background = when {
                active -> TvStyle.shape(
                    activity,
                    withAlpha(TvStyle.colors.accent, 0.14f),
                    Color.TRANSPARENT,
                    TvMetric.RADIUS_S,
                )
                else -> null
            }
            card.findViewWithTag<View>("settings:cat-bar")
                ?.setBackgroundColor(if (active) TvStyle.colors.accent else Color.TRANSPARENT)
            card.findViewWithTag<TextView>("settings:cat-title")?.apply {
                setTextColor(if (active) TvStyle.colors.textPrimary else TvStyle.colors.textSecondary)
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
        // ── 左：窄导航项。名称 + 选中竖条，不加卡片底，和右侧的宽行形成主次。
        val heading = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            setPadding(0, dp(1), dp(6), dp(1))
            isFocusable = false
        }
        val bar = View(activity).apply {
            tag = "settings:cat-bar"
            setBackgroundColor(Color.TRANSPARENT)
        }
        heading.addView(bar, LinearLayout.LayoutParams(dp(3), dp(16)).apply { rightMargin = dp(11) })

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
            setPadding(0, dp(13), dp(8), dp(13))
        }
        categoryIndicators += headingTitle
        headingTitle.setOnFocusChangeListener { _, focused ->
            val category = categoryIndicators.indexOf(headingTitle)
            if (focused) categoryControllerRef?.markFocusedCategory(category)
            // 左侧窄栏里的焦点也要看得见：加一层更亮的强调底色，和"当前分类"的浅底色区分开。
            headingTitle.background = if (focused) {
                TvStyle.shape(activity, withAlpha(TvStyle.colors.accent, 0.22f), Color.TRANSPARENT, TvMetric.RADIUS_S)
            } else null
        }
        headingTitle.setOnClickListener {
            val category = categoryIndicators.indexOf(headingTitle)
            categoryItems.getOrNull(category)?.firstOrNull { it.visibility == View.VISIBLE }?.requestFocus()
        }
        heading.addView(headingTitle, LinearLayout.LayoutParams(0, -2, 1f))
        categoryHeadingCards += heading
        categoryCard.addView(heading, LinearLayout.LayoutParams(-1, -2))

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
                setPadding(dp(3), dp(20), 0, dp(3))
            },
            LinearLayout.LayoutParams(-1, -2),
        )
        val note = createText(subtitle, TvMetric.TEXT_CAPTION, TvStyle.colors.textTertiary).apply {
            includeFontPadding = false
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(3), 0, 0, dp(7))
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
