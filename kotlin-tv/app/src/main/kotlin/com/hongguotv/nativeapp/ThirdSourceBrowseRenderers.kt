// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.app.Activity
import android.content.res.ColorStateList
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView

internal class ThirdSourceBrowseSearchBarRenderer(
    private val activity: Activity,
    private val ui: ThirdSourceUi,
    private val filters: ThirdSourceSearchFilterRenderer,
) {
    data class Result(
        val rows: List<View>,
        val search: TextView,
        val categoryButtons: List<TextView>,
        val filterButtons: List<View>,
        val clearRef: TextView?,
        val searchStatus: TextView?,
    )

    fun render(
        categories: List<ThirdCategory>,
        categoryId: String?,
        searchWord: String?,
        searchDetails: List<ThirdDetail>,
        filterType: String?,
        filterYear: String?,
        filterArea: String?,
        searchSummary: () -> String,
        onPickCategory: () -> Unit,
        onOpenSearch: (Boolean) -> Unit,
        onScopeToggle: () -> Unit,
        onPickFilter: (String, String?) -> Unit,
        onClearFilters: () -> Unit,
        showFilterPicker: (String, List<String?>, String?, (String?) -> Unit) -> Unit,
    ): Result {
        val rows = mutableListOf<View>()
        var categoryButtons: List<TextView> = emptyList()
        var filterButtons: List<View> = emptyList()
        var clearRef: TextView? = null
        var searchStatus: TextView? = null
        val searchRow = ui.row()
        val search: TextView

        if (searchWord == null) {
            val options = listOf(ThirdCategory("", "全部")) + categories
            val currentName = options.firstOrNull { it.id.ifEmpty { null } == categoryId }?.name ?: "全部"
            val categoryChip = ui.text("分类：$currentName ▾", 14f,
                if (categoryId != null) TvStyle.accent else TvStyle.text).apply {
                gravity = android.view.Gravity.CENTER
                setPadding(ui.dp(14), ui.dp(8), ui.dp(14), ui.dp(8))
                ui.focusStyle(this, selected = categoryId != null)
                setOnClickListener { onPickCategory() }
            }
            categoryButtons = listOf(categoryChip)
            searchRow.addView(categoryChip, ui.lp(-2, -2))
            searchRow.addView(View(activity), ui.lp(0, ui.dp(1)).apply { weight = 1f })
            search = ui.text("搜索", 14f, TvStyle.text).apply {
                gravity = android.view.Gravity.CENTER
                setPadding(ui.dp(14), ui.dp(8), ui.dp(16), ui.dp(8))
                setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_search, 0, 0, 0)
                compoundDrawablePadding = ui.dp(6)
                setCompoundDrawableTintList(ColorStateList.valueOf(TvStyle.accent))
                ui.focusStyle(this)
                setOnClickListener { onOpenSearch(false) }
            }
            searchRow.addView(search, ui.lp(-2, -2))
        } else {
            search = ui.addButton(searchRow, "重新搜索") { onOpenSearch(true) }
            clearRef = search
            val status = ui.text(searchSummary(), 13f).apply { setPadding(0, ui.dp(6), 0, ui.dp(2)) }
            searchStatus = status
            rows += status
            val renderedFilters = filters.render(
                searchDetails = searchDetails,
                filterType = filterType,
                filterYear = filterYear,
                filterArea = filterArea,
                onScopeToggle = onScopeToggle,
                onPickFilter = onPickFilter,
                onClearFilters = onClearFilters,
                showFilterPicker = showFilterPicker,
            )
            rows += renderedFilters.row
            filterButtons = renderedFilters.buttons
        }
        rows.add(0, searchRow)
        return Result(rows, search, categoryButtons, filterButtons, clearRef, searchStatus)
    }
}

internal class ThirdSourceBrowseSourceBarRenderer(
    private val activity: Activity,
    private val ui: ThirdSourceUi,
) {
    data class Result(
        val row: View,
        val capsules: List<View>,
        val history: TextView,
        val favorite: TextView,
        val manage: TextView,
        val probeSummary: TextView,
    )

    fun render(
        sources: List<ThirdSource>,
        createCapsule: (ThirdSource, Int) -> View,
        clearCapsuleIndicators: () -> Unit,
        probeSummaryText: () -> String,
        onHistory: () -> Unit,
        onFavorites: () -> Unit,
        onManage: () -> Unit,
    ): Result {
        val sourceRow = ui.row()
        val capsuleScroll = HorizontalScrollView(activity).apply {
            isHorizontalScrollBarEnabled = false
            isFocusable = false
            clipToPadding = false
        }
        val capsulesRow = ui.row()
        clearCapsuleIndicators()
        val capsules = sources.mapIndexed { index, source -> createCapsule(source, index) }
        capsules.forEach { capsulesRow.addView(it, ui.lp(-2, -2).apply { rightMargin = ui.dp(8) }) }
        capsuleScroll.addView(capsulesRow)
        sourceRow.addView(capsuleScroll, ui.lp(0, -2).apply { weight = 1f })
        val history = ui.addButton(sourceRow, "观看历史", onClick = onHistory)
        val favorite = ui.addButton(sourceRow, "收藏", onClick = onFavorites)
        val manage = ui.addButton(sourceRow, "管理", onClick = onManage)
        val summary = ui.text(probeSummaryText(), 12f, TvStyle.muted).apply {
            setPadding(0, ui.dp(5), 0, ui.dp(5))
        }
        return Result(sourceRow, capsules, history, favorite, manage, summary)
    }
}

