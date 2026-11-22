package com.hongguotv.nativeapp

import android.view.View
import android.widget.TextView

internal class FavoriteUiState {
    val badges = mutableMapOf<String, TextView>()
    var status: TextView? = null
    var check: TextView? = null
    var groupFilter: String? = null
    var filterFirst: TextView? = null
    var filterButtons: List<TextView> = emptyList()
    var filterFocus: Int = -1
}
