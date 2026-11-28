// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp.ui.screens

import android.graphics.BitmapFactory
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Sms
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.hongguotv.core.BackupProgress
import com.hongguotv.core.Series
import com.hongguotv.core.SyncMode
import com.hongguotv.nativeapp.ui.components.ButtonTone
import com.hongguotv.nativeapp.ui.components.FocusSurface
import com.hongguotv.nativeapp.ui.components.PosterCard
import com.hongguotv.nativeapp.ui.components.PulseDots
import com.hongguotv.nativeapp.ui.components.TvButton
import com.hongguotv.nativeapp.ui.components.tryRequestFocus
import com.hongguotv.nativeapp.ui.data.AccountState
import com.hongguotv.nativeapp.ui.data.LibraryState
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
    library: LibraryState,
    onLogin: () -> Unit,
    onSignOut: () -> Unit,
    onSwitchMode: (LoginMode) -> Unit,
    onPhone: (String) -> Unit,
    onCode: (String) -> Unit,
    onSendCode: () -> Unit,
    onVerify: () -> Unit,
    onResetPhone: () -> Unit,
    onCountryCode: (String) -> Unit,
    onOpenLibrary: (LibraryTab) -> Unit,
    onDetail: (Series) -> Unit,
    onResume: (Series) -> Unit,
    onToggleSync: (Boolean, SyncMode) -> Unit,
    modifier: Modifier = Modifier,
    entryFocus: FocusRequester? = null,
    upFocus: FocusRequester? = null,
) {
    if (state.signedIn) {
        AccountCenter(
            state = state,
            library = library,
            onSignOut = onSignOut,
            onOpenLibrary = onOpenLibrary,
            onDetail = onDetail,
            onResume = onResume,
            onToggleSync = onToggleSync,
            modifier = modifier,
            entryFocus = entryFocus,
            upFocus = upFocus,
        )
        return
    }
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
            onCountryCode = onCountryCode,
            entryFocus = entryFocus,
            upFocus = upFocus,
        )

        AccountIntro(
            state = state,
            onSignOut = onSignOut,
            modifier = Modifier.weight(1f),
            entryFocus = null,
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
    onCountryCode: (String) -> Unit,
    entryFocus: FocusRequester? = null,
    upFocus: FocusRequester? = null,
) {
    val phoneMode = state.mode == LoginMode.PHONE
    val cardWidth by animateDpAsState(
        targetValue = if (phoneMode) d(640) else d(340),
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
                    // entryFocus 全局只能挂一个组件：手机号模式让给号码框，扫码模式归模式切换键。
                    ModeSwitch(state.mode, onSwitchMode, entryFocus.takeIf { !phoneMode }, upFocus)
                }
            }
            Spacer(Modifier.height(d(18)))
            if (phoneMode) {
                PhonePane(
                    state = state,
                    onPhone = onPhone,
                    onCode = onCode,
                    onSendCode = onSendCode,
                    onVerify = onVerify,
                    onResetPhone = onResetPhone,
                    onCountryCode = onCountryCode,
                    entryFocus = entryFocus.takeIf { phoneMode },
                    upFocus = upFocus,
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
        ModeTab("扫码登录", mode == LoginMode.QR, entryFocus, upFocus) { onSwitchMode(LoginMode.QR) }
        ModeTab("手机验证", mode == LoginMode.PHONE) { onSwitchMode(LoginMode.PHONE) }
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
    onCountryCode: (String) -> Unit,
    entryFocus: FocusRequester? = null,
    upFocus: FocusRequester? = null,
) {
    var target by remember { mutableStateOf(InputTarget.PHONE) }
    var showCountryPicker by remember { mutableStateOf(false) }
    val codeFocus = remember { FocusRequester() }
    androidx.compose.runtime.LaunchedEffect(state.codeSent) {
        if (state.codeSent) {
            target = InputTarget.CODE
            // 验证码框是随 codeSent 一起从 disabled 变 enabled 的，同一帧里它还不一定
            // attach 到焦点树，单次 requestFocus 会静默失败。多帧重试直到拿上焦点。
            repeat(12) {
                if (codeFocus.tryRequestFocus()) return@LaunchedEffect
                withFrameNanos { }
            }
        }
    }
    // 进入手机号模式时焦点直接落在号码框，遥控器无需再按方向键。
    androidx.compose.runtime.LaunchedEffect(Unit) {
        runCatching { entryFocus?.requestFocus() }
    }

    fun type(digit: String) {
        when (target) {
            InputTarget.PHONE -> {
                val max = if (state.countryCode == "+86") 11 else 15
                if (state.phone.length < max) onPhone(state.phone + digit)
            }
            InputTarget.CODE -> if (state.code.length < 8) onCode(state.code + digit)
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
            InputField(
                icon = Icons.Rounded.PhoneAndroid,
                label = "手机号",
                value = state.phone,
                placeholder = "请输入手机号",
                active = target == InputTarget.PHONE,
                modifier = Modifier.fillMaxWidth(),
                onClick = { target = InputTarget.PHONE },
                focusRequester = if (target == InputTarget.PHONE) entryFocus else null,
                upFocus = upFocus,
                valueLeading = {
                    CountryCodeChip(code = state.countryCode, onClick = { showCountryPicker = true })
                },
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
            Spacer(Modifier.height(d(6)))
            InputField(
                icon = Icons.Rounded.Sms,
                label = if (state.codeSent) "验证码 · 已发送至 ${state.phoneMasked}" else "验证码",
                value = state.code,
                placeholder = if (state.codeSent) "请输入 4-8 位验证码" else "先获取验证码",
                active = target == InputTarget.CODE && state.codeSent,
                enabled = state.codeSent,
                modifier = Modifier.fillMaxWidth(),
                onClick = { if (state.codeSent) target = InputTarget.CODE },
                focusRequester = codeFocus,
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

    // 区号选择：点击手机号框左侧的 +86 弹出，从列表里直接选目标区号。
    if (showCountryPicker) {
        CountryCodePicker(
            current = state.countryCode,
            onPick = { code ->
                showCountryPicker = false
                onCountryCode(code)
            },
            onDismiss = { showCountryPicker = false },
        )
    }
}

/** 区号选择弹窗：常用地区列表，当前项高亮，遥控器逐项聚焦。 */
@Composable
private fun CountryCodePicker(
    current: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val options = listOf(
        "+86" to "中国大陆",
        "+852" to "中国香港",
        "+886" to "中国台湾",
        "+1" to "美国 / 加拿大",
        "+44" to "英国",
        "+81" to "日本",
        "+82" to "韩国",
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF141821),
        title = { Text("选择区号", color = Paper.Pure, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(d(6))) {
                options.forEach { (code, name) ->
                    val selected = code == current
                    TvButton(
                        text = "$code  $name",
                        tone = if (selected) ButtonTone.Primary else ButtonTone.Ghost,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { onPick(code) },
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TvButton(text = "取消", tone = ButtonTone.Quiet, onClick = onDismiss)
        },
    )
}

/** 区号 chip：手机号框左侧的 +86，点击弹出区号列表。 */
@Composable
private fun CountryCodeChip(code: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Box(
        Modifier
            .height(d(50))
            .clip(RoundedCornerShape(d(12)))
            .background(if (focused) Color(0x1AF2415A) else Color(0x0AFFFFFF))
            .border(
                d(1.2f),
                if (focused) Gold.Core.copy(alpha = 0.8f) else Color(0x14FFFFFF),
                RoundedCornerShape(d(12)),
            )
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = d(12)),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(code, style = MaterialTheme.typography.titleMedium, color = Paper.Pure, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.width(d(4)))
            Text("▾", style = MaterialTheme.typography.labelSmall, color = Paper.Tertiary)
        }
    }
}

/**
 * 大气长框：标签提到框外上方，框内是图标槽 + 大号值。
 * 手机号与验证码各用一枚、彼此独立；值长度不固定（验证码 4-8 位 / 手机号 5-15 位），
 * 聚焦态给红金描边与光晕。数字连续显示（不插空格，保证 11 位手机号完整可见），
 * 光标闪烁并跟随在数字末尾，即最新输入位之后。
 */
@Composable
private fun InputField(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
    placeholder: String,
    active: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null,
    upFocus: FocusRequester? = null,
    valueLeading: @Composable (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val highlighted = active && enabled
    val shape = RoundedCornerShape(d(14))
    val scale by animateFloatAsState(if (focused) 1.01f else 1f, tween(140), label = "field-scale")
    Column(modifier) {
        // 标签移到框外：框内只留图标 + 大号数字，视觉更干净。
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = if (highlighted) Gold.Core else Paper.Tertiary,
            modifier = Modifier.padding(start = d(4), bottom = d(8)),
        )
        Box(
            Modifier
                .fillMaxWidth()
                .graphicsLayer { scaleX = scale; scaleY = scale }
                .height(d(64))
                .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                .then(if (upFocus != null) Modifier.focusProperties { up = upFocus } else Modifier)
                .clip(shape)
                .background(
                    Brush.linearGradient(
                        listOf(
                            if (highlighted) Color(0xFF1A1418) else Ink.Surface,
                            if (highlighted) Color(0xFF120F16) else Ink.Deep,
                        ),
                    ),
                )
                .border(
                    d(1.4f),
                    when {
                        focused -> Rouge.Bright
                        highlighted -> Gold.Core.copy(alpha = 0.7f)
                        else -> Ink.Line
                    },
                    shape,
                )
                .clickable(enabled = enabled, interactionSource = interaction, indication = null, onClick = onClick)
                .padding(horizontal = d(18)),
            contentAlignment = Alignment.CenterStart,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 左侧图标槽
                Box(
                    Modifier
                        .size(d(44))
                        .clip(RoundedCornerShape(d(12)))
                        .background(if (highlighted) Rouge.Veil else Color(0x0FFFFFFF))
                        .border(d(1f), if (highlighted) Rouge.Core.copy(alpha = 0.5f) else Color(0x14FFFFFF), RoundedCornerShape(d(12))),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        icon, null,
                        tint = if (highlighted) Rouge.Bright else Paper.Tertiary,
                        modifier = Modifier.size(d(22)),
                    )
                }
                Spacer(Modifier.width(d(16)))
                // 中间：区号 / 值 / 光标
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    if (valueLeading != null) {
                        valueLeading()
                        Spacer(Modifier.width(d(10)))
                    }
                    if (value.isEmpty()) {
                        Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = Paper.Disabled)
                    } else {
                        // 连续显示，不在每位之间插空格：11 位手机号加空格会撑到 20+ 字符，
                        // 在有限宽度里被 maxLines=1 截断，看不到完整号码。
                        Text(
                            value,
                            style = MaterialTheme.typography.titleLarge,
                            fontFamily = FontFamily.Monospace,
                            color = Paper.Pure,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                    // 光标跟随在数字末尾并闪烁（等宽连续数字下，末尾即最新输入位之后）。
                    if (highlighted) {
                        Spacer(Modifier.width(d(3)))
                        BlinkingCaret()
                    }
                }
                // 右侧槽：手机号框放区号切换
                if (trailing != null) {
                    Spacer(Modifier.width(d(12)))
                    trailing()
                }
            }
        }
    }
}

/** 闪烁光标：1f→0f 无限往返，半周期 500ms，与系统输入框的呼吸节奏一致。 */
@Composable
private fun BlinkingCaret() {
    val alpha by rememberInfiniteTransition(label = "caret").animateFloat(
        initialValue = 1f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(500), RepeatMode.Reverse),
        label = "caret-alpha",
    )
    Box(
        Modifier
            .width(d(2))
            .height(d(24))
            .background(Gold.Bright.copy(alpha = alpha)),
    )
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

// ------------------------------------------------------------------ 云同步开关与状态

/** 同步方式单选项：整行可聚焦点击，右侧圆点表示选中，选中态走鎏金描边。 */
@Composable
private fun SyncModeChoice(
    title: String,
    desc: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    FocusSurface(
        onClick = onClick,
        shape = RoundedCornerShape(d(12)),
        focusScale = 1.01f,
        borderWidth = d(1f),
        modifier = Modifier.fillMaxWidth(),
    ) { _ ->
        Row(
            Modifier.fillMaxWidth().padding(horizontal = d(14), vertical = d(10)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(d(18))
                    .clip(CircleShape)
                    .background(if (selected) Signal.Jade else Color(0x1AFFFFFF)),
                contentAlignment = Alignment.Center,
            ) {
                if (selected) Box(Modifier.size(d(8)).background(Paper.Pure, CircleShape))
            }
            Spacer(Modifier.width(d(12)))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (selected) Paper.Pure else Paper.Primary,
                )
                Spacer(Modifier.height(d(3)))
                Text(desc, style = MaterialTheme.typography.labelMedium, color = Paper.Tertiary)
            }
        }
    }
}

/**
 * 云同步开关：整行可聚焦点击，右侧是一枚滑动开关。
 * 关闭态用低调灰蓝轨道，开启态走鎏金轨道 + 玉色滑块，与夜色鎏金主题一致。
 */
@Composable
private fun SyncSwitchEntry(
    enabled: Boolean,
    busy: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FocusSurface(
        onClick = onClick,
        enabled = !busy,
        shape = RoundedCornerShape(d(16)),
        focusScale = 1.02f,
        borderWidth = d(1.2f),
        modifier = modifier.fillMaxWidth(),
    ) { focused ->
        Row(
            Modifier.fillMaxWidth().height(d(74)).padding(horizontal = d(16)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(d(42))
                    .clip(RoundedCornerShape(d(12)))
                    .background(if (enabled) Gold.Core.copy(alpha = 0.16f) else Color(0x0AFFFFFF)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.CloudSync, null,
                    tint = if (enabled) Gold.Bright else Paper.Secondary,
                    modifier = Modifier.size(d(20)),
                )
            }
            Spacer(Modifier.width(d(14)))
            Column(Modifier.weight(1f)) {
                Text("云同步", style = MaterialTheme.typography.titleSmall, color = Paper.Primary)
                Spacer(Modifier.height(d(3)))
                Text(
                    if (busy) "正在同步…" else if (enabled) "收藏与进度自动跟随账号" else "已关闭 · 本机与账号隔离",
                    style = MaterialTheme.typography.labelSmall,
                    color = Paper.Tertiary,
                    maxLines = 1,
                )
            }
            Spacer(Modifier.width(d(10)))
            val track by animateDpAsState(targetValue = if (enabled) d(46) else d(40), animationSpec = tween(220))
            val slide by animateDpAsState(targetValue = if (enabled) d(22) else d(2), animationSpec = tween(220))
            Box(
                Modifier
                    .width(track)
                    .height(d(24))
                    .clip(RoundedCornerShape(d(12)))
                    .background(if (enabled) Gold.Core else Color(0x2A303C)),
            ) {
                Box(
                    Modifier
                        .padding(start = slide)
                        .size(d(20))
                        .clip(CircleShape)
                        .background(if (enabled) Signal.Jade else Paper.Tertiary)
                        .align(Alignment.CenterStart),
                )
            }
        }
    }
}

/** 右侧内容区顶部的同步状态条：常驻显示开关语义与最近一次同步结果。 */
@Composable
private fun SyncStatusBar(enabled: Boolean, busy: Boolean, status: String, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(d(14))
    Row(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Brush.horizontalGradient(listOf(Color(0xFF171B25), Color(0xFF0E1118))))
            .border(d(1f), Color(0x14FFFFFF), shape)
            .padding(horizontal = d(18), vertical = d(12)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(d(8))
                .clip(CircleShape)
                .background(if (enabled) Signal.Jade else Paper.Tertiary),
        )
        Spacer(Modifier.width(d(12)))
        Text(
            text = if (busy) "云同步进行中…" else if (status.isNotBlank()) status
            else if (enabled) "云同步已开启 · 本机与账号数据保持一致" else "云同步已关闭 · 本机数据独立保存",
            style = MaterialTheme.typography.bodyMedium,
            color = if (enabled) Paper.Primary else Paper.Secondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.weight(1f))
        if (busy) PulseDots(Gold.Bright)
    }
}

