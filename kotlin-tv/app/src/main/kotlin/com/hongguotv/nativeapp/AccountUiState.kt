// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView

/** 账户页视图与渲染状态；View 引用仅在 Activity 当前生命周期内有效。 */
internal class AccountUiState {
    var contentHost: FrameLayout? = null
    var renderRevision: Int = 0
    var renderedAvatarRevision: Long = -1L
    var qrAutoStarted: Boolean = false
    var qrImageView: ImageView? = null
    var qrPlaceholderView: FrameLayout? = null
    var qrStatusView: TextView? = null
    var qrRefreshView: TextView? = null
    var apiCheckStarted: Boolean = false
    var apiOnline: Boolean? = null
    var apiDotView: TextView? = null
    var apiLabelView: TextView? = null
    var section: Int = 0
}
