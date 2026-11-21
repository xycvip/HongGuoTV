package com.hongguotv.nativeapp

import android.content.Context
import com.hongguotv.quickjs.QuickJsRuntime

/**
 * Loads the bundled RedGuoAPI Hongguo module without modifying its source.
 * The caller must create the runtime and install a complete host adapter first.
 */
internal class HongguoJsModule(context: Context) {
    private val assets = context.applicationContext.assets

    suspend fun loadInto(runtime: QuickJsRuntime) {
        val source = assets.open(ASSET_PATH).bufferedReader(Charsets.UTF_8).use { it.readText() }
        runtime.evaluateCommonJs(
            source = source,
            filename = ASSET_PATH,
            exportName = EXPORT_NAME,
        )
    }

    companion object {
        const val ASSET_PATH = "js/hongguo/main.js"
        const val EXPORT_NAME = "__hongguoModule"
    }
}
