// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.os.Handler
import java.util.concurrent.Executor

/** 单条影片详情请求协调器：网络访问固定在后台线程，过期页面不接收回调。 */
internal class ThirdSourceDetailRequestCoordinator(
    private val client: ThirdCmsClient,
    private val executor: Executor,
    private val main: Handler
) {
    fun request(
        source: ThirdSource,
        videoId: String,
        isCurrent: () -> Boolean,
        onComplete: (ThirdDetail?) -> Unit
    ) {
        executor.execute {
            val detail = runCatching { client.detail(source.api, videoId) }.getOrNull()
            main.post { if(isCurrent()) onComplete(detail) }
        }
    }
}
