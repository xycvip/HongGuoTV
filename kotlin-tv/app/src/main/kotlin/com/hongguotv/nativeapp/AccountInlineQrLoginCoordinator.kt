// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.SystemClock
import com.hongguotv.core.WebLogin
import okhttp3.OkHttpClient

/**
 * 账户页内嵌二维码登录会话。
 *
 * 只管理二维码获取、有效期、轮询与会话取消；账户凭据落盘和登录后数据刷新仍由
 * AccountController 负责，避免把账户业务状态复制到 UI 协调器中。
 */
internal class AccountInlineQrLoginCoordinator(
    private val http: OkHttpClient,
    private val main: Handler,
    private val worker: java.util.concurrent.Executor,
    private val authentication: AccountQrAuthenticationCoordinator,
    private val generation: () -> Int,
    private val advanceGeneration: () -> Int,
    private val isClosed: () -> Boolean,
    private val isBusy: () -> Boolean,
    private val setBusy: (Boolean) -> Unit,
    private val setStatus: (String) -> Unit,
    private val safeError: (Throwable) -> String,
    private val onInlineChanged: () -> Unit,
    private val onAccountChanged: () -> Unit,
    private val onAuthenticated: (com.hongguotv.core.AccountCredentials, com.hongguotv.core.CloudProfile) -> Unit,
) {
    private var login: WebLogin? = null
    private var deadline = 0L
    private var token = 0
    var bitmap: Bitmap? = null
        private set

    fun start() {
        if (isClosed() || isBusy()) return
        token = advanceGeneration()
        val requestToken = token
        val session = WebLogin(http)
        login = session
        bitmap = null
        setBusy(true)
        setStatus("正在获取二维码…")
        onInlineChanged()
        worker.execute {
            val result = runCatching { session.qr() }
            main.post {
                if (isClosed() || requestToken != generation()) return@post
                result.fold(
                    { qr ->
                        val decoded = BitmapFactory.decodeByteArray(qr.image, 0, qr.image.size)
                        if (decoded == null) {
                            setBusy(false)
                            setStatus("二维码无法显示，请刷新重试")
                            onInlineChanged()
                            return@fold
                        }
                        bitmap = decoded
                        deadline = SystemClock.elapsedRealtime() + qr.expiresIn.coerceAtLeast(1) * 1000L
                        setBusy(false)
                        setStatus("请使用手机红果 App 扫码，并在手机上确认登录")
                        onInlineChanged()
                        schedulePoll(requestToken)
                    },
                    {
                        setBusy(false)
                        setStatus(safeError(it))
                        onInlineChanged()
                    }
                )
            }
        }
    }

    private fun schedulePoll(requestToken: Int) {
        main.postDelayed({
            if (isClosed() || requestToken != generation() || requestToken != token || login == null) return@postDelayed
            if (SystemClock.elapsedRealtime() >= deadline) {
                cancelSession()
                setStatus("二维码已过期，请点击刷新二维码")
                onInlineChanged()
                return@postDelayed
            }
            val session = login ?: return@postDelayed
            authentication.poll(session, isCurrent = { !isClosed() && requestToken == generation() }) { outcome ->
                when (outcome) {
                    AccountQrAuthenticationCoordinator.Outcome.Waiting -> schedulePoll(requestToken)
                    is AccountQrAuthenticationCoordinator.Outcome.Authenticated -> {
                        setBusy(false)
                        login = null
                        bitmap = null
                        onAuthenticated(outcome.account.first, outcome.account.second)
                    }
                    is AccountQrAuthenticationCoordinator.Outcome.PollFailed -> {
                        if (outcome.error.message.orEmpty().contains("过期")) {
                            cancelSession()
                            setStatus("二维码已过期，请点击刷新二维码")
                            onInlineChanged()
                        } else schedulePoll(requestToken)
                    }
                    is AccountQrAuthenticationCoordinator.Outcome.ValidationFailed -> {
                        setBusy(false)
                        login = null
                        bitmap = null
                        setStatus(safeError(outcome.error))
                        onAccountChanged()
                    }
                }
            }
        }, 1800L)
    }

    fun cancel() {
        cancelSession()
    }

    private fun cancelSession() {
        bitmap = null
        login?.cancel()
        login = null
    }
}
