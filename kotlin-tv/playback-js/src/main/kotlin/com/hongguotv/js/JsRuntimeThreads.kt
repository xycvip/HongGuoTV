// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.js

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * QuickJS 专用线程池。
 *
 * 为什么要自建线程：Android 主线程与 [kotlinx.coroutines.Dispatchers.Default] 的线程栈默认
 * 只有 1MB（32 位进程更紧），而 [com.dokar.quickjs.QuickJs.maxStackSize] 设成了 4MB。
 * QuickJS 的栈溢出检查按「当前 SP − maxStackSize」算边界，边界落在真实栈底之下时检查永远
 * 不触发，稍深的调用链会直接越过线程栈 → SIGSEGV（崩溃点落在 JS_CallInternal /
 * js_async_function_resume），而不是抛出可捕获的异常。
 *
 * 这里给每个线程显式申请 8MB 栈，让 4MB 的 maxStackSize 真正生效：超限变成可捕获异常。
 */
internal object JsRuntimeThreads {
    /**
     * 允许同时存活的 runtime 上限（[JsScriptRunner] 按这个值建信号量）。
     * 从 2 提到 4：冷启动时首页、接口探针（4 项）、榜单会并发发起脚本调用，
     * 2 个 permit 会让榜单排队等待；每个 runtime 内存上限 96MB，4 个仍可控。
     */
    const val MAX_CONCURRENT_RUNTIMES = 4

    /**
     * 线程数大于并发上限：QuickJs 内部还会往同一个 dispatcher 派发任务，
     * 线程数与 permit 数相等时会出现「持有 permit 的调用等不到空闲线程」的自我阻塞。
     */
    private const val THREAD_COUNT = 6
    private const val STACK_SIZE = 8L * 1024 * 1024
    private const val KEEP_ALIVE_SECONDS = 30L

    private val sequence = AtomicInteger(0)

    private val executor = ThreadPoolExecutor(
        THREAD_COUNT,
        THREAD_COUNT,
        KEEP_ALIVE_SECONDS,
        TimeUnit.SECONDS,
        LinkedBlockingQueue(),
    ) { runnable ->
        Thread(null, runnable, "hongguo-js-${sequence.incrementAndGet()}", STACK_SIZE).apply {
            isDaemon = true
        }
    }.apply { allowCoreThreadTimeOut(true) }

    val dispatcher: CoroutineDispatcher = executor.asCoroutineDispatcher()
}
