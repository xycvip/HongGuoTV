// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

/** 第三方片源历史/收藏页面使用的数据快照与展示模型。 */
internal class ThirdSourceLibraryState {
    var historyEntries: List<ThirdHistoryEntry> = emptyList()
        private set
    var historyVideos: List<ThirdVideo> = emptyList()
        private set
    var favoriteEntries: List<ThirdFavorite> = emptyList()
        private set
    var favoriteVideos: List<ThirdVideo> = emptyList()
        private set

    fun loadHistory(
        entries: List<ThirdHistoryEntry>,
        aliveApis: Set<String>,
        formatTime: (Long) -> String,
    ) {
        historyEntries = entries
        historyVideos = ThirdSourceLibraryMapper.historyVideos(entries, aliveApis, formatTime)
    }

    fun loadFavorites(
        entries: List<ThirdFavorite>,
        aliveApis: Set<String>,
        hostOf: (String) -> String,
    ) {
        favoriteEntries = entries
        favoriteVideos = ThirdSourceLibraryMapper.favoriteVideos(entries, aliveApis, hostOf)
    }

    fun historyEntry(videoId: String): ThirdHistoryEntry? =
        historyEntries.firstOrNull { ThirdSourceLibraryMapper.historyKey(it) == videoId }

    fun favoriteEntry(videoId: String): ThirdFavorite? =
        favoriteEntries.firstOrNull { ThirdSourceLibraryMapper.favoriteKey(it) == videoId }
}
