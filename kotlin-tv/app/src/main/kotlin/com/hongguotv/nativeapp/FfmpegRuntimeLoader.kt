// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.os.Looper
import android.util.Log
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.FfmpegLibrary
import java.io.File

/** 激活已下载且校验过的 NextLib 原生运行库。原生加载开始后不在同一进程盲目重试。 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal object FfmpegRuntimeLoader {
    private const val TAG = "HongguoTV-FFmpeg"
    sealed class Result {
        data class Ready(val version: String) : Result()
        data class Unavailable(val reason: String, val cause: Throwable? = null) : Result()
    }
    @Volatile private var activationAttempted = false
    @Volatile private var readyVersion: String? = null
    val isReady: Boolean get() = readyVersion != null

    fun activateInstalled(context: android.content.Context): Result {
        check(!Looper.getMainLooper().isCurrentThread) { "FFmpeg 原生库加载必须在后台线程执行" }
        val abi = FfmpegRuntimeInstaller.Abi.current()
            ?: return Result.Unavailable("当前应用进程 ABI 暂不支持")
        val root = File(context.noBackupFilesDir, "ffmpeg-runtime")
        val candidate = root.listFiles()?.asSequence()
            ?.filter { it.isDirectory && !it.name.startsWith(".") }
            ?.map { File(it, abi.folder) }?.filter { it.isDirectory }
            ?.maxByOrNull { it.parentFile?.lastModified() ?: 0L }
            ?: return Result.Unavailable("尚未下载 FFmpeg 软件解码运行库")
        return activate(candidate)
    }

    @Synchronized
    fun activate(directory: File): Result {
        check(!Looper.getMainLooper().isCurrentThread) { "FFmpeg 原生库加载必须在后台线程执行" }
        readyVersion?.let { return Result.Ready(it) }
        if (activationAttempted) {
            Log.w(TAG, "本进程此前已执行原生加载；避免重复加载不完整 JNI 状态，不再尝试")
            return Result.Unavailable("本进程已经尝试加载过 FFmpeg；请重启应用后重试")
        }
        val required = listOf("libavutil.so", "libswresample.so", "libswscale.so", "libavcodec.so", "libmedia3ext.so")
        try {
            require(directory.isDirectory) { "FFmpeg 运行库目录不存在" }
            required.forEach { name ->
                val file = File(directory, name)
                require(file.isFile && file.canRead()) { "缺少 FFmpeg 原生库：$name" }
                require(!file.canWrite()) { "原生库必须为只读文件：$name" }
            }
        } catch (problem: Exception) {
            Log.e(TAG, "FFmpeg 运行库文件检查失败 directory=$directory", problem)
            return Result.Unavailable(problem.message ?: "FFmpeg 运行库文件检查失败", problem)
        }

        // 文件校验失败不消耗尝试次数；进入 JNI 加载后可能已有部分 so 被系统装载，
        // 此时失败不能在同一进程重复 dlopen，避免出现更难定位的 native 崩溃。
        activationAttempted = true
        return try {
            FfmpegLibrary.setRuntimeLibraryDirectory(directory)
            check(FfmpegLibrary.isAvailable()) { "FFmpeg JNI 库加载失败" }
            val version = FfmpegLibrary.getVersion() ?: "unknown"
            readyVersion = version
            Log.i(TAG, "FFmpeg 原生运行库激活成功 version=$version directory=$directory")
            Result.Ready(version)
        } catch (problem: Throwable) {
            Log.e(TAG, "FFmpeg 原生运行库激活失败 directory=$directory", problem)
            if (problem is VirtualMachineError || problem is ThreadDeath) throw problem
            Result.Unavailable(problem.message ?: "FFmpeg 原生库加载失败", problem)
        }
    }
}
