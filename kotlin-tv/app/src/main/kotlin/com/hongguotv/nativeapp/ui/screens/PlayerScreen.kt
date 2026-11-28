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
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.HighQuality
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Schedule
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
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
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
import androidx.compose.ui.unit.sp
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
import com.hongguotv.nativeapp.ui.player.PlaybackDebugInfo
import com.hongguotv.nativeapp.ui.player.DebugSection
import com.hongguotv.nativeapp.ui.player.DebugRow
import com.hongguotv.nativeapp.ui.player.PlayerSession
import com.hongguotv.nativeapp.ui.theme.Gold
import com.hongguotv.nativeapp.ui.theme.Ink
import com.hongguotv.nativeapp.ui.theme.Paper
import com.hongguotv.nativeapp.ui.theme.Rouge
import com.hongguotv.nativeapp.DanmakuRenderer
import com.hongguotv.nativeapp.DanmakuView
import com.hongguotv.nativeapp.PlaybackExtras
import com.hongguotv.nativeapp.ui.data.RemoteKey
import com.hongguotv.nativeapp.ui.data.SettingsSnapshot
import com.hongguotv.nativeapp.ui.theme.d
import com.hongguotv.nativeapp.ui.theme.ui
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope

@UnstableApi
@Composable
fun PlayerScreen(
    session: PlayerSession,
    settings: SettingsSnapshot,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    clock: String = "",
    /** 启动自动播放的候选剧；非空表示处于「选剧第一层」。手动点剧进来为空，行为与原来一致。 */
    startupCandidates: List<com.hongguotv.core.Series> = emptyList(),
    startupIndex: Int = 0,
    /** 第一层上下键切剧：回调候选列表中的目标索引，由外层拉新剧分集后重建播放器。 */
    onSwitchSeries: (Int) -> Unit = {},
    /** 定时关闭：应用级状态与操作，面板在本页内联弹出。 */
    sleepState: com.hongguotv.nativeapp.ui.data.SleepTimerUiState = com.hongguotv.nativeapp.ui.data.SleepTimerUiState(),
    onSetSleep: (com.hongguotv.nativeapp.ui.data.SleepMode, Int, com.hongguotv.nativeapp.ui.data.SleepAction) -> Unit = { _, _, _ -> },
    onSleepAction: (com.hongguotv.nativeapp.ui.data.SleepAction) -> Unit = {},
    onCancelSleep: () -> Unit = {},
    /** 一集播完推进到下一集时回调（含跨季），驱动「再播 N 集」定时递减。 */
    onEpisodeAdvanced: () -> Unit = {},
) {
    val context = LocalContext.current
    // 第一层：启动选剧模式。进入即自动播放当前剧，画面上只显示简洁的切剧提示 + 操作说明；
    // 确定键进入第二层（完整控制层），不打断播放。手动点剧进来时直接是第二层，行为不变。
    var introLayer by remember { mutableStateOf(startupCandidates.isNotEmpty()) }
    // 进播放页直接出画面：控制层（进度条 + 底部选项）初始隐藏，不给「先闪一层 UI 再收起」的过程。
    // 遥控器按确定随时叫出来；此前是 true，开播瞬间会盖住画面底部一大块。
    var controlsVisible by remember { mutableStateOf(false) }
    var episodesOpen by remember { mutableStateOf(false) }
    var seekHint by remember { mutableStateOf<String?>(null) }
    var hintToken by remember { mutableIntStateOf(0) }
    var hintDuration by remember { mutableLongStateOf(900L) }
    var interaction by remember { mutableIntStateOf(0) }
    var qualityDialog by remember { mutableStateOf(false) }
    var speedDialog by remember { mutableStateOf(false) }
    var frameDialog by remember { mutableStateOf(false) }
    // 弹幕设置：在播放页内联弹出，不再跳去设置页（跳走会把播放会话一起销毁）。
    var danmakuOpen by remember { mutableStateOf(false) }
    // 定时关闭面板：off/on 点击都弹同一个面板。
    var sleepOpen by remember { mutableStateOf(false) }
    val playFocus = remember { FocusRequester() }
    val rootFocus = remember { FocusRequester() }
    val episodeFocus = remember { FocusRequester() }
    val episodeFirstFocus = remember { FocusRequester() }
    // 面板七行各一个 requester：首尾互相绕回，方向键不会跑到面板背后的播放控件上。
    val danmakuRowFocus = remember { List(7) { FocusRequester() } }
    // 弹幕面板底部「恢复默认 / 关闭」两个按钮，与设置行连成闭环焦点链。
    val danmakuResetFocus = remember { FocusRequester() }
    val danmakuCloseFocus = remember { FocusRequester() }
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

    /** 第一层上下键切剧：在候选列表内循环，越过首尾回到另一端。 */
    fun switchStartupSeries(delta: Int) {
        if (startupCandidates.isEmpty()) return
        val next = (startupIndex + delta + startupCandidates.size) % startupCandidates.size
        if (next == startupIndex) return
        onSwitchSeries(next)
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
                // 打开播放设置面板：直接叫出控制层。原先这里还有一层从无入口的
                // menuKeyAction 死分支（永远走 else），已随死字段一并移除。
                revealControls = true
            }
            PlaybackExtras.REMOTE_SLEEP_TIMER -> {
                sleepOpen = true
                // 与清晰度/倍速弹窗一致：面板弹出即收起播放状态栏，焦点由面板自己接管。
                controlsVisible = false
            }
            else -> Unit
        }
        if (revealControls) poke() else interaction++
    }

    // 5 秒无操作自动收起控制层；暂停、缓冲、选集、弹幕/定时面板打开时保持显示。
    // 方向键/确定键在控制层内移动焦点会 interaction++ 重置本计时（见根节点 onPreviewKeyEvent），
    // 用户还在操作时状态栏不会消失，停下来 5 秒才收起。
    LaunchedEffect(interaction, session.playing, session.buffering, episodesOpen, danmakuOpen, sleepOpen) {
        if (!controlsVisible) return@LaunchedEffect
        delay(5000)
        if (session.playing && !session.buffering && !episodesOpen && !danmakuOpen && !sleepOpen) controlsVisible = false
    }
    LaunchedEffect(hintToken) {
        if (hintToken == 0) return@LaunchedEffect
        delay(hintDuration)
        seekHint = null
        hintDuration = 900L
    }
    // 跨季连播：把会话层的切季提示接到统一的 hint 通道显示，随后清掉会话状态。
    val seasonNotice = session.seasonNotice
    LaunchedEffect(seasonNotice) {
        if (seasonNotice != null) {
            seekHint = seasonNotice
            hintToken++
            session.clearSeasonNotice()
        }
    }
    // 自动降低分辨率：降档后仍返回 1080p 时提示一次，停留久一点让用户看清。
    // 提示带所属集号：若消费前已切到别的集，旧提示直接作废，不串台。
    val downgradeNotice = session.downgradeNotice
    val downgradeNoticeIndex = session.downgradeNoticeIndex
    LaunchedEffect(downgradeNotice) {
        if (downgradeNotice != null) {
            if (downgradeNoticeIndex == session.index) {
                seekHint = downgradeNotice
                hintDuration = 3000L
                hintToken++
            }
            session.clearDowngradeNotice()
        }
    }
    // 定时关闭·集数模式：一集推进到下一集（含手动切集、自动连播、跨季重置为第 1 集）
    // 就回调一次。首次组合不回调，避免刚进播放页就把定时集数减掉。
    var lastEpisodeIndex by remember { mutableIntStateOf(session.index) }
    LaunchedEffect(session.index) {
        if (session.index != lastEpisodeIndex) {
            lastEpisodeIndex = session.index
            onEpisodeAdvanced()
        }
    }
    // 焦点跟着画面状态走：控制层可见时落在主播放键上（一进来就能按方向键操作），
    // 隐藏时收回根节点（方向键直接变成快进/快退），选集抽屉打开则进抽屉。
    // 之前焦点永远停在根 Box 上，遥控器看着「完全没反应」，就是缺了这一步。
    // 焦点跟着画面状态走：错误弹窗优先，其次弹幕面板 / 选集抽屉 / 控制层 / 根节点。
    // 控制层可见时落在主播放键上（一进来就能按方向键操作），隐藏时收回根节点
    // （方向键直接变成快进/快退）。之前焦点永远停在根 Box 上，遥控器看着「完全没反应」。
    LaunchedEffect(controlsVisible, episodesOpen, danmakuOpen, sleepOpen, session.error) {
        // 定时面板有自己完整的焦点闭环（进面板时 modeTab.tryRequestFocus），
        // 打开期间本联动不插手，否则会把焦点拉回播放键/根节点，面板里方向键就失灵了。
        // 唯一例外是错误弹窗——那种情况优先级最高，仍要走下面的 error 分支。
        if (sleepOpen && session.error == null) return@LaunchedEffect
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
            sleepOpen -> sleepOpen = false
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
                        // 第一层：确定键进入第二层（显示完整控制层），不影响正在播放的内容。
                        if (introLayer) {
                            introLayer = false
                            poke()
                            true
                        } else if (!controlsVisible) {
                            // 内联面板（弹幕/定时）打开时，确定键交给面板内焦点控件做确认，
                            // 不能被这里 poke 把状态栏叫回来、也不能抢走面板的确认操作。
                            if (sleepOpen || danmakuOpen) return@onPreviewKeyEvent false
                            // 控制层隐藏时把「确定」当成叫出控制层。
                            poke()
                            true
                        } else {
                            // 焦点在控制层内操作：重置自动收起计时，确定键让给焦点控件。
                            interaction++
                            false
                        }
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        if (!controlsVisible) {
                            // 内联面板（弹幕/定时）打开时，方向键必须交给面板内的焦点控件，
                            // 否则会被这里当成快进消费，面板里左右调档就失灵了。
                            if (sleepOpen || danmakuOpen) return@onPreviewKeyEvent false
                            seek(-session.seekStepMillis, "快退 ${session.seekStepMillis / 1000} 秒")
                            true
                        } else {
                            // 焦点还在控制层按钮间移动：重置自动收起计时，避免操作中途状态栏消失。
                            interaction++
                            false
                        }
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        if (!controlsVisible) {
                            if (sleepOpen || danmakuOpen) return@onPreviewKeyEvent false
                            seek(session.seekStepMillis, "快进 ${session.seekStepMillis / 1000} 秒")
                            true
                        } else {
                            interaction++
                            false
                        }
                    }
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        if (!controlsVisible) {
                            if (sleepOpen || danmakuOpen) return@onPreviewKeyEvent false
                            // 第一层：上下键切剧；第二层：沿用原来的遥控动作映射。
                            if (introLayer) switchStartupSeries(-1)
                            else dispatch(session.remoteAction(RemoteKey.UP))
                            true
                        } else {
                            interaction++
                            false
                        }
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        if (!controlsVisible) {
                            if (sleepOpen || danmakuOpen) return@onPreviewKeyEvent false
                            if (introLayer) switchStartupSeries(1)
                            else dispatch(session.remoteAction(RemoteKey.DOWN))
                            true
                        } else {
                            interaction++
                            false
                        }
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
        // 正式渲染器使用独立 SurfaceView；本实验分支可切换为普通 View + 硬件加速 Canvas，
        // 用于对比窗口合成路径的清晰度与资源开销。两种实现共用同一弹幕时钟和排程。
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
        if (session.dualVideoLayers) {
            AndroidView(
                factory = { ctx -> buildVideoLayer(ctx, 1) },
                update = { view -> view.resizeMode = session.frameResizeMode },
                onRelease = { view -> session.detachPlayerView(view, 1) },
                modifier = Modifier.fillMaxSize(),
            )
        }
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

        // 固定使用独立 SurfaceView；帧率设置仅控制隔帧或逐帧绘制。
        // 只覆盖顶部弹幕带，限制窗口失效和绘制范围。
        val danmakuView: android.view.View = remember(session.danmakuFrameRateMode) {
            DanmakuView(context, frameRateMode = session.danmakuFrameRateMode)
        }
        val danmakuRenderer = danmakuView as DanmakuRenderer
        AndroidView(
            factory = { danmakuView },
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.46f)
                .padding(top = d(26)),
        )
        DisposableEffect(danmakuRenderer) {
            session.attachDanmaku(danmakuRenderer)
            onDispose { session.detachDanmaku(danmakuRenderer) }
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

        // ── 第一层（启动选剧）：电影感导览层，覆盖在播放画面上，不抢焦点、不打断播放 ──
        // 进入即自动播放当前剧；上下键在候选间切剧，确定键进入第二层完整控制层，返回键回首页。
        AnimatedVisibility(
            visible = introLayer && !controlsVisible && session.error == null,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Box(Modifier.fillMaxSize()) {
                // 左侧横向渐变：只压暗文字区、把画面让给右侧，贴片感而非整屏黑罩。
                Box(
                    Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(0.68f)
                        .background(
                            Brush.horizontalGradient(
                                listOf(
                                    Color(0xE6000000),
                                    Color(0xA6000000),
                                    Color(0x3D000000),
                                    Color.Transparent,
                                ),
                            ),
                        ),
                )

                // 主信息：垂直居中偏上，海报 hero 式排版。
                Column(
                    Modifier
                        .align(Alignment.CenterStart)
                        .fillMaxWidth(0.62f)
                        .padding(start = ui.gutter),
                ) {
                    // 品牌行：金线 + 开机精选。
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(width = d(28), height = d(2))
                                .background(Gold.Core),
                        )
                        Spacer(Modifier.width(d(10)))
                        Text(
                            "开机精选",
                            style = MaterialTheme.typography.labelMedium,
                            color = Gold.Bright,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Spacer(Modifier.height(d(16)))
                    // 剧名：全站最大的字阶，收字距，最多两行。
                    Text(
                        session.series.title,
                        style = MaterialTheme.typography.displaySmall,
                        color = Paper.Pure,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(d(14)))
                    // 元信息：全 N 集胭脂红胶囊 + 标签，缺项自动隐去，不留空档。
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val episodeCount = startupCandidates.getOrNull(startupIndex)?.episodes?.size ?: 0
                        if (episodeCount > 0) {
                            Text(
                                "全 $episodeCount 集",
                                style = MaterialTheme.typography.labelSmall,
                                color = Paper.Pure,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(d(6)))
                                    .background(Rouge.Core)
                                    .padding(horizontal = d(10), vertical = d(4)),
                            )
                        }
                        val tags = session.series.tags.trim()
                        if (tags.isNotBlank()) {
                            if (episodeCount > 0) Spacer(Modifier.width(d(12)))
                            Text(
                                tags,
                                style = MaterialTheme.typography.bodySmall,
                                color = Paper.Secondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    // 简介：克制两行，给画面留呼吸。
                    val synopsis = session.series.description.trim()
                    if (synopsis.isNotBlank()) {
                        Spacer(Modifier.height(d(12)))
                        Text(
                            synopsis,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Paper.Secondary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    // 候选导览：金色进度轨道 + 序号，比纯文字更直观。
                    if (startupCandidates.size > 1) {
                        Spacer(Modifier.height(d(26)))
                        StartupProgressRail(startupCandidates.size, startupIndex)
                    }
                }

                // 底部操作说明：玻璃质感按键条，确定键用金色强调。
                Row(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = d(40), start = ui.gutter, end = ui.gutter)
                        .clip(RoundedCornerShape(d(18)))
                        .background(Color(0x9910141C))
                        .border(d(1), Color(0x33E7BE7C), RoundedCornerShape(d(18)))
                        .padding(horizontal = d(22), vertical = d(14)),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StartupKeyHint("▲ ▼", "切换剧目")
                    Spacer(Modifier.width(d(18)))
                    Box(Modifier.width(d(1)).height(d(18)).background(Color(0x2EFFFFFF)))
                    Spacer(Modifier.width(d(18)))
                    StartupKeyHint("确定", "进入播放", accent = true)
                    Spacer(Modifier.width(d(18)))
                    Box(Modifier.width(d(1)).height(d(18)).background(Color(0x2EFFFFFF)))
                    Spacer(Modifier.width(d(18)))
                    StartupKeyHint("返回", "回到首页")
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
                                    // 与清晰度/倍速弹窗一致：面板弹出即收起播放状态栏，
                                    // 焦点由 danmakuOpen 联动落到面板行，不会丢。
                                    controlsVisible = false
                                },
                            )
                            SleepBadgeChip(
                                on = sleepState.badgeOn,
                                description = if (sleepState.badgeOn) "定时已开启，${sleepState.summaryText}" else "定时关闭",
                            ) {
                                sleepOpen = true
                                // 与清晰度/倍速弹窗一致：面板弹出即收起播放状态栏，
                                // 定时面板有自己的焦点闭环（modeTab 请求焦点），不会丢。
                                controlsVisible = false
                            }
                            ControlChip("选集", Icons.AutoMirrored.Rounded.PlaylistPlay) { episodesOpen = true }
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
                            Icon(Icons.AutoMirrored.Rounded.PlaylistPlay, null, tint = Paper.Secondary, modifier = Modifier.size(d(18)))
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

        // 右上角一行：播放信息 · 实时网速 · 时钟，常驻、不随控制层显隐。
        // 无框无金、低饱和灰白 + 细投影，各段用细竖线分隔，时间固定在最右。
        // 调试模式（播放信息第 4 档）时整行让位给左侧诊断面板，避免信息重复。
        val debugMode = settings.videoInfoMode >= 3
        val showInfo = !debugMode && settings.videoInfoMode > 0 && session.ready
        val showSpeed = !debugMode && settings.showNetworkSpeed
        val showClockThis = settings.showClock && clock.isNotBlank()
        // 定时关闭：只在临界时显示倒计时（分钟<=60s 逐秒、集数<=3 逐集），平时不常驻。
        val showSleep = sleepState.active && sleepState.countdownText.isNotBlank()
        if (showInfo || showSpeed || showClockThis || showSleep) {
            Row(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = d(30), end = ui.gutter),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(d(10)),
            ) {
                if (showInfo) {
                    Text(
                        buildString {
                            if (session.videoWidth > 0) append("${session.videoWidth}×${session.videoHeight}")
                            if (session.qualityLabel.isNotBlank()) {
                                if (isNotEmpty()) append(" · ")
                                append(session.qualityLabel)
                            }
                            if (settings.videoInfoMode >= 2) {
                                if (session.videoBitrate > 0) append(" · ${"%.1f".format(session.videoBitrate / 1_000_000f)} Mbps")
                                if (session.videoFrameRate > 0f) append(" · ${"%.0f".format(session.videoFrameRate)} fps")
                            }
                        },
                        style = MaterialTheme.typography.labelMedium.copy(
                            shadow = Shadow(color = Color(0x99000000), blurRadius = with(LocalDensity.current) { d(5).toPx() }),
                        ),
                        color = Color(0xD9FFFFFF),
                    )
                }
                if (showInfo && showSpeed) TopInfoDivider()
                if (showSpeed) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Rounded.Download,
                            contentDescription = null,
                            tint = Color(0xD9FFFFFF),
                            modifier = Modifier.size(d(14)),
                        )
                        Spacer(Modifier.width(d(4)))
                        Text(
                            "${"%.1f".format(session.networkMbps)} Mbps",
                            style = MaterialTheme.typography.labelMedium.copy(
                                shadow = Shadow(color = Color(0x99000000), blurRadius = with(LocalDensity.current) { d(5).toPx() }),
                            ),
                            color = Color(0xD9FFFFFF),
                        )
                    }
                }
                if (showSleep) {
                    if (showInfo || showSpeed) TopInfoDivider()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Rounded.Schedule,
                            contentDescription = null,
                            tint = Color(0xFF43A047),
                            modifier = Modifier.size(d(14)),
                        )
                        Spacer(Modifier.width(d(4)))
                        Text(
                            sleepState.countdownText,
                            style = MaterialTheme.typography.labelMedium.copy(
                                shadow = Shadow(color = Color(0x99000000), blurRadius = with(LocalDensity.current) { d(5).toPx() }),
                            ),
                            color = Color(0xFFFF6B7E),
                        )
                    }
                }
                if ((showInfo || showSpeed || showSleep) && showClockThis) TopInfoDivider()
                if (showClockThis) {
                    Text(
                        clock,
                        style = MaterialTheme.typography.titleMedium.copy(
                            shadow = Shadow(color = Color(0x99000000), blurRadius = with(LocalDensity.current) { d(6).toPx() }),
                        ),
                        color = Paper.Secondary,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }

        // 调试模式：视频左侧叠一屏诊断面板，带半透明背景、所有信息一屏展示，不滚动、不抢焦点。
        if (debugMode && session.error == null) {
            DebugOverlayPanel(
                info = session.debugInfo,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = ui.gutter, top = d(26)),
            )
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
                        .height(d(500))
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(d(8)),
                ) {
                    DanmakuStepperRow(
                        title = "弹幕帧率",
                        description = "越高越流畅，性能消耗也越大",
                        value = danmakuFrameRateLabel(session.danmakuFrameRateMode),
                        focusRequester = danmakuRowFocus[0],
                        // 三档循环，左右和确定都切一档，避免到端点时按键无反应。
                        onLeft = { session.changeDanmakuFrameRateMode((session.danmakuFrameRateMode + 2) % 3) },
                        onRight = { session.changeDanmakuFrameRateMode((session.danmakuFrameRateMode + 1) % 3) },
                        // 首行向上绕到底部「关闭」按钮，与底部两按钮连成闭环。
                        onUp = { danmakuCloseFocus.tryRequestFocus() },
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
                        onDown = { danmakuRowFocus[6].tryRequestFocus() },
                    )
                    DanmakuStepperRow(
                        title = "合并重复弹幕",
                        description = "十秒内重复的字幕只显示一条",
                        value = if (session.danmakuMergeRepeat) "开启" else "关闭",
                        focusRequester = danmakuRowFocus[6],
                        onLeft = { session.changeDanmakuMergeRepeat(!session.danmakuMergeRepeat) },
                        onRight = { session.changeDanmakuMergeRepeat(!session.danmakuMergeRepeat) },
                        onUp = { danmakuRowFocus[5].tryRequestFocus() },
                        // 末行向下落到「恢复默认」，不再直接绕回首行。
                        onDown = { danmakuResetFocus.tryRequestFocus() },
                    )
                }
                Spacer(Modifier.height(d(16)))
                // 底部两个按钮并排居中，不再孤零零一个「恢复默认」。
                // 焦点链：末行↓恢复默认↓关闭↓首行，首行↑关闭，形成闭环，方向键不会掉到面板外。
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TvButton(
                        "恢复默认",
                        { session.resetDanmakuStyle() },
                        tone = ButtonTone.Ghost,
                        modifier = Modifier.focusProperties { right = danmakuCloseFocus },
                        focusRequester = danmakuResetFocus,
                        upFocus = danmakuRowFocus[6],
                        downFocus = danmakuCloseFocus,
                    )
                    Spacer(Modifier.width(d(18)))
                    TvButton(
                        "关闭",
                        { danmakuOpen = false },
                        tone = ButtonTone.Ghost,
                        modifier = Modifier.focusProperties { left = danmakuResetFocus },
                        focusRequester = danmakuCloseFocus,
                        upFocus = danmakuResetFocus,
                        downFocus = danmakuRowFocus[0],
                    )
                }
            }
        }
    }

    if (sleepOpen) {
        SleepTimerPanel(
            state = sleepState,
            onSetSleep = onSetSleep,
            onSleepAction = onSleepAction,
            onCancelSleep = onCancelSleep,
            onDismiss = { sleepOpen = false },
        )
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

/** 右上角状态行的段间细竖线。 */
@Composable
private fun TopInfoDivider() {
    Box(
        Modifier
            .width(d(1))
            .height(d(12))
            .background(Color(0x44FFFFFF)),
    )
}

/**
 * 调试模式诊断面板：视频左侧一屏展示所有播放状态，带半透明深色背景 + 细金边。
 * 分组标题金色小字，信息逐行列对齐；不滚动、不聚焦，不干扰遥控器操作。
 */
@Composable
private fun DebugOverlayPanel(info: PlaybackDebugInfo, modifier: Modifier = Modifier) {
    Column(
        modifier
            .width(d(320))
            .clip(RoundedCornerShape(d(12)))
            .background(Color(0xD90B0E14))
            .border(d(1), Color(0x33E7BE7C), RoundedCornerShape(d(12)))
            .padding(horizontal = d(16), vertical = d(14)),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(d(7)).clip(RoundedCornerShape(d(2))).background(Rouge.Bright))
            Spacer(Modifier.width(d(8)))
            Text(
                "播放诊断",
                style = MaterialTheme.typography.titleSmall,
                color = Gold.Bright,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.weight(1f))
            Text(
                info.updatedAt,
                style = MaterialTheme.typography.labelSmall,
                color = Paper.Disabled,
            )
        }
        Spacer(Modifier.height(d(6)))
        Box(Modifier.fillMaxWidth().height(d(1)).background(Color(0x26FFFFFF)))
        info.sections.forEachIndexed { index, section ->
            Spacer(Modifier.height(d(9)))
            Text(
                section.title,
                style = MaterialTheme.typography.labelSmall,
                color = Gold.Core,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(d(3)))
            section.rows.forEach { row -> DebugInfoRow(row) }
            if (index != info.sections.lastIndex) {
                Spacer(Modifier.height(d(5)))
                Box(Modifier.fillMaxWidth().height(d(1)).background(Color(0x14FFFFFF)))
            }
        }
    }
}

