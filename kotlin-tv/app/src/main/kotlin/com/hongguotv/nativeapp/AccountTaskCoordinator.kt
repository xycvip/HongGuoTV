// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.os.Handler
import java.util.concurrent.Executor

/**
 * 账户控制器通用任务执行器。
 *
 * 统一处理 busy 状态、工作线程执行、主线程回调和账号会话 generation 校验；
 * 不持有 Activity、账户数据或具体网络客户端。
 */
internal interface AccountTaskRunner {
    fun <T> run(
        action: () -> T,
        success: (T) -> Unit,
        failure: (Throwable) -> Unit,
    )
}

internal class AccountTaskCoordinator(
    private val worker: Executor,
    private val main: Handler,
    private val generation: () -> Int,
    private val isClosed: () -> Boolean,
    private val isBusy: () -> Boolean,
    private val setBusy: (Boolean) -> Unit,
    private val setStatus: (String) -> Unit,
    private val safeError: (Throwable) -> String,
    private val toast: (String) -> Unit,
) : AccountTaskRunner {
    fun <T> run(action: () -> T, success: (T) -> Unit) =
        run(action, success) { toast(safeError(it)) }

    override fun <T> run(
        action: () -> T,
        success: (T) -> Unit,
        failure: (Throwable) -> Unit,
    ) {
        if (isBusy() || isClosed()) return
        setBusy(true)
        val token = generation()
        worker.execute {
            if (isClosed() || token != generation()) return@execute
            val result = runCatching(action)
            main.post {
                if (isClosed() || token != generation()) return@post
                setBusy(false)
                result.onSuccess(success).onFailure {
                    setStatus(safeError(it))
                    failure(it)
                }
            }
        }
    }
}
