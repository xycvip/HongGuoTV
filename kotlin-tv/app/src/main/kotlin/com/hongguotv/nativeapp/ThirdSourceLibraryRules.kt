// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp


internal object ThirdSourceLibraryMapper {
    fun historyKey(entry: ThirdHistoryEntry): String = entry.sourceApi + "|" + entry.videoId

    fun favoriteKey(entry: ThirdFavorite): String = entry.sourceApi + "|" + entry.videoId

    fun historyVideos(
        entries: List<ThirdHistoryEntry>,
        aliveApis: Set<String>,
        formatTime: (Long) -> String,
    ): List<ThirdVideo> = entries.map { entry ->
        val badge = when {
            entry.sourceApi !in aliveApis -> "源已移除"
            entry.completed -> "已看完"
            else -> entry.episodeLabel.ifBlank { "第 ${entry.episodeIndex + 1} 集" } +
                if(entry.positionMs > 0) " · " + formatTime(entry.positionMs) else ""
        }
        ThirdVideo(historyKey(entry), entry.title.ifBlank { "未命名影片" }, entry.cover, badge)
    }

    fun favoriteVideos(
        entries: List<ThirdFavorite>,
        aliveApis: Set<String>,
        hostOf: (String) -> String,
    ): List<ThirdVideo> = entries.map { entry ->
        val badge = if(entry.sourceApi !in aliveApis) "源已移除"
        else listOf(entry.sourceName.ifBlank { hostOf(entry.sourceApi) },
            entry.episodeLabel.ifBlank { "第 ${entry.episodeIndex + 1} 集" })
            .filter { it.isNotBlank() }
            .joinToString(" · ")
        ThirdVideo(favoriteKey(entry), entry.title.ifBlank { "未命名影片" }, entry.cover, badge)
    }
}

internal object ThirdSourceSavedEntryPlanner {
    internal data class Resolution(
        val source: ThirdSource?,
        val shouldSwitch: Boolean
    )

    fun resolve(
        sourceApi: String,
        sources: List<ThirdSource>,
        currentId: String?
    ): Resolution {
        val source = sources.firstOrNull { it.api == sourceApi }
        return Resolution(
            source = source,
            shouldSwitch = source != null && source.id != currentId
        )
    }
}
