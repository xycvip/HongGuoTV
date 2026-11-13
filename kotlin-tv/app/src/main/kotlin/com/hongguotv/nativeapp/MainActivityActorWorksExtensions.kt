// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.widget.Toast
import com.hongguotv.core.ActorRankEntry

/** Open the official works list for the selected actor while preserving the ranking page. */
internal fun MainActivity.openActorWorks(actor: ActorRankEntry) {
    if (actor.actorId.isBlank()) {
        Toast.makeText(this, "该演员暂缺作品查询编号", Toast.LENGTH_SHORT).show()
        return
    }
    collectionReturn = CatalogState(page, catalog, hasMore, ranking, rankBoard, actorRanking)
    collectionFocus = catalogFocus
    collection = "actor-works"
    actorWorksActor = actor
    actorWorksItems = emptyList()
    actorWorksNextOffset = 0
    actorWorksHasMore = false
    page = 1
    catalogFocus = ""
    showCatalog(load = true)
}
