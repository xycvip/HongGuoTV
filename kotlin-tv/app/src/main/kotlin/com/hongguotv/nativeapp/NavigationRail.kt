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
    widthDp() >= 1500f -> 136
    widthDp() >= 1100f -> 120
    widthDp() >= 780f -> 104
    else -> 88
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
 * 结构：品牌 → 内容页签（首页/剧场/排行榜/追剧）→ 细线 → 搜索/我的 → 弹性空隙 → 工具区（设置/推送/片源）。
 * 工具区贴底，视觉上和内容页签分开，避免"设置"和"追剧"看起来是同一级内容。
 *
 * 行高不是写死的：先按 540dp（1080p）的舒适值排一遍，装不下就整体降档，
 * 直到最矮 26dp 为止。这样 720p 的 360dp 高屏和 4K 的 1080dp 高屏都能各自排满一屏，
 * 不需要再为某个分辨率单独写一套尺寸。
 */
internal fun MainActivity.renderNavigationRail(railHost: FrameLayout, container: LinearLayout) {
    val showThirdSource = getSharedPreferences(APPEARANCE_PREFS, Context.MODE_PRIVATE)
        .getBoolean("show_third_source", false)
    val utilCount = if (showThirdSource) 3 else 2

    // 品牌行在高度不够时先让位：它是装饰，页签才是功能。
    val tall = heightDp() >= 800f
    var brandHeight = if (heightDp() >= 470f) (if (tall) 68 else 52) else 0
    var itemHeight = if (tall) 58 else 46
    var utilHeight = if (tall) 44 else 34
    var randomHeight = if (tall) 40 else 32
    fun totalHeight() = brandHeight + itemHeight * 6 + utilHeight * utilCount + randomHeight + 2 * 13 + 12
    // 逐档收缩，直到一屏放得下（含上下内边距与两条分隔线）。
    while (totalHeight() > heightDp() - 12 && itemHeight > 26) {
        itemHeight -= 2
        utilHeight = (utilHeight - 2).coerceAtLeast(24)
        randomHeight = (randomHeight - 2).coerceAtLeast(24)
        if (totalHeight() > heightDp() + 40 && brandHeight > 0) brandHeight = 0
    }

    val labelSize = when {
        itemHeight >= 52 -> TvMetric.TEXT_SUBTITLE
        itemHeight >= 44 -> TvMetric.TEXT_LABEL
        itemHeight >= 34 -> 13.5f
        else -> TvMetric.TEXT_CAPTION
    }
    val utilSize = when {
        utilHeight >= 40 -> TvMetric.TEXT_LABEL
        utilHeight >= 32 -> 13.5f
        else -> TvMetric.TEXT_CAPTION
    }
    val horizontalPadding = if (railWidthDp() >= 104) 12 else 8
    val labelPaddingStart = if (itemHeight >= 44) 17 else 14

    val rail = column().apply {
        setPadding(dp(horizontalPadding), dp(if (brandHeight > 0) 18 else 8), dp(horizontalPadding), dp(10))
    }
    railHost.addView(rail, FrameLayout.LayoutParams(-1, -1))

    if (brandHeight > 0) {
        // 品牌行只放字标：竖栏净宽有限，再塞状态灯会把「红果TV」挤成三行。
        val brand = row().apply { setPadding(dp(10), 0, dp(4), dp(brandHeight - 34)) }
        brand.addView(
            View(this).apply { background = TvStyle.shape(this@renderNavigationRail, TvStyle.colors.accent) },
            lp(dp(6), dp(6)).apply { rightMargin = dp(8) },
        )
        // 「红果」用主字重、「TV」压小并降一级灰：一行塞四个字会换行。
        val wordmark = row()
        wordmark.addView(text("红果", TvMetric.TEXT_SUBTITLE, TvStyle.colors.textPrimary).apply {
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            includeFontPadding = false
            maxLines = 1
        }, lp(-2, -2))
        wordmark.addView(text("TV", 10.5f, TvStyle.colors.textTertiary).apply {
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            includeFontPadding = false
            maxLines = 1
            setPadding(dp(4), 0, 0, dp(2))
        }, lp(-2, -2).apply { gravity = Gravity.BOTTOM })
        brand.addView(wordmark, lp(0, -2).apply { weight = 1f })
        rail.addView(brand, lp(-1, dp(brandHeight)))
    }

    val items = mutableListOf<View>()
    fun addItem(
        label: String,
        active: Boolean,
        util: Boolean,
        tabId: Int?,
        onPick: () -> Unit,
    ): FrameLayout {
        val item = FrameLayout(this).apply {
            id = View.generateViewId()
            isFocusable = true; isFocusableInTouchMode = true; isClickable = true
            contentDescription = label
        }
        // 选中标记用左侧 3dp 竖条而不是整块底色：一条细条就够定位，颜色面积小才显得安静。
        val bar = View(this).apply { setBackgroundColor(TvStyle.colors.accent) }
        item.addView(
            bar,
            FrameLayout.LayoutParams(dp(3), dp(if (itemHeight >= 40) 18 else 14), Gravity.START or Gravity.CENTER_VERTICAL),
        )
        val labelView = text(label, if (util) utilSize else labelSize, TvStyle.colors.textSecondary).apply {
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
                    if (active) Typeface.BOLD else Typeface.NORMAL,
                ),
            )
        }
        paint(false)
        item.setOnFocusChangeListener { _, focused -> paint(focused) }
        item.setOnClickListener { onPick() }
        rail.addView(item, LinearLayout.LayoutParams(-1, dp(if (util) utilHeight else itemHeight)))
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

    addItem("首页", activeOf(0), false, 0) { switchTab(0) }
    addItem("剧场", activeOf(7), false, 7) { switchTab(7) }
    addItem("排行榜", activeOf(2), false, 2) { switchTab(2) }
    addItem("追剧", activeOf(9), false, 9) { switchTab(9) }
    rail.addView(
        hairline(),
        lp(-1, dp(1)).apply { topMargin = dp(if (itemHeight >= 40) 10 else 5); bottomMargin = dp(if (itemHeight >= 40) 10 else 5) },
    )
    addItem("搜索", activeOf(1), false, 1) { switchTab(1) }
    addItem("我的", activeOf(6), false, 6) { switchTab(6) }

    rail.addView(View(this), lp(-1, 0).apply { weight = 1f })
    rail.addView(hairline(), lp(-1, dp(1)).apply { bottomMargin = dp(8) })
    // 四叶接口状态灯 + 随机播放：顶栏时代它是右上角的一个小入口，竖栏里放在工具区顶部，
    // 保持"看一眼接口是否正常"的能力，同时仍然可以按确定键直接随便看一部。
    val randomItem = FrameLayout(this).apply {
        id = View.generateViewId(); isFocusable = true; isFocusableInTouchMode = true; isClickable = true
        contentDescription = "随便看看"
    }
    val flower = createApiStatusFlower()
    val flowerSize = (randomHeight * .68f).toInt().coerceAtLeast(18)
    randomItem.addView(flower, FrameLayout.LayoutParams(dp(flowerSize), dp(flowerSize), Gravity.CENTER))
    fun paintRandom(focused: Boolean) {
        randomItem.background = when {
            focused -> TvStyle.shape(this, TvStyle.colors.surfaceRaised, TvStyle.colors.focusRing, TvMetric.RADIUS_M)
            else -> null
        }
        flower.alpha = if (focused) 1f else .78f
    }
    paintRandom(false)
    randomItem.setOnFocusChangeListener { _, focused -> paintRandom(focused) }
    randomItem.setOnClickListener { showRandomPicks() }
    rail.addView(randomItem, LinearLayout.LayoutParams(-1, dp(randomHeight)))
    items += randomItem
    nav += randomItem
    addItem("设置", activeOf(5), true, 5) { switchTab(5) }
    addItem("推送播放", activeOf(-1), true, -1) { showPushPlayback() }
    if (showThirdSource) {
        addItem("片源", activeOf(8), true, 8) {
            pushPlaybackPageActive = false; closePushPlaybackService(); openThirdSource()
        }
    }

    // 竖栏内部是一条连续的上下焦点链；左右交给系统的空间搜索，进内容区。
    items.forEachIndexed { index, item ->
        item.nextFocusUpId = items.getOrNull(index - 1)?.id ?: item.id
        item.nextFocusDownId = items.getOrNull(index + 1)?.id ?: item.id
    }
}
