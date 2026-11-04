// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.graphics.Color
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities

/** 系统网络回调适配：将网络变化统一投递到主线程并交给 Activity 当前状态处理。 */
internal fun MainActivity.createNetworkCallback(): ConnectivityManager.NetworkCallback =
    object : ConnectivityManager.NetworkCallback() {
        private fun changed() {
            main.post {
                // A callback already in flight may enqueue work after unregister/cleanup.
                if (isDestroyed) return@post
                val online = hasValidatedInternet()
                if (!online) {
                    if (connectivity.isActiveNetworkMetered || !networkAvailable()) cancelPrefetch()
                    if (!apiNetworkUnavailable) {
                        apiNetworkUnavailable = true
                        apiStatusCheckStarted = false
                        val failed = Color.rgb(235, 75, 75)
                        listOf("首页", "搜索", "播放", "联想").forEach {
                            setApiStatus(it, failed, "网络不可用")
                        }
                    }
                } else if (apiNetworkUnavailable) {
                    apiNetworkUnavailable = false
                    apiStatusCheckStarted = false
                    if (library.isLoaded && !isDestroyed && tab == 0) runAutomaticApiChecks()
                }
                if (foreground && screen == "player" && playError && autoRecovery) scheduleRecovery()
            }
        }

        override fun onAvailable(network: Network) = changed()
        override fun onLost(network: Network) = changed()
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = changed()
        override fun onBlockedStatusChanged(network: Network, blocked: Boolean) = changed()
    }
