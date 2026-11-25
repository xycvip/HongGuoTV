package com.hongguotv.js

import com.hongguotv.core.RequestScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import okio.Timeout
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class RequestScopeCoroutineTest {
    @Test
    fun `request scope follows coroutine dispatcher switches`() = runBlocking {
        val scope = RequestScope()
        scope.runSuspending {
            assertSame(scope, RequestScope.currentScope())
            withContext(Dispatchers.Default) {
                assertSame(scope, RequestScope.currentScope())
                withContext(Dispatchers.IO) {
                    assertSame(scope, RequestScope.currentScope())
                }
            }
        }
    }

    @Test
    fun `cancelling coroutine cancels synchronous okhttp call immediately`() = runBlocking {
        val scope = RequestScope()
        val call = BlockingCall()
        val job = launch(Dispatchers.Default) {
            scope.runSuspending {
                withContext(Dispatchers.IO) {
                    runCatching {
                        RequestScope.execute(call) {
                            error("cancelled call must not return a response")
                        }
                    }
                }
            }
        }

        assertTrue("OkHttp call did not start", call.entered.await(2, TimeUnit.SECONDS))
        job.cancel()
        assertTrue("RequestScope did not cancel the active Call", call.cancelled.await(2, TimeUnit.SECONDS))
        job.join()
    }

    @Test
    fun `timeout child job cancels synchronous okhttp call immediately`() = runBlocking {
        val scope = RequestScope()
        val call = BlockingCall()
        val job = launch(Dispatchers.Default) {
            try {
                scope.runSuspending {
                    withTimeout(250) {
                        val timeoutJob = currentCoroutineContext()[Job]!!
                        val handle = scope.cancelOnCancellation(timeoutJob)
                        try {
                            withContext(Dispatchers.IO) {
                                runCatching {
                                    RequestScope.execute(call) {
                                        error("timed out call must not return a response")
                                    }
                                }
                            }
                        } finally {
                            handle.dispose()
                        }
                    }
                }
            } catch (_: TimeoutCancellationException) {
                // Expected: the timeout cancels the scope and unblocks the synchronous Call.
            }
        }

        assertTrue("OkHttp call did not start", call.entered.await(2, TimeUnit.SECONDS))
        job.join()
        assertTrue("Timeout did not cancel the active Call", call.cancelled.await(2, TimeUnit.SECONDS))
    }

    private class BlockingCall : Call {
        val entered = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        private val cancelledFlag = AtomicBoolean(false)

        override fun request(): Request =
            Request.Builder().url("https://example.com/").build()

        override fun execute(): Response {
            entered.countDown()
            cancelled.await(5, TimeUnit.SECONDS)
            throw IOException("Call cancelled")
        }

        override fun enqueue(responseCallback: Callback) {
            error("enqueue is not used by this test")
        }

        override fun cancel() {
            cancelledFlag.set(true)
            cancelled.countDown()
        }

        override fun isExecuted(): Boolean = entered.count == 0L
        override fun isCanceled(): Boolean = cancelledFlag.get()
        override fun timeout(): Timeout = Timeout.NONE
        override fun clone(): Call = BlockingCall()
    }
}
