// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.graphics.drawable.GradientDrawable
import android.view.*
import android.view.inputmethod.InputMethodManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Uri
import android.net.Network
import android.net.NetworkCapabilities
import android.widget.*
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.media3.common.*
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.source.preload.DefaultPreloadManager
import androidx.media3.ui.PlayerView
import com.hongguotv.core.*
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

internal fun MainActivity.backupFolder(): java.io.File {
    return java.io.File(android.os.Environment.getExternalStorageDirectory(), "HongGuoTV")
}


    internal fun MainActivity.showFileBackupDialog(anchor: View) {
        tvTools.choose(
            "备份与恢复",
            listOf("导出备份文件","从备份文件恢复"),
            anchor,
            { choice ->
                pendingBackupAction = choice
                pendingBackupAnchor = anchor
                ensureBackupStoragePermission()
            },
            "备份会包含红果账户登录凭据（明文），请勿分享备份文件。云端账户的观看历史、收藏和预约记录不会写入备份；恢复后会尝试登录并从云端刷新。"
        )
    }

    internal fun MainActivity.hasBackupStoragePermission(): Boolean {
        val readGranted = Build.VERSION.SDK_INT < 23 ||
            checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val writeGranted = Build.VERSION.SDK_INT < 23 ||
            checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED
        return readGranted && writeGranted
    }

    internal fun MainActivity.ensureBackupStoragePermission() {
        val readGranted = Build.VERSION.SDK_INT < 23 ||
            checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val writeGranted = Build.VERSION.SDK_INT < 23 ||
            checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (readGranted && writeGranted) {
            runPendingBackupAction()
            return
        }
        if (Build.VERSION.SDK_INT >= 23) {
            requestPermissions(
                arrayOf(
                    android.Manifest.permission.READ_EXTERNAL_STORAGE,
                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                ),
                REQUEST_STORAGE_PERMISSION
            )
        } else {
            runPendingBackupAction()
        }
    }

    internal fun MainActivity.runPendingBackupAction() {
        if (resettingAllData) return
        val action = pendingBackupAction
        val anchor = pendingBackupAnchor
        pendingBackupAction = -1
        when (action) {
            0 -> exportLibraryBackupToPublicFolder(anchor)
            1 -> showLocalBackupFiles(anchor)
            2 -> startDebugLogging(anchor)
            3 -> tvTools.libraryTransfer(anchor)
        }
    }

    internal fun MainActivity.restoreBackupAccount(data: BackupData, anchor: View?) {
        if (resettingAllData) return
        val payload = data.accountState?.takeIf { it.isNotBlank() }
        if (payload == null) {
            // 备份没有账户信息时，沿用当前登录账号，只把现有收藏补充到该账号。
            cloudAccount.syncLocalFavorites(library.favorites())
            return
        }
        // 先恢复备份所属账号，再同步收藏，避免误传到恢复前登录的旧账号。
        cloudAccount.restoreJson(payload) { success, message ->
            if (isDestroyed || resettingAllData) return@restoreJson
            if (success) cloudAccount.syncLocalFavorites(library.favorites())
            tvTools.info(if (success) "账户已恢复" else "账户恢复提示", message, anchor)
        }
    }

    internal fun MainActivity.exportLibraryBackupToPublicFolder(anchor: View?) {
        if (resettingAllData) return
        val snapshot = runCatching { library.snapshot().copy(accountState = cloudAccount.backupJson(), playbackExtras = extras.backupSnapshot(library.skipMarksSnapshot())) }.getOrElse {
            tvTools.info("备份失败", "无法读取当前记录，请稍后重试。", anchor)
            return
        }
        io.execute {
            val result = runCatching {
                val folder = backupFolder()
                if (!folder.exists() && !folder.mkdirs()) error("无法创建目录：${folder.absolutePath}")
                if (!folder.isDirectory || !folder.canWrite()) error("目录不可写：${folder.absolutePath}")
                val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(java.util.Date())
                val file = java.io.File(folder, "hongguotv-backup-$stamp.json")
                file.writeText(LibraryBackup.encode(snapshot), Charsets.UTF_8)
                file.absolutePath
            }
            main.post {
                if (isDestroyed || resettingAllData) return@post
                result.onSuccess { path ->
                    tvTools.info("备份已保存", "备份文件已保存到公共目录：\n${path}\n\n卸载红果 TV 通常不会删除此文件。", anchor)
                }.onFailure { error ->
                    tvTools.info("备份失败", "无法保存备份文件：${error.message ?: "未知错误"}", anchor)
                }
            }
        }
    }

    internal fun MainActivity.showLocalBackupFiles(anchor: View?) {
        if (resettingAllData) return
        val folder = backupFolder()
        val ready = runCatching {
            if (!folder.exists()) folder.mkdirs()
            folder.isDirectory
        }.getOrDefault(false)
        if (!ready) {
            tvTools.info("无法打开备份目录", "无法访问公共目录：\n${folder.absolutePath}\n\n请检查存储权限或设备存储状态。", anchor)
            return
        }
        val files = folder.listFiles()?.filter {
            it.isFile && it.extension.equals("json", true) && !it.name.startsWith("hongguotv-debug-",true)
        }?.sortedByDescending { it.lastModified() }.orEmpty()
        if (files.isEmpty()) {
            tvTools.info(
                "没有找到备份文件",
                "请先将备份 JSON 文件复制到以下目录，再重新选择恢复：\n\n${folder.absolutePath}",
                anchor
            )
            return
        }
        tvTools.choose("选择备份文件", files.map { it.name }, anchor, { which ->
            val selected = files.getOrNull(which)
            if (selected != null) {
                tvTools.choose(selected.name, listOf("恢复此备份", "删除此备份"), anchor, { action ->
                    when (action) {
                        0 -> importLibraryBackup(Uri.fromFile(selected), anchor)
                        1 -> tvTools.confirm(
                            "删除备份文件",
                            "确定删除这条本地备份吗？\n\n${selected.name}\n\n删除后无法恢复。",
                            anchor
                        ) {
                            io.execute {
                                val deleted = runCatching { selected.isFile && selected.delete() }.getOrDefault(false)
                                main.post {
                                    if (isDestroyed || resettingAllData) return@post
                                    if (deleted) {
                                        Toast.makeText(this, "备份已删除", Toast.LENGTH_SHORT).show()
                                        showLocalBackupFiles(anchor)
                                    } else {
                                        tvTools.info("删除失败", "无法删除备份文件，请检查存储权限或文件是否被占用。", anchor)
                                    }
                                }
                            }
                        }
                    }
                })
            }
        })
    }

    internal fun MainActivity.exportLibraryBackup(uri: Uri) {
        if (resettingAllData) return
        val snapshot=runCatching { library.snapshot().copy(accountState = cloudAccount.backupJson(), playbackExtras = extras.backupSnapshot(library.skipMarksSnapshot())) }.getOrElse {
            tvTools.info("备份失败","无法读取当前记录，请稍后重试。",null)
            return
        }
        io.execute {
            val result=runCatching {
                val bytes=LibraryBackup.encode(snapshot).toByteArray(Charsets.UTF_8)
                contentResolver.openOutputStream(uri,"wt")?.use { output -> output.write(bytes); output.flush() }
                    ?: error("无法打开备份文件")
            }
            main.post {
                if(isDestroyed || resettingAllData) return@post
                result.onSuccess { Toast.makeText(this,"备份文件已导出",Toast.LENGTH_LONG).show() }
                    .onFailure { Toast.makeText(this,"导出失败：${it.message ?: "无法写入文件"}",Toast.LENGTH_LONG).show() }
            }
        }
    }

    internal fun MainActivity.importLibraryBackup(uri: Uri,anchor: View?) {
        if (resettingAllData) return
        io.execute {
            val result=runCatching {
                val input=contentResolver.openInputStream(uri) ?: error("无法读取所选文件")
                val bytes=input.use { stream ->
                    val output=java.io.ByteArrayOutputStream()
                    val buffer=ByteArray(8192)
                    var total=0
                    while(true) {
                        val count=stream.read(buffer)
                        if(count<0) break
                        total+=count
                        require(total<=LibraryBackup.MAX_BYTES) { "备份文件超过 2 MB 限制" }
                        output.write(buffer,0,count)
                    }
                    output.toByteArray()
                }
                LibraryBackup.decode(String(bytes,Charsets.UTF_8))
            }
            main.post {
                if(isDestroyed || resettingAllData) return@post
                result.onFailure { Toast.makeText(this,"备份文件无效：${it.message ?: "无法解析"}",Toast.LENGTH_LONG).show() }
                result.onSuccess { data ->
                    tvTools.choose(
                        "选择恢复方式",
                        listOf(
                            "恢复记录并重置播放偏好设置",
                            "恢复记录并保留当前播放偏好设置",
                            "恢复记录及备份中的播放偏好设置"
                        ),
                        anchor,
                        { choice ->
                            val resetPlayback = choice == 0
                            val restoreSettings = choice == 2
                            val accountNote = if (data.accountState.isNullOrBlank()) "\n备份中没有账户登录信息。" else "\n备份包含明文红果账户凭据，恢复时会尝试登录并刷新云端数据；云端观影资料本身不在备份中。"
                            val preferenceNote = when (choice) {
                                0 -> "\n当前播放偏好设置将恢复默认值。"
                                1 -> "\n当前播放偏好设置将保持不变。"
                                else -> "\n将恢复备份中的播放偏好设置。"
                            }
                            val message = "备份包含 ${data.favorites.size} 部收藏、${data.history.size} 条观看记录、${data.later.size} 部稍后看和 ${data.searches.size} 条搜索记录。\n\n红果收藏、历史等主记录将按备份内容恢复；第三方片源只追加本机缺少的源，第三方观看历史和收藏按更新时间合并，避免覆盖较新的记录。${preferenceNote}${accountNote}"
                            tvTools.confirm("确认恢复备份", message, anchor) {
                                runCatching {
                                    favoriteMonitor.stop()
                                    library.restore(data, restoreSettings)
                                    if (resetPlayback) {
                                        resetPlaybackPreferences()
                                        TvStyle.apply(library.theme)
                                    }
                                    if (restoreSettings) {
                                        val restoredExtras = data.playbackExtras ?: BackupPlaybackExtras()
                                        extras.restoreBackup(restoredExtras)
                                        library.restoreSkipMarks(restoredExtras.skipMarks)
                                    }
                                    tabState.clear(); tabFocus.clear(); catalogFocus = ""; page = 1
                                    showCatalog()
                                    favoriteMonitor.check()
                                    Toast.makeText(this, "备份记录已恢复", Toast.LENGTH_LONG).show()
                                    restoreBackupAccount(data, anchor)
                                }.onFailure { tvTools.info("恢复失败", "记录未能完整恢复：${it.message ?: "未知错误"}", anchor) }
                            }
                        }
                    )
                }
            }
        }
    }

