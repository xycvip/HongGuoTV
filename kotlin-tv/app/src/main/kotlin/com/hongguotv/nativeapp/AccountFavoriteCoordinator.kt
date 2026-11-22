// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import com.hongguotv.core.AccountCredentials
import com.hongguotv.core.Series

/** 账户收藏状态与业务流程；UI 控制器只保留稳定的对外入口。 */
internal class AccountFavoriteCoordinator(
    private val cloudData: AccountCloudDataCoordinator,
    private val localSync: AccountLocalFavoriteSyncCoordinator,
    private val account: () -> AccountCredentials?,
    private val isSignedIn: () -> Boolean,
    private val isClosed: () -> Boolean,
    private val generation: () -> Int,
    private val showLogin: () -> Unit,
    private val onChanged: () -> Unit,
    private val safeError: (Throwable) -> String,
) {
    private var favorites = FavoriteState()
    private val unconfirmedFavorites = mutableSetOf<String>()
    private var localSyncRunning = false
    private var localSyncEpoch = 0

    fun state(): FavoriteState = if(isSignedIn()) favorites else FavoriteState(message = "未登录")

    fun isFavorite(id: String): Boolean = isSignedIn() && favorites.rows.any { it.series.id == id }

    fun needsRefresh(id: String): Boolean = id in unconfirmedFavorites

    fun actionLabel(id: String): String = AccountFavoriteRules.actionLabel(
        signedIn = isSignedIn(),
        loading = favorites.loading,
        needsRefresh = needsRefresh(id),
        isFavorite = isFavorite(id),
    )

    fun change(series: Series, add: Boolean, done: (String) -> Unit) {
        val currentAccount = account() ?: run {
            showLogin()
            return
        }
        if(isClosed() || favorites.loading) {
            done("账户收藏正在处理，请稍候")
            return
        }
        if(needsRefresh(series.id)) {
            refresh()
            done("上次操作尚未确认，已刷新账户收藏，请核对后再操作。")
            return
        }
        val token = generation()
        favorites = favorites.copy(loading = true, message = "正在核对并更新账户收藏…")
        onChanged()
        cloudData.changeFavorite(
            account = currentAccount,
            series = series,
            add = add,
            isCurrent = { !isClosed() && token == generation() }
        ) { execution ->
            val presentation = execution.result.fold(
                { outcome ->
                    val result = AccountFavoriteChangePresentationRules.success(
                        confirmed = outcome.rows.any { it.series.id == series.id } == add,
                        add = add,
                        wrote = outcome.wrote,
                        rowCount = outcome.rows.size,
                    )
                    if(result.confirmed == true) unconfirmedFavorites.remove(series.id)
                    else unconfirmedFavorites.add(series.id)
                    favorites = FavoriteState(
                        outcome.rows,
                        loaded = true,
                        message = result.stateMessage,
                    )
                    result
                },
                { error ->
                    val result = AccountFavoriteChangePresentationRules.failure(
                        attempted = execution.attempted,
                        error = safeError(error),
                    )
                    if(execution.attempted) unconfirmedFavorites.add(series.id)
                    favorites = favorites.copy(loading = false, message = result.stateMessage)
                    result
                }
            )
            onChanged()
            done(presentation.completionMessage)
        }
    }

    fun refresh() {
        val currentAccount = account() ?: return
        if(isClosed() || favorites.loading) return
        val token = generation()
        favorites = favorites.copy(loading = true, message = "正在读取账户收藏…")
        onChanged()
        cloudData.loadFavorites(
            account = currentAccount,
            isCurrent = { !isClosed() && token == generation() }
        ) { result ->
            favorites = result.fold(
                {
                    unconfirmedFavorites.clear()
                    FavoriteState(it, loaded = true, message = "已读取 ${it.size} 部账户收藏")
                },
                { favorites.copy(loading = false, message = "读取失败：${safeError(it)}") }
            )
            onChanged()
        }
    }

    /** 仅补充本地缺失项，不删除或覆盖云端收藏。 */
    fun syncLocal(localRows: List<Series>) {
        val currentAccount = account() ?: return
        if(isClosed() || localSyncRunning || favorites.loading || localRows.isEmpty()) return
        val token = generation()
        val epoch = localSyncEpoch
        localSyncRunning = true
        localSync.sync(
            account = currentAccount,
            localRows = localRows,
            isCurrent = { !isClosed() && token == generation() }
        ) { result ->
            if(epoch != localSyncEpoch) return@sync
            if(isClosed() || token != generation()) {
                localSyncRunning = false
                return@sync
            }
            localSyncRunning = false
            result.onSuccess { if(it) refresh() }
        }
    }

    fun reset() {
        favorites = FavoriteState()
        unconfirmedFavorites.clear()
        localSyncRunning = false
        localSyncEpoch++
    }
}
