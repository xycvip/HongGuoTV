// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp.ui.screens

import android.graphics.BitmapFactory
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Sms
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.hongguotv.nativeapp.ui.components.ButtonTone
import com.hongguotv.nativeapp.ui.components.PulseDots
import com.hongguotv.nativeapp.ui.components.TvButton
import com.hongguotv.nativeapp.ui.data.AccountState
import com.hongguotv.nativeapp.ui.data.LoginMode
import com.hongguotv.nativeapp.ui.theme.Gold
import com.hongguotv.nativeapp.ui.theme.Ink
import com.hongguotv.nativeapp.ui.theme.Paper
import com.hongguotv.nativeapp.ui.theme.Rouge
import com.hongguotv.nativeapp.ui.theme.Signal
import com.hongguotv.nativeapp.ui.theme.d
import com.hongguotv.nativeapp.ui.theme.ui

/**
 * 账号页：一张「票根」式卡片承载扫码与手机验证码两种登录，
 * 右侧是账号能力说明。两种模式共用同一张卡片，切换时宽度平滑变化。
 */
@Composable
fun AccountScreen(
    state: AccountState,
    onLogin: () -> Unit,
    onSignOut: () -> Unit,
    onSwitchMode: (LoginMode) -> Unit,
    onPhone: (String) -> Unit,
    onCode: (String) -> Unit,
    onSendCode: () -> Unit,
    onVerify: () -> Unit,
    onResetPhone: () -> Unit,
    modifier: Modifier = Modifier,
    entryFocus: FocusRequester? = null,
    upFocus: FocusRequester? = null,
) {
    Row(
        modifier
            .fillMaxSize()
            .padding(start = ui.gutter, end = ui.gutter, top = d(96), bottom = d(40)),
        horizontalArrangement = Arrangement.spacedBy(d(48)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LoginCard(
            state = state,
            onLogin = onLogin,
            onSwitchMode = onSwitchMode,
            onPhone = onPhone,
            onCode = onCode,
            onSendCode = onSendCode,
            onVerify = onVerify,
            onResetPhone = onResetPhone,
            entryFocus = entryFocus.takeIf { !state.signedIn },
            upFocus = upFocus,
        )

        AccountIntro(
            state = state,
            onLogin = onLogin,
            onSignOut = onSignOut,
            modifier = Modifier.weight(1f),
            entryFocus = entryFocus.takeIf { state.signedIn },
            upFocus = upFocus,
        )
    }
}

// ------------------------------------------------------------------ 左侧票根

@Composable
private fun LoginCard(
    state: AccountState,
    onLogin: () -> Unit,
    onSwitchMode: (LoginMode) -> Unit,
    onPhone: (String) -> Unit,
    onCode: (String) -> Unit,
    onSendCode: () -> Unit,
    onVerify: () -> Unit,
    onResetPhone: () -> Unit,
    entryFocus: FocusRequester? = null,
    upFocus: FocusRequester? = null,
) {
    val phoneMode = state.mode == LoginMode.PHONE
    val cardWidth by animateDpAsState(
        targetValue = if (phoneMode && !state.signedIn) d(640) else d(340),
        animationSpec = tween(200),
        label = "login-card-width",
    )
    val shape = RoundedCornerShape(d(26))
    Box(
        Modifier
            .width(cardWidth)
            .height(d(470))
            .clip(shape)
            .background(Brush.verticalGradient(listOf(Color(0xFF171B25), Color(0xFF0B0E14))))
            .border(d(1f), Color(0x1FFFFFFF), shape),
    ) {
        // 右上角一团极淡的红光，让纯色卡面有层次，又不抢内容。
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .offset(x = d(90), y = d(-110))
                .size(d(300))
                .background(
                    Brush.radialGradient(listOf(Rouge.Core.copy(alpha = 0.20f), Color.Transparent)),
                    CircleShape,
                ),
        )
        // 顶部一线鎏金：票根质感。
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(d(1.2f))
                .background(
                    Brush.horizontalGradient(
                        listOf(Color.Transparent, Gold.Core.copy(alpha = 0.55f), Color.Transparent),
                    ),
                ),
        )
        CornerMarks()

        Column(Modifier.fillMaxSize().padding(horizontal = d(26), vertical = d(22))) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(d(6))
                        .background(Rouge.Bright, CircleShape),
                )
                Spacer(Modifier.width(d(8)))
                Text(
                    "红果账号",
                    style = MaterialTheme.typography.labelSmall,
                    color = Paper.Tertiary,
                )
                Spacer(Modifier.weight(1f))
                if (!state.signedIn) {
                    ModeSwitch(state.mode, onSwitchMode, entryFocus, upFocus)
                }
            }
            Spacer(Modifier.height(d(18)))
            if (state.signedIn) {
                SignedInPane(state)
            } else if (phoneMode) {
                PhonePane(
                    state = state,
                    onPhone = onPhone,
                    onCode = onCode,
                    onSendCode = onSendCode,
                    onVerify = onVerify,
                    onResetPhone = onResetPhone,
                )
            } else {
                QrPane(state, onLogin)
            }
        }
    }
}

