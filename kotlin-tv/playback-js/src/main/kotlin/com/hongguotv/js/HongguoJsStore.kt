// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.js

import android.content.Context
import okhttp3.OkHttpClient

/**
 * HongguoJs 进程内单例：main.js 只加载一次，字节码内存在跨调用复用
 * （每次取流仍是独立 runtime，见 JsScriptRunner）。
 */
object HongguoJsStore {
    @Volatile
    private var instance: HongguoJs? = null

    fun get(context: Context, http: OkHttpClient): HongguoJs {
        instance?.let { return it }
        return synchronized(this) {
            instance ?: HongguoJs.create(
                context.applicationContext,
                http,
                store = SharedPrefsJsKeyValueStore(context.applicationContext),
            ).also { instance = it }
        }
    }

    /**
     * 本地没有可用脚本时返回 null，不抛异常。
     * 首次安装（APK 已不带 main.js）用它判断是否需要先走引导下载。
     */
    fun getOrNull(context: Context, http: OkHttpClient): HongguoJs? {
        instance?.let { return it }
        return synchronized(this) {
            instance ?: runCatching {
                HongguoJs.create(
                    context.applicationContext,
                    http,
                    store = SharedPrefsJsKeyValueStore(context.applicationContext),
                )
            }.getOrNull()?.also { instance = it }
        }
    }

    /** 热更新落地后调用：丢弃旧实例，下次取流时重新加载（新脚本或上一版兜底）。 */
    fun reload() {
        synchronized(this) { instance = null }
        // 旧脚本解析出的播放地址不再沿用，避免热更新后短暂播放到旧线路。
        HongguoJsStreamSource.clearCache()
    }

    /**
     * Release the session-owned script facade and in-memory bytecode cache when the app's
     * ViewModel is permanently cleared. QuickJS itself is per-call and already closed by
     * JsScriptRunner.finally; the process-wide daemon dispatcher intentionally stays alive
     * so a later Activity launch can create fresh runtimes without executor reinitialization.
     */
    fun release() {
        synchronized(this) { instance = null }
        HongguoJsStreamSource.clearCache()
    }

    /**
     * 重置设备身份：删掉持久化的 device_register 结果。
     * 下次任意脚本调用新建 runtime 时，bootstrap 读不到缓存，vendor 的 Ot() 会重新注册一台新设备。
     * 用于被风控封禁后换身份；SharedPreferences 按 name 共享，这里直接构造 store 操作同一份数据即可。
     */
    fun resetDeviceIdentity(context: Context) {
        SharedPrefsJsKeyValueStore(context.applicationContext)
            .set(JsEngineHost.DEVICE_IDENTITY_KEY, null, null)
    }
}
