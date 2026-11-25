// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp.ui.screens

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBackIosNew
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.HighQuality
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.hongguotv.core.DanmakuPrefs
import com.hongguotv.core.PlaybackQuality
import com.hongguotv.core.PlaybackSpeed
import com.hongguotv.core.VideoFrameMode
import com.hongguotv.nativeapp.ui.components.ButtonTone
import com.hongguotv.nativeapp.ui.components.Choice
import com.hongguotv.nativeapp.ui.components.ChoiceDialog
import com.hongguotv.nativeapp.ui.components.PulseDots
import com.hongguotv.nativeapp.ui.components.TvButton
import com.hongguotv.nativeapp.ui.components.tryRequestFocus
import com.hongguotv.nativeapp.R
import com.hongguotv.nativeapp.ui.player.PlayerSession
import com.hongguotv.nativeapp.ui.theme.Ink
import com.hongguotv.nativeapp.ui.theme.Paper
import com.hongguotv.nativeapp.ui.theme.Rouge
import com.hongguotv.nativeapp.DanmakuView
import com.hongguotv.nativeapp.PlaybackExtras
import com.hongguotv.nativeapp.ui.data.RemoteKey
import com.hongguotv.nativeapp.ui.data.SettingsSnapshot
import com.hongguotv.nativeapp.ui.theme.d
import com.hongguotv.nativeapp.ui.theme.ui
import kotlinx.coroutines.delay

