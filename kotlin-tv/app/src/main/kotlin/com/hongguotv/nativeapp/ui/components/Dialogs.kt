// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.hongguotv.nativeapp.ui.theme.Gold
import com.hongguotv.nativeapp.ui.theme.Ink
import com.hongguotv.nativeapp.ui.theme.Paper
import com.hongguotv.nativeapp.ui.theme.Rouge
import com.hongguotv.nativeapp.ui.theme.d

/** 统一的弹窗容器：深色玻璃卡片 + 顶部细金线，任何页面调用都保持同一气质。 */
@Composable
fun AppDialog(
    title: String,
    message: String? = null,
    confirmText: String = "确定",
    dismissText: String? = "取消",
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    content: (@Composable () -> Unit)? = null,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        val confirmFocus = remember { FocusRequester() }
        Column(
            Modifier
                .widthIn(min = d(420), max = d(560))
                .clip(RoundedCornerShape(d(24)))
                .background(Brush.verticalGradient(listOf(Color(0xFF171B24), Color(0xFF0E1118))))
                .border(d(1f), Color(0x26FFFFFF), RoundedCornerShape(d(24)))
                .padding(d(30)),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier
                    .width(d(46))
                    .height(d(3))
                    .clip(RoundedCornerShape(99.dp))
                    .background(Brush.horizontalGradient(listOf(Rouge.Bright, Gold.Core))),
            )
            Spacer(Modifier.height(d(20)))
            Text(title, style = MaterialTheme.typography.headlineSmall, color = Paper.Pure, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
            if (!message.isNullOrBlank()) {
                Spacer(Modifier.height(d(12)))
                Text(
                    message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Paper.Secondary,
                    textAlign = TextAlign.Center,
                )
            }
            if (content != null) {
                Spacer(Modifier.height(d(18)))
                content()
            }
            Spacer(Modifier.height(d(26)))
            Row(horizontalArrangement = Arrangement.spacedBy(d(12)), verticalAlignment = Alignment.CenterVertically) {
                if (dismissText != null) {
                    TvButton(dismissText, onDismiss, tone = ButtonTone.Ghost)
                }
                TvButton(
                    confirmText,
                    onConfirm,
                    tone = ButtonTone.Primary,
                    focusRequester = confirmFocus,
                )
            }
        }
        LaunchedEffect(Unit) { runCatching { confirmFocus.requestFocus() } }
    }
}

data class Choice(val key: String, val label: String, val hint: String? = null)

/** 单选弹窗：清晰度、倍速、主题等设置共用。 */
@Composable
fun ChoiceDialog(
    title: String,
    choices: List<Choice>,
    selected: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .widthIn(min = d(360), max = d(460))
                .clip(RoundedCornerShape(d(24)))
                .background(Brush.verticalGradient(listOf(Color(0xFF171B24), Color(0xFF0E1118))))
                .border(d(1f), Color(0x26FFFFFF), RoundedCornerShape(d(24)))
                .padding(d(26)),
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge, color = Paper.Pure, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(d(16)))
            Column(verticalArrangement = Arrangement.spacedBy(d(8))) {
                choices.forEach { choice ->
                    val active = choice.key == selected
                    ChoiceRow(choice, active) {
                        onSelect(choice.key)
                    }
                }
            }
            Spacer(Modifier.height(d(12)))
            TextButtonRow("关闭", onDismiss)
        }
        LaunchedEffect(Unit) { }
    }
}

@Composable
private fun ChoiceRow(choice: Choice, selected: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(d(12)))
            .background(
                when {
                    focused -> Color(0x1FFFFFFF)
                    selected -> Rouge.Veil
                    else -> Color(0x08FFFFFF)
                },
            )
            .border(
                d(1.2f),
                when {
                    focused -> Rouge.Bright
                    selected -> Rouge.Core.copy(0.5f)
                    else -> Color.Transparent
                },
                RoundedCornerShape(d(12)),
            )
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = d(16), vertical = d(12)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(d(18))
                .clip(CircleShape)
                .border(d(1.5f), if (selected) Rouge.Bright else Paper.Tertiary, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Box(Modifier.size(d(9)).clip(CircleShape).background(Rouge.Bright))
            }
        }
        Spacer(Modifier.width(d(14)))
        Text(choice.label, style = MaterialTheme.typography.bodyLarge, color = if (selected) Paper.Pure else Paper.Secondary)
        Spacer(Modifier.weight(1f))
        if (choice.hint != null) {
            Text(choice.hint, style = MaterialTheme.typography.bodySmall, color = Paper.Tertiary)
        }
    }
}

@Composable
private fun TextButtonRow(text: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(d(12)))
            .background(if (focused) Color(0x14FFFFFF) else Color.Transparent)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(vertical = d(11)),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = if (focused) Paper.Pure else Paper.Tertiary)
    }
}

/** 轻提示：贴底居中，自动消失，不打断焦点。 */
@Composable
fun ToastHost(message: String?, modifier: Modifier = Modifier) {
    AnimatedVisibility(
        visible = message != null,
        enter = fadeIn() + slideInVertically { it / 3 },
        exit = fadeOut() + slideOutVertically { it / 3 },
        modifier = modifier,
    ) {
        Row(
            Modifier
                .clip(RoundedCornerShape(d(999)))
                .background(Color(0xE6141820))
                .border(d(1f), Color(0x1FFFFFFF), RoundedCornerShape(d(999)))
                .padding(horizontal = d(20), vertical = d(11)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(d(6))
                    .clip(CircleShape)
                    .background(Rouge.Bright),
            )
            Spacer(Modifier.width(d(10)))
            Text(message.orEmpty(), style = MaterialTheme.typography.bodyMedium, color = Paper.Primary)
        }
    }
}

/** 全屏加载遮罩，用于切集、解析播放地址等不能立即完成的动作。 */
@Composable
fun LoadingOverlay(label: String, visible: Boolean) {
    AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut()) {
        Box(Modifier.fillMaxSize().background(Color(0x99040508)), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                PulseDots()
                Spacer(Modifier.height(d(16)))
                Text(label, style = MaterialTheme.typography.bodyMedium, color = Paper.Secondary)
            }
        }
    }
}
