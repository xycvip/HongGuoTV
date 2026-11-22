// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.view.Gravity
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/** 三方片源详情页分集网格；当前播放进度和点击动作由页面传入。 */
internal class ThirdSourceEpisodeAdapter(
    private val episodes: List<ThirdEpisode>,
    private val ui: ThirdSourceUi,
    private val watchedEpisode: Int?,
    private val onSelect: (Int) -> Unit,
) : RecyclerView.Adapter<ThirdSourceEpisodeAdapter.Holder>() {
    inner class Holder(val view: TextView) : RecyclerView.ViewHolder(view)

    override fun getItemCount() = episodes.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = ui.text("", 13f).apply {
            gravity = Gravity.CENTER
            minHeight = ui.dp(34)
            layoutParams = RecyclerView.LayoutParams(-1, -2).apply {
                bottomMargin = ui.dp(6)
                rightMargin = ui.dp(6)
            }
        }
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val episode = episodes[position]
        val watched = watchedEpisode == position
        holder.view.text = episode.label
        ui.focusStyle(holder.view, selected = watched)
        if(watched) holder.view.setTextColor(TvStyle.accent)
        holder.view.setOnClickListener { onSelect(position) }
    }
}
