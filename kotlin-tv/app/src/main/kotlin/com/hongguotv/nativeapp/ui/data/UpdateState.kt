// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp.ui.data

import com.hongguotv.core.AppUpdate

/** 更新弹窗的阶段。UI 只按这个枚举渲染，避免多处布尔量互相打架。 */
enum class UpdatePhase { IDLE, CHECKING, UP_TO_DATE, AVAILABLE, DOWNLOADING, VERIFYING, READY, ERROR }

data class UpdateUiState(
    val visible: Boolean = false,
    val phase: UpdatePhase = UpdatePhase.IDLE,
    val update: AppUpdate? = null,
    val progress: Int = 0,
    val speedBytesPerSecond: Long = 0L,
    val error: String? = null,
    val notice: String? = null,
    val apkPath: String? = null,
    /** null 表示自动线路；其余为 AppUpdate.MIRRORS 的索引。 */
    val mirrorIndex: Int? = null,
) {
    val busy: Boolean get() = phase == UpdatePhase.CHECKING || phase == UpdatePhase.DOWNLOADING || phase == UpdatePhase.VERIFYING
}
