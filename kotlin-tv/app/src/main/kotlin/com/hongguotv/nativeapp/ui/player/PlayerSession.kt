// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp.ui.player

import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.hongguotv.core.ContentRepository
import com.hongguotv.core.DanmakuLoader
import com.hongguotv.core.RemoteVideo
import com.hongguotv.core.PlaybackQuality
import com.hongguotv.core.Series
import com.hongguotv.core.VideoFrameMode
import com.hongguotv.js.HongguoJsDanmaku
import com.hongguotv.js.HongguoJsStore
import com.hongguotv.nativeapp.DanmakuView
import com.hongguotv.nativeapp.Library
import com.hongguotv.nativeapp.PlaybackExtras
import com.hongguotv.nativeapp.VideoDataSource
import com.hongguotv.nativeapp.playbackStreamSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * 一次播放会话：负责解析地址、准备加密数据源、驱动 ExoPlayer、记录进度。
 * 界面层只读这里的状态，所有网络与解密工作都在 IO 线程完成。
 *
 * 无感连播只有两条通道，二选一：
 *  - 开关开启：第二个 ExoPlayer 在隐藏画面里把下一集渲染出首帧，切集直接接管（双播放器）。
 *  - 开关关闭：同一个播放器用 playlist 预载下一集（addMediaSource + PreloadConfiguration），
 *    当前集播完自动过渡，手动切集直接 seekToNextMediaItem。
 * 双播放器任一环节失败（内存不足 / 无视频轨道 / 解码失败）都退化为 playlist 通道。
 */