/** 诊断面板里的一行：左侧标签固定宽，右侧值右对齐、单行截断。 */
@Composable
private fun DebugInfoRow(row: DebugRow) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = d(1)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            row.label,
            style = MaterialTheme.typography.labelSmall,
            color = Paper.Secondary,
            modifier = Modifier.width(d(74)),
        )
        Text(
            row.value,
            style = MaterialTheme.typography.labelSmall,
            color = Paper.Pure,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
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

/**
 * 定时关闭状态按钮：单个定时器图标（恒定灰白，不随开关变色），右下角挂 OFF/ON 文字角标。
 * ON 用弹幕绿勾同款绿，OFF 用弹幕关勾同款灰；两种状态点击行为一致，都弹定时面板。
 */
@Composable
private fun SleepBadgeChip(on: Boolean, description: String, onClick: () -> Unit) {
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
        Box {
            Icon(
                Icons.Rounded.Schedule,
                null,
                tint = if (focused) Color.White else Paper.Secondary,
                modifier = Modifier.size(d(18)),
            )
            Box(
                Modifier
                    .offset(x = d(9), y = d(9))
                    .height(d(12))
                    .clip(RoundedCornerShape(d(4)))
                    .background(Color(0xE60A0C12))
                    .border(d(0.7f), Color(0x66FFFFFF), RoundedCornerShape(d(4)))
                    .padding(horizontal = d(2.5f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (on) "ON" else "OFF",
                    color = if (on) Color(0xFF43A047) else Color(0xFF9AA0AA),
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 6.sp, fontWeight = FontWeight.Bold),
                )
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

/**
 * 定时关闭面板。
 * 01 计时方式：分钟 / 集数两个分段，左右键切换（互斥，切到哪段就激活哪段），每模式记住各自的值。
 * 大号数字左右键单步，长按 320ms 后加速连调；范围分钟 10..999、集数 1..999。
 * 02 到点后：暂停 / 退出两档。底部显示当前状态并可一键关闭。
 * 焦点链：模式段 → 数字 → 动作 → 关闭按钮 → 回到模式段，方向键不会跑到面板背后。
 */
@Composable
private fun SleepTimerPanel(
    state: com.hongguotv.nativeapp.ui.data.SleepTimerUiState,
    onSetSleep: (com.hongguotv.nativeapp.ui.data.SleepMode, Int, com.hongguotv.nativeapp.ui.data.SleepAction) -> Unit,
    onSleepAction: (com.hongguotv.nativeapp.ui.data.SleepAction) -> Unit,
    onCancelSleep: () -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val modeTab = remember { FocusRequester() }
    val numberRow = remember { FocusRequester() }
    val actionTab = remember { FocusRequester() }
    val closeBtn = remember { FocusRequester() }

    // 面板本地记住两个模式各自调到的值，切回来时接着用；底层 timer 始终只有一种模式生效。
    var minutesDraft = remember { mutableIntStateOf(if (state.mode == com.hongguotv.nativeapp.ui.data.SleepMode.MINUTES && state.active) state.value.coerceIn(10, 999) else 30) }
    var episodesDraft = remember { mutableIntStateOf(if (state.mode == com.hongguotv.nativeapp.ui.data.SleepMode.EPISODES && state.active) state.value.coerceIn(1, 999) else 3) }
    var selectedMode = remember {
        mutableStateOf(
            when (state.mode) {
                com.hongguotv.nativeapp.ui.data.SleepMode.EPISODES -> com.hongguotv.nativeapp.ui.data.SleepMode.EPISODES
                else -> com.hongguotv.nativeapp.ui.data.SleepMode.MINUTES
            }
        )
    }

    // 长按加速：按下先走一步，320ms 后每 40ms 一跳并逐渐提速；松开或焦点移走即停止。
    // TV 遥控长按会持续发 repeat KeyDown，用 holdingDir 拦住，避免反复重启协程。
    var holdJob by remember { mutableStateOf<Job?>(null) }
    var holdingDir by remember { mutableIntStateOf(0) }
    fun stopHold() { holdJob?.cancel(); holdJob = null; holdingDir = 0 }
    DisposableEffect(Unit) { onDispose { holdJob?.cancel() } }

    fun step(delta: Int) {
        if (selectedMode.value == com.hongguotv.nativeapp.ui.data.SleepMode.EPISODES) {
            val next = episodesDraft.intValue + delta
            if (next !in 1..999) return
            episodesDraft.intValue = next
            onSetSleep(com.hongguotv.nativeapp.ui.data.SleepMode.EPISODES, next, state.action)
        } else {
            val next = minutesDraft.intValue + delta * 10
            if (next !in 10..999) return
            minutesDraft.intValue = next
            onSetSleep(com.hongguotv.nativeapp.ui.data.SleepMode.MINUTES, next, state.action)
        }
    }

    fun startHold(delta: Int) {
        if (holdingDir == delta) return // repeat KeyDown，加速已在跑，忽略
        stopHold()
        holdingDir = delta
        step(delta)
        holdJob = scope.launch {
            delay(320)
            var gap = 40L
            while (isActive) {
                step(delta)
                delay(gap)
                if (gap > 16L) gap -= 2L
            }
        }
    }

    fun selectMode(mode: com.hongguotv.nativeapp.ui.data.SleepMode) {
        if (selectedMode.value == mode) return
        stopHold()
        selectedMode.value = mode
        val value = if (mode == com.hongguotv.nativeapp.ui.data.SleepMode.EPISODES) episodesDraft.intValue else minutesDraft.intValue
        onSetSleep(mode, value, state.action)
    }

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0x99000000))
                .pointerInput(Unit) { detectTapGestures(onTap = { onDismiss() }) },
        )
        Column(
            Modifier
                .align(Alignment.Center)
                .width(d(560))
                .clip(RoundedCornerShape(d(22)))
                .background(Color(0xF20B0E14))
                .border(d(1f), Color(0x1FFFFFFF), RoundedCornerShape(d(22)))
                // 面板内部点击自己吃掉，不穿透到底层遮罩（否则手动点一下面板就关了）。
                .pointerInput(Unit) { detectTapGestures(onTap = {}) }
                .padding(d(22)),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Schedule, null, tint = Rouge.Bright, modifier = Modifier.size(d(20)))
                Spacer(Modifier.width(d(10)))
                Text("定时关闭", style = MaterialTheme.typography.headlineSmall, color = Paper.Pure, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                CircleAction(Icons.Rounded.Close, "关闭", d(38), onDismiss)
            }
            Spacer(Modifier.height(d(6)))
            Text("左右键切模式 / 调数值 · 长按加速 · 立即生效", style = MaterialTheme.typography.labelSmall, color = Paper.Tertiary)
            Spacer(Modifier.height(d(16)))

            Text("01  计时方式", style = MaterialTheme.typography.labelSmall, color = Gold.Bright)
            Spacer(Modifier.height(d(8)))
            // 分段 tab：左右键在「分钟 / 集数」间切换，互斥激活。
            val modeInteraction = remember { MutableInteractionSource() }
            val modeFocused by modeInteraction.collectIsFocusedAsState()
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(d(12)))
                    .background(if (modeFocused) Color(0x1FFFFFFF) else Color(0x0AFFFFFF))
                    .border(d(1.2f), if (modeFocused) Rouge.Bright else Color(0x12FFFFFF), RoundedCornerShape(d(12)))
                    .onKeyEvent { event ->
                        if (event.type != androidx.compose.ui.input.key.KeyEventType.KeyDown) return@onKeyEvent false
                        when (event.key) {
                            Key.DirectionLeft -> { selectMode(com.hongguotv.nativeapp.ui.data.SleepMode.MINUTES); true }
                            Key.DirectionRight -> { selectMode(com.hongguotv.nativeapp.ui.data.SleepMode.EPISODES); true }
                            Key.DirectionDown -> { numberRow.tryRequestFocus(); true }
                            Key.DirectionUp -> { closeBtn.tryRequestFocus(); true }
                            else -> false
                        }
                    }
                    .focusRequester(modeTab)
                    .focusable(interactionSource = modeInteraction)
                    .padding(d(4)),
                horizontalArrangement = Arrangement.spacedBy(d(4)),
            ) {
                listOf(com.hongguotv.nativeapp.ui.data.SleepMode.MINUTES to "分钟", com.hongguotv.nativeapp.ui.data.SleepMode.EPISODES to "集数").forEach { (mode, label) ->
                    val on = selectedMode.value == mode
                    Box(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(d(9)))
                            .background(if (on) Rouge.Bright else Color.Transparent)
                            // 触摸点击也能切模式；不新增焦点节点，遥控器焦点链不变。
                            .pointerInput(mode) { detectTapGestures(onTap = { selectMode(mode) }) }
                            .padding(vertical = d(9)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            label,
                            style = MaterialTheme.typography.titleSmall,
                            color = if (on) Color.White else Paper.Secondary,
                            fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    }
                }
            }
            Spacer(Modifier.height(d(12)))

            // 大号数字：左右键单步，长按加速。
            val numInteraction = remember { MutableInteractionSource() }
            val numFocused by numInteraction.collectIsFocusedAsState()
            val isEpisodes = selectedMode.value == com.hongguotv.nativeapp.ui.data.SleepMode.EPISODES
            val bigValue = if (isEpisodes) episodesDraft.intValue else minutesDraft.intValue
            val unit = if (isEpisodes) "集" else "分钟"
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(d(14)))
                    .background(if (numFocused) Color(0x1FFFFFFF) else Color(0x0AFFFFFF))
                    .border(d(1.2f), if (numFocused) Rouge.Bright else Color(0x12FFFFFF), RoundedCornerShape(d(14)))
                    .onKeyEvent { event ->
                        when (event.type) {
                            androidx.compose.ui.input.key.KeyEventType.KeyDown -> when (event.key) {
                                Key.DirectionLeft -> { startHold(-1); true }
                                Key.DirectionRight -> { startHold(1); true }
                                Key.DirectionUp -> { stopHold(); modeTab.tryRequestFocus(); true }
                                Key.DirectionDown -> { stopHold(); actionTab.tryRequestFocus(); true }
                                else -> false
                            }
                            androidx.compose.ui.input.key.KeyEventType.KeyUp -> {
                                if (event.key == Key.DirectionLeft || event.key == Key.DirectionRight) { stopHold(); true } else false
                            }
                            else -> false
                        }
                    }
                    .focusRequester(numberRow)
                    .focusable(interactionSource = numInteraction),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Text(
                    "‹",
                    style = MaterialTheme.typography.titleLarge,
                    color = if (numFocused) Rouge.Bright else Paper.Tertiary,
                    modifier = Modifier.pointerInput(Unit) { detectTapGestures(onTap = { stopHold(); step(-1) }) },
                )
                Spacer(Modifier.width(d(20)))
                Text(
                    "$bigValue",
                    style = MaterialTheme.typography.displayMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = if (numFocused) Color.White else Paper.Primary,
                )
                Spacer(Modifier.width(d(6)))
                Text(unit, style = MaterialTheme.typography.titleMedium, color = Paper.Secondary)
                Spacer(Modifier.width(d(20)))
                Text(
                    "›",
                    style = MaterialTheme.typography.titleLarge,
                    color = if (numFocused) Rouge.Bright else Paper.Tertiary,
                    modifier = Modifier.pointerInput(Unit) { detectTapGestures(onTap = { stopHold(); step(1) }) },
                )
            }
            Spacer(Modifier.height(d(4)))
            Text(
                if (isEpisodes) "每次 1 集 · 范围 1 – 999" else "每次 10 分钟 · 范围 10 – 999",
                style = MaterialTheme.typography.labelSmall,
                color = Paper.Tertiary,
                modifier = Modifier.fillMaxWidth(),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            Spacer(Modifier.height(d(16)))

            Text("02  到点后", style = MaterialTheme.typography.labelSmall, color = Gold.Bright)
            Spacer(Modifier.height(d(8)))
            val exit = state.action == com.hongguotv.nativeapp.ui.data.SleepAction.EXIT
            val actInteraction = remember { MutableInteractionSource() }
            val actFocused by actInteraction.collectIsFocusedAsState()
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(d(12)))
                    .background(if (actFocused) Color(0x1FFFFFFF) else Color(0x0AFFFFFF))
                    .border(d(1.2f), if (actFocused) Rouge.Bright else Color(0x12FFFFFF), RoundedCornerShape(d(12)))
                    .onKeyEvent { event ->
                        if (event.type != androidx.compose.ui.input.key.KeyEventType.KeyDown) return@onKeyEvent false
                        when (event.key) {
                            Key.DirectionLeft -> { onSleepAction(com.hongguotv.nativeapp.ui.data.SleepAction.PAUSE); true }
                            Key.DirectionRight -> { onSleepAction(com.hongguotv.nativeapp.ui.data.SleepAction.EXIT); true }
                            Key.DirectionUp -> { numberRow.tryRequestFocus(); true }
                            Key.DirectionDown -> { closeBtn.tryRequestFocus(); true }
                            else -> false
                        }
                    }
                    .focusRequester(actionTab)
                    .focusable(interactionSource = actInteraction)
                    .padding(d(4)),
                horizontalArrangement = Arrangement.spacedBy(d(4)),
            ) {
                listOf(false to "暂停播放", true to "退出应用").forEach { (isExit, label) ->
                    val on = exit == isExit
                    Box(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(d(9)))
                            .background(if (on) Rouge.Bright else Color.Transparent)
                            // 触摸点击也能切「到点后」动作；不新增焦点节点。
                            .pointerInput(isExit) {
                                detectTapGestures(onTap = {
                                    onSleepAction(if (isExit) com.hongguotv.nativeapp.ui.data.SleepAction.EXIT else com.hongguotv.nativeapp.ui.data.SleepAction.PAUSE)
                                })
                            }
                            .padding(vertical = d(9)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            label,
                            style = MaterialTheme.typography.titleSmall,
                            color = if (on) Color.White else Paper.Secondary,
                            fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    }
                }
            }
            Spacer(Modifier.height(d(18)))

            Row(
                Modifier
                    .fillMaxWidth()
                    .border(d(1f), Color(0x18FFFFFF), RoundedCornerShape(d(12)))
                    .padding(horizontal = d(14), vertical = d(10)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("当前", style = MaterialTheme.typography.labelSmall, color = Paper.Tertiary)
                Spacer(Modifier.width(d(10)))
                Text(
                    if (state.active) state.summaryText else "未设置",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (state.active) Color(0xFF43A047) else Paper.Secondary,
                )
                Spacer(Modifier.weight(1f))
                Box(Modifier.focusRequester(closeBtn)) {
                    TvButton("关闭定时", { onCancelSleep(); onDismiss() }, tone = ButtonTone.Quiet, enabled = state.active)
                }
            }
        }
    }
    LaunchedEffect(Unit) { modeTab.tryRequestFocus() }
}

