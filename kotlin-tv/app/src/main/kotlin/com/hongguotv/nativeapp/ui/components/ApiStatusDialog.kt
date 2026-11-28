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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import com.hongguotv.nativeapp.ui.data.ApiProbe
import com.hongguotv.nativeapp.ui.data.ApiProbeStatus
import com.hongguotv.nativeapp.ui.data.ApiStatusState
import com.hongguotv.nativeapp.ui.data.label
import com.hongguotv.nativeapp.ui.data.tint
import com.hongguotv.nativeapp.ui.theme.Gold
import com.hongguotv.nativeapp.ui.theme.Paper
import com.hongguotv.nativeapp.ui.theme.Rouge
import com.hongguotv.nativeapp.ui.theme.d
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 接口状态详情。顶栏品牌灯和「设置 · 网络」共用同一个弹窗，
 * 四项固定、不滚动，遥控器一次就能看全。
 */
@Composable
fun ApiStatusDialog(
    state: ApiStatusState,
    onRefresh: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val actionFocus = remember { FocusRequester() }
        Column(
            Modifier
                .widthIn(min = d(520), max = d(600))
                .clip(RoundedCornerShape(d(26)))
                .background(Brush.verticalGradient(listOf(Color(0xFF171B24), Color(0xFF0C0F16))))
                .border(d(1f), Color(0x26FFFFFF), RoundedCornerShape(d(26)))
                .padding(horizontal = d(30), vertical = d(26)),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "SERVICE STATUS",
                        style = MaterialTheme.typography.labelSmall,
                        color = Gold.Core,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(d(6)))
                    Text("接口状态", style = MaterialTheme.typography.headlineMedium, color = Paper.Pure, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(d(4)))
                    Text(
                        "首页、搜索、播放与剧场四项接口的最近一次检测",
                        style = MaterialTheme.typography.bodySmall,
                        color = Paper.Tertiary,
                    )
                }
                SummaryBadge(state.summary, state.overall)
            }
            Spacer(Modifier.height(d(18)))
            Column(verticalArrangement = Arrangement.spacedBy(d(6))) {
                state.probes.forEach { probe ->
                    ProbeRow(probe, onRetry = { onRefresh(probe.key) })
                }
            }
            Spacer(Modifier.height(d(20)))
            Row(horizontalArrangement = Arrangement.spacedBy(d(12)), verticalAlignment = Alignment.CenterVertically) {
                TvButton(
                    text = if (state.running) "检测中…" else "重新检测全部",
                    onClick = { if (!state.running) onRefresh(null) },
                    enabled = !state.running,
                    focusRequester = actionFocus,
                    tone = ButtonTone.Primary,
                )
                TvButton("关闭", onDismiss, tone = ButtonTone.Ghost)
                Spacer(Modifier.weight(1f))
                Text(
                    "检测只读取公开接口，不影响本地记录",
                    style = MaterialTheme.typography.bodySmall,
                    color = Paper.Tertiary,
                )
            }
        }
        LaunchedEffect(Unit) { runCatching { actionFocus.requestFocus() } }
    }
}

@Composable
private fun SummaryBadge(text: String, status: ApiProbeStatus) {
    val color = status.tint()
    Row(
        Modifier
            .clip(RoundedCornerShape(d(99)))
            .background(color.copy(alpha = 0.12f))
            .border(d(1f), color.copy(alpha = 0.32f), RoundedCornerShape(d(99)))
            .padding(horizontal = d(14), vertical = d(8)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(color = color, running = status == ApiProbeStatus.CHECKING, size = 8)
        Spacer(Modifier.width(d(8)))
        Text(text, style = MaterialTheme.typography.labelMedium, color = color, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun ProbeRow(probe: ApiProbe, onRetry: () -> Unit) {
    val color = probe.status.tint()
    val time = remember(probe.updatedAt) {
        if (probe.updatedAt <= 0L) "尚未检测"
        else SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(probe.updatedAt))
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(d(14)))
            .background(Color(0x07FFFFFF))
            .border(d(1f), Color(0x0FFFFFFF), RoundedCornerShape(d(14)))
            .clickable(onClick = onRetry)
            .padding(horizontal = d(16), vertical = d(12)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(color = color, running = probe.status == ApiProbeStatus.CHECKING, size = 9)
        Spacer(Modifier.width(d(12)))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(probe.label, style = MaterialTheme.typography.titleSmall, color = Paper.Primary, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(d(8)))
                Text(
                    probe.status.label(),
                    style = MaterialTheme.typography.labelSmall,
                    color = color,
                )
            }
            Spacer(Modifier.height(d(3)))
            Text(
                probe.detail,
                style = MaterialTheme.typography.bodySmall,
                color = Paper.Tertiary,
                maxLines = 2,
            )
        }
        Spacer(Modifier.width(d(14)))
        Column(horizontalAlignment = Alignment.End) {
            if (probe.elapsedMs > 0) {
                Text("${probe.elapsedMs} ms", style = MaterialTheme.typography.labelMedium, color = Paper.Secondary)
            }
            Spacer(Modifier.height(d(3)))
            Text(time, style = MaterialTheme.typography.labelMedium, color = Paper.Tertiary)
        }
        Spacer(Modifier.width(d(12)))
        Text("重测", style = MaterialTheme.typography.labelMedium, color = if (probe.status == ApiProbeStatus.CHECKING) Paper.Disabled else Gold.Core)
    }
}

/** 带柔光的状态点：比纯色圆点更像「指示灯」。 */
@Composable
fun StatusDot(color: Color, running: Boolean = false, size: Int = 9) {
    Box(Modifier.size(d(size + 8)), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(d(size + 8))
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        colors = listOf(color.copy(alpha = if (running) 0.42f else 0.26f), Color.Transparent),
                    ),
                ),
        )
        Box(
            Modifier
                .size(d(size))
                .clip(CircleShape)
                .background(color)
                .border(d(1f), Color(0x33FFFFFF), CircleShape),
        )
    }
}