@androidx.annotation.OptIn(UnstableApi::class)
class PlayerSession(
    private val context: Context,
    private val repository: ContentRepository,
    private val library: Library,
    private val extras: PlaybackExtras,
    val series: Series,
    val episodes: List<String>,
    initialIndex: Int,
    /** 进入播放器时的续播位置（详情页「继续第 N 集」带过来的毫秒数）。 */
    initialPositionMs: Long = 0L,
) {
    var index by mutableIntStateOf(initialIndex.coerceIn(0, (episodes.size - 1).coerceAtLeast(0)))
        private set

    /**
     * JS 取流需要「剧集 ID:分集 ID」组合（与 adapter chapter.id 一致）；
     * series.id 形如 "hongguo:7690..."，拼上分集 ID 后由 HongguoJsStreamSource 解析。
     */
    private fun episodeKey(videoId: String): String = "${series.id}:$videoId"

    var player by mutableStateOf<ExoPlayer?>(null)
        private set
    var playing by mutableStateOf(false)
        private set
    var buffering by mutableStateOf(true)
        private set
    var ready by mutableStateOf(false)
        private set
    var position by mutableLongStateOf(0L)
        private set
    var duration by mutableLongStateOf(0L)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var qualityLabel by mutableStateOf("")
        private set
    var speed by mutableFloatStateOf(library.playbackSpeed)
        private set
    var frameMode by mutableStateOf(library.frameMode)
        private set
    var loadingLabel by mutableStateOf("正在解析播放地址")
        private set
    var danmakuEnabled by mutableStateOf(library.danmaku)
        private set
    var quality by mutableIntStateOf(library.maxQuality)
        private set

    /** 播放信息叠层：仅在设置开启时由界面读取。 */
    var videoWidth by mutableIntStateOf(0)
        private set
    var videoHeight by mutableIntStateOf(0)
        private set
    var videoBitrate by mutableIntStateOf(0)
        private set
    var videoFrameRate by mutableFloatStateOf(0f)
        private set
    var networkMbps by mutableFloatStateOf(0f)
        private set
    var danmakuLoaded by mutableIntStateOf(0)
        private set

    private val danmakuSource = HongguoJsDanmaku(HongguoJsStore.get(context, repository.http))
    private var danmakuView: DanmakuView? = null
    private var danmakuLoader: DanmakuLoader? = null
    /** 当前绑定的「集 + 时长」，避免同一集被 READY/接管/刷新重复重建 loader。 */
    private var danmakuKey = ""

    /** 关闭无感连播时的单实例 playlist 预载：已经追加进队列的下一集。 */
    private var queuedIndex = -1
    private var queuedStream: PreparedStream? = null
    private var queueJob: Job? = null

    private data class PreparedStream(val video: RemoteVideo, val quality: String)

    /**
     * 解码器报「不支持该分辨率」时的一次性降档：不改用户的清晰度设置，
     * 只对当前这一集生效，切集/用户手动改清晰度都会清掉。
     */
    private var qualityOverride: Int? = null
    private var autoDowngraded = false

    /**
     * 硬解在某段码流上崩溃后的最后一档：换软解重建播放器。
     * 有些电视盒子（尤其 Amlogic/老 MTK）的硬解驱动遇到非标 SPS/PPS 或大 B 帧会直接报
     * DECODING_FAILED，降清晰度若拿到的还是同一路编码就完全无效，只有软解能过。
     */
    private var softwareDecoderForced = false

    /**
     * 本会话内不再创建第二解码器。
     *
     * 触发三选一：设备声明的并发实例数不够、第二实例申请就失败、或者已经因为两路硬解
     * 抢资源挂过一次。置位后退回同实例 playlist 预载——切集、连播、画质全都不变，
     * 只是少了「下一集首帧已渲染」那一点顺滑。宁可少一点顺滑，也不能让主画面被回收。
     */
    private var dualDecoderBlocked = false

    /** 双播放器真正可用：用户开了开关，且没有因为设备解码能力被禁掉。 */
    private val useDualDecoder: Boolean get() = extras.seamlessPlayback && !dualDecoderBlocked

    /** 这一集里解码类错误已经重试过几次，用于避免「降档→同样的错→再降档」死循环。 */
    private var decodeRetries = 0

    /** 降档前那一档的档位名，用来判断降档到底有没有换到别的码流。 */
    private var failedQualityLabel = ""

    /** 最近一次双播放器接管的时刻，用于冷却期判定。 */
    private var lastHandoffAt = 0L

    /**
     * 自愈令牌：每次 prepare 都会自增。
     * 解码恢复要延迟几百毫秒才动手，这期间用户可能已经切集或退出——
     * 令牌对不上就说明这次恢复已经过期，不能把用户刚切的集覆盖掉。
     */
    private var recoveryToken = 0

    private val effectiveQuality: Int get() = qualityOverride ?: library.maxQuality

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())
    private var remote: RemoteVideo? = null
    private var loadJob: Job? = null
    private var released = false
    private var lastSavedAt = 0L
    private val retiredRemotes = mutableListOf<RemoteVideo>()

    // ------------------------------------------------------------ 双播放器状态

    /** 主画面容器：接管时用它把备用播放器换上去，并给隐藏输出面提供挂载点。 */
    private var playerView: PlayerView? = null
    private var standbyPlayer: ExoPlayer? = null
    private var standbyRemote: RemoteVideo? = null
    private var standbyHttp: OkHttpClient? = null
    private var standbyIndex = -1
    private var standbyQuality = ""
    private var standbyReady = false
    private var standbyFirstFrameAt = 0L
    private var standbyOutputView: SurfaceView? = null
    private var standbyJob: Job? = null

    /** 接管后的稳定窗内不显示缓冲圈：画面已接上，转圈会闪。 */
    private var suppressBuffering = false
    private var handoffInProgress = false
    private var handoffToken = 0

    /** playlist 快进目标：同一次切集被连按两次时不重复重建数据源。 */
    private var switchingTo = -1
    private var switchingAt = 0L

    val currentEpisodeId: String get() = episodes.getOrNull(index).orEmpty()

    /** 遥控器左右键单次快进步长。 */
    val seekStepMillis: Long get() = extras.seekStepSeconds * 1000L

    private val bandwidthMeter = androidx.media3.exoplayer.upstream.DefaultBandwidthMeter.Builder(context).build()

    private val mainListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (released) return
            when (playbackState) {
                Player.STATE_BUFFERING -> if (!suppressBuffering) buffering = true
                Player.STATE_READY -> {
                    buffering = false
                    suppressBuffering = false
                    ready = true
                    val exo = player ?: return
                    duration = exo.duration.coerceAtLeast(0L)
                    exo.videoFormat?.let { format ->
                        videoWidth = format.width
                        videoHeight = format.height
                        videoBitrate = format.bitrate
                        videoFrameRate = format.frameRate
                    }
                    startDanmaku()
                    // 原版时机：当前集真正可播后才启动预载，避免和主播放抢带宽。
                    if (useDualDecoder) prepareStandby(index + 1) else queueNextEpisode(index)
                }
                Player.STATE_ENDED -> {
                    ready = false
                    save(completed = true)
                    playing = false
                    if (library.autoNext && index < episodes.lastIndex) {
                        prepare(index + 1, 0L)
                    }
                }
            }
        }

        /** 单实例 playlist 自动/手动过渡到下一集：状态、弹幕、进度都换到新集。 */
        override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
            if (released || useDualDecoder) return
            val exo = player ?: return
            if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_AUTO &&
                reason != Player.MEDIA_ITEM_TRANSITION_REASON_SEEK
            ) return
            val newIndex = exo.currentMediaItemIndex
            if (newIndex == index) return
            if (mediaItem?.mediaId != episodes.getOrNull(newIndex)) return
            applyEpisodeTransition(newIndex)
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (released) return
            playing = isPlaying
            // 弹幕跟着播放状态走：暂停时停帧，继续时从当前媒体时间接着滚。
            // 之前没人通知弹幕层，暂停后再播弹幕会卡在暂停那一帧不动。
            danmakuView?.setPlaybackActive(isPlaying)
        }

        override fun onPlayerError(playerError: PlaybackException) {
            if (released) return
            buffering = false
            suppressBuffering = false
            playing = false
            val decodeIssue = isDecodeIssue(playerError)
            PlayerLog.error(
                "onPlayerError code=${playerError.errorCode}(${playerError.errorCodeName})" +
                    " decode=$decodeIssue standby=${standbyPlayer != null}" +
                    " blocked=$dualDecoderBlocked soft=$softwareDecoderForced" +
                    " quality=$qualityLabel/${PlaybackQuality.label(effectiveQuality)}" +
                    " at=$position size=${videoWidth}x$videoHeight" +
                    " cause=${playerError.cause?.let { it::class.java.simpleName + ":" + (it.message ?: "") }?.take(200)}",
            )
            if (!decodeIssue) {
                error = friendlyError(playerError)
                return
            }
            decodeRetries += 1
            // 第一优先：把第二解码器让出去，然后用同一条流原地重开。
            // 电视盒子的 DECODING_FAILED 绝大多数不是这段码流解不了，而是两路硬解抢同一颗 codec，
            // 系统为腾资源回收了正在播的那一路——证据就是「退出播放页再进来就好了」，
            // 因为退出时两路实例一起销毁了。这种情况下降清晰度完全无效（实例数没变），
            // 正确做法是撤掉备用实例，让主画面独占解码器。
            if (standbyPlayer != null || standbyJob != null) {
                val resumeAt = runCatching { player?.currentPosition ?: position }.getOrDefault(position)
                val token = ++recoveryToken
                switchToPlaylistPreload("主画面解码器被回收，退出双解码器")
                error = null
                buffering = true
                loadingLabel = "正在恢复播放"
                mainHandler.postDelayed({
                    if (released || recoveryToken != token) return@postDelayed
                    retrySameStream(resumeAt)
                }, DECODER_RELEASE_DELAY_MS)
                return
            }
            // 刚完成一次双播放器交接的几秒内报解码失败：旧实例的 codec 往往还没被系统回收，
            // 新实例抢不到。原地重开一次就能过，没必要先降清晰度（降了也还是同一个解码器）。
            if (lastHandoffAt > 0L &&
                SystemClock.elapsedRealtime() - lastHandoffAt < HANDOFF_RECOVER_WINDOW_MS &&
                decodeRetries <= 2
            ) {
                val resumeAt = runCatching { player?.currentPosition ?: position }.getOrDefault(position)
                val token = ++recoveryToken
                dualDecoderBlocked = true
                error = null
                buffering = true
                loadingLabel = "正在恢复播放"
                mainHandler.postDelayed({
                    if (released || recoveryToken != token) return@postDelayed
                    retrySameStream(resumeAt)
                }, DECODER_RELEASE_DELAY_MS)
                return
            }
            // 第二优先：只剩一路还在挂，才考虑降档。
            val next = downgradeFor(playerError)
            if (next != null) {
                failedQualityLabel = qualityLabel
                qualityOverride = next
                autoDowngraded = true
                error = null
                loadingLabel = "设备不支持当前分辨率，正在切换到 ${PlaybackQuality.label(next)}"
                prepare(index, position)
                return
            }
            // 第三优先：硬解这条路过不去了（或降档拿回来的还是同一路编码），换软解重建。
            if (!softwareDecoderForced) {
                loadingLabel = "正在切换到兼容解码"
                error = null
                rebuildForDecoderIssue(softwareOnly = true)
                return
            }
            // 全都试过了，如实报错，别让用户对着转圈干等。
            error = friendlyError(playerError)
        }

        /** 解码类错误返回降一档后的清晰度；已降过或没得降则返回 null。 */
        private fun downgradeFor(playerError: PlaybackException): Int? {
            if (autoDowngraded) return null
            if (decodeRetries > MAX_DECODE_RETRIES) return null
            if (!isDecodeIssue(playerError)) return null
            return when (val current = effectiveQuality) {
                PlaybackQuality.AUTO_HIGH, PlaybackQuality.AUTO_COMPAT -> 720
                1080 -> 720
                720 -> 480
                else -> null
            }
        }

        private fun isDecodeIssue(playerError: PlaybackException): Boolean {
            val raw = (playerError.cause?.message ?: playerError.message).orEmpty()
            return playerError.errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED ||
                playerError.errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ||
                playerError.cause is androidx.media3.exoplayer.mediacodec.MediaCodecRenderer.DecoderInitializationException ||
                raw.contains("分辨率", true) ||
                raw.contains("resolution", true) ||
                raw.contains("not supported", true)
        }

        /** 接管后首帧出现：再等 350ms 稳定窗，避免"画面已出却闪一下转圈"。 */
        override fun onRenderedFirstFrame() {
            if (!handoffInProgress) return
            val token = handoffToken
            mainHandler.postDelayed({
                if (handoffToken == token && handoffInProgress) {
                    handoffInProgress = false
                    suppressBuffering = false
                    buffering = player?.playbackState == Player.STATE_BUFFERING
                }
            }, HANDOFF_SETTLE_MS)
        }
    }

    /** 系统内存吃紧时优先丢弃备用实例，别让第二个解码器把前台播放拖崩。 */
    private val memoryCallback = object : ComponentCallbacks2 {
        override fun onTrimMemory(level: Int) {
            if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) clearStandby("系统内存压力")
        }

        override fun onLowMemory() {
            clearStandby("系统内存不足")
        }

        override fun onConfigurationChanged(newConfig: Configuration) = Unit
    }

    init {
        buildPlayer()
        runCatching { context.registerComponentCallbacks(memoryCallback) }
        // 续播位置必须带进第一次 prepare：之前固定从 0 开始，
        // 用户从详情页「继续观看」进来也是从头播，看起来就像进度没保存。
        prepare(index, initialPositionMs.coerceAtLeast(0L))
    }

    private fun buildPlayer() {
        val exo = ExoPlayer.Builder(context)
            // 主播放器原来没开解码器回退：硬解初始化一失败就直接整集报废，
            // 而备用播放器是开着的——同一台机器上「连播能播、单独点进去不能播」就是这么来的。
            .setRenderersFactory(renderersFactory())
            .setBandwidthMeter(bandwidthMeter)
            .build()
        exo.setHandleAudioBecomingNoisy(true)
        exo.playWhenReady = true
        applyPlaylistPolicy(exo)
        exo.addListener(mainListener)
        player = exo
    }

    /** 软解兜底时把系统软解 codec 排到最前；平时保持系统默认优先级。 */
    private fun renderersFactory(): DefaultRenderersFactory {
        val factory = DefaultRenderersFactory(context).setEnableDecoderFallback(true)
        if (softwareDecoderForced) {
            factory.setMediaCodecSelector(
                MediaCodecSelector { mimeType, requiresSecureDecoder, requiresTunnelingDecoder ->
                    MediaCodecUtil.getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder)
                        .sortedByDescending { it.softwareOnly }
                },
            )
        }
        return factory
    }

    /**
     * 播到一半解码器挂掉：释放当前实例，按当前进度重建。
     * 不重新解析用户的清晰度设置，只在当前这一集内自救。
     */
    private fun rebuildForDecoderIssue(softwareOnly: Boolean) {
        if (released) return
        val resumeAt = runCatching { player?.currentPosition ?: position }.getOrDefault(position)
        val target = index
        val token = ++recoveryToken
        clearStandby("重建解码器")
        queueJob?.cancel()
        discardQueue()
        softwareDecoderForced = softwareOnly
        // 走到这一步说明硬解已经不稳了，本次会话不要再并发第二路解码器，
        // 否则软解刚接上又会被抢占一次。
        dualDecoderBlocked = true
        // 先彻底释放：同一颗解码器在旧实例刚 release 时立刻被新实例申请，成功率很低，
        // 尤其是设备本来就在资源紧张状态。这里让系统先回收，再建新实例。
        runCatching { player?.release() }
        player = null
        error = null
        buffering = true
        loadingLabel = "正在切换到兼容解码"
        PlayerLog.warn("rebuildForDecoderIssue soft=$softwareOnly at=$resumeAt episode=$target")
        mainHandler.postDelayed({
            if (released || recoveryToken != token) return@postDelayed
            buildPlayer()
            playerView?.player = player
            prepare(target, resumeAt)
        }, DECODER_RELEASE_DELAY_MS)
    }

    /**
     * 解码器被系统回收后的原地重开：复用同一条流，不重新取播放地址。
     *
     * 重新取流要重新解析地址、可能换到别的线路，既慢又引入新变量；而解码失败跟链路无关。
     * 复用现有 RemoteVideo 是这里最快、也最可控的一条路。
     */
    private fun retrySameStream(atMs: Long) {
        if (released) return
        val exo = player
        val video = remote
        if (exo == null || video == null) {
            // 流已经不在了（可能用户刚切集），退回完整准备流程。
            prepare(index, atMs)
            return
        }
        val target = index
        error = null
        buffering = true
        ready = false
        position = atMs.coerceAtLeast(0L)
        loadingLabel = "正在恢复播放"
        runCatching {
            exo.stop()
            exo.clearMediaItems()
            exo.setMediaSource(episodeSource(episodes[target], video))
            if (atMs > 0L) exo.seekTo(atMs)
            exo.prepare()
            exo.playWhenReady = true
            exo.setPlaybackSpeed(speed)
        }.onFailure { problem ->
            buffering = false
            error = "恢复播放失败：${problem.message ?: "未知错误"}"
        }.onSuccess {
            // clearMediaItems 会把刚排好的下一集一起清掉，队列状态必须跟着重置，
            // 否则用户按「下一集」会指向一个已经不存在的媒体项。
            queuedIndex = -1
            queuedStream = null
            if (index + 1 < episodes.size) queueNextEpisode(index, force = true)
        }
        PlayerLog.warn("retrySameStream at=$atMs episode=$target")
    }

    /**
     * 停用双解码器，退回同实例 playlist 预载。
     * 切集、连播、画质全都不变，只是少了「下一集首帧已渲染」那点顺滑；
     * 换来的是主画面不再被系统回收。
     */
    private fun switchToPlaylistPreload(reason: String) {
        if (dualDecoderBlocked && standbyPlayer == null && standbyJob == null) return
        PlayerLog.warn("双解码器停用：$reason")
        dualDecoderBlocked = true
        clearStandby(reason)
        player?.let(::applyPlaylistPolicy)
        if (index + 1 < episodes.size) queueNextEpisode(index, force = true)
    }

    // ------------------------------------------------------------ 界面挂载

    /** 由播放页在 PlayerView 创建后调用：接管时换播放器、隐藏输出面挂到同一容器。 */
    fun attachPlayerView(view: PlayerView) {
        playerView = view
        if (view.player !== player) view.player = player
        // 会话可能先于画面视图创建：视图加入后补一次「下一集」准备。
        // 双解码器要等隐藏输出面，单实例预载不需要，两条路都要补，否则先建会话的情况下
        // 这一集播完就直接停了、没有预载。
        view.post {
            if (released || !ready) return@post
            when {
                useDualDecoder && standbyIndex == -1 -> prepareStandby(index + 1)
                !useDualDecoder && extras.seamlessPlayback && queuedIndex == -1 ->
                    queueNextEpisode(index, force = true)
            }
        }
    }

    fun detachPlayerView(view: PlayerView) {
        if (playerView === view) playerView = null
    }

    private fun friendlyError(error: PlaybackException): String {
        val raw = (error.cause?.message ?: error.message).orEmpty()
        return when {
            error.errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED ->
                "视频解码中断，已尝试自动恢复。请重试或换一集"
            error.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ->
                "当前设备无法解码该视频，可尝试降低清晰度"
            error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED -> "网络连接中断，请检查网络后重试"
            error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "视频服务器返回异常，请稍后重试"
            raw.contains("分段", true) -> raw
            else -> "播放失败：${raw.take(80)}"
        }
    }

    fun prepare(targetIndex: Int, startMs: Long) {
        if (released) return
        if (targetIndex !in episodes.indices) return
        // 任何一次新的准备都让之前挂着的解码恢复作废，避免它把这次准备覆盖掉。
        recoveryToken += 1
        // 无感连播：目标正是备用播放器准备好的那一集，且已渲染过首帧，直接接管。
        if (startMs == 0L && promoteStandby(targetIndex)) return
        // 关闭开关时：目标正是同一播放器 playlist 里预载好的下一集，直接前进，不重建。
        if (startMs == 0L && switchToQueued(targetIndex)) return
        // 接管没成功就别让半成品备用实例继续占着解码器，统一走普通流程。
        if (standbyIndex != -1) clearStandby("改用普通播放流程")
        switchingTo = -1
        val episodeId = episodes[targetIndex]
        if (index != targetIndex) {
            // 换集重新给一次降档机会：上一集被降档不代表这一集也不行。
            autoDowngraded = false
            qualityOverride = null
            failedQualityLabel = ""
            decodeRetries = 0
        }
        if (index != targetIndex || duration > 0 || position > 0) save(completed = false)
        index = targetIndex
        error = null
        buffering = true
        ready = false
        position = startMs.coerceAtLeast(0L)
        duration = 0L
        loadingLabel = "正在解析第 ${targetIndex + 1} 集"
        loadJob?.cancel()
        stopDanmaku()
        danmakuLoaded = 0
        remote?.let { runCatching { it.close() } }
        remote = null
        player?.stop()
        player?.clearMediaItems()
        discardQueue()
        player?.let(::applyPlaylistPolicy)
        loadJob = scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val info = context.playbackStreamSource(repository.http).stream(episodeKey(episodeId), effectiveQuality)
                    PreparedStream(RemoteVideo(repository.http, info).prepare(), info.quality)
                }
            }
            if (released) {
                result.getOrNull()?.video?.let { runCatching { it.close() } }
                return@launch
            }
            result.onSuccess { preparedStream ->
                qualityLabel = preparedStream.quality
                // 降档后拿回来的还是同一档：说明这一集平台只产出了一路编码，
                // 再重试只会「同样的错→再降一档→同样的错」。别白等它挂一次，直接换软解。
                if (autoDowngraded && !softwareDecoderForced &&
                    failedQualityLabel.isNotEmpty() && preparedStream.quality == failedQualityLabel
                ) {
                    PlayerLog.warn("降档未换流（${preparedStream.quality}），直接切软解")
                    runCatching { preparedStream.video.close() }
                    rebuildForDecoderIssue(softwareOnly = true)
                    return@onSuccess
                }
                remote = preparedStream.video
                attach(preparedStream.video, targetIndex, startMs)
            }.onFailure { failure ->
                buffering = false
                error = when {
                    failure.message?.contains("不支持", true) == true -> failure.message.orEmpty()
                    failure.message?.contains("解码", true) == true -> failure.message.orEmpty()
                    else -> "无法获取播放地址：${failure.message ?: "未知错误"}"
                }
            }
        }
    }

    private fun attach(prepared: RemoteVideo, targetIndex: Int, startMs: Long) {
        if (released) return
        val exo = player ?: return
        exo.setMediaSource(episodeSource(episodes[targetIndex], prepared))
        if (startMs > 0L) exo.seekTo(startMs)
        exo.prepare()
        exo.playWhenReady = true
        exo.setPlaybackSpeed(speed)
        loadingLabel = ""
    }

    // ------------------------------------------------------------ 单实例 playlist 预载

    /**
     * 关闭无感连播时的唯一通道：把下一集作为第二个 MediaItem 追加到同一个播放器队列，
     * 用 PreloadConfiguration 在当前集播放期间预载；播完自动过渡，手动切集直接前进。
     * 双播放器失败时也降级到这里（force = true 绕过开关判断）。
     */
    private fun queueNextEpisode(current: Int, force: Boolean = false) {
        if (useDualDecoder && !force) return
        if (released) return
        // 用户关掉「自动连播」时不应在播完后自动前进，这里就不排下一集。
        if (!library.autoNext) {
            discardQueue()
            return
        }
        val nextIndex = current + 1
        if (nextIndex > episodes.lastIndex) {
            discardQueue()
            return
        }
        if (queuedIndex == nextIndex && queuedStream != null) return
        val exo = player ?: return
        discardQueue()
        val targetId = episodes[nextIndex]
        queueJob = scope.launch {
            val prepared = withContext(Dispatchers.IO) {
                runCatching {
                    val info = context.playbackStreamSource(repository.http).stream(episodeKey(targetId), effectiveQuality)
                    PreparedStream(RemoteVideo(repository.http, info).prepare(), info.quality)
                }.getOrNull()
            }
            if (released || prepared == null) {
                prepared?.video?.let { runCatching { it.close() } }
                return@launch
            }
            // 预载期间用户已经切集 / 改清晰度 / 打开双播放器：这份流作废。
            if (index != current || (useDualDecoder && !force)) {
                runCatching { prepared.video.close() }
                return@launch
            }
            queuedIndex = nextIndex
            queuedStream = prepared
            // 自动连播链上队列会一直往后长：加新项前把当前集之前的旧项摘掉。
            runCatching { exo.removeMediaItems(0, exo.currentMediaItemIndex) }
            if (force) runCatching { exo.setPreloadConfiguration(ExoPlayer.PreloadConfiguration(PLAYLIST_PRELOAD_US)) }
            runCatching { exo.addMediaSource(episodeSource(targetId, prepared.video)) }
                .onFailure {
                    queuedIndex = -1
                    queuedStream = null
                    runCatching { prepared.video.close() }
                }
        }
    }

    /** 目标集已在队列里且就是下一项：直接前进，由 onMediaItemTransition 统一收尾。 */
    private fun switchToQueued(targetIndex: Int): Boolean {
        if (useDualDecoder) return false
        val exo = player ?: return false
        // 连按「下一集」时第一次已经发起 seek，第二次不能因为 index 还没更新就重建数据源，
        // 否则会把当前集重新准备一遍——用户看到的就是「下一集还是这一集」。
        if (switchingTo == targetIndex && SystemClock.elapsedRealtime() - switchingAt < SWITCH_GUARD_MS) return true
        if (queuedIndex != targetIndex || queuedStream == null) return false
        val nextItem = exo.currentMediaItemIndex + 1
        if (nextItem >= exo.mediaItemCount) return false
        if (exo.getMediaItemAt(nextItem).mediaId != episodes[targetIndex]) return false
        switchingTo = targetIndex
        switchingAt = SystemClock.elapsedRealtime()
        if (!runCatching { exo.seekToNextMediaItem() }.isSuccess) {
            switchingTo = -1
            return false
        }
        // seek 没落到新集（播放器正好停在 IDLE 等边界状态）时兜底重建，避免看起来「按了没反应」。
        mainHandler.postDelayed({
            if (!released && switchingTo == targetIndex && index != targetIndex) {
                switchingTo = -1
                prepare(targetIndex, 0L)
            }
        }, SWITCH_FALLBACK_MS)
        return true
    }

    /** 队列过渡完成：把界面状态、进度、弹幕、数据源全部换到新集，并继续预载再下一集。 */
    private fun applyEpisodeTransition(newIndex: Int) {
        if (newIndex !in episodes.indices) return
        switchingTo = -1
        val queued = queuedStream?.takeIf { queuedIndex == newIndex }
        save(completed = false)
        val previous = remote
        index = newIndex
        error = null
        position = 0L
        duration = 0L
        buffering = true
        ready = false
        loadingLabel = ""
        stopDanmaku()
        danmakuLoaded = 0
        if (queued != null) {
            remote = queued.video
            qualityLabel = queued.quality
            queuedIndex = -1
            queuedStream = null
        }
        if (queued == null) remote = null
        previous?.let(::releaseSoon)
        val exo = player
        if (exo != null && exo.playbackState == Player.STATE_READY) {
            runCatching {
                duration = exo.duration.coerceAtLeast(0L)
                exo.videoFormat?.let { format ->
                    videoWidth = format.width
                    videoHeight = format.height
                    videoBitrate = format.bitrate
                    videoFrameRate = format.frameRate
                }
            }
            ready = true
            buffering = false
            startDanmaku()
        }
        queueNextEpisode(newIndex)
    }

    /** 关闭预载通道：把已追加的下一集从队列里摘掉，再释放它的数据源。 */
    private fun discardQueue() {
        queueJob?.cancel()
        queueJob = null
        val exo = player
        val targetId = episodes.getOrNull(queuedIndex)
        // 队列里那一项必须先移除：留着它而提前关闭数据源，用户切过去会直接播失败。
        if (exo != null && targetId != null) {
            for (i in exo.mediaItemCount - 1 downTo 0) {
                if (i != exo.currentMediaItemIndex && exo.getMediaItemAt(i).mediaId == targetId) {
                    runCatching { exo.removeMediaItem(i) }
                }
            }
        }
        queuedIndex = -1
        queuedStream?.video?.let(::releaseSoon)
        queuedStream = null
    }

    /** 切集后旧数据源可能仍被上一个 MediaItem 的 MediaSource 引用，延后关闭。 */
    private fun releaseSoon(video: RemoteVideo) {
        retiredRemotes += video
        mainHandler.postDelayed({
            retiredRemotes.remove(video)
            runCatching { video.close() }
        }, 800L)
    }

    /** 双解码器可用时收回 playlist 预载；否则给同一实例开预载，切集照旧无缝、且只占一路解码器。 */
    private fun applyPlaylistPolicy(exo: ExoPlayer) {
        runCatching {
            exo.setPreloadConfiguration(
                if (useDualDecoder) ExoPlayer.PreloadConfiguration.DEFAULT
                else ExoPlayer.PreloadConfiguration(PLAYLIST_PRELOAD_US)
            )
        }
    }

    private fun episodeSource(id: String, video: RemoteVideo): MediaSource {
        val item = MediaItem.Builder()
            .setMediaId(id)
            .setUri("hongguotv://episode/$id")
            .setMimeType(MimeTypes.VIDEO_MP4)
            .build()
        return ProgressiveMediaSource.Factory { VideoDataSource(video) { } }.createMediaSource(item)
    }

    // ------------------------------------------------------------ 备用播放器

    /**
     * 后台准备下一集：独立连接池取流 → 建流 → 第二实例 + 隐藏输出面真正渲染首帧。
     * 任何一步失败都退回 [queueNextEpisode] 的单实例 playlist 预载，不影响正常播放。
     */
    private fun prepareStandby(nextIndex: Int) {
        if (released) return
        // 已经判定不能并发（或用户自己关了开关）：直接走同实例预载。
        // 用户开关还开着时也要补这一步，否则关了双播放器就真的没有下一集预载了。
        if (!useDualDecoder) {
            if (extras.seamlessPlayback) queueNextEpisode(index, force = true)
            return
        }
        // 没有画面容器就没有隐藏输出面，等 PlayerView 挂载后由 attachPlayerView 再触发。
        if (playerView == null) return
        // 刚完成一次接管：旧实例的 codec 才释放、新实例还在爬坡。
        // 这个窗口里再申请第二路解码器，就是把刚接上的画面往火坑里推。
        if (lastHandoffAt > 0L && SystemClock.elapsedRealtime() - lastHandoffAt < HANDOFF_WARMUP_MS) return
        if (nextIndex !in episodes.indices) {
            clearStandby("已经是最后一集")
            return
        }
        // 低内存设备（isLowRamDevice）：解码器和内存都紧张，默认就不并发。
        // 这条比 maxSupportedInstances 更可靠——很多盒子那项虚标成 4，实际只有一路硬解。
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        if (activityManager?.isLowRamDevice == true) {
            switchToPlaylistPreload("低内存设备，不启用双解码器")
            return
        }
        // 接着问设备能不能同时跑两路：maxSupportedInstances 不够就根本不申请，
        // 否则第二路一申请，系统会回收正在播的那一路（这正是「播到一半无法解码」的成因）。
        val mime = player?.videoFormat?.sampleMimeType
        if (!DecoderCapacity.canRunTwoDecoders(mime)) {
            switchToPlaylistPreload("设备并发解码上限 ${DecoderCapacity.maxInstances(mime)} 路（${mime ?: "未知编码"}）")
            return
        }
        if (standbyIndex == nextIndex && (standbyPlayer != null || standbyJob?.isActive == true)) return
        val availableMb = availableMemoryMb()
        if (availableMb in 0..(STANDBY_MEMORY_FLOOR_MB - 1)) {
            clearStandby("可用内存不足（${availableMb}MB）")
            queueNextEpisode(index, force = true)
            return
        }
        clearStandby("准备新的下一集")
        standbyIndex = nextIndex
        val targetId = episodes[nextIndex]
        val http = repository.http.newBuilder()
            .dispatcher(Dispatcher())
            .connectionPool(ConnectionPool())
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(25, TimeUnit.SECONDS)
            .build()
        standbyHttp = http
        standbyJob = scope.launch {
            val prepared = withContext(Dispatchers.IO) {
                runCatching {
                    // 备用链路独立取流：失败不切换全局线路，避免影响正在播放的这一集。
                    val info = context.playbackStreamSource(http, notifyOnFallback = false).stream(episodeKey(targetId), effectiveQuality)
                    PreparedStream(RemoteVideo(http, info).prepare(), info.quality)
                }.getOrNull()
            }
            if (released || !useDualDecoder || standbyIndex != nextIndex) {
                prepared?.video?.let { runCatching { it.close() } }
                return@launch
            }
            if (prepared == null) {
                clearStandby("下一集资源解析失败")
                queueNextEpisode(index, force = true)
                return@launch
            }
            standbyRemote = prepared.video
            standbyQuality = prepared.quality
            buildStandbyPlayer(prepared.video, nextIndex)
        }
    }

    private fun buildStandbyPlayer(video: RemoteVideo, target: Int) {
        if (released) return
        val secondary = try {
            ExoPlayer.Builder(context)
                .setRenderersFactory(DefaultRenderersFactory(context).setEnableDecoderFallback(true))
                .setLoadControl(
                    DefaultLoadControl.Builder()
                        // 接管后前几秒最容易卡：备用阶段就攒够连续播放的数据，而不是只满足首帧。
                        .setBufferDurationsMs(6_000, 18_000, 1_500, 3_500)
                        .setTargetBufferBytes(8 * 1024 * 1024)
                        .setPrioritizeTimeOverSizeThresholds(true)
                        .build()
                )
                .build()
        } catch (problem: Throwable) {
            // 连实例都建不起来：这台设备的解码资源确实不够并发，本次会话不再尝试。
            switchToPlaylistPreload("第二播放器创建失败：${problem.message}")
            return
        }
        standbyPlayer = secondary
        // 备用实例不申请音频焦点：主实例一直持有，接管时也不需要重新走一遍焦点协商，
        // 否则某些设备上会出现「画面接上了、声音没要回来」。
        secondary.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build(),
            false,
        )
        secondary.volume = 0f
        secondary.playWhenReady = false
        val output = createStandbyOutput()
        if (output == null) {
            // 没有挂载点就预热不了首帧，留着这个实例只会白占解码器。
            clearStandby("画面容器尚未就绪")
            return
        }
        standbyOutputView = output
        secondary.setVideoSurfaceView(output)
        secondary.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (standbyPlayer !== secondary || standbyIndex != target) return
                if (state == Player.STATE_READY) {
                    // READY 不等于能出画：没有选中视频轨道时直接放弃，别把坏状态交给接管。
                    if (!secondary.currentTracks.isTypeSelected(C.TRACK_TYPE_VIDEO)) {
                        clearStandby("备用播放器没有可用视频轨道")
                        queueNextEpisode(index, force = true)
                        return
                    }
                    standbyReady = true
                } else if (state == Player.STATE_IDLE && standbyReady) {
                    standbyReady = false
                }
            }

            override fun onRenderedFirstFrame() {
                if (standbyPlayer === secondary && standbyIndex == target && standbyFirstFrameAt == 0L) {
                    standbyFirstFrameAt = SystemClock.elapsedRealtime()
                }
            }

            override fun onPlayerError(playerError: PlaybackException) {
                if (standbyPlayer !== secondary || standbyIndex != target) return
                PlayerLog.error(
                    "standby onPlayerError ${playerError.errorCode}(${playerError.errorCodeName})" +
                        " cause=${playerError.cause?.message?.take(160)}",
                )
                // 第二实例自己就挂：这台设备/这段编码扛不住并发，本次会话不再开第二解码器。
                switchToPlaylistPreload("备用播放器准备失败：${playerError.errorCodeName}")
            }
        })
        val id = episodes[target]
        val item = MediaItem.Builder()
            .setMediaId(id)
            .setUri("hongguotv://episode/$id")
            .setMimeType(MimeTypes.VIDEO_MP4)
            .build()
        secondary.setMediaSource(
            ProgressiveMediaSource.Factory { VideoDataSource(video) { } }.createMediaSource(item)
        )
        secondary.prepare()
    }

    /**
     * 接管：六个条件全部满足才换播放器，否则清掉备用实例走普通流程。
     * 返回 true 表示这次 prepare 已经被接管处理完。
     */
    private fun promoteStandby(targetIndex: Int): Boolean {
        if (!useDualDecoder) return false
        if (standbyIndex != targetIndex) return false
        val standby = standbyPlayer ?: return false
        when {
            !standbyReady -> {
                clearStandby("备用播放器未就绪")
                return false
            }
            standbyFirstFrameAt == 0L -> {
                clearStandby("备用播放器尚未渲染首帧")
                return false
            }
            standby.currentMediaItem?.mediaId != episodes[targetIndex] -> {
                clearStandby("备用播放器媒体验证失败")
                return false
            }
        }
        val promotedRemote = standbyRemote ?: return false
        val old = player
        val previousRemote = remote
        // 必须在换播放器前保存旧集进度，不能从还没播放的备用实例读取位置。
        save(completed = false)
        switchingTo = -1
        index = targetIndex
        error = null
        ready = false
        buffering = false
        suppressBuffering = true
        handoffInProgress = true
        handoffToken += 1
        val token = handoffToken
        position = 0L
        duration = 0L
        loadingLabel = ""
        loadJob?.cancel()
        stopDanmaku()
        danmakuLoaded = 0
        qualityLabel = standbyQuality
        discardQueue()
        // 先换状态再换输出面：重组时的 update 块不会把旧实例写回来。
        player = standby
        playerView?.player = standby
        remote = promotedRemote
        standbyPlayer = null
        standbyRemote = null
        standbyReady = false
        standbyFirstFrameAt = 0L
        standbyIndex = -1
        standbyJob = null
        standbyHttp?.let(::closeClient)
        standbyHttp = null
        // 隐藏输出面先留着：接管瞬间立刻 removeView 会让 PlayerView 重新布局，
        // 某些设备上就是这一下导致「交接时黑一帧」。等画面稳定后再收。
        standby.addListener(mainListener)
        // 预渲染阶段音量为 0（避免和第二实例抢声音），接管时必须恢复，
        // 否则下一集只有画面没有声音。
        standby.volume = 1f
        standby.playWhenReady = true
        standby.setPlaybackSpeed(speed)
        // 备用实例接管前已经 READY，不会再回调 STATE_READY：这里手动补齐时长、画面参数与弹幕。
        runCatching {
            val promotedDuration = standby.duration.coerceAtLeast(0L)
            if (promotedDuration > 0L) duration = promotedDuration
            standby.videoFormat?.let { format ->
                videoWidth = format.width
                videoHeight = format.height
                videoBitrate = format.bitrate
                videoFrameRate = format.frameRate
            }
        }
        ready = true
        startDanmaku()
        // 隐藏输出面已经渲染过首帧，接管即出画：350ms 稳定窗先恢复提示，1.5 秒再兜底一次。
        mainHandler.postDelayed({
            if (handoffToken == token && handoffInProgress) {
                handoffInProgress = false
                suppressBuffering = false
                buffering = player?.playbackState == Player.STATE_BUFFERING
            }
        }, HANDOFF_SETTLE_MS)
        mainHandler.postDelayed({
            if (handoffToken == token && handoffInProgress) {
                handoffInProgress = false
                suppressBuffering = false
                buffering = player?.playbackState == Player.STATE_BUFFERING
            }
        }, HANDOFF_TIMEOUT_MS)
        old?.removeListener(mainListener)
        runCatching { old?.release() }
        previousRemote?.let { runCatching { it.close() } }
        lastHandoffAt = SystemClock.elapsedRealtime()
        PlayerLog.info("promoteStandby -> episode=$targetIndex quality=$standbyQuality")
        // 接管后再准备下一集，形成连续的预渲染链。
        // 但必须给交接留出冷却：这一瞬间旧实例刚 release、新实例的 codec 还在爬坡，
        // 立刻再申请第三路解码器，正是「刚跳到下一集没多久就报无法解码」的触发点。
        mainHandler.postDelayed({
            if (!released && index == targetIndex && player === standby) {
                prepareStandby(targetIndex + 1)
            }
        }, HANDOFF_WARMUP_MS + 120L)
        return true
    }

    private fun createStandbyOutput(): SurfaceView? {
        // 必须挂进 PlayerView（FrameLayout）内部：Compose 容器不会给额外 addView 的子视图布局，
        // 放在外面会得到 0 尺寸的 surface，备用播放器永远渲染不出首帧。
        val host = playerView ?: return null
        // 复用同一块隐藏输出面：每次准备下一集都 add/remove 会让 PlayerView 反复 requestLayout，
        // 接管瞬间的那次重排就是「交接黑一帧」的来源。
        standbyOutputView?.takeIf { it.parent === host }?.let { return it }
        val size = (16 * context.resources.displayMetrics.density).toInt().coerceAtLeast(1)
        val view = SurfaceView(host.context).apply {
            alpha = 0f
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        return runCatching {
            host.addView(view, FrameLayout.LayoutParams(size, size, Gravity.END or Gravity.BOTTOM))
            standbyOutputView = view
            view
        }.getOrNull()
    }

    private fun removeStandbyOutput() {
        standbyOutputView?.let { view ->
            runCatching { (view.parent as? ViewGroup)?.removeView(view) }
        }
        standbyOutputView = null
    }

    /** 释放备用播放器及其独立网络会话；不触碰正在播放的主实例。 */
    private fun clearStandby(reason: String) {
        if (standbyPlayer != null || standbyJob != null) PlayerLog.info("clearStandby: $reason")
        standbyJob?.cancel()
        standbyJob = null
        val secondary = standbyPlayer
        standbyPlayer = null
        if (secondary != null) {
            runCatching { secondary.playWhenReady = false }
            runCatching { secondary.stop() }
            runCatching { secondary.clearMediaItems() }
            runCatching { secondary.release() }
        }
        // 隐藏输出面留着复用，只在会话结束时 remove（见 release）。
        standbyRemote?.let { runCatching { it.close() } }
        standbyRemote = null
        closeClient(standbyHttp)
        standbyHttp = null
        standbyIndex = -1
        standbyQuality = ""
        standbyReady = false
        standbyFirstFrameAt = 0L
    }

    private fun closeClient(client: OkHttpClient?) {
        if (client == null) return
        runCatching { client.dispatcher.executorService.shutdown() }
        runCatching { client.connectionPool.evictAll() }
        runCatching { client.cache?.close() }
    }

    private fun availableMemoryMb(): Long {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return -1L
        val info = ActivityManager.MemoryInfo()
        manager.getMemoryInfo(info)
        return info.availMem / (1024L * 1024L)
    }

    // ---------------------------------------------------------------- 弹幕

    fun attachDanmaku(view: DanmakuView) {
        danmakuView = view
        applyDanmakuPrefs()
        view.setBlockedFilter { extras.blocks(it) }
        startDanmaku()
    }

    fun detachDanmaku(view: DanmakuView) {
        if (danmakuView === view) {
            runCatching { view.unbind() }
            danmakuView = null
            danmakuLoader = null
        }
    }

    private fun applyDanmakuPrefs() {
        val view = danmakuView ?: return
        view.applyPrefs(library.danmakuOpacity, library.danmakuFont, library.danmakuSpeed, library.danmakuRows)
        view.setColorMode(extras.danmakuColorMode)
    }

    private fun startDanmaku() {
        val view = danmakuView ?: return
        if (!danmakuEnabled || currentEpisodeId.isBlank()) {
            runCatching { view.unbind() }
            danmakuLoader = null
            danmakuKey = ""
            return
        }
        // 有些片源（HLS / 直播切片）拿不到时长，duration 一直是 0。
        // 原来这一条直接 return，整集弹幕永远不启动——这就是「时有时无」里最隐蔽的一类。
        // 给一个足够长的兜底时长，让弹幕先跑起来。
        val span = if (duration > 0L) duration else UNKNOWN_DURATION_MS
        val key = "$currentEpisodeId@$span"
        if (danmakuLoader != null && danmakuKey == key) return
        applyDanmakuPrefs()
        val loader = DanmakuLoader(danmakuSource, series.id, currentEpisodeId, span)
        danmakuLoader = loader
        danmakuKey = key
        view.bind(
            loader,
            { position },
            { p -> scope.launch(Dispatchers.IO) { runCatching { loader.loadUpTo(p) } } },
            playing,
        )
        scope.launch(Dispatchers.IO) {
            runCatching { loader.loadUpTo(position) }
        }
    }

    private fun stopDanmaku() {
        danmakuLoader = null
        danmakuKey = ""
        runCatching { danmakuView?.unbind() }
    }

    fun refreshPosition() {
        val exo = player ?: return
        if (released) return
        runCatching {
            position = exo.currentPosition.coerceAtLeast(0L)
            duration = exo.duration.coerceAtLeast(0L)
        }
        networkMbps = runCatching { bandwidthMeter.bitrateEstimate / 1_000_000f }.getOrDefault(0f)
        // STATE_READY 只来一次：若那一刻时长/集号还没就绪，弹幕会整集静默。
        // 每 400ms 的刷新顺带补一次启动，失败也能自愈。
        if (danmakuEnabled && danmakuLoader == null && duration > 0L &&
            currentEpisodeId.isNotBlank() && ready
        ) {
            startDanmaku()
        }
        val now = System.currentTimeMillis()
        if (now - lastSavedAt > 5000 && position > 0 && duration > 0) {
            lastSavedAt = now
            save(completed = false)
        }
    }

    fun togglePlay() {
        val exo = player ?: return
        if (error != null) {
            prepare(index, position)
            return
        }
        if (exo.isPlaying) {
            exo.pause()
            // 暂停就落一次盘：用户停在这里切走/息屏，记录也不会丢。
            save(completed = false)
        } else exo.play()
    }

    fun play() {
        player?.play()
    }

    fun pause() {
        player?.pause()
    }

    fun seekTo(ms: Long) {
        val exo = player ?: return
        val target = ms.coerceIn(0L, if (duration > 0) duration else Long.MAX_VALUE)
        exo.seekTo(target)
        position = target
    }

    fun seekBy(deltaMs: Long) {
        seekTo(position + deltaMs)
    }

    fun next() {
        if (index < episodes.lastIndex) prepare(index + 1, 0L)
    }

    fun previous() {
        if (index > 0) prepare(index - 1, 0L)
    }

    fun selectEpisode(target: Int) {
        if (target != index) prepare(target, 0L)
    }

    fun changeSpeed(value: Float) {
        speed = value
        player?.setPlaybackSpeed(value)
        standbyPlayer?.setPlaybackSpeed(value)
    }

    /**
     * 用户点「兼容模式重试」：本次会话停用第二解码器，按当前进度重开。
     * 不改用户的「无感连播」设置——下次进来仍按他自己的偏好走。
     */
    fun retryWithoutDualDecoder() {
        if (released) return
        recoveryToken += 1
        dualDecoderBlocked = true
        clearStandby("用户选择兼容模式")
        player?.let(::applyPlaylistPolicy)
        retrySameStream(position)
    }

    fun changeQuality(value: Int) {
        quality = value
        library.maxQuality = value
        // 用户手动选清晰度后，之前那次自动降档就该失效。
        qualityOverride = null
        autoDowngraded = false
        failedQualityLabel = ""
        decodeRetries = 0
        clearStandby("清晰度变更")
        prepare(index, position)
    }

    fun changeFrameMode(mode: VideoFrameMode) {
        frameMode = mode
        library.frameMode = mode
    }

    /** 当前剧集是否允许弹幕（全局开关 + 单剧记忆）。 */
    fun danmakuAvailable(): Boolean = extras.danmakuEnabled(series.id, library.danmaku)

    fun remoteAction(key: com.hongguotv.nativeapp.ui.data.RemoteKey): String = when (key) {
        com.hongguotv.nativeapp.ui.data.RemoteKey.UP -> extras.remoteUpAction
        com.hongguotv.nativeapp.ui.data.RemoteKey.DOWN -> extras.remoteDownAction
        com.hongguotv.nativeapp.ui.data.RemoteKey.CONFIRM -> extras.remoteConfirmAction
        com.hongguotv.nativeapp.ui.data.RemoteKey.MENU -> extras.remoteMenuAction
    }

    fun toggleDanmaku() {
        danmakuEnabled = !danmakuEnabled
        library.danmaku = danmakuEnabled
        if (danmakuEnabled) startDanmaku() else stopDanmaku()
    }

    val frameResizeMode: Int
        get() = when (frameMode) {
            VideoFrameMode.FIT -> AspectRatioFrameLayout.RESIZE_MODE_FIT
            VideoFrameMode.ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            VideoFrameMode.FILL -> AspectRatioFrameLayout.RESIZE_MODE_FILL
        }

    fun save(completed: Boolean) {
        if (episodes.isEmpty()) return
        if (position <= 0 && !completed) return
        runCatching {
            library.save(
                com.hongguotv.core.BackupProgress(
                    series = series,
                    episodeId = currentEpisodeId,
                    episodeIndex = index,
                    position = if (completed) 0L else position,
                    duration = duration,
                    completed = completed,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
        }
    }

    fun release() {
        if (released) return
        save(completed = false)
        released = true
        clearStandby("会话结束")
        discardQueue()
        retiredRemotes.forEach { runCatching { it.close() } }
        retiredRemotes.clear()
        handoffToken += 1
        handoffInProgress = false
        mainHandler.removeCallbacksAndMessages(null)
        stopDanmaku()
        runCatching { context.unregisterComponentCallbacks(memoryCallback) }
        loadJob?.cancel()
        scope.cancel()
        player?.removeListener(mainListener)
        runCatching { player?.release() }
        player = null
        remote?.let { runCatching { it.close() } }
        remote = null
        removeStandbyOutput()
        playerView = null
    }

    private companion object {
        /** 片源不报时长时，弹幕窗口按这个跨度铺开（短剧远达不到 45 分钟）。 */
        const val UNKNOWN_DURATION_MS = 45 * 60_000L

        /** 双播放器硬门槛：可用物理内存低于该值时只用单播放器预解析。 */
        const val STANDBY_MEMORY_FLOOR_MB = 512L

        /**
         * 释放解码器后等系统真正归还资源的时间。
         * 立刻重建时同一颗 codec 经常还被旧实例占着，会再失败一次。
         */
        const val DECODER_RELEASE_DELAY_MS = 600L

        /** 单集内解码类错误的最大自愈次数，超过就如实报错，避免无限重试。 */
        const val MAX_DECODE_RETRIES = 4

        /** 接管后隔多久再预载下一集：给旧实例的 codec 回收和新实例起步留出余量。 */
        const val HANDOFF_WARMUP_MS = 1_500L

        /** 交接后这段时间内的解码失败，优先按「codec 未回收」处理，直接原地重开。 */
        const val HANDOFF_RECOVER_WINDOW_MS = 5_000L

        /** 接管后等可见首帧的兜底时限（原实现 3 秒，这里收紧到 1.5 秒）。 */
        const val HANDOFF_TIMEOUT_MS = 1_500L

        /** 首帧出现后的稳定窗，避免画面刚接上就闪一下缓冲圈。 */
        const val HANDOFF_SETTLE_MS = 350L

        /** playlist 预载深度：15 秒，足够覆盖一集尾巴加切集握手。 */
        const val PLAYLIST_PRELOAD_US = 15_000_000L

        /** 同一次「下一集」请求在这个窗口内重复到达时视为连按，不重建数据源。 */
        const val SWITCH_GUARD_MS = 3_000L

        /** seek 到队列下一项后迟迟没有过渡回调时的兜底时限。 */
        const val SWITCH_FALLBACK_MS = 1_200L
    }
}
