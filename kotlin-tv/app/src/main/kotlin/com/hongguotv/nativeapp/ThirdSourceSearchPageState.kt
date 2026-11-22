// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

/** 独立三方片源搜索页的输入、联想与分页状态，不包含浏览态搜索结果。 */
internal class ThirdSourceSearchPageState {
    var input: String = ""
    var suggestions: List<String> = emptyList()
    var suggestionSequence: Int = 0
    var loading: Boolean = false
    var loadingMore: Boolean = false
    var requested: Boolean = false
    var page: Int = 1
    var hasMore: Boolean = false
    var total: Int = 0

    fun invalidateSuggestions() {
        suggestionSequence++
        suggestions = emptyList()
    }

    fun resetResults() {
        loading = false
        loadingMore = false
        requested = false
        page = 1
        hasMore = false
        total = 0
    }
}
