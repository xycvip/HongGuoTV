// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

/** 当前详情会话的数据上下文与页面内 UI 状态。 */
internal class ThirdSourceDetailSessionState {
    var detail: ThirdDetail? = null
    var source: ThirdSource? = null
    var group: Int = 0
    var returnMode: ThirdSourceScreenMode = ThirdSourceScreenMode.BROWSE
    var synopsisExpanded: Boolean = false

    fun clear() {
        detail = null
        source = null
        group = 0
        returnMode = ThirdSourceScreenMode.BROWSE
        synopsisExpanded = false
    }
}
