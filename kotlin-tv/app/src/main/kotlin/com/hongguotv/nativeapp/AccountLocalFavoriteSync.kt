// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.os.Handler
import com.hongguotv.core.AccountCredentials
import com.hongguotv.core.CloudAccountClient
import com.hongguotv.core.Series
import java.util.concurrent.Executor

internal class AccountLocalFavoriteSyncCoordinator(
    private val client: CloudAccountClient,
    private val executor: Executor,
    private val main: Handler
) {
    fun sync(
        account: AccountCredentials,
        localRows: List<Series>,
        isCurrent: () -> Boolean,
        onComplete: (Result<Boolean>) -> Unit
    ) {
        executor.execute {
            val result = runCatching {
                val existing = client.favorites(account).mapTo(mutableSetOf()) { it.series.id }
                val missing = AccountLocalFavoriteSyncPlanner.missingFromAccount(localRows, existing)
                missing.forEach { series ->
                    check(isCurrent()) { "账户会话已变化，停止收藏同步" }
                    client.setFavorite(account, series.id, true)
                    existing.add(series.id)
                }
                missing.isNotEmpty()
            }
            // 即使会话已失效也回传完成，调用方需要释放同步中标记；是否更新数据由调用方校验。
            main.post { onComplete(result) }
        }
    }
}

internal object AccountLocalFavoriteSyncPlanner {
    private val numericId = Regex("[0-9]{1,30}")

    fun missingFromAccount(localRows: List<Series>, accountIds: Set<String>): List<Series> =
        localRows.distinctBy { it.id }
            .filter { it.id.matches(numericId) && it.id !in accountIds }
            .take(500)
}
