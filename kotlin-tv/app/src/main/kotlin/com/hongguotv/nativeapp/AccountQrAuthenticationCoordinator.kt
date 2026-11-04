// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.os.Handler
import com.hongguotv.core.AccountCredentials
import com.hongguotv.core.CloudProfile
import com.hongguotv.core.WebLogin
import java.util.concurrent.Executor

/** 扫码轮询与凭据验证协调器：轮询/账号资料请求均在工作线程执行，并区分两类失败。 */
internal class AccountQrAuthenticationCoordinator(
    private val validator: AccountCredentialValidator,
    private val executor: Executor,
    private val main: Handler
) {
    sealed class Outcome {
        object Waiting : Outcome()
        data class PollFailed(val error: Throwable) : Outcome()
        data class ValidationFailed(val error: Throwable) : Outcome()
        data class Authenticated(val account: Pair<AccountCredentials, CloudProfile>) : Outcome()
    }

    fun poll(
        login: WebLogin,
        isCurrent: () -> Boolean,
        onComplete: (Outcome) -> Unit
    ) {
        executor.execute {
            val outcome = try {
                if(!login.poll()) Outcome.Waiting
                else {
                    try {
                        Outcome.Authenticated(validator.validate(login.credentials()))
                    } catch(error: Throwable) {
                        Outcome.ValidationFailed(error)
                    }
                }
            } catch(error: Throwable) {
                Outcome.PollFailed(error)
            }
            main.post { if(isCurrent()) onComplete(outcome) }
        }
    }
}
