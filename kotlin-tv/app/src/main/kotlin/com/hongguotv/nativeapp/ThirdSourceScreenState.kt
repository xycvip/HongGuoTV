// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

/** 三方片源页面的独立导航状态。 */
internal enum class ThirdSourceScreenMode {
    BROWSE, SEARCH, SOURCES, DETAIL, PLAY, HISTORY, FAVORITE
}

/** 三方片源探活指示状态。 */
internal enum class ThirdSourceProbeDot {
    RUNNING, OK, DEAD
}

/** 单个片源最近一次探活的展示状态。 */
internal data class ThirdSourceProbeState(
    val dot: ThirdSourceProbeDot,
    val total: Int,
    val reason: String? = null
)
