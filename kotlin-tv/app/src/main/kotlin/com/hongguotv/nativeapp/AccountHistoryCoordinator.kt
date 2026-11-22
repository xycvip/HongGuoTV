// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import com.hongguotv.core.AccountCredentials
import com.hongguotv.core.CloudProfile
import com.hongguotv.core.CloudProgress

/** 云端观看历史会话：负责读取、去重、保留最近记录及播放进度回填。 */
internal class AccountHistoryCoordinator(
    private val loadHistory: (AccountCredentials) -> Pair<CloudProfile, List<CloudProgress>>,
    private val taskCoordinator: AccountTaskRunner,
    private val account: () -> AccountCredentials?,
    private val isSignedIn: () -> Boolean,
    private val isClosed: () -> Boolean,
    private val isBusy: () -> Boolean,
    private val setStatus: (String) -> Unit,
    private val onProfileLoaded: (CloudProfile) -> Unit,
    private val onChanged: () -> Unit,
    private val toast: (String) -> Unit,
    private val safeError: (Throwable) -> String,
) {
    private var history = emptyList<CloudProgress>()
    private var loaded = false

    val ready: Boolean
        get() = isSignedIn() && loaded

    fun rows(): List<CloudProgress> = if(isSignedIn()) history.toList() else emptyList()

    fun progress(id: String): CloudProgress? =
        if(account() != null) history.firstOrNull { it.series.id == id } else null

    fun refresh(showResult: Boolean, onResultShown: () -> Unit = {}) {
        val currentAccount = account() ?: return
        if(isBusy() || isClosed()) return
        setStatus("正在读取账号观看历史…")
        taskCoordinator.run(
            { loadHistory(currentAccount) },
            { (user, rows) ->
                onProfileLoaded(user)
                loaded = true
                history = rows.distinctBy { it.series.id }.sortedByDescending { it.updatedAt }.take(50)
                setStatus("已读取 ${rows.size} 部云端观看记录")
                onChanged()
                if(showResult) {
                    toast("已读取 ${rows.size} 部云端观看记录")
                    onResultShown()
                }
            },
            {
                onChanged()
                toast(safeError(it))
            },
        )
        onChanged()
    }

    fun addProgress(progress: CloudProgress, episodes: Int) {
        history = (listOf(progress.copy(episodeCount = episodes)) +
            history.filterNot { it.series.id == progress.series.id }).take(50)
        onChanged()
    }

    fun reset() {
        history = emptyList()
        loaded = false
    }
}
