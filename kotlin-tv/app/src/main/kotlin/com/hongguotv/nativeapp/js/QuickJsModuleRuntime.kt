package com.hongguotv.nativeapp.js

import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.function
import com.dokar.quickjs.evaluate
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Executes one CommonJS-style remote module in QuickJS.
 *
 * A runtime instance is confined to its dispatcher. Do not share it between concurrent
 * requests; create one per module worker and close it when that worker is retired.
 */
class QuickJsModuleRuntime(
    private val host: JsHostBridge,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1)
) : AutoCloseable {
    private var engine: QuickJs? = null
    private var loaded = false
    private var exportedNames: Set<String> = emptySet()

    suspend fun load(source: String) = withContext(dispatcher) {
        check(!loaded) { "JavaScript module already loaded" }
        val js = QuickJs.create(dispatcher)
        js.evaluationTimeoutMillis = 15_000L
        js.memoryLimit = 64L * 1024L * 1024L
        try {
            HongGuoHostMethods.names.forEach { method ->
                js.function("__hongguo_host_$method") { args ->
                    host.invoke(method, args.toList())
                }
            }
            val hostObject = HongGuoHostMethods.names.joinToString(",") { method ->
                "$method: (...args) => __hongguo_host_$method(...args)"
            }
            js.evaluate<Unit>("globalThis.host = {$hostObject};", filename = "hongguo-host.js")

            val bootstrap = """
                (function () {
                  const module = { exports: {} };
                  const exports = module.exports;
                  const hostRef = globalThis.host;
                  (function (module, exports, host) {
                    $source
                  })(module, exports, hostRef);
                  globalThis.__hongguoModule = module.exports;
                  return JSON.stringify(Object.keys(module.exports || {}));
                })();
            """.trimIndent()
            val namesJson = js.evaluate<String>(bootstrap, filename = "hongguo-main.js")
            exportedNames = JSONObject("""{"names":$namesJson}""")
                .getJSONArray("names").let { array ->
                    (0 until array.length()).map { array.getString(it) }.toSet()
                }
            engine = js
            loaded = true
        } catch (error: Throwable) {
            js.close()
            throw error
        }
    }

    fun exports(): Set<String> = exportedNames

    suspend fun call(exportName: String, argumentsJson: String = "[]"): String =
        withContext(dispatcher) {
            check(loaded) { "JavaScript module is not loaded" }
            require(exportName in exportedNames) { "Unknown JavaScript export: $exportName" }
            val safeName = JSONObject.quote(exportName)
            val safeArgs = argumentsJson
            require(safeArgs.trim().startsWith("[") && safeArgs.trim().endsWith("]")) {
                "Arguments must be a JSON array"
            }
            // Validate before embedding so callers cannot inject executable source.
            JSONObject("""{"args":$safeArgs}""")
            val script = """
                (function () {
                  const fn = globalThis.__hongguoModule[$safeName];
                  if (typeof fn !== "function") throw new Error("Export is not callable");
                  const args = $safeArgs;
                  const result = fn.apply(globalThis.__hongguoModule, args);
                  if (result && typeof result.then === "function")
                    throw new Error("This module export returned a Promise; async bridge is not enabled");
                  return JSON.stringify(result === undefined ? null : result, (key, value) =>
                    typeof value === "bigint" ? value.toString() : value);
                })();
            """.trimIndent()
            engine!!.evaluate<String>(script, filename = "hongguo-call.js")
        }

    override fun close() {
        val current = engine ?: return
        engine = null
        loaded = false
        exportedNames = emptySet()
        current.close()
    }
}
