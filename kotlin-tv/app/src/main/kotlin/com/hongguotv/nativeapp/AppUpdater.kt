// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.FrameLayout
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.FileProvider
import com.hongguotv.core.AppUpdate
import com.hongguotv.core.AppUpdateRepository
import com.hongguotv.core.UpdateFiles
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** Foreground-only updater: no background service, and no networking or prompts during playback. */
class AppUpdater(private val activity: Activity, private val idle: () -> Boolean) {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val isBetaChannel = BuildConfig.APPLICATION_ID == AppUpdate.BETA_PACKAGE
    private val repository = if (isBetaChannel) AppUpdateRepository(
        expectedPackageName = AppUpdate.BETA_PACKAGE,
        releaseRepository = AppUpdate.BETA_REPOSITORY,
        releaseTagPrefix = AppUpdate.BETA_TAG_PREFIX,
        latestManifestUrl = AppUpdate.BETA_LATEST_MANIFEST,
        apkFilePrefix = "HongguoTV-"
    ) else AppUpdateRepository()
    private val preferences = activity.getSharedPreferences("app_updates", Activity.MODE_PRIVATE)
    private var apk = File(activity.cacheDir, "updates/update.apk")
    private var task: Future<*>? = null
    private var generation = 0
    private var active = false
    private var busy = false
    private var downloadInProgress = false
    private var available: AppUpdate? = null
    private var ready = false
    private var permissionRequested = false
    private var dialog: AlertDialog? = null
    val showing get() = dialog?.isShowing == true
    private var statusView: TextView? = null
    private var statusDetailsView: TextView? = null
    private var anchor: View? = null
    private var actionButton: android.widget.Button? = null
    private var aboutDialogShowing = false
    private var sourceButtons: List<android.widget.Button> = emptyList()
    private var manualProxyIndex = -1
    private var pendingSource: Int? = null
    private var pendingProxyIndex: Int? = null
    private var speedView: TextView? = null
    private var status = "当前版本 ${BuildConfig.VERSION_NAME}"
    private var retryAfter = 0L
    private var promptedThisSession = false
    private var checkedThisSession = false
    @Volatile private var destroyed = false
    private val auto get() = preferences.getBoolean("automatic", true)
    private val poll = object : Runnable {
        override fun run() {
            if (!active) return
            if (idle() && dialog == null && !busy && auto) {
                val now = System.currentTimeMillis()
                val last = preferences.getLong("last_attempt", 0)
                val update = available
                when {
                    update != null && !promptedThisSession -> show(activity.currentFocus)
                    update != null && !ready && now >= retryAfter -> download()
                    !checkedThisSession -> checkedThisSession = check(false)
                    now < last || now - last >= 6 * 60 * 60 * 1000L -> check(false)
                }
            }
            main.postDelayed(this, 15000)
        }
    }
    fun resume() {
        active = true; main.removeCallbacks(poll); main.postDelayed(poll, 8000)
        if (permissionRequested) {
            permissionRequested = false
            if (Build.VERSION.SDK_INT < 26 || activity.packageManager.canRequestPackageInstalls()) install()
            else { status = "安装权限未开启，可在这里再次安装"; show(anchor) }
        } else if (!checkedThisSession && auto) {
            // Nag semantics need a fresh verdict on every app start, not the 6-hour poll cadence;
            // when the library is still loading the poll loop retries this below.
            checkedThisSession = check(false)
        }
    }
    fun pause() {
        active = false; main.removeCallbacks(poll); interrupt()
        dialog?.dismiss()
    }
    fun interrupt() {
        generation++; task?.cancel(true); task = null; repository.cancel(); busy = false; downloadInProgress = false
    }
    fun destroy() {
        if (destroyed) return
        destroyed = true
        pause()
        worker.shutdownNow()
        // TLS socket close may send close_notify; it must not run on Android's main thread.
        Thread({ repository.close() }, "hongguotv-update-cleanup").start()
    }