/**
 * 登录后的账号中心：左侧身份卡 + 收藏/历史入口 + 退出，右侧直接铺开「继续观看」与「我的收藏」。
 * 数据全部来自本地库 [LibraryState]，入口点击后跳追剧页对应分区，不再只显示两个干巴巴的数字。
 */
@Composable
private fun AccountCenter(
    state: AccountState,
    library: LibraryState,
    onSignOut: () -> Unit,
    onOpenLibrary: (LibraryTab) -> Unit,
    onDetail: (Series) -> Unit,
    onResume: (Series) -> Unit,
    onToggleSync: (Boolean, SyncMode) -> Unit,
    modifier: Modifier = Modifier,
    entryFocus: FocusRequester? = null,
    upFocus: FocusRequester? = null,
) {
    // 开启同步前弹确认：合并（默认）/ 以账号为准 / 上传覆盖本机。
    var showSyncConfirm by remember { mutableStateOf(false) }
    var selectedSyncMode by remember { mutableStateOf(SyncMode.MERGE) }
    Row(
        modifier
            .fillMaxSize()
            .padding(start = ui.gutter, end = ui.gutter, top = d(92), bottom = d(34)),
        horizontalArrangement = Arrangement.spacedBy(d(28)),
    ) {
        Column(
            Modifier.width(d(384)).fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(d(12)),
        ) {
            IdentityCard(state, Modifier.fillMaxWidth())
            AccountStatEntry(
                icon = Icons.Rounded.FavoriteBorder,
                title = "我的收藏",
                caption = "追的剧都在这里",
                count = library.favorites.size,
                unit = "部",
                onClick = { onOpenLibrary(LibraryTab.FAVORITES) },
                focusRequester = entryFocus,
                upFocus = upFocus,
            )
            AccountStatEntry(
                icon = Icons.Rounded.History,
                title = "观看历史",
                caption = "从上次看到的位置接着看",
                count = library.history.size,
                unit = "部",
                onClick = { onOpenLibrary(LibraryTab.HISTORY) },
            )
            AccountStatEntry(
                icon = Icons.Rounded.BookmarkBorder,
                title = "我的预约",
                caption = "手机端预约的作品，自动同步到这里",
                count = library.reservations.size,
                unit = "部",
                onClick = { onOpenLibrary(LibraryTab.RESERVATIONS) },
            )
            SyncSwitchEntry(
                enabled = state.syncEnabled,
                busy = state.syncBusy,
                onClick = {
                    if (state.syncEnabled) onToggleSync(false, SyncMode.OFF) else showSyncConfirm = true
                },
            )
            Spacer(Modifier.weight(1f))
            TvButton(
                text = "退出登录",
                onClick = onSignOut,
                tone = ButtonTone.Ghost,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Column(
            Modifier.weight(1f).fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(d(16)),
        ) {
            SyncStatusBar(enabled = state.syncEnabled, busy = state.syncBusy, status = state.syncStatus)
            AccountShelf(
                title = "继续观看",
                caption = if (library.history.isEmpty()) "还没有播放记录" else "共 ${library.history.size} 部",
                empty = library.history.isEmpty(),
                emptyTitle = "还没有观看记录",
                emptyHint = "随便点开一部剧，进度会自动记在这里",
            ) {
                items(library.history.take(10), key = { it.series.id }) { progress ->
                    PosterCard(
                        series = progress.series,
                        onClick = { onResume(progress.series) },
                        width = d(150),
                        progress = if (progress.duration > 0) {
                            (progress.position.toFloat() / progress.duration).coerceIn(0f, 1f)
                        } else {
                            null
                        },
                        subtitle = "看到第 ${progress.episodeIndex + 1} 集",
                        completed = progress.completed,
                    )
                }
            }
            AccountShelf(
                title = "我的收藏",
                caption = if (library.favorites.isEmpty()) "还没有收藏" else "共 ${library.favorites.size} 部",
                empty = library.favorites.isEmpty(),
                emptyTitle = "还没有收藏",
                emptyHint = "在详情页按「加入收藏」，更新会第一时间出现",
            ) {
                items(library.favorites.take(10), key = { it.id }) { series ->
                    PosterCard(
                        series = series,
                        onClick = { onDetail(series) },
                        width = d(128),
                        subtitle = library.favoriteLabels[series.id],
                        completed = series.id in library.completedIds,
                    )
                }
            }
        }
    }

    if (showSyncConfirm) {
        val localCount = library.favorites.size + library.history.size
        val cloudCount = state.cloudFavorites + state.cloudHistory
        AlertDialog(
            onDismissRequest = { showSyncConfirm = false },
            containerColor = Color(0xFF141821),
            title = {
                Text("开启云同步", color = Paper.Pure, fontWeight = FontWeight.Bold)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(d(10))) {
                    Text(
                        "本机 $localCount 条记录 · 账号 $cloudCount 条 · 两边都有时按最新时间取新",
                        color = Paper.Secondary,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text("选择同步方式：", color = Paper.Tertiary, style = MaterialTheme.typography.labelMedium)
                    SyncModeChoice(
                        title = "合并（推荐）",
                        desc = "两边取并集，冲突按最新时间取新；本机新变化会自动上传",
                        selected = selectedSyncMode == SyncMode.MERGE,
                        onClick = { selectedSyncMode = SyncMode.MERGE },
                    )
                    SyncModeChoice(
                        title = "丢弃本机，以账号为准",
                        desc = "本机收藏与历史被账号内容替换，本机独有的记录会被丢弃",
                        selected = selectedSyncMode == SyncMode.CLOUD_WINS,
                        onClick = { selectedSyncMode = SyncMode.CLOUD_WINS },
                    )
                    SyncModeChoice(
                        title = "上传本机覆盖账号",
                        desc = "把本机收藏与历史上传到账号，账号多出的记录会被移除",
                        selected = selectedSyncMode == SyncMode.LOCAL_WINS,
                        onClick = { selectedSyncMode = SyncMode.LOCAL_WINS },
                    )
                }
            },
            confirmButton = {
                TvButton(
                    text = "确认开启",
                    tone = ButtonTone.Primary,
                    onClick = {
                        showSyncConfirm = false
                        onToggleSync(true, selectedSyncMode)
                    },
                )
            },
            dismissButton = {
                TvButton(
                    text = "取消",
                    tone = ButtonTone.Ghost,
                    onClick = { showSyncConfirm = false },
                )
            },
        )
    }
}

/** 身份卡：真实头像（失败回落昵称首字）、昵称、同步状态，沿用票根的鎏金细线与红光。 */
@Composable
private fun IdentityCard(state: AccountState, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(d(22))
    Box(
        modifier
            .clip(shape)
            .background(Brush.verticalGradient(listOf(Color(0xFF171B25), Color(0xFF0B0E14))))
            .border(d(1f), Color(0x1FFFFFFF), shape),
    ) {
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .offset(x = d(70), y = d(-90))
                .size(d(220))
                .background(
                    Brush.radialGradient(listOf(Rouge.Core.copy(alpha = 0.22f), Color.Transparent)),
                    CircleShape,
                ),
        )
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
        Column(Modifier.padding(horizontal = d(22), vertical = d(20))) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(state.avatar, state.nickname, d(72))
                Spacer(Modifier.width(d(16)))
                Column(Modifier.weight(1f)) {
                    Text(
                        state.nickname.ifBlank { "红果用户" },
                        style = MaterialTheme.typography.headlineSmall,
                        color = Paper.Pure,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(d(7)))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(d(7)).background(Signal.Jade, CircleShape))
                        Spacer(Modifier.width(d(6)))
                        Text(
                            if (state.busy) "正在同步云端数据…" else "已登录 · 数据已同步",
                            style = MaterialTheme.typography.labelSmall,
                            color = Signal.Jade,
                        )
                    }
                }
            }
            Spacer(Modifier.height(d(16)))
            Box(Modifier.fillMaxWidth().height(d(1f)).background(Color(0x14FFFFFF)))
            Spacer(Modifier.height(d(12)))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.CloudSync, null, tint = Gold.Core, modifier = Modifier.size(d(14)))
                Spacer(Modifier.width(d(6)))
                Text(
                    "红果账号 · 收藏与进度随账号走，换设备也能接着看",
                    style = MaterialTheme.typography.labelSmall,
                    color = Paper.Tertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** 圆形头像：底层首字兜底，有真实头像 URL 时叠在上面，加载失败自然露出首字。 */
@Composable
private fun Avatar(url: String, nickname: String, size: androidx.compose.ui.unit.Dp) {
    val context = LocalContext.current
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(Brush.linearGradient(listOf(Rouge.Core, Rouge.Deep)))
            .border(d(2f), Gold.Core.copy(alpha = 0.5f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            nickname.take(1).ifBlank { "红" },
            style = MaterialTheme.typography.headlineMedium,
            color = Color.White,
            fontWeight = FontWeight.Bold,
        )
        if (url.isNotBlank()) {
            AsyncImage(
                model = ImageRequest.Builder(context).data(url).crossfade(true).build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** 数据入口行：图标 + 标题 + 说明 + 金色数字 + 箭头，整行可聚焦点击。 */
@Composable
private fun AccountStatEntry(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    caption: String,
    count: Int,
    unit: String,
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null,
    upFocus: FocusRequester? = null,
) {
    FocusSurface(
        onClick = onClick,
        shape = RoundedCornerShape(d(16)),
        focusScale = 1.02f,
        borderWidth = d(1.2f),
        focusRequester = focusRequester,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (upFocus != null) Modifier.focusProperties { up = upFocus } else Modifier),
    ) { focused ->
        Row(
            Modifier.fillMaxWidth().height(d(74)).padding(horizontal = d(16)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(d(42))
                    .clip(RoundedCornerShape(d(12)))
                    .background(if (focused) Rouge.Veil else Color(0x0AFFFFFF)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    icon, null,
                    tint = if (focused) Rouge.Bright else Paper.Secondary,
                    modifier = Modifier.size(d(20)),
                )
            }
            Spacer(Modifier.width(d(14)))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = Paper.Primary)
                Spacer(Modifier.height(d(3)))
                Text(caption, style = MaterialTheme.typography.labelSmall, color = Paper.Tertiary, maxLines = 1)
            }
            Text(
                count.toString(),
                style = MaterialTheme.typography.headlineSmall,
                color = Gold.Bright,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.width(d(3)))
            Text(unit, style = MaterialTheme.typography.labelSmall, color = Paper.Tertiary)
            Spacer(Modifier.width(d(8)))
            Icon(
                Icons.Rounded.ChevronRight, null,
                tint = if (focused) Paper.Pure else Paper.Tertiary,
                modifier = Modifier.size(d(18)),
            )
        }
    }
}

/** 右侧内容架：标题 + 说明 + 横向海报，空数据时给一张引导卡。 */
@Composable
private fun AccountShelf(
    title: String,
    caption: String,
    empty: Boolean,
    emptyTitle: String,
    emptyHint: String,
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(d(10))) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = Paper.Pure,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.width(d(10)))
            Text(caption, style = MaterialTheme.typography.labelSmall, color = Paper.Tertiary)
        }
        if (empty) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(d(140))
                    .clip(RoundedCornerShape(d(16)))
                    .background(Color(0x05FFFFFF))
                    .border(d(1f), Color(0x14FFFFFF), RoundedCornerShape(d(16)))
                    .padding(horizontal = d(24)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(d(44)).clip(CircleShape).background(Rouge.Veil),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.BookmarkBorder, null, tint = Rouge.Bright, modifier = Modifier.size(d(20)))
                }
                Spacer(Modifier.width(d(16)))
                Column {
                    Text(emptyTitle, style = MaterialTheme.typography.titleSmall, color = Paper.Secondary)
                    Spacer(Modifier.height(d(4)))
                    Text(emptyHint, style = MaterialTheme.typography.labelSmall, color = Paper.Tertiary)
                }
            }
        } else {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(d(14))) { content() }
        }
    }
}

// ------------------------------------------------------------------ 右侧说明

@Composable
private fun AccountIntro(
    state: AccountState,
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
                else -> "非官方客户端，登录可能会导致账号异常风险，请谨慎使用"
            },
            style = MaterialTheme.typography.bodyLarge,
            // 未登录时这句是风险提示，用警示金突出；已登录时是普通状态说明，用常规色。
            color = if (state.signedIn) Paper.Secondary else Gold.Core,
            modifier = Modifier.fillMaxWidth(0.92f),
        )
        Spacer(Modifier.height(d(2)))
        if (phoneMode && !state.signedIn) {
            FeatureRow(Icons.Rounded.CloudSync, "数据同步", "剧集与观看进度可以同步到红果云端")
            FeatureRow(Icons.Rounded.PlayCircle, "跨设备续播", "在手机看到一半，电视上接着播")
            FeatureRow(Icons.Rounded.Sms, "无需输入密码", "手机号收条短信验证码，不用记密码")
        } else {
            FeatureRow(Icons.Rounded.CloudSync, "数据同步", "剧集与观看进度可以同步到红果云端")
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