internal fun MainActivity.handleBackupPermissionResult(requestCode: Int, grantResults: IntArray) {
    if (resettingAllData) return
    if (requestCode != REQUEST_STORAGE_PERMISSION) return
    val action = pendingBackupAction
    val granted = grantResults.isNotEmpty() &&
        grantResults.all { it == android.content.pm.PackageManager.PERMISSION_GRANTED }
    if (granted) {
        runPendingBackupAction()
    } else {
        tvTools.info(
            "需要存储权限",
            if (action == 2) "调试模式需要访问公共存储目录，才能将诊断日志保存到 HongGuoTV 文件夹。允许存储权限后可重新开启。"
            else if (action == 3) "远程文件管理需要访问电视公共存储目录，允许存储权限后才能通过局域网浏览和传输文件。"
            else "红果 TV 需要访问公共存储目录，才能在 HongGuoTV 文件夹中保存或读取备份。你可以在系统设置中允许存储权限后重试。",
            pendingBackupAnchor
        )
        pendingBackupAction = -1
    }
}

internal fun MainActivity.handleBackupActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
    if (resettingAllData) return
    if (resultCode != Activity.RESULT_OK) return
    val uri = data?.data ?: return
    when (requestCode) {
        REQUEST_EXPORT_BACKUP -> exportLibraryBackup(uri)
        REQUEST_IMPORT_BACKUP -> importLibraryBackup(uri, root)
    }
}
