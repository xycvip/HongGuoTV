// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.os.Handler
import java.util.concurrent.Executor

/**
 * 三方片源搜索请求协调器。
 *
 * 每个源独立提交到注入的执行器，结果回到主线程前执行调用方提供的轮次校验。
 * 聚合、去重、失败统计和 UI 状态仍由页面状态层负责。
 */
internal class ThirdSourceSearchRequestCoordinator(
    private val client: ThirdCmsClient,
    private val executor: Executor,
    private val main: Handler
) {
    fun request(
        source: ThirdSource,
        word: String,
        page: Int,
        isCurrent: () -> Boolean,
        onResult: (ThirdSource, ThirdSearchResult) -> Unit
    ) {
        executor.execute {
            val result = runCatching { client.searchPage(source.api, word, page) }
                .getOrElse { ThirdSearchResult(null, it.message ?: "连接失败") }
            main.post {
                if(isCurrent()) onResult(source, result)
            }
        }
    }
}
