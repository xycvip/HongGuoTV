// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.hongguotv.core.*

/**
 * 主导航竖栏。
 *
 * ## 为什么从顶部横排换成左侧竖排
 *
 * 顶部横排把「切换页面」和「在页面里横向浏览」放在了同一个方向轴上：遥控器左右键既要翻页签、
 * 又要翻海报，两种语义互相抢键；页签一多（首页/剧场/排行榜/追剧/设置/推送/账户/片源）就挤成一条，
 * 字号只能压到 15sp 以下，远看不见层级。竖排之后：
 *
 *  - 上下 = 换页面，左右 = 在当前页面里浏览，两个方向各有唯一含义，不用试。
 *  - 纵向空间几乎无限，加一个入口不用重排整条栏。
 *  - 顶部 52dp 让给主推位，内容从屏幕上方 22dp 就开始，画面更完整。
 *
 * 竖栏本身不跟随内容滚动、不做隐藏动画：电视上"能看见自己在哪"比"多露两行内容"重要。
 * 宽度只占 88~104dp（16:9 屏幕上约 9%~11%），内容区仍有 780dp 以上，五列海报不受影响。
 */
/**
 * 只有「宽到能同时放下竖栏和五列海报」时才启用竖栏。
 *
 * 1080p 电视是 960×540dp，竖栏 104dp + 内边距后内容区仍有 788dp，五列海报每张 148dp。
 * 720p 电视只有 640×360dp，扣掉竖栏后五列只剩 90dp 一张，剧名会挤成两三个字一行，
 * 这种情况继续用顶部横排（信息密度更低但每张卡更大），这是分辨率适配，不是两套设计。
 */
internal fun MainActivity.useNavigationRail(): Boolean = widthDp() >= 840f

internal fun MainActivity.railWidthDp(): Int =
    if(widthDp() >= 900f) TvMetric.RAIL_WIDE else TvMetric.RAIL_NARROW

/** 竖栏占位后剩下的可用宽度，供键盘/网格这类需要按宽度算列数的页面使用。 */
internal fun MainActivity.contentWidthDp(): Float =
    if(catalogRailHost == null) widthDp()*.9f else (widthDp() - railWidthDp() - 68f).coerceAtLeast(320f)

/** 四项接口状态灯。顶栏和竖栏共用同一个构造，避免两套注册逻辑走偏。 */
internal fun MainActivity.createApiStatusFlower(): ApiStatusFlowerView {
    val flower=ApiStatusFlowerView(this).apply {
        isFocusable=false; isFocusableInTouchMode=false; isClickable=false
        background=null
        contentDescription="四项接口状态指示灯"
    }
    listOf("首页","搜索","播放","联想").forEach { name ->
        apiStatusViews[name]=flower
        apiStatusResults[name]?.let { (color,detail) ->
            if(detail.startsWith("检测中") || detail.startsWith("重试中")) animateApiStatus(name,color,detail)
            else setApiStatus(name,color,detail)
        }
    }
    catalogNavFlower=flower
    return flower
}

/**
 * 渲染竖栏。
 *
 * 结构：品牌 → 内容页签（首页/剧场/排行榜/追剧）→ 细线 → 搜索/我的 → 弹性空隙 → 工具区（设置/推送/片源）。
 * 工具区贴底，视觉上和内容页签分开，避免"设置"和"追剧"看起来是同一级内容。
 */
