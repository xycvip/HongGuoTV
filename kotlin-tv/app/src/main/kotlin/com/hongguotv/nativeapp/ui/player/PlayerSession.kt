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
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.hongguotv.core.ContentRepository
import com.hongguotv.core.DanmakuLoader
import com.hongguotv.core.RemoteVideo
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

    /** 关闭无感连播时的单实例 playlist 预载：已经追加进队列的下一集。 */
    private var queuedIndex = -1
    private var queuedStream: PreparedStream? = null
    private var queueJob: Job? = null

    private data class PreparedStream(val video: RemoteVideo, val quality: String)

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
                    if (extras.seamlessPlayback) prepareStandby(index + 1) else queueNextEpisode(index)
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
            if (released || extras.seamlessPlayback) return
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
        }

        override fun onPlayerError(playerError: PlaybackException) {
            if (released) return
            buffering = false
            suppressBuffering = false
            playing = false
            error = friendlyError(playerError)
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
        prepare(index, 0L)
    }

    private fun buildPlayer() {
        val exo = ExoPlayer.Builder(context).setBandwidthMeter(bandwidthMeter).build()
        exo.setHandleAudioBecomingNoisy(true)
        exo.playWhenReady = true
        applyPlaylistPolicy(exo)
        exo.addListener(mainListener)
        player = exo
    }

    // ------------------------------------------------------------ 界面挂载

    /** 由播放页在 PlayerView 创建后调用：接管时换播放器、隐藏输出面挂到同一容器。 */
    fun attachPlayerView(view: PlayerView) {
        playerView = view
        if (view.player !== player) view.player = player
        // 会话可能先于画面视图创建：视图加入后补一次备用准备，否则隐藏输出面没有挂载点。
        view.post {
            if (!released && ready && extras.seamlessPlayback && standbyIndex == -1) prepareStandby(index + 1)
        }
    }

    fun detachPlayerView(view: PlayerView) {
        if (playerView === view) playerView = null
    }

    private fun friendlyError(error: PlaybackException): String {
        val raw = (error.cause?.message ?: error.message).orEmpty()
        return when {
            raw.contains("分段", true) || raw.contains("视频", true) -> raw
            error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED -> "网络连接中断，请检查网络后重试"
            error.errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED -> "当前设备无法解码该视频，可尝试降低清晰度"
            error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "视频服务器返回异常，请稍后重试"
            else -> "播放失败：${raw.take(80)}"
        }
    }

    fun prepare(targetIndex: Int, startMs: Long) {
        if (released) return
        if (targetIndex !in episodes.indices) return
        // 无感连播：目标正是备用播放器准备好的那一集，且已渲染过首帧，直接接管。
        if (startMs == 0L && promoteStandby(targetIndex)) return
        // 关闭开关时：目标正是同一播放器 playlist 里预载好的下一集，直接前进，不重建。
        if (startMs == 0L && switchToQueued(targetIndex)) return
        // 接管没成功就别让半成品备用实例继续占着解码器，统一走普通流程。
        if (standbyIndex != -1) clearStandby("改用普通播放流程")
        val episodeId = episodes[targetIndex]
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
                    val info = context.playbackStreamSource(repository.http).stream(episodeKey(episodeId), library.maxQuality)
                    PreparedStream(RemoteVideo(repository.http, info).prepare(), info.quality)
                }
            }
            if (released) {
                result.getOrNull()?.video?.let { runCatching { it.close() } }
                return@launch
            }
            result.onSuccess { preparedStream ->
                qualityLabel = preparedStream.quality
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
        if (extras.seamlessPlayback && !force) return
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
                    val info = context.playbackStreamSource(repository.http).stream(episodeKey(targetId), library.maxQuality)
                    PreparedStream(RemoteVideo(repository.http, info).prepare(), info.quality)
                }.getOrNull()
            }
            if (released || prepared == null) {
                prepared?.video?.let { runCatching { it.close() } }
                return@launch
            }
            // 预载期间用户已经切集 / 改清晰度 / 打开双播放器：这份流作废。
            if (index != current || (extras.seamlessPlayback && !force)) {
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
        if (extras.seamlessPlayback) return false
        val exo = player ?: return false
        if (queuedIndex != targetIndex || queuedStream == null) return false
        val nextItem = exo.currentMediaItemIndex + 1
        if (nextItem >= exo.mediaItemCount) return false
        if (exo.getMediaItemAt(nextItem).mediaId != episodes[targetIndex]) return false
        exo.seekToNextMediaItem()
        return true
    }

    /** 队列过渡完成：把界面状态、进度、弹幕、数据源全部换到新集，并继续预载再下一集。 */
    private fun applyEpisodeTransition(newIndex: Int) {
        if (newIndex !in episodes.indices) return
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

    /** 关闭开关时给同一实例开 playlist 预载；开启双播放器时收回，避免两个解码器抢带宽。 */
    private fun applyPlaylistPolicy(exo: ExoPlayer) {
        runCatching {
            exo.setPreloadConfiguration(
                if (extras.seamlessPlayback) ExoPlayer.PreloadConfiguration.DEFAULT
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
        if (released || !extras.seamlessPlayback) return
        // 没有画面容器就没有隐藏输出面，等 PlayerView 挂载后由 attachPlayerView 再触发。
        if (playerView == null) return
        if (nextIndex !in episodes.indices) {
            clearStandby("已经是最后一集")
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
                    val info = context.playbackStreamSource(http, notifyOnFallback = false).stream(episodeKey(targetId), library.maxQuality)
                    PreparedStream(RemoteVideo(http, info).prepare(), info.quality)
                }.getOrNull()
            }
            if (released || !extras.seamlessPlayback || standbyIndex != nextIndex) {
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
            clearStandby("第二播放器创建失败：${problem.message}")
            queueNextEpisode(index, force = true)
            return
        }
        standbyPlayer = secondary
        secondary.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build(),
            true,
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
                clearStandby("备用播放器准备失败：${playerError.errorCodeName}")
                queueNextEpisode(index, force = true)
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
        if (!extras.seamlessPlayback) return false
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
        removeStandbyOutput()
        standby.addListener(mainListener)
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
        // 接管成功后再准备下一集，形成连续的预渲染链。
        prepareStandby(targetIndex + 1)
        return true
    }

    private fun createStandbyOutput(): SurfaceView? {
        // 必须挂进 PlayerView（FrameLayout）内部：Compose 容器不会给额外 addView 的子视图布局，
        // 放在外面会得到 0 尺寸的 surface，备用播放器永远渲染不出首帧。
        val host = playerView ?: return null
        val size = (16 * context.resources.displayMetrics.density).toInt().coerceAtLeast(1)
        val view = SurfaceView(host.context).apply {
            alpha = 0f
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        return runCatching {
            host.addView(view, FrameLayout.LayoutParams(size, size, Gravity.END or Gravity.BOTTOM))
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
        removeStandbyOutput()
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
        if (!danmakuEnabled || duration <= 0L || currentEpisodeId.isBlank()) {
            runCatching { view.unbind() }
            danmakuLoader = null
            return
        }
        applyDanmakuPrefs()
        val loader = DanmakuLoader(danmakuSource, series.id, currentEpisodeId, duration)
        danmakuLoader = loader
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
        if (exo.isPlaying) exo.pause() else exo.play()
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

    fun changeQuality(value: Int) {
        quality = value
        library.maxQuality = value
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
        playerView = null
    }

    private companion object {
        /** 双播放器硬门槛：可用物理内存低于该值时只用单播放器预解析。 */
        const val STANDBY_MEMORY_FLOOR_MB = 300L

        /** 接管后等可见首帧的兜底时限（原实现 3 秒，这里收紧到 1.5 秒）。 */
        const val HANDOFF_TIMEOUT_MS = 1_500L

        /** 首帧出现后的稳定窗，避免画面刚接上就闪一下缓冲圈。 */
        const val HANDOFF_SETTLE_MS = 350L

        /** playlist 预载深度：15 秒，足够覆盖一集尾巴加切集握手。 */
        const val PLAYLIST_PRELOAD_US = 15_000_000L
    }
}
