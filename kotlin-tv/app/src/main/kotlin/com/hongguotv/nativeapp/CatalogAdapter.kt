// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.*
import android.widget.*
import androidx.recyclerview.widget.RecyclerView
import com.hongguotv.core.*

private const val CATALOG_TYPE_CARD=0
private const val CATALOG_TYPE_FOOTER=1

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
        val card=column(); card.setPadding(dp(4),dp(4),dp(4),dp(9)); focusStyle(card)
        // 海报容器按自身实际测量宽度定高，回收复用、列宽变化都不会出现同屏高低不一
        val artwork=object: FrameLayout(host) {
            override fun onMeasure(widthMeasureSpec: Int,heightMeasureSpec: Int) {
                val w=MeasureSpec.getSize(widthMeasureSpec)
                super.onMeasure(widthMeasureSpec,MeasureSpec.makeMeasureSpec((w/TvStyle.POSTER_ASPECT).toInt(),MeasureSpec.EXACTLY))
            }
        }
        val image=ImageView(host).apply { scaleType=ImageView.ScaleType.CENTER_CROP; importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        artwork.addView(image,FrameLayout.LayoutParams(-1,-1))
        val rank=TextView(host).apply { textSize=11f; setTypeface(null,Typeface.BOLD); setPadding(dp(8),dp(4),dp(8),dp(4)) }
        artwork.addView(rank,FrameLayout.LayoutParams(-2,-2,Gravity.TOP or Gravity.START))
        val fixedSearchCard=tab==1
        val title=text("",14f).apply { setLines(2); ellipsize=TextUtils.TruncateAt.END; includeFontPadding=false; gravity=Gravity.TOP or Gravity.START; setPadding(dp(3),dp(7),dp(3),0) }
        val tags=text("",10.5f).apply { maxLines=1; minLines=1; minHeight=dp(18); ellipsize=TextUtils.TruncateAt.END; setPadding(dp(3),0,dp(3),0); setTextColor(TvStyle.accent) }
        val badge=text("",12f).apply { maxLines=1; minLines=1; minHeight=dp(if(fixedSearchCard) 22 else 19); ellipsize=TextUtils.TruncateAt.END; setPadding(dp(3),0,0,0) }
        card.addView(artwork,lp(-1,-2))
        card.addView(title,lp(-1,-2))
        card.addView(tags,lp(-1,dp(18)))
        // 搜索网格同一行会按最高卡片统一行高；用弹性空白吸收多出的高度，
        // 让每张卡片的标签始终落在卡片底部，而不是跟在标题后悬空。
        if(fixedSearchCard) card.addView(View(host),lp(-1,0).apply { weight=1f })
        card.addView(badge,lp(-1,if(fixedSearchCard) dp(22) else -2))
        card.layoutParams=RecyclerView.LayoutParams(-1,-2).apply { rightMargin=dp(14); bottomMargin=dp(10) }
        return CardHolder(card,artwork,image,rank,title,tags,badge)
    
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
        val actorRanked=tab==2 && actorRanking
        val actor=if(actorRanked) actorRankItems.firstOrNull { it.userId==item.id } else null
        val ranked=tab==2 && ranking!=null
        val rankPosition=if(ranked) ranking?.positions?.get(item.id) else null
        if(actorRanked) {
            val rank=actor?.position
            val rankLabel=if(rank!=null) "第 $rank 名" else "名次暂无"
            holder.rank.text=rankLabel
            holder.rank.setTextColor(if(rank==1) white else Color.rgb(24,26,30))
            holder.rank.background=rounded(if(rank==1) accent else Color.argb(235,255,255,255),6)
            holder.rank.visibility=View.VISIBLE
            card.contentDescription="$rankLabel，${actor?.name ?: item.title}，${actor?.popularity.orEmpty()}"
        } else if(ranked) {
            val rank=rankPosition?.rank
            val rankLabel=if(rank!=null) "第 $rank 名" else "名次暂无"
            val styled=android.text.SpannableString(rankLabel)
            if(rank!=null) {
                val start=rankLabel.indexOf(' ')+1; val end=rankLabel.lastIndexOf(' ')
                styled.setSpan(android.text.style.RelativeSizeSpan(1.45f),start,end,0)
                styled.setSpan(android.text.style.StyleSpan(Typeface.BOLD),start,end,0)
            }
            holder.rank.text=styled
            holder.rank.setTextColor(if(rank==1) white else Color.rgb(24,26,30))
            holder.rank.background=rounded(if(rank==1) accent else Color.argb(235,255,255,255),6)
            holder.rank.visibility=View.VISIBLE
            card.contentDescription="$rankLabel，${item.title}，${rankPosition?.heat?.ifBlank { "热度暂无" } ?: "热度暂无"}"
        } else holder.rank.visibility=View.GONE
        holder.title.text=actor?.name ?: item.title
        val genreTags=(actor?.details ?: item.tags.split(Regex("\\s*[·|，,、]\\s*")))
            .map { it.trim() }.filter { it.isNotBlank() }.distinct().take(3)
        holder.tags.text=genreTags.joinToString("  ·  ")
        holder.tags.visibility=if(genreTags.isEmpty()) View.INVISIBLE else View.VISIBLE
        val progress=if(!actorRanked && (tab==4 || collection=="resume")) library.progress(item.id) else null
        val aggregateType=if(tab==1 && aggregateSearch) when {
            item.badge.startsWith("短剧") -> ContentType.SHORT
            item.badge.startsWith("漫剧") -> ContentType.COMIC
            else -> null
        } else null
        holder.badge.text=if(actorRanked) actor?.popularity?.ifBlank { "人气暂无" } ?: "人气暂无" else if(tab==3) library.favoriteLabel(item.id) else if(ranked) rankPosition?.heat?.ifBlank { "热度暂无" } ?: "热度暂无" else if(progress!=null) if(library.watched(item.id)) "整剧已看完" else "第 ${progress.episodeIndex+1} 集 · ${formatTime(progress.position)}" else item.badge
        if(aggregateType!=null) {
            val short=aggregateType==ContentType.SHORT
            holder.badge.setTextColor(if(short) Color.rgb(145,72,20) else Color.rgb(54,79,157))
            holder.badge.background=GradientDrawable().apply {
                cornerRadius=dp(5).toFloat()
                setColor(if(short) Color.rgb(255,237,218) else Color.rgb(229,237,255))
            }
            holder.badge.setPadding(dp(7),0,dp(7),0)
            holder.badge.text=holder.badge.text.toString().replace(" · ","  ·  ")
        } else {
            holder.badge.background=null
            holder.badge.setPadding(dp(3),0,0,0)
            holder.badge.setTextColor(if(actorRanked || ranked || tab==3) accent else muted)
        }
        // 搜索结果卡片的标签统一占据 22dp 高度，并统一垂直居中。
        // 短剧/漫剧标签与普通来源文字混排、ViewHolder 回收复用时，也保持同一基线。
        if(tab==1) {
            holder.badge.minHeight=dp(22)
            holder.badge.gravity=Gravity.CENTER_VERTICAL or Gravity.START
            holder.badge.includeFontPadding=false
        }
        if(tab==3) favoriteBadges[item.id]=holder.badge
        card.setOnClickListener {
            catalogFocus=item.id
            if(actorRanked) {
                val entry=actor ?: return@setOnClickListener
                rankSubtitle?.text=buildString {
                    append("第 ${entry.position} 名 · ${entry.name}")
                    if(entry.popularity.isNotBlank()) append(" · ${entry.popularity}")
                    if(entry.intro.isNotBlank()) append("\n${entry.intro}")
                    if(entry.details.isNotEmpty()) append("\n${entry.details.joinToString(" · ")}")
                }
            } else openDetail(item,tab==4 || collection=="resume" || collection=="updates")
        }
        card.setOnLongClickListener {
            catalogFocus=item.id
            if(actorRanked) true else { quickActions(item,card); true }
        }
        card.setOnKeyListener { _,key,event -> if(key==KeyEvent.KEYCODE_MENU) { if(event.action==KeyEvent.ACTION_UP && !actorRanked) { catalogFocus=item.id; quickActions(item,card) }; true } else false }
        card.setOnFocusChangeListener { _,focused -> card.background=TvStyle.card(host,focused); if(focused) { catalogFocus=item.id; loadCover(holder.image,item.cover); if(!actorRanked) selectionPreview?.show(item,selectionStatus(item)) } }
        card.nextFocusUpId=if(position<cols) {
            // 搜索页右侧海报栏的首行向上直接回到顶部“搜索”导航，避免被中栏联想/历史区截走。
            if(tab==1) searchNavShortcut?.id ?: View.NO_ID
            else if(tab==2 && actorRanking) actorRankButton?.id ?: rankSwitch?.id ?: rankRefresh?.id ?: navForTab().id
            else suggestFirst?.id ?: historyFirst?.id ?: azLastRow.firstOrNull()?.id ?: searchInput?.id ?: collectionBack?.id ?: genreClear?.id ?: typeButtons[library.contentType]?.id ?: rankSwitch?.id ?: rankRefresh?.id ?: favoriteFilterFirst?.id ?: favoriteCheck?.id ?: historyManage?.id ?: navForTab().id
        } else View.NO_ID
        if(position>=displayed.size-12) catalogRecycler?.post { maybeLoadMore() }
    
    }
}
    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
    with(host) {

        (holder as? CardHolder)?.let { h -> if(h.boundId.isNotEmpty() && favoriteBadges[h.boundId]===h.badge) favoriteBadges.remove(h.boundId) }
    
    }
}
    private fun bindFooter(frame: FrameLayout) {
    with(host) {

        frame.removeAllViews()
        if(footer==FOOTER_MORE_HOT) {
            frame.addView(button("下一批热门") { collection=null; collectionReturn=null; page=2; catalogFocus=""; showCatalog(true) },FrameLayout.LayoutParams(-2,-2,Gravity.CENTER))
            return
        }
        val label=when(footer) {
            FOOTER_LOADING -> "正在加载…"
            FOOTER_ERROR -> "加载失败，继续浏览会自动重试"
            else -> if(tab==2) "已展示全部 ${displayed.size} 部" else "已加载全部，试试搜索"
        }
        val hint=text(label,14f,muted).apply {
            gravity=Gravity.CENTER; setPadding(0,dp(8),0,dp(8)); isFocusable=true; isFocusableInTouchMode=true
            setOnFocusChangeListener { _,focused -> setTextColor(if(focused) white else muted) }
        }
        frame.addView(hint,FrameLayout.LayoutParams(-1,-2))
    
    }
}
}

internal class CardHolder(
    val card: LinearLayout,
    val artwork: FrameLayout,
    val image: ImageView,
    val rank: TextView,
    val title: TextView,
    val tags: TextView,
    val badge: TextView
) : RecyclerView.ViewHolder(card) {
    var boundId=""
}
