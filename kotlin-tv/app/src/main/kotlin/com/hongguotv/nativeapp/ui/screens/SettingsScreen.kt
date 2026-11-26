// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp.ui.screens

import android.content.Context
import android.content.ClipData
import android.content.ClipboardManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Gamepad
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.hongguotv.nativeapp.ui.components.tryRequestFocus
import com.hongguotv.core.DanmakuPrefs
import com.hongguotv.core.DnsProvider
import com.hongguotv.core.DnsSettings
import com.hongguotv.nativeapp.R
import com.hongguotv.core.PlaybackQuality
import com.hongguotv.core.PlaybackSpeed
import com.hongguotv.core.VideoFrameMode
import com.hongguotv.nativeapp.PlaybackExtras
import com.hongguotv.nativeapp.ui.components.AppDialog
import com.hongguotv.nativeapp.ui.components.ButtonTone
import com.hongguotv.nativeapp.ui.components.Choice
import com.hongguotv.nativeapp.ui.components.ChoiceDialog
import com.hongguotv.nativeapp.ui.components.TvButton
import com.hongguotv.nativeapp.ui.data.ApiStatusState
import com.hongguotv.nativeapp.ui.data.AppViewModel
import com.hongguotv.nativeapp.ui.data.RemoteKey
import com.hongguotv.nativeapp.ui.data.ScriptUpdateState
import com.hongguotv.nativeapp.ui.data.SettingsSnapshot
import com.hongguotv.nativeapp.ui.data.videoInfoLabel
import com.hongguotv.nativeapp.ui.player.PlaybackDiagnostics
import com.hongguotv.nativeapp.ui.theme.Gold
import com.hongguotv.nativeapp.ui.theme.Ink
import com.hongguotv.nativeapp.ui.theme.Paper
import com.hongguotv.nativeapp.ui.theme.Rouge
import com.hongguotv.nativeapp.ui.theme.d
import com.hongguotv.nativeapp.ui.theme.ui
import androidx.lifecycle.compose.collectAsStateWithLifecycle

private enum class SettingsSection(val label: String, val icon: ImageVector) {
    PLAYBACK("播放", Icons.Rounded.PlayCircle),
    DANMAKU("弹幕", Icons.Rounded.Subtitles),
    APPEARANCE("画面", Icons.Rounded.Palette),
    CONTROL("按键", Icons.Rounded.Gamepad),
    NETWORK("网络", Icons.Rounded.Wifi),
    DATA("数据", Icons.Rounded.Storage),
    ABOUT("关于", Icons.Rounded.Info),
}

