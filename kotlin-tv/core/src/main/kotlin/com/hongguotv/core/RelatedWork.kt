// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

/**
 * 账号预约（subscribe）用的待上线作品条目。
 *
 * 相关作品解析器已随详情页热更新迁移删除；模型保留给云账号的预约写入使用。
 */
data class RelatedWork(val series: Series, val season: String, val upcoming: Boolean) {
    val label
        get() =
            listOf(season, if (upcoming) "待上线 · 预约" else "已上线")
                .filter { it.isNotBlank() }
                .joinToString(" · ")
}
