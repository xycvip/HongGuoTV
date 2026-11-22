// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.view.View
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView

/** 浏览页焦点恢复与遥控器上下左右焦点接线。 */
internal class ThirdSourceBrowseFocusController {
    fun connect(
        display: List<ThirdVideo>,
        browseFocusKey: String?,
        loading: Boolean,
        searchWord: String?,
        loadError: String?,
        categoryId: String?,
        sources: List<ThirdSource>,
        currentId: String?,
        capsuleViews: List<View>,
        categoryButtons: List<TextView>,
        filterButtons: List<View>,
        search: TextView,
        clearRef: TextView?,
        historyRef: TextView?,
        favoriteRef: TextView?,
        manage: TextView?,
        gridRecycler: RecyclerView?,
    ): Int {
        val restorePosition = browseFocusKey?.let { key -> display.indexOfFirst { it.id == key } } ?: -1
        val emptyBrowse = display.isEmpty() && !loading && searchWord == null && loadError == null
        val selectedCategory = if (searchWord == null && categoryId != null) categoryButtons.firstOrNull() else null
        val currentCapsule = capsuleViews.getOrNull(sources.indexOfFirst { it.id == currentId })
        val focusId = when {
            restorePosition >= 0 && gridRecycler != null -> gridRecycler.id
            selectedCategory != null -> selectedCategory.id
            searchWord != null && gridRecycler != null -> gridRecycler.id
            searchWord != null -> search.id
            emptyBrowse && categoryButtons.isNotEmpty() -> categoryButtons[0].id
            currentCapsule != null -> currentCapsule.id
            else -> capsuleViews.firstOrNull()?.id ?: search.id
        }

        if (restorePosition >= 0) gridRecycler?.let { recycler ->
            recycler.post {
                (recycler.layoutManager as? GridLayoutManager)?.scrollToPositionWithOffset(restorePosition, 0)
                recycler.post { recycler.findViewHolderForAdapterPosition(restorePosition)?.itemView?.requestFocus() }
            }
        }

        val rowAbove = currentCapsule?.id ?: historyRef?.id ?: favoriteRef?.id ?: manage?.id ?: View.NO_ID
        gridRecycler?.id?.let { gridId ->
            val browseRow = categoryButtons.firstOrNull()?.id
            val secondRow = browseRow ?: filterButtons.firstOrNull()?.id ?: gridId
            capsuleViews.forEach { it.nextFocusDownId = browseRow ?: search.id }
            historyRef?.nextFocusDownId = browseRow ?: search.id
            favoriteRef?.nextFocusDownId = browseRow ?: search.id
            manage?.nextFocusDownId = search.id
            search.nextFocusDownId = if (browseRow != null) gridId else secondRow
            clearRef?.nextFocusDownId = secondRow
            categoryButtons.forEach { it.nextFocusDownId = gridId }
            filterButtons.forEach { it.nextFocusDownId = gridId; it.nextFocusUpId = search.id }
        }
        categoryButtons.forEach { chip ->
            chip.nextFocusUpId = rowAbove
            chip.nextFocusRightId = search.id
        }
        search.nextFocusUpId = manage?.id ?: rowAbove
        search.nextFocusLeftId = categoryButtons.firstOrNull()?.id ?: manage?.id ?: favoriteRef?.id ?: historyRef?.id ?: View.NO_ID
        historyRef?.let { it.nextFocusRightId = favoriteRef?.id ?: manage?.id ?: View.NO_ID }
        favoriteRef?.let {
            it.nextFocusLeftId = historyRef?.id ?: View.NO_ID
            it.nextFocusRightId = manage?.id ?: View.NO_ID
        }
        manage?.let {
            it.nextFocusLeftId = favoriteRef?.id ?: historyRef?.id ?: View.NO_ID
            it.nextFocusRightId = clearRef?.id ?: search.id
            clearRef?.nextFocusLeftId = it.id
        }
        return focusId
    }
}
