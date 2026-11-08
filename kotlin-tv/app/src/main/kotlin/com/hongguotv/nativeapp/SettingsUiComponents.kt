// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView

internal class SettingsCategoryController(
    private val activity: MainActivity,
    private val categoryHeadingCards: List<LinearLayout>,
    private val categoryIndicators: List<TextView>,
    private val categoryHeadingSubtitles: List<TextView>,
    private val categoryHeadingTitles: List<TextView>,
    private val categoryScale: Float,
    private val categoryTitleSize: Float,
    private val categoryTitleHeight: Int,
    private val categorySubtitleSize: Float,
    private val muted: Int,
    private val white: Int,
    private val dp: (Int) -> Int,
    private val rounded: (Int) -> Drawable,
) {
    var focusedCategory: Int = -1
        private set

    fun markFocusedCategory(category: Int) {
        if(category !in categoryIndicators.indices) return
        focusedCategory=category
        categoryHeadingCards.forEachIndexed { index,card ->
            val focused=categoryIndicators.getOrNull(index)?.hasFocus()==true
            val active=index==focusedCategory
            card.background=when {
                focused -> TvStyle.card(activity,true)
                active -> TvStyle.shape(activity,TvStyle.selectedSurface)
                else -> TvStyle.card(activity,false)
            }
        }
        categoryIndicators.forEach { indicator ->
            val focused=indicator.hasFocus()
            indicator.background=rounded(Color.TRANSPARENT)
            indicator.setTextColor(if(focused) white else muted)
            indicator.setTypeface(null,if(focused) Typeface.BOLD else Typeface.NORMAL)
        }
    }

    private fun setCategoryDetailMode(category: Int, enabled: Boolean) {
        if(category !in categoryHeadingSubtitles.indices || category !in categoryHeadingTitles.indices || category !in categoryHeadingCards.indices) return
        val title=categoryHeadingTitles[category]
        val subtitle=categoryHeadingSubtitles[category]
        val card=categoryHeadingCards[category]
        card.gravity=android.view.Gravity.CENTER_VERTICAL
        card.setPadding(dp(if(enabled) 7 else 8),dp(if(enabled) 7 else 9),dp(8),dp(if(enabled) 7 else 9))
        title.visibility=if(enabled) View.GONE else View.VISIBLE
        subtitle.visibility=if(enabled) View.VISIBLE else View.GONE
        title.textSize=categoryTitleSize
        title.gravity=android.view.Gravity.CENTER
        title.textAlignment=View.TEXT_ALIGNMENT_CENTER
        val titleParams=title.layoutParams as? LinearLayout.LayoutParams
        titleParams?.height=dp(categoryTitleHeight)
        if(titleParams!=null) title.layoutParams=titleParams
        subtitle.textSize=if(enabled) (17f*categoryScale).coerceIn(15f,19f) else categorySubtitleSize
        subtitle.gravity=android.view.Gravity.CENTER_VERTICAL or android.view.Gravity.START
        subtitle.maxLines=Int.MAX_VALUE
        subtitle.ellipsize=null
        subtitle.setPadding(dp(5),dp(if(enabled) 4 else 3),dp(2),dp(if(enabled) 4 else 3))
        val subtitleParams=subtitle.layoutParams as? LinearLayout.LayoutParams
        if(subtitleParams!=null) {
            subtitleParams.height=0
            subtitleParams.weight=1f
            subtitle.layoutParams=subtitleParams
        }
        subtitle.setTextColor(if(enabled) Color.argb(235,255,255,255) else muted)
        card.requestLayout()
    }

    fun restoreCategoryDescription(category: Int) {
        if(category !in categoryHeadingSubtitles.indices) return
        categoryHeadingSubtitles[category].text=""
        setCategoryDetailMode(category,false)
    }

    fun showSettingDescription(category: Int, label: String) {
        if(category !in categoryHeadingSubtitles.indices) return
        val key=label.substringBefore("·").trim()
        // 播放线路选项标签为“线路首选 · Native/Phoenix”，文案表登记名为“播放线路”。
        val descriptionKey=if(key=="线路首选") "播放线路" else key
        val description=SETTINGS_DESCRIPTIONS[descriptionKey] ?: return
        categoryHeadingSubtitles.indices.filter { it!=category }.forEach { restoreCategoryDescription(it) }
        categoryHeadingSubtitles[category].text=description.second
        setCategoryDetailMode(category,true)
    }
}

internal class SettingsItemFactory(
    private val activity: MainActivity,
    private val settingRows: MutableList<TextView>,
    private val categoryItems: MutableList<MutableList<TextView>>,
    private val categoryController: SettingsCategoryController,
    private val settingTextSize: Float,
    private val settingItemHeight: Int,
    private val settingHorizontalPadding: Int,
    private val settingVerticalPadding: Int,
    private val categoryScale: Float,
    private val dp: (Int) -> Int,
    private val createButton: (String, () -> Unit) -> TextView,
) {
    fun setting(group: GridLayout, category: Int, label: String, action: () -> Unit): TextView {
        val view=createButton(label,action).apply {
            gravity=Gravity.CENTER
            textSize=settingTextSize
            typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL)
            includeFontPadding=false
            maxLines=1
            ellipsize=TextUtils.TruncateAt.END
            setPadding(dp(settingHorizontalPadding),dp(settingVerticalPadding),dp(settingHorizontalPadding),dp(settingVerticalPadding))
            minHeight=dp(settingItemHeight)
            background=TvStyle.card(activity,false)
            setOnFocusChangeListener { _,focused ->
                background=TvStyle.card(activity,focused)
                if(focused) {
                    categoryController.markFocusedCategory(category)
                    categoryController.showSettingDescription(category,label)
                } else {
                    post {
                        if(settingRows.none { it.hasFocus() }) categoryController.restoreCategoryDescription(categoryController.focusedCategory)
                    }
                }
            }
        }
        val params=GridLayout.LayoutParams(
            GridLayout.spec(GridLayout.UNDEFINED),
            GridLayout.spec(GridLayout.UNDEFINED,1f)
        ).apply {
            width=0; height=dp(settingItemHeight)
            val itemMargin=(5f*categoryScale).toInt().coerceIn(3,7)
            setMargins(dp(itemMargin),dp(itemMargin),dp(itemMargin),dp(itemMargin))
        }
        group.addView(view,params)
        settingRows+=view
        categoryItems[category]+=view
        return view
    }
}