@Composable
private fun ModeSwitch(
    mode: LoginMode,
    onSwitchMode: (LoginMode) -> Unit,
    entryFocus: FocusRequester? = null,
    upFocus: FocusRequester? = null,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(d(6))) {
        ModeTab("扫码", mode == LoginMode.QR, entryFocus, upFocus) { onSwitchMode(LoginMode.QR) }
        ModeTab("手机号", mode == LoginMode.PHONE) { onSwitchMode(LoginMode.PHONE) }
    }
}

@Composable
private fun ModeTab(
    label: String,
    selected: Boolean,
    focusRequester: FocusRequester? = null,
    upFocus: FocusRequester? = null,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(999.dp)
    Box(
        Modifier
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .then(if (upFocus != null) Modifier.focusProperties { up = upFocus } else Modifier)
            .clip(shape)
            .background(
                when {
                    selected -> Rouge.Veil
                    focused -> Color(0x14FFFFFF)
                    else -> Color(0x08FFFFFF)
                },
            )
            .border(
                d(1.2f),
                when {
                    focused -> Rouge.Bright
                    selected -> Rouge.Core.copy(alpha = 0.45f)
                    else -> Color(0x0FFFFFFF)
                },
                shape,
            )
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = d(13), vertical = d(6)),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = when {
                focused -> Paper.Pure
                selected -> Rouge.Bright
                else -> Paper.Tertiary
            },
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

// ------------------------------------------------------------------ 扫码

@Composable
private fun QrPane(state: AccountState, onLogin: () -> Unit) {
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        val qr: ImageBitmap? = remember(state.qrBitmap) {
            state.qrBitmap?.let { bytes ->
                runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size).asImageBitmap() }.getOrNull()
            }
        }
        Box(
            Modifier
                .size(d(210))
                .clip(RoundedCornerShape(d(16)))
                .background(Color(0xFFF7F5F0)),
            contentAlignment = Alignment.Center,
        ) {
            when {
                qr != null -> Image(qr, "登录二维码", Modifier.fillMaxSize().padding(d(10)), contentScale = ContentScale.Fit)
                state.qrLoading -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    PulseDots(Rouge.Core)
                    Spacer(Modifier.height(d(14)))
                    Text("正在获取二维码", style = MaterialTheme.typography.labelSmall, color = Ink.Base)
                }
                else -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Rounded.QrCode2, null, tint = Ink.Line, modifier = Modifier.size(d(60)))
                    Spacer(Modifier.height(d(12)))
                    Text("点按下方按钮获取二维码", style = MaterialTheme.typography.labelSmall, color = Ink.SurfaceTop)
                }
            }
        }
        Spacer(Modifier.height(d(16)))
        Text(
            state.qrHint,
            style = MaterialTheme.typography.bodySmall,
            color = Paper.Secondary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(d(16)))
        TvButton(
            text = if (state.qrBitmap == null) "获取二维码" else "刷新二维码",
            onClick = onLogin,
            icon = if (state.qrBitmap == null) Icons.Rounded.QrCode2 else Icons.Rounded.Refresh,
            tone = ButtonTone.Primary,
        )
    }
}

// ------------------------------------------------------------------ 手机验证码

private enum class InputTarget { PHONE, CODE }

