// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.os.Handler
import java.util.concurrent.Executor

/**
 * 三方片源目录请求协调器。
 *
 * 负责把分类与目录列表请求放到后台线程，并将同一轮结果合并后投递回主线程。
 * 页面仍负责 generation 校验策略以外的状态变更、分页合并和 UI 刷新。
 */
internal class ThirdSourceCatalogRequestCoordinator(
    private val client: ThirdCmsClient,
    private val executor: Executor,
    private val main: Handler
) {
    data class Result(
        val categories: List<ThirdCategory>?,
        val page: ThirdListPage?
    )

    fun request(
        api: String,
        categoryId: String?,
        page: Int,
        includeCategories: Boolean,
        isCurrent: () -> Boolean,
        onComplete: (Result) -> Unit
    ) {
        executor.execute {
            val categories = if(includeCategories) {
                runCatching { client.categories(api) }.getOrDefault(emptyList())
            } else null
            val listPage = runCatching { client.list(api, categoryId, page) }.getOrNull()
            main.post {
                if(isCurrent()) onComplete(Result(categories, listPage))
            }
        }
    }
}
