// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.hongguotv.core.AppUpdate
import com.hongguotv.nativeapp.ui.data.UpdatePhase
import com.hongguotv.nativeapp.ui.data.UpdateUiState
import com.hongguotv.nativeapp.ui.theme.Gold
import com.hongguotv.nativeapp.ui.theme.Paper
import com.hongguotv.nativeapp.ui.theme.Rouge
import com.hongguotv.nativeapp.ui.theme.d

/**
 * 更新弹窗：检查 → 下载（进度 / 速度 / 线路）→ 校验 → 安装，一个弹窗走完。
 * 下载中关掉弹窗不会中断下载，回来还能继续看进度并安装。
 */
@Composable
fun UpdateDialog(
    state: UpdateUiState,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onInstall: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    onPickMirror: (Int?) -> Unit,
) {
    if (!state.visible) return
    var mirrorPicker by remember { mutableStateOf(false) }
    val update = state.update
    val primaryFocus = remember { FocusRequester() }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .widthIn(min = d(540), max = d(680))
                .clip(RoundedCornerShape(d(26)))
                .background(Brush.verticalGradient(listOf(Color(0xFF191D27), Color(0xFF0B0E15))))
                .border(d(1f), Color(0x26FFFFFF), RoundedCornerShape(d(26)))
                .padding(horizontal = d(30), vertical = d(26)),
        ) {
            Text(
                "VERSION UPDATE",
                style = MaterialTheme.typography.labelSmall,
                color = Gold.Core,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(d(8)))
            when (state.phase) {
                UpdatePhase.CHECKING -> {
                    Text("正在检查新版本", style = MaterialTheme.typography.headlineMedium, color = Paper.Pure, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(d(10)))
                    Text("正在连接官方发布通道", style = MaterialTheme.typography.bodyMedium, color = Paper.Tertiary)
                }
                UpdatePhase.UP_TO_DATE -> {
                    Text("已是最新版本", style = MaterialTheme.typography.headlineMedium, color = Paper.Pure, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(d(10)))
                    Text(
                        "当前 ${versionName()} 已是官方最新版，无需更新。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Paper.Secondary,
                    )
                }
                else -> {
                    Text(
                        update?.let { "发现新版本 ${it.versionName}" } ?: "版本更新",
                        style = MaterialTheme.typography.headlineMedium,
                        color = Paper.Pure,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(d(8)))
                    if (update != null) VersionMeta(update)
                }
            }

            if (update != null && state.phase != UpdatePhase.UP_TO_DATE) {
                Spacer(Modifier.height(d(16)))
                NotesPanel(update)
                Spacer(Modifier.height(d(16)))
                DownloadBlock(state)
            }

            state.error?.let { message ->
                Spacer(Modifier.height(d(12)))
                Text(message, style = MaterialTheme.typography.bodySmall, color = Rouge.Bright)
            }
            state.notice?.let { message ->
                Spacer(Modifier.height(d(12)))
                Text(message, style = MaterialTheme.typography.bodySmall, color = Gold.Core)
            }

            Spacer(Modifier.height(d(22)))
            Row(horizontalArrangement = Arrangement.spacedBy(d(12)), verticalAlignment = Alignment.CenterVertically) {
                when (state.phase) {
                    UpdatePhase.CHECKING -> Unit
                    UpdatePhase.UP_TO_DATE -> TvButton("知道了", onDismiss, tone = ButtonTone.Primary, focusRequester = primaryFocus)
                    UpdatePhase.AVAILABLE -> {
                        TvButton("立即更新", onDownload, tone = ButtonTone.Primary, focusRequester = primaryFocus)
                        TvButton("稍后", onDismiss, tone = ButtonTone.Ghost)
                    }
                    UpdatePhase.DOWNLOADING, UpdatePhase.VERIFYING -> {
                        TvButton("取消下载", onCancelDownload, tone = ButtonTone.Ghost)
                        TvButton("后台下载", onDismiss, tone = ButtonTone.Quiet)
                    }
                    UpdatePhase.READY -> {
                        TvButton("立即安装", onInstall, tone = ButtonTone.Primary, focusRequester = primaryFocus)
                        TvButton("稍后", onDismiss, tone = ButtonTone.Ghost)
                    }
                    UpdatePhase.ERROR -> {
                        TvButton("重试", onRetry, tone = ButtonTone.Primary, focusRequester = primaryFocus)
                        TvButton("关闭", onDismiss, tone = ButtonTone.Ghost)
                    }
                    UpdatePhase.IDLE -> TvButton("关闭", onDismiss, tone = ButtonTone.Ghost)
                }
                Spacer(Modifier.weight(1f))
                if (update != null && state.phase != UpdatePhase.UP_TO_DATE && state.phase != UpdatePhase.READY) {
                    MirrorChip(
                        label = state.mirrorIndex?.let { AppUpdate.MIRROR_NAMES.getOrNull(it) } ?: "自动线路",
                        enabled = !state.busy,
                    ) { mirrorPicker = true }
                }
            }
            if (update != null && state.phase != UpdatePhase.UP_TO_DATE) {
                Spacer(Modifier.height(d(12)))
                Text(
                    "只在应用前台检查更新；下载完成后需确认系统安装，追剧与观看记录保留。",
                    style = MaterialTheme.typography.bodySmall,
                    color = Paper.Tertiary,
                )
            }
        }
        LaunchedEffect(state.phase) {
            if (state.phase == UpdatePhase.AVAILABLE || state.phase == UpdatePhase.READY || state.phase == UpdatePhase.UP_TO_DATE) {
                runCatching { primaryFocus.requestFocus() }
            }
        }
    }

    if (mirrorPicker) {
        val choices = listOf(Choice("auto", "自动线路", "按顺序尝试，失败自动切换")) +
            AppUpdate.MIRROR_NAMES.mapIndexed { index, name -> Choice(index.toString(), name) }
        ChoiceDialog(
            title = "下载线路",
            choices = choices,
            selected = state.mirrorIndex?.toString() ?: "auto",
            onSelect = { key ->
                onPickMirror(if (key == "auto") null else key.toIntOrNull())
                mirrorPicker = false
            },
            onDismiss = { mirrorPicker = false },
        )
    }
}

