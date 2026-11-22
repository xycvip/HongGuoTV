// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.view.View
import com.hongguotv.core.ComicRanking
import com.hongguotv.core.RankBoard
import com.hongguotv.core.Series

internal class NavigationUiState {
    var screen: String = "catalog"
    var tab: Int = 0
    var page: Int = 1
    var catalog: List<Series> = emptyList()
    var hasMore: Boolean = false
    var catalogFocus: String = ""
    var collection: String? = null
    var collectionReturn: CatalogState? = null
    var collectionFocus: String = ""
    var collectionBack: View? = null
    var loadingMore: Boolean = false
    var catalogPageKey: String = ""
    var prefetchAttemptAt: Long = 0L
    var prefetchAttempts: Int = 0
    var lastHomeNavTapAt: Long = 0L
    val tabFocus = mutableMapOf<Int, String>()
    val nav = mutableListOf<View>()
    val navByTab = mutableMapOf<Int, View>()
    var searchNavShortcut: View? = null
    var settingsFocusKey: String? = null
}

internal data class CatalogState(
    val page: Int,
    val items: List<Series>,
    val hasMore: Boolean,
    val ranking: ComicRanking?,
    val board: RankBoard? = null,
    val actorRanking: Boolean = false
)
