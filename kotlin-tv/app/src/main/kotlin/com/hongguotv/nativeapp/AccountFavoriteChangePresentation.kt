// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

/** 收藏写入结果的状态与用户提示映射；不负责网络、重试或账户状态副作用。 */
internal data class AccountFavoriteChangePresentation(
    val confirmed: Boolean?,
    val stateMessage: String,
    val completionMessage: String,
)

internal object AccountFavoriteChangePresentationRules {
    fun success(confirmed: Boolean, add: Boolean, wrote: Boolean, rowCount: Int) =
        AccountFavoriteChangePresentation(
            confirmed = confirmed,
            stateMessage = if (confirmed) {
                "已核对账户收藏，共读取 $rowCount 部"
            } else {
                "请求已提交，列表尚未确认，请刷新或在手机核对"
            },
            completionMessage = when {
                !confirmed ->
                    "请求已提交，但账户列表尚未确认生效。请刷新或在手机红果核对，不要连续重复提交。"
                wrote && add ->
                    "已加入账户收藏，并已在账户列表确认"
                wrote ->
                    "已取消账户收藏，并已从本次读取的列表移除"
                add ->
                    "这部剧已在账户收藏中，无需重复添加"
                else ->
                    "本次读取的账户列表中已没有这部剧，无需重复取消"
            },
        )

    fun failure(attempted: Boolean, error: String) =
        AccountFavoriteChangePresentation(
            confirmed = null,
            stateMessage = if (attempted) {
                "收藏操作未确认，请刷新或在手机核对"
            } else {
                "读取失败：$error"
            },
            completionMessage = if (attempted) {
                "收藏操作未确认：$error。请先刷新或在手机核对。"
            } else {
                "未修改账户收藏：$error"
            },
        )
}
