// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp


internal class ThirdSourceCatalogPageState {
    var categories: List<ThirdCategory> = emptyList()
    var page: Int = 1
    var hasMore: Boolean = false
    var videos: MutableList<ThirdVideo> = mutableListOf()
    var loading: Boolean = false
    var loadingMore: Boolean = false
    var categoryId: String? = null
    var categoriesCached: Boolean = false
    var requested: Boolean = false

    /** 重置分类选择、缓存标记和首屏请求守卫；分页内容由 resetContent() 单独清理。 */
    fun resetSelection() {
        categoryId = null
        categoriesCached = false
        requested = false
    }

    /** 保留加载标记不变，匹配换源/跨页清理原有行为。 */
    fun resetContent() {
        page = 1
        hasMore = false
        videos.clear()
        categories = emptyList()
    }
}

internal data class ThirdSourceCatalogPageUpdate(
    val videos: List<ThirdVideo>,
    val freshVideos: List<ThirdVideo>,
    val insertAt: Int,
    val page: Int,
    val hasMore: Boolean,
)

internal object ThirdSourceCatalogPageReducer {
    fun replace(page: ThirdListPage) = ThirdSourceCatalogPageUpdate(
        videos = page.videos,
        freshVideos = page.videos,
        insertAt = 0,
        page = 1,
        hasMore = page.hasMore && page.videos.isNotEmpty(),
    )

    fun append(
        current: List<ThirdVideo>,
        incoming: ThirdListPage,
        requestedPage: Int,
    ): ThirdSourceCatalogPageUpdate {
        val known = current.mapTo(HashSet()) { it.id }
        val fresh = incoming.videos.filter { it.id !in known }
        return ThirdSourceCatalogPageUpdate(
            videos = current + fresh,
            freshVideos = fresh,
            insertAt = current.size,
            page = requestedPage,
            hasMore = incoming.hasMore && incoming.videos.isNotEmpty(),
        )
    }
}