@Composable
fun SettingsScreen(
    vm: AppViewModel,
    snapshot: SettingsSnapshot,
    versionName: String,
    modifier: Modifier = Modifier,
    onApiStatus: () -> Unit = {},
    /** 从播放页「弹幕设置」进来时直接落到弹幕栏；消费后由调用方清空。 */
    initialSection: String? = null,
    onInitialSectionConsumed: () -> Unit = {},
    /** 顶栏「设置」按「下」进入时的落点：分类导轨的当前项。 */
    entryFocus: FocusRequester? = null,
    /** 分类导轨最上面一项按「上」回到顶栏导航项。 */
    upFocus: FocusRequester? = null,
) {
    var section by remember { mutableStateOf(SettingsSection.PLAYBACK) }
    LaunchedEffect(initialSection) {
        if (!initialSection.isNullOrBlank()) {
            section = SettingsSection.entries.firstOrNull { it.name.equals(initialSection, ignoreCase = true) }
                ?: SettingsSection.PLAYBACK
            onInitialSectionConsumed()
        }
    }
    val context = LocalContext.current
    var showPlaybackDiagnostics by remember { mutableStateOf(false) }
    var diagnosticsText by remember { mutableStateOf("") }
    val apiStatus by vm.apiStatus.collectAsStateWithLifecycle()
    val scriptUpdate by vm.scriptUpdate.collectAsStateWithLifecycle()
    // 冷启动那次检测被系统打断时，进入网络栏补一次。
    LaunchedEffect(section) {
        if (section == SettingsSection.NETWORK && !apiStatus.running && apiStatus.okCount == 0 && apiStatus.failedCount == 0) {
            vm.refreshApiStatus()
        }
    }
    var choice by remember { mutableStateOf<ChoiceRequest?>(null) }
    var confirm by remember { mutableStateOf<ConfirmRequest?>(null) }
    var blockEditor by remember { mutableStateOf(false) }
    var iconPicker by remember { mutableStateOf(false) }
    var customDns by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    // 右侧内容区焦点锚点：分类导轨按「右/下」时精确落到内容区首个可聚焦项；
    // 聚焦组内请求焦点会自动交给组内第一个可聚焦子项。
    val contentFocus = remember { FocusRequester() }
    val sections = SettingsSection.entries

    Row(modifier.fillMaxSize()) {
        // 左侧分类导轨（普通 Column：上下键在导轨内自然遍历）
        Column(
            Modifier
                .width(d(240))
                .fillMaxHeight()
                .padding(start = ui.gutter, top = d(96), bottom = d(40)),
            verticalArrangement = Arrangement.spacedBy(d(6)),
        ) {
            Text("设置", style = MaterialTheme.typography.displaySmall, color = Paper.Pure, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(d(4)))
            Text("偏好会保存在本机", style = MaterialTheme.typography.bodySmall, color = Paper.Tertiary)
            Spacer(Modifier.height(d(24)))
            sections.forEachIndexed { index, item ->
                val atTop = index == 0
                val atBottom = index == sections.lastIndex
                SectionTab(
                    section = item,
                    selected = item == section,
                    onClick = { section = item },
                    // 「设置」按「下」时落在当前分类项上；分类项本身是导轨的焦点入口。
                    requester = if (item == section) entryFocus else null,
                    rightFocus = contentFocus,
                    // 末项按下没有下一个分类，直接交给内容区，避免焦点掉出去。
                    downFocus = if (atBottom) contentFocus else null,
                    // 首项按上回到顶栏导航项。
                    upFocus = if (atTop) upFocus else null,
                )
            }
            Spacer(Modifier.weight(1f))
            Text("红果 TV · $versionName", style = MaterialTheme.typography.labelSmall, color = Paper.Disabled)
        }

        Column(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(end = ui.gutter, top = d(96), bottom = d(30))
                // 内容区自成焦点组：切换分类时旧行被移除，focusRestorer 会把焦点
                // 收回到组内仍存在的项，避免「换一栏后光标消失」。
                .focusRequester(contentFocus)
                .focusGroup()
                .focusRestorer(),
        ) {
            Text(
                section.label,
                style = MaterialTheme.typography.headlineMedium,
                color = Paper.Primary,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(d(16)))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(d(8))) {
                when (section) {
                    SettingsSection.PLAYBACK -> playbackPanel(snapshot, vm) { choice = it }
                    SettingsSection.DANMAKU -> danmakuPanel(snapshot, vm, { choice = it }, { blockEditor = true }, { confirm = it })
                    SettingsSection.APPEARANCE -> appearancePanel(snapshot, vm, { iconPicker = true }) { choice = it }
                    SettingsSection.CONTROL -> controlPanel(snapshot, vm) { choice = it }
                    SettingsSection.NETWORK -> networkPanel(
                        apiStatus = apiStatus,
                        script = scriptUpdate,
                        snapshot = snapshot,
                        vm = vm,
                        onApiStatus = onApiStatus,
                        onChoice = { choice = it },
                        onCustomDns = { customDns = true },
                    )
                    SettingsSection.DATA -> dataPanel(vm, { confirm = it }, { message = it }, { diagnosticsText = PlaybackDiagnostics.readAll(); showPlaybackDiagnostics = true })
                    SettingsSection.ABOUT -> aboutPanel(versionName, vm)
                }
            }
        }
    }

    choice?.let { request ->
        ChoiceDialog(
            title = request.title,
            choices = request.choices,
            selected = request.selected,
            onSelect = { key ->
                request.onPick(key)
                choice = null
            },
            onDismiss = { choice = null },
        )
    }
    confirm?.let { request ->
        AppDialog(
            title = request.title,
            message = request.message,
            confirmText = "确定",
            onConfirm = {
                request.onConfirm()
                confirm = null
            },
            onDismiss = { confirm = null },
        )
    }
    if (blockEditor) {
        BlockedWordsDialog(
            words = snapshot.blockedWords,
            onAdd = { vm.addBlockedWord(it) },
            onRemove = { vm.removeBlockedWord(it) },
            onDismiss = { blockEditor = false },
        )
    }
    if (iconPicker) {
        AppIconDialog(
            current = snapshot.appIconVariant,
            onPick = { vm.setAppIconVariant(it) },
            onDismiss = { iconPicker = false },
        )
    }
    if (customDns) {
        DnsCustomDialog(
            current = snapshot.dnsCustom,
            onSave = { vm.setDnsCustom(it) },
            onDismiss = { customDns = false },
        )
    }
    if (showPlaybackDiagnostics) {
        AppDialog(
            title = "播放诊断记录",
            message = "仅记录播放器运行现场；若上次未正常结束，会保留最后一段记录。",
            confirmText = "关闭",
            dismissText = null,
            onConfirm = { showPlaybackDiagnostics = false },
            onDismiss = { showPlaybackDiagnostics = false },
        ) {
            Column(Modifier.fillMaxWidth()) {
                val diagnosticLines = remember(diagnosticsText) { diagnosticsText.lineSequence().toList() }
                LazyColumn(
                    Modifier.fillMaxWidth().height(d(360)),
                    verticalArrangement = Arrangement.spacedBy(d(4)),
                ) {
                    items(diagnosticLines.size) { lineIndex ->
                        Text(
                            diagnosticLines[lineIndex],
                            modifier = Modifier.fillMaxWidth().focusable(),
                            style = MaterialTheme.typography.bodySmall,
                            color = Paper.Secondary,
                        )
                    }
                }
                Spacer(Modifier.height(d(12)))
                Row(horizontalArrangement = Arrangement.spacedBy(d(10))) {
                    TvButton("一键复制", onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        clipboard?.setPrimaryClip(ClipData.newPlainText("播放诊断记录", diagnosticsText))
                        showPlaybackDiagnostics = false
                        message = "播放诊断记录已复制"
                    }, tone = ButtonTone.Primary)
                    TvButton("清空记录", onClick = {
                        PlaybackDiagnostics.clear()
                        diagnosticsText = "暂无播放诊断记录"
                    }, tone = ButtonTone.Ghost)
                }
            }
        }
    }

    message?.let { text ->
        AppDialog(
            title = "提示",
            message = text,
            confirmText = "知道了",
            dismissText = null,
            onConfirm = { message = null },
            onDismiss = { message = null },
        )
    }
}

// ------------------------------------------------------------------ 播放

