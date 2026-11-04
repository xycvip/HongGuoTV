// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * 搜索页独立状态容器。
 *
 * 视图引用仅在当前 Activity 生命周期内有效；Activity 通过委托属性暂时保持
 * 原有调用接口，后续可将搜索渲染与异步搜索协调逐步迁出 Activity。
 */
internal class SearchUiState {
    var query: String = ""
    var aggregateSearch: Boolean = true
    var aggregateButton: View? = null
    var searchInput: View? = null
    var searchButton: TextView? = null
    var searchActions: MutableList<View> = mutableListOf()
    var azFirst: View? = null
    var azLastRow: MutableList<View> = mutableListOf()
    var suggestRow: LinearLayout? = null
    var suggestFirst: View? = null
    var suggestTicket: Int = 0
    var suggestVertical: Boolean = false
    var suggestFocusIndex: Int = -1
    var searchBody: LinearLayout? = null
    var searchColumns: Int = 6
    var keyboardLastLabel: String? = null
    var keyboardKeys: Map<String,TextView> = emptyMap()
    var keyboardRows: List<List<TextView>> = emptyList()
    var historyFirst: TextView? = null
    var historyClear: TextView? = null
    var historyItems: List<TextView> = emptyList()
    var suggestScrollRef: ScrollView? = null
    var fullSearchColumns: Int = 6
    var lastSuggestWords: List<String> = emptyList()
    var lastSuggestKey: String = ""
    var resultFirstCard: View? = null
    var searchFocusPending: Boolean = false
    var searchHotLoading: Boolean = false
    var searchHotLoaded: Boolean = false
    var refreshHomePending: Boolean = false
    var pendingRefreshFocus: String? = null
    var historyManage: TextView? = null
}
