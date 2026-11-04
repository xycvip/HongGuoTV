// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.os.Handler
import android.os.SystemClock
import com.hongguotv.core.AccountCredentials
import com.hongguotv.core.CloudAccountClient
import com.hongguotv.core.CloudProgress
import java.util.concurrent.ExecutorService

/**
 * 云端观看进度上传队列。
 *
 * 负责同集节流、合并待上传进度、串行上传及销毁前最后一次进度提交；
 * 账户状态与历史列表由 AccountController 通过回调持有。
 */
internal class AccountProgressUploader(
    private val worker: ExecutorService,
    private val main: Handler,
    private val client: CloudAccountClient,
    private val credentials: () -> AccountCredentials?,
    private val generation: () -> Int,
    private val isClosed: () -> Boolean,
    private val onProgress: (CloudProgress, Int) -> Unit,
    private val onStatus: (String) -> Unit,
    private val safeError: (Throwable) -> String,
) {
    private var lastUpload = 0L
    private var lastEpisode = ""
    private var pending: Pair<CloudProgress, Int>? = null
    private var lastProgress: Pair<CloudProgress, Int>? = null
    private var draining = false

    fun upload(progress: CloudProgress, episodes: Int, force: Boolean) {
        val account = credentials() ?: return
        val now = SystemClock.elapsedRealtime()
        val snapshot = progress to episodes
        // Keep the newest local position even when the network upload is throttled;
        // destroy() uses this snapshot for its final, forced upload.
        lastProgress = snapshot
        if (!force && progress.episodeId == lastEpisode && now - lastUpload < 30000) return
        lastUpload = now
        lastEpisode = progress.episodeId
        onProgress(progress, episodes)
        pending = snapshot
        if (draining) return
        draining = true
        drain(account, generation())
    }

    private fun drain(account: AccountCredentials, token: Int) {
        val next = pending
        if (isClosed() || token != generation()) return
        if (next == null) {
            draining = false
            return
        }
        pending = null
        worker.execute {
            val result = runCatching {
                if (!isClosed() && token == generation()) client.upload(account, next.first, next.second)
            }
            main.post {
                if (isClosed()) return@post
                if (token == generation()) {
                    onStatus(
                        if (result.isSuccess) "播放进度已上传（请在手机端核对续播）"
                        else "进度上传失败：${safeError(result.exceptionOrNull()!!)}"
                    )
                }
                drain(account, token)
            }
        }
    }

    fun reset() {
        pending = null
        lastProgress = null
        draining = false
    }

    fun finalProgress(): Pair<CloudProgress, Int>? = lastProgress
}