internal class ThirdSourceSearchFilterRenderer(
    private val ui: ThirdSourceUi,
    private val store: ThirdStore,
) {
    data class Result(val row: View, val buttons: List<View>)

    fun render(
        searchDetails: List<ThirdDetail>,
        filterType: String?,
        filterYear: String?,
        filterArea: String?,
        onScopeToggle: () -> Unit,
        onPickFilter: (String, String?) -> Unit,
        onClearFilters: () -> Unit,
        showFilterPicker: (String, List<String?>, String?, (String?) -> Unit) -> Unit,
    ): Result {
        val row = ui.row().apply { setPadding(0, 0, 0, ui.dp(4)) }
        val typeOptions = searchDetails.mapNotNull { it.typeName }.distinct().sorted()
        val yearOptions = searchDetails.mapNotNull { it.year }.distinct().sortedDescending()
        val areaOptions = searchDetails.mapNotNull { it.area }.distinct().sorted()
        val buttons = mutableListOf<View>()

        buttons += ui.text(if (store.searchAllSources) "范围：全部源" else "范围：仅当前源", 13f,
            if (store.searchAllSources) TvStyle.accent else TvStyle.text).apply {
            setPadding(ui.dp(10), ui.dp(6), ui.dp(10), ui.dp(6))
            ui.focusStyle(this, selected = store.searchAllSources)
            setOnClickListener { onScopeToggle() }
        }

        fun filterCapsule(label: String, current: String?, options: List<String?>): TextView =
            ui.text(label + (current?.let { "：$it" } ?: ""), 13f,
                if (current != null) TvStyle.accent else TvStyle.text).apply {
                setPadding(ui.dp(10), ui.dp(6), ui.dp(10), ui.dp(6))
                ui.focusStyle(this, selected = current != null)
                setOnClickListener { showFilterPicker(label, listOf(null) + options, current) { onPickFilter(label, it) } }
            }

        if (typeOptions.size > 1) buttons += filterCapsule("类型", filterType, typeOptions)
        if (yearOptions.size > 1) buttons += filterCapsule("年份", filterYear, yearOptions)
        if (areaOptions.size > 1) buttons += filterCapsule("地区", filterArea, areaOptions)
        if (filterType != null || filterYear != null || filterArea != null) {
            buttons += ui.text("清除筛选", 13f, TvStyle.muted).apply {
                setPadding(ui.dp(10), ui.dp(6), ui.dp(10), ui.dp(6))
                ui.focusStyle(this)
                setOnClickListener { onClearFilters() }
            }
        }
        buttons.forEach { row.addView(it, ui.lp(-2, -2).apply { rightMargin = ui.dp(8) }) }
        return Result(row, buttons)
    }
}

internal object ThirdSourceBrowseEmptyState {
    fun message(
        searchWord: String?,
        searchDetailsCount: Int,
        aggregating: Boolean,
        sourcesDone: Int,
        sourceTotal: Int,
        searchLoading: Boolean,
        loadError: String?,
        loading: Boolean,
        sourceName: String?,
    ): String = when {
        searchWord != null && searchDetailsCount > 0 ->
            "没有符合筛选条件的结果，调整筛选或清除筛选试试"
        searchWord != null && aggregating ->
            "正在聚合搜索「$searchWord」…（$sourcesDone/$sourceTotal 个源已返回）"
        searchWord != null && searchLoading ->
            "正在搜索「$searchWord」…"
        searchWord != null && loadError != null ->
            "搜索失败：$loadError，换个关键词或稍后重试"
        searchWord != null ->
            "未找到与「$searchWord」相关的内容，换个关键词试试"
        loading ->
            "正在加载「${sourceName.orEmpty()}」…"
        loadError == null ->
            "该分类暂无内容"
        else -> ""
    }
}

internal class ThirdSourceBrowseResultRenderer(
    private val ui: ThirdSourceUi,
    private val muted: Int,
) {
    fun addLoadError(
        container: LinearLayout,
        sourceName: String,
        error: String,
        onRetry: () -> Unit,
    ) {
        container.addView(
            ui.text("暂时无法加载「$sourceName」：$error", 15f, muted).apply {
                setPadding(0, ui.dp(24), 0, ui.dp(10))
            }
        )
        ui.addButton(container, "重试", onClick = onRetry).requestFocus()
    }

    fun addEmptyState(container: LinearLayout, message: String) {
        ui.message(container, message)
    }

    fun createGridStatus(message: String): TextView =
        ui.text(message, 12f, muted).apply {
            setPadding(0, ui.dp(4), 0, 0)
        }
}
