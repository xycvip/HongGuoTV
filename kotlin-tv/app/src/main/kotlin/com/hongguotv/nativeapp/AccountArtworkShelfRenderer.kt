// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.hongguotv.core.Series

/** 账户历史、收藏与预约列表共用的海报网格渲染器。 */
internal class AccountArtworkShelfRenderer(
    private val activity: Activity,
    private val page: LinearLayout,
    private val tabs: LinearLayout,
    private val accountSection: Int,
    private val columns: Int,
    private val dp: (Int) -> Int,
    private val column: () -> LinearLayout,
    private val text: (String, Float, Int) -> TextView,
    private val layoutParams: (Int, Int) -> LinearLayout.LayoutParams,
    private val rounded: (Int) -> Drawable,
    private val loadCover: (ImageView, String) -> Unit,
) {
    fun render(entries: List<Triple<Series, String, () -> Unit>>, shelfKey: String): View? {
        if (entries.isEmpty()) return null
        val grid = GridLayout(activity).apply {
            columnCount = columns
            rowCount = (entries.size + columns - 1) / columns
            alignmentMode = GridLayout.ALIGN_BOUNDS
            useDefaultMargins = false
        }
        val cards = mutableListOf<View>()
        entries.forEachIndexed { index, entry ->
            val (series, subtitle, click) = entry
            val rowIndex = index / columns
            val columnIndex = index % columns
            val card = column().apply {
                id = View.generateViewId()
                tag = "$shelfKey:${series.id}"
                isFocusable = true
                isFocusableInTouchMode = true
                setPadding(dp(4), dp(4), dp(4), dp(9))
                background = TvStyle.card(activity, false)
                contentDescription = "${series.title}，$subtitle"
                setOnFocusChangeListener { view, focused ->
                    view.background = TvStyle.card(activity, focused)
                }
                setOnClickListener { click() }
            }
            val artwork = object : FrameLayout(activity) {
                override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                    val width = MeasureSpec.getSize(widthMeasureSpec)
                    val height = (width / TvStyle.POSTER_ASPECT).toInt()
                    super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY))
                }
            }
            val poster = ImageView(activity).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = rounded(Color.rgb(25, 29, 35))
                contentDescription = "${series.title} 封面"
            }
            artwork.addView(poster, FrameLayout.LayoutParams(-1, -1))
            card.addView(artwork, layoutParams(-1, -2))
            loadCover(poster, series.cover)
            card.addView(text(series.title.ifBlank { "未知剧集" }, 14f, TvStyle.text).apply {
                setLines(2)
                ellipsize = TextUtils.TruncateAt.END
                includeFontPadding = false
                gravity = Gravity.TOP or Gravity.START
                setPadding(dp(3), dp(7), dp(3), 0)
            }, layoutParams(-1, -2))
            card.addView(text(subtitle, 12f, TvStyle.muted).apply {
                maxLines = 1
                minHeight = dp(19)
                ellipsize = TextUtils.TruncateAt.END
                setPadding(dp(3), 0, 0, 0)
            }, layoutParams(-1, -2))
            val params = GridLayout.LayoutParams(
                GridLayout.spec(rowIndex),
                GridLayout.spec(columnIndex, 1f)
            ).apply {
                width = 0
                height = -2
                setMargins(0, 0, dp(14), dp(10))
            }
            grid.addView(card, params)
            cards += card
        }
        // 少于一整行时补空列，保持卡片宽度与完整行一致。
        if (entries.size < columns) {
            for (columnIndex in entries.size until columns) {
                val spacer = View(activity).apply { visibility = View.INVISIBLE }
                val spacerParams = GridLayout.LayoutParams(
                    GridLayout.spec(0),
                    GridLayout.spec(columnIndex, 1f)
                ).apply {
                    width = 0
                    height = 0
                    setMargins(0, 0, dp(14), 0)
                }
                grid.addView(spacer, spacerParams)
            }
        }
        cards.forEachIndexed { index, card ->
            val rowIndex = index / columns
            val columnIndex = index % columns
            val rowEnd = minOf((rowIndex + 1) * columns, cards.size) - 1
            val nextRow = index + columns
            card.nextFocusLeftId = if (columnIndex > 0) cards[index - 1].id else card.id
            card.nextFocusRightId = if (index < rowEnd) cards[index + 1].id else card.id
            card.nextFocusUpId = if (rowIndex > 0) cards[index - columns].id
                else tabs.findViewWithTag<View>("account:tab:$accountSection")?.id ?: View.NO_ID
            card.nextFocusDownId = if (nextRow < cards.size) cards[nextRow].id else card.id
        }
        page.addView(grid, layoutParams(-1, -2).apply { bottomMargin = dp(8) })
        return cards.firstOrNull()
    }
}
