// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.app.Activity
import android.app.AlertDialog
import android.graphics.BitmapFactory
import android.os.Handler
import com.hongguotv.core.AccountCredentials
import com.hongguotv.core.CloudProfile
import com.hongguotv.core.WebLogin
import okhttp3.OkHttpClient
import java.util.concurrent.ExecutorService

/**
 * 传统扫码登录弹窗会话：二维码获取、有效期、轮询、取消与凭据落盘。
 * 账户资料和页面刷新仍交由 AccountController 接管。
 */
internal class AccountQrDialogLoginCoordinator(
    private val activity: Activity,
    private val http: OkHttpClient,
    private val main: Handler,
    private val worker: ExecutorService,
    private val authentication: AccountQrAuthenticationCoordinator,
    private val store: AccountStore,
    private val generation: () -> Int,
    private val isClosed: () -> Boolean,
    private val isBusy: () -> Boolean,
    private val setBusy: (Boolean) -> Unit,
    private val setStatus: (String) -> Unit,
    private val safeError: (Throwable) -> String,
    private val toast: (String) -> Unit,
    private val showDialog: (AlertDialog) -> Unit,
    private val onAuthenticated: (AccountCredentials, CloudProfile) -> Unit,
) {
    fun start() {
        if (isBusy() || isClosed()) return
        val login = WebLogin(http)
        val token = generation()
        setBusy(true)
        worker.execute {
            if (isClosed() || token != generation()) return@execute
            val result = runCatching { login.qr() }
            main.post {
                if (isClosed() || token != generation()) {
                    login.cancel()
                    return@post
                }
                setBusy(false)
                result.onSuccess { qr ->
                    val bitmap = BitmapFactory.decodeByteArray(qr.image, 0, qr.image.size)
                    if (bitmap == null) {
                        toast("登录二维码无法显示，请重试")
                        return@onSuccess
                    }
                    val (qrDialog, cancelButton) = AccountQrDialogUi.create(activity, bitmap)
                    showDialog(qrDialog)
                    cancelButton.setOnClickListener { qrDialog.dismiss() }
                    val current = qrDialog
                    val sessionToken = generation()
                    val deadline = android.os.SystemClock.elapsedRealtime() +
                        qr.expiresIn.coerceIn(1, 300) * 1000L
                    var cancelled = false
                    setBusy(true)
                    setStatus("等待手机确认扫码登录")
                    current.setOnDismissListener {
                        cancelled = true
                        login.cancel()
                        if (sessionToken == generation()) {
                            setBusy(false)
                            setStatus("已取消扫码登录")
                        }
                    }

                    fun closeQr() {
                        cancelled = true
                        current.setOnDismissListener(null)
                        login.cancel()
                        if (sessionToken == generation()) setBusy(false)
                        current.dismiss()
                    }

                    fun poll() {
                        if (cancelled || isClosed() || sessionToken != generation()) return
                        if (android.os.SystemClock.elapsedRealtime() >= deadline) {
                            closeQr()
                            setStatus("二维码已过期，请重新扫码")
                            showDialog(
                                TvDialog.confirm(
                                    activity,
                                    "二维码已过期",
                                    "请刷新二维码，再用手机红果 App 扫码确认。",
                                    action = { start() },
                                )
                            )
                            return
                        }
                        authentication.poll(
                            login,
                            isCurrent = { !cancelled && !isClosed() && sessionToken == generation() },
                        ) { outcome ->
                            when (outcome) {
                                AccountQrAuthenticationCoordinator.Outcome.Waiting ->
                                    main.postDelayed({ poll() }, 2000)
                                is AccountQrAuthenticationCoordinator.Outcome.Authenticated -> {
                                    val account = outcome.account
                                    closeQr()
                                    setStatus("扫码已确认，正在保存登录状态")
                                    setBusy(true)
                                    worker.execute {
                                        val saved = runCatching {
                                            store.save(account.first)
                                            account
                                        }
                                        main.post {
                                            if (isClosed() || sessionToken != generation()) return@post
                                            setBusy(false)
                                            saved.onSuccess { onAuthenticated(it.first, it.second) }
                                                .onFailure { error ->
                                                    showDialog(
                                                        TvDialog.confirm(
                                                            activity,
                                                            "登录状态保存失败",
                                                            safeError(error),
                                                            action = { start() },
                                                        )
                                                    )
                                                }
                                        }
                                    }
                                }
                                is AccountQrAuthenticationCoordinator.Outcome.PollFailed -> {
                                    closeQr()
                                    setStatus(safeError(outcome.error))
                                    showDialog(
                                        TvDialog.confirm(
                                            activity,
                                            "扫码登录未完成",
                                            safeError(outcome.error),
                                            action = { start() },
                                        )
                                    )
                                }
                                is AccountQrAuthenticationCoordinator.Outcome.ValidationFailed -> {
                                    closeQr()
                                    setStatus(safeError(outcome.error))
                                    showDialog(
                                        TvDialog.confirm(
                                            activity,
                                            "扫码登录未完成",
                                            safeError(outcome.error),
                                            action = { start() },
                                        )
                                    )
                                }
                            }
                        }
                    }
                    main.postDelayed({ poll() }, 1500)
                }.onFailure { error ->
                    setStatus(safeError(error))
                    toast(safeError(error))
                }
            }
        }
    }
}