@UnstableApi
@Composable
fun PlayerScreen(
    session: PlayerSession,
    settings: SettingsSnapshot,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    clock: String = "",
    onDanmakuSettings: () -> Unit = {},
) {
    val context = LocalContext.current
    var controlsVisible by remember { mutableStateOf(true) }
    var episodesOpen by remember { mutableStateOf(false) }
    var seekHint by remember { mutableStateOf<String?>(null) }
    var hintToken by remember { mutableIntStateOf(0) }
    var interaction by remember { mutableIntStateOf(0) }
    var qualityDialog by remember { mutableStateOf(false) }
    var speedDialog by remember { mutableStateOf(false) }
    var frameDialog by remember { mutableStateOf(false) }
    // 弹幕设置：在播放页内联弹出，不再跳去设置页（跳走会把播放会话一起销毁）。
    var danmakuOpen by remember { mutableStateOf(false) }
    val playFocus = remember { FocusRequester() }
    val rootFocus = remember { FocusRequester() }
    val episodeFocus = remember { FocusRequester() }
    val episodeFirstFocus = remember { FocusRequester() }
    // 面板六行各一个 requester：首尾互相绕回，方向键不会跑到面板背后的播放控件上。
    val danmakuRowFocus = remember { List(6) { FocusRequester() } }
    // 错误弹窗的首/末按钮：弹窗一出就把焦点拉过来，否则遥控器确定键落在背后的播放键上。
    val errorPrimaryFocus = remember { FocusRequester() }
    val errorLastFocus = remember { FocusRequester() }

    fun poke() {
        interaction++
        controlsVisible = true
    }

    fun seek(delta: Long, label: String) {
        session.seekBy(delta)
        seekHint = label
        hintToken++
        // 只重置自动收起计时，不把控制层叫出来：它一出现就会抢走焦点，
        // 第二次按左右变成移动光标，连续快进就此中断。
        interaction++
    }

    /** 按用户配置分派遥控器动作。 */
    fun dispatch(action: String) {
        var revealControls = false
        when (action) {
            PlaybackExtras.REMOTE_PREVIOUS -> session.previous()
            PlaybackExtras.REMOTE_NEXT -> session.next()
            PlaybackExtras.REMOTE_PLAY_PAUSE -> session.togglePlay()
            PlaybackExtras.REMOTE_EPISODES -> {
                episodesOpen = true
                revealControls = true
            }
            PlaybackExtras.REMOTE_COMMENTS -> {
                session.toggleDanmaku()
                seekHint = if (session.danmakuEnabled) "弹幕已开启" else "弹幕已关闭"
                hintToken++
            }
            PlaybackExtras.REMOTE_MENU -> {
                revealControls = true
                when (settings.menuKeyAction) {
                    PlaybackExtras.MENU_EPISODES -> episodesOpen = true
                    PlaybackExtras.MENU_DANMAKU -> {
                        session.toggleDanmaku()
                        seekHint = if (session.danmakuEnabled) "弹幕已开启" else "弹幕已关闭"
                        hintToken++
                    }
                    else -> Unit
                }
            }
            PlaybackExtras.REMOTE_SLEEP_TIMER -> {
                seekHint = "暂不支持定时关闭"
                hintToken++
            }
            else -> Unit
        }
        if (revealControls) poke() else interaction++
    }

    // 5 秒无操作自动收起控制层；暂停、缓冲、选集、弹幕面板打开时保持显示。
    LaunchedEffect(interaction, session.playing, session.buffering, episodesOpen, danmakuOpen) {
        if (!controlsVisible) return@LaunchedEffect
        delay(5000)
        if (session.playing && !session.buffering && !episodesOpen && !danmakuOpen) controlsVisible = false
    }
    LaunchedEffect(hintToken) {
        if (hintToken == 0) return@LaunchedEffect
        delay(900)
        seekHint = null
    }
    // 焦点跟着画面状态走：控制层可见时落在主播放键上（一进来就能按方向键操作），
    // 隐藏时收回根节点（方向键直接变成快进/快退），选集抽屉打开则进抽屉。
    // 之前焦点永远停在根 Box 上，遥控器看着「完全没反应」，就是缺了这一步。
    // 焦点跟着画面状态走：错误弹窗优先，其次弹幕面板 / 选集抽屉 / 控制层 / 根节点。
    // 控制层可见时落在主播放键上（一进来就能按方向键操作），隐藏时收回根节点
    // （方向键直接变成快进/快退）。之前焦点永远停在根 Box 上，遥控器看着「完全没反应」。
    LaunchedEffect(controlsVisible, episodesOpen, danmakuOpen, session.error) {
        // 选集抽屉里优先落在当前集；当前集不在首屏时退回第一集，保证一定有落点。
        val targets = when {
            session.error != null -> listOf(errorPrimaryFocus)
            danmakuOpen -> danmakuRowFocus
            episodesOpen -> listOf(episodeFocus, episodeFirstFocus)
            controlsVisible -> listOf(playFocus)
            else -> listOf(rootFocus)
        }
        // 控制层的进入动画和 LazyGrid 需要几帧才 attach，单次请求容易落空。
        repeat(12) {
            if (targets.any { it.tryRequestFocus() }) return@LaunchedEffect
            withFrameNanos { }
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            session.refreshPosition()
            delay(400)
        }
    }

    // 返回键分层：先关浮层，再收控制层，最后才退出播放。
    // 之前控制层可见时直接 onBack()，用户只是想收起进度条，结果一脚踹回详情页。
    BackHandler {
        when {
            danmakuOpen -> danmakuOpen = false
            episodesOpen -> episodesOpen = false
            qualityDialog -> qualityDialog = false
            speedDialog -> speedDialog = false
            frameDialog -> frameDialog = false
            controlsVisible -> controlsVisible = false
            else -> onBack()
        }
    }

    Box(
        modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(rootFocus)
            .focusable()
            // 用 preview 阶段拦截：控制层隐藏时方向键要立刻变成快进/快退，
            // 不能被 focusable 的默认焦点移动先吃掉。
            .onPreviewKeyEvent { event ->
                if (event.type != androidx.compose.ui.input.key.KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.nativeKeyEvent.keyCode) {
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                        // 控制层可见时把「确定」让给焦点所在的控件（清晰度/选集/进度条…），
                        // 否则 preview 会先把它当成「确认键行为」消费掉——用户点「清晰度」
                        // 却触发播放/暂停，就是这里抢了事件。
                        if (!controlsVisible) {
                            poke()
                            true
                        } else {
                            false
                        }
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        if (!controlsVisible) {
                            seek(-session.seekStepMillis, "快退 ${session.seekStepMillis / 1000} 秒")
                            true
                        } else false
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        if (!controlsVisible) {
                            seek(session.seekStepMillis, "快进 ${session.seekStepMillis / 1000} 秒")
                            true
                        } else false
                    }
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        if (!controlsVisible) {
                            dispatch(session.remoteAction(RemoteKey.UP))
                            true
                        } else false
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        if (!controlsVisible) {
                            dispatch(session.remoteAction(RemoteKey.DOWN))
                            true
                        } else false
                    }
                    KeyEvent.KEYCODE_MENU -> {
                        // 菜单键任何时候都要能用：控制层可见时被焦点移动吃掉，
                        // 用户会觉得「设置里配了菜单键却一点反应没有」。
                        dispatch(session.remoteAction(RemoteKey.MENU))
                        true
                    }
                    else -> false
                }
            },
    ) {
        // 视频画面：两层 PlayerView（均为 SurfaceView）叠放，切集时只交换两层的屏幕位置、
        // 不重新绑定播放器，避免「换 player → surface 重建 → 闪屏/黑帧」。
        // inactive 层由 session 用 translationX 物理移出屏幕（surface 仍存活、照常预渲染首帧），
        // 不用 alpha / z-order——那两类「改属性」方式在 SurfaceView 上有传播时序窗口，
        // 会让备用首帧偶发闪到前台。位置切换是 View 属性、同步生效，无此问题。
        // 弹幕是独立 SurfaceView（setZOrderMediaOverlay），浮在视频之上、控制层之下，
        // 由 Choreographer 对齐 vsync 驱动，重绘不触发 window 合成，视频不受影响。
        // 播放器与 view 的绑定、位置切换由 session 管理，这里的 update 只同步缩放模式。
        fun buildVideoLayer(ctx: android.content.Context, slot: Int): PlayerView {
            val view = android.view.View.inflate(ctx, R.layout.player_view_surface, null) as PlayerView
            return view.apply {
                useController = false
                // PlayerView 是原生 View，一旦参与 Android 层的焦点分配，
                // Compose 就收不到方向键了（表现：整页遥控器全失效）。
                isFocusable = false
                isFocusableInTouchMode = false
                descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
                setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                // 首帧不跳变：PlayerView 内部的 AspectRatioFrameLayout 在视频尺寸回来之前
                // 没有宽高比约束，会先按全屏铺满，等 onVideoSizeChanged 才缩到真实比例——
                // 表现就是「首帧先全屏再缩小」。短剧源均为 9:16 竖屏，这里先预设 9:16，
                // 首帧即按竖屏窄条布局（与最终正常画面一致），不会全屏铺满再跳变；
                // 真实尺寸到达后 Media3 会覆盖该值，不影响后续任何一集。
                // 横屏内容极少见；若遇到，首帧会先按 9:16 布局、拿到尺寸后自动更正一次。
                findViewById<AspectRatioFrameLayout>(androidx.media3.ui.R.id.exo_content_frame)
                    ?.setAspectRatio(9f / 16f)
                session.attachPlayerView(this, slot)
            }
        }
        AndroidView(
            factory = { ctx -> buildVideoLayer(ctx, 0) },
            update = { view -> view.resizeMode = session.frameResizeMode },
            onRelease = { view -> session.detachPlayerView(view, 0) },
            modifier = Modifier.fillMaxSize(),
        )
        AndroidView(
            factory = { ctx -> buildVideoLayer(ctx, 1) },
            update = { view -> view.resizeMode = session.frameResizeMode },
            onRelease = { view -> session.detachPlayerView(view, 1) },
            modifier = Modifier.fillMaxSize(),
        )
        // 手势覆盖层：放在两层视频之上，切集换层时点击/双击行为保持稳定。
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { poke() },
                        onDoubleTap = {
                            if (session.playing) {
                                session.pause()
                                seekHint = "暂停"
                            } else {
                                session.play()
                                seekHint = "播放"
                            }
                            hintToken++
                        },
                    )
                },
        )

        // 弹幕层：独立 SurfaceView + Choreographer（vsync 对齐），setZOrderMediaOverlay 浮在
        // 视频之上、控制层之下。用独立 surface 而非普通 View：普通 View 每帧 invalidate
        // 会强制整个 window 重合成，把两层全屏视频 SurfaceView 一起拖垮（视频卡死/错位）。
        // 只覆盖顶部弹幕带：匹配常见弹幕区域，也限制每帧清屏/绘制面积。
        val danmakuView = remember { DanmakuView(context) }
        AndroidView(
            factory = { danmakuView },
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.46f)
                .padding(top = d(26)),
        )
        DisposableEffect(danmakuView) {
            session.attachDanmaku(danmakuView)
            onDispose { session.detachDanmaku(danmakuView) }
        }

        // 播放信息与实时网速
        val showInfo = settings.videoInfoMode > 0 && session.ready
        if (showInfo || settings.showNetworkSpeed) {
            Column(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = ui.gutter, top = d(64)),
                horizontalAlignment = Alignment.End,
            ) {
                if (showInfo) {
                    Text(
                        buildString {
                            if (session.videoWidth > 0) append("${session.videoWidth}×${session.videoHeight}")
                            if (session.qualityLabel.isNotBlank()) {
                                if (isNotEmpty()) append("  ·  ")
                                append(session.qualityLabel)
                            }
                            if (settings.videoInfoMode >= 2) {
                                if (session.videoBitrate > 0) append("  ·  ${"%.1f".format(session.videoBitrate / 1_000_000f)} Mbps")
                                if (session.videoFrameRate > 0f) append("  ·  ${"%.0f".format(session.videoFrameRate)} fps")
                            }
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xCCFFFFFF),
                        modifier = Modifier
                            .clip(RoundedCornerShape(d(6)))
                            .background(Color(0x66000000))
                            .padding(horizontal = d(8), vertical = d(4)),
                    )
                }
                if (settings.showNetworkSpeed) {
                    Spacer(Modifier.height(d(6)))
                    Text(
                        "↓ ${"%.1f".format(session.networkMbps)} Mbps",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xB3FFFFFF),
                        modifier = Modifier
                            .clip(RoundedCornerShape(d(6)))
                            .background(Color(0x66000000))
                            .padding(horizontal = d(8), vertical = d(4)),
                    )
                }
            }
        }

        if (session.buffering && session.error == null) {
            Column(
                Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                PulseDots(Color.White)
                Spacer(Modifier.height(d(14)))
                Text(
                    session.loadingLabel.ifBlank { "正在缓冲…" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = Paper.Secondary,
                )
            }
        }

        session.error?.let { message ->
            val canRetryDecode = session.decodeError
            Column(
                Modifier
                    .align(Alignment.Center)
                    .width(d(420))
                    .clip(RoundedCornerShape(d(20)))
                    .background(Color(0xE610131A))
                    .border(d(1f), Color(0x1FFFFFFF), RoundedCornerShape(d(20)))
                    .padding(d(26))
                    // 上下键在弹窗内循环，别再落到背后的播放控制条上。
                    .onKeyEvent { event ->
                        if (event.type != androidx.compose.ui.input.key.KeyEventType.KeyDown) return@onKeyEvent false
                        when (event.key) {
                            Key.DirectionUp -> {
                                errorPrimaryFocus.tryRequestFocus(); true
                            }
                            Key.DirectionDown -> {
                                errorLastFocus.tryRequestFocus(); true
                            }
                            else -> false
                        }
                    },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("播放遇到问题", style = MaterialTheme.typography.titleLarge, color = Paper.Pure)
                Spacer(Modifier.height(d(10)))
                Text(message, style = MaterialTheme.typography.bodySmall, color = Paper.Secondary)
                Spacer(Modifier.height(d(20)))
                Row(horizontalArrangement = Arrangement.spacedBy(d(12))) {
                    // 地址/分段类错误走「重新获取地址」：必须让取流缓存失效再拿一条新签名地址，
                    // 原地重开同一条流（原来的做法）只会再报同一个错。
                    if (session.sourceError) {
                        TvButton(
                            "重新获取地址",
                            { session.retryWithFreshUrl() },
                            tone = ButtonTone.Primary,
                            focusRequester = errorPrimaryFocus,
                            downFocus = if (canRetryDecode) errorLastFocus else null,
                        )
                    } else {
                        TvButton(
                            "重试",
                            { session.prepare(session.index, session.position) },
                            tone = ButtonTone.Primary,
                            focusRequester = errorPrimaryFocus,
                            downFocus = if (canRetryDecode) errorLastFocus else null,
                        )
                    }
                    TvButton(
                        "返回详情",
                        onBack,
                        tone = ButtonTone.Ghost,
                        focusRequester = if (canRetryDecode) null else errorLastFocus,
                        upFocus = errorPrimaryFocus,
                    )
                }
                // 只在解码类错误时提供「兼容模式重试」：它复用同一条流、只换解码器，
                // 地址失效时点了必然还是同一个错，之前误显示在这里误导过一次。
                if (canRetryDecode) {
                    Spacer(Modifier.height(d(12)))
                    TvButton(
                        "兼容模式重试",
                        { session.retryWithoutDualDecoder() },
                        tone = ButtonTone.Quiet,
                        focusRequester = errorLastFocus,
                        upFocus = errorPrimaryFocus,
                    )
                }
            }
        }

        // 顶部信息与控制
        AnimatedVisibility(visible = controlsVisible, enter = fadeIn(), exit = fadeOut()) {
            Box(Modifier.fillMaxSize()) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(d(150))
                        .background(Brush.verticalGradient(listOf(Color(0xE6000000), Color.Transparent))),
                )
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = ui.gutter, vertical = d(22)),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 屏幕返回箭头按设置显隐；遥控器返回键始终通过 BackHandler 生效，不受此开关影响。
                    if (settings.showScreenBack) {
                        CircleAction(Icons.Rounded.ArrowBackIosNew, "返回", d(44)) { onBack() }
                        Spacer(Modifier.width(d(18)))
                    }
                    Column {
                        Text(
                            session.series.title,
                            style = MaterialTheme.typography.titleLarge,
                            color = Paper.Pure,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(d(3)))
                        Text(
                            "第 ${session.index + 1} 集 / 共 ${session.episodes.size} 集",
                            style = MaterialTheme.typography.labelSmall,
                            color = Paper.Secondary,
                        )
                    }
                    // 右上角时间：跟随设置里的「时间显示」开关，不加背景框，避免遮挡画面。
                    if (settings.showClock && clock.isNotBlank()) {
                        Spacer(Modifier.weight(1f))
                        Text(clock, style = MaterialTheme.typography.labelMedium, color = Paper.Secondary)
                    }
                }

                // 底部控制区
                Column(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(Brush.verticalGradient(0f to Color.Transparent, 0.45f to Color(0x99000000), 1f to Color(0xE6000000)))
                        .padding(horizontal = ui.gutter, vertical = d(24)),
                ) {
                    SeekBar(
                        position = session.position,
                        duration = session.duration,
                        onSeek = { session.seekTo(it) },
                        onSeekBy = { seek(it, if (it > 0) "快进 10 秒" else "快退 10 秒") },
                        onInteract = { poke() },
                    )
                    // 进度条、播放键整组下移：两段间距各收窄 7dp，让它们更贴近底部选项行，
                    // 而不是悬在控制区上半部分。选项行仍锚在底部，不动。
                    Spacer(Modifier.height(d(9)))
                    // 主播放控制：去掉快退/快进图标后只留「上一集 / 播放 / 下一集」，居中排布。
                    // 快进快退仍可用——控制层隐藏时左右键直接 seek，进度条聚焦时左右键也能 seek。
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(d(28)), verticalAlignment = Alignment.CenterVertically) {
                            CircleAction(Icons.Rounded.SkipPrevious, "上一集", d(46)) { session.previous() }
                            Box(
                                Modifier
                                    .size(d(68))
                                    .clip(CircleShape)
                                    .background(Brush.linearGradient(listOf(Rouge.Bright, Rouge.Deep)))
                                    .border(d(1.6f), Color.White.copy(0.5f), CircleShape)
                                    .focusRequester(playFocus)
                                    .clickable {
                                        session.togglePlay()
                                        poke()
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    if (session.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                    if (session.playing) "暂停" else "播放",
                                    tint = Color.White,
                                    modifier = Modifier.size(d(34)),
                                )
                            }
                            CircleAction(Icons.Rounded.SkipNext, "下一集", d(46)) { session.next() }
                        }
                    }
                    Spacer(Modifier.height(d(9)))
                    // 选项从右上角移到左下角，紧邻播放键下方，遥控器一进来就能左右遍历到。
                    // 右下角保留遥控器提示，两者共用一行，互不遮挡。
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Row(horizontalArrangement = Arrangement.spacedBy(d(10)), verticalAlignment = Alignment.CenterVertically) {
                            ControlChip(if (session.qualityLabel.isBlank()) "清晰度" else session.qualityLabel, Icons.Rounded.HighQuality) {
                                qualityDialog = true
                            }
                            ControlChip(PlaybackSpeed.label(session.speed), Icons.Rounded.Speed) { speedDialog = true }
                            ControlChip(session.frameMode.label, Icons.Rounded.AspectRatio) { frameDialog = true }
                            // 两个弹幕图标（设置页弹幕图标 Subtitles 的副本），放在选集前面：
                            // 开关角标 = 小勾/小叉；设置角标 = 小螺丝（齿轮）。互不抢焦点。
                            DanmakuBadgeChip(
                                badge = if (session.danmakuEnabled) Icons.Rounded.Check else Icons.Rounded.Close,
                                badgeTint = if (session.danmakuEnabled) Color(0xFF43A047) else Color(0xFF9AA0AA),
                                description = if (session.danmakuEnabled) "弹幕已开启" else "弹幕已关闭",
                            ) {
                                session.toggleDanmaku()
                                seekHint = if (session.danmakuEnabled) "弹幕已开启" else "弹幕已关闭"
                                hintToken++
                                poke()
                            }
                            DanmakuBadgeChip(
                                badge = Icons.Rounded.Settings,
                                badgeTint = Rouge.Bright,
                                description = "弹幕设置",
                                onClick = {
                                    danmakuOpen = true
                                    poke()
                                },
                            )
                            ControlChip("选集", Icons.Rounded.PlaylistPlay) { episodesOpen = true }
                        }
                        Spacer(Modifier.weight(1f))
                        // 提示文案由设置里的按键映射实时推导，改完设置回播放页就能看到新说明。
                        val remoteHints = remember(
                            settings.remoteUp,
                            settings.remoteDown,
                            settings.remoteConfirm,
                            settings.remoteMenu,
                            settings.seekStepSeconds,
                        ) {
                            PlaybackExtras.remoteHintLines(
                                up = settings.remoteUp,
                                down = settings.remoteDown,
                                confirm = settings.remoteConfirm,
                                menu = settings.remoteMenu,
                                seekSeconds = settings.seekStepSeconds,
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.PlaylistPlay, null, tint = Paper.Secondary, modifier = Modifier.size(d(18)))
                            Spacer(Modifier.width(d(8)))
                            Column(horizontalAlignment = Alignment.Start) {
                                remoteHints.forEach { line ->
                                    Text(
                                        "遥控器：$line",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Paper.Tertiary,
                                        maxLines = 1,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // 进度条常驻：屏幕最底部一条极细进度线，比常规进度条更细、低对比，不聚焦不拦截输入，
        // 只为随时看到播放位置。控制层显隐、选集抽屉都不影响它；有真实时长才绘制。
        if (settings.persistentProgress && session.duration > 0L) {
            PersistentProgress(
                position = session.position,
                duration = session.duration,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }

        seekHint?.let { hint ->
            Row(
                Modifier
                    .align(Alignment.Center)
                    .clip(RoundedCornerShape(d(999)))
                    .background(Color(0xCC0A0C12))
                    .border(d(1f), Color(0x22FFFFFF), RoundedCornerShape(d(999)))
                    .padding(horizontal = d(22), vertical = d(12)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(hint, style = MaterialTheme.typography.titleSmall, color = Paper.Pure)
            }
        }

        // 选集抽屉
        AnimatedVisibility(
            visible = episodesOpen,
            enter = slideInHorizontally { it } + fadeIn(),
            exit = slideOutHorizontally { it } + fadeOut(),
            modifier = Modifier.align(Alignment.CenterEnd),
        ) {
            Column(
                Modifier
                    .width(d(470))
                    .fillMaxHeight()
                    .background(Color(0xF20B0E14))
                    .border(d(1f), Color(0x14FFFFFF), RoundedCornerShape(topStart = d(24), bottomStart = d(24)))
                    .padding(d(24)),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("选集", style = MaterialTheme.typography.headlineSmall, color = Paper.Pure, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.width(d(10)))
                    Text("${session.episodes.size} 集", style = MaterialTheme.typography.labelSmall, color = Paper.Tertiary)
                    Spacer(Modifier.weight(1f))
                    CircleAction(Icons.Rounded.Close, "关闭", d(38)) { episodesOpen = false }
                }
                Spacer(Modifier.height(d(16)))
                LazyVerticalGrid(
                    columns = GridCells.Fixed(6),
                    horizontalArrangement = Arrangement.spacedBy(d(8)),
                    verticalArrangement = Arrangement.spacedBy(d(8)),
                ) {
                    itemsIndexed(session.episodes) { index, _ ->
                        EpisodeButton(
                            index = index,
                            active = index == session.index,
                            focusRequester = when (index) {
                                session.index -> episodeFocus
                                0 -> episodeFirstFocus
                                else -> null
                            },
                            onClick = {
                                session.selectEpisode(index)
                                episodesOpen = false
                                poke()
                            },
                        )
                    }
                }
            }
        }

        // 弹幕设置面板：盖在画面上，调完即生效、不离开播放页。
        // 每行左右键直接调档（遥控器最省事），确定键等于「加一档」。
        if (danmakuOpen) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color(0x99000000))
                    // 用 pointerInput 而不是 clickable：遮罩只需要吃掉点击，不能成为可聚焦节点，
                    // 否则方向键会从面板跑到遮罩上。
                    .pointerInput(Unit) {
                        detectTapGestures(onTap = { danmakuOpen = false })
                    },
            )
            Column(
                Modifier
                    .align(Alignment.Center)
                    .width(d(600))
                    .clip(RoundedCornerShape(d(22)))
                    .background(Color(0xF20B0E14))
                    .border(d(1f), Color(0x1FFFFFFF), RoundedCornerShape(d(22)))
                    .padding(d(22)),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Subtitles, null, tint = Rouge.Bright, modifier = Modifier.size(d(20)))
                    Spacer(Modifier.width(d(10)))
                    Text("弹幕设置", style = MaterialTheme.typography.headlineSmall, color = Paper.Pure, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.weight(1f))
                    CircleAction(Icons.Rounded.Close, "关闭", d(38)) { danmakuOpen = false }
                }
                Spacer(Modifier.height(d(6)))
                Text("左右键调整，调完立即生效", style = MaterialTheme.typography.labelSmall, color = Paper.Tertiary)
                Spacer(Modifier.height(d(12)))
                Column(
                    Modifier
                        .height(d(430))
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(d(8)),
                ) {
                    DanmakuStepperRow(
                        title = "显示弹幕",
                        description = "关闭后本集与后续剧集都不叠加弹幕",
                        value = if (session.danmakuEnabled) "开启" else "关闭",
                        focusRequester = danmakuRowFocus[0],
                        // 两档开关，左右和确定都给「切一下」，避免按左在关闭态下无反应。
                        onLeft = { session.toggleDanmaku() },
                        onRight = { session.toggleDanmaku() },
                        onUp = { danmakuRowFocus[5].tryRequestFocus() },
                    )
                    DanmakuStepperRow(
                        title = "不透明度",
                        description = "太高会挡住字幕，太低在亮场景看不清",
                        value = "${session.danmakuOpacity}%",
                        onLeft = { session.changeDanmakuOpacity(stepOpacity(session.danmakuOpacity, -5)) },
                        onRight = { session.changeDanmakuOpacity(stepOpacity(session.danmakuOpacity, 5)) },
                    )
                    DanmakuStepperRow(
                        title = "字号",
                        description = "电视距离远，可以适当调大",
                        value = DanmakuPrefs.fontLabel(session.danmakuFont),
                        onLeft = { session.changeDanmakuFont(session.danmakuFont - 1) },
                        onRight = { session.changeDanmakuFont(session.danmakuFont + 1) },
                    )
                    DanmakuStepperRow(
                        title = "颜色",
                        description = "默认保留接口原色，也可以统一成一种颜色",
                        value = danmakuColorName(session.danmakuColor),
                        onLeft = { session.changeDanmakuColor(stepColor(session.danmakuColor, -1)) },
                        onRight = { session.changeDanmakuColor(stepColor(session.danmakuColor, 1)) },
                    )
                    DanmakuStepperRow(
                        title = "移动速度",
                        description = "太快来不及看，太慢会堆在一起",
                        value = DanmakuPrefs.speedLabel(session.danmakuSpeed),
                        onLeft = { session.changeDanmakuSpeed(session.danmakuSpeed - 1) },
                        onRight = { session.changeDanmakuSpeed(session.danmakuSpeed + 1) },
                    )
                    DanmakuStepperRow(
                        title = "展示行数",
                        description = "同时在屏幕上滚动的轨道数量",
                        value = "${session.danmakuRows} 行",
                        focusRequester = danmakuRowFocus[5],
                        onLeft = { session.changeDanmakuRows(session.danmakuRows - 1) },
                        onRight = { session.changeDanmakuRows(session.danmakuRows + 1) },
                    )
                }
                Spacer(Modifier.height(d(14)))
                Row(
                    // 底部按钮向下绕回首行，避免焦点掉到面板背后的播放控制条上。
                    modifier = Modifier.onKeyEvent { event ->
                        if (event.type == androidx.compose.ui.input.key.KeyEventType.KeyDown &&
                            event.key == Key.DirectionDown
                        ) {
                            danmakuRowFocus[0].tryRequestFocus()
                            true
                        } else false
                    },
                    horizontalArrangement = Arrangement.spacedBy(d(10)),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TvButton("恢复默认", { session.resetDanmakuStyle() }, tone = ButtonTone.Quiet)
                    // 屏蔽词与热更新脚本仍留在设置页：那个入口是次要路径，点了会离开播放页。
                    TvButton(
                        "更多设置",
                        {
                            danmakuOpen = false
                            onDanmakuSettings()
                        },
                        tone = ButtonTone.Ghost,
                    )
                }
            }
        }
    }

    if (qualityDialog) {
        ChoiceDialog(
            title = "清晰度",
            choices = PlaybackQuality.options.map { Choice(it.toString(), PlaybackQuality.label(it)) },
            selected = session.quality.toString(),
            onSelect = { key ->
                key.toIntOrNull()?.let(session::changeQuality)
                qualityDialog = false
            },
            onDismiss = { qualityDialog = false },
        )
    }
    if (speedDialog) {
        ChoiceDialog(
            title = "播放速度",
            choices = PlaybackSpeed.options.map { Choice(it.toString(), PlaybackSpeed.label(it)) },
            selected = session.speed.toString(),
            onSelect = { key ->
                key.toFloatOrNull()?.let(session::changeSpeed)
                speedDialog = false
            },
            onDismiss = { speedDialog = false },
        )
    }
    if (frameDialog) {
        ChoiceDialog(
            title = "画面模式",
            choices = VideoFrameMode.entries.map { Choice(it.name, it.label) },
            selected = session.frameMode.name,
            onSelect = { key ->
                session.changeFrameMode(VideoFrameMode.fromStored(key))
                frameDialog = false
            },
            onDismiss = { frameDialog = false },
        )
    }
}