private fun androidx.compose.foundation.lazy.LazyListScope.playbackPanel(
    snapshot: SettingsSnapshot,
    vm: AppViewModel,
    onChoice: (ChoiceRequest) -> Unit,
) {
    item {
        SettingRow(
            title = "默认清晰度",
            description = "进入播放时优先选择的清晰度",
            value = PlaybackQuality.label(snapshot.quality),
            onClick = {
                onChoice(
                    ChoiceRequest("默认清晰度", PlaybackQuality.options.map { Choice(it.toString(), PlaybackQuality.label(it)) }, snapshot.quality.toString()) {
                        vm.setQuality(it.toIntOrNull() ?: snapshot.quality)
                    },
                )
            },
        )
    }
    item {
        SettingRow(
            title = "默认倍速",
            description = "不影响已开始的播放",
            value = PlaybackSpeed.label(snapshot.speed),
            onClick = {
                onChoice(
                    ChoiceRequest("默认倍速", PlaybackSpeed.options.map { Choice(it.toString(), PlaybackSpeed.label(it)) }, snapshot.speed.toString()) {
                        vm.setSpeed(it.toFloatOrNull() ?: 1f)
                    },
                )
            },
        )
    }
    item {
        SettingRow(
            title = "启动时播放推荐",
            description = "打开应用后直接播放首页主推的第一部",
            toggle = snapshot.startupAutoplay,
            onToggle = { vm.setStartupAutoplay(it) },
        )
    }
    item {
        SettingRow(
            title = "无感连播",
            description = "开启：第二个播放器预渲染下一集首帧，切换即出画；关闭：同一播放器预载下一集。部分电视盒子同时开两路硬解会中断播放，遇到可关闭此项",
            toggle = snapshot.seamlessPlayback,
            onToggle = { vm.setSeamlessPlayback(it) },
        )
    }
    item {
        SettingRow(
            title = "自动连播",
            description = "一集结束后自动播放下一集",
            toggle = snapshot.autoNext,
            onToggle = { vm.setAutoNext(it) },
        )
    }
    item {
        SettingRow(
            title = "跨季连播",
            description = "本季结束后继续播放下一季",
            toggle = snapshot.autoSeason,
            onToggle = { vm.setAutoSeason(it) },
        )
    }
    item {
        SettingRow(
            title = "画面模式",
            description = "完整画面保留字幕，铺满模式填满屏幕",
            value = VideoFrameMode.fromStored(snapshot.frameMode).label,
            onClick = {
                onChoice(
                    ChoiceRequest(
                        "画面模式",
                        VideoFrameMode.entries.map { Choice(it.name, it.label) },
                        snapshot.frameMode,
                    ) { vm.setFrameMode(VideoFrameMode.fromStored(it)) },
                )
            },
        )
    }
    item {
        SettingRow(
            title = "快进步长",
            description = "播放时左右键单次快进的秒数",
            value = "${snapshot.seekStepSeconds} 秒",
            onClick = {
                onChoice(
                    ChoiceRequest(
                        "快进步长",
                        PlaybackExtras.SEEK_STEPS.map { Choice(it.toString(), "$it 秒") },
                        snapshot.seekStepSeconds.toString(),
                    ) { vm.setSeekStep(it.toIntOrNull() ?: 10) },
                )
            },
        )
    }
}

// ------------------------------------------------------------------ 弹幕

private fun androidx.compose.foundation.lazy.LazyListScope.danmakuPanel(
    snapshot: SettingsSnapshot,
    vm: AppViewModel,
    onChoice: (ChoiceRequest) -> Unit,
    onBlockedWords: () -> Unit,
    onConfirm: (ConfirmRequest) -> Unit,
) {
    item {
        SettingRow(
            title = "显示弹幕",
            description = "播放页叠加观众弹幕，关闭后所有剧集都不显示",
            toggle = snapshot.danmaku,
            onToggle = { vm.setDanmaku(it) },
        )
    }
    item {
        SettingRow(
            title = "弹幕渲染方式",
            description = "普通 View 为实验方案；切换后下次进入播放页生效，A/B 播放器共用同一渲染层",
            value = listOf("自动（兼容优先）", "普通 View（实验）", "SurfaceView（原版）").getOrElse(snapshot.danmakuRendererMode.coerceIn(0, 2)) { "自动（兼容优先）" },
            onClick = {
                onChoice(
                    ChoiceRequest(
                        "弹幕渲染方式",
                        listOf(
                            Choice("0", "自动（兼容优先）"),
                            Choice("1", "普通 View（实验）"),
                            Choice("2", "SurfaceView（原版）"),
                        ),
                        snapshot.danmakuRendererMode.toString(),
                    ) { vm.setDanmakuRendererMode(it.toIntOrNull() ?: 0) },
                )
            },
        )
    }

    item {
        SettingRow(
            title = "不透明度",
            description = "弹幕压在画面上，太高会挡字幕",
            value = "${snapshot.danmakuOpacity}%",
            onClick = {
                onChoice(
                    ChoiceRequest(
                        "弹幕不透明度",
                        (25..100 step 5).map { Choice(it.toString(), "$it%") },
                        snapshot.danmakuOpacity.toString(),
                    ) { vm.setDanmakuOpacity(it.toIntOrNull() ?: DanmakuPrefs.OPACITY_DEFAULT) },
                )
            },
        )
    }
    item {
        SettingRow(
            title = "字号",
            description = "大屏距离远，可以适当调大",
            value = DanmakuPrefs.fontLabel(snapshot.danmakuFont),
            onClick = {
                onChoice(
                    ChoiceRequest(
                        "弹幕字号",
                        DanmakuPrefs.FONT_LABELS.mapIndexed { index, label -> Choice(index.toString(), label) },
                        snapshot.danmakuFont.toString(),
                    ) { vm.setDanmakuFont(it.toIntOrNull() ?: DanmakuPrefs.FONT_DEFAULT) },
                )
            },
        )
    }
    item {
        SettingRow(
            title = "颜色",
            description = "默认保留接口原色，也可以统一成一种颜色",
            value = danmakuColorLabel(snapshot.danmakuColor),
            onClick = {
                onChoice(
                    ChoiceRequest(
                        "弹幕颜色",
                        (0..1).map { Choice(it.toString(), danmakuColorLabel(it)) } +
                            DanmakuPrefsColor.names.mapIndexed { index, name -> Choice((index + 2).toString(), name) },
                        snapshot.danmakuColor.toString(),
                    ) { vm.setDanmakuColor(it.toIntOrNull() ?: 0) },
                )
            },
        )
    }
    item {
        SettingRow(
            title = "移动速度",
            description = "太快来不及看，太慢会堆在一起",
            value = DanmakuPrefs.speedLabel(snapshot.danmakuSpeed),
            onClick = {
                onChoice(
                    ChoiceRequest(
                        "弹幕速度",
                        DanmakuPrefs.SPEED_LABELS.mapIndexed { index, label -> Choice(index.toString(), label) },
                        snapshot.danmakuSpeed.toString(),
                    ) { vm.setDanmakuSpeed(it.toIntOrNull() ?: DanmakuPrefs.SPEED_DEFAULT) },
                )
            },
        )
    }
    item {
        SettingRow(
            title = "展示行数",
            description = "同时在屏幕上滚动的轨道数量",
            value = "${snapshot.danmakuRows} 行",
            onClick = {
                onChoice(
                    ChoiceRequest(
                        "弹幕行数",
                        (DanmakuPrefs.ROWS_MIN..DanmakuPrefs.ROWS_MAX).map { Choice(it.toString(), "$it 行") },
                        snapshot.danmakuRows.toString(),
                    ) { vm.setDanmakuRows(it.toIntOrNull() ?: DanmakuPrefs.ROWS_DEFAULT) },
                )
            },
        )
    }
    item {
        SettingRow(
            title = "屏蔽词",
            description = if (snapshot.blockedWords.isEmpty()) "还没有屏蔽词" else "已屏蔽 ${snapshot.blockedWords.size} 个词",
            value = "管理",
            onClick = onBlockedWords,
        )
    }
    item {
        SettingRow(
            title = "恢复弹幕默认",
            description = "不透明度、字号、颜色、速度、行数恢复出厂值",
            value = "恢复",
            onClick = {
                onConfirm(ConfirmRequest("恢复弹幕默认", "弹幕显示效果会恢复为默认值，屏蔽词不会删除。") { vm.resetDanmakuPreferences() })
            },
        )
    }
}

