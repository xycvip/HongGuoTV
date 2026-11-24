// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

/** Shared season-title normalization and Chinese season-number parsing. */
internal fun baseName(title: String)=title.replace(SeasonPattern,"").trim().trim('：',':','-','·',' ')

private val SeasonPattern=Regex("第([0-9零〇一二三四五六七八九十百千]+)(?:季|部)")
fun seasonNumber(title: String): Int {
    val matched=SeasonPattern.find(title)?.groupValues?.get(1) ?: return Int.MAX_VALUE
    return chineseNumber(matched)
}
private fun chineseNumber(text: String): Int {
    text.toIntOrNull()?.let { return it }
    val digits=mapOf('一' to 1,'二' to 2,'三' to 3,'四' to 4,'五' to 5,'六' to 6,'七' to 7,'八' to 8,'九' to 9)
    val ten=text.indexOf('十')
    if(ten<0) return text.singleOrNull()?.let { digits[it] } ?: Int.MAX_VALUE
    val high=if(ten==0) 1 else digits[text.first()] ?: 1
    val low=if(ten==text.lastIndex) 0 else digits[text.last()] ?: 0
    return high*10+low
}

/**
 * 季序号的界面文案：1 → 第一季，12 → 第十二季。
 * 系列剧卡片只留「第几季」，完整剧名放副标题，列表扫读更干净。
 */
fun seasonLabel(number: Int): String {
    if (number <= 0) return "系列"
    if (number > 99) return "第${number}季"
    val digits = "零一二三四五六七八九"
    val label = when {
        number < 10 -> digits[number].toString()
        number == 10 -> "十"
        number < 20 -> "十" + digits[number % 10]
        number % 10 == 0 -> digits[number / 10].toString() + "十"
        else -> digits[number / 10].toString() + "十" + digits[number % 10]
    }
    return "第${label}季"
}
