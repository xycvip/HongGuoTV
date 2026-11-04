// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import com.hongguotv.core.AccountCredentials
import com.hongguotv.core.CloudAccountClient
import com.hongguotv.core.CloudProfile
import java.io.IOException

/** 扫码凭据补全设备身份并验证账号资料；不管理登录 UI 或会话状态。 */
internal class AccountCredentialValidator(private val client: CloudAccountClient) {
    fun validate(web: AccountCredentials): Pair<AccountCredentials, CloudProfile> {
        // QR 登录可能没有设备身份，仅在缺失时注册，避免重复创建设备。
        val next = if (web.deviceId.isNotBlank() && web.installId.isNotBlank()) {
            web
        } else {
            val device = client.registerDevice() ?: throw IOException("账号设备初始化失败")
            web.copy(
                deviceId = device.deviceId,
                installId = device.installId,
                device = mapOf("device_id" to device.deviceId, "iid" to device.installId),
            )
        }
        return next to client.profile(next)
    }
}
