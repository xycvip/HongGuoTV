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
 * 竖排现在是**唯一形态**：所有分辨率、所有页面（播放器除外）都挂同一条栏。
 * 不再有「宽屏竖栏、窄屏顶栏」两套布局——两套布局意味着两套焦点规则、两套字号，
 * 维护成本和用户习惯都在翻倍。窄屏上真正需要变的是**栏有多宽、海报分几列**，不是栏的方向。
 *
 * 竖栏本身不跟随内容滚动、不做隐藏动画：电视上"能看见自己在哪"比"多露两行内容"重要。
 */

/**
 * 竖栏宽度按屏幕宽度分档，而不是固定 96dp。
 *
 * 4K 电视的逻辑宽度可以到 1920dp，96dp 的栏在那里只有 5% 宽，像一条贴边细条；
 * 720p 电视只有 640dp，104dp 会吃掉 16%。分档之后各分辨率都落在 9%~12% 这个区间里。
 */
internal fun MainActivity.railWidthDp(): Int = when {
    widthDp() >= 1500f -> 152
    widthDp() >= 1100f -> 136
    widthDp() >= 780f -> 120
    else -> 96
}

/** 有竖栏时内容区左右内边距之和。窄屏收窄，否则 640dp 屏只剩不到 460dp 可用。 */
internal fun MainActivity.contentPaddingDp(): Float = if (widthDp() >= 780f) 68f else 44f

/** 竖栏占位后剩下的可用宽度，供海报列数、搜索分栏这类需要按宽度算布局的页面使用。 */
internal fun MainActivity.contentWidthDp(): Float =
    (widthDp() - railWidthDp() - contentPaddingDp()).coerceAtLeast(280f)

/** 四项接口状态灯。顶栏和竖栏共用同一个构造，避免两套注册逻辑走偏。 */
internal fun MainActivity.createApiStatusFlower(): ApiStatusFlowerView {
    val flower = ApiStatusFlowerView(this).apply {
        isFocusable = false; isFocusableInTouchMode = false; isClickable = false
        background = null
        contentDescription = "四项接口状态指示灯"
    }
    listOf("首页", "搜索", "播放", "联想").forEach { name ->
        apiStatusViews[name] = flower
        apiStatusResults[name]?.let { (color, detail) ->
            if (detail.startsWith("检测中") || detail.startsWith("重试中")) animateApiStatus(name, color, detail)
            else setApiStatus(name, color, detail)
        }
    }
    catalogNavFlower = flower
    return flower
}

/**
 * 渲染竖栏。
 *
 * 结构：品牌 → 内容页签（首页/剧场/排行榜/追剧）→ 细线 → 搜索/投播 → 弹性空隙 → 栏底（我的/设置/片源）。
 * 高频入口在上方，账户与设置收在栏底，视觉上和内容页签分开。
 *
 * 行高不是写死的：先按 540dp（1080p）的舒适值排一遍，装不下就整体降档，
 * 直到最矮 26dp 为止。这样 720p 的 360dp 高屏和 4K 的 1080dp 高屏都能各自排满一屏，
 * 不需要再为某个分辨率单独写一套尺寸。
 */
