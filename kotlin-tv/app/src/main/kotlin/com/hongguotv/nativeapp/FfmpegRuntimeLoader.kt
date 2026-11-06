// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.os.Looper
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.FfmpegLibrary
import java.io.File

/**
 * Activates a fully downloaded, hash-verified NextLib runtime.
 *
 * Must run off the main thread and before any NextLib renderer is initialized.
 * Native loading is process-global and cannot be safely repeated with another
 * directory; install a new runtime and restart the app before activating it.
 */
@androidx.media3.common.util.UnstableApi
internal object FfmpegRuntimeLoader {
    sealed class Result {
        data class Ready(val version: String) : Result()
        data class Unavailable(val reason: String, val cause: Throwable? = null) : Result()
    }

    @Volatile
    private var activationAttempted = false

    @Synchronized
    fun activate(directory: File): Result {
        check(!Looper.getMainLooper().isCurrentThread) {
            "FFmpeg 原生库加载必须在后台线程执行"
        }
        if (activationAttempted) {
            return Result.Unavailable("本进程已经尝试加载过 FFmpeg；切换运行库需要重启应用")
        }
        activationAttempted = true

        return runCatching {
            require(directory.isDirectory) { "FFmpeg 运行库目录不存在" }
            val required = listOf(
                "libavutil.so",
                "libswresample.so",
                "libswscale.so",
                "libavcodec.so",
                "libmedia3ext.so"
            )
            required.forEach { name ->
                val file = File(directory, name)
                require(file.isFile && file.canRead()) { "缺少 FFmpeg 原生库：$name" }
                require(!file.canWrite()) { "原生库必须为只读文件：$name" }
            }
            FfmpegLibrary.setRuntimeLibraryDirectory(directory)
            check(FfmpegLibrary.isAvailable()) { "FFmpeg JNI 库加载失败" }
            val version = FfmpegLibrary.getVersion() ?: "unknown"
            Result.Ready(version)
        }.getOrElse {
            Result.Unavailable(it.message ?: "FFmpeg 原生库加载失败", it)
        }
    }
}
