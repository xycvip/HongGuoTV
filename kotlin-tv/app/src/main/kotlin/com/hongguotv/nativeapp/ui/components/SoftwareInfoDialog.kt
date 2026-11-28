// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.hongguotv.nativeapp.ui.theme.Gold
import com.hongguotv.nativeapp.ui.theme.Ink
import com.hongguotv.nativeapp.ui.theme.Paper
import com.hongguotv.nativeapp.ui.theme.Rouge
import com.hongguotv.nativeapp.ui.theme.d

/**
 * 软件说明弹窗：免责与使用须知，遥控器可上下滚动逐条阅读。
 */
@Composable
fun SoftwareInfoDialog(onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val firstItemFocus = remember { FocusRequester() }
        val closeFocus = remember { FocusRequester() }
        val listState = rememberLazyListState()
        Column(
            Modifier
                .widthIn(min = d(560), max = d(660))
                // 固定为屏幕高度的 86%：列表区用 weight 吃掉剩余空间，
                // 底部「关闭」行始终锚定在可见区域，不会被超长内容挤出屏幕。
                .fillMaxHeight(0.86f)
                .clip(RoundedCornerShape(d(26)))
                .background(Brush.verticalGradient(listOf(Color(0xFF171B24), Color(0xFF0C0F16))))
                .border(d(1f), Color(0x26FFFFFF), RoundedCornerShape(d(26)))
                .padding(horizontal = d(30), vertical = d(26)),
        ) {
            Box(
                Modifier
                    .width(d(46))
                    .height(d(3))
                    .clip(RoundedCornerShape(d(99)))
                    .background(Brush.horizontalGradient(listOf(Rouge.Bright, Gold.Core))),
            )
            Spacer(Modifier.height(d(18)))
            Text(
                "DISCLAIMER",
                style = MaterialTheme.typography.labelSmall,
                color = Gold.Core,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(d(6)))
            Text(
                "软件说明",
                style = MaterialTheme.typography.headlineMedium,
                color = Paper.Pure,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(d(4)))
            Text(
                "关于本软件与使用时请留意的事项",
                style = MaterialTheme.typography.bodySmall,
                color = Paper.Tertiary,
            )

            Spacer(Modifier.height(d(18)))
            Box(Modifier.fillMaxWidth().weight(1f)) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(d(14)))
                        .background(Color(0x06FFFFFF)),
                    verticalArrangement = Arrangement.spacedBy(d(8)),
                ) {
                    itemsIndexed(SOFTWARE_INFO_ITEMS) { index, item ->
                        InfoItem(
                            number = index + 1,
                            title = item.first,
                            body = item.second,
                            modifier = if (index == 0) Modifier.focusRequester(firstItemFocus) else Modifier,
                        )
                    }
                }
                LazyListScrollbar(listState)
            }

            Spacer(Modifier.height(d(18)))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(d(6))
                        .clip(CircleShape)
                        .background(Gold.Core),
                )
                Spacer(Modifier.width(d(8)))
                Text(
                    "本应用以 GPL-3.0-only 发布 · 详见仓库 LICENSE",
                    style = MaterialTheme.typography.bodySmall,
                    color = Paper.Tertiary,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(d(12)))
                TvButton("关闭", onDismiss, tone = ButtonTone.Ghost, focusRequester = closeFocus)
            }
        }
        LaunchedEffect(Unit) { runCatching { firstItemFocus.requestFocus() } }
    }
}

private val SOFTWARE_INFO_ITEMS: List<Pair<String, String>> = listOf(
    "非官方声明" to "本软件为红果短剧的第三方非官方客户端，与平台官方无任何关联、授权或背书。",
    "用途与版权" to "仅供个人学习与技术研究使用。影视内容版权归原权利方所有，本软件不托管、不复制、不分发任何内容，仅通过公开接口获取信息。",
    "账号风险" to "登录官方账号可能触发平台风控，导致账号异常或受限，请谨慎使用，建议使用非主力账号。",
    "本机数据" to "收藏、观看历史与设置默认仅保存在本机，卸载即清除；云同步为可选功能，开启后才与账号云端合并。",
    "接口与稳定性" to "功能依赖第三方公开接口，接口变更或服务调整可能导致部分功能失效，不保证长期可用。",
    "责任声明" to "本软件按「现状」提供，不提供任何明示或默示担保；因使用本软件产生的任何后果由使用者自行承担。",
)

@Composable
private fun InfoItem(number: Int, title: String, body: String, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(d(12)))
            .background(Ink.Surface)
            .border(d(1f), Color(0x14FFFFFF), RoundedCornerShape(d(12)))
            .focusable()
            .padding(horizontal = d(14), vertical = d(12)),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            Modifier
                .size(d(22))
                .clip(CircleShape)
                .background(Gold.Core.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "$number",
                style = MaterialTheme.typography.labelSmall,
                color = Gold.Core,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Spacer(Modifier.width(d(12)))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = Paper.Primary, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(d(4)))
            Text(body, style = MaterialTheme.typography.bodySmall, color = Paper.Secondary, lineHeight = MaterialTheme.typography.bodySmall.lineHeight)
        }
    }
}