private object DanmakuPrefsColor {
    val names = listOf("白色", "红色", "橙色", "黄色", "绿色", "青色", "蓝色", "紫色")
}

private fun danmakuColorLabel(mode: Int): String = when (mode) {
    0 -> "默认"
    1 -> "多彩"
    else -> DanmakuPrefsColor.names.getOrElse(mode - 2) { "默认" }
}

// ------------------------------------------------------------------ 画面

private fun androidx.compose.foundation.lazy.LazyListScope.appearancePanel(
    snapshot: SettingsSnapshot,
    vm: AppViewModel,
    onIconPicker: () -> Unit,
    onChoice: (ChoiceRequest) -> Unit,
) {
    item {
        SettingRow(
            title = "应用图标",
            description = "桌面图标与电视横幅一起换，共四款线稿",
            value = appIconVariantLabel(snapshot.appIconVariant),
            onClick = onIconPicker,
        )
    }
    item {
        SettingRow(
            title = "海报列数",
            description = "剧场与首页每行放几部，自动会跟随屏幕宽度",
            value = if (snapshot.posterColumns == 0) "自动" else "${snapshot.posterColumns} 列",
            onClick = {
                onChoice(
                    ChoiceRequest(
                        "海报列数",
                        listOf(Choice("0", "自动")) + (3..8).map { Choice(it.toString(), "$it 列") },
                        snapshot.posterColumns.toString(),
                    ) { vm.setPosterColumns(it.toIntOrNull() ?: 0) },
                )
            },
        )
    }
    item {
        SettingRow(
            title = "播放信息",
            description = "播放页叠加分辨率与码率",
            value = videoInfoLabel(snapshot.videoInfoMode),
            onClick = {
                onChoice(
                    ChoiceRequest(
                        "播放信息",
                        listOf(Choice("0", "关闭"), Choice("1", "简要（分辨率）"), Choice("2", "详细（码率与帧率）")),
                        snapshot.videoInfoMode.toString(),
                    ) { vm.setVideoInfoMode(it.toIntOrNull() ?: 0) },
                )
            },
        )
    }
    item {
        SettingRow(
            title = "实时网速",
            description = "播放页右下角显示当前下载速度",
            toggle = snapshot.showNetworkSpeed,
            onToggle = { vm.setShowNetworkSpeed(it) },
        )
    }
    item {
        SettingRow(
            title = "进度条常驻",
            description = "屏幕最底部显示一条极细进度线，随时看到进度，不影响画面",
            toggle = snapshot.persistentProgress,
            onToggle = { vm.setPersistentProgress(it) },
        )
    }
    item {
        SettingRow(
            title = "顶部时钟",
            description = "在顶栏显示当前时间",
            toggle = snapshot.showClock,
            onToggle = { vm.setShowClock(it) },
        )
    }
}

// ------------------------------------------------------------------ 按键

private val remoteActionChoices = PlaybackExtras.REMOTE_ACTION_LABELS.map { (key, label) -> Choice(key, label) }

private fun remoteActionLabel(action: String): String = PlaybackExtras.actionLabel(action)

