// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.app.Activity
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/** 三方片源目录网格适配器；数据、焦点记录、分页触发和点击行为由页面注入。 */
internal class ThirdSourceGridAdapter(
    private val activity: Activity,
    private val ui: ThirdSourceUi,
    private val artwork: ArtworkLoader,
    private val columns: Int,
    private val source: () -> List<ThirdVideo>,
    private val isBrowseMode: () -> Boolean,
    private val onBrowseFocus: (String) -> Unit,
    private val onNearEnd: () -> Unit,
    private val onOpen: (ThirdVideo) -> Unit,
) : RecyclerView.Adapter<ThirdSourceGridAdapter.Holder>() {
    private val contentPx = (activity.resources.displayMetrics.widthPixels * 0.88f).toInt()
    private val cellPx = (contentPx - ui.dp(12) * (columns - 1)) / columns
    private val posterPx = (cellPx / TvStyle.POSTER_ASPECT).toInt()
    private val items: List<ThirdVideo> get() = source()

    fun submit(appendFrom: Int) {
        if(appendFrom < 0) notifyDataSetChanged()
        else notifyItemRangeInserted(appendFrom, source().size - appendFrom)
    }

    inner class Holder(
        val card: LinearLayout,
        val image: ImageView,
        val name: TextView,
        val badge: TextView,
    ) : RecyclerView.ViewHolder(card)

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): Holder {
        val card = ui.column().apply { setPadding(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(4)) }
        val imageFrame = FrameLayout(activity)
        val image = ImageView(activity).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(TvStyle.badgeSurface)
        }
        imageFrame.addView(image, FrameLayout.LayoutParams(-1, posterPx))
        val badge = ui.text("", 12f, Color.WHITE).apply {
            setPadding(ui.dp(6), ui.dp(3), ui.dp(6), ui.dp(3))
            background = ui.rounded(TvStyle.badgeSurface, radius = 6)
        }
        imageFrame.addView(
            badge,
            FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply {
                topMargin = ui.dp(5)
                rightMargin = ui.dp(5)
            },
        )
        val name = ui.text("", 15f).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(ui.dp(2), ui.dp(6), ui.dp(2), 0)
        }
        card.addView(imageFrame)
        card.addView(name)
        card.layoutParams = RecyclerView.LayoutParams(-1, -2).apply {
            bottomMargin = ui.dp(12)
            rightMargin = ui.dp(4)
        }
        return Holder(card, image, name, badge)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = items[position]
        holder.name.text = item.name
        holder.badge.visibility = if(item.remarks.isBlank()) View.GONE else View.VISIBLE
        holder.badge.text = item.remarks
        artwork.load(holder.image, item.cover, ArtworkLoader.PRIORITY_VISIBLE)
        holder.card.id = View.generateViewId()
        ui.focusStyle(holder.card)
        // 保留 focusStyle 的绘制监听，再追加名称高亮与分页预取。
        val paint = holder.card.onFocusChangeListener
        holder.card.setOnFocusChangeListener { view, focused ->
            paint?.onFocusChange(view, focused)
            holder.name.setTextColor(if(focused) TvStyle.accent else TvStyle.text)
            if(focused && isBrowseMode()) onBrowseFocus(item.id)
            if(focused && position >= items.size - 3) onNearEnd()
        }
        holder.card.setOnClickListener { onOpen(item) }
        if(position >= items.size - 12) onNearEnd()
    }
}
