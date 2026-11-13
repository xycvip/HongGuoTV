package com.hongguotv.nativeapp

import com.hongguotv.core.CloudProgress
import com.hongguotv.core.ContentType
import com.hongguotv.core.Genre
import com.hongguotv.core.Series
import android.view.View
import android.widget.TextView

internal class HomeUiState {
    var homePosition: HomeScreen.Position? = null
    var scrollPositions = mutableMapOf<String, Int>()
    var homeScreen: HomeScreen? = null
    var selectionPreview: SeriesPreview? = null
    var homeFromCache = false
    var homeUpdateCount = 0
    var homeContentLogged = false
    var pendingCloudProgress: CloudProgress? = null
    var homeUpdates: TextView? = null
    var clockView: TextView? = null
    var clockTick: Runnable? = null
    val typeButtons = mutableMapOf<ContentType, View>()
    var genre: Genre? = null
    var genreButton: View? = null
    var genreClear: View? = null
    val homeShelfLoads = mutableSetOf<ContentType>()
    val queuedHomeShelfRefresh = mutableSetOf<ContentType>()
    var initialCatalogPending = true
    var restoreStoppedPage = false
    var pendingDetail: Series? = null
    var detailAutoplay = true
    var libraryLoadError: Throwable? = null
}
