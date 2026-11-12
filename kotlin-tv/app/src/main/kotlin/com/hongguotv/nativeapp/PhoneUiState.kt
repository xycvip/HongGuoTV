// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import com.hongguotv.core.Detail
import com.hongguotv.core.Series

internal enum class PhonePage { CATALOG, DETAIL }

internal class PhoneUiState {
    var tab by mutableStateOf(0)
    var useLegacyPage by mutableStateOf(false)
    var page by mutableStateOf(PhonePage.CATALOG)
    var selectedSeries by mutableStateOf<Series?>(null)
    var detail by mutableStateOf<Detail?>(null)
    var catalogRevision by mutableStateOf(0)
    var playerControlsVisible by mutableStateOf(true)
    var playerHost: ComposeView? = null
    var catalogHost: ComposeView? = null
}
