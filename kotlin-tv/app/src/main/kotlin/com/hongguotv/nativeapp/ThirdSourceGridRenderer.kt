// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.app.Activity
import android.view.View
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView

/** 网格控件创建与 RecyclerView 焦点/滚动行为；数据和页面动作通过回调注入。 */
internal class ThirdSourceGridRenderer(
    private val activity: Activity,
    private val ui: ThirdSourceUi,
    private val artwork: ArtworkLoader,
) {
    data class MountedGrid(
        val adapter: ThirdSourceGridAdapter,
        val recycler: RecyclerView,
    )

    fun render(
        source: () -> List<ThirdVideo>,
        isBrowseMode: () -> Boolean,
        onBrowseFocus: (String) -> Unit,
        onNearEnd: () -> Unit,
        onOpen: (ThirdVideo) -> Unit,
    ): MountedGrid {
        val columns = ui.browseCols()
        val adapter = ThirdSourceGridAdapter(
            activity = activity,
            ui = ui,
            artwork = artwork,
            columns = columns,
            source = source,
            isBrowseMode = isBrowseMode,
            onBrowseFocus = onBrowseFocus,
            onNearEnd = onNearEnd,
            onOpen = onOpen,
        )
        val layout = GridLayoutManager(activity, columns)
        val recycler = RecyclerView(activity).apply {
            id = View.generateViewId()
            setLayoutManager(layout)
            setAdapter(adapter)
            itemAnimator = null
            isVerticalScrollBarEnabled = false
            isFocusable = true
            isFocusableInTouchMode = true
            setOnFocusChangeListener { _, focused ->
                if (focused) {
                    val first = layout.findFirstVisibleItemPosition().coerceAtLeast(0)
                    post { findViewHolderForAdapterPosition(first)?.itemView?.requestFocus() }
                }
            }
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    // 滚到底自动续页：焦点导航未触发 bind 临界判断时由这里兜底
                    if (!recyclerView.canScrollVertically(1)) onNearEnd()
                }
            })
        }
        return MountedGrid(adapter, recycler)
    }
}
