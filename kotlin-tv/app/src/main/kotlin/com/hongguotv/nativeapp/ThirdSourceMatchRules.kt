// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

/** 跨片源同剧匹配规则：优先匹配标准化剧名与年份，不匹配时沿用首条候选回退。 */
internal object ThirdSourceMatchRules {
    fun bestDetail(
        candidates: List<ThirdDetail>,
        targetName: String,
        targetYear: String?
    ): ThirdDetail? {
        if(candidates.isEmpty()) return null
        val key = ThirdCms.searchKey(targetName, targetYear)
        return candidates.firstOrNull { ThirdCms.searchKey(it.video.name, it.year) == key }
            ?: candidates.firstOrNull()
    }
}
