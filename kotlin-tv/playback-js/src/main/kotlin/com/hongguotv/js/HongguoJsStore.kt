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
            instance ?: HongguoJs.create(context.applicationContext, http).also { instance = it }
        }
    }

    /** 热更新落地后调用：丢弃旧实例，下次取流时重新加载（新脚本或回退内置版）。 */
    fun reload() {
        synchronized(this) { instance = null }
    }
}