@Composable
private fun CircleAction(icon: ImageVector, description: String, size: androidx.compose.ui.unit.Dp, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(if (focused) Color(0x33FFFFFF) else Color(0x1A000000))
            .border(d(1.2f), if (focused) Rouge.Bright else Color(0x22FFFFFF), CircleShape)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, tint = if (focused) Color.White else Paper.Secondary, modifier = Modifier.size(size * 0.42f))
    }
}

@Composable
private fun ControlChip(label: String, icon: ImageVector, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Row(
        Modifier
            .clip(RoundedCornerShape(d(999)))
            .background(if (focused) Color(0x2EFFFFFF) else Color(0x14FFFFFF))
            .border(d(1.2f), if (focused) Rouge.Bright else Color(0x22FFFFFF), RoundedCornerShape(d(999)))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = d(14), vertical = d(9)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = if (focused) Color.White else Paper.Secondary, modifier = Modifier.size(d(16)))
        Spacer(Modifier.width(d(7)))
        Text(label, style = MaterialTheme.typography.labelSmall, color = if (focused) Color.White else Paper.Secondary, maxLines = 1)
    }
}

@Composable
private fun DanmakuBadgeChip(
    badge: ImageVector,
    badgeTint: Color,
    description: String,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Box(
        Modifier
            .clip(RoundedCornerShape(d(999)))
            .background(if (focused) Color(0x2EFFFFFF) else Color(0x14FFFFFF))
            .border(d(1.2f), if (focused) Rouge.Bright else Color(0x22FFFFFF), RoundedCornerShape(d(999)))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = d(13), vertical = d(9)),
    ) {
        // 与设置页弹幕入口同一个图标（Subtitles），角标贴在主图标右下角。
        Box {
            Icon(
                Icons.Rounded.Subtitles,
                null,
                tint = if (focused) Color.White else Paper.Secondary,
                modifier = Modifier.size(d(18)),
            )
            Box(
                Modifier
                    .offset(x = d(9), y = d(9))
                    .size(d(11))
                    .clip(CircleShape)
                    .background(Color(0xE60A0C12))
                    .border(d(0.8f), Color(0x66FFFFFF), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(badge, description, tint = badgeTint, modifier = Modifier.size(d(8)))
            }
        }
    }
}

