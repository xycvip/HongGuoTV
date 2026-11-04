// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.os.Handler
import android.os.SystemClock
import java.util.concurrent.Executor

/** 串行测速协调器：避免多个源同时探测造成带宽竞争，并按完成顺序回传单源结果。 */
internal class ThirdSourceSpeedTestCoordinator(
    private val client: ThirdCmsClient,
    private val executor: Executor,
    private val main: Handler
) {
    data class Outcome(
        val source: ThirdSource,
        val result: ThirdProbe,
        val elapsedMs: Long
    )

    data class Summary(
        val fastest: Outcome?,
        val outcomes: Int
    )

    fun run(
        sources: List<ThirdSource>,
        isCurrent: () -> Boolean,
        onEach: (Outcome) -> Unit,
        onComplete: (Summary) -> Unit
    ) {
        executor.execute {
            var fastest: Outcome? = null
            var completed = 0
            sources.forEach { source ->
                val started = SystemClock.elapsedRealtime()
                val result = runCatching { client.probe(source.api) }
                    .getOrElse { ThirdProbe(false, 0, it.message ?: "连接失败") }
                val outcome = Outcome(source, result, SystemClock.elapsedRealtime() - started)
                completed++
                if(result.ok && (fastest == null || outcome.elapsedMs < fastest!!.elapsedMs)) {
                    fastest = outcome
                }
                main.post { if(isCurrent()) onEach(outcome) }
            }
            val summary = Summary(fastest, completed)
            // 完成回调必须始终执行：调用方需要结束 busy 状态；是否更新 UI 由调用方校验轮次。 
            main.post { onComplete(summary) }
        }
    }
}