internal fun MainActivity.renderNavigationRail(railHost: FrameLayout, container: LinearLayout) {
    val showThirdSource = getSharedPreferences(APPEARANCE_PREFS, Context.MODE_PRIVATE)
        .getBoolean("show_third_source", false)

    // 真机反馈：工具区（我的/设置）字体比主页签明显小一号。改为全部页签同一行高、
    // 同一字号 —— 一条竖栏里只允许一种文字尺度，层级改用位置（上下分隔线）表达。
    val tall = heightDp() >= 800f
    var brandHeight = if (heightDp() >= 470f) (if (tall) 68 else 52) else 0
    var itemHeight = if (tall) 58 else 46
    val itemCount = 8 + if (showThirdSource) 1 else 0
    // 高度预算：品牌行 + 14dp 呼吸位 + 全部页签 + 两条分隔线(30) + 上下内边距(28)。
    fun totalHeight() = brandHeight + 14 + itemHeight * itemCount + 30 + 28
    while (totalHeight() > heightDp() - 12 && itemHeight > 26) {
        itemHeight -= 2
        if (totalHeight() > heightDp() + 40 && brandHeight > 0) brandHeight = 0
    }

    val labelSize = when {
        itemHeight >= 44 -> TvMetric.TEXT_SUBTITLE
        itemHeight >= 34 -> TvMetric.TEXT_LABEL
        else -> 13.5f
    }
    val horizontalPadding = if (railWidthDp() >= 120) 12 else 9
    val labelPaddingStart = if (itemHeight >= 44) 18 else 15

    val rail = column().apply {
        setPadding(dp(horizontalPadding), dp(if (brandHeight > 0) 18 else 8), dp(horizontalPadding), dp(10))
    }
    railHost.addView(rail, FrameLayout.LayoutParams(-1, -1))

    // 切页只认确认键：焦点扫过页签不再自动切页。悬停切页会让"路过"和"想去"
    // 两种意图混在一起，页面连环重建、焦点反复跳，真机反馈已确认撤销。
    // 语义固定：上下=栏内换页签，右=就近进内容，确认=切页。

    var brandItem: FrameLayout? = null
    if (brandHeight > 0) {
        // 品牌位：放大版的四叶接口状态灯。原来缩在工具区 32dp 高的一格里看不清，
        // 移到栏顶当徽标 —— 一眼能看到四项接口是否正常，按下确定键直接随便看一部，
        // 也替掉了纯装饰的字标。
        brandItem = FrameLayout(this).apply {
            id = View.generateViewId()
            isFocusable = true; isFocusableInTouchMode = true; isClickable = true
            contentDescription = "接口状态与随便看看"
            clipChildren = false
            setOnFocusChangeListener { _, focused ->
                background = if (focused) TvStyle.shape(this@renderNavigationRail, TvStyle.colors.surfaceRaised, TvStyle.colors.focusRing, TvMetric.RADIUS_M) else null
            }
            setOnClickListener { showRandomPicks() }
        }
        val flowerBrand = createApiStatusFlower()
        val brandFlowerSize = dp(if (tall) 46 else 38)
        brandItem.addView(flowerBrand, FrameLayout.LayoutParams(brandFlowerSize, brandFlowerSize, Gravity.CENTER))
        // 真机反馈：指示灯和「首页」贴得太近。灯是徽标、页签是功能，
        // 中间留出一格呼吸位，两者从视觉上就分开了。
        rail.addView(brandItem, lp(-1, dp(brandHeight)).apply { bottomMargin = dp(14) })
    }

    val items = mutableListOf<View>()
    fun addItem(
        label: String,
        active: Boolean,
        itemTag: String?,
        tabId: Int?,
        onPick: () -> Unit,
    ): FrameLayout {
        val item = FrameLayout(this).apply {
            id = View.generateViewId()
            if (itemTag != null) tag = itemTag
            isFocusable = true; isFocusableInTouchMode = true; isClickable = true
            contentDescription = label
        }
        // 选中标记用左侧 3dp 竖条而不是整块底色：一条细条就够定位，颜色面积小才显得安静。
        val bar = View(this).apply { setBackgroundColor(TvStyle.colors.accent) }
        item.addView(
            bar,
            FrameLayout.LayoutParams(dp(3), dp(if (itemHeight >= 40) 18 else 14), Gravity.START or Gravity.CENTER_VERTICAL),
        )
        val labelView = text(label, labelSize, TvStyle.colors.textSecondary).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(labelPaddingStart), 0, dp(6), 0)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        item.addView(labelView, FrameLayout.LayoutParams(-1, -1))
        fun paint(focused: Boolean) {
            bar.visibility = if (active) View.VISIBLE else View.INVISIBLE
            item.background = when {
                focused -> TvStyle.shape(this, TvStyle.colors.surfaceRaised, TvStyle.colors.focusRing, TvMetric.RADIUS_M)
                active -> TvStyle.shape(this, TvStyle.colors.railSelected, Color.TRANSPARENT, TvMetric.RADIUS_M)
                else -> null
            }
            labelView.setTextColor(if (focused || active) TvStyle.colors.textPrimary else TvStyle.colors.textSecondary)
            labelView.setTypeface(
                Typeface.create(
                    "sans-serif",
                    if (active) Typeface.BOLD else Typeface.NORMAL,
                ),
            )
        }
        paint(false)
        item.setOnFocusChangeListener { _, focused -> paint(focused) }
        // 只按确认键才切页。
        item.setOnClickListener { onPick() }
        rail.addView(item, LinearLayout.LayoutParams(-1, dp(itemHeight)))
        items += item
        nav += item
        when (tabId) {
            null -> Unit
            9 -> { navByTab[3] = item; navByTab[4] = item }
            1 -> { navByTab[1] = item; searchNavShortcut = item }
            else -> navByTab[tabId] = item
        }
        return item
    }

    /**
     * 当前页高亮。详情页/随机播放这类"临时页面"不高亮任何内容页签——
     * 它们不属于某个标签页，把"首页"点亮反而会误导用户以为已经回首页了。
     */
    fun activeOf(tabId: Int): Boolean = when {
        pushPlaybackPageActive -> tabId == -1
        screen == "third_source" -> tabId == 8
        screen != "catalog" -> false
        tabId == -1 -> false
        tabId == 9 -> tab == 3 || tab == 4
        tabId == 8 -> false
        else -> tabId == tab
    }

    addItem("首页", activeOf(0), null, 0) { switchTab(0) }
    addItem("剧场", activeOf(7), null, 7) { switchTab(7) }
    addItem("排行榜", activeOf(2), null, 2) { switchTab(2) }
    addItem("追剧", activeOf(9), null, 9) { switchTab(9) }
    rail.addView(
        hairline(),
        lp(-1, dp(1)).apply { topMargin = dp(if (itemHeight >= 40) 10 else 5); bottomMargin = dp(if (itemHeight >= 40) 10 else 5) },
    )
    addItem("搜索", activeOf(1), null, 1) { switchTab(1) }
    // 投播（原"推送播放"）紧跟搜索：都是"把内容弄到电视上看"的入口，语义相邻。
    // tag 供推送来源的视频退出时把焦点还回来（pushPlaybackAnchor）。
    addItem("投播", activeOf(-1), "nav:push-playback", -1) { showPushPlayback() }

    rail.addView(View(this), lp(-1, 0).apply { weight = 1f })
    rail.addView(hairline(), lp(-1, dp(1)).apply { bottomMargin = dp(8) })
    // 账户与设置收在栏底：低频但必须好找，设置永远是最下面一格。
    addItem("我的", activeOf(6), null, 6) { switchTab(6) }
    addItem("设置", activeOf(5), null, 5) { switchTab(5) }
    if (showThirdSource) {
        addItem("片源", activeOf(8), null, 8) {
            pushPlaybackPageActive = false; closePushPlaybackService(); openThirdSource()
        }
    }

    // 焦点链统一写在 FocusFrame：竖栏上下自链、左键原地不动；
    // 页面渲染代码不得改写竖栏项的 nextFocusUp/Down（旧横排"下键进内容"已废除）。
    if (brandItem != null) items.add(0, brandItem!!)
    FocusFrame.applyRailChain(items)
}
