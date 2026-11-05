// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.graphics.Color
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities

/** 系统网络回调适配：将网络变化统一投递到主线程并交给 Activity 当前状态处理。 */
internal fun MainActivity.createNetworkCallback(): ConnectivityManager.NetworkCallback =
    object : ConnectivityManager.NetworkCallback() {
        private fun changed(reason:String) {
            main.post {
                // A callback already in flight may enqueue work after unregister/cleanup.
                if (isDestroyed) return@post
                val wasUnavailable=apiNetworkUnavailable
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
                val active=connectivity.activeNetwork
                val caps=active?.let { connectivity.getNetworkCapabilities(it) }
                val type=when {
                    caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)==true -> "Wi-Fi"
                    caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)==true -> "以太网"
                    caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)==true -> "移动网络"
                    else -> "无网络/未知"
                }
                debugLog("网络事件：$reason；类型=$type；已验证互联网=$online；页面=$screen；网络状态变化=${wasUnavailable!=apiNetworkUnavailable}")
                if (foreground && screen == "player" && playError && autoRecovery) scheduleRecovery()
            }
        }

        override fun onAvailable(network: Network) = changed("网络可用")
        override fun onLost(network: Network) = changed("网络断开")
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = changed("网络能力变化")
        override fun onBlockedStatusChanged(network: Network, blocked: Boolean) = changed("网络阻止状态变化：blocked=$blocked")
    }
