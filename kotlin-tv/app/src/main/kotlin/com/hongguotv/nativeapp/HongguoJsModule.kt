package com.hongguotv.nativeapp

import android.content.Context
import com.hongguotv.quickjs.QuickJsRuntime
import org.json.JSONObject

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

    suspend fun invoke(runtime: QuickJsRuntime, method: String, argumentsJson: String): String {
        require(method.matches(Regex("[A-Za-z_$][A-Za-z0-9_$]*"))) { "Invalid module method" }
        val encodedArguments = JSONObject.quote(argumentsJson)
        val encodedMethod = JSONObject.quote(method)
        val script = """
            (function () {
                const args = JSON.parse($encodedArguments);
                const target = globalThis.$EXPORT_NAME[$encodedMethod];
                if (typeof target !== "function") {
                    throw new Error("模块未导出函数 " + $encodedMethod);
                }
                const result = target.apply(globalThis.$EXPORT_NAME, args);
                return JSON.stringify(result === undefined ? null : result);
            })()
        """.trimIndent()
        return runtime.evaluate(script, "hongguo-invoke.js", asModule = false) as? String ?: "null"
    }

    companion object {
        const val ASSET_PATH = "js/hongguo/main.js"
        const val EXPORT_NAME = "__hongguoModule"
    }
}