    /** Wait until cancelled downloads can no longer create or replace cache files. */
    fun awaitStopped(timeout: Long, unit: java.util.concurrent.TimeUnit): Boolean =
        worker.awaitTermination(timeout, unit)
    private fun updateStatus(text: String) {
        status = text
        val parts = text.split("\n", limit = 2)
        statusView?.apply {
            this.text = parts.firstOrNull().orEmpty()
            visibility = View.VISIBLE
        }
        statusDetailsView?.apply {
            this.text = parts.getOrNull(1).orEmpty()
            visibility = if (text.contains("\n")) View.VISIBLE else View.GONE
        }
    }
    private fun renderActions() {
        actionButton?.apply {
            text = if (aboutDialogShowing) "检查更新" else when { busy -> "处理中…"; ready -> "立即安装"; available != null -> "下载新版"; else -> "检查更新" }
            // Keep D-pad focus stable while the request runs; action methods reject duplicates.
            isEnabled = true
        }
        sourceButtons.forEach { button -> button.isEnabled = active && idle() }
    }
    fun show(from: View?, aboutMode: Boolean = false) {
        if (!active) return
        anchor = from
        dialog?.dismiss()
        aboutDialogShowing = aboutMode
        // One nag per process lifetime: the user may close it, but the next app restart re-prompts.
        val updatePending = available != null
        if (updatePending) promptedThisSession = true
        val dp = activity.resources.displayMetrics.density
        val body = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding((24*dp).toInt(), (12*dp).toInt(), (24*dp).toInt(), 0) }
        body.addView(TvDialog.title(activity, if (aboutMode) "关于软件" else "版本与更新 · ${BuildConfig.VERSION_NAME}"))
        statusView = TextView(activity).apply {
            text = status.substringBefore("\n")
            textSize = 18f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(TvStyle.accent)
            setTextIsSelectable(false)
            setLineSpacing(0f, 1.12f)
            setPadding((14 * dp).toInt(), (12 * dp).toInt(), (14 * dp).toInt(), (12 * dp).toInt())
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(TvStyle.surface)
                cornerRadius = (12 * dp).toFloat()
                setStroke((1 * dp).toInt(), TvStyle.outline)
            }
            if (aboutMode && status == "当前版本 ${BuildConfig.VERSION_NAME}") visibility = View.GONE
        }
        speedView = TextView(activity).apply {
            text = "下载速度  0 Kb/s"
            textSize = 14f
            setTextColor(TvStyle.muted)
            visibility = if (aboutMode) View.GONE else View.VISIBLE
        }
        statusDetailsView = TextView(activity).apply {
            text = status.substringAfter("\n", "")
            textSize = 15f
            setTextColor(TvStyle.muted)
            setLineSpacing(0f, 1.2f)
            setPadding(0, (8 * dp).toInt(), 0, (12 * dp).toInt())
            visibility = if (status.contains("\n")) View.VISIBLE else View.GONE
            setTextIsSelectable(false)
        }
        // 除底部操作按钮外，标题、状态、说明、选项和换源入口全部放进同一个滚动区。
        // 手机横屏可用高度较小，不能只滚动更新说明，否则上方固定内容仍会把按钮挤出屏幕。
        val statusContent = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            if (aboutMode) {
                addView(TextView(activity).apply {
                    text = "当前版本  ${BuildConfig.VERSION_NAME}"
                    textSize = 14f
                    setTextColor(TvStyle.accent)
                    setPadding(0, 0, 0, (10 * dp).toInt())
                    setTextIsSelectable(false)
                }, LinearLayout.LayoutParams(-1, -2))
                addView(TvDialog.body(
                    activity,
                    "红果TV 是面向 Android 电视与大屏设备的影视播放客户端，支持内容浏览、搜索、剧集播放、收藏与观看记录管理。\n\n" +
                        "本原生版以 GPL-3.0 发布。内容协议与加密处理移植自 drpys（22261ad）。\n" +
                        "AndroidX Media3 / OkHttp：Apache-2.0\nKotlin：Apache-2.0\nBouncy Castle：MIT。\n\n" +
                        "应用不提供、不存储、不传播视频内容；播放内容由用户选择的来源提供，相关内容版权归原平台及权利人所有。"
                ))
            } else {
                addView(speedView, LinearLayout.LayoutParams(-1, -2))
                addView(statusView, LinearLayout.LayoutParams(-1, -2).apply {
                    bottomMargin = (10 * dp).toInt()
                })
                addView(statusDetailsView, LinearLayout.LayoutParams(-1, -2))
            }
        }
        val statusScroll = ScrollView(activity).apply {
            isFillViewport = false
            isVerticalScrollBarEnabled = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            clipToPadding = false
            addView(statusContent, FrameLayout.LayoutParams(-1, -2))
        }
        // 给卡片外层留出系统栏和圆角内边距空间；操作按钮固定在滚动区之外。
        body.layoutParams = LinearLayout.LayoutParams(
            -1, (activity.resources.displayMetrics.heightPixels * 0.64f).toInt()
        )
        body.addView(statusScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val autoCheckBox = if (!aboutMode && !updatePending) CheckBox(activity).apply {
            id = View.generateViewId()
            text = "自动检查并下载更新"; isChecked = auto; setTextColor(TvStyle.text)
            isFocusable = true
            isFocusableInTouchMode = true
            setOnCheckedChangeListener { _, enabled ->
                preferences.edit().putBoolean("automatic", enabled).apply()
                if (!enabled && busy) { interrupt(); updateStatus("自动更新已关闭，可手动检查"); renderActions() }
            }
        } else null
        autoCheckBox?.let { statusContent.addView(it, LinearLayout.LayoutParams(-1, -2)) }
        if (!aboutMode) statusContent.addView(TextView(activity).apply {
            text = if (updatePending) "发现新版本，点击即可更新" else "只在应用前台浏览时检查，播放中不打扰。下载后需确认系统安装，收藏与观看记录保留。"
            textSize = 13f; setTextColor(TvStyle.muted)
            setPadding(0, (8*dp).toInt(), 0, (8*dp).toInt())
        })
        val buttons = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.END
            setPadding(0, (14*dp).toInt(), 0, 0)
        }
        val later = TvDialog.capsuleButton(activity, if (aboutMode) "关闭" else "稍后") { }
        val action = TvDialog.capsuleButton(activity,"检查更新") { }
        actionButton = action
        val sourceRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER
        }
        val address1 = TvDialog.capsuleButton(activity, "下载地址1") { }
        val address2 = TvDialog.capsuleButton(activity, "下载地址2") { }
        val address3 = TvDialog.capsuleButton(activity, "下载地址3") { }
        listOf(address1, address2, address3).forEach { button ->
            button.id = View.generateViewId()
            button.isFocusable = true
            button.isFocusableInTouchMode = true
            sourceRow.addView(button, LinearLayout.LayoutParams(0, -2, 1f).apply {
                if (button !== address1) leftMargin = (6 * dp).toInt()
            })
        }
        sourceButtons = listOf(address1, address2, address3)
        if (!aboutMode) {
            statusContent.addView(TextView(activity).apply {
                text = "1：GitCode    2：GitHub    3：GitHub 代理轮换"
                textSize = 13f
                setTextColor(TvStyle.muted)
                setPadding(0, (8 * dp).toInt(), 0, (4 * dp).toInt())
            }, LinearLayout.LayoutParams(-1, -2))
            statusContent.addView(sourceRow, LinearLayout.LayoutParams(-1, -2))
        }
        buttons.addView(later, LinearLayout.LayoutParams(-2,-2).apply { rightMargin=(12*dp).toInt() })
        buttons.addView(action, LinearLayout.LayoutParams(-2,-2))
        body.addView(buttons)
        val next = AlertDialog.Builder(activity).setView(TvDialog.card(activity,body)).create()
        val isCompactScreen = activity.resources.configuration.smallestScreenWidthDp < 600
        TvDialog.styleWindow(activity, next, widthFraction = if (isCompactScreen) 0.90f else 0.55f)
        dialog = next
        next.setOnDismissListener {
            if (dialog === next) { dialog = null; statusView = null; statusDetailsView = null; speedView = null; actionButton = null; sourceButtons = emptyList(); aboutDialogShowing = false }
            if (from?.isAttachedToWindow == true) from.requestFocus()
        }
        next.show()
        later.setOnClickListener { next.dismiss() }
        fun chooseAddress(source: Int, button: android.widget.Button) {
            if (aboutMode || !active || !idle()) return
            if (busy && downloadInProgress) interrupt() else if (busy) return
            if (source == 3) manualProxyIndex = (manualProxyIndex + 1) % AppUpdate.MIRRORS.drop(1).size
            if (available == null) {
                pendingSource = source
                pendingProxyIndex = if (source == 3) manualProxyIndex else null
                check(true)
                button.post { if (next.isShowing && button.isEnabled) button.requestFocus() }
                return
            }
            download(source, if (source == 3) manualProxyIndex else null)
            button.post { if (next.isShowing && button.isEnabled) button.requestFocus() }
        }
        address1.setOnClickListener { chooseAddress(1, address1) }
        address2.setOnClickListener { chooseAddress(2, address2) }
        address3.setOnClickListener { chooseAddress(3, address3) }
        action.setOnClickListener {
            if (aboutMode) {
                // 关于页只保留更新入口；点击后进入独立的更新弹窗，再执行真正的版本检查。
                next.dismiss()
                show(from, aboutMode = false)
                check(true)
            } else {
                when {
                    ready -> install()
                    available != null -> download()
                    else -> check(true)
                }
            }
        }
        renderActions()
        // 为电视遥控器建立明确的纵向焦点链，避免系统按控件几何位置跳过复选框或换源按钮。
        action.nextFocusUpId = address3.id
        address3.nextFocusDownId = action.id
        address2.nextFocusDownId = action.id
        address1.nextFocusRightId = address2.id
        address2.nextFocusLeftId = address1.id
        address2.nextFocusRightId = address3.id
        address3.nextFocusLeftId = address2.id
        if (autoCheckBox != null) {
            autoCheckBox.nextFocusDownId = address1.id
            address1.nextFocusUpId = autoCheckBox.id
        } else {
            address1.nextFocusUpId = action.id
        }
        action.post { if (dialog === next) action.requestFocus() }
    }
    private fun <T> work(action: () -> T, done: (T) -> Unit) {
        busy = true; renderActions(); val ticket = ++generation
        task = worker.submit {
            try {
                val result = action()
                main.post { if (active && generation == ticket) { busy = false; done(result); renderActions() } }
            } catch (e: Exception) {
                main.post { if (active && generation == ticket) {
                    busy = false; downloadInProgress = false; ready = false; retryAfter = System.currentTimeMillis()+30*60*1000L
                    updateStatus(if (e is IOException) e.message ?: "更新失败，请重试" else "更新校验失败，请重试"); renderActions()
                } }
            }
        }
    }
    /** @return true when the request actually started; callers may retry later otherwise. */
    private fun check(manual: Boolean, mirrorIndex: Int? = null): Boolean {
        if (busy || !active || !idle()) return false
        preferences.edit().putLong("last_attempt", System.currentTimeMillis()).apply()
        updateStatus("正在检查新版…")
        work({ repository.latest() }) { update ->
            available = update.takeIf { it.newerThan(BuildConfig.VERSION_CODE.toLong(), Build.VERSION.SDK_INT) }; ready = false
            if (available != null) {
                // 每个版本使用独立文件名和 content URI，避免 Android 14–16 安装器把旧 URI 的 APK 缓存当成新包。
                apk = File(activity.cacheDir, "updates/HongguoTV-${available!!.versionName}-${available!!.versionCode}.apk")
            }
            if (available == null) {
                pendingSource = null
                pendingProxyIndex = null
                updateStatus(if (update.versionCode > BuildConfig.VERSION_CODE && update.minSdk > Build.VERSION.SDK_INT) "新版需要 Android API ${update.minSdk}，当前电视继续使用本版" else "已是最新版本 ${BuildConfig.VERSION_NAME}")
                worker.execute { apk.delete() }
            } else {
                updateStatus("发现 ${update.versionName} · ${"%.1f".format(update.size/1048576.0)} MB\n${update.notes}")
                if (manual || auto) {
                    val chosenSource = pendingSource
                    val chosenProxy = pendingProxyIndex
                    pendingSource = null
                    pendingProxyIndex = null
                    if (chosenSource != null) download(chosenSource, chosenProxy)
                    else if (mirrorIndex == null) download() else download(2, mirrorIndex)
                }
            }
        }
        return true
    }
    private fun download(source: Int? = null, proxyIndex: Int? = null) {
        val update = available ?: return
        if (busy || !active || !idle()) return
        val sourceName = when (source) {
            1 -> "GitCode"
            2 -> "GitHub"
            3 -> "GitHub 代理"
            else -> "自动线路"
        }
        downloadInProgress = true
        speedView?.text = "下载速度  0 Kb/s"
        renderActions()
        updateStatus("正在通过 $sourceName 下载 ${update.versionName}…")
        val ticket = generation + 1
        work({
            repository.download(update, apk, { percent -> main.post {
                if (generation == ticket && active) updateStatus("正在通过 $sourceName 下载 ${update.versionName} · $percent%")
            } }, proxyIndex, source, { bytesPerSecond -> main.post {
                if (generation == ticket && active) {
                    val bitsPerSecond = bytesPerSecond * 8.0
                    val speedText = if (bitsPerSecond >= 1_000_000.0)
                        "%.2f Mb/s".format(bitsPerSecond / 1_000_000.0)
                    else "%.0f Kb/s".format(bitsPerSecond / 1_000.0)
                    speedView?.text = "下载速度  $speedText"
                }
            } })
            validateApk(update)
        }) {
            downloadInProgress = false
            speedView?.text = "下载速度  0 Kb/s"
            ready = true
            updateStatus("${update.versionName} 已下载并校验\n${update.notes}\n\n确认后由系统覆盖安装，保留本机记录。")
            if (idle() && dialog == null && !promptedThisSession) show(activity.currentFocus)
        }
    }
    private fun validateApk(update: AppUpdate) = AppUpdateApkValidator.validate(activity, apk, update)
    private fun install() {
        val update = available ?: return
        if (busy || !active || !ready) return
        work({ validateApk(update) }) {
            try {
                if (Build.VERSION.SDK_INT >= 26 && !activity.packageManager.canRequestPackageInstalls()) {
                    permissionRequested = true
                    activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")))
                } else {
                    val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.updates", apk)
                    // Android 14–16 对安装器 Intent 的路由更严格，明确使用安装动作并传递只读 URI 授权。
                    activity.startActivity(Intent(Intent.ACTION_VIEW)
                        .setDataAndType(uri, "application/vnd.android.package-archive")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        .apply { clipData = ClipData.newRawUri("红果TV ${update.versionName} 安装包", uri) })
                }
            } catch (_: Exception) { permissionRequested = false; updateStatus("电视未提供安装或授权入口，请通过系统文件管理器安装新版") }
        }
    }
}