private fun androidx.compose.foundation.lazy.LazyListScope.controlPanel(
    snapshot: SettingsSnapshot,
    vm: AppViewModel,
    onChoice: (ChoiceRequest) -> Unit,
) {
    item {
        SettingRow(
            title = "屏幕返回按钮",
            description = "各页面顶部显示可点击的返回箭头；遥控器返回键始终可用",
            toggle = snapshot.showScreenBack,
            onToggle = { vm.setShowScreenBack(it) },
        )
    }
    item {
        SettingRow(
            title = "上键",
            description = "播放中按遥控器上键",
            value = remoteActionLabel(snapshot.remoteUp),
            onClick = {
                onChoice(
                    ChoiceRequest("上键行为", remoteActionChoices, snapshot.remoteUp) {
                        vm.setRemoteAction(RemoteKey.UP, it)
                    },
                )
            },
        )
    }
    item {
        SettingRow(
            title = "下键",
            description = "播放中按遥控器下键",
            value = remoteActionLabel(snapshot.remoteDown),
            onClick = {
                onChoice(
                    ChoiceRequest("下键行为", remoteActionChoices, snapshot.remoteDown) {
                        vm.setRemoteAction(RemoteKey.DOWN, it)
                    },
                )
            },
        )
    }
    item {
        SettingRow(
            title = "确认键",
            description = "播放中按遥控器确认键",
            value = remoteActionLabel(snapshot.remoteConfirm),
            onClick = {
                onChoice(
                    ChoiceRequest("确认键行为", remoteActionChoices, snapshot.remoteConfirm) {
                        vm.setRemoteAction(RemoteKey.CONFIRM, it)
                    },
                )
            },
        )
    }
    item {
        SettingRow(
            title = "菜单键",
            description = "播放中按遥控器菜单键",
            value = remoteActionLabel(snapshot.remoteMenu),
            onClick = {
                onChoice(
                    ChoiceRequest("菜单键行为", remoteActionChoices, snapshot.remoteMenu) {
                        vm.setRemoteAction(RemoteKey.MENU, it)
                    },
                )
            },
        )
    }
}

// ------------------------------------------------------------------ 网络

private fun androidx.compose.foundation.lazy.LazyListScope.networkPanel(
    apiStatus: ApiStatusState,
    script: ScriptUpdateState,
    snapshot: SettingsSnapshot,
    vm: AppViewModel,
    onApiStatus: () -> Unit,
    onChoice: (ChoiceRequest) -> Unit,
    onCustomDns: () -> Unit,
) {
    item {
        SettingRow(
            title = "DNS 服务商",
            description = "首页、搜索、播放地址的域名解析；切换后立即生效",
            value = DnsProvider.of(snapshot.dnsProvider).label,
            onClick = {
                onChoice(
                    ChoiceRequest(
                        title = "DNS 服务商",
                        choices = DnsProvider.entries.map { Choice(it.key, it.label, it.hint) },
                        selected = snapshot.dnsProvider,
                    ) { key ->
                        vm.setDnsProvider(key)
                        // 选到「自定义」时直接接着让用户填服务器，少一次往返操作。
                        if (key == DnsProvider.CUSTOM.key) onCustomDns()
                    },
                )
            },
        )
    }
    if (snapshot.dnsProvider == DnsProvider.CUSTOM.key) {
        item {
            SettingRow(
                title = "自定义 DNS 服务器",
                description = "填 IPv4 地址，多个用逗号分隔",
                value = snapshot.dnsCustom.ifBlank { "未设置" },
                onClick = onCustomDns,
            )
        }
    }
    item { InfoRow("当前 DNS", DnsSettings.describe()) }
    item {
        SettingRow(
            title = "接口状态",
            description = "首页、搜索、播放与联想四项接口的连通性",
            value = apiStatus.summary,
            onClick = onApiStatus,
        )
    }
    item {
        SettingRow(
            title = "重新检测",
            description = "立即对四项接口各发一次请求，只读公开数据",
            value = if (apiStatus.running) "检测中…" else "检测",
            onClick = { if (!apiStatus.running) vm.refreshApiStatus() },
        )
    }
    item {
        val context = LocalContext.current
        InfoRow("当前网络", remember(context) { networkLabel(context) })
    }
    item {
        InfoRow("数据脚本", scriptVersionLabel(script))
    }
    item {
        InfoRow("脚本更新", scriptStatusLabel(script))
    }
    item { InfoRow("更新通道", "RedGuoAPI 发布通道 · 六条线路自动择优") }
}

/** 「2026-11-24 03:38 · 在线」这种一眼能看出跑的是哪一版。 */
private fun scriptVersionLabel(state: ScriptUpdateState): String {
    val version = state.version.ifBlank { "未知版本" }
    val source = if (state.fromUpdate) "在线" else "本地"
    val digest = state.digest.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
    return "$version · $source$digest"
}

/** 检查结果一句话：进行中 / 已下载待生效 / 失败原因 / 上次成功时间。 */
private fun scriptStatusLabel(state: ScriptUpdateState): String = when {
    state.checking -> "检查中…"
    state.pendingVersion.isNotBlank() -> "已下载 ${state.pendingVersion}，稍后生效"
    state.lastError != null -> "上次检查失败：${state.lastError}"
    state.checkedAt > 0 -> "已是最新 · ${scriptClock(state.checkedAt)}"
    else -> "启动后自动检查"
}

private fun scriptClock(at: Long): String =
    java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA).format(java.util.Date(at))

