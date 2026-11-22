// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.graphics.Color
import android.graphics.Typeface
import android.text.TextUtils
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
        // 卡片本体：透明底 + 3dp 内缩，焦点环画在背景上，和海报之间留一道缝，环不会压住画面。
        val card=FrameLayout(host).apply {
            setPadding(dp(3),dp(3),dp(3),dp(3))
            clipChildren=false
            isFocusable=true
            isFocusableInTouchMode=true
        }
        // 海报容器按自身实际测量宽度定高，回收复用、列宽变化都不会出现同屏高低不一
        val artwork=object: FrameLayout(host) {
            override fun onMeasure(widthMeasureSpec: Int,heightMeasureSpec: Int) {
                val w=MeasureSpec.getSize(widthMeasureSpec)
                super.onMeasure(widthMeasureSpec,MeasureSpec.makeMeasureSpec((w/TvStyle.POSTER_ASPECT).toInt(),MeasureSpec.EXACTLY))
            }
        }
        val image=ImageView(host).apply { scaleType=ImageView.ScaleType.CENTER_CROP; importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        // 海报本身裁成圆角：原来只有底板是圆角，方角图片压在上面，边缘会露出一圈直角
        artwork.background=rounded(TvStyle.surface)
        artwork.clipToOutline=true
        artwork.addView(image,FrameLayout.LayoutParams(-1,-1))
        // 底部压暗：剧照亮度不可控（白底、雪景、高光都常见），标题必须始终读得出来。
        val scrim=View(host).apply {
            background=posterScrim(.96f)
            importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        artwork.addView(scrim,FrameLayout.LayoutParams(-1,dp(104),Gravity.BOTTOM))

        val track=LinearLayout(host).apply { orientation=LinearLayout.HORIZONTAL }
        val progressFill=View(host).apply { setBackgroundColor(TvStyle.colors.accent) }
        val progressRest=View(host).apply { setBackgroundColor(withAlpha(TvPalette.INK_100,.20f)) }
        track.addView(progressFill,LinearLayout.LayoutParams(0,dp(4),0f))
        track.addView(progressRest,LinearLayout.LayoutParams(0,dp(4),1f))
        artwork.addView(track,FrameLayout.LayoutParams(-1,dp(4),Gravity.BOTTOM))

        val info=LinearLayout(host).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(dp(11),0,dp(11),dp(9))
        }
        val title=text("",TvMetric.TEXT_LABEL).apply {
            setLines(1); includeFontPadding=false
            ellipsize=TextUtils.TruncateAt.END
            setTextColor(withAlpha(TvPalette.INK_100,.97f))
            typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL)
        }
        // 副信息一行封顶：回收复用时有无副信息都不会让标题上下跳。
        val badge=text("",TvMetric.TEXT_CAPTION).apply {
            maxLines=1; minLines=1
            setPadding(0,dp(3),0,0)
            includeFontPadding=false
            ellipsize=TextUtils.TruncateAt.END
            setTextColor(withAlpha(TvPalette.INK_300,.92f))
        }
        info.addView(title,lp(-1,-2))
        info.addView(badge,lp(-1,-2))
        artwork.addView(info,FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM).apply { bottomMargin=dp(3) })

        // 名次方牌：只放数字。左边距 9dp 与标题左边距对齐，视觉上压在一条竖线上。
        val rank=TextView(host).apply {
            textSize=TvMetric.TEXT_BODY
            gravity=Gravity.CENTER
            includeFontPadding=false
            minWidth=dp(24); minHeight=dp(24)
            setPadding(dp(7),dp(2),dp(7),dp(3))
            typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL)
            setTextColor(TvPalette.INK_100)
            background=TvStyle.shape(host,withAlpha(TvPalette.INK_950,.66f),Color.TRANSPARENT,TvMetric.RADIUS_S)
        }
        artwork.addView(rank,FrameLayout.LayoutParams(-2,-2,Gravity.TOP or Gravity.START).apply {
            leftMargin=dp(9); topMargin=dp(9)
        })

        // 高度由 artwork 自己按比例测量，这里用 wrap_content 交接，父容器不会把它拉成满高。
        card.addView(artwork,FrameLayout.LayoutParams(-1,-2))
        card.layoutParams=RecyclerView.LayoutParams(-1,-2).apply { rightMargin=dp(12); bottomMargin=dp(14) }
        return CardHolder(card,artwork,image,rank,title,badge,progressFill,progressRest)
    
    }
}
    override fun onBindViewHolder(holder: RecyclerView.ViewHolder,position: Int) {
    with(host) {

        if(getItemViewType(position)==CATALOG_TYPE_FOOTER) { bindFooter(holder.itemView as FrameLayout); return }
        holder as CardHolder
        val item=displayed[position]; holder.boundId=item.id
        if(position==0) resultFirstCard=holder.card
        val card=holder.card
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
        // 焦点态：白色环画在卡片背景上（海报外侧 3dp），同时轻微放大并抬升。
        fun paintCard(focused: Boolean) {
            card.background=if(focused) {
                TvStyle.shape(host,Color.TRANSPARENT,TvStyle.colors.focusRing,TvMetric.RADIUS_M)
            } else null
            card.elevation=if(focused) dp(10).toFloat() else 0f
            card.animateFocus(focused,1.045f)
        }
        paintCard(false)
        card.setOnFocusChangeListener { _,focused ->
            paintCard(focused)
            if(focused) { catalogFocus=item.id; loadCover(holder.image,item.cover); if(!actorRanked) selectionPreview?.show(item,selectionStatus(item)) }
        }
        card.nextFocusUpId=if(position<cols) {
            // 搜索页右侧海报栏的首行向上直接回到顶部“搜索”导航，避免被中栏联想/历史区截走。
            if(tab==1) searchNavShortcut?.id ?: View.NO_ID
            else if(tab==2 && actorRanking && collection!="actor-works") actorRankButton?.id ?: rankSwitch?.id ?: rankRefresh?.id ?: navForTab().id
            else suggestFirst?.id ?: historyFirst?.id ?: azLastRow.firstOrNull()?.id ?: searchInput?.id ?: collectionBack?.id ?: typeButtons[library.contentType]?.id ?: rankSwitch?.id ?: rankRefresh?.id ?: favoriteFilterFirst?.id ?: favoriteCheck?.id ?: historyManage?.id ?: navForTab().id
        } else View.NO_ID
        if(position>=displayed.size-12) catalogRecycler?.post { maybeLoadMore() }
    
    }
}
    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
    with(host) {

        (holder as? CardHolder)?.let { h ->
            if(h.boundId.isNotEmpty() && favoriteBadges[h.boundId]===h.badge) favoriteBadges.remove(h.boundId)
            // 复用前复位焦点动画，避免滚动后残留放大态
            h.card.animate().cancel(); h.card.scaleX=1f; h.card.scaleY=1f; h.card.elevation=0f
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

internal class CardHolder(
    val card: View,
    val artwork: FrameLayout,
    val image: ImageView,
    val rank: TextView,
    val title: TextView,
    /** 副信息（集数 / 热度 / 人气 / 收藏进度）。收藏页会直接改写这里的文字。 */
    val badge: TextView,
    val progressFill: View,
    val progressRest: View,
) : RecyclerView.ViewHolder(card) {
    var boundId=""
}
