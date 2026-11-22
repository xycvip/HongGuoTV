// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.os.Handler
import java.util.concurrent.Executor

/** 当前片源的搜索联想请求：后台访问 CMS，回调前由调用方提供输入序号校验。 */
internal class ThirdSourceSuggestionRequestCoordinator(
    private val client: ThirdCmsClient,
    private val executor: Executor,
    private val main: Handler
) {
    fun request(
        source: ThirdSource,
        word: String,
        isCurrent: () -> Boolean,
        onComplete: (List<String>) -> Unit
    ) {
        executor.execute {
            val suggestions = runCatching { client.suggest(source.api, word) }.getOrDefault(emptyList())
            main.post { if(isCurrent()) onComplete(suggestions) }
        }
    }
}
