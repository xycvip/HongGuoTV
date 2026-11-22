// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.graphics.Color
import android.view.*
import android.widget.*
import androidx.recyclerview.widget.RecyclerView
import com.hongguotv.core.*

private const val CATALOG_TYPE_CARD=0
private const val CATALOG_TYPE_FOOTER=1

/**
 * 全站海报网格（剧场 / 排行榜 / 追剧 / 搜索结果 / 演员作品）的卡片。
 *
 * 改造前每张卡是「海报 + 标题 + 标签 + 说明」四行堆叠：一张卡吃掉 300dp 高度，
 * 5 列网格一屏只能露出一排半，标题还要在 10.5sp 和 12sp 之间来回抢位置。
 * 现在改成**整块海报 + 底部压暗压字**：
 *  - 卡片本体就是海报，一屏能露出更多内容，"海报墙"才立得住；
 *  - 标题/副信息压在压暗层上，两行封顶，不再有第三行的零碎信息；
 *  - 名次做成左上角小方牌，前三名用强调色，其余用墨色半透明，浅色剧照上也不会糊；
 *  - 有观看进度时，海报底边压一条 3dp 进度条，扫一眼就知道看到哪了。
 */
internal class CatalogAdapter(private val host: MainActivity,val displayed: MutableList<Series>,val cols: Int): RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    var footer=FOOTER_NONE
    fun isFooter(position: Int)=footer!=FOOTER_NONE && position==displayed.size
    override fun getItemCount()=displayed.size+if(footer!=FOOTER_NONE) 1 else 0
    override fun getItemViewType(position: Int)=if(isFooter(position)) CATALOG_TYPE_FOOTER else CATALOG_TYPE_CARD
    fun updateFooter(next: Int) {
        if(footer==next) { if(next!=FOOTER_NONE) notifyItemChanged(displayed.size); return }
        val had=footer!=FOOTER_NONE; footer=next
        if(next==FOOTER_NONE) notifyItemRemoved(displayed.size)
        else if(had) notifyItemChanged(displayed.size)
        else notifyItemInserted(displayed.size)
    }
    override fun onCreateViewHolder(parent: ViewGroup,viewType: Int): RecyclerView.ViewHolder {
    return with(host) {

        if(viewType==CATALOG_TYPE_FOOTER) {
            val frame=FrameLayout(host)
            frame.layoutParams=RecyclerView.LayoutParams(-1,-2).apply { rightMargin=dp(14); bottomMargin=dp(10) }
            return object: RecyclerView.ViewHolder(frame) {}
        }
        // 卡片视觉统一由 PosterCard 构建（与首页货架同一实现），这里只负责网格间距。
        val poster=PosterCard.create(host,PosterCard.Mode.POSTER)
        poster.card.layoutParams=RecyclerView.LayoutParams(-1,-2).apply { rightMargin=dp(12); bottomMargin=dp(14) }
        return CardHolder(poster)

    }
}
    override fun onBindViewHolder(holder: RecyclerView.ViewHolder,position: Int) {
    with(host) {

        if(getItemViewType(position)==CATALOG_TYPE_FOOTER) { bindFooter(holder.itemView as FrameLayout); return }
        holder as CardHolder
        val item=displayed[position]; holder.boundId=item.id
        if(position==0) resultFirstCard=holder.card
        val card=holder.card
        // 防御性复位：海报高度一律交给 PosterCard 按实际宽度测量，
        // 绝不让上一屏/上一次布局留下的显式高度混进网格。
        holder.poster.artwork?.let { artwork ->
            (artwork.layoutParams as? FrameLayout.LayoutParams)?.let { params ->
                if(params.height>=0) { params.height=FrameLayout.LayoutParams.WRAP_CONTENT; artwork.layoutParams=params }
            }
        }
        card.tag=item.id; card.contentDescription=item.title
        loadCover(holder.image,item.cover)
        // 演员榜复用卡片网格布局，但数据与可播放短剧保持独立。
        val actorRanked=tab==2 && actorRanking && collection!="actor-works"
        val actor=if(actorRanked) actorRankItems.firstOrNull { it.actorId==item.id || (it.actorId.isBlank() && it.userId==item.id) } else null
        val ranked=tab==2 && ranking!=null
        val rankPosition=if(ranked) ranking?.positions?.get(item.id) else null
        val rankNumber=when {
            ranked -> rankPosition?.rank
            actorRanked -> actor?.position
            else -> null
        }
        if(rankNumber!=null) {
            // 前三名用强调色，其余用墨色：一眼能看出"榜首"和"上榜"的区别，
            // 又不会让整片网格变成一排橙色牌子。
            val top=rankNumber<=3
            holder.rank.visibility=View.VISIBLE
            holder.rank.text=rankNumber.toString()
            holder.rank.background=TvStyle.shape(
                host,
                if(top) TvStyle.colors.accent else withAlpha(TvPalette.INK_950,.66f),
                Color.TRANSPARENT,
                TvMetric.RADIUS_S,
            )
            holder.rank.setTextColor(if(top) TvStyle.colors.onAccent else TvPalette.INK_100)
        } else holder.rank.visibility=View.GONE

        holder.title.text=actor?.name ?: item.title
        val progress=if(!actorRanked && (tab==4 || collection=="resume")) library.progress(item.id) else null

        // 进度条：只在有进度时出现，宽度按观看百分比。
        if(progress!=null && progress.duration>0) {
            val ratio=(progress.position.toFloat()/progress.duration.toFloat()).coerceIn(0f,.999f)
            (holder.progressFill.layoutParams as? LinearLayout.LayoutParams)?.let {
                it.weight=ratio; holder.progressFill.layoutParams=it
            }
            (holder.progressRest.layoutParams as? LinearLayout.LayoutParams)?.let {
                it.weight=1f-ratio; holder.progressRest.layoutParams=it
            }
            holder.progressFill.visibility=View.VISIBLE; holder.progressRest.visibility=View.VISIBLE
        } else {
            holder.progressFill.visibility=View.GONE; holder.progressRest.visibility=View.GONE
        }

        val aggregateType=if(tab==1 && aggregateSearch) when {
            item.badge.startsWith("短剧") -> ContentType.SHORT
            item.badge.startsWith("漫剧") -> ContentType.COMIC
            else -> null
        } else null
        // 副信息只留一行，按优先级取最有用的一条，再补一个体裁词：
        // 名次/演员人气/收藏进度/观看进度/更新集数，五选一。
        val typeLabel=when(aggregateType) { ContentType.SHORT -> "短剧"; ContentType.COMIC -> "漫剧"; else -> null }
        val primaryMeta=when {
            actorRanked -> actor?.popularity?.ifBlank { "" }.orEmpty()
            tab==3 -> library.favoriteLabel(item.id)
            ranked -> rankPosition?.heat?.ifBlank { "" }.orEmpty()
            progress!=null -> if(library.watched(item.id)) "已看完" else "第 ${progress.episodeIndex+1} 集 · ${formatTime(progress.position)}"
            else -> item.badge
        }
        val genre=(actor?.details ?: item.tags.split(Regex("\\s*[·|，,、]\\s*")))
            .map { it.trim() }.firstOrNull { it.isNotBlank() && it != primaryMeta }
        holder.badge.text=listOfNotNull(typeLabel,primaryMeta.ifBlank { null },genre)
            .distinct().joinToString("  ·  ")
        holder.badge.setTextColor(withAlpha(TvPalette.INK_300,.92f))
        if(tab==3) favoriteBadges[item.id]=holder.badge
        card.setOnClickListener {
            catalogFocus=item.id
            if(actorRanked) {
                val entry=actor ?: return@setOnClickListener
                openActorWorks(entry)
            } else openDetail(item,tab==4 || collection=="resume" || collection=="updates")
        }
        card.setOnLongClickListener {
            catalogFocus=item.id
            if(actorRanked) true else { quickActions(item,card); true }
        }
        card.setOnKeyListener { _,key,event -> if(key==KeyEvent.KEYCODE_MENU) { if(event.action==KeyEvent.ACTION_UP && !actorRanked) { catalogFocus=item.id; quickActions(item,card) }; true } else false }
        // 焦点态：选项框画在前景（FocusFrame），卡内海报盖不住环。
        fun paintCard(focused: Boolean) {
            holder.poster.paintFocus(focused)
        }
        paintCard(false)
        card.setOnFocusChangeListener { _,focused ->
            paintCard(focused)
            if(focused) { catalogFocus=item.id; loadCover(holder.image,item.cover);  }
        }
        card.nextFocusUpId=if(position<cols) {
            // 搜索页右侧海报栏的首行向上直接回到顶部“搜索”导航，避免被中栏联想/历史区截走。
            if(tab==1) searchNavShortcut?.id ?: View.NO_ID
            else if(tab==2 && actorRanking && collection!="actor-works") actorRankButton?.id ?: rankSwitch?.id ?: rankRefresh?.id ?: navForTab().id
            else suggestFirst?.id ?: historyFirst?.id ?: azLastRow.firstOrNull()?.id ?: searchInput?.id ?: collectionBack?.id ?: rankSwitch?.id ?: rankRefresh?.id ?: favoriteFilterFirst?.id ?: favoriteCheck?.id ?: historyManage?.id ?: navForTab().id
        } else View.NO_ID
        // 第一列按左键统一回到左侧竖栏当前页签；交给系统空间搜索会"就近"落到
        // 顶部某个输入框或按钮上，回错导航是最常见的抱怨。
        if(position % cols==0) card.nextFocusLeftId=navForTab().id
        if(position>=displayed.size-12) catalogRecycler?.post { maybeLoadMore() }
    
    }
}
    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
    with(host) {

        (holder as? CardHolder)?.let { h ->
            if(h.boundId.isNotEmpty() && favoriteBadges[h.boundId]===h.badge) favoriteBadges.remove(h.boundId)
            // 复用前复位焦点动画，避免滚动后残留放大态（放大现在挂在卡内 artwork/tile 上）
            h.card.animate().cancel(); h.card.scaleX=1f; h.card.scaleY=1f; h.card.elevation=0f; h.card.foreground=null
            h.poster.artwork?.let { it.animate().cancel(); it.scaleX=1f; it.scaleY=1f }
            h.poster.tile?.let { it.animate().cancel(); it.scaleX=1f; it.scaleY=1f }
        }
    
    }
}
    private fun bindFooter(frame: FrameLayout) {
    with(host) {

        frame.removeAllViews()
        if(footer==FOOTER_MORE_HOT) {
            val line=row()
            addToneButton(line,"下一批热门",TvTone.Secondary) { collection=null; collectionReturn=null; page=2; catalogFocus=""; showCatalog(true) }
            frame.addView(line,FrameLayout.LayoutParams(-2,-2,Gravity.CENTER))
            return
        }
        val label=when(footer) {
            FOOTER_LOADING -> "正在加载…"
            FOOTER_ERROR -> "加载失败，继续浏览会自动重试"
            else -> if(tab==2 && actorRanking) "已显示全部内容" else if(tab==2) "已展示全部 ${displayed.size} 部" else "已加载全部，试试搜索"
        }
        val hint=text(label,TvMetric.TEXT_CAPTION,TvStyle.colors.textTertiary).apply {
            gravity=Gravity.CENTER
            setPadding(0,dp(TvMetric.GAP_M),0,dp(TvMetric.GAP_L))
            isFocusable=true; isFocusableInTouchMode=true
            setOnFocusChangeListener { view,focused ->
                (view as TextView).setTextColor(if(focused) TvStyle.colors.textSecondary else TvStyle.colors.textTertiary)
            }
        }
        frame.addView(hint,FrameLayout.LayoutParams(-1,-2))
    
    }
}
}

internal class CardHolder(val poster: PosterCard) : RecyclerView.ViewHolder(poster.card) {
    val card: View get()=poster.card
    val artwork: FrameLayout get()=poster.artwork as FrameLayout
    val image: ImageView get()=poster.image!!
    val rank: TextView get()=poster.rank!!
    val title: TextView get()=poster.title!!
    /** 副信息（集数 / 热度 / 人气 / 收藏进度）。收藏页会直接改写这里的文字。 */
    val badge: TextView get()=poster.badge!!
    val progressFill: View get()=poster.progressFill!!
    val progressRest: View get()=poster.progressRest!!
    var boundId=""
}
