// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import com.hongguotv.core.AccountCredentials
import com.hongguotv.core.CloudAccountClient
import com.hongguotv.core.RelatedWork

/** 账户预约业务协调：请求、待确认标记与已上线/待上线列表状态独立于 UI 控制器。 */
internal class AccountReservationCoordinator(
    private val client: CloudAccountClient,
    private val cloudData: AccountCloudDataCoordinator,
    private val taskCoordinator: AccountTaskCoordinator,
    private val account: () -> AccountCredentials?,
    private val isSignedIn: () -> Boolean,
    private val isBusy: () -> Boolean,
    private val isClosed: () -> Boolean,
    private val generation: () -> Int,
    private val showLogin: () -> Unit,
    private val onChanged: () -> Unit,
    private val toast: (String) -> Unit,
    private val safeError: (Throwable) -> String,
) {
    private val acceptedReservations = mutableSetOf<String>()
    private var reservations = emptyMap<Boolean, ReservationState>()

    fun state(online: Boolean): ReservationState =
        AccountReservationRules.state(isSignedIn(), reservations, online)

    fun label(work: RelatedWork): String {
        val alreadyReserved = state(false).rows.any {
            it.series.id == work.series.id || it.series.id == "reservation:${work.series.id}"
        }
        return AccountReservationRules.label(
            signedIn = isSignedIn(),
            alreadyReserved = alreadyReserved,
            awaitingConfirmation = work.series.id in acceptedReservations,
        )
    }

    fun reserve(work: RelatedWork, done: (String) -> Unit) {
        val currentAccount = account() ?: run {
            showLogin()
            return
        }
        if(isBusy()) {
            toast("账号操作进行中，请稍候")
            return
        }
        if(label(work) == "已预约") {
            done("该作品已在当前账号的预约列表中")
            return
        }
        if(work.series.id in acceptedReservations) {
            refresh()
            done("正在刷新预约列表，请稍后核对；未显示前暂不能确认预约生效。")
            return
        }
        taskCoordinator.run(
            { client.reserve(currentAccount, work) },
            {
                acceptedReservations += work.series.id
                onChanged()
                done("红果已接受预约请求，尚未在账号列表确认。请刷新预约状态，或在手机红果核对是否新增。")
            },
            { done("预约未完成：${safeError(it)}") },
        )
    }

    fun refresh() {
        val currentAccount = account() ?: return
        if(isClosed() || reservations.values.any { it.loading }) return
        val token = generation()
        reservations = listOf(true, false).associateWith {
            state(it).copy(loading = true, message = "正在读取预约记录…")
        }
        onChanged()
        cloudData.loadReservations(
            account = currentAccount,
            isCurrent = { !isClosed() && token == generation() }
        ) { online, result ->
            val before = state(online)
            reservations = reservations + (online to result.fold(
                {
                    ReservationState(it, loaded = true, message = "已读取 ${it.size} 部预约记录")
                },
                {
                    before.copy(loading = false, message = "读取失败：${safeError(it)}")
                }
            ))
            onChanged()
        }
    }

    fun reset() {
        reservations = emptyMap()
        acceptedReservations.clear()
    }
}
