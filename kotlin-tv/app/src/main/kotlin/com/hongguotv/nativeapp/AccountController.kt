// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

// 账户界面与状态协调：连接登录、云端记录及页面刷新回调。

import android.app.Activity
import android.app.AlertDialog
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.hongguotv.core.*
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Cloud history is kept separate from guest records; only explicit playback creates local history.
 */
class AccountController(private val activity: Activity, private val play: (CloudProgress) -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val http =
        okhttp3.OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    private val client = CloudAccountClient(http)
    private val credentialValidator = AccountCredentialValidator(client)
    private val qrAuthentication = AccountQrAuthenticationCoordinator(credentialValidator, worker, main)
    private val store = AccountStore(activity)
    private val qrDialogLogin = AccountQrDialogLoginCoordinator(
        activity = activity,
        http = http,
        main = main,
        worker = worker,
        authentication = qrAuthentication,
        store = store,
        generation = { generation },
        isClosed = { closed },
        isBusy = { busy },
        setBusy = { busy = it },
        setStatus = { status = it },
        safeError = ::safeError,
        toast = ::toast,
        showDialog = ::showDialog,
        onAuthenticated = { credentials, account -> acceptAccount(credentials, account) },
    )
    private val localFavoriteSync = AccountLocalFavoriteSyncCoordinator(client, worker, main)
    private val cloudData = AccountCloudDataCoordinator(client, worker, main)
    private val favoriteCoordinator = AccountFavoriteCoordinator(
        cloudData = cloudData,
        localSync = localFavoriteSync,
        account = { credentials },
        isSignedIn = { signedIn },
        isClosed = { closed },
        generation = { generation },
        showLogin = ::show,
        onChanged = ::changed,
        safeError = ::safeError,
    )
    @Volatile private var generation = 0
    @Volatile private var closed = false
    @Volatile private var cleanupStarted = false
    private var credentials: AccountCredentials? = null
    private var profile: CloudProfile? = null
    private var status = "未登录"
    private var busy = false
    private var dialog: AlertDialog? = null
    private val taskCoordinator = AccountTaskCoordinator(
        worker = worker,
        main = main,
        generation = { generation },
        isClosed = { closed },
        isBusy = { busy },
        setBusy = { busy = it },
        setStatus = { status = it },
        safeError = ::safeError,
        toast = ::toast,
    )
    private val historyCoordinator = AccountHistoryCoordinator(
        loadHistory = { account -> client.profile(account) to client.history(account) },
        taskCoordinator = taskCoordinator,
        account = { credentials },
        isSignedIn = { signedIn },
        isClosed = { closed },
        isBusy = { busy },
        setStatus = { status = it },
        onProfileLoaded = {
            profile = it
            accountProfileRevision++
        },
        onChanged = ::changed,
        toast = ::toast,
        safeError = ::safeError,
    )
    private val reservationCoordinator = AccountReservationCoordinator(
        client = client,
        cloudData = cloudData,
        taskCoordinator = taskCoordinator,
        account = { credentials },
        isSignedIn = { signedIn },
        isBusy = { busy },
        isClosed = { closed },
        generation = { generation },
        showLogin = ::show,
        onChanged = ::changed,
        toast = ::toast,
        safeError = ::safeError,
    )
    private val inlineQr = AccountInlineQrLoginCoordinator(
        http = http,
        main = main,
        worker = worker,
        authentication = qrAuthentication,
        generation = { generation },
        advanceGeneration = { ++generation },
        isClosed = { closed },
        isBusy = { busy },
        setBusy = { busy = it },
        setStatus = { status = it },
        safeError = ::safeError,
        onInlineChanged = ::inlineChanged,
        onAccountChanged = ::changed,
        onAuthenticated = { next, account -> acceptAccount(next, account, inline = true) },
    )
    val inlineQrBitmap: android.graphics.Bitmap? get() = inlineQr.bitmap
    val inlineStatus: String get() = status
    val inlineBusy: Boolean get() = busy
    var onHistoryChanged: (() -> Unit)? = null
    var onInlineChanged: (() -> Unit)? = null

    private val progressUploader = AccountProgressUploader(
        worker, main, client,
        credentials = { credentials?.takeIf { profile != null } },
        generation = { generation },
        isClosed = { closed },
        onProgress = { progress, episodes -> historyCoordinator.addProgress(progress, episodes) },
        onStatus = { status = it },
        safeError = ::safeError,
    )

    internal fun recommendationCredentials() = credentials?.takeIf { profile != null }

    internal val recommendationIdentity
        get() = "$generation:${profile?.id.orEmpty()}"

    val signedIn
        get() = credentials != null

    val accountLabel
        get() = profile?.nickname ?: if (signedIn) "红果账号" else "未登录"

    val accountAvatarUrl
        get() = profile?.avatarUrl.orEmpty()

    /** Increments whenever account profile data is fetched, even when the avatar URL is unchanged. */
    var accountProfileRevision = 0L
        private set

    val historyStatus
        get() = status

    val historyLoading
        get() = busy

    val historyReady
        get() = historyCoordinator.ready

    val historyScope
        get() = generation

    fun historyRows(): List<CloudProgress> = historyCoordinator.rows()

    /** Read-only connectivity check for the signed-in account API. */
    fun checkConnection(done: (Boolean, String) -> Unit) {
        val account = credentials
        if (account == null || closed) {
            done(false, "未登录")
            return
        }
        val token = generation
        worker.execute {
            val result = runCatching { client.profile(account) }
            main.post {
                if (closed || token != generation) return@post
                result.fold(
                    { done(true, "连接成功") },
                    { done(false, safeError(it)) },
                )
            }
        }
    }

    fun refreshHistory() {
        refresh(false)
    }

    internal fun reservationState(online: Boolean): ReservationState =
        reservationCoordinator.state(online)

    fun reservationLabel(work: RelatedWork): String = reservationCoordinator.label(work)

    fun reserve(work: RelatedWork, done: (String) -> Unit) =
        reservationCoordinator.reserve(work, done)

    internal fun favoriteState(): FavoriteState = favoriteCoordinator.state()

    fun accountFavorite(id: String): Boolean = favoriteCoordinator.isFavorite(id)

    fun favoriteNeedsRefresh(id: String): Boolean = favoriteCoordinator.needsRefresh(id)

    fun favoriteActionLabel(id: String): String = favoriteCoordinator.actionLabel(id)

    fun changeFavorite(series: Series, add: Boolean, done: (String) -> Unit) =
        favoriteCoordinator.change(series, add, done)

    fun refreshFavorites() = favoriteCoordinator.refresh()

    /** 只把本机收藏补充到红果账户；绝不删除或覆盖云端已有收藏。 */
    fun syncLocalFavorites(localRows: List<Series>) = favoriteCoordinator.syncLocal(localRows)

    fun refreshReservations() = reservationCoordinator.refresh()

    private fun changed() {
        onHistoryChanged?.invoke()
    }

    private fun inlineChanged() {
        onInlineChanged?.invoke()
    }

    val showing
        get() = dialog?.isShowing == true

    init {
        val token = generation
        worker.execute {
            val saved = runCatching { store.read() }
            main.post {
                if (closed || token != generation) return@post
                credentials = saved.getOrNull()
                status =
                    if (credentials != null) "登录态已恢复，打开账号页面刷新云端记录"
                    else if (saved.isFailure) "登录态无法读取，请重新登录" else "未登录"
                changed()
                if (credentials != null) refresh(false)
            }
        }
    }

    private fun toast(value: String) {
        if (!closed) Toast.makeText(activity, value, Toast.LENGTH_LONG).show()
    }

    private fun safeError(error: Throwable) =
        when (error) {
            is java.net.SocketTimeoutException -> "账号接口连接超时，请稍后重试"
            is java.net.UnknownHostException -> "无法连接账号接口，请检查网络"
            else ->
                error.message.orEmpty().replace(Regex("https?://\\S+"), "[地址]").take(150).ifBlank {
                    "账号操作失败"
                }
        }

    fun progress(id: String): CloudProgress? = historyCoordinator.progress(id)

    private fun showDialog(next: AlertDialog) {
        dialog?.dismiss()
        dialog = next
        next.setOnDismissListener { if (dialog === next) dialog = null }
        next.show()
    }

    fun show() {
        if (closed) return
        if (busy) {
            toast("账号操作进行中，请稍候")
            return
        }
        val next = AccountEntryDialogUi.create(
            activity = activity,
            nickname = profile?.nickname,
            signedIn = credentials != null,
            onQrLogin = ::qrLogin,
            onHistory = ::showHistory,
            onRefresh = { refresh(true) },
            onLogout = ::logout,
            onSyncStatus = {
                dialog?.dismiss()
                TvDialog.info(
                    activity,
                    "账号同步状态",
                    "$status\n\n登录后，电视播放的进度会自动上传。手机记录可从「云端继续观看」打开。平台接口可能变化，首次使用请对照手机核对集数和时间。\n本机历史与云端记录分别保留；退出登录会停止上传。"
                ).show()
            },
        )
        showDialog(next)
    }

    private fun acceptAccount(next: AccountCredentials, account: CloudProfile, inline: Boolean = false) {
        credentials = next
        profile = account
        accountProfileRevision++
        historyCoordinator.reset()
        favoriteCoordinator.reset()
        reservationCoordinator.reset()
        // 接管新账号会话：使旧任务回调失效，并清除旧会话遗留的 busy 状态。
        generation++
        busy = false
        status = "已登录，正在读取云端记录"
        progressUploader.reset()
        changed()
        toast("已登录 ${account.nickname}")
        refresh(!inline)
    }

    /** Starts or refreshes the QR session shown inside the account page. */
    fun startQrInline() = inlineQr.start()

    fun logoutInline() {
        if (busy) return
        logout()
        inlineQr.cancel()
        changed()
    }

    fun loginByQr() = qrLogin()

    fun backupJson(): String? = credentials?.toJson()

    fun restoreJson(raw: String, callback: (Boolean, String) -> Unit) {
        worker.execute {
            val result = runCatching {
                val saved = AccountCredentials.fromJson(raw)
                val checked = credentialValidator.validate(saved)
                store.save(checked.first)
                checked
            }
            main.post {
                if (closed) return@post
                result.onSuccess { pair ->
                    acceptAccount(pair.first, pair.second, inline = true)
                    callback(true, "账户已恢复，正在同步云端数据")
                }.onFailure { error ->
                    callback(false, "账户恢复失败或凭据已失效：${safeError(error)}")
                }
            }
        }
    }

    private fun qrLogin() = qrDialogLogin.start()

    private fun refresh(showResult: Boolean) =
        historyCoordinator.refresh(showResult) {
            toast(status)
            show()
        }

    private fun showHistory() {
        val snapshot = historyRows()
        if (snapshot.isEmpty()) {
            toast("暂无已读取的云端记录，请先刷新")
            return
        }
        showDialog(
            TvDialog.choose(
                activity,
                "云端继续观看",
                snapshot.map { "${it.series.title} · 第 ${it.episodeIndex+1} 集 · ${it.position/1000} 秒" },
                { index -> play(snapshot[index]) }
            )
        )
    }

    private fun logout() {
        generation++
        busy = false
        credentials = null
        profile = null
        historyCoordinator.reset()
        favoriteCoordinator.reset()
        reservationCoordinator.reset()
        progressUploader.reset()
        http.dispatcher.cancelAll()
        status = "已退出账号，自动同步已停止"
        changed()
        worker.execute {
            val result = runCatching { store.clear() }
            main.post { toast(if (result.isSuccess) status else "清除登录态失败，请重试退出") }
        }
    }

    fun upload(progress: CloudProgress, episodes: Int, force: Boolean) {
        progressUploader.upload(progress, episodes, force)
    }

    /** Stop every account task before destructive storage reset; no final upload is queued. */
    fun stopForReset() {
        if (closed) return
        closed = true
        generation++
        onHistoryChanged = null
        onInlineChanged = null
        progressUploader.reset()
        inlineQr.cancel()
        dialog?.dismiss()
        dialog = null
        http.dispatcher.cancelAll()
        worker.shutdownNow()
    }

    fun awaitStopped(timeout: Long, unit: TimeUnit): Boolean =
        worker.awaitTermination(timeout, unit)

    fun destroy() {
        if (cleanupStarted) return
        cleanupStarted = true
        val account = credentials
        val final = progressUploader.finalProgress()
        onHistoryChanged = null
        onInlineChanged = null
        if (!closed) {
            closed = true
            generation++
            progressUploader.reset()
            inlineQr.cancel()
            dialog?.dismiss()
            dialog = null
            http.dispatcher.cancelAll()
            worker.execute {
                if (account != null && final != null)
                    runCatching { client.upload(account, final.first, final.second) }
                http.connectionPool.evictAll()
                http.dispatcher.executorService.shutdown()
            }
            worker.shutdown()
        } else {
            http.dispatcher.cancelAll()
            Thread({
                http.connectionPool.evictAll()
                http.dispatcher.executorService.shutdown()
            }, "hongguotv-account-cleanup").start()
        }
    }
}
