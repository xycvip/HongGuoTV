package com.hongguotv.nativeapp

import android.view.View
import android.widget.TextView
import com.hongguotv.core.ComicRanking
import com.hongguotv.core.Genre
import com.hongguotv.core.RankBoard

internal class RankingUiState {
    var ranking: ComicRanking? = null
    var rankBoard: RankBoard = RankBoard.REAL
    /** 记录排行榜上次真正获得焦点的顶部控件，供页面重建后恢复。 */
    var rankFocusTag: String? = null
    var rankSwitch: View? = null
    var rankRefresh: View? = null
    var rankSubtitle: TextView? = null
    var aiCatalog: Boolean = false
    var aiGenre: Genre? = null
    var aiEntry: View? = null
    var aiBack: View? = null
    var aiGenreButton: View? = null
    var genre: Genre? = null
    var genreButton: View? = null
    var genreClear: View? = null
}
