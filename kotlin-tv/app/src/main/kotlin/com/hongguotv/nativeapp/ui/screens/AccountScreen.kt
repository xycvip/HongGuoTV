// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp.ui.screens

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.hongguotv.nativeapp.ui.components.ButtonTone
import com.hongguotv.nativeapp.ui.components.PulseDots
import com.hongguotv.nativeapp.ui.components.TvButton
import com.hongguotv.nativeapp.ui.data.AccountState
import com.hongguotv.nativeapp.ui.theme.Gold
import com.hongguotv.nativeapp.ui.theme.Ink
import com.hongguotv.nativeapp.ui.theme.Paper
import com.hongguotv.nativeapp.ui.theme.Rouge
import com.hongguotv.nativeapp.ui.theme.Signal
import com.hongguotv.nativeapp.ui.theme.d
import com.hongguotv.nativeapp.ui.theme.ui

@Composable
fun AccountScreen(
    state: AccountState,
    onLogin: () -> Unit,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxSize()
            .padding(start = ui.gutter, end = ui.gutter, top = d(96), bottom = d(40)),
        horizontalArrangement = Arrangement.spacedBy(d(52)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 左侧：二维码 / 头像
        Box(
            Modifier
                .width(d(330))
                .height(d(400))
                .clip(RoundedCornerShape(d(26)))
                .background(Brush.verticalGradient(listOf(Color(0xFF161A23), Color(0xFF0C0F15))))
                .border(d(1f), Color(0x1FFFFFFF), RoundedCornerShape(d(26)))
                .padding(d(22)),
        ) {
            // 四角装饰线，像一张被灯光照亮的票根
            CornerMarks()
            if (state.signedIn) {
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
            } else {
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
                            .size(d(228))
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
                                Icon(Icons.Rounded.QrCode2, null, tint = Ink.Line, modifier = Modifier.size(d(64)))
                                Spacer(Modifier.height(d(12)))
                                Text("点按下方按钮获取二维码", style = MaterialTheme.typography.labelSmall, color = Ink.SurfaceTop)
                            }
                        }
                    }
                    Spacer(Modifier.height(d(18)))
                    Text(
                        state.qrHint,
                        style = MaterialTheme.typography.bodySmall,
                        color = Paper.Secondary,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(d(18)))
                    TvButton(
                        text = if (state.qrBitmap == null) "获取二维码" else "刷新二维码",
                        onClick = onLogin,
                        icon = if (state.qrBitmap == null) Icons.Rounded.QrCode2 else Icons.Rounded.Refresh,
                        tone = ButtonTone.Primary,
                    )
                }
            }
        }

        // 右侧：说明与操作
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(d(18))) {
            Text("红果账号", style = MaterialTheme.typography.displaySmall, color = Paper.Pure, fontWeight = FontWeight.Bold)
            Text(
                if (state.signedIn) "云端数据已与本机合并，换设备也能接着看。"
                else "扫码登录后，追剧、观看进度和预约会跟随账号，换一台电视也能无缝续上。",
                style = MaterialTheme.typography.bodyLarge,
                color = Paper.Secondary,
                modifier = Modifier.fillMaxWidth(0.9f),
            )
            Spacer(Modifier.height(d(6)))
            FeatureRow(Icons.Rounded.CloudSync, "追剧同步", "收藏与观看进度自动同步到红果云端")
            FeatureRow(Icons.Rounded.PlayCircle, "跨设备续播", "在手机看到一半，电视上接着播")
            FeatureRow(Icons.Rounded.QrCode2, "无需输入密码", "用红果 App 扫码确认即可登录")
            Spacer(Modifier.height(d(12)))
            Row(horizontalArrangement = Arrangement.spacedBy(d(12))) {
                if (state.signedIn) {
                    TvButton("退出登录", onSignOut, tone = ButtonTone.Ghost)
                } else {
                    TvButton("扫码登录", onLogin, tone = ButtonTone.Primary)
                }
            }
            state.message?.let {
                Spacer(Modifier.height(d(4)))
                Text(it, style = MaterialTheme.typography.bodySmall, color = Gold.Core)
            }
            Spacer(Modifier.height(d(10)))
            Text(
                "登录仅用于同步官方账号数据，不会上传本机任何其他信息。",
                style = MaterialTheme.typography.labelSmall,
                color = Paper.Tertiary,
            )
        }
    }
}

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
