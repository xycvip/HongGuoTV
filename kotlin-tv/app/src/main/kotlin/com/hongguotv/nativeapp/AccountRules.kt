// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import com.hongguotv.core.CloudFavorite
import com.hongguotv.core.CloudReservation

internal data class FavoriteState(
    val rows: List<CloudFavorite> = emptyList(),
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val message: String = "尚未读取账户收藏",
)

internal data class ReservationState(
    val rows: List<CloudReservation> = emptyList(),
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val message: String = "尚未读取预约记录",
)

internal object AccountFavoriteRules {
    fun actionLabel(signedIn: Boolean, loading: Boolean, needsRefresh: Boolean, isFavorite: Boolean): String = when {
        !signedIn -> "登录后加入账户收藏"
        loading -> "账户收藏处理中…"
        needsRefresh -> "刷新账户收藏状态"
        isFavorite -> "取消账户收藏"
        else -> "加入账户收藏"
    }
}

internal object AccountReservationRules {
    fun state(
        signedIn: Boolean,
        reservations: Map<Boolean, ReservationState>,
        online: Boolean,
    ): ReservationState = if (signedIn) reservations[online] ?: ReservationState()
        else ReservationState(message = "未登录")

    fun label(
        signedIn: Boolean,
        alreadyReserved: Boolean,
        awaitingConfirmation: Boolean,
    ): String = when {
        !signedIn -> "登录后预约"
        alreadyReserved -> "已预约"
        awaitingConfirmation -> "刷新预约状态"
        else -> "预约"
    }
}
