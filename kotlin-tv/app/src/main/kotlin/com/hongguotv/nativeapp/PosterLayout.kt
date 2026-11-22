// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context

/** 外观类偏好统一放这一个文件里，避免每处各写一遍 prefs 名和 key。 */
internal const val APPEARANCE_PREFS = "appearance-settings-v1"
private const val POSTER_COLUMNS_KEY = "poster_columns"

/**
 * 海报列数。
 *
 * 改造前列数写死在代码里（`gridColumns = 5`），结果是一套数字对付所有屏幕：
 * 1080p 电视上单张 148dp 正好，720p 电视上只剩 90dp——剧名挤成两三个字一行；
 * 4K 电视上又觉得一排太空、海报太薄。
 *
 * 现在列数由「内容区实际可用宽度」决定：竖栏宽度、页面内边距、卡片间距都算进去，
 * 再按单张海报的目标宽度反推能放几列。用户也可以在外观设置里固定列数，
 * 固定值同样会被可用宽度约束——选了 7 列但屏幕只放得下 4 列时，按 4 列排，
 * 不会出现半张卡被裁掉。
 */
internal object PosterLayout {
    /** 0 = 自动。 */
    const val AUTO = 0
    const val MIN_COLUMNS = 2
    const val MAX_COLUMNS = 7

    /** 自动档的上限：真机反馈 6/7 列海报太碎，自适应默认最多给到 5 列。 */
    const val AUTO_MAX_COLUMNS = 5

    /** 单张海报的目标宽度：低于 140dp，剧名开始被截成两三个字。 */
    private const val TARGET_CARD_DP = 136f

    /** 手动选高列数时，仍要保证单张不小于这个宽度。 */
    private const val MIN_CARD_DP = 112f

    /** 卡片之间的实际水平占用：网格右间距 12dp + 卡片左右各 3dp 内缩。 */
    private const val GAP_DP = 18f

    /** 设置里可选的档位，与文案一一对应。 */
    val choices = listOf(AUTO, 3, 4, 5, 6, 7)

    private fun prefs(context: Context) = context.getSharedPreferences(APPEARANCE_PREFS, Context.MODE_PRIVATE)

    fun stored(context: Context): Int = prefs(context).getInt(POSTER_COLUMNS_KEY, AUTO)

    fun save(context: Context, value: Int) {
        prefs(context).edit().putInt(POSTER_COLUMNS_KEY, value).apply()
    }

    /**
     * 自动列数。矮屏（720p 电视只有 360dp 高）用更小的目标宽度，
     * 否则一排 3 张、单张 148dp 会把一屏高度占满，露不出第二排。
     */
    fun autoColumns(contentWidthDp: Float, screenHeightDp: Float): Int {
        val target = if (screenHeightDp < 430f) 134f else TARGET_CARD_DP
        val fit = ((contentWidthDp + GAP_DP) / (target + GAP_DP)).toInt()
        return fit.coerceIn(MIN_COLUMNS, AUTO_MAX_COLUMNS)
    }

    /** 这一屏最多放得下几列（保证单张不小于 [MIN_CARD_DP]）。 */
    fun affordableColumns(contentWidthDp: Float): Int =
        ((contentWidthDp + GAP_DP) / (MIN_CARD_DP + GAP_DP)).toInt().coerceIn(MIN_COLUMNS, MAX_COLUMNS)

    /** 最终生效列数：自动值，或手动值被可用宽度夹一次。 */
    fun resolve(context: Context, contentWidthDp: Float, screenHeightDp: Float): Int {
        val auto = autoColumns(contentWidthDp, screenHeightDp)
        val stored = stored(context)
        if (stored == AUTO) return auto
        return minOf(stored, affordableColumns(contentWidthDp)).coerceAtLeast(MIN_COLUMNS)
    }

    /**
     * 设置行文案。选了但放不下时明确写出来，不然用户会以为设置没生效。
     */
    fun label(context: Context, contentWidthDp: Float, screenHeightDp: Float): String {
        val stored = stored(context)
        if (stored == AUTO) return "自动（${autoColumns(contentWidthDp, screenHeightDp)} 列）"
        val actual = resolve(context, contentWidthDp, screenHeightDp)
        return if (actual == stored) "$stored 列" else "$stored 列（本屏最多 $actual 列）"
    }

    fun choiceLabels(contentWidthDp: Float, screenHeightDp: Float): List<String> {
        val affordable = affordableColumns(contentWidthDp)
        return choices.map { value ->
            when {
                value == AUTO -> "自动（${autoColumns(contentWidthDp, screenHeightDp)} 列）"
                value > affordable -> "$value 列 · 本屏放不下"
                else -> "$value 列"
            }
        }
    }
}

/** 当前屏幕、当前内容区宽度下的海报列数。全站只从这里取，别处不再写死 5。 */
internal fun MainActivity.posterColumns(): Int =
    PosterLayout.resolve(this, contentWidthDp(), heightDp())
