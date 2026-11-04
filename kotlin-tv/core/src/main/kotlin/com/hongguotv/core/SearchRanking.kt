// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import java.util.Locale

/** Search relevance scoring shared by web and app search result aggregation. */
/** Score exact title, prefix, full substring and character coverage in that order. */
    internal fun titleMatchScore(title: String, keyword: String): Int {
        fun normalize(value: String) = value.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }
        val t=normalize(title)
        val q=normalize(keyword)
        if(t.isEmpty() || q.isEmpty()) return 0
        if(t==q) return 1_000_000
        if(t.startsWith(q)) return 900_000-(t.length-q.length).coerceAtMost(100_000)
        if(t.contains(q)) return 800_000-(t.length-q.length).coerceAtMost(100_000)
        if(q.contains(t)) return 250_000-(q.length-t.length).coerceAtMost(100_000)
        // 对非连续匹配按关键词字符覆盖率评分；不再让单个首字匹配的长剧名挤到前面。
        val uniqueQuery=q.toSet()
        val matched=uniqueQuery.count { it in t }
        if(matched==0) return 0
        val coverage=matched*100_000/uniqueQuery.size
        var cursor=0
        var consecutive=0
        var bestConsecutive=0
        for(ch in q) {
            val found=t.indexOf(ch,cursor)
            if(found>=0) { consecutive++; bestConsecutive=maxOf(bestConsecutive,consecutive); cursor=found+1 }
            else consecutive=0
        }
        return 100_000+coverage+bestConsecutive*100-(t.length.coerceAtMost(10_000))
    }
