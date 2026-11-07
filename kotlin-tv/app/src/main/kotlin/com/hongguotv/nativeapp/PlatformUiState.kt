// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import com.hongguotv.core.*

internal enum class ContentPlatform(val id: String, val label: String) {
    REDFRUIT("redfruit", "红果"),
    HIPPO("hippo", "河马剧场");

    fun other() = if (this == REDFRUIT) HIPPO else REDFRUIT
    companion object {
        fun from(value: String?) = entries.firstOrNull { it.id == value } ?: REDFRUIT
    }
}

internal class PlatformUiState {
    var active: ContentPlatform = ContentPlatform.REDFRUIT
    val snapshots = mutableMapOf<ContentPlatform, PlatformCatalogSnapshot>()
}

internal data class PlatformCatalogSnapshot(
    val tab: Int,
    val page: Int,
    val catalog: List<Series>,
    val hasMore: Boolean,
    val ranking: ComicRanking?,
    val rankBoard: RankBoard,
    val genre: Genre?,
    val catalogFocus: String,
    val tabState: Map<Int, CatalogState>,
    val tabFocus: Map<Int, String>
)

internal class PlatformPreferenceStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("content-platform-v1", Context.MODE_PRIVATE)
    fun load(): ContentPlatform = ContentPlatform.from(prefs.getString("active", "redfruit"))
    fun save(platform: ContentPlatform) { prefs.edit().putString("active", platform.id).apply() }
}