private fun stepOpacity(current: Int, delta: Int): Int = (current + delta).coerceIn(25, 100)

private fun stepColor(current: Int, delta: Int): Int = ((current + delta) % 10 + 10) % 10

private fun danmakuColorName(mode: Int): String = when (mode) {
    0 -> "默认"
    1 -> "多彩"
    else -> listOf("白色", "红色", "橙色", "黄色", "绿色", "青色", "蓝色", "紫色")
        .getOrElse(mode - 2) { "默认" }
}

/** 与设置页「弹幕帧率」同一套编号：0=标准、1=高帧率、2=超帧率。 */
private fun danmakuFrameRateLabel(mode: Int): String =
    listOf("标准", "高帧率", "超帧率").getOrElse(mode.coerceIn(0, 2)) { "标准" }

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
            .height(d(1.5f))
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

/** 第一层操作说明里的单个按键提示：键帽 + 说明文字；确定键用金色强调。 */
@Composable
private fun StartupKeyHint(key: String, label: String, accent: Boolean = false) {
    val keyColor = if (accent) Gold.Bright else Paper.Pure
    val capBg = if (accent) Color(0x33E7BE7C) else Ink.SurfaceTop
    val capBorder = if (accent) Gold.Core else Ink.Line
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .clip(RoundedCornerShape(d(8)))
                .background(capBg)
                .border(d(1), capBorder, RoundedCornerShape(d(8)))
                .padding(horizontal = d(12), vertical = d(6)),
        ) {
            Text(
                key,
                style = MaterialTheme.typography.labelSmall,
                color = keyColor,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Spacer(Modifier.width(d(8)))
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = if (accent) Paper.Primary else Paper.Secondary,
        )
    }
}

/** 第一层候选导览：以当前剧为窗口展示最多 9 个刻度，居中项金色高亮并放大。 */
@Composable
private fun StartupProgressRail(count: Int, current: Int) {
    if (count <= 0) return
    val window = 9.coerceAtMost(count)
    val start = (current - window / 2).coerceIn(0, count - window)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "${current + 1}",
            style = MaterialTheme.typography.titleMedium,
            color = Gold.Bright,
            fontWeight = FontWeight.Bold,
        )
        Text(
            " / $count",
            style = MaterialTheme.typography.bodySmall,
            color = Paper.Tertiary,
        )
        Spacer(Modifier.width(d(18)))
        Row(verticalAlignment = Alignment.CenterVertically) {
            for (slot in 0 until window) {
                val index = start + slot
                val active = index == current
                Box(
                    Modifier
                        .width(if (active) d(22) else d(10))
                        .height(if (active) d(5) else d(4))
                        .clip(RoundedCornerShape(d(3)))
                        .background(if (active) Gold.Bright else Color(0x59FFFFFF)),
                )
                if (slot < window - 1) Spacer(Modifier.width(d(6)))
            }
        }
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
