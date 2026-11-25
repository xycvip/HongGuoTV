// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp.ui.player

import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
import com.hongguotv.core.DanmakuPrefs
import com.hongguotv.core.RemoteVideo
import com.hongguotv.core.PlaybackQuality
import com.hongguotv.core.Series
import com.hongguotv.core.VideoFrameMode
import com.hongguotv.js.HongguoJsDanmaku
import com.hongguotv.js.HongguoJsStore
import com.hongguotv.js.HongguoJsStreamSource
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
import kotlinx.coroutines.delay
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

    /**
     * 错误归属，界面据此给不同的补救动作：
     * `decode` 解码/分辨率 → 兼容模式重试；`source` 取流与分段 → 重新获取地址；`other` 只重试。
     * 之前不区分，"片段不可用"也会显示「兼容模式重试」，而那条路复用同一条失效地址，点了必然再错。
     */
    var errorKind by mutableStateOf("")
        private set

    /** 当前错误是「地址/取流」类：界面显示「重新获取地址」。 */
    val sourceError: Boolean get() = errorKind == ERROR_KIND_SOURCE

    /** 当前错误是解码类：界面才提供「兼容模式重试」。 */
    val decodeError: Boolean get() = errorKind == ERROR_KIND_DECODE
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

    /** 这一集里「地址失效/分段读不到」已经自动重新取流几次。 */
    private var sourceRetries = 0

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

    /**
     * 两层全屏 PlayerView（均为 SurfaceView）叠放：active 层显示当前集，
     * inactive 层物理移出屏幕（translationX），在其中预渲染下一集首帧。
     * 每个 view 一旦 setPlayer 就固定绑定那个播放器实例，切集只交换两层的屏幕位置、
     * 绝不换绑定——换绑定会让 PlayerView 把主 surface 重新设置给新播放器，触发 codec 重建而闪屏。
     */
    private val playerViews: Array<PlayerView?> = arrayOfNulls(2)
    private var activeSlot = 0
    /** 当前显示层。 */
    private val playerView: PlayerView? get() = playerViews.getOrNull(activeSlot)
    /** 预渲染层（移出屏幕），下一集在这里渲染好首帧等待接管。 */
    private val inactiveView: PlayerView? get() = playerViews.getOrNull(1 - activeSlot)

    private var standbyPlayer: ExoPlayer? = null
    private var standbyRemote: RemoteVideo? = null
    private var standbyHttp: OkHttpClient? = null
    private var standbyIndex = -1
    private var standbyQuality = ""
    private var standbyReady = false
    private var standbyFirstFrameAt = 0L
    private var standbyJob: Job? = null

    /** 备用实例最后一次尝试创建的时刻：心跳补建用，避免每 400ms 反复重试。 */
    private var lastStandbyAttemptAt = 0L

    /**
     * 重叠换层：接管时只把新层移入，旧层原地保留，等新层真正出画后再移出、解绑、释放。
     *
     * 原实现是「同帧移出旧层 + 立即解绑 + 立即 release」，但 translationX 要经过
     * 下一帧 performTraversals → SurfaceControl.Transaction → vsync 才生效，而解绑与释放是
     * 当场生效。于是那 1–2 帧里旧层已断流、新层还在屏外，露出根容器的黑底——就是用户说的
     * 「切换画面那一闪」。重叠期两层同时可见，任何时序下屏幕都有画面。
     */
    private var overlapOutView: PlayerView? = null
    private var overlapOutPlayer: ExoPlayer? = null
    private var overlapRemote: RemoteVideo? = null
    private var overlapToken = 0

    /** 已解绑、等待延迟释放的备用实例（不在错误回调栈里同步销毁）。 */
    private val retiringStandby = mutableListOf<ExoPlayer>()

    /** 等待窗口令牌：任何一次新的 prepare 都会让它作废，避免刚切的集被旧等待覆盖。 */
    private var standbyWaitToken = 0

    /** 刚刚播完（已写入 completed=true）的集号，防止紧接着的切集把它覆盖成未看完。 */
    private var completedIndex = -1

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
                    // 真正播起来了，把「地址失效」的重试额度还回去：同一集后面再出问题还能再救一次。
                    sourceRetries = 0
                    errorKind = ""
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
                    // 这一集已经写成「已看完」：紧接着的自动连播会离开本集，
                    // 若不做标记，leave 时的 save(completed=false) 会立刻把它抹回未看完。
                    completedIndex = index
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
            if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_AUTO &&
                reason != Player.MEDIA_ITEM_TRANSITION_REASON_SEEK
            ) return
            // 修复：单实例 playlist 恒被收敛成 [当前集, 下一集] 两项，currentMediaItemIndex
            // 只有 0/1，不能当绝对集号用——从第 2 集起 newIndex(1) 恰好等于 index(1)，
            // 过渡会被 "newIndex == index" 误判为没换集而丢弃，index 卡住、不预载再下一集；
            // 播完后 STATE_ENDED 又用错误的 index+1 重新 prepare，于是重新取流又播回当前集。
            // 改用 mediaId 在 episodes 里反查真实集号。
            val newIndex = mediaItem?.mediaId?.let { episodes.indexOf(it) } ?: -1
            if (newIndex < 0 || newIndex == index) return
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
            val sourceIssue = !decodeIssue && isSourceIssue(playerError)
            PlayerLog.error(
                "onPlayerError code=${playerError.errorCode}(${playerError.errorCodeName})" +
                    " decode=$decodeIssue source=$sourceIssue standby=${standbyPlayer != null}" +
                    " blocked=$dualDecoderBlocked soft=$softwareDecoderForced" +
                    " quality=$qualityLabel/${PlaybackQuality.label(effectiveQuality)}" +
                    " at=$position size=${videoWidth}x$videoHeight" +
                    " cause=${playerError.cause?.let { it::class.java.simpleName + ":" + (it.message ?: "") }?.take(200)}",
            )
            if (!decodeIssue) {
                // 取流/分段类：签名地址可能在中途失效（过期、CDN 403/416、Range 对不上）。
                // 原地重开同一条流没用——地址缓存 90 秒内还是同一条死地址，
                // 必须让它失效后重新取一次。这也是「点兼容模式重试还是同一个错」的原因。
                if (sourceIssue && sourceRetries < MAX_SOURCE_RETRIES) {
                    val resumeAt = runCatching { player?.currentPosition ?: position }.getOrDefault(position)
                    sourceRetries += 1
                    val token = ++recoveryToken
                    error = null
                    errorKind = ""
                    buffering = true
                    position = resumeAt
                    loadingLabel = "播放地址已失效，正在重新获取"
                    PlayerLog.warn("source retry ${sourceRetries}/$MAX_SOURCE_RETRIES at=$resumeAt episode=$index")
                    // 重新取流前先让出带宽：备用实例正在下载下一集，两条链路一起抢更容易再失败。
                    clearStandby("重新获取播放地址")
                    invalidateStreamAddress()
                    mainHandler.postDelayed({
                        if (released || recoveryToken != token) return@postDelayed
                        prepare(index, resumeAt)
                    }, SOURCE_RETRY_BASE_MS * sourceRetries)
                    return
                }
                errorKind = if (sourceIssue) ERROR_KIND_SOURCE else ERROR_KIND_OTHER
                error = when {
                    playerError.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
                        playerError.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
                        "网络连接中断，请检查网络后重试"
                    sourceIssue -> "播放地址已失效，请重新获取地址后重试"
                    else -> friendlyError(playerError)
                }
                return
            }
            errorKind = ERROR_KIND_DECODE
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

        /**
         * 取流/分段类故障：地址失效、CDN 拒绝、Range 不匹配。
         *
         * 这类错误和分辨率、解码器都无关，重新取一条地址往往就恢复了；
         * 但必须先让地址缓存失效，否则重试拿回的还是同一条。
         */
        private fun isSourceIssue(playerError: PlaybackException): Boolean {
            val raw = (playerError.cause?.message ?: playerError.message).orEmpty()
            // 切集/退出播放页时我们会主动 close 数据源，会以「播放请求已取消」结束。
            // 那不是故障，绝不能当成地址失效去重试（否则切集会莫名其妙地重播）。
            if (raw.contains("已取消") || raw.contains("取消")) return false
            return when (playerError.errorCode) {
                PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
                PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
                PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
                PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
                -> true
                else -> raw.contains("分段") || raw.contains("资源已变化")
            }
        }

        /** 接管后首帧出现：先收掉重叠期的旧层，再等 350ms 稳定窗，避免"画面已出却闪一下转圈"。 */
        override fun onRenderedFirstFrame() {
            // 新层真正出画了，旧层可以退场（这是重叠换层的最优退出点，比定时兜底更准）。
            if (overlapOutView != null) finishOverlapSwap("rendered")
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

    /**
     * 系统内存吃紧时优先丢弃备用实例，别让第二个解码器把前台播放拖崩。
     *
     * 门槛从 RUNNING_LOW(10) 提高到 RUNNING_CRITICAL(15)：电视盒子常年停在 LOW 这一档，
     * 按 LOW 就清会导致「清 codec → 下一个 READY 又建 → 又清」的循环，
     * 而 codec 在短时间内反复申请/释放正是驱动最容易崩的操作序列。
     */
    private val memoryCallback = object : ComponentCallbacks2 {
        override fun onTrimMemory(level: Int) {
            if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) clearStandby("系统内存压力")
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

    /**
     * 两层视频 SurfaceView 的显隐只用「物理隔离」：inactive 层整体移出屏幕，
     * 不靠 alpha、也不靠 z-order——这两类「改属性」的方式都有异步传播窗口，
     * 会让 standby 首帧偶发闪到前台（alpha 不可靠 / z-order 设置晚于 surface 重建）。
     *
     * 用 translationX 把 inactive 层挪到屏幕外：
     *  - surface 全程存活、首帧照常渲染（ExoPlayer 只认 surface，不管它在不在屏幕内）；
     *  - 位置在屏幕外，物理上不可能闪到前台，与播放器内部设置无关；
     *  - translation 是 View 属性，无需 surface 即可同步生效，没有异步时序窗口；
     *  - 切集时同帧交换两层的 translation，旧层移出、新层移入，画面无缝续上。
     *
     * 层级完全交给自然顺序：视频(默认, window 之下) < 弹幕(mediaOverlay) < Compose 控制层。
     */
    private fun applyVideoLayerOrder() {
        for (slot in 0..1) {
            val pv = playerViews[slot] ?: continue
            // 重叠期的旧层保持原位：它还在屏上给用户兜着画面，等 finishOverlapSwap 再移出。
            if (pv === overlapOutView) continue
            val isActive = slot == activeSlot
            val targetX = if (isActive) 0f else offScreenX(pv)
            if (pv.translationX != targetX) pv.translationX = targetX
        }
    }

    /**
     * inactive 层的水平偏移：整层右移接近一个屏宽，只在最左侧留 2px 在屏幕内。
     *
     * 原来完全移出（x=20000）时 layer 的可见区域为空，SurfaceFlinger 会回收它已排队的
     * buffer 来省内存——备用层「渲染过首帧」不代表接管瞬间那一帧还在队列里，于是新层移入后
     * 还要等解码器重新出一帧（30–100ms）。留 2px 让可见区域非空，buffer 不会被丢弃，
     * 接管瞬间即有画面。2px 在屏幕最左边缘，正常观看不可察。
     */
    private fun offScreenX(view: PlayerView): Float {
        val width = view.width.takeIf { it > 8 } ?: view.rootView.width
        // 首次布局尚未测量时先彻底移出，布局完成后 applyVideoLayerOrder 会按真实宽度
        // 调整为只留 2px 的缓冲保留区；不能用固定 1918px（宽屏会露出备用画面）。
        return if (width > 8) width.toFloat() - 2f else 100_000f
    }

    /**
     * 结束重叠换层：旧层移出屏幕 → 解绑 → 释放实例 → 关闭旧数据源。
     *
     * 顺序不能反：`translationX` 的变更要下一帧才作用到 SurfaceControl 上，而 `player = null`
     * 与 `release()` 是当场生效的。所以先移出，隔一小段时间再解绑/释放，
     * 否则「位置还没动、输出面已经断了」又会在屏幕原位露一帧黑。
     */
    private fun finishOverlapSwap(trigger: String, scheduleNext: Boolean = true) {
        val view = overlapOutView
        val outPlayer = overlapOutPlayer
        val outRemote = overlapRemote
        if (view == null && outPlayer == null) return
        overlapOutView = null
        overlapOutPlayer = null
        overlapRemote = null
        overlapToken += 1
        // 1) 旧层移出（下一帧生效）。
        applyVideoLayerOrder()
        // 2) 位置生效后再解绑输出面。
        mainHandler.postDelayed({
            if (view != null) runCatching { view.player = null }
        }, OVERLAP_UNBIND_DELAY_MS)
        // 3) 实例本体走统一的延迟释放队列；数据源等实例释放后才真正关闭。
        if (outPlayer != null) {
            runCatching { outPlayer.removeListener(mainListener) }
            retireStandbyInstance(outPlayer)
        }
        outRemote?.let(::releaseSoon)
        lastHandoffAt = SystemClock.elapsedRealtime()
        PlayerLog.info("overlapSwap out done frame=$trigger")
        // 交接真正结束（旧实例已进入释放队列）才预渲染再下一集：给 codec 回收与新实例起步留余量。
        val target = index
        if (!released && scheduleNext) {
            mainHandler.postDelayed({
                if (!released && index == target && player != null && useDualDecoder) {
                    prepareStandby(target + 1)
                }
            }, HANDOFF_WARMUP_MS + 120L)
        }
    }

    /** 任何会打断画面的操作（切集/清备用/释放会话）前，先把重叠期收干净。 */
    private fun settleOverlapNow() {
        if (overlapOutView != null || overlapOutPlayer != null) finishOverlapSwap("settle")
    }

    /**
     * 由播放页在两层 PlayerView 创建后分别调用（slot 0 / 1）。
     * 每层固定绑定一个播放器：active 层绑主播放器，inactive 层留给备用播放器。
     * 切集时只切两层的 z-order，不在这里换绑定，避免 surface 重建闪屏。
     */
    fun attachPlayerView(view: PlayerView, slot: Int) {
        if (slot !in 0..1) return
        playerViews[slot] = view
        if (slot == activeSlot) {
            if (view.player !== player) view.player = player
        } else {
            // inactive 层：若备用播放器已经建好就补绑，否则留空等 prepareStandby。
            val standby = standbyPlayer
            if (standby != null && view.player !== standby) view.player = standby
        }
        // 显隐只用 translationX 物理移屏，alpha 恒为 1（SurfaceView 的 alpha 不可靠）。
        view.alpha = 1f
        applyVideoLayerOrder()
        // 首次 attach 时 view 还没测量，width=0 只能退回兜底偏移；布局完成后再按真实屏宽
        // 重算一次，确保 inactive 层只留最左 2px 在屏幕内（buffer 不会被 SurfaceFlinger 丢掉）。
        view.post { if (!released) applyVideoLayerOrder() }
        // 会话可能先于画面视图创建：视图加入后补一次「下一集」准备。
        // 双解码器要等 inactive 层挂载，单实例预载不需要，两条路都要补，否则先建会话的情况下
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

    fun detachPlayerView(view: PlayerView, slot: Int) {
        if (slot in 0..1 && playerViews[slot] === view) playerViews[slot] = null
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

    fun prepare(targetIndex: Int, startMs: Long, allowStandbyWait: Boolean = true) {
        if (released) return
        if (targetIndex !in episodes.indices) return
        // 任何一次新的准备都让之前挂着的解码恢复作废，避免它把这次准备覆盖掉。
        recoveryToken += 1
        // 上一次的「等备用就绪」窗口也一并作废：连按下一集时不能让旧等待把新目标改回去。
        standbyWaitToken += 1
        // 上一次重叠换层还没收尾（120ms 内又切集）：先把旧层释放掉，否则连着切会堆实例。
        settleOverlapNow()
        // 无感连播：目标正是备用播放器准备好的那一集，且已渲染过首帧，直接接管。
        if (startMs == 0L && promoteStandby(targetIndex)) return
        // 关闭开关时：目标正是同一播放器 playlist 里预载好的下一集，直接前进，不重建。
        if (startMs == 0L && switchToQueued(targetIndex)) return
        // 备用正在为目标集取流/建实例，只差最后一步：给它一个等待窗口，别在「快好了」时推倒重来。
        // 立刻 clearStandby 再完整重建，等于重新取一次流（又一次 JS runtime + 一次 codec 申请），
        // 既慢又更容易崩。
        if (startMs == 0L && allowStandbyWait && standbyIndex == targetIndex &&
            (standbyJob?.isActive == true || standbyPlayer != null) && !handoffInProgress
        ) {
            val token = standbyWaitToken
            PlayerLog.info("等备用实例就绪 episode=$targetIndex")
            mainHandler.postDelayed({
                if (released || standbyWaitToken != token) return@postDelayed
                prepare(targetIndex, 0L, allowStandbyWait = false)
            }, STANDBY_WAIT_MS)
            return
        }
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
            sourceRetries = 0
            errorKind = ""
        }
        if (index != targetIndex || duration > 0 || position > 0) saveLeavingEpisode(targetIndex)
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
        // 先停止并清空旧 MediaSource，再关闭其 RemoteVideo，避免旧数据源仍在读取时断流。
        player?.let { exo ->
            runCatching { exo.playWhenReady = false }
            runCatching { exo.stop() }
            runCatching { exo.clearMediaItems() }
        }
        remote?.let { runCatching { it.close() } }
        remote = null
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
        saveLeavingEpisode(newIndex)
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
     * 心跳补建：该有备用实例却没有时，自动补一个。
     *
     * 备用实例会被内存门槛、Trim、建实例失败、错误回调清掉，而原实现只在 STATE_READY 那一刻
     * 尝试建一次——一旦被清，这一整集都不会再重建，播完就必然走「重新取流」的长黑。
     * 这里挂在界面 400ms 的心跳上，最多 3 秒就能补回来，`STATE_ENDED` 到达时通常已就绪。
     */
    private fun maybeRestoreStandby() {
        if (released || !useDualDecoder || !ready || handoffInProgress) return
        if (index + 1 > episodes.lastIndex) return
        // 正在为目标集准备 / 已经在等它：交给正常的 prepareStandby 流程，不重复建。
        if (standbyIndex != -1 || standbyPlayer != null || standbyJob?.isActive == true) return
        // 交接刚结束的一段时间内不补：旧实例 codec 还没回收，新实例还在爬坡。
        if (lastHandoffAt > 0L && SystemClock.elapsedRealtime() - lastHandoffAt < HANDOFF_WARMUP_MS) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastStandbyAttemptAt < STANDBY_RETRY_BACKOFF_MS) return
        lastStandbyAttemptAt = now
        PlayerLog.info("心跳补建备用实例 episode=${index + 1}")
        prepareStandby(index + 1)
    }

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
        // 没有 inactive 画面层就没有预渲染输出面，等两层 PlayerView 挂载后由 attachPlayerView 再触发。
        if (inactiveView == null) return
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
        if (activityManager == null || activityManager.isLowRamDevice) {
            switchToPlaylistPreload("低内存设备或设备信息不可用，不启用双解码器")
            return
        }
        // 接着问设备能不能同时跑两路：maxSupportedInstances 不够就根本不申请，
        // 否则第二路一申请，系统会回收正在播的那一路（这正是「播到一半无法解码」的成因）。
        // 必须用真实的视频尺寸去问：短剧是竖屏 720P，拿 1920×1080 去问会落到另一颗解码器上，
        // 探到的并发数未必属于真正在播的那一颗，误判后系统直接 native abort。
        val mime = player?.videoFormat?.sampleMimeType
        if (!DecoderCapacity.canRunTwoDecoders(mime, videoWidth, videoHeight)) {
            switchToPlaylistPreload(
                "设备并发解码上限 ${DecoderCapacity.maxInstances(mime, videoWidth, videoHeight)} 路" +
                    "（${mime ?: "未知编码"} ${videoWidth}x$videoHeight）",
            )
            return
        }
        if (standbyIndex == nextIndex && (standbyPlayer != null || standbyJob?.isActive == true)) return
        val availableMb = availableMemoryMb()
        if (availableMb < STANDBY_MEMORY_FLOOR_MB) {
            // -1 代表无法读取内存，不是「内存无限充足」；未知状态一律退回单播放器。
            switchToPlaylistPreload(
                if (availableMb < 0) "无法读取可用内存，不启用双解码器"
                else "可用内存不足（${availableMb}MB）",
            )
            return
        }
        clearStandby("准备新的下一集")
        standbyIndex = nextIndex
        lastStandbyAttemptAt = SystemClock.elapsedRealtime()
        val targetId = episodes[nextIndex]
        val http = repository.http.newBuilder()
            .dispatcher(Dispatcher())
            // 独立连接池：closeClient 会 evictAll()，共享池会把主链路正在用的连接一起清掉。
            .connectionPool(ConnectionPool())
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            // 比主链路短：备用取流失败要尽快回落到有画面的通道，而不是陪它等 25 秒。
            .callTimeout(18, TimeUnit.SECONDS)
            .build()
        standbyHttp = http
        standbyJob = scope.launch {
            // 错开主链路的 JS 取流窗口：主画面 READY 的同一瞬间再起第二个 QuickJS runtime，
            // 两个 runtime + 两路 codec 同时到峰值，正是概率性闪退的来源。
            // 备用有一整集的时间可用，晚 1.2 秒完全无感。
            delay(STANDBY_STAGGER_MS)
            if (released || !useDualDecoder || standbyIndex != nextIndex) return@launch
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
                        // 原来只缓到 6 秒，弱网下接管后几秒就吃干，「黑完接着转圈」往往就是它。
                        // 15 秒 720P 约 4–6MB，8MB 上限够装，不额外增加内存峰值。
                        .setBufferDurationsMs(15_000, 30_000, 2_500, 5_000)
                        .setTargetBufferBytes(STANDBY_BUFFER_BYTES)
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
        // 从这里到 prepare() 全部在保护内：PlayerView.setPlayer() 内部会 addListener，
        // setMediaSource()/prepare() 在实例已不可用时抛 IllegalStateException——
        // 这些异常会直接冒到主线程变成闪退。原来 try 只包住了 build()，恰恰把最危险的几行留在了外面。
        try {
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
            // 备用实例固定绑定到 inactive 层（物理移出屏幕），在其中渲染首帧。
            // 这一绑定在整个备用周期内都不再改变；接管时只交换两层位置，不重新 setPlayer。
            val backView = inactiveView
            if (backView == null) {
                // 没有挂载点就预热不了首帧，留着这个实例只会白占解码器。
                clearStandby("画面容器尚未就绪")
                return
            }
            if (backView.player !== secondary) backView.player = secondary
            applyVideoLayerOrder()
            secondary.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (standbyPlayer !== secondary || standbyIndex != target) return
                    if (state == Player.STATE_READY) {
                        // READY 不等于能出画：没有选中视频轨道时直接放弃，别把坏状态交给接管。
                        if (!secondary.currentTracks.isTypeSelected(C.TRACK_TYPE_VIDEO)) {
                            // 不能在播放器回调栈内销毁自己：回调返回后 EventDispatcher 还会访问
                            // renderer / trackSelector，实例已释放就会抛 IllegalStateException。
                            mainHandler.post {
                                if (released || standbyPlayer !== secondary || standbyIndex != target) return@post
                                clearStandby("备用播放器没有可用视频轨道")
                                queueNextEpisode(index, force = true)
                            }
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
                    // 必须挪到下一轮消息再销毁，理由同上：不能在错误分发栈内 release 自己。
                    mainHandler.post {
                        if (released || standbyPlayer !== secondary || standbyIndex != target) return@post
                        switchToPlaylistPreload("备用播放器准备失败：${playerError.errorCodeName}")
                    }
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
        } catch (problem: Throwable) {
            PlayerLog.error("buildStandbyPlayer 失败：${problem::class.java.simpleName}: ${problem.message}")
            switchToPlaylistPreload("备用播放器初始化失败：${problem.message}")
        }
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
        saveLeavingEpisode(targetIndex)
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
        // 重叠换层：只把新层移入，旧层原地保留（仍绑着旧播放器、仍显示最后一帧）。
        // 旧层的移出、解绑、释放统一交给 finishOverlapSwap()，等新层真正出画后再做——
        // 这样无论 surface 位置变更早一帧还是晚一帧，屏幕上都始终有一层画面。
        val outgoingView = playerView
        // 在全局 player 引用切换前隔离旧播放器，防止旧集事件误操作新集状态。
        old?.let { outgoing ->
            runCatching { outgoing.removeListener(mainListener) }
            runCatching { outgoing.playWhenReady = false }
            runCatching { outgoing.volume = 0f }
        }
        activeSlot = 1 - activeSlot
        playerViews[activeSlot]?.let { if (it.translationX != 0f) it.translationX = 0f }
        // 旧层已暂停并移除主监听器；Surface 保留最后一帧作为新层出画前的兜底。
        // 状态引用跟着一起换：player 指向刚抬上来的 standby，它本来就绑在现在的 active 层。
        player = standby
        remote = promotedRemote
        standbyPlayer = null
        standbyRemote = null
        standbyReady = false
        standbyFirstFrameAt = 0L
        standbyIndex = -1
        standbyJob = null
        standbyHttp?.let(::closeClient)
        standbyHttp = null
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
        // 重叠期开始：旧层还能兜 120ms 画面，新层一旦出帧就立刻收尾。
        overlapOutView = outgoingView
        overlapOutPlayer = old
        overlapRemote = previousRemote
        overlapToken += 1
        val swapToken = overlapToken
        mainHandler.postDelayed({
            if (overlapToken == swapToken) finishOverlapSwap("timeout")
        }, OVERLAP_HOLD_MS)
        PlayerLog.info("promoteStandby -> episode=$targetIndex quality=$standbyQuality")
        return true
    }

    /**
     * 释放备用播放器及其独立网络会话；不触碰正在播放的主实例。
     *
     * release() 不再同步执行：这个方法有六个入口（内存门槛、Trim、备用错误回调、切集、
     * 会话结束、通道切换），其中「备用错误回调」那条位于播放器的事件分发栈内——
     * 在那里同步 release 自己，回调返回后 EventDispatcher 还会访问 renderer/trackSelector，
     * 直接 IllegalStateException → 闪退。所以这里立即解绑输出面、清零状态，
     * 实例本体放到下一轮消息销毁；会话结束（[release]）时统一同步收尾。
     */
    private fun clearStandby(reason: String) {
        // 重叠换层期间 inactiveView 可能正是仍在屏上的旧层。
        // clearStandby 只清待接管资源，不能结束/解绑由 finishOverlapSwap 管理的旧画面。
        val overlapManagedView = overlapOutView
        // 幂等：没有备用实例就什么都不做，避免重复清状态把刚建好的重叠换层打断。
        if (standbyPlayer == null && standbyJob == null && standbyRemote == null &&
            standbyHttp == null && standbyIndex == -1
        ) {
            return
        }
        PlayerLog.info("clearStandby: $reason")
        standbyJob?.cancel()
        standbyJob = null
        val secondary = standbyPlayer
        standbyPlayer = null
        // inactive 层不再持有即将释放的备用实例（解绑必须立即做，PlayerView 不能留着死引用）。
        inactiveView?.let { view ->
            if (view !== overlapManagedView &&
                (secondary == null || view.player === secondary || view.player == null)
            ) {
                runCatching { view.player = null }
            }
            view.alpha = 1f
        }
        // 先停止备用播放器并清空 source，再关闭 RemoteVideo / 独立 HTTP 客户端。
        secondary?.let { exo ->
            runCatching { exo.playWhenReady = false }
            runCatching { exo.stop() }
            runCatching { exo.clearMediaItems() }
        }
        applyVideoLayerOrder()
        standbyRemote?.let { runCatching { it.close() } }
        standbyRemote = null
        closeClient(standbyHttp)
        standbyHttp = null
        standbyIndex = -1
        standbyQuality = ""
        standbyReady = false
        standbyFirstFrameAt = 0L
        if (secondary != null) retireStandbyInstance(secondary)
    }

    /** 解绑后的实例延后销毁：避免在播放器回调/合成帧里做最重的操作。 */
    private fun retireStandbyInstance(exo: ExoPlayer) {
        retiringStandby += exo
        mainHandler.postDelayed({
            retiringStandby.remove(exo)
            releaseStandbyInstance(exo, "retire")
        }, STANDBY_RETIRE_DELAY_MS)
    }

    /** 真正销毁一个 ExoPlayer 实例；每步都 runCatching，允许对已部分释放的实例重复调用。 */
    private fun releaseStandbyInstance(exo: ExoPlayer, reason: String) {
        runCatching { exo.playWhenReady = false }
        runCatching { exo.stop() }
        runCatching { exo.clearMediaItems() }
        runCatching { exo.release() }
        PlayerLog.info("standby released ($reason)")
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
        return runCatching {
            manager.getMemoryInfo(info)
            info.availMem / (1024L * 1024L)
        }.getOrDefault(-1L)
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

    // --- 播放页内联弹幕面板 ---
    // 面板就在画面里调，不再把用户甩到设置页：数值写回 library/extras（与设置页同一份偏好、
    // 同样落盘），再立刻 applyDanmakuPrefs()，本集画面马上跟着变。
    // 镜像成 Compose state，左右键改完面板上的数字才能即时刷新。

    var danmakuOpacity by mutableIntStateOf(library.danmakuOpacity)
        private set
    var danmakuFont by mutableIntStateOf(library.danmakuFont)
        private set
    var danmakuSpeed by mutableIntStateOf(library.danmakuSpeed)
        private set
    var danmakuRows by mutableIntStateOf(library.danmakuRows)
        private set
    var danmakuColor by mutableIntStateOf(extras.danmakuColorMode)
        private set

    fun changeDanmakuOpacity(value: Int) {
        danmakuOpacity = DanmakuPrefs.opacity(value)
        library.danmakuOpacity = danmakuOpacity
        applyDanmakuPrefs()
    }

    fun changeDanmakuFont(value: Int) {
        danmakuFont = DanmakuPrefs.font(value)
        library.danmakuFont = danmakuFont
        applyDanmakuPrefs()
    }

    fun changeDanmakuSpeed(value: Int) {
        danmakuSpeed = DanmakuPrefs.speed(value)
        library.danmakuSpeed = danmakuSpeed
        applyDanmakuPrefs()
    }

    fun changeDanmakuRows(value: Int) {
        danmakuRows = DanmakuPrefs.rows(value)
        library.danmakuRows = danmakuRows
        applyDanmakuPrefs()
    }

    /** 颜色取值 0=默认、1=多彩、2..9=八种单色，与设置页同一套编号。 */
    fun changeDanmakuColor(value: Int) {
        danmakuColor = value.coerceIn(0, 9)
        extras.danmakuColorMode = danmakuColor
        applyDanmakuPrefs()
    }

    fun resetDanmakuStyle() {
        changeDanmakuOpacity(DanmakuPrefs.OPACITY_DEFAULT)
        changeDanmakuFont(DanmakuPrefs.FONT_DEFAULT)
        changeDanmakuSpeed(DanmakuPrefs.SPEED_DEFAULT)
        changeDanmakuRows(DanmakuPrefs.ROWS_DEFAULT)
        changeDanmakuColor(0)
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
        // 弹幕时钟必须读 ExoPlayer 的实时进度，而不是 UI 每 400ms 才刷新的 position 缓存。
        // 直接用缓存会让弹幕每秒只跳 2.5 次，画面一顿一顿、怎么调画布都不丝滑。
        // player 始终指向当前激活的播放器（切集 promoteStandby 时已同步更新）。
        view.bind(
            loader,
            { runCatching { player?.currentPosition }.getOrNull()?.coerceAtLeast(0L) ?: position },
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
        // 心跳顺带兜住备用实例：被清掉 / 建失败时最多 3 秒补回来，别等到播完才发现没有下一集。
        maybeRestoreStandby()
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

    /** 切到后台前是否在播：仅当切后台时正在播，回到前台才自动恢复，避免覆盖用户的手动暂停。 */
    private var resumeOnForeground = false

    /**
     * Activity 进入后台（ON_STOP）：暂停播放并落盘进度。
     * 之前没有生命周期监听，切后台时页面未 dispose、播放器继续解码播放（声音/流量都在跑）。
     */
    fun onAppBackground() {
        if (released) return
        resumeOnForeground = playing
        if (resumeOnForeground) {
            runCatching { player?.pause() }
            // 暂停即落盘：切后台/息屏时进度不丢。
            save(completed = false)
        }
        // 备用播放器本就 playWhenReady=false，这里再兜底一次，避免后台偷偷解码。
        runCatching { standbyPlayer?.playWhenReady = false }
    }

    /** Activity 回到前台（ON_START）：只在切后台前正在播时恢复，尊重用户此前的暂停。 */
    fun onAppForeground() {
        if (released) return
        val shouldResume = resumeOnForeground
        resumeOnForeground = false
        if (shouldResume && player?.isPlaying != true) {
            runCatching { player?.play() }
        }
        // 后台期间备用实例可能被系统回收：回前台缓一拍再补建，别和刚恢复的主画面抢资源。
        mainHandler.postDelayed({
            if (!released && player?.isPlaying == true) maybeRestoreStandby()
        }, FOREGROUND_RESTORE_DELAY_MS)
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
        errorKind = ""
        clearStandby("用户选择兼容模式")
        player?.let(::applyPlaylistPolicy)
        retrySameStream(position)
    }

    /**
     * 错误卡上的「重新获取地址」：清掉这一集的地址缓存，按当前进度重新取流。
     *
     * 和「兼容模式重试」的区别很关键：那个复用同一个 RemoteVideo（同一条 URL、同一份分段缓存），
     * 只换解码器。地址失效时报的是 IO 错误，换解码器毫无作用，点了还是同一个错——
     * 这正是「播到一半片段不可用，点兼容模式重试也没用」的原因。
     */
    fun retryWithFreshUrl() {
        if (released) return
        recoveryToken += 1
        sourceRetries = 0
        error = null
        errorKind = ""
        buffering = true
        loadingLabel = "正在重新获取播放地址"
        clearStandby("重新获取播放地址")
        invalidateStreamAddress()
        prepare(index, position)
    }

    /** 让当前这一集的取流地址缓存立即失效，下一次 stream() 会重新签名。 */
    private fun invalidateStreamAddress() {
        runCatching {
            val videoId = episodes.getOrNull(index) ?: return@runCatching
            HongguoJsStreamSource.invalidate(episodeKey(videoId), effectiveQuality)
        }
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

    /**
     * 离开当前集之前的进度落盘。
     * 刚播完的那一集（completedIndex）已经写成「已看完」，这里不能再写 completed=false，
     * 否则自动连播一切集，「已看完」立刻被抹掉、详情页又显示「继续观看」。
     *
     * @param nextIndex 紧接着要进入的集号；只是退出/离开时传 -1。
     */
    private fun saveLeavingEpisode(nextIndex: Int = -1) {
        if (nextIndex != index && index == completedIndex) return
        save(completed = false)
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
        saveLeavingEpisode()
        released = true
        // 先收重叠换层的旧实例，再清备用实例：两者都可能在延迟队列里。
        settleOverlapNow()
        clearStandby("会话结束")
        discardQueue()
        // 延迟释放队列会被下面的 removeCallbacks 取消，这里同步收尾，防止实例泄漏。
        retiringStandby.toList().forEach { releaseStandbyInstance(it, "session") }
        retiringStandby.clear()
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
        // 两层 view 都解绑播放器（view 本身由 Compose 负责销毁）。
        playerViews.forEach { view ->
            runCatching { view?.player = null }
        }
        playerViews[0] = null
        playerViews[1] = null
    }

    private companion object {
        /** 片源不报时长时，弹幕窗口按这个跨度铺开（短剧远达不到 45 分钟）。 */
        const val UNKNOWN_DURATION_MS = 45 * 60_000L

        /**
         * 双播放器硬门槛：可用物理内存低于该值时只用单播放器预解析。
         * 512MB 对盒子太激进（availMem 常年 300–600 波动，等于经常不启用双播），
         * 768MB 又保守到几乎不启用；取 640 折中，低端机仍有 [clearStandby] 与 Trim 兜底。
         */
        const val STANDBY_MEMORY_FLOOR_MB = 640L

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

        /** 错误归属：界面据此决定给哪个补救按钮。 */
        const val ERROR_KIND_DECODE = "decode"
        const val ERROR_KIND_SOURCE = "source"
        const val ERROR_KIND_OTHER = "other"

        /** 一集里最多自动重新取流几次，以及第 N 次的退避（800ms / 1600ms）。 */
        const val MAX_SOURCE_RETRIES = 2
        const val SOURCE_RETRY_BASE_MS = 800L

        /** 备用实例取流前的错峰延迟：避开主链路 JS runtime + codec 的同一峰值窗口。 */
        const val STANDBY_STAGGER_MS = 1_200L

        /** 备用实例的缓冲上限：15 秒 720P 约 4–6MB，8MB 够装且不抬高内存峰值。 */
        const val STANDBY_BUFFER_BYTES = 8 * 1024 * 1024

        /**
         * 重叠换层保持时长：新层已出帧就提前收，最长等这么久。
         * 只影响旧层的退场时间，不影响播放进度；120ms 内即使新层没出帧，
         * 旧层也仍显示最后一帧，不会出现黑屏。
         */
        const val OVERLAP_HOLD_MS = 120L

        /** 备用实例解绑后多久真正 release：避开播放器事件分发栈与合成帧。 */
        const val STANDBY_RETIRE_DELAY_MS = 200L

        /** 重叠期旧层移出后多久解绑输出面（约 2–3 帧，够 SurfaceControl 提交）。 */
        const val OVERLAP_UNBIND_DELAY_MS = 48L

        /** 等待「正在准备的备用实例」的窗口：超时才推倒重来，避免把快好的实例扔掉。 */
        const val STANDBY_WAIT_MS = 700L

        /** 心跳补建备用实例的退避间隔。 */
        const val STANDBY_RETRY_BACKOFF_MS = 3_000L

        /** 回前台多久后补建备用实例。 */
        const val FOREGROUND_RESTORE_DELAY_MS = 1_500L
    }
}
