// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import okhttp3.Call
import okhttp3.Response
import java.io.IOException
import java.io.InterruptedIOException
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.DisposableHandle
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Cancels only the work owned by one navigation/playback intent, including body reads. */
class RequestScope :
    ThreadContextElement<RequestScope?>,
    AbstractCoroutineContextElement(Key) {
    private var cancelled = false
    private val calls = mutableSetOf<Call>()

    @Synchronized fun cancel() {
        cancelled = true
        calls.forEach { it.cancel() }
        calls.clear()
    }

    @Synchronized private fun attach(call: Call) {
        if (cancelled || Thread.currentThread().isInterrupted) {
            call.cancel()
            throw IOException("请求已取消")
        }
        calls += call
    }

    @Synchronized private fun detach(call: Call) { calls -= call }

    /** 同步作用域，保留给纯线程调用与现有测试。协程调用请使用 runSuspending。 */
    fun <T> run(action: () -> T): T {
        val previous = current.get()
        current.set(this)
        try {
            synchronized(this) { if (cancelled) throw IOException("请求已取消") }
            return action()
        } finally {
            if (previous == null) current.remove() else current.set(previous)
        }
    }

    /**
     * 通过 ThreadContextElement 跨 withContext/QuickJS 线程传播 ThreadLocal。
     * Job 进入 cancelling 状态时立即取消已登记的 OkHttp Call，避免同步 execute
     * 阻塞到网络超时后才观察到协程取消。
     */
    @OptIn(InternalCoroutinesApi::class)
    suspend fun <T> runSuspending(action: suspend () -> T): T {
        val job = currentCoroutineContext()[Job]
        val cancellation: DisposableHandle? = job?.let(::cancelOnCancellation)
        return try {
            withContext(this) {
                synchronized(this@RequestScope) {
                    if (cancelled) throw InterruptedIOException("请求已取消")
                }
                action()
            }
        } finally {
            cancellation?.dispose()
        }
    }

    @OptIn(InternalCoroutinesApi::class)
    fun cancelOnCancellation(job: Job): DisposableHandle =
        job.invokeOnCompletion(
            onCancelling = true,
            invokeImmediately = true,
        ) { cause ->
            if (cause != null) cancel()
        }

    override fun updateThreadContext(context: CoroutineContext): RequestScope? {
        val previous = current.get()
        current.set(this)
        return previous
    }

    override fun restoreThreadContext(context: CoroutineContext, oldState: RequestScope?) {
        if (oldState == null) current.remove() else current.set(oldState)
    }

    @Synchronized private fun ensureActiveInternal() {
        if (cancelled) throw InterruptedIOException("请求已取消")
    }

    companion object Key : CoroutineContext.Key<RequestScope> {
        private val current = ThreadLocal<RequestScope>()
        fun currentScope(): RequestScope? = current.get()

        /** Retry/backoff code must respect both navigation cancellation and thread interruption. */
        fun ensureActive() {
            if (Thread.currentThread().isInterrupted) throw InterruptedIOException("请求已取消")
            current.get()?.ensureActiveInternal()
        }
        fun <T> execute(call: Call, consume: (Response) -> T): T {
            val owner = current.get()
            owner?.attach(call)
            try { return call.execute().use(consume) }
            finally { owner?.detach(call) }
        }
    }
}