@Composable
private fun PhonePane(
    state: AccountState,
    onPhone: (String) -> Unit,
    onCode: (String) -> Unit,
    onSendCode: () -> Unit,
    onVerify: () -> Unit,
    onResetPhone: () -> Unit,
) {
    var target by remember { mutableStateOf(InputTarget.PHONE) }
    androidx.compose.runtime.LaunchedEffect(state.codeSent) {
        if (state.codeSent) target = InputTarget.CODE
    }

    fun type(digit: String) {
        when (target) {
            InputTarget.PHONE -> if (state.phone.length < 11) onPhone(state.phone + digit)
            InputTarget.CODE -> if (state.code.length < 6) onCode(state.code + digit)
        }
    }

    fun backspace() {
        when (target) {
            InputTarget.PHONE -> onPhone(state.phone.dropLast(1))
            InputTarget.CODE -> onCode(state.code.dropLast(1))
        }
    }

    fun clear() {
        when (target) {
            InputTarget.PHONE -> onPhone("")
            InputTarget.CODE -> onCode("")
        }
    }

    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(d(22))) {
        Column(
            Modifier.weight(1f).fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(d(10)),
        ) {
            FieldLabel("手机号", if (target == InputTarget.PHONE) "正在输入" else null)
            PhoneBoxes(
                phone = state.phone,
                active = target == InputTarget.PHONE,
                onClick = { target = InputTarget.PHONE },
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                TvButton(
                    text = when {
                        state.sending -> "发送中…"
                        state.countdown > 0 -> "重新获取 ${state.countdown}s"
                        state.codeSent -> "重新发送"
                        else -> "获取验证码"
                    },
                    onClick = onSendCode,
                    modifier = Modifier.weight(1f),
                    tone = ButtonTone.Ghost,
                    enabled = !state.sending && state.countdown == 0,
                )
                if (state.codeSent) {
                    Spacer(Modifier.width(d(8)))
                    TvButton(text = "改号码", onClick = onResetPhone, tone = ButtonTone.Quiet)
                }
            }
            Spacer(Modifier.height(d(2)))
            FieldLabel(
                "验证码",
                if (state.codeSent) "已发送至 ${state.phoneMasked}" else "先获取验证码",
            )
            CodeBoxes(
                code = state.code,
                active = target == InputTarget.CODE,
                armed = state.codeSent,
                onClick = { if (state.codeSent) target = InputTarget.CODE },
            )
            Spacer(Modifier.weight(1f))
            val hint = state.phoneError ?: state.message
            if (hint != null) {
                Text(
                    hint,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (state.phoneError != null) Gold.Core else Signal.Jade,
                    maxLines = 2,
                )
                Spacer(Modifier.height(d(2)))
            }
            TvButton(
                text = if (state.verifying) "正在登录…" else "登录",
                onClick = onVerify,
                modifier = Modifier.fillMaxWidth(),
                tone = ButtonTone.Primary,
                enabled = state.codeValid && !state.verifying,
            )
        }

        Column(Modifier.width(d(232)).fillMaxHeight()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.PhoneAndroid, null, tint = Paper.Tertiary, modifier = Modifier.size(d(14)))
                Spacer(Modifier.width(d(6)))
                Text(
                    if (target == InputTarget.PHONE) "数字键盘 · 手机号" else "数字键盘 · 验证码",
                    style = MaterialTheme.typography.labelSmall,
                    color = Paper.Tertiary,
                )
            }
            Spacer(Modifier.height(d(10)))
            NumberPad(
                onDigit = { type(it) },
                onDelete = { backspace() },
                onClear = { clear() },
            )
        }
    }
}

@Composable
private fun FieldLabel(title: String, hint: String?) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.labelMedium, color = Paper.Secondary)
        if (hint != null) {
            Spacer(Modifier.width(d(8)))
            Text(hint, style = MaterialTheme.typography.labelSmall, color = Paper.Tertiary)
        }
    }
}

/** 11 位手机号按 3-4-4 分组显示，视觉上比一长串数字更容易核对。 */
@Composable
private fun PhoneBoxes(phone: String, active: Boolean, onClick: () -> Unit) {
    val groups = listOf(phone.take(3), phone.drop(3).take(4), phone.drop(7).take(4))
    val widths = listOf(62, 92, 92)
    Row(horizontalArrangement = Arrangement.spacedBy(d(6))) {
        groups.forEachIndexed { index, value ->
            Box(
                Modifier
                    .width(d(widths[index]))
                    .height(d(50))
                    .clip(RoundedCornerShape(d(12)))
                    .background(if (active) Color(0x1AF2415A) else Color(0x0AFFFFFF))
                    .border(
                        d(1.2f),
                        if (active) Gold.Core.copy(alpha = 0.65f) else Color(0x14FFFFFF),
                        RoundedCornerShape(d(12)),
                    )
                    .clickable(onClick = onClick),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    value.chunked(1).joinToString(" "),
                    style = MaterialTheme.typography.titleMedium,
                    fontFamily = FontFamily.Monospace,
                    color = if (value.isEmpty()) Paper.Disabled else Paper.Pure,
                )
            }
        }
    }
}

