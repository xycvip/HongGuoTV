// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.graphics.Typeface
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.*
import com.hongguotv.core.Series

/** Bounded horizontal shelves with stable D-pad selection and per-row scroll positions. */
class HomeScreen(
    context: Context,
    private val top: View,
    private val loadCover: (ImageView,String)->Unit,
    private val open: (Series,Boolean)->Unit,
    private val actions: (Series,View)->Unit,
    private val remember: (String)->Unit,
    private val onVerticalScroll: (Int)->Unit = {},
    /** 内容区可用宽度（像素）。有左侧竖栏时比屏幕窄，卡片必须按它等分，否则最后一列会被裁掉。 */
    private val contentWidthPx: (() -> Int)? = null,
    /** 一行放几张。跟随「外观 · 海报列数」设置，默认五列。 */
    private val columns: (() -> Int)? = null,
    /** 左侧竖栏当前页签的 View id：最左列按左键统一回到导航栏。 */
    private val navId: (() -> Int)? = null,
): LinearLayout(context) {
    data class Entry(val series: Series,val label: String,val resume: Boolean=false,val progress: Int?=null)
    data class Shelf(val key: String,val title: String,val entries: List<Entry>,val more: (() -> Unit)?=null)
    data class Position(val focus: String,val shelf: String?,val column: Int,val row: Int,val vertical: Int,val horizontal: Map<String,Int>)
    private data class Card(val key: String,val view: FrameLayout,val poster: PosterCard,var entry: Entry?,var pending: Boolean=false)
    private data class Row(val key: String,val block: LinearLayout,val horizontal: HorizontalScrollView,val cards: List<Card>)
    private val accent=TvStyle.accent
    private fun dp(n: Int)=(n*resources.displayMetrics.density).toInt()
    private fun shape(focus: Boolean)=TvStyle.card(context,focus)
    private fun label(value: String,size: Float,color: Int=TvStyle.text)=TextView(context).apply { text=value; textSize=size; setTextColor(color); includeFontPadding=false }
    private val scroll=ScrollView(context).apply {
        // 滚动容器保留自身裁剪：货架海报滚出视口的部分不允许越界绘制；
        // clipToPadding=false 让边缘卡片的焦点环画进 padding 区仍完整。
        isVerticalScrollBarEnabled=false; clipToPadding=false
        setOnTouchListener { _,event ->
            when(event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN,android.view.MotionEvent.ACTION_MOVE,
                android.view.MotionEvent.ACTION_UP,android.view.MotionEvent.ACTION_CANCEL -> noteInteraction()
            }
            false
        }
        setOnScrollChangeListener { _,_,y,_,oldY ->
            if(y!=oldY) noteInteraction()
            onVerticalScroll(y-oldY)
        }
    }
    private val contents=LinearLayout(context).apply { orientation=VERTICAL; clipChildren=false }
    private val rows=mutableListOf<Row>()
    private val cards=linkedMapOf<String,Card>()
    private var shelves=emptyList<Shelf>()
    private var footerMore: (() -> Unit)?=null
    private var moreButton: View?=null
    private var heroView: HomeHeroView?=null
    private val HERO_KEY="home:hero"
    private var lastFocus=""
    private var lastRow: String?=null
    private var restoring=false
    private var revision=0
    // 首页数据可能在用户滑动时陆续返回；结构性重建会销毁并重建整排卡片，造成滚动抖动。
    // 将更新合并，并等触摸/惯性滚动停止后再一次性应用。
    private val updateHandler=Handler(Looper.getMainLooper())
    private var lastInteractionAt=0L
    private var pendingShelves: List<Shelf>?=null
    private var pendingRequestFocus=false
    private val applyPendingShelves=Runnable { applyPendingShelfUpdate() }
    private fun applyPendingShelfUpdate() {
        val next=pendingShelves ?: return
        val quietFor=SystemClock.uptimeMillis()-lastInteractionAt
        val touching=scroll.isPressed || rows.any { it.horizontal.isPressed }
        if(touching || quietFor<450L) {
            updateHandler.postDelayed(applyPendingShelves,if(touching) 180L else (450L-quietFor).coerceAtLeast(80L))
        } else {
            pendingShelves=null
            val position=capturePosition()
            render(next,position.focus,pendingRequestFocus,footerMore,position)
        }
    }
    private fun noteInteraction() {
        lastInteractionAt=SystemClock.uptimeMillis()
        if(pendingShelves!=null) {
            updateHandler.removeCallbacks(applyPendingShelves)
            updateHandler.postDelayed(applyPendingShelves,500L)
        }
    }
    private fun scheduleShelfUpdate(next: List<Shelf>,requestFocus: Boolean) {
        pendingShelves=next
        pendingRequestFocus=requestFocus
        updateHandler.removeCallbacks(applyPendingShelves)
        updateHandler.postDelayed(applyPendingShelves,500L)
    }
    val refresh=label("正在更新热门…",14f,TvStyle.muted).apply { id=View.generateViewId(); tag="home:refresh"; isFocusable=true; isFocusableInTouchMode=true; gravity=Gravity.CENTER; minHeight=dp(44); setPadding(dp(16),dp(10),dp(16),dp(10)); background=shape(false); setOnFocusChangeListener { _,f -> background=shape(f); setTextColor(if(f) TvStyle.text else TvStyle.muted); if(f) lastFocus=tag.toString() } }
    init { orientation=VERTICAL; scroll.addView(contents); addView(scroll,LayoutParams(-1,0,1f)) }

    fun capturePosition(): Position {
        val key=findFocus()?.tag?.toString()?.takeIf { it in cards || it==HERO_KEY || it=="home:refresh" || it=="home:more" } ?: lastFocus
        val row=rows.indexOfFirst { item -> item.cards.any { it.key==key } }
        val column=rows.getOrNull(row)?.cards?.indexOfFirst { it.key==key } ?: 0
        return Position(key,rows.getOrNull(row)?.key,column.coerceAtLeast(0),row.coerceAtLeast(0),scroll.scrollY,rows.associate { it.key to it.horizontal.scrollX })
    }

    /** Rebuild only this bounded shelf area, preserving the old card or its nearest neighbour. */
    fun updateShelves(next: List<Shelf>,requestFocus: Boolean=hasFocus()) {
        val recentlyActive=SystemClock.uptimeMillis()-lastInteractionAt<450L
        val touching=scroll.isPressed || rows.any { it.horizontal.isPressed }
        if(recentlyActive || touching) {
            scheduleShelfUpdate(next,requestFocus)
            return
        }
        updateHandler.removeCallbacks(applyPendingShelves)
        pendingShelves=null
        val position=capturePosition()
        render(next,position.focus,requestFocus,footerMore,position)
    }

    /** Update an existing card without replacing views or moving focus. Structural changes use updateShelves. */
    fun updateEntry(shelfKey: String,entry: Entry): Boolean {
        val card=cards["$shelfKey:${entry.series.id}"] ?: return false
        if(card.entry?.series?.cover!=entry.series.cover) { card.poster.image?.setImageDrawable(null) }
        card.entry=entry
        card.poster.title?.text=entry.series.title
        card.poster.badge?.text=entry.label
        card.poster.setProgressRatio(entry.progress?.let { it/100f })
        card.view.contentDescription="${shelves.firstOrNull { it.key==shelfKey }?.title.orEmpty()}，${entry.series.title}，${entry.label}"
        shelves=shelves.map { shelf -> if(shelf.key==shelfKey) shelf.copy(entries=shelf.entries.map { if(it.series.id==entry.series.id) entry else it }) else shelf }
        loadNearbySelection()
        return true
    }



    /** Retry visible cover requests after the owner cancels work for a page or lifecycle change. */
    fun reloadArtwork() {
        if (restoring) return
        // 刷新首页时重新请求当前已展示的所有货架封面，避免仅当前焦点附近有图。
        rows.forEach { row -> row.cards.forEach(::requestCover) }
    }

    fun render(shelves: List<Shelf>,focus: String,requestFocus: Boolean,more: (() -> Unit)?,position: Position?=null) {
        // 列数在每次重建时算一次：屏幕宽度、竖栏宽度、用户设置三者都变了才需要重算。
        val columnCount=(columns?.invoke() ?: 5).coerceAtLeast(2)
        val previous=position ?: if(rows.isNotEmpty()) capturePosition() else null
        this.shelves=shelves; footerMore=more; revision++; val ticket=revision; restoring=true
        contents.removeAllViews(); rows.clear(); cards.clear(); moreButton=null; lastRow=null
        // 顶部主推位：优先"接着看"的第一条，其次"收藏有更新"，最后退回第一个有内容的货架。
        val lead=shelves.firstOrNull { it.entries.isNotEmpty() && it.key=="resume" }
            ?: shelves.firstOrNull { it.entries.isNotEmpty() && it.key=="updates" }
            ?: shelves.firstOrNull { it.entries.isNotEmpty() }
        if(lead!=null) {
            val hero=heroView ?: HomeHeroView(context,loadCover) { item -> lastFocus=HERO_KEY; remember(HERO_KEY); open(item.series,item.resume) }
                .also { it.id=View.generateViewId(); it.tag=HERO_KEY; heroView=it }
            hero.bind(lead.entries.first())
            contents.addView(hero,LayoutParams(-1,-2))
        }
        // 主推位已展示"接着看"的第一条，整栏再铺一遍就是重复；只在高屏没有主推位时保留。
        val heroConsumed=if(lead!=null) lead.key else null
        shelves.filter { (it.entries.isNotEmpty() && it.key!=heroConsumed) || it.more!=null }.forEachIndexed { shelfIndex,shelf ->
            // clipChildren=false：最左侧卡片获得焦点时向外放大 4.5% 并带 10dp 投影，
            // 容器不放开裁剪的话第一列的焦点框左缘会被裁掉半条。
            val block=LinearLayout(context).apply { orientation=VERTICAL; clipChildren=false }
            // 随机推荐区保留居中的操作卡片，不再额外显示重复的栏目标题。
            if(shelf.key!="random") {
                val heading=LinearLayout(context).apply { orientation=HORIZONTAL; gravity=Gravity.CENTER_VERTICAL; setPadding(dp(2),dp(TvMetric.GAP_XS),dp(2),dp(TvMetric.GAP_S)) }
                heading.addView(View(context).apply { background=TvStyle.shape(context,accent,radius=2) },LayoutParams(dp(3),dp(16)).apply { rightMargin=dp(TvMetric.GAP_S) })
                heading.addView(label(shelf.title,TvMetric.TEXT_SUBTITLE).apply { setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL)) },LayoutParams(0,-2,1f))
                // 操作提示只在第一个货架出现一次：每条都挂一遍等于没有提示。
                if(shelfIndex==0) heading.addView(label("左右选剧  ·  上下切换分区",TvMetric.TEXT_CAPTION,TvStyle.colors.textTertiary))
                block.addView(heading)
            }
            val horizontal=VerticalFriendlyHorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled=false; clipToPadding=false
                // 上下 12dp、左右 8dp 焦点呼吸位：卡片焦点环（放大 4%）+ 4dp 投影
                // 画在 padding 区内不缺角；视口外的半张卡仍被货架自身裁剪，
                // 不会越界叠到左侧竖栏上（真机实测）。
                setPadding(dp(8),dp(12),dp(8),dp(12)); isFillViewport=shelf.key=="random"
                setOnTouchListener { _,event ->
                    when(event.actionMasked) {
                        android.view.MotionEvent.ACTION_DOWN,android.view.MotionEvent.ACTION_MOVE,
                        android.view.MotionEvent.ACTION_UP,android.view.MotionEvent.ACTION_CANCEL -> noteInteraction()
                    }
                    false
                }
            }
            val line=LinearLayout(context).apply { orientation=HORIZONTAL; clipChildren=false; gravity=if(shelf.key=="random") Gravity.CENTER else Gravity.TOP }
            horizontal.addView(line, if(shelf.key=="random") LayoutParams(-1,-2) else LayoutParams(-2,-2)); block.addView(horizontal)
            val rowCards=mutableListOf<Card>()
            fun addCard(entry: Entry?,moreAction: (() -> Unit)?=null) {
                // 按列数等分内容区。宽度必须按「竖栏之后剩下的宽度」算，用屏幕宽会让最后一列被裁掉右半边。
                val available = contentWidthPx?.invoke() ?: (resources.displayMetrics.widthPixels * .9f).toInt()
                val width=if(shelf.key=="random") dp(320)
                    else ((available-dp(16)-dp(14)*columnCount)/columnCount).toInt().coerceAtLeast(dp(96))
                val key=if(entry!=null) "${shelf.key}:${entry.series.id}" else "${shelf.key}:more"
                val isRandom=shelf.key=="random"
                val mode=when { entry!=null -> PosterCard.Mode.POSTER; isRandom -> PosterCard.Mode.RANDOM; else -> PosterCard.Mode.MORE }
                val posterHeight=if(isRandom) dp(98) else ((width-dp(6))/TvStyle.POSTER_ASPECT).toInt()
                // 卡片视觉统一走 PosterCard：首页货架与剧场/榜单网格从此是同一套实现。
                val poster=PosterCard.create(context,mode,if(entry!=null) null else width,if(entry!=null) null else posterHeight)
                val view=poster.card
                view.id=View.generateViewId(); view.tag=key
                if(mode==PosterCard.Mode.POSTER) {
                    poster.title?.text=entry?.series?.title
                    if(isRandom) {
                        // 随机推荐里的剧集卡是横条里的紧凑缩略图：副信息与进度都不展示。
                        poster.badge?.visibility=View.GONE
                        poster.artwork?.layoutParams?.height=dp(76)
                    } else {
                        poster.badge?.text=entry?.label ?: shelf.title
                        poster.setProgressRatio(entry?.progress?.let { it/100f })
                    }
                }
                val card=Card(key,view,poster,entry)
                view.contentDescription=entry?.let { "${shelf.title}，${it.series.title}，${it.label}" } ?: "查看全部${shelf.title}"
                view.setOnClickListener { lastFocus=key; remember(key); card.entry?.let { open(it.series,it.resume) } ?: moreAction?.invoke() }
                if(entry!=null) {
                    view.setOnLongClickListener { lastFocus=key; remember(key); card.entry?.let { actions(it.series,view) }; true }
                    view.setOnKeyListener { _,code,event -> if(code==KeyEvent.KEYCODE_MENU) { if(event.action==KeyEvent.ACTION_UP) { lastFocus=key; remember(key); card.entry?.let { actions(it.series,view) } }; true } else false }
                }
                view.setOnFocusChangeListener { _,focused ->
                    poster.paintFocus(focused,1.04f)
                    if(focused) {
                        lastFocus=key; remember(key)
                        if(!restoring) {
                            loadNearbySelection()
                            if(lastRow!=shelf.key) scroll.post { if(revision==ticket && view.hasFocus()) reveal(block) }
                        }
                        lastRow=shelf.key
                    }
                }
                line.addView(view,LinearLayout.LayoutParams(width,if(isRandom) dp(104) else -1).apply {
                    rightMargin=dp(14)
                }); rowCards+=card; cards[key]=card
            }
            // 所有片单先展示内容，“查看全部”统一放在对应栏目末尾。
            shelf.entries.forEach { addCard(it) }
            shelf.more?.let { action -> addCard(null,action) }
            contents.addView(block,LayoutParams(-1,-2).apply { bottomMargin=dp(16) }); rows+=Row(shelf.key,block,horizontal,rowCards)
        }
        if(cards.isEmpty()) contents.addView(label("还没有本机记录，热门剧加载后可选择观看。",15f).apply { setPadding(0,dp(16),0,dp(16)) })
        // 刷新按钮由首页顶部快捷操作栏承载；底部不再重复放置“刷新热门/更多热门”。
        moreButton=null
        rows.forEachIndexed { i,row -> row.cards.forEachIndexed { j,card ->
            // 最左一张按下左键回到左侧竖栏当前页签（统一规则），而不是把自己再选中一次。
            card.view.nextFocusLeftId=if(j==0) (navId?.invoke() ?: top.id) else row.cards[j-1].view.id
            card.view.nextFocusRightId=row.cards[(j+1).coerceAtMost(row.cards.lastIndex)].view.id
            card.view.nextFocusUpId=if(i==0) (heroView?.id ?: top.id) else rows[i-1].cards[j.coerceAtMost(rows[i-1].cards.lastIndex)].view.id
            card.view.nextFocusDownId=if(i==rows.lastIndex) card.view.id else rows[i+1].cards[j.coerceAtMost(rows[i+1].cards.lastIndex)].view.id
        } }
        heroView?.let { hero ->
            hero.nextFocusUpId=navId?.invoke() ?: top.id
            hero.nextFocusLeftId=navId?.invoke() ?: top.id
            hero.nextFocusDownId=rows.firstOrNull()?.cards?.firstOrNull()?.view?.id ?: hero.id
        }
        // 不要再把「导航项的下键」接到主推位：竖栏里下键必须继续换页，
        // 进内容的通道是右键（交给系统的空间搜索）。
        val preferred=focus.ifBlank { previous?.focus.orEmpty() }
        val target=target(preferred,previous)
        if(requestFocus) target.requestFocus()
        post layout@{
            if(revision!=ticket) return@layout
            fitArtwork()
            post restore@{
                if(revision!=ticket) return@restore
                if(previous!=null) restoreOffsets(previous)
                restoring=false
                if(requestFocus && previous==null) rows.firstOrNull { row -> row.cards.any { it.view===target } }?.let { revealSelection(it) }
                loadNearbySelection()
            }
        }
    }

    fun restorePosition(position: Position,requestFocus: Boolean=true) {
        restoring=true
        if(requestFocus) target(position.focus,position).requestFocus()
        post { restoreOffsets(position); restoring=false; loadNearbySelection() }
    }

    private fun target(key: String,position: Position?): View {
        if(key==HERO_KEY) heroView?.let { return it }
        if(key=="home:refresh") return top
        if(key=="home:more") moreButton?.let { return it }
        cards[key]?.let { return it.view }
        if(position!=null) {
            val row=rows.firstOrNull { it.key==position.shelf } ?: rows.getOrNull(position.row.coerceAtMost(rows.lastIndex))
            row?.cards?.getOrNull(position.column.coerceAtMost(row.cards.lastIndex))?.let { return it.view }
        }
        return cards.values.firstOrNull()?.view ?: refresh
    }
    private fun restoreOffsets(position: Position) {
        rows.forEach { row -> row.horizontal.scrollTo(position.horizontal[row.key] ?: 0,0) }
        scroll.scrollTo(0,position.vertical)
        // A removed row can leave the nearest surviving card outside the old viewport.
        rows.firstOrNull { row -> row.cards.any { it.view.hasFocus() } }?.let(::revealSelection)
    }
    private fun revealSelection(row: Row) {
        row.cards.firstOrNull { it.view.hasFocus() }?.view?.let { card ->
            val horizontal=row.horizontal
            val left=horizontal.scrollX
            val right=left+horizontal.width-horizontal.paddingRight
            val target=when { card.left<left -> card.left; card.right>right -> card.right-horizontal.width+horizontal.paddingRight; else -> left }
            if(target!=left) horizontal.scrollTo(target.coerceAtLeast(0),0)
        }
        reveal(row.block,false)
    }
    private fun reveal(block: View,smooth: Boolean=true) {
        val target=if(smooth) block.top else when { block.top<scroll.scrollY -> block.top; block.bottom>scroll.scrollY+scroll.height -> if(block.height>=scroll.height) block.top else block.bottom-scroll.height; else -> scroll.scrollY }.coerceAtLeast(0)
        if(target!=scroll.scrollY) { if(smooth) scroll.smoothScrollTo(0,target) else scroll.scrollTo(0,target) }
    }
    private fun loadNearbySelection() {
        if(restoring) return
        val row=rows.indexOfFirst { it.cards.any { card -> card.key==lastFocus } }.coerceAtLeast(0)
        val column=rows.getOrNull(row)?.cards?.indexOfFirst { it.key==lastFocus }?.coerceAtLeast(0) ?: 0
        rows.getOrNull(row)?.cards?.let { items -> for(index in (column-1).coerceAtLeast(0)..(column+6).coerceAtMost(items.lastIndex)) requestCover(items[index]) }
        rows.getOrNull(row+1)?.cards?.take(6)?.forEach(::requestCover)
    }
    private fun requestCover(card: Card) {
        val image=card.poster.image ?: return
        if(card.pending || card.entry?.series?.cover.isNullOrBlank()) return
        val ticket=revision
        fun start() {
            card.pending=false
            if(ticket!=revision || !image.isAttachedToWindow || image.width<=0 || image.height<=0) return
            loadCover(image,card.entry!!.series.cover)
        }
        if(image.isLaidOut && !image.isLayoutRequested) start()
        else {
            card.pending=true
            image.addOnLayoutChangeListener(object: View.OnLayoutChangeListener {
                override fun onLayoutChange(v: View,left: Int,top: Int,right: Int,bottom: Int,oldLeft: Int,oldTop: Int,oldRight: Int,oldBottom: Int) {
                    image.removeOnLayoutChangeListener(this); start()
                }
            })
        }
    }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); post { reloadArtwork() } }
    override fun onDetachedFromWindow() {
        updateHandler.removeCallbacks(applyPendingShelves)
        super.onDetachedFromWindow()
    }
    override fun onSizeChanged(w: Int,h: Int,oldw: Int,oldh: Int) { super.onSizeChanged(w,h,oldw,oldh); if(w!=oldw || h!=oldh) post { fitArtwork(); loadNearbySelection() } }
    private fun fitArtwork() {
        if(height<=0) return
        rows.forEach { row ->
            // 随机推荐操作卡片使用独立固定尺寸，不参与海报高度自适应。
            if(row.key=="random") return@forEach
            // 卡片本体就是海报（标题已压进遮罩层），可用高度只剩货架标题行与上下留白。
            val heading=row.block.getChildAt(0).measuredHeight
            val desired=((row.cards.first().view.width-dp(6))/TvStyle.POSTER_ASPECT).toInt()
            val imageHeight=minOf(desired,(height-heading-dp(10)).coerceAtLeast(dp(48)))
            row.cards.forEach { card ->
                val artwork=card.poster.artwork ?: return@forEach
                if(artwork.layoutParams.height!=imageHeight) artwork.layoutParams=artwork.layoutParams.apply { height=imageHeight }
            }
        }
    }
    fun firstId()=heroView?.id ?: rows.firstOrNull()?.cards?.firstOrNull()?.view?.id ?: top.id
}