/** 自定义 DNS：常用地址一键填入，也可用系统输入法手输。 */
@Composable
private fun DnsCustomDialog(
    current: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by remember { mutableStateOf(current) }
    val ime = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    fun closeKeyboard() {
        focusManager.clearFocus()
        ime?.hide()
    }

    AppDialog(
        title = "自定义 DNS",
        message = "填写 IPv4 服务器地址，多个用逗号分隔；留空则回退系统解析。",
        confirmText = "保存",
        onConfirm = {
            closeKeyboard()
            onSave(draft)
            onDismiss()
        },
        onDismiss = {
            closeKeyboard()
            onDismiss()
        },
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(d(12)))
                    .background(Color(0x0FFFFFFF))
                    .padding(horizontal = d(16), vertical = d(12)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    singleLine = true,
                    textStyle = TextStyle(color = Paper.Pure, fontSize = MaterialTheme.typography.titleSmall.fontSize),
                    cursorBrush = SolidColor(Rouge.Bright),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { closeKeyboard() }),
                    decorationBox = { inner ->
                        Box {
                            if (draft.isEmpty()) {
                                Text(
                                    "例如 119.29.29.29",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = Paper.Disabled,
                                )
                            }
                            inner()
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(d(12)))
            Text("常用地址", style = MaterialTheme.typography.labelSmall, color = Paper.Tertiary)
            Spacer(Modifier.height(d(8)))
            listOf(
                listOf("119.29.29.29", "223.5.5.5"),
                listOf("114.114.114.114", "8.8.8.8"),
            ).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(d(8))) {
                    row.forEach { ip ->
                        DnsChip(ip) {
                            draft = if (draft.isBlank()) ip else "${draft.trim().trimEnd(',')}, $ip"
                        }
                    }
                }
                Spacer(Modifier.height(d(8)))
            }
        }
    }
}

@Composable
private fun DnsChip(text: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(999.dp)
    Box(
        Modifier
            .clip(shape)
            .background(if (focused) Rouge.Veil else Color(0x0AFFFFFF))
            .border(d(1.2f), if (focused) Rouge.Bright else Color(0x14FFFFFF), shape)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = d(14), vertical = d(8)),
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = if (focused) Paper.Pure else Paper.Secondary)
    }
}

private fun networkLabel(context: Context): String = runCatching {
    val manager = context.getSystemService(ConnectivityManager::class.java) ?: return@runCatching "未知"
    val network = manager.activeNetwork ?: return@runCatching "未连接"
    val caps = manager.getNetworkCapabilities(network) ?: return@runCatching "未连接"
    when {
        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "有线网络"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "无线网络"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "移动网络"
        else -> "其他网络"
    }
}.getOrDefault("未知")

// ------------------------------------------------------------------ 数据

private fun androidx.compose.foundation.lazy.LazyListScope.dataPanel(
    vm: AppViewModel,
    onConfirm: (ConfirmRequest) -> Unit,
    onMessage: (String) -> Unit,
    onPlaybackDiagnostics: () -> Unit,
) {
    item {
        SettingRow(
            title = "播放诊断记录",
            description = "查看播放状态、切集、预载、解码器、Surface、内存与异常中断现场",
            value = "查看",
            onClick = onPlaybackDiagnostics,
        )
    }
    item {
        SettingRow(
            title = "恢复播放偏好",
            description = "清晰度、倍速、连播等恢复默认，保留收藏与历史",
            value = "恢复",
            onClick = {
                onConfirm(ConfirmRequest("恢复播放偏好", "播放相关设置会恢复为默认值，收藏与观看历史不会受影响。") { vm.resetPlaybackPreferences() })
            },
        )
    }
    item {
        SettingRow(
            title = "清空观看历史",
            description = "本机所有观看进度将被删除",
            value = "清空",
            onClick = {
                onConfirm(ConfirmRequest("清空观看历史", "此操作不可恢复，确定清空全部观看记录？") { vm.clearHistory() })
            },
        )
    }
    item {
        SettingRow(
            title = "清空搜索历史",
            description = "只清除本机保存的搜索词",
            value = "清空",
            onClick = {
                onConfirm(ConfirmRequest("清空搜索历史", "清除本机保存的全部搜索词？") { vm.clearSearchHistory() })
            },
        )
    }
    item {
        SettingRow(
            title = "重置设备身份",
            description = "重新注册一台新设备，可解决接口被风控限制",
            value = "重置",
            onClick = {
                onConfirm(ConfirmRequest("重置设备身份", "将清除本机保存的设备注册信息，下次联网时重新注册一台新设备。收藏与观看历史不受影响。") { vm.resetDeviceIdentity() })
            },
        )
    }
    item {
        SettingRow(
            title = "清除缓存",
            description = "清理更新包与脚本字节码，收藏、历史与设置保留",
            value = "清除",
            onClick = { vm.clearCache() },
        )
    }
    item {
        SettingRow(
            title = "清除全部数据",
            description = "清空本机收藏、历史、设置和账号，完成后自动重启",
            value = "清除",
            onClick = {
                onConfirm(ConfirmRequest("清除全部数据", "将删除本机全部收藏、观看历史、设置、账号与数据脚本。应用会先保存尚未保存完成的数据，再清除全部内容并自动重启，可能需要几十秒。此操作不可恢复，确定继续？") { vm.clearAllData() })
            },
        )
    }
}

// ------------------------------------------------------------------ 关于

private fun androidx.compose.foundation.lazy.LazyListScope.aboutPanel(
    versionName: String,
    vm: AppViewModel,
) {
    item {
        SettingRow(
            title = "检查更新",
            description = "检查官方发布通道，可直接下载并安装新版",
            value = "检查",
            onClick = { vm.openUpdateDialog() },
        )
    }
    item { InfoRow("版本", versionName) }
    item { InfoRow("内容来源", "红果短剧官方公开接口") }
    item { InfoRow("本地数据", "收藏、历史与设置仅保存在本机，卸载即清除") }
    item { InfoRow("开源许可", "GPL-3.0 · 界面基于 Jetpack Compose 重写") }
}

// ------------------------------------------------------------------ 通用

