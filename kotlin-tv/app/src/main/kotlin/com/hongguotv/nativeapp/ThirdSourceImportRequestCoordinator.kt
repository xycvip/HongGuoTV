// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.os.Handler
import java.util.concurrent.Executor

/** TVBox 配置下载与解析请求：网络与 JSON 解析放在后台，页面轮次校验后再回调。 */
internal class ThirdSourceImportRequestCoordinator(
    private val client: ThirdCmsClient,
    private val executor: Executor,
    private val main: Handler
) {
    fun request(
        url: String,
        isCurrent: () -> Boolean,
        onComplete: (Pair<ThirdTvboxParse?, String?>) -> Unit
    ) {
        executor.execute {
            val result = runCatching { client.importTvbox(url) }
                .getOrElse { null to (it.message ?: "配置获取失败") }
            main.post { if(isCurrent()) onComplete(result) }
        }
    }
}
