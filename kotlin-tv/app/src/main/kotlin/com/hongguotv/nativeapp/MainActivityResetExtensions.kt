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

    internal fun MainActivity.resetPlaybackPreferences() {
        // 只重置「播放偏好」分组，不动片单类型、系统风格、系统时间和片源入口等设备偏好。
        library.resetPlaybackPreferences()
        getSharedPreferences("playback-extras-v1", Context.MODE_PRIVATE).edit().clear().apply()
        getSharedPreferences("third-sources-v1", Context.MODE_PRIVATE).edit()
            .remove("playbackSpeed").remove("resizeMode").remove("skipMarks").apply()
        getSharedPreferences("startup_playback", Context.MODE_PRIVATE).edit().clear().apply()
        getSharedPreferences("playback-settings-v1", Context.MODE_PRIVATE).edit().remove("sleep_action_mode").apply()
        getSharedPreferences("playback-renderer-v1", Context.MODE_PRIVATE).edit().remove("video_renderer_mode").apply()
    }

    internal fun MainActivity.showResetOptions(anchor: View) {
        val content=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        content.addView(TvDialog.title(this,"重置软件"))
        content.addView(TvDialog.body(this,"选择一种重置方式。恢复设置不会动你的个人记录；清除所有数据则会让应用回到首次使用状态。"))
        lateinit var optionsDialog: AlertDialog
        fun option(title:String,description:String,onClick:()->Unit): LinearLayout =
            LinearLayout(this).apply {
                orientation=LinearLayout.VERTICAL
                isFocusable=true; isClickable=true
                setPadding(dp(16),dp(13),dp(16),dp(13))
                background=rounded(surface,TvStyle.outline)
                setOnFocusChangeListener { _,focused -> background=rounded(if(focused) TvStyle.raised else surface,if(focused) accent else TvStyle.outline) }
                setOnClickListener { onClick() }
                addView(text(title,16f).apply { setTypeface(null,Typeface.BOLD); setTextColor(white) })
                addView(text(description,13f,muted).apply { setPadding(0,dp(5),0,0) })
            }
        fun cacheBytes():Long = listOfNotNull(cacheDir,externalCacheDir).sumOf { dir ->
            dir.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
        }
        fun cacheSizeLabel(bytes:Long):String = when {
            bytes < 1024L -> "$bytes B"
            bytes < 1024L*1024L -> "${bytes/1024L} KB"
            else -> "%.1f MB".format(bytes/1048576f)
        }
        val cacheOption=option("清理缓存","正在计算缓存占用…") {
            optionsDialog.dismiss()
            TvDialog.confirm(this,"清理缓存","清除封面图片、已下载的安装包等缓存数据，不影响收藏和观看记录。") {
                io.execute {
                    val result = runCatching {
                        check(artwork.clearDiskCache()) { "封面缓存删除不完整" }
                        listOfNotNull(cacheDir, externalCacheDir).distinctBy { it.absolutePath }.forEach { dir ->
                            val children = dir.listFiles()
                            check(children != null || !dir.exists()) { "无法读取缓存目录：${dir.name}" }
                            children.orEmpty().forEach { child ->
                                // artwork-v1 已由 ArtworkLoader 在同步锁内清理，避免与图片任务竞争。
                                if (dir.absolutePath == cacheDir.absolutePath && child.name == "artwork-v1") return@forEach
                                check(child.deleteRecursively()) { "无法删除缓存：${child.name}" }
                            }
                        }
                    }
                    main.post {
                        if(isDestroyed || resettingAllData) return@post
                        val message = result.fold(
                            onSuccess = { "缓存已清理" },
                            onFailure = { "缓存清理失败：${it.message ?: "存储访问失败"}" }
                        )
                        Toast.makeText(this,message,Toast.LENGTH_LONG).show()
                    }
                }
            }.show()
        }
        val cacheDescription=cacheOption.getChildAt(1) as TextView
        io.execute {
            val bytes=cacheBytes()
            main.post {
                if(!isDestroyed && optionsDialog.isShowing) {
                    cacheDescription.text="当前占用：${cacheSizeLabel(bytes)}"
                }
            }
        }

        val settingsOption=option("恢复软件设置","恢复播放、弹幕、界面和遥控器默认值；保留观看记录、收藏、稍后看及片源配置。") {
            optionsDialog.dismiss()
            TvDialog.confirm(this,"恢复软件设置","将恢复默认播放与界面设置。观看记录、收藏、稍后看、片源配置和账号信息都会保留。") {
                library.resetSettings()
                getSharedPreferences("playback-extras-v1",Context.MODE_PRIVATE).edit().clear().apply()
                // 仅清理探活/聚合搜索缓存；倍速、画面比例和跳过设置保持不变。
                ThirdStore(this).clearProbeCache()
                getSharedPreferences("startup_playback",Context.MODE_PRIVATE).edit().clear().apply()
                getSharedPreferences("app_updates",Context.MODE_PRIVATE).edit().clear().apply()
                getSharedPreferences("random_pool",Context.MODE_PRIVATE).edit().clear().apply()
                // 这些偏好独立于 Library：恢复定时关闭、弹幕颜色、渲染方式、片源入口和设备方向默认值。
                getSharedPreferences("playback-settings-v1",Context.MODE_PRIVATE).edit().clear().apply()
                getSharedPreferences("playback-renderer-v1",Context.MODE_PRIVATE).edit().clear().apply()
                getSharedPreferences("appearance-settings-v1",Context.MODE_PRIVATE).edit().clear().apply()
                getSharedPreferences("playback-device-v1",Context.MODE_PRIVATE).edit().clear().apply()
                TvStyle.apply(library.theme)
                applyDeviceOrientationPolicy()
                applyVideoRenderer()
                settingsFocusKey="重置软件"
                showCatalog()
                Toast.makeText(this,"软件设置已恢复默认，个人记录已保留",Toast.LENGTH_LONG).show()
            }.show()
        }
        val allOption=option("清除所有数据","清空账号、本机设置、观看记录、收藏、片源和缓存，重新启动后相当于首次使用。") {
            optionsDialog.dismiss()
            TvDialog.confirm(this,"清除所有数据","此操作不可撤销！本机登录信息、观看记录、收藏、稍后看、片源配置及所有设置都会被清除。已导出的备份文件不受影响。确定继续吗？") {
                clearAllLocalData()
            }.show()
        }
        val buttons=LinearLayout(this).apply { gravity=Gravity.END; setPadding(0,dp(14),0,0) }
        val cancel=TvDialog.capsuleButton(this,"取消") {}
        buttons.addView(cancel)
        content.addView(cacheOption,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(10) })
        content.addView(settingsOption,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(10) })
        content.addView(allOption,LinearLayout.LayoutParams(-1,-2))
        content.addView(buttons)
        optionsDialog=AlertDialog.Builder(this).setView(TvDialog.card(this,content)).create()
        TvDialog.styleWindow(this,optionsDialog,0.72f,720) { cacheOption.requestFocus() }
        cancel.setOnClickListener { optionsDialog.dismiss() }
        optionsDialog.setOnDismissListener { if(anchor.isAttachedToWindow) anchor.requestFocus() }
        optionsDialog.show()
        cacheOption.post { if(optionsDialog.isShowing) cacheOption.requestFocus() }
    }

    internal fun MainActivity.clearAllLocalData() {
        if(resettingAllData) return
        resettingAllData=true
        // 先关闭所有可能写入本地存储的独立资源所有者；清理线程会等待它们真正退出。
        favoriteMonitor.destroy()
        updater.destroy()
        cloudAccount.stopForReset()
        cancelWork(); cancelPrefetch()
        // 这两个执行器也可能持有预取/网络清理任务，清除前必须停止并等待。
        prefetchWorker.shutdownNow()
        networkCleanup.shutdownNow()
        artwork.close()
        tvTools.destroy()
        player?.pause(); saveProgress(); releasePlayer()
        stopDebugLoggingForReset()
        debugLogExecutor.shutdown()
        Toast.makeText(this,"正在清除本机数据…",Toast.LENGTH_SHORT).show()
        Thread(clearTask@{
            val ownersStopped = runCatching {
                val accountStopped = cloudAccount.awaitStopped(20, java.util.concurrent.TimeUnit.SECONDS)
                val updaterStopped = updater.awaitStopped(20, java.util.concurrent.TimeUnit.SECONDS)
                val artworkStopped = artwork.awaitClosed(20, java.util.concurrent.TimeUnit.SECONDS)
                val favoriteStopped = favoriteMonitor.awaitStopped(20, java.util.concurrent.TimeUnit.SECONDS)
                val toolsStopped = tvTools.awaitStopped(20, java.util.concurrent.TimeUnit.SECONDS)
                val debugStopped = debugLogExecutor.awaitTermination(20, java.util.concurrent.TimeUnit.SECONDS)
                val prefetchStopped = prefetchWorker.awaitTermination(20, java.util.concurrent.TimeUnit.SECONDS)
                val networkStopped = networkCleanup.awaitTermination(20, java.util.concurrent.TimeUnit.SECONDS)
                accountStopped && updaterStopped && artworkStopped && favoriteStopped && toolsStopped && debugStopped && prefetchStopped && networkStopped
            }.getOrDefault(false)
            if (!ownersStopped) {
                main.post {
                    restartAfterLocalResetFailure("后台任务仍在退出，暂未清除数据。应用已重新启动，请稍后重试。")
                }
                return@clearTask
            }
            // 先终止并等待所有使用 Library 的 Activity IO 任务，再关闭 Library。
            // 否则 in-flight 工作可能在 closeAndAwait 之后继续触碰已关闭的写入队列。
            repository.http.dispatcher.cancelAll()
            io.shutdownNow()
            val ioStopped = runCatching { io.awaitTermination(15,java.util.concurrent.TimeUnit.SECONDS) }.getOrDefault(false)
            if (!ioStopped) {
                main.post {
                    restartAfterLocalResetFailure("后台任务仍在退出，暂未清除数据。应用已重新启动，请稍后重试。")
                }
                return@clearTask
            }
            val drained=runCatching { library.closeAndAwait(20) }.getOrDefault(false)
            if(!drained) {
                main.post {
                    // closeAndAwait 已关闭旧 Library；不能继续留在当前 Activity 使用已关闭的写入队列。
                    restartAfterLocalResetFailure("本机记录仍在保存，暂未清除。已重新启动应用，请稍后再试。")
                }
                return@clearTask
            }
            runCatching {
                databaseList().forEach { name -> check(deleteDatabase(name)) { "无法删除数据库：$name" } }
                val prefsDir=java.io.File(filesDir.parentFile,"shared_prefs")
                val preferenceNames=(prefsDir.listFiles()?.filter { it.isFile && it.name.endsWith(".xml") }
                    ?.map { it.name.removeSuffix(".xml") } ?: emptyList()) + listOf(
                    "native-library-v1","native-home-cache-v1","cloud_account_secure",
                    "playback-extras-v1","third-sources-v1","startup_playback","app_updates","random_pool"
                )
                preferenceNames.distinct().forEach { name ->
                    check(getSharedPreferences(name,Context.MODE_PRIVATE).edit().clear().commit()) { "无法清除本机设置：$name" }
                }
                fun deleteChildren(dir: java.io.File) {
                    val children = dir.listFiles()
                    check(children != null || !dir.exists()) { "无法读取目录：${dir.name}" }
                    children.orEmpty().forEach { child ->
                        check(child.deleteRecursively()) { "无法删除文件：${child.name}" }
                    }
                }
                listOfNotNull(filesDir,cacheDir,codeCacheDir,noBackupFilesDir,externalCacheDir)
                    .distinctBy { it.absolutePath }.forEach(::deleteChildren)
                getExternalFilesDirs(null).filterNotNull().distinctBy { it.absolutePath }.forEach(::deleteChildren)
            }.onFailure { error ->
                main.post {
                    // Library 与 IO 执行器已关闭；失败时重启可重新创建它们并保留未完成写入队列引用。
                    restartAfterLocalResetFailure("清理未完成：${error.message ?: "存储访问失败"}。应用将重新启动。")
                }
                return@clearTask
            }
            main.post {
                val launch=packageManager.getLaunchIntentForPackage(packageName)
                if(launch==null) {
                    Toast.makeText(this,"数据已清除，请手动重新打开红果TV",Toast.LENGTH_LONG).show()
                    finishAffinity()
                    return@post
                }
                runCatching {
                    // 清理已完成，直接清空旧任务栈并启动全新的首页，不再通过闹钟延迟重启。
                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    startActivity(launch)
                    finishAffinity()
                }.onFailure { error ->
                    Toast.makeText(this,"数据已清除，但自动重启失败：${error.message ?: "请手动打开"}",Toast.LENGTH_LONG).show()
                    finishAffinity()
                }
            }
        },"hongguotv-clear-data").start()
    }


/** 清理流程已经关闭当前 Activity 的 Library/IO 资源，失败后必须重建运行时而不能原地继续。 */
private fun MainActivity.restartAfterLocalResetFailure(message: String) {
    // Keep the old Activity in reset mode: its Library may already be closed.
    Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    val launch = packageManager.getLaunchIntentForPackage(packageName)
    if (launch == null) {
        finishAffinity()
        return
    }
    runCatching {
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        startActivity(launch)
        finishAffinity()
    }.onFailure {
        Toast.makeText(this, "无法自动重启，请手动重新打开红果TV。", Toast.LENGTH_LONG).show()
        finishAffinity()
    }
}
