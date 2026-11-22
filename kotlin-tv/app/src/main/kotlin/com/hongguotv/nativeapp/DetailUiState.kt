package com.hongguotv.nativeapp

import android.view.View
import android.widget.LinearLayout
import com.hongguotv.core.Detail

internal class DetailUiState {
    var detail: Detail? = null
    var seasonLine: LinearLayout? = null
    var seasonSection: LinearLayout? = null
    val episodeButtons = mutableListOf<View>()
    val relatedCards = mutableListOf<View>()
    var sameNameLoadingId: String = ""
    var episodeIndex: Int = 0
    var group: Int = 0
    var synopsisExpanded: Boolean = false
}