/** 弹幕面板的一行：左右键直接调档，确定键等价于「加一档」。 */
@Composable
private fun DanmakuStepperRow(
    title: String,
    description: String,
    value: String,
    focusRequester: FocusRequester? = null,
    onLeft: () -> Unit,
    onRight: () -> Unit,
    onUp: (() -> Unit)? = null,
    onDown: (() -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(d(12)))
            .background(if (focused) Color(0x1FFFFFFF) else Color(0x0AFFFFFF))
            .border(d(1.2f), if (focused) Rouge.Bright else Color(0x12FFFFFF), RoundedCornerShape(d(12)))
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            // onKeyEvent 必须在 focusable 之前：这样它包住焦点节点，方向键先到这里，
            // 被消费后就不会再触发 Compose 的焦点移动。
            .onKeyEvent { event ->
                if (event.type != androidx.compose.ui.input.key.KeyEventType.KeyDown) return@onKeyEvent false
                when (event.key) {
                    Key.DirectionLeft -> {
                        onLeft(); true
                    }
                    Key.DirectionRight, Key.DirectionCenter, Key.Enter -> {
                        onRight(); true
                    }
                    Key.DirectionUp -> {
                        if (onUp == null) false else { onUp(); true }
                    }
                    Key.DirectionDown -> {
                        if (onDown == null) false else { onDown(); true }
                    }
                    else -> false
                }
            }
            .focusable(interactionSource = interaction)
            .padding(horizontal = d(16), vertical = d(12)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = if (focused) Color.White else Paper.Primary,
            )
            Text(
                description,
                style = MaterialTheme.typography.labelSmall,
                color = Paper.Tertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(d(12)))
        Text(
            if (focused) "‹  $value  ›" else value,
            style = MaterialTheme.typography.titleSmall,
            color = if (focused) Rouge.Bright else Paper.Secondary,
            maxLines = 1,
        )
    }
}

