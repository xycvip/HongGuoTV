package com.hongguotv.core

import org.junit.Assert.*
import org.junit.Test
import java.io.InterruptedIOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class RequestScopeTest {
    @Test fun `captured navigation scope propagates cancellation to a child thread`() {
        val scope=RequestScope()
        val captured=scope.run { RequestScope.currentScope() }!!
        val entered=CountDownLatch(1)
        val finished=CountDownLatch(1)
        val failure=AtomicReference<Throwable?>()
        val child=Thread {
            try {
                captured.run {
                    entered.countDown()
                    while(true) {
                        RequestScope.ensureActive()
                        Thread.sleep(10)
                    }
                }
            } catch(problem: Throwable) {
                failure.set(problem)
            } finally {
                finished.countDown()
            }
        }
        child.start()
        assertTrue(entered.await(2,TimeUnit.SECONDS))
        scope.cancel()
        assertTrue(finished.await(2,TimeUnit.SECONDS))
        assertTrue("Expected cancellation exception, got ${failure.get()}",failure.get() is InterruptedIOException)
    }
}
