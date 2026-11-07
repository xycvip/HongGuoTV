// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.app.AlertDialog
import android.widget.Toast
import com.hongguotv.core.Series

/**
 * 河马排行榜中的剧单详情由 1116 获取。
 * 剧单成员仍然是普通短剧，点击成员后进入现有 1132 目录与 1133 播放流程。
 */
internal fun MainActivity.openHippoPlaylist(playlist: Series) {
    val playlistId = playlist.id.removePrefix("hippo-playlist:")
    if (playlistId.isBlank()) {
        Toast.makeText(this, "剧单编号无效", Toast.LENGTH_SHORT).show()
        return
    }
    Toast.makeText(this, "正在加载剧单：${playlist.title}", Toast.LENGTH_SHORT).show()
    io.execute {
        val result = runCatching { hippoRepository.playlistDetail(playlistId) }
        main.post {
            if (isFinishing || isDestroyed || activePlatform != ContentPlatform.HIPPO) return@post
            result.onSuccess { dramas ->
                if (dramas.isEmpty()) {
                    Toast.makeText(this, "该剧单暂时没有短剧内容", Toast.LENGTH_LONG).show()
                    return@onSuccess
                }
                val labels = dramas.map { drama ->
                    drama.title + drama.badge.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
                }.toTypedArray()
                AlertDialog.Builder(this)
                    .setTitle(playlist.title)
                    .setItems(labels) { _, index ->
                        dramas.getOrNull(index)?.let { openDetail(it) }
                    }
                    .setNegativeButton("返回", null)
                    .show()
            }.onFailure { error ->
                Toast.makeText(this, "剧单加载失败：${error.message ?: "请稍后重试"}", Toast.LENGTH_LONG).show()
            }
        }
    }
}