@Composable
private fun VersionMeta(update: AppUpdate) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(d(10))) {
        MetaChip("当前 ${versionName()}")
        MetaChip("${"%.1f".format(update.size / 1048576.0)} MB")
        MetaChip("minSdk ${update.minSdk}")
    }
}

@Composable
private fun MetaChip(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = Paper.Tertiary,
        modifier = Modifier
            .clip(RoundedCornerShape(d(99)))
            .background(Color(0x0DFFFFFF))
            .border(d(1f), Color(0x12FFFFFF), RoundedCornerShape(d(99)))
            .padding(horizontal = d(10), vertical = d(5)),
    )
}

@Composable
private fun NotesPanel(update: AppUpdate) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(d(16)))
            .background(Color(0x06FFFFFF))
            .border(d(1f), Color(0x0FFFFFFF), RoundedCornerShape(d(16)))
            .padding(d(16)),
    ) {
        Text("更新说明", style = MaterialTheme.typography.labelMedium, color = Gold.Core)
        Spacer(Modifier.height(d(8)))
        Text(
            update.notes.ifBlank { "本次更新优化了播放稳定性与界面细节。" },
            style = MaterialTheme.typography.bodyMedium,
            color = Paper.Secondary,
            modifier = Modifier
                .heightIn(max = d(190))
                .verticalScroll(rememberScrollState()),
        )
    }
}

@Composable
private fun DownloadBlock(state: UpdateUiState) {
    when (state.phase) {
        UpdatePhase.DOWNLOADING, UpdatePhase.VERIFYING, UpdatePhase.READY, UpdatePhase.AVAILABLE -> Unit
        else -> return
    }
    if (state.phase == UpdatePhase.AVAILABLE) {
        Text("点击「立即更新」开始下载，下载完成后可直接安装。", style = MaterialTheme.typography.bodySmall, color = Paper.Tertiary)
        return
    }
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (state.phase == UpdatePhase.READY) "安装包已就绪" else if (state.phase == UpdatePhase.VERIFYING) "正在校验安装包" else "正在下载",
                style = MaterialTheme.typography.titleSmall,
                color = Paper.Primary,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.weight(1f))
            Text(
                if (state.phase == UpdatePhase.DOWNLOADING) "${state.progress}%" else if (state.phase == UpdatePhase.READY) "100%" else "校验中",
                style = MaterialTheme.typography.titleMedium,
                color = Gold.Core,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.height(d(10)))
        Box(
            Modifier
                .fillMaxWidth()
                .height(d(6))
                .clip(RoundedCornerShape(d(99)))
                .background(Color(0x14FFFFFF)),
        ) {
            Box(
                Modifier
                    .fillMaxWidth((state.progress.coerceIn(0, 100) / 100f).coerceAtLeast(0.01f))
                    .height(d(6))
                    .clip(RoundedCornerShape(d(99)))
                    .background(Brush.horizontalGradient(listOf(Rouge.Core, Gold.Core))),
            )
        }
        Spacer(Modifier.height(d(8)))
        Row {
            Text(
                if (state.phase == UpdatePhase.DOWNLOADING) speedText(state.speedBytesPerSecond) else "",
                style = MaterialTheme.typography.labelMedium,
                color = Paper.Tertiary,
            )
            Spacer(Modifier.weight(1f))
            Text(
                if (state.phase == UpdatePhase.READY) "已通过 SHA-256 与签名校验" else "下载可后台进行",
                style = MaterialTheme.typography.labelMedium,
                color = if (state.phase == UpdatePhase.READY) Color(0xFF5FE0B2) else Paper.Disabled,
            )
        }
    }
}

@Composable
private fun MirrorChip(label: String, enabled: Boolean, onClick: () -> Unit) {
    Text(
        "线路 · $label",
        style = MaterialTheme.typography.labelMedium,
        color = if (enabled) Paper.Secondary else Paper.Disabled,
        modifier = Modifier
            .clip(RoundedCornerShape(d(99)))
            .border(d(1f), Color(0x1AFFFFFF), RoundedCornerShape(d(99)))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = d(12), vertical = d(7)),
    )
}

private fun speedText(bytesPerSecond: Long): String = when {
    bytesPerSecond >= 1_000_000 -> "%.1f MB/s".format(bytesPerSecond / 1_000_000.0)
    bytesPerSecond > 0 -> "%.0f KB/s".format(bytesPerSecond / 1024.0)
    else -> ""
}

private fun versionName(): String = com.hongguotv.nativeapp.BuildConfig.VERSION_NAME
