// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.app.Activity
import android.app.AlertDialog

/** 账户入口弹窗的选项与说明 UI；账户操作本身通过回调交给控制器。 */
internal object AccountEntryDialogUi {
    fun create(
        activity: Activity,
        nickname: String?,
        signedIn: Boolean,
        onQrLogin: () -> Unit,
        onHistory: () -> Unit,
        onRefresh: () -> Unit,
        onLogout: () -> Unit,
        onSyncStatus: () -> Unit,
    ): AlertDialog {
        val choices = if (signedIn) {
            arrayOf("云端继续观看（最近 50 部）", "刷新账号和云端记录", "退出红果账号")
        } else {
            arrayOf("使用红果 App 扫码登录")
        }
        return TvDialog.choose(
            activity,
            nickname?.let { "红果账户 · $it" } ?: "红果账户",
            choices.toList(),
            { index ->
                if (!signedIn) onQrLogin()
                else when (index) {
                    0 -> onHistory()
                    1 -> onRefresh()
                    2 -> onLogout()
                }
            },
            extra = "同步状态",
            onExtra = onSyncStatus,
        )
    }
}
