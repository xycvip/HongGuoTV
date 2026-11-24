// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.js

import com.dokar.quickjs.QuickJs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.InterruptedIOException
import java.security.MessageDigest

/** 脚本调用结果：[data] 是 API 返回值的 JSON 文本，由调用方按需解析。 */
class JsCallResult(
    val ok: Boolean,
    val data: String?,
    val error: String?,
    val code: String?,
)

/**
 * QuickJS 运行时宿主：一次调用 = 一个独立 runtime。
 *
 * 设计要点（对应 README §宿主怎么调用）：
 *  - 每次调用 QuickJs.create → 注入 host → evaluate(main.js 字节码) → 调 API → close。
 *    崩溃互不影响、无跨调用状态泄漏、无并发锁竞争；
 *  - main.js 编译成 QuickJS 字节码缓存（内存 + 可选磁盘），509KB 源码不重复编译；
 *  - quickjs-kt 的 evaluate 会驱动 JS 微任务直到 Promise 落定（evalAndAwait），
 *    main.js「全部导出 async」的契约因此直接成立，无需手动泵微任务；
 *  - withTimeout 取消协程时会中断 evaluate（1.0.18 起支持），JS 死循环也能掐掉。
 */
class JsScriptRunner(
    private val host: JsEngineHost,
    private val scriptSource: () -> ByteArray,
    private val bytecodeCache: JsBytecodeCache = JsBytecodeCache.memoryOnly(),
    private val jobDispatcher: CoroutineDispatcher = JsRuntimeThreads.dispatcher,
) {
    /** 脚本标识（内容 sha256 前 16 位），用于字节码缓存键与诊断日志。每次调用都要用，缓存一份。 */
    private val digest: String by lazy {
        val bytes = scriptSource()
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes)
        hash.take(8).joinToString("") { "%02x".format(it) }
    }

    fun scriptDigest(): String = digest

    /**
     * 调用 main.js 的导出函数。
     * @param function HongguoAPI 上的函数名（白名单字符校验，防注入）
     * @param argsJson 位置参数数组的 JSON 文本，如 `[{"dramaId":"123"}]`
     */
    suspend fun call(
        function: String,
        argsJson: String,
        timeoutMillis: Long = DEFAULT_TIMEOUT_MS,
    ): JsCallResult {
        require(FUNCTION_NAME.matches(function)) { "非法函数名：$function" }
        return try {
            withTimeout(timeoutMillis) {
                withContext(jobDispatcher) {
                    runtimePermits.withPermit {
                        invokeLocked(function, argsJson)
                    }
                }
            }
        } catch (error: TimeoutCancellationException) {
            JsCallResult(false, null, "脚本执行超时（${timeoutMillis}ms）", "SCRIPT_TIMEOUT")
        }
    }

    private suspend fun invokeLocked(function: String, argsJson: String): JsCallResult {
        val quickJs = QuickJs.create(jobDispatcher)
        return try {
            quickJs.memoryLimit = MEMORY_LIMIT
            // 必须跑在 JsRuntimeThreads 的 8MB 栈线程上，否则这个 4MB 上限不生效（超限变成 SIGSEGV）。
            quickJs.maxStackSize = 4L * 1024 * 1024
            quickJs.installHost()
            val bytecode = loadBytecode(quickJs)
            quickJs.evaluate<Any?>(bytecode)
            val payload = callFunction(quickJs, function, argsJson)
            JsCallResult(true, payload, null, null)
        } catch (error: CancellationException) {
            // 页面销毁 / 请求取消：向上传播，不伪装成脚本结果。
            throw error
        } catch (error: Throwable) {
            JsCallResult(false, null, error.message ?: error.javaClass.simpleName, classify(error))
        } finally {
            runCatching { quickJs.close() }
        }
    }

    private fun QuickJs.installHost() {
        with(host) { install() }
    }

    private fun loadBytecode(quickJs: QuickJs): ByteArray {
        val key = scriptDigest() + "@" + quickJs.version
        bytecodeCache.get(key)?.let { return it }
        val source = String(scriptSource(), Charsets.UTF_8)
        val bytecode = quickJs.compile(source, filename = "main.js")
        bytecodeCache.put(key, bytecode)
        return bytecode
    }

    /**
     * 三段式调用：注入参数 → 执行 async 导出（evaluate 内部等 Promise 落定）→ 取回 JSON。
     * 用全局变量传参而不是字符串拼接函数体，参数永远走 JSON 解析路径。
     */
    private suspend fun callFunction(quickJs: QuickJs, function: String, argsJson: String): String {
        val args = runCatching {
            org.json.JSONTokener(argsJson).nextValue() as? org.json.JSONArray
        }.getOrNull() ?: org.json.JSONArray()
        quickJs.evaluate<Any?>("globalThis.__thlArgs = $args;")
        val runner = """
            (function () {
              var api = globalThis.HongguoAPI;
              if (!api || typeof api["$function"] === 'undefined') {
                globalThis.__thlResult = { ok: false, error: '脚本缺少导出：$function', code: 'MISSING_EXPORT' };
                return;
              }
              // 属性型导出（如 meta: {id,name,version}）直接返回值，不走调用路径。
              if (typeof api["$function"] !== 'function') {
                globalThis.__thlResult = { ok: true, data: api["$function"] };
                return;
              }
              var promise;
              try {
                promise = api["$function"].apply(null, globalThis.__thlArgs);
              } catch (error) {
                globalThis.__thlResult = { ok: false, error: String((error && error.message) || error), code: error && error.code != null ? String(error.code) : null };
                return;
              }
              Promise.resolve(promise).then(function (value) {
                globalThis.__thlResult = { ok: true, data: value === undefined ? null : value };
              }, function (error) {
                globalThis.__thlResult = { ok: false, error: String((error && error.message) || error), code: error && error.code != null ? String(error.code) : null };
              });
            })();
        """.trimIndent()
        quickJs.evaluate<Any?>(runner)
        val raw = quickJs.evaluate<String>(
            "globalThis.__thlResult === undefined ? 'null' : JSON.stringify(globalThis.__thlResult)"
        )
        val json = org.json.JSONObject(raw)
        if (!json.optBoolean("ok")) {
            throw JsCallException(
                json.optString("error").ifBlank { "脚本调用失败" },
                json.optString("code").ifBlank { "SCRIPT_ERROR" },
            )
        }
        return json.opt("data")?.toString() ?: "null"
    }

    private fun classify(error: Throwable): String = when (error) {
        is InterruptedIOException -> "CANCELLED"
        is java.util.concurrent.CancellationException -> "CANCELLED"
        is JsCallException -> error.code
        else -> "SCRIPT_ERROR"
    }

    /** 脚本主动抛出的业务错误（带 vendor 的 code，如 INVALID_CHAPTER / PLAYBACK_FAILED）。 */
    class JsCallException(message: String, val code: String) : RuntimeException(message)

    companion object {
        const val DEFAULT_TIMEOUT_MS = 25_000L
        private val FUNCTION_NAME = Regex("[A-Za-z_$][A-Za-z0-9_$]{0,63}")

        /** 单 runtime 内存上限。32 位进程地址空间紧张，压到 96MB（原 256MB）。 */
        private const val MEMORY_LIMIT = 96L * 1024 * 1024

        /** 同时存活的 runtime 最多 2 个：冷启动时首页和接口探针会并发发起脚本调用。 */
        private val runtimePermits = Semaphore(JsRuntimeThreads.MAX_CONCURRENT_RUNTIMES)
    }
}
