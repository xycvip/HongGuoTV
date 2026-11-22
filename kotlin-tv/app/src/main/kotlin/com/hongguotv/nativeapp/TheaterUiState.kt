// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.view.View
import android.widget.LinearLayout
import com.hongguotv.core.*

/**
 * 剧场筛选 UI 状态。
 *
 * 筛选条件按内容类型分别缓存；Activity 暂时通过委托属性保持既有调用接口，
 * 让筛选渲染逻辑可以在后续步骤中独立迁移。
 */
internal class TheaterUiState {
    var filterHost: LinearLayout? = null
    var focusTag: String? = null
    val filtersByType = mutableMapOf<String, List<TheaterFilterGroup>>()
    var contentType: ContentType? = null
    val selections = mutableMapOf<String, String>()
}
