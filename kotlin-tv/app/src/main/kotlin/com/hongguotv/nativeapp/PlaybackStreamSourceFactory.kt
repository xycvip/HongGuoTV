// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import com.hongguotv.core.StreamSource
import com.hongguotv.js.HongguoJsStore
import com.hongguotv.js.HongguoJsStreamSource
import okhttp3.OkHttpClient

/**
 * 播放取流唯一入口：热更新 JS 线路。
 *
 * N（playback-native）/ P（playback-phoenix）两条原生线路已全部移除，
 * 取流、签名、设备注册全部由 main.js 内的 vendor 完成；本工厂只负责
 * 把进程内单例的 HongguoJs 包成 StreamSource。
 */
internal fun Context.playbackStreamSource(
    http: OkHttpClient,
    notifyOnFallback: Boolean = true,
    allowFallback: Boolean = true,
): StreamSource {
    // 参数保留以兼容旧调用点；JS 单线路没有回落概念。
    val js = HongguoJsStore.get(this, http)
    return HongguoJsStreamSource(js)
}