@Composable
private fun CodeBoxes(code: String, active: Boolean, armed: Boolean, onClick: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(d(6))) {
        repeat(6) { index ->
            val char = code.getOrNull(index)
            val current = armed && active && index == code.length
            Box(
                Modifier
                    .width(d(36))
                    .height(d(52))
                    .clip(RoundedCornerShape(d(11)))
                    .background(
                        when {
                            current -> Color(0x1FF2415A)
                            char != null -> Color(0x12FFFFFF)
                            else -> Color(0x08FFFFFF)
                        },
                    )
                    .border(
                        d(1.2f),
                        when {
                            current -> Gold.Core.copy(alpha = 0.8f)
                            char != null -> Rouge.Core.copy(alpha = 0.35f)
                            else -> Color(0x12FFFFFF)
                        },
                        RoundedCornerShape(d(11)),
                    )
                    .clickable(enabled = armed, onClick = onClick),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    char?.toString().orEmpty(),
                    style = MaterialTheme.typography.titleMedium,
                    fontFamily = FontFamily.Monospace,
                    color = Paper.Pure,
                )
            }
        }
    }
}

@Composable
private fun NumberPad(onDigit: (String) -> Unit, onDelete: () -> Unit, onClear: () -> Unit) {
    val rows = listOf(
        listOf("1", "2", "3"),
        listOf("4", "5", "6"),
        listOf("7", "8", "9"),
        listOf("清空", "0", "删除"),
    )
    Column(verticalArrangement = Arrangement.spacedBy(d(8))) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(d(8))) {
                row.forEach { key ->
                    PadKey(
                        label = key,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            when (key) {
                                "删除" -> onDelete()
                                "清空" -> onClear()
                                else -> onDigit(key)
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun PadKey(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (focused) 1.05f else 1f, tween(120), label = "pad-key")
    Box(
        modifier
            .height(d(46))
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(d(12)))
            .background(if (focused) Rouge.Veil else Color(0x0AFFFFFF))
            .border(
                d(1.2f),
                if (focused) Rouge.Bright else Color(0x10FFFFFF),
                RoundedCornerShape(d(12)),
            )
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = if (label.length > 1) MaterialTheme.typography.labelSmall else MaterialTheme.typography.titleMedium,
            fontFamily = if (label.length == 1) FontFamily.Monospace else FontFamily.Default,
            color = if (focused) Paper.Pure else Paper.Secondary,
            fontWeight = if (label.length == 1) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

// ------------------------------------------------------------------ 已登录

@Composable
private fun SignedInPane(state: AccountState) {
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier
                .size(d(104))
                .clip(CircleShape)
                .background(Brush.linearGradient(listOf(Rouge.Core, Rouge.Deep)))
                .border(d(2f), Gold.Core.copy(0.5f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                state.nickname.take(1).ifBlank { "红" },
                style = MaterialTheme.typography.displaySmall,
                color = Color.White,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.height(d(20)))
        Text(state.nickname.ifBlank { "红果用户" }, style = MaterialTheme.typography.headlineSmall, color = Paper.Pure)
        Spacer(Modifier.height(d(6)))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.CheckCircle, null, tint = Signal.Jade, modifier = Modifier.size(d(15)))
            Spacer(Modifier.width(d(6)))
            Text("已登录 · 云端同步中", style = MaterialTheme.typography.labelSmall, color = Signal.Jade)
        }
        Spacer(Modifier.height(d(26)))
        Row(horizontalArrangement = Arrangement.spacedBy(d(26))) {
            StatBlock("收藏", state.cloudFavorites.toString())
            StatBlock("历史", state.cloudHistory.toString())
        }
    }
}

// ------------------------------------------------------------------ 右侧说明

@Composable
private fun AccountIntro(
    state: AccountState,
    onLogin: () -> Unit,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier,
    entryFocus: FocusRequester? = null,
    upFocus: FocusRequester? = null,
) {
    val phoneMode = state.mode == LoginMode.PHONE
    Column(modifier, verticalArrangement = Arrangement.spacedBy(d(16))) {
        Text("红果账号", style = MaterialTheme.typography.displaySmall, color = Paper.Pure, fontWeight = FontWeight.Bold)
        Text(
            when {
                state.signedIn -> "云端数据已与本机合并，换设备也能接着看。"
                phoneMode -> "没有手机在手上也能登录：输手机号收一条短信，验证码一填就好。"
                else -> "扫码登录后，收藏、观看进度和预约会跟随账号，换一台电视也能无缝续上。"
            },
            style = MaterialTheme.typography.bodyLarge,
            color = Paper.Secondary,
            modifier = Modifier.fillMaxWidth(0.92f),
        )
        Spacer(Modifier.height(d(2)))
        if (phoneMode && !state.signedIn) {
            FeatureRow(Icons.Rounded.Sms, "短信验证", "手机号 + 验证码，不用另一台设备")
            FeatureRow(Icons.Rounded.Lock, "不存密码", "只校验验证码，本机不保存任何密码")
            FeatureRow(Icons.Rounded.CloudSync, "登录即同步", "收藏、历史与预约自动并入云端")
        } else {
            FeatureRow(Icons.Rounded.CloudSync, "收藏同步", "剧集与观看进度自动同步到红果云端")
            FeatureRow(Icons.Rounded.PlayCircle, "跨设备续播", "在手机看到一半，电视上接着播")
            FeatureRow(Icons.Rounded.QrCode2, "无需输入密码", "用红果 App 扫码确认即可登录")
        }
        Spacer(Modifier.height(d(10)))
        if (state.signedIn) {
            TvButton(
                "退出登录",
                onSignOut,
                tone = ButtonTone.Ghost,
                focusRequester = entryFocus,
                upFocus = upFocus,
            )
        } else if (!phoneMode) {
            TvButton("扫码登录", onLogin, tone = ButtonTone.Primary)
        }
        state.message?.takeIf { it.isNotBlank() && !state.signedIn && !phoneMode }?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = Gold.Core)
        }
        Spacer(Modifier.height(d(6)))
        Text(
            "登录仅用于同步官方账号数据，不会上传本机任何其他信息。",
            style = MaterialTheme.typography.labelSmall,
            color = Paper.Tertiary,
        )
    }
}

