// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import android.os.Build
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Optional FFmpeg/NextLib runtime downloader.
 *
 * This installs and verifies native libraries in app-private storage only.
 * It intentionally does not load them: upstream NextLib currently uses
 * System.loadLibrary(), so its loader must first be adapted for absolute paths.
 *
 * Call installAsync() from the UI layer. Never call install() on the main thread.
 */
internal class FfmpegRuntimeInstaller(context: Context) {
    private val appContext = context.applicationContext
    private val runtimeRoot = File(appContext.noBackupFilesDir, "ffmpeg-runtime")
    private val worker = Executors.newSingleThreadExecutor()
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.MINUTES)
        .build()

    enum class Abi(val folder: String, val elfClass: Int, val machine: Int) {
        ARM32("armeabi-v7a", 1, 40),
        ARM64("arm64-v8a", 2, 183),
        X86("x86", 1, 3),
        X86_64("x86_64", 2, 62);

        companion object {
            fun current(): Abi? {
                // Match the ABI of this app process, not merely the device CPU.
                // A 64-bit device may also advertise 32-bit ABIs.
                val processAbis = if (android.os.Process.is64Bit()) {
                    Build.SUPPORTED_64_BIT_ABIS
                } else {
                    Build.SUPPORTED_32_BIT_ABIS
                }
                return processAbis.firstNotNullOfOrNull { name ->
                    entries.firstOrNull { it.folder == name }
                }
            }
        }
    }

    data class LibraryArtifact(
        val fileName: String,
        val url: String,
        val sha256: String,
        val loadOrder: Int
    )

    data class RuntimeManifest(
        val version: String,
        val abi: Abi,
        val libraries: List<LibraryArtifact>
    )

    sealed class Result {
        data class Installed(val directory: File, val version: String, val abi: Abi) : Result()
        data class Failed(val reason: String, val cause: Throwable? = null) : Result()
    }

    fun installAsync(manifestUrl: String, callback: (Result) -> Unit) {
        worker.execute {
            val result = runCatching { install(manifestUrl) }.fold(
                onSuccess = { it },
                onFailure = { Result.Failed(it.message ?: "FFmpeg 运行库安装失败", it) }
            )
            android.os.Handler(appContext.mainLooper).post { callback(result) }
        }
    }

    /** Download, verify, and activate the runtime on the worker thread. */
    fun installAndActivateAsync(manifestUrl: String, callback: (Result) -> Unit) {
        worker.execute {
            val result = runCatching {
                val installed = install(manifestUrl)
                when (val loaded = FfmpegRuntimeLoader.activate(installed.directory)) {
                    is FfmpegRuntimeLoader.Result.Ready -> installed
                    is FfmpegRuntimeLoader.Result.Unavailable ->
                        error("运行库已下载，但当前进程加载失败：${loaded.reason}；请重启应用后重试")
                }
            }.fold(
                onSuccess = { it },
                onFailure = { Result.Failed(it.message ?: "FFmpeg 运行库安装或加载失败", it) }
            )
            android.os.Handler(appContext.mainLooper).post { callback(result) }
        }
    }

    /**
     * Manifest example:
     * {
     *   "version":"1.0.0",
     *   "abi":"arm64-v8a",
     *   "libraries":[
     *     {"file":"libavutil.so","url":"https://host/libavutil.so",
     *      "sha256":"64 hex chars","loadOrder":1}
     *   ]
     * }
     */
    fun install(manifestUrl: String): Result.Installed {
        check(!android.os.Looper.getMainLooper().isCurrentThread) {
            "FFmpeg runtime installation must not run on the main thread"
        }
        require(manifestUrl.startsWith("https://", ignoreCase = true)) {
            "运行库清单必须使用 HTTPS"
        }
        val abi = Abi.current() ?: error("当前处理器架构暂不支持 FFmpeg 软件解码")
        val manifestRequest = Request.Builder().url(manifestUrl).get().build()
        val manifestBody = client.newCall(manifestRequest).execute().use { response ->
            if (!response.isSuccessful) error("获取运行库清单失败：HTTP " + response.code)
            response.body?.string() ?: error("运行库清单为空")
        }
        val manifest = parseManifest(manifestBody)
        require(manifest.version.matches(Regex("[A-Za-z0-9._-]{1,64}"))) {
            "运行库版本号格式错误"
        }
        require(manifest.abi == abi) {
            "运行库架构不匹配：设备 " + abi.folder + "，清单 " + manifest.abi.folder
        }
        require(manifest.libraries.isNotEmpty()) { "运行库清单没有库文件" }

        val targetDir = File(runtimeRoot, manifest.version + "/" + abi.folder)
        val stagingDir = File(runtimeRoot, ".staging-" + manifest.version + "-" + abi.folder)
        stagingDir.deleteRecursively()
        check(stagingDir.mkdirs() || stagingDir.isDirectory) { "无法创建运行库暂存目录" }

        try {
            manifest.libraries.sortedBy { it.loadOrder }.forEach { artifact ->
                require(artifact.fileName.matches(Regex("lib[A-Za-z0-9_.+-]+\\.so"))) {
                    "非法原生库文件名：" + artifact.fileName
                }
                require(artifact.url.startsWith("https://", ignoreCase = true)) {
                    "原生库下载地址必须使用 HTTPS"
                }
                require(artifact.sha256.matches(Regex("[A-Fa-f0-9]{64}"))) {
                    "SHA256 格式错误：" + artifact.fileName
                }
                val temp = File(stagingDir, artifact.fileName + ".part")
                val destination = File(stagingDir, artifact.fileName)
                val request = Request.Builder().url(artifact.url).get().build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        error("下载 " + artifact.fileName + " 失败：HTTP " + response.code)
                    }
                    val body = response.body ?: error("下载内容为空：" + artifact.fileName)
                    body.byteStream().use { input ->
                        FileOutputStream(temp).use { output -> input.copyTo(output) }
                    }
                }
                require(sha256(temp).equals(artifact.sha256, ignoreCase = true)) {
                    "SHA256 校验失败：" + artifact.fileName
                }
                validateElf(temp, abi, artifact.fileName)
                // Android 14+ requires dynamically loaded code to be read-only.
                check(temp.setReadOnly()) { "无法将原生库设为只读：" + artifact.fileName }
                check(temp.renameTo(destination)) { "无法保存原生库：" + artifact.fileName }
            }

            // Keep the previous runtime intact until the new directory is ready.
            val backupDir = File(runtimeRoot, ".backup-" + manifest.version + "-" + abi.folder)
            backupDir.deleteRecursively()
            val hadPrevious = targetDir.exists()
            if (hadPrevious) {
                check(targetDir.renameTo(backupDir)) { "无法备份现有 FFmpeg 运行库" }
            }
            if (!stagingDir.renameTo(targetDir)) {
                if (hadPrevious) backupDir.renameTo(targetDir)
                error("无法安装 FFmpeg 运行库")
            }
            backupDir.deleteRecursively()
            return Result.Installed(targetDir, manifest.version, abi)
        } catch (error: Throwable) {
            stagingDir.deleteRecursively()
            throw error
        }
    }

    private fun parseManifest(raw: String): RuntimeManifest {
        val json = JSONObject(raw)
        val abi = Abi.entries.firstOrNull { it.folder == json.getString("abi") }
            ?: error("清单包含不支持的 ABI")
        val items = json.getJSONArray("libraries")
        val libraries = (0 until items.length()).map { index ->
            val item = items.getJSONObject(index)
            LibraryArtifact(
                fileName = item.getString("file"),
                url = item.getString("url"),
                sha256 = item.getString("sha256"),
                loadOrder = item.optInt("loadOrder", index)
            )
        }
        return RuntimeManifest(json.getString("version"), abi, libraries)
    }

    private fun validateElf(file: File, abi: Abi, name: String) {
        val header = ByteArray(20)
        file.inputStream().use { input ->
            require(input.read(header) == header.size) { "原生库文件过短：" + name }
        }
        require(
            header[0] == 0x7f.toByte() && header[1] == 'E'.code.toByte() &&
                header[2] == 'L'.code.toByte() && header[3] == 'F'.code.toByte()
        ) { "不是有效 ELF 文件：" + name }
        val elfClass = header[4].toInt() and 0xff
        val machine = (header[18].toInt() and 0xff) or ((header[19].toInt() and 0xff) shl 8)
        require(elfClass == abi.elfClass && machine == abi.machine) {
            "ELF 架构不匹配：" + name + "，设备 ABI=" + abi.folder
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
