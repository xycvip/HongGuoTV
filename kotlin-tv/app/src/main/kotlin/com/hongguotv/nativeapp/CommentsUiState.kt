package com.hongguotv.nativeapp

import android.view.View
import com.hongguotv.core.RequestScope

internal class CommentsUiState {
    var panel: OfficialCommentPanel? = null
    var anchor: View? = null
    var cursor: String = "0"
    var generation: Int = 0
    var listScope: RequestScope? = null
    val replyCursors = mutableMapOf<String, String>()
}
