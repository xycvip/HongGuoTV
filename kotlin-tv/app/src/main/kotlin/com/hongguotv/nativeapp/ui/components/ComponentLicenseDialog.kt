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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.hongguotv.nativeapp.ui.theme.Gold
import com.hongguotv.nativeapp.ui.theme.Ink
import com.hongguotv.nativeapp.ui.theme.Paper
import com.hongguotv.nativeapp.ui.theme.Rouge
import com.hongguotv.nativeapp.ui.theme.d

/**
 * 组件清单弹窗：三大核心组件突出展示，其余依赖以可滚动清单列出名称与许可。
 * 遥控器可上下滚动查看，确定/返回关闭。
 */
@Composable
fun ComponentLicenseDialog(onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val closeFocus = remember { FocusRequester() }
        val firstItemFocus = remember { FocusRequester() }
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
            // 顶部红金细线，票根质感，与其余弹窗一致。
            Box(
                Modifier
                    .width(d(46))
                    .height(d(3))
                    .clip(RoundedCornerShape(d(99)))
                    .background(Brush.horizontalGradient(listOf(Rouge.Bright, Gold.Core))),
            )
            Spacer(Modifier.height(d(18)))
            // 标题区
            Text(
                "BUILDING BLOCKS",
                style = MaterialTheme.typography.labelSmall,
                color = Gold.Core,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(d(6)))
            Text(
                "组件列表",
                style = MaterialTheme.typography.headlineMedium,
                color = Paper.Pure,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(d(4)))
            Text(
                "支撑本应用运行的开源组件与许可",
                style = MaterialTheme.typography.bodySmall,
                color = Paper.Tertiary,
            )

            Spacer(Modifier.height(d(18)))
            // 三大核心组件：横向三卡
            Row(horizontalArrangement = Arrangement.spacedBy(d(10))) {
                CoreComponentCard(CORE_COMPONENTS[0], Modifier.weight(1f))
                CoreComponentCard(CORE_COMPONENTS[1], Modifier.weight(1f))
                CoreComponentCard(CORE_COMPONENTS[2], Modifier.weight(1f))
            }

            Spacer(Modifier.height(d(18)))
            Text(
                "全部依赖",
                style = MaterialTheme.typography.titleSmall,
                color = Paper.Secondary,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(d(8)))
            Box(Modifier.fillMaxWidth().weight(1f)) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight()
                        .border(d(1f), Color(0x14FFFFFF), RoundedCornerShape(d(14)))
                        .clip(RoundedCornerShape(d(14)))
                        .background(Color(0x06FFFFFF)),
                    verticalArrangement = Arrangement.spacedBy(d(1)),
                ) {
                    itemsIndexed(ALL_COMPONENTS) { index, component ->
                        DependencyRow(
                            component,
                            focusRequester = if (index == 0) firstItemFocus else null,
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
                    "本应用以 GPL-3.0-only 发布 · 第三方组件各遵其许可",
                    style = MaterialTheme.typography.bodySmall,
                    color = Paper.Tertiary,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(d(12)))
                TvButton("关闭", onDismiss, tone = ButtonTone.Ghost, focusRequester = closeFocus)
            }
        }
        // 初始焦点落在列表首行，遥控器上下键即可逐项浏览；关闭按钮仍可由焦点遍历到达。
        LaunchedEffect(Unit) { runCatching { firstItemFocus.requestFocus() } }
    }
}

private data class CoreComponent(
    val name: String,
    val role: String,
    val icon: ImageVector,
    val tint: Color,
)

private data class Dependency(
    val name: String,
    val version: String,
    val license: String,
)

private val CORE_COMPONENTS = listOf(
    CoreComponent("Jetpack Compose", "界面", Icons.Rounded.AutoAwesome, Gold.Core),
    CoreComponent("Media3 ExoPlayer", "播放", Icons.Rounded.PlayCircle, Rouge.Bright),
    CoreComponent("QuickJS", "脚本引擎", Icons.Rounded.Code, Color(0xFF63A9FF)),
)

private val ALL_COMPONENTS = listOf(
    Dependency("Jetpack Compose", "BOM 2025.06.01", "Apache-2.0"),
    Dependency("Media3 ExoPlayer (+HLS)", "1.11.1", "Apache-2.0"),
    Dependency("QuickJS (quickjs-kt)", "1.0.18", "MIT"),
    Dependency("OkHttp", "4.12.0", "Apache-2.0"),
    Dependency("Coil", "2.7.0", "Apache-2.0"),
    Dependency("ZXing", "3.5.3", "Apache-2.0"),
    Dependency("Kotlin / Coroutines", "2.4.21 / 1.11.0", "Apache-2.0"),
    Dependency("Bouncy Castle", "1.79", "Bouncy Castle License"),
    Dependency("jsoup", "1.23.2", "MIT"),
    Dependency("AndroidX Core / RecyclerView", "1.16.0 / 1.3.2", "Apache-2.0"),
)

@Composable
private fun CoreComponentCard(component: CoreComponent, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(d(16)))
            .background(component.tint.copy(alpha = 0.08f))
            .border(d(1f), component.tint.copy(alpha = 0.28f), RoundedCornerShape(d(16)))
            .padding(vertical = d(16), horizontal = d(10)),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(d(44))
                .clip(CircleShape)
                .background(component.tint.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(component.icon, null, tint = component.tint, modifier = Modifier.size(d(22)))
        }
        Spacer(Modifier.height(d(12)))
        Text(
            component.name,
            style = MaterialTheme.typography.labelMedium,
            color = Paper.Pure,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            maxLines = 2,
        )
        Spacer(Modifier.height(d(4)))
        Text(component.role, style = MaterialTheme.typography.labelSmall, color = component.tint)
    }
}

@Composable
private fun DependencyRow(component: Dependency, focusRequester: FocusRequester? = null) {
    // 与软件说明弹窗一致：固定淡边框、无聚焦红框。
    // 列表已有滚动条指示位置，遥控器上下键靠 .focusable() 滚动即可，不再画选中框。
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .focusable()
            .background(Ink.Surface)
            .border(d(1f), Color(0x14FFFFFF), RoundedCornerShape(d(8)))
            .padding(horizontal = d(14), vertical = d(10)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(d(5))
                .clip(CircleShape)
                .background(Gold.Core.copy(alpha = 0.7f)),
        )
        Spacer(Modifier.width(d(12)))
        Column(Modifier.weight(1f)) {
            Text(component.name, style = MaterialTheme.typography.bodyMedium, color = Paper.Primary)
            Spacer(Modifier.height(d(2)))
            Text(component.version, style = MaterialTheme.typography.labelSmall, color = Paper.Tertiary)
        }
        Spacer(Modifier.width(d(10)))
        Text(
            component.license,
            style = MaterialTheme.typography.labelSmall,
            color = Paper.Secondary,
            textAlign = TextAlign.End,
        )
    }
}
