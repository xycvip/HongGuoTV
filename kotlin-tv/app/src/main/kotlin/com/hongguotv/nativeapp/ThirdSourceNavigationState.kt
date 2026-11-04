// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

/** 第三方片源页面的导航模式与异步回调代际令牌。 */
internal class ThirdSourceNavigationState {
    var generation: Int = 0
    var mode: ThirdSourceScreenMode = ThirdSourceScreenMode.BROWSE

    fun nextGeneration(): Int {
        generation += 1
        return generation
    }

    /** 纯导航回退规则；null 表示浏览首页应交还宿主页面处理。 */
    fun backTarget(
        hasSearchWord: Boolean,
        detailReturnMode: ThirdSourceScreenMode
    ): ThirdSourceScreenMode? = when(mode) {
        ThirdSourceScreenMode.PLAY -> ThirdSourceScreenMode.DETAIL
        ThirdSourceScreenMode.DETAIL -> detailReturnMode
        ThirdSourceScreenMode.SEARCH -> ThirdSourceScreenMode.BROWSE
        ThirdSourceScreenMode.SOURCES,
        ThirdSourceScreenMode.HISTORY,
        ThirdSourceScreenMode.FAVORITE -> ThirdSourceScreenMode.BROWSE
        ThirdSourceScreenMode.BROWSE -> if(hasSearchWord) ThirdSourceScreenMode.SEARCH else null
    }
}