private data class ChoiceRequest(
    val title: String,
    val choices: List<Choice>,
    val selected: String,
    val onPick: (String) -> Unit,
)

private data class ConfirmRequest(
    val title: String,
    val message: String,
    val onConfirm: () -> Unit,
)

@Composable
private fun SectionTab(
    section: SettingsSection,
    selected: Boolean,
    onClick: () -> Unit,
    requester: FocusRequester? = null,
    rightFocus: FocusRequester? = null,
    downFocus: FocusRequester? = null,
    upFocus: FocusRequester? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Row(
        Modifier
            .fillMaxWidth()
            // 焦点属性放在 clickable 之前（官方推荐顺序）。
            .then(if (requester != null) Modifier.focusRequester(requester) else Modifier)
            // 显式连线：右/下进入右侧内容区首行，首项向上回到顶栏，末项向下也交给内容区，
            // 避免几何搜索在「导轨已到底」时找不到目标而导致焦点丢失。
            .focusProperties {
                if (rightFocus != null) right = rightFocus
                if (downFocus != null) down = downFocus
                if (upFocus != null) up = upFocus
            }
            .clip(RoundedCornerShape(d(12)))
            .background(
                when {
                    focused -> Color(0x14FFFFFF)
                    selected -> Rouge.Veil
                    else -> Color.Transparent
                },
            )
            .border(
                d(1.2f),
                when {
                    focused -> Rouge.Bright
                    selected -> Rouge.Core.copy(0.35f)
                    else -> Color.Transparent
                },
                RoundedCornerShape(d(12)),
            )
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = d(16), vertical = d(11)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            section.icon,
            null,
            tint = if (focused || selected) Rouge.Bright else Paper.Tertiary,
            modifier = Modifier.size(d(18)),
        )
        Spacer(Modifier.width(d(12)))
        Text(
            section.label,
            style = MaterialTheme.typography.titleSmall,
            color = if (focused || selected) Paper.Pure else Paper.Secondary,
        )
    }
}

@Composable
private fun SettingRow(
    title: String,
    description: String,
    value: String? = null,
    toggle: Boolean? = null,
    onClick: (() -> Unit)? = null,
    onToggle: ((Boolean) -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val active = focused && onClick != null
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(d(13)))
            .background(
                when {
                    active -> Color(0x1AFFFFFF)
                    focused -> Color(0x14FFFFFF)
                    else -> Color(0x08FFFFFF)
                },
            )
            .border(
                d(1.2f),
                if (focused) Rouge.Bright else Color(0x0AFFFFFF),
                RoundedCornerShape(d(13)),
            )
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = onClick != null || onToggle != null,
            ) {
                if (onToggle != null && toggle != null) onToggle(!toggle) else onClick?.invoke()
            }
            .padding(horizontal = d(18), vertical = d(12)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = Paper.Primary)
            Spacer(Modifier.height(d(3)))
            Text(description, style = MaterialTheme.typography.bodySmall, color = Paper.Tertiary, maxLines = 1)
        }
        if (toggle != null) {
            TogglePill(toggle, focused)
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(value.orEmpty(), style = MaterialTheme.typography.labelMedium, color = if (focused) Gold.Bright else Paper.Secondary)
                if (onClick != null) {
                    Spacer(Modifier.width(d(6)))
                    Icon(
                        Icons.Rounded.ChevronRight,
                        null,
                        tint = if (focused) Paper.Primary else Paper.Tertiary,
                        modifier = Modifier.size(d(17)),
                    )
                }
            }
        }
    }
}

@Composable
private fun TogglePill(on: Boolean, focused: Boolean) {
    val offset by animateFloatAsState(if (on) 1f else 0f, spring(0.7f, 500f), label = "toggle")
    val travel = with(androidx.compose.ui.platform.LocalDensity.current) { (d(46) - d(26)).toPx() }
    val bg by animateColorAsState(
        when {
            on && focused -> Rouge.Bright
            on -> Rouge.Core
            focused -> Color(0x33FFFFFF)
            else -> Color(0x1AFFFFFF)
        },
        tween(160),
        label = "toggle-bg",
    )
    Box(
        Modifier
            .width(d(46))
            .height(d(26))
            .clip(RoundedCornerShape(999.dp))
            .background(bg)
            .border(d(1f), if (focused) Color.White.copy(0.65f) else Color.Transparent, RoundedCornerShape(999.dp))
            .padding(horizontal = d(3)),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .graphicsLayer { translationX = offset * travel }
                .size(d(20))
                .clip(CircleShape)
                .background(Color.White),
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(d(13)))
            .background(Color(0x06FFFFFF))
            .padding(horizontal = d(18), vertical = d(13)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.titleSmall, color = Paper.Secondary)
        Spacer(Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodySmall, color = Paper.Tertiary)
    }
}

