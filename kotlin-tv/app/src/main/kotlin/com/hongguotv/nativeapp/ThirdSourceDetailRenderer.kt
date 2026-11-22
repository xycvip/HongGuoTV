// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.app.Activity
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView

/** 第三方影片详情页渲染器：展示影片资料、续播入口、播放组与剧集网格。 */
internal class ThirdSourceDetailRenderer(
    private val activity: Activity,
    private val ui: ThirdSourceUi,
    private val store: ThirdStore,
    private val artwork: ArtworkLoader,
    private val muted: Int
) {
    fun render(
        container: LinearLayout,
        data: ThirdDetail,
        source: ThirdSource?,
        sourceCount: Int,
        detailGroup: Int,
        playGroup: Int,
        synopsisExpanded: Boolean,
        onSynopsisExpanded: (Boolean) -> Unit,
        onBack: () -> Unit,
        onSwitchSource: (ThirdDetail) -> Unit,
        onToggleFavorite: (ThirdDetail) -> Unit,
        onPlay: (Int, Int, Long) -> Unit,
        onSelectGroup: (Int) -> Unit
    ): Int {
        val body = ui.column()
        val head = ui.row()
        val back = ui.addButton(head, "‹ 返回") { onBack() }
        head.addView(ui.text(data.video.name, 20f).apply {
            setTypeface(null, android.graphics.Typeface.BOLD)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(ui.dp(6), 0, 0, 0)
        }, ui.lp(0, -2).apply { weight = 1f; gravity = Gravity.CENTER_VERTICAL })
        body.addView(head, ui.lp(-1, -2).apply { bottomMargin = ui.dp(8) })

        val scroll = ScrollView(activity).apply { isVerticalScrollBarEnabled = false }
        val content = ui.column()
        scroll.addView(content)
        val infoRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        val poster = ImageView(activity).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(TvStyle.badgeSurface)
        }
        artwork.load(poster, data.video.cover)
        infoRow.addView(poster, ui.lp(ui.dp(150), (ui.dp(150) / TvStyle.POSTER_ASPECT).toInt()))

        val right = ui.column()
        val titleRow = ui.row()
        titleRow.addView(
            ui.text(data.video.name, 20f).apply { setTypeface(null, android.graphics.Typeface.BOLD) },
            ui.lp(-2, -2)
        )
        if(data.video.remarks.isNotBlank()) {
            titleRow.addView(ui.text(data.video.remarks, 11f, Color.WHITE).apply {
                setPadding(ui.dp(6), ui.dp(3), ui.dp(6), ui.dp(3))
                background = ui.rounded(TvStyle.badgeSurface, radius = 6)
            }, ui.lp(-2, -2).apply { leftMargin = ui.dp(8) })
        }
        right.addView(titleRow)
        val episodeCount = data.groups.getOrNull(detailGroup)?.second?.size ?: 0
        right.addView(
            ui.text(
                listOfNotNull(data.typeName, data.year, data.area, "共 $episodeCount 集").joinToString(" · "),
                13f,
                muted
            ).apply { setPadding(0, ui.dp(6), 0, ui.dp(6)) }
        )
        val sourceRow = ui.row()
        sourceRow.addView(ui.text("来源：${source?.name.orEmpty()}", 12f, muted), ui.lp(-2, -2))
        if(sourceCount > 1) {
            sourceRow.addView(ui.text("换源 ›", 12f, TvStyle.accent).apply {
                gravity = Gravity.CENTER
                setPadding(ui.dp(10), ui.dp(4), ui.dp(10), ui.dp(4))
                ui.focusStyle(this)
                setOnClickListener { onSwitchSource(data) }
            }, ui.lp(-2, -2).apply { leftMargin = ui.dp(4) })
        }
        right.addView(sourceRow)

        var expandedSynopsis = synopsisExpanded
        val synopsis = ui.text(data.synopsis ?: "暂无简介", 13f, muted).apply {
            maxLines = if(expandedSynopsis) Int.MAX_VALUE else 3
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        right.addView(synopsis)
        val toggle = ui.text(if(expandedSynopsis) "收起 ▲" else "展开 ▼", 12f, TvStyle.accent).apply {
            setPadding(0, ui.dp(4), 0, 0)
            visibility = View.GONE
            ui.focusStyle(this)
            setOnClickListener {
                expandedSynopsis = !expandedSynopsis
                val expanded = expandedSynopsis
                onSynopsisExpanded(expanded)
                synopsis.maxLines = if(expanded) Int.MAX_VALUE else 3
                text = if(expanded) "收起 ▲" else "展开 ▼"
            }
        }
        synopsis.post {
            toggle.visibility = if(synopsis.lineCount > 3 || expandedSynopsis) View.VISIBLE else View.GONE
        }
        right.addView(toggle)
        infoRow.addView(right, ui.lp(0, -2).apply { weight = 1f; leftMargin = ui.dp(14) })
        content.addView(infoRow, ui.lp(-1, -2).apply { bottomMargin = ui.dp(10) })

        val progress = store.progress(source?.id.orEmpty(), data.video.id)
        val mainActions = ui.row().apply { setPadding(0, 0, 0, ui.dp(10)) }
        var mainFocus: TextView? = null
        if(progress != null && data.groups.getOrNull(progress.group)?.second?.getOrNull(progress.episode) != null) {
            mainFocus = ui.addButton(mainActions, "继续播放 第${progress.episode + 1}集") {
                onPlay(progress.group, progress.episode, progress.positionMs)
            }
        }
        val start = ui.addButton(mainActions, if(progress != null) "从头再看" else "从第01集开始") {
            onPlay(detailGroup, 0, 0L)
        }
        if(mainFocus == null) mainFocus = start
        val favorited = source?.let { store.isFavorite(it.api, data.video.id) } == true
        val favoriteButton = ui.addButton(mainActions, if(favorited) "已收藏" else "收藏") { onToggleFavorite(data) }
        if(mainFocus !== start) mainFocus?.nextFocusRightId = start.id
        start.nextFocusRightId = favoriteButton.id
        favoriteButton.nextFocusLeftId = start.id
        content.addView(mainActions)

        if(data.groups.size > 1) {
            val tabRow = ui.row().apply { setPadding(0, 0, 0, ui.dp(6)) }
            var firstTab: TextView? = null
            data.groups.forEachIndexed { index, (name, _) ->
                val tab = ui.addButton(tabRow, name, index == playGroup) { onSelectGroup(index) }
                if(firstTab == null) firstTab = tab
            }
            content.addView(tabRow)
            firstTab?.let { tab ->
                mainFocus?.nextFocusDownId = tab.id
                tab.nextFocusUpId = mainFocus?.id ?: View.NO_ID
            }
        }

        val episodes = data.groups.getOrNull(playGroup)?.second.orEmpty()
        if(episodes.isEmpty()) {
            content.addView(ui.text("该线路暂无可用剧集", 14f, muted).apply { setPadding(0, ui.dp(8), 0, 0) })
        } else {
            val cols = ui.episodeCols()
            val watchedEpisode = store.progress(source?.id.orEmpty(), data.video.id)?.episode
            val adapter = ThirdSourceEpisodeAdapter(episodes, ui, watchedEpisode) { position ->
                onPlay(playGroup, position, 0L)
            }
            val grid = GridLayoutManager(activity, cols)
            val recycler = RecyclerView(activity).apply {
                id = View.generateViewId()
                layoutManager = grid
                this.adapter = adapter
                itemAnimator = null
                isVerticalScrollBarEnabled = false
                isFocusable = true
                isFocusableInTouchMode = true
                setOnFocusChangeListener { _, focused ->
                    if(focused) {
                        val first = grid.findFirstVisibleItemPosition().coerceAtLeast(0)
                        post { findViewHolderForAdapterPosition(first)?.itemView?.requestFocus() }
                    }
                }
            }
            content.addView(recycler, ui.lp(-1, -2))
            mainFocus?.nextFocusDownId = recycler.id
        }

        body.addView(scroll, ui.lp(-1, 0).apply { weight = 1f })
        container.addView(body, ui.lp(-1, 0).apply { weight = 1f })
        mainFocus?.nextFocusUpId = back.id
        mainFocus?.requestFocus()
        return mainFocus?.id ?: back.id
    }
}
