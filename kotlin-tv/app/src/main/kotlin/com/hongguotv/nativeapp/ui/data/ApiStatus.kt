// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp.ui.data

import androidx.compose.ui.graphics.Color
import com.hongguotv.nativeapp.ui.theme.Gold

/** 单项接口的检测状态。 */
enum class ApiProbeStatus { UNKNOWN, CHECKING, OK, FAILED }

/**
 * 一项接口的最近一次检测结果。
 * 四项固定：首页、搜索、播放、联想，顺序也是品牌四叶的顺时针顺序。
 */
data class ApiProbe(
    val key: String,
    val label: String,
    val status: ApiProbeStatus = ApiProbeStatus.UNKNOWN,
    val detail: String = "尚未检测",
    val elapsedMs: Long = 0L,
    val updatedAt: Long = 0L,
)

private val defaultProbes = listOf(
    ApiProbe("home", "首页"),
    ApiProbe("search", "搜索"),
    ApiProbe("play", "播放"),
    ApiProbe("suggest", "联想"),
)

/** 接口指示器与「设置 · 网络」共用的状态。 */
data class ApiStatusState(
    val probes: List<ApiProbe> = defaultProbes,
    val running: Boolean = false,
) {
    fun probe(key: String): ApiProbe? = probes.firstOrNull { it.key == key }

    val okCount: Int get() = probes.count { it.status == ApiProbeStatus.OK }
    val failedCount: Int get() = probes.count { it.status == ApiProbeStatus.FAILED }

    /** 顶栏 logo 与设置页摘要都用这一句，避免两处口径不一致。 */
    val summary: String
        get() = when {
            running -> "正在检测"
            failedCount > 0 -> "$failedCount 项异常"
            okCount == probes.size -> "全部正常"
            okCount > 0 -> "$okCount / ${probes.size} 正常"
            else -> "尚未检测"
        }

    /** 最差状态决定品牌灯的呼吸色：异常压过检测中，检测中压过正常。 */
    val overall: ApiProbeStatus
        get() = when {
            running -> ApiProbeStatus.CHECKING
            failedCount > 0 -> ApiProbeStatus.FAILED
            okCount == probes.size -> ApiProbeStatus.OK
            probes.any { it.status == ApiProbeStatus.CHECKING } -> ApiProbeStatus.CHECKING
            else -> ApiProbeStatus.UNKNOWN
        }
}

/** 状态色：正常用青玉绿，异常用品牌胭脂红，检测中用鎏金，未检测用低饱和的暖灰金。 */
fun ApiProbeStatus.tint(): Color = when (this) {
    ApiProbeStatus.OK -> Color(0xFF5FE0B2)
    ApiProbeStatus.FAILED -> Color(0xFFFF5D6C)
    ApiProbeStatus.CHECKING -> Gold.Core
    ApiProbeStatus.UNKNOWN -> Color(0xFF8C8273)
}

fun ApiProbeStatus.label(): String = when (this) {
    ApiProbeStatus.OK -> "正常"
    ApiProbeStatus.FAILED -> "异常"
    ApiProbeStatus.CHECKING -> "检测中"
    ApiProbeStatus.UNKNOWN -> "未验证"
}