/** 屏蔽词管理：列表删除 + 输入框添加。遥控器用系统输入法输入。 */
@Composable
private fun BlockedWordsDialog(
    words: List<String>,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val addFocus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val ime = LocalSoftwareKeyboardController.current
    var fieldFocused by remember { mutableStateOf(false) }

    fun closeKeyboard() {
        focusManager.clearFocus()
        ime?.hide()
    }

    // 输入态下返回键只收键盘，不关闭弹窗；输入框失焦后返回键恢复正常关闭。
    // 之前的做法缺了这一层：返回直接把 Dialog 关掉，输入法窗口却留在屏幕上。
    BackHandler(enabled = fieldFocused) { closeKeyboard() }
    // 弹窗退出时兜底收键盘，避免 Dialog 销毁后输入法残留。
    DisposableEffect(Unit) { onDispose { ime?.hide() } }

    AppDialog(
        title = "弹幕屏蔽词",
        message = if (words.isEmpty()) "还没有屏蔽词，命中任意一个词的弹幕都不会显示。" else null,
        confirmText = "完成",
        dismissText = null,
        onConfirm = {
            closeKeyboard()
            onDismiss()
        },
        onDismiss = {
            closeKeyboard()
            onDismiss()
        },
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(d(12)))
                    .background(Color(0x0FFFFFFF))
                    .padding(horizontal = d(16), vertical = d(12)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    singleLine = true,
                    textStyle = TextStyle(color = Paper.Pure, fontSize = MaterialTheme.typography.titleSmall.fontSize),
                    cursorBrush = SolidColor(Rouge.Bright),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = {
                        if (draft.isNotBlank()) {
                            onAdd(draft)
                            draft = ""
                        }
                    }),
                    decorationBox = { inner ->
                        Box {
                            if (draft.isEmpty()) {
                                Text("输入要屏蔽的词", style = MaterialTheme.typography.titleSmall, color = Paper.Disabled)
                            }
                            inner()
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(focus)
                        .onFocusChanged { info ->
                            fieldFocused = info.isFocused
                            if (!info.isFocused) ime?.hide()
                        }
                        // 遥控器不会困在输入框里：按「下」直接到「添加」，再按 OK 入列。
                        .onPreviewKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown) {
                                addFocus.tryRequestFocus()
                            } else {
                                false
                            }
                        },
                )
                Spacer(Modifier.width(d(10)))
                TvButton(
                    text = "添加",
                    onClick = {
                        if (draft.isNotBlank()) {
                            onAdd(draft)
                            draft = ""
                        }
                    },
                    tone = ButtonTone.Primary,
                    focusRequester = addFocus,
                )
            }
            if (words.isNotEmpty()) {
                Spacer(Modifier.height(d(12)))
                LazyColumn(Modifier.fillMaxWidth().height(d(220)), verticalArrangement = Arrangement.spacedBy(d(6))) {
                    words.forEach { word ->
                        item {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(d(10)))
                                    .background(Color(0x0AFFFFFF))
                                    .padding(horizontal = d(14), vertical = d(10)),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(word, style = MaterialTheme.typography.bodyMedium, color = Paper.Secondary)
                                Spacer(Modifier.weight(1f))
                                TvButton(text = "移除", onClick = { onRemove(word) }, tone = ButtonTone.Ghost)
                            }
                        }
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ 应用图标

private fun appIconVariantLabel(variant: String): String = when (variant) {
    "h" -> "环刻"
    "j" -> "光弧"
    "k" -> "光圈"
    else -> "玻璃窗"
}

private val appIconChoices = listOf(
    Triple("i", "玻璃窗", "圆窗 · 一线反光"),
    Triple("h", "环刻", "圆环 · 金弧"),
    Triple("j", "光弧", "暗场一束光"),
    Triple("k", "光圈", "六叶快门"),
)

@Composable
private fun AppIconDialog(
    current: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    // 预览必须用原始 webp 位图：_safe 是 <inset> 容器 drawable，Compose 的 painterResource
    // 只支持 VectorDrawable 与 PNG/JPG/WEBP，加载 <inset> 会抛 IllegalArgumentException 闪退。
    // 桌面那份 inset 安全区（8dp）在这里改用 Modifier.padding 复现，视觉一致且不再崩溃。
    val fgRes = mapOf(
        "h" to R.drawable.ic_launcher_fg_h,
        "i" to R.drawable.ic_launcher_fg_i,
        "j" to R.drawable.ic_launcher_fg_j,
        "k" to R.drawable.ic_launcher_fg_k,
    )
    AppDialog(
        title = "应用图标",
        message = "选中即生效，桌面图标与电视横幅一起更换",
        confirmText = "完成",
        dismissText = null,
        onConfirm = onDismiss,
        onDismiss = onDismiss,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(d(14))) {
            appIconChoices.chunked(2).forEach { rowItems ->
                Row(horizontalArrangement = Arrangement.spacedBy(d(14))) {
                    rowItems.forEach { (key, name, hint) ->
                        val selected = key == current
                        Column(
                            Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(d(18)))
                                .border(
                                    d(if (selected) 1.6f else 1f),
                                    if (selected) Gold.Core else Color(0x1FFFFFFF),
                                    RoundedCornerShape(d(18)),
                                )
                                .background(if (selected) Color(0x14E7BE7C) else Color(0x0AFFFFFF))
                                .clickable { onPick(key) }
                                .padding(d(12)),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Box(
                                Modifier
                                    .size(d(92))
                                    .clip(RoundedCornerShape(d(20)))
                                    .background(Brush.verticalGradient(listOf(Color(0xFF131722), Color(0xFF04050A)))),
                            ) {
                                Image(
                                    painter = painterResource(fgRes.getValue(key)),
                                    contentDescription = name,
                                    // 用 padding 复现 _safe 那份 8dp inset 安全区，保证预览与桌面裁切一致。
                                    Modifier.fillMaxSize().padding(d(14)),
                                )
                                if (selected) {
                                    Icon(
                                        Icons.Rounded.Check,
                                        contentDescription = null,
                                        tint = Gold.Bright,
                                        modifier = Modifier.align(Alignment.TopEnd).size(d(18)).padding(d(2)),
                                    )
                                }
                            }
                            Spacer(Modifier.height(d(10)))
                            Text(
                                name,
                                style = MaterialTheme.typography.titleSmall,
                                color = if (selected) Gold.Core else Paper.Pure,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Spacer(Modifier.height(d(3)))
                            Text(hint, style = MaterialTheme.typography.labelSmall, color = Paper.Tertiary)
                        }
                    }
                }
            }
        }
    }
}
