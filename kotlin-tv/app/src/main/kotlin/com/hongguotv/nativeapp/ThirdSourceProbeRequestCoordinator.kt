// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.os.Handler
import java.util.concurrent.Executor

/** 单源探活请求：后台探测并持久化缓存，主线程只接收仍属于当前页面轮次的结果。 */
internal class ThirdSourceProbeRequestCoordinator(
    private val client: ThirdCmsClient,
    private val store: ThirdStore,
    private val executor: Executor,
    private val main: Handler
) {
    fun request(
        source: ThirdSource,
        isCurrent: () -> Boolean,
        onComplete: (ThirdProbe) -> Unit
    ) {
        executor.execute {
            val result = runCatching { client.probe(source.api) }
                .getOrElse { ThirdProbe(false, 0, it.message ?: "连接失败") }
            store.saveProbe(
                source.api,
                ThirdProbeRecord(result.ok, result.total, result.reason, System.currentTimeMillis())
            )
            main.post { if(isCurrent()) onComplete(result) }
        }
    }
}
