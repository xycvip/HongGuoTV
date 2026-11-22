// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.app.Activity
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
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
    private val items: List<ThirdVideo> get() = source()

    fun submit(appendFrom: Int) {
        if(appendFrom < 0) notifyDataSetChanged()
        else notifyItemRangeInserted(appendFrom, source().size - appendFrom)
    }

    inner class Holder(
        val card: FrameLayout,
        val image: ImageView,
        val name: TextView,
        val badge: TextView,
    ) : RecyclerView.ViewHolder(card)

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): Holder {
        val card = FrameLayout(activity).apply {
            setPadding(ui.dp(3), ui.dp(3), ui.dp(3), ui.dp(3))
            clipChildren = false
        }
        // 高度按实际测量宽度算，和主站海报网格同一套比例，列宽变化不会出现两套卡高。
        val imageFrame = object : FrameLayout(activity) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val width = MeasureSpec.getSize(widthMeasureSpec)
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec((width / TvStyle.POSTER_ASPECT).toInt(), MeasureSpec.EXACTLY))
            }
        }.apply {
            background = ui.rounded(TvStyle.surface, radius = TvMetric.RADIUS_M)
            clipToOutline = true
        }
        val image = ImageView(activity).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(TvStyle.badgeSurface)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        imageFrame.addView(image, FrameLayout.LayoutParams(-1, -1))
        val scrim = View(activity).apply {
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.TRANSPARENT, withAlpha(TvPalette.INK_950, .96f)),
            )
        }
        imageFrame.addView(scrim, FrameLayout.LayoutParams(-1, ui.dp(88), Gravity.BOTTOM))
        // 更新到第几集压在海报底部，和卡片标题同一条基线；换成右上角小胶囊会和标题抢同一块颜色。
        val name = ui.text("", TvMetric.TEXT_LABEL, withAlpha(TvPalette.INK_100, .97f)).apply {
            maxLines = 1
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(ui.dp(11), 0, ui.dp(11), ui.dp(10))
        }
        imageFrame.addView(name, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        val badge = ui.text("", TvMetric.TEXT_CAPTION, TvPalette.INK_100).apply {
            includeFontPadding = false
            setPadding(ui.dp(7), ui.dp(3), ui.dp(7), ui.dp(4))
            background = ui.rounded(withAlpha(TvPalette.INK_950, .68f), radius = TvMetric.RADIUS_S)
        }
        imageFrame.addView(
            badge,
            FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply {
                topMargin = ui.dp(8)
                rightMargin = ui.dp(8)
            },
        )
        card.addView(imageFrame, FrameLayout.LayoutParams(-1, -2))
        card.layoutParams = RecyclerView.LayoutParams(-1, -2).apply {
            bottomMargin = ui.dp(14)
            rightMargin = ui.dp(12)
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
        holder.card.isFocusable = true
        holder.card.isFocusableInTouchMode = true
        fun paint(focused: Boolean) {
            // 选项框统一走 FocusFrame（柔光+白环，零越界）；卡内图片保留轻微放大。
            holder.card.background = if (focused) {
                FocusFrame.ring(holder.card.context, Color.TRANSPARENT, TvMetric.RADIUS_M)
            } else null
            holder.card.elevation = 0f
            holder.card.animate().cancel(); holder.card.scaleX = 1f; holder.card.scaleY = 1f
            holder.image.animateFocus(focused, 1.02f)
        }
        paint(false)
        holder.card.setOnFocusChangeListener { _, focused ->
            paint(focused)
            if(focused && isBrowseMode()) onBrowseFocus(item.id)
            if(focused && position >= items.size - 3) onNearEnd()
        }
        holder.card.setOnClickListener { onOpen(item) }
        if(position >= items.size - 12) onNearEnd()
    }
}