// ------------------------------------------------------------------ 小组件

@Composable
private fun CornerMarks() {
    val color = Color(0x33E7BE7C)
    val len = d(18)
    val thick = d(1.4f)
    // 四个角的 L 形细线
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.align(Alignment.TopStart).width(len).height(thick).background(color))
        Box(Modifier.align(Alignment.TopStart).width(thick).height(len).background(color))
        Box(Modifier.align(Alignment.TopEnd).width(len).height(thick).background(color))
        Box(Modifier.align(Alignment.TopEnd).width(thick).height(len).background(color))
        Box(Modifier.align(Alignment.BottomStart).width(len).height(thick).background(color))
        Box(Modifier.align(Alignment.BottomStart).width(thick).height(len).background(color))
        Box(Modifier.align(Alignment.BottomEnd).width(len).height(thick).background(color))
        Box(Modifier.align(Alignment.BottomEnd).width(thick).height(len).background(color))
    }
}

@Composable
private fun StatBlock(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.headlineSmall, color = Gold.Bright, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(d(2)))
        Text(label, style = MaterialTheme.typography.labelSmall, color = Paper.Tertiary)
    }
}

@Composable
private fun FeatureRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, desc: String) {
    Row(
        Modifier
            .fillMaxWidth(0.86f)
            .clip(RoundedCornerShape(d(14)))
            .background(Color(0x06FFFFFF))
            .padding(horizontal = d(18), vertical = d(14)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(d(38))
                .clip(RoundedCornerShape(d(10)))
                .background(Rouge.Veil),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = Rouge.Bright, modifier = Modifier.size(d(20)))
        }
        Spacer(Modifier.width(d(14)))
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall, color = Paper.Primary)
            Spacer(Modifier.height(d(2)))
            Text(desc, style = MaterialTheme.typography.bodySmall, color = Paper.Tertiary)
        }
    }
}