private fun stepOpacity(current: Int, delta: Int): Int = (current + delta).coerceIn(25, 100)

private fun stepColor(current: Int, delta: Int): Int = ((current + delta) % 10 + 10) % 10

private fun danmakuColorName(mode: Int): String = when (mode) {
    0 -> "默认"
    1 -> "多彩"
    else -> listOf("白色", "红色", "橙色", "黄色", "绿色", "青色", "蓝色", "紫色")
        .getOrElse(mode - 2) { "默认" }
}

@Composable
private fun PersistentProgress(
    position: Long,
    duration: Long,
    modifier: Modifier = Modifier,
) {
    val progress = (position.toFloat() / duration).coerceIn(0f, 1f)
    Box(
        modifier
            .fillMaxWidth()
            .height(d(2.5f))
            .background(Color(0x2EFFFFFF)),
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(progress)
                .background(Brush.horizontalGradient(listOf(Rouge.Bright, Rouge.Core))),
        )
    }
}

@Composable
private fun SeekBar(
    position: Long,
    duration: Long,
    onSeek: (Long) -> Unit,
    onSeekBy: (Long) -> Unit,
    onInteract: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val progress = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val density = LocalDensity.current
    val barHeightFocused = with(density) { d(6).toPx() }
    val barHeightIdle = with(density) { d(4).toPx() }
    val thumbFocused = with(density) { d(7).toPx() }
    val thumbIdle = with(density) { d(4).toPx() }
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(formatSeconds(position), style = MaterialTheme.typography.labelSmall, color = Paper.Secondary)
        Spacer(Modifier.width(d(14)))
        Box(
            Modifier
                .weight(1f)
                .height(d(26))
                .clip(RoundedCornerShape(99.dp))
                .border(d(1.2f), if (focused) Rouge.Bright.copy(0.7f) else Color.Transparent, RoundedCornerShape(99.dp))
                .clickable(interactionSource = interaction, indication = null) { onInteract() }
                .onKeyEvent { event ->
                    if (event.type != androidx.compose.ui.input.key.KeyEventType.KeyDown) return@onKeyEvent false
                    when (event.nativeKeyEvent.keyCode) {
                        KeyEvent.KEYCODE_DPAD_LEFT -> {
                            onSeekBy(-10_000)
                            true
                        }
                        KeyEvent.KEYCODE_DPAD_RIGHT -> {
                            onSeekBy(10_000)
                            true
                        }
                        else -> false
                    }
                }
                .pointerInput(duration) {
                    detectTapGestures { offset ->
                        if (duration > 0 && size.width > 0) {
                            onSeek((duration * (offset.x / size.width)).toLong())
                        }
                    }
                },
        ) {
            androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                val barHeight = if (focused) barHeightFocused else barHeightIdle
                val centerY = size.height / 2f
                drawRoundRect(
                    color = Color(0x33FFFFFF),
                    topLeft = androidx.compose.ui.geometry.Offset(0f, centerY - barHeight / 2f),
                    size = androidx.compose.ui.geometry.Size(size.width, barHeight),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(barHeight / 2f),
                )
                drawRoundRect(
                    brush = Brush.horizontalGradient(listOf(Rouge.Bright, Rouge.Core)),
                    topLeft = androidx.compose.ui.geometry.Offset(0f, centerY - barHeight / 2f),
                    size = androidx.compose.ui.geometry.Size(size.width * progress, barHeight),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(barHeight / 2f),
                )
                if (focused || progress > 0f) {
                    drawCircle(
                        color = Color.White,
                        radius = if (focused) thumbFocused else thumbIdle,
                        center = androidx.compose.ui.geometry.Offset((size.width * progress).coerceIn(0f, size.width), centerY),
                    )
                }
            }
        }
        Spacer(Modifier.width(d(14)))
        Text(formatSeconds(duration), style = MaterialTheme.typography.labelSmall, color = Paper.Tertiary)
    }
}

@Composable
private fun EpisodeButton(
    index: Int,
    active: Boolean,
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Box(
        Modifier
            // 焦点属性放在 clickable 之前（官方推荐顺序）。
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .height(d(42))
            .clip(RoundedCornerShape(d(9)))
            .background(
                when {
                    active -> Rouge.Core.copy(0.85f)
                    focused -> Color(0x33FFFFFF)
                    else -> Color(0x0FFFFFFF)
                },
            )
            .border(d(1.2f), if (focused) Rouge.Bright else Color(0x14FFFFFF), RoundedCornerShape(d(9)))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            (index + 1).toString(),
            style = MaterialTheme.typography.labelLarge,
            color = if (active || focused) Color.White else Paper.Secondary,
        )
    }
}

@Composable
private fun EpisodeButtonPlaceholder() = Unit
