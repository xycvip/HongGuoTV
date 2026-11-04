// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.os.Handler
import com.hongguotv.core.AccountCredentials
import com.hongguotv.core.CloudAccountClient
import com.hongguotv.core.CloudFavorite
import com.hongguotv.core.CloudReservation
import com.hongguotv.core.Series
import java.util.concurrent.Executor

internal data class AccountFavoriteChange(
    val rows: List<CloudFavorite>,
    val wrote: Boolean
)

internal data class AccountFavoriteChangeExecution(
    val result: Result<AccountFavoriteChange>,
    val attempted: Boolean
)

/** 云端收藏与预约数据请求协调器；所有网络操作均在工作线程，回调回到主线程。 */
internal class AccountCloudDataCoordinator(
    private val client: CloudAccountClient,
    private val worker: Executor,
    private val main: Handler
) {
    fun loadFavorites(
        account: AccountCredentials,
        isCurrent: () -> Boolean,
        onComplete: (Result<List<CloudFavorite>>) -> Unit
    ) {
        worker.execute {
            if(!isCurrent()) return@execute
            val result = runCatching { client.favorites(account) }
            main.post { if(isCurrent()) onComplete(result) }
        }
    }

    fun changeFavorite(
        account: AccountCredentials,
        series: Series,
        add: Boolean,
        isCurrent: () -> Boolean,
        onComplete: (AccountFavoriteChangeExecution) -> Unit
    ) {
        worker.execute {
            var attempted = false
            val result = runCatching {
                check(isCurrent()) { "账户会话已变化" }
                val before = client.favorites(account)
                if(before.any { it.series.id == series.id } == add) {
                    AccountFavoriteChange(before, false)
                } else {
                    check(isCurrent()) { "账户会话已变化" }
                    attempted = true
                    client.setFavorite(account, series.id, add)
                    check(isCurrent()) { "账户会话已变化" }
                    AccountFavoriteChange(client.favorites(account), true)
                }
            }
            val execution = AccountFavoriteChangeExecution(result, attempted)
            main.post { if(isCurrent()) onComplete(execution) }
        }
    }

    /** 分别读取已上线和待上线预约；单类失败独立回传，不伪装成空列表。 */
    fun loadReservations(
        account: AccountCredentials,
        isCurrent: () -> Boolean,
        onResult: (Boolean, Result<List<CloudReservation>>) -> Unit
    ) {
        worker.execute {
            for(online in listOf(true, false)) {
                if(!isCurrent()) return@execute
                val result = runCatching { client.reservations(account, online) }
                main.post { if(isCurrent()) onResult(online, result) }
            }
        }
    }
}
