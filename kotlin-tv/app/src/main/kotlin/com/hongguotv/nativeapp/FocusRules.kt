// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.view.ViewGroup

/**
 * 全局焦点与裁剪策略的唯一入口。
 *
 * 焦点规则此前散落在十几处渲染代码里：竖栏的上下链、页面往竖栏回指、
 * 网格首列左键回栏、顶部横排时代的"下键进内容"…… 任何一处改坏都会让
 * 遥控器焦点乱跳。现在规则收敛到这里，渲染代码只允许引用、不允许另写：
 *
 *  1. **竖栏上下链**只在 [NavigationRail] 建栏时写一次（[applyRailChain]）。
 *     页面渲染一律不得改写竖栏项的 nextFocusUp/Down——旧的横排导航靠
 *     "下键进内容"，换竖栏后这段遗留逻辑会把下键从栏里拽进页面。
 *     竖栏语义固定为：上下=在栏内换页签，右=就近进入页面内容，左=原地不动，
 *     确认=切换页面。
 *  2. **左右回栏**是页面侧规则：海报网格/货架第一列左键回 [MainActivity.navForTab]，
 *     已在 CatalogAdapter/HomeScreen 里实现，本文件不再重复。
 *  3. **裁剪**：焦点环与放大态画在 View 边界之外，任何中间容器默认的
 *     clipChildren 都会把边缘切掉。页面建好后整棵内容树统一放开（[unclipTree]），
 *     渲染代码不再逐个容器手写 clipChildren=false。
 */
internal object FocusRules {

    /**
     * 竖栏内部焦点链：从品牌灯到工具区是一条连续的上下链，
     * 链尾自指，按过头会停在最后一项而不是跳走。
     */
    fun applyRailChain(items: List<android.view.View>) {
        items.forEachIndexed { index, item ->
            item.nextFocusUpId = items.getOrNull(index - 1)?.id ?: item.id
            item.nextFocusDownId = items.getOrNull(index + 1)?.id ?: item.id
            item.nextFocusLeftId = item.id
        }
    }

    /**
     * 页面渲染完成后对整棵内容树放开裁剪。焦点放大 4.5% + 外扩白环
     * 都画在 View 边界之外，边缘卡片不放开裁剪必然缺一角。
     */
    fun unclipTree(view: ViewGroup) {
        view.clipChildren = false
        for (index in 0 until view.childCount) {
            val child = view.getChildAt(index)
            if (child is ViewGroup) unclipTree(child)
        }
    }
}