internal class SettingsSectionFactory(
    private val activity: MainActivity,
    private val categoryIndicators: MutableList<TextView>,
    private val categoryHeadingSubtitles: MutableList<TextView>,
    private val categoryHeadingTitles: MutableList<TextView>,
    private val categoryHeadingCards: MutableList<LinearLayout>,
    private val categoryItems: MutableList<MutableList<TextView>>,
    private val categoryController: SettingsCategoryController,
    private val categoryScale: Float,
    private val categoryTitleSize: Float,
    private val categoryTitleHeight: Int,
    private val categorySubtitleSize: Float,
    private val categoryCardExtension: Int,
    private val headingWidth: Int,
    private val columns: Int,
    private val categoryCard: LinearLayout,
    private val sectionsHost: LinearLayout,
    private val white: Int,
    private val muted: Int,
    private val dp: (Int) -> Int,
    private val createText: (String, Float, Int) -> TextView,
    private val createColumn: () -> LinearLayout,
    private val layoutParams: (Int, Int) -> LinearLayout.LayoutParams,
    private val rounded: (Int) -> Drawable
) {
    fun section(title: String, subtitle: String): Pair<LinearLayout, GridLayout> {
        val headingTitle = createText(title, categoryTitleSize, white).apply {
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            textAlignment = View.TEXT_ALIGNMENT_CENTER
            includeFontPadding = false
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(2), 0, dp(2), 0)
            isFocusable = true
            isFocusableInTouchMode = true
        }
        categoryIndicators += headingTitle
        headingTitle.setOnFocusChangeListener { _, _ ->
            val category = categoryIndicators.indexOf(headingTitle)
            if(category == categoryController.focusedCategory || headingTitle.hasFocus()) {
                categoryController.markFocusedCategory(category)
                if(headingTitle.hasFocus()) {
                    categoryHeadingSubtitles.indices.forEach { categoryController.restoreCategoryDescription(it) }
                }
            }
        }
        headingTitle.background = rounded(Color.TRANSPARENT)
        headingTitle.setOnClickListener {
            val category = categoryIndicators.indexOf(headingTitle)
            categoryItems.getOrNull(category)?.firstOrNull()?.requestFocus()
        }

        val headingSubtitle = createText(subtitle, categorySubtitleSize, muted).apply {
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(5), dp(3), dp(2), 0)
        }
        headingSubtitle.visibility = View.GONE
        categoryHeadingSubtitles += headingSubtitle
        categoryHeadingTitles += headingTitle

        val heading = createColumn().apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(9), dp(8), dp(9))
            background = TvStyle.card(activity, false)
            addView(headingTitle, layoutParams(-1, dp(categoryTitleHeight)))
            addView(headingSubtitle)
        }
        categoryHeadingCards += heading
        categoryCard.addView(
            heading,
            layoutParams(dp(headingWidth + categoryCardExtension), -2).apply {
                rightMargin = -dp(categoryCardExtension)
            }
        )

        val panel = createColumn().apply {
            setPadding(dp(8), dp(7), dp(8), dp(7))
        }
        val grid = GridLayout(activity).apply {
            columnCount = columns
            useDefaultMargins = false
        }
        panel.addView(grid, layoutParams(-1, -2))

        sectionsHost.addView(panel, layoutParams(-1, -2))
        fun alignHeadingToSettingCards() {
            if(panel.height <= 0 || grid.childCount <= 0) return
            val firstMargins = grid.getChildAt(0).layoutParams as? ViewGroup.MarginLayoutParams
            val lastMargins = grid.getChildAt(grid.childCount - 1).layoutParams as? ViewGroup.MarginLayoutParams
            val topInset = panel.paddingTop + (firstMargins?.topMargin ?: 0)
            val bottomInset = panel.paddingBottom + (lastMargins?.bottomMargin ?: 0)
            val cardHeight = (panel.height - topInset - bottomInset).coerceAtLeast(dp(1))
            val params = heading.layoutParams as? LinearLayout.LayoutParams ?: return
            if(params.height != cardHeight || params.topMargin != topInset || params.bottomMargin != bottomInset) {
                params.height = cardHeight
                params.topMargin = topInset
                params.bottomMargin = bottomInset
                heading.layoutParams = params
            }
        }
        panel.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> alignHeadingToSettingCards() }
        grid.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> alignHeadingToSettingCards() }
        panel.post { alignHeadingToSettingCards() }
        categoryItems.add(mutableListOf())
        return panel to grid
    }
}