internal fun MainActivity.renderNavigationRail(railHost: FrameLayout, container: LinearLayout) {
    val compact=heightDp() < 470f
    // 1080p（540dp 高）下竖栏总高约 500dp，留 40dp 余量给字体缩放；
    // 720p（360dp 高）隐藏品牌行并把行高压到 34dp，保证底部三个工具项仍在屏内。
    val itemHeight=if(compact) 34 else 46
    val utilHeight=if(compact) 30 else 34
    val rail=column().apply {
        setPadding(dp(10),dp(if(compact) 8 else 18),dp(10),dp(if(compact) 8 else 12))
    }
    railHost.addView(rail,FrameLayout.LayoutParams(-1,-1))

    if(!compact) {
        // 品牌行只放字标：竖栏只有 84dp 净宽，再塞状态灯会把「红果TV」挤成三行。
        val brand=row().apply { setPadding(dp(12),0,dp(6),dp(14)) }
        brand.addView(
            View(this).apply { background=TvStyle.shape(this@renderNavigationRail,TvStyle.colors.accent) },
            lp(dp(6),dp(6)).apply { rightMargin=dp(8) },
        )
        brand.addView(text("红果TV",14f,TvStyle.colors.textPrimary).apply {
            typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL)
            letterSpacing=.04f
            includeFontPadding=false
            maxLines=1
        },lp(0,-2).apply { weight=1f })
        rail.addView(brand,lp(-1,-2))
    }

    val items=mutableListOf<View>()
    fun addItem(
        label: String,
        active: Boolean,
        util: Boolean,
        tabId: Int?,
        onPick: () -> Unit,
    ): FrameLayout {
        val item=FrameLayout(this).apply {
            id=View.generateViewId()
            isFocusable=true; isFocusableInTouchMode=true; isClickable=true
            contentDescription=label
        }
        // 选中标记用左侧 3dp 竖条而不是整块底色：一条细条就够定位，颜色面积小才显得安静。
        val bar=View(this).apply { setBackgroundColor(TvStyle.colors.accent) }
        item.addView(
            bar,
            FrameLayout.LayoutParams(dp(3),dp(if(compact) 14 else 18),Gravity.START or Gravity.CENTER_VERTICAL),
        )
        val labelView=text(
            label,
            when {
                util -> if(compact) 12.5f else 13.5f
                compact -> 13.5f
                else -> 15f
            },
            TvStyle.colors.textSecondary,
        ).apply {
            gravity=Gravity.CENTER_VERTICAL
            setPadding(dp(17),0,dp(8),0)
            maxLines=1
            ellipsize=TextUtils.TruncateAt.END
        }
        item.addView(labelView,FrameLayout.LayoutParams(-1,-1))
        fun paint(focused: Boolean) {
            bar.visibility=if(active) View.VISIBLE else View.INVISIBLE
            item.background=when {
                focused -> TvStyle.shape(this,TvStyle.colors.surfaceRaised,TvStyle.colors.focusRing,TvMetric.RADIUS_M)
                active -> TvStyle.shape(this,withAlpha(TvStyle.colors.textPrimary,.06f),Color.TRANSPARENT,TvMetric.RADIUS_M)
                else -> null
            }
            labelView.setTextColor(
                when {
                    focused || active -> TvStyle.colors.textPrimary
                    util -> TvStyle.colors.textTertiary
                    else -> TvStyle.colors.textSecondary
                },
            )
            labelView.setTypeface(
                Typeface.create(
                    "sans-serif",
                    if(active) Typeface.BOLD else Typeface.NORMAL,
                ),
            )
        }
        paint(false)
        item.setOnFocusChangeListener { _,focused ->
            paint(focused)
            if(focused) revealCatalogNavForFocus()
        }
        item.setOnClickListener { onPick() }
        rail.addView(item,LinearLayout.LayoutParams(-1,dp(if(util) utilHeight else itemHeight)))
        items+=item
        nav+=item
        when(tabId) {
            null -> Unit
            9 -> { navByTab[3]=item; navByTab[4]=item }
            1 -> { navByTab[1]=item; searchNavShortcut=item }
            else -> navByTab[tabId]=item
        }
        return item
    }

    fun activeOf(tabId: Int): Boolean = when {
        pushPlaybackPageActive -> tabId == -1
        tabId == -1 -> false
        tabId == 9 -> tab == 3 || tab == 4
        tabId == 8 -> screen == "third_source"
        else -> tabId == tab
    }

    addItem("首页",activeOf(0),false,0) { switchTab(0) }
    addItem("剧场",activeOf(7),false,7) { switchTab(7) }
    addItem("排行榜",activeOf(2),false,2) { switchTab(2) }
    addItem("追剧",activeOf(9),false,9) { switchTab(9) }
    rail.addView(hairline(),lp(-1,dp(1)).apply { topMargin=dp(if(compact) 4 else 10); bottomMargin=dp(if(compact) 4 else 10) })
    addItem("搜索",activeOf(1),false,1) { switchTab(1) }
    addItem("我的",activeOf(6),false,6) { switchTab(6) }

    rail.addView(View(this),lp(-1,0).apply { weight=1f })
    rail.addView(hairline(),lp(-1,dp(1)).apply { bottomMargin=dp(if(compact) 4 else 8) })
    // 四叶接口状态灯 + 随机播放：顶栏时代它是右上角的一个小入口，竖栏里放在工具区顶部，
    // 保持"看一眼接口是否正常"的能力，同时仍然可以按确定键直接随便看一部。
    val randomItem=FrameLayout(this).apply {
        id=View.generateViewId(); isFocusable=true; isFocusableInTouchMode=true; isClickable=true
        contentDescription="随便看看"
    }
    val flower=createApiStatusFlower()
    randomItem.addView(flower,FrameLayout.LayoutParams(dp(if(compact) 18 else 22),dp(if(compact) 18 else 22),Gravity.CENTER))
    fun paintRandom(focused: Boolean) {
        randomItem.background=when {
            focused -> TvStyle.shape(this,TvStyle.colors.surfaceRaised,TvStyle.colors.focusRing,TvMetric.RADIUS_M)
            else -> null
        }
        flower.alpha=if(focused) 1f else .78f
    }
    paintRandom(false)
    randomItem.setOnFocusChangeListener { _,focused -> paintRandom(focused) }
    randomItem.setOnClickListener { showRandomPicks() }
    rail.addView(randomItem,LinearLayout.LayoutParams(-1,dp(if(compact) 26 else 32)))
    items+=randomItem
    nav+=randomItem
    addItem("设置",activeOf(5),true,5) { switchTab(5) }
    addItem("推送播放",activeOf(-1),true,-1) { showPushPlayback() }
    val showThirdSource=getSharedPreferences("appearance-settings-v1",Context.MODE_PRIVATE)
        .getBoolean("show_third_source",false)
    if(showThirdSource) {
        addItem("片源",activeOf(8),true,8) {
            pushPlaybackPageActive=false; closePushPlaybackService(); openThirdSource()
        }
    }

    // 竖栏内部是一条连续的上下焦点链；左右交给系统的空间搜索，进内容区。
    items.forEachIndexed { index,item ->
        item.nextFocusUpId=items.getOrNull(index-1)?.id ?: item.id
        item.nextFocusDownId=items.getOrNull(index+1)?.id ?: item.id
    }
}
