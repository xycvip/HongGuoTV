// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

/**
 * 首页顶部主推位。
 *
 * 改造前首屏第一眼是一排 132dp 的小卡，右侧空出一大片，整体显得"零碎"而不是"大气"。
 * 电视端的体量感来自「一个占满宽度的画面 + 一行大字」，所以把"接着看"的第一条提到这里。
 *
 * 配色有意固定为亮色（墨黑渐隐 + 近白文字），不跟主题走：底下永远是一张彩色照片，
 * 浅色主题的深棕文字压上去读不出来。同理，进度条用暖橙而不是 accent 令牌。
 */
internal class HomeHeroView(
    context: Context,
    private val loadCover: (ImageView,String)->Unit,
    private val onOpen: (HomeScreen.Entry)->Unit
): FrameLayout(context) {
    private fun dp(n: Int)=(n*resources.displayMetrics.density).toInt()
    /** 矮屏（720p 电视）上主推位只有 150dp 高，字号与间距必须同步收一档。 */
    private val compact=resources.displayMetrics.heightPixels/resources.displayMetrics.density<430f
    private var entry: HomeScreen.Entry?=null

    private val image=ImageView(context).apply {
        scaleType=ImageView.ScaleType.CENTER_CROP
        importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    // 左侧横向压暗：标题落在画面左半边，不压暗就会碰到浅色人物/花字。
    private val scrimLeft=View(context).apply {
        background=GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(withAlpha(TvPalette.INK_950,.96f),withAlpha(TvPalette.INK_950,.82f),withAlpha(TvPalette.INK_950,0f))
        )
    }
    // 底部纵向压暗：让主推位和下方的货架平滑过渡，而不是硬切一条边。
    private val scrimBottom=View(context).apply {
        background=GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(withAlpha(TvPalette.INK_950,0f),withAlpha(TvPalette.INK_950,.96f))
        )
    }
    private val focusBar=View(context).apply {
        background=TvStyle.shape(context,TvStyle.colors.accent,radius=2)
        alpha=.35f
    }
    private val eyebrow=TextView(context).apply {
        text="继续观看"; textSize=TvMetric.TEXT_CAPTION; setTextColor(TvPalette.EMBER_300); includeFontPadding=false
        letterSpacing=.09f; typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL)
    }
    private val title=TextView(context).apply {
        textSize=if(compact) TvMetric.TEXT_TITLE else TvMetric.TEXT_DISPLAY
        setTextColor(TvPalette.INK_100); includeFontPadding=false
        setLines(1); ellipsize=TextUtils.TruncateAt.END
        letterSpacing=.015f
        typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL)
    }
    private val meta=TextView(context).apply {
        textSize=TvMetric.TEXT_BODY; setTextColor(TvPalette.INK_300); includeFontPadding=false
        setLines(1); ellipsize=TextUtils.TruncateAt.END
    }
    private val progress=ProgressBar(context,null,android.R.attr.progressBarStyleHorizontal).apply {
        max=100; progressTintList=android.content.res.ColorStateList.valueOf(TvPalette.EMBER_400)
        // 未播放部分给一条半透明轨道：进度不再是"孤零零一条橙线"，走完/没走完一目了然。
        progressBackgroundTintList=android.content.res.ColorStateList.valueOf(0x33FFFFFF)
    }
    private val hint=TextView(context).apply {
        text="按「确定」继续播放"; textSize=TvMetric.TEXT_CAPTION; setTextColor(TvPalette.INK_400); includeFontPadding=false
    }
    private val content=LinearLayout(context).apply {
        orientation=LinearLayout.VERTICAL; gravity=Gravity.BOTTOM
        setPadding(dp(if(compact) 22 else 40),0,dp(if(compact) 22 else 40),dp(if(compact) 18 else 30))
        addView(eyebrow,LinearLayout.LayoutParams(-2,-2))
        addView(title,LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(if(compact) 6 else 10) })
        addView(meta,LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(if(compact) 5 else 8) })
        addView(progress,LinearLayout.LayoutParams(dp(if(compact) 180 else 260),dp(4)).apply { topMargin=dp(if(compact) 10 else 15) })
        addView(hint,LinearLayout.LayoutParams(-2,-2).apply { topMargin=dp(if(compact) 7 else 10) })
    }

    init {
        isFocusable=true; isFocusableInTouchMode=true
        // 主推位落在内容区里（base() 已留 5% 边距），左右不是出血到屏幕边缘，
        // 所以按大卡片处理：圆角 + 裁剪，而不是一块方图贴在左上角。
        background=TvStyle.shape(context,TvStyle.colors.background,radius=20)
        clipToOutline=true
        // 焦点时封面要能微微放大，不能被自己裁掉
        clipChildren=false
        addView(image,FrameLayout.LayoutParams(-1,-1))
        addView(scrimLeft,FrameLayout.LayoutParams(-1,-1))
        addView(scrimBottom,FrameLayout.LayoutParams(-1,-1))
        addView(content,FrameLayout.LayoutParams(-1,-1))
        addView(focusBar,FrameLayout.LayoutParams(dp(4),dp(if(compact) 44 else 58),Gravity.START or Gravity.BOTTOM).apply { bottomMargin=dp(if(compact) 56 else 86) })
        setOnClickListener { entry?.let(onOpen) }
        setOnFocusChangeListener { _,focused ->
            focusBar.animate().alpha(if(focused) 1f else .35f).setDuration(140).start()
            image.animate().scaleX(if(focused) 1.03f else 1f).scaleY(if(focused) 1.03f else 1f)
                .setDuration(240).setInterpolator(android.view.animation.DecelerateInterpolator()).start()
        }
    }

    fun bind(next: HomeScreen.Entry) {
        entry=next
        image.setImageDrawable(null)
        loadCover(image,next.series.cover)
        title.text=next.series.title
        val tags=next.series.tags.split('·','|','，',',','、').map(String::trim)
            .filter { it.isNotBlank() && !it.all(Char::isDigit) }.distinct().take(2).joinToString(" · ")
        meta.text=listOf(next.label,tags).filter { it.isNotBlank() }.joinToString("  ·  ")
        val p=next.progress
        progress.progress=p ?: 0
        progress.visibility=if(p==null) INVISIBLE else VISIBLE
        contentDescription="${next.series.title}，${next.label}"
    }

    /**
     * 高度跟宽度走。1080p 电视的内容区是 788dp 宽、约 540dp 高，0.30 得到 236dp，
     * 首屏能看到完整主推位加一整排海报。
     *
     * 下限必须跟着屏幕高度走：720p 电视只有 360dp 高，固定 240dp 的下限会让主推位
     * 一口吃掉三分之二屏，海报只能露出半行——那是"看起来大气，实际没法用"。
     */
    override fun onMeasure(widthMeasureSpec: Int,heightMeasureSpec: Int) {
        val width=MeasureSpec.getSize(widthMeasureSpec)
        val screenDp=resources.displayMetrics.heightPixels/resources.displayMetrics.density
        val minHeight=if(screenDp<430f) dp(150) else dp(220)
        val height=(width*.30f).toInt().coerceIn(minHeight,dp(400))
        super.onMeasure(widthMeasureSpec,MeasureSpec.makeMeasureSpec(height,MeasureSpec.EXACTLY))
    }
}
