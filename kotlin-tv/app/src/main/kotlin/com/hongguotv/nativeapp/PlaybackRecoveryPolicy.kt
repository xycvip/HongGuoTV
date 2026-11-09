// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

/** 每集最多执行一次同线路重建和一次跨线路切换，防止 N/P 来回循环。 */
internal enum class PlaybackRecoveryStep {
    RETRY_CURRENT_ROUTE,
    SWITCH_ROUTE,
    EXHAUSTED
}

internal class PlaybackRecoveryPolicy {
    private var episodeKey: String = ""
    private var currentRouteRetryUsed = false
    private var routeSwitchUsed = false

    @Synchronized
    fun next(key: String, allowRouteSwitch: Boolean = true): PlaybackRecoveryStep {
        if (episodeKey != key) {
            episodeKey = key
            currentRouteRetryUsed = false
            routeSwitchUsed = false
        }
        if (!currentRouteRetryUsed) {
            currentRouteRetryUsed = true
            return PlaybackRecoveryStep.RETRY_CURRENT_ROUTE
        }
        if (!routeSwitchUsed && allowRouteSwitch) {
            routeSwitchUsed = true
            return PlaybackRecoveryStep.SWITCH_ROUTE
        }
        return PlaybackRecoveryStep.EXHAUSTED
    }

    @Synchronized
    fun isExhaustedFor(key: String): Boolean =
        episodeKey == key && currentRouteRetryUsed && routeSwitchUsed

    @Synchronized
    fun reset() {
        episodeKey = ""
        currentRouteRetryUsed = false
        routeSwitchUsed = false
    }
}
