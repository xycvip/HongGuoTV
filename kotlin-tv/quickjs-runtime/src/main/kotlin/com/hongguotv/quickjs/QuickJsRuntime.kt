package com.hongguotv.quickjs

import com.dokar.quickjs.ModuleContent
import com.dokar.quickjs.ModuleLoader
import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.moduleLoader
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A lifecycle-scoped QuickJS runtime.
 *
 * The upstream QuickJS engine owns JavaScript execution, coroutine integration,
 * ES-module resolution and native memory management. This class only supplies
 * the application module source and a place to install host bindings.
 */
class QuickJsRuntime private constructor(
    private val engine: QuickJs,
) : AutoCloseable {

    private val closed = AtomicBoolean(false)

    /**
     * Evaluate JavaScript source. Set [asModule] for ES module syntax/imports.
     * Async host bindings registered during creation can be awaited in JS.
     */
    suspend fun <T : Any?> evaluate(
        source: String,
        filename: String = "main.js",
        asModule: Boolean = false,
    ): T {
        check(!closed.get()) { "QuickJS runtime is closed" }
        return engine.evaluate(source, filename, asModule)
    }

    /** Compile source to bytecode using the upstream engine. */
    fun compile(
        source: String,
        filename: String = "main.js",
        asModule: Boolean = false,
    ): ByteArray {
        check(!closed.get()) { "QuickJS runtime is closed" }
        return engine.compile(source, filename, asModule)
    }

    /** Set a per-evaluation JavaScript execution timeout. Zero disables it. */
    var evaluationTimeoutMillis: Long
        get() = engine.evaluationTimeoutMillis
        set(value) {
            check(!closed.get()) { "QuickJS runtime is closed" }
            engine.evaluationTimeoutMillis = value
        }

    /** Release the native QuickJS context. Safe to call more than once. */
    override fun close() {
        if (closed.compareAndSet(false, true)) engine.close()
    }

    companion object {
        /**
         * Create a runtime with on-demand ES module loading.
         *
         * [loadModule] is called synchronously by QuickJS while resolving imports;
         * it must be a fast in-memory lookup and must not call back into this runtime.
         * Perform network downloads before creating the runtime, then provide the
         * resulting local source map here.
         */
        fun create(
            loadModule: (normalizedName: String) -> String?,
            dispatcher: CoroutineDispatcher = Dispatchers.IO,
            installBindings: (QuickJs) -> Unit = {},
        ): QuickJsRuntime {
            val loader: ModuleLoader = moduleLoader {
                load { name ->
                    loadModule(name)?.let(ModuleContent::Source)
                }
            }
            val engine = QuickJs.create(dispatcher, loader)
            try {
                installBindings(engine)
                return QuickJsRuntime(engine)
            } catch (error: Throwable) {
                engine.close()
                throw error
            }
        }
    }
}
