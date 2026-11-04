package com.hongguotv.nativeapp

import android.animation.AnimatorSet
import android.view.View

internal class StartupUiState {
    var launchRandomPlaybackStarted = false
    var startupHomeRefreshStarted = false
    var homeRefreshInFlight = false
    var bootAutoPlaybackPending = false
    var startupAutoPlaybackActive = false
    var startupPreviewEpisodeId: String? = null
    var startupPreviewPlayedMs = 0L
    var startupPreviewLastTickAt = 0L
    var startupRecommendationExpired = false
    var bootSplash: View? = null
    var bootSplashAnimator: AnimatorSet? = null
}
