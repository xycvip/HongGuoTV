// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.MovieFilter
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import androidx.compose.ui.platform.LocalContext
import com.hongguotv.nativeapp.ui.theme.Gold
import com.hongguotv.nativeapp.ui.theme.Ink
import com.hongguotv.nativeapp.ui.theme.Paper
import com.hongguotv.nativeapp.ui.theme.Rouge
import com.hongguotv.nativeapp.ui.theme.Signal
import com.hongguotv.nativeapp.ui.theme.d
import com.hongguotv.nativeapp.ui.theme.ui

/**
 * 把「上 / 下」方向钉在指定节点上。
 *
 * 电视端只有一个窗口级焦点搜索，几何最近的不一定是对的元素：内容区最上面一行
 * 往上，最近往往是投播/搜索/账号这些工具位，看起来就是「跳到别的导航区」。
 * 在最上行的控件上显式指定「上 = 本页导航项」，页面之间的边界就不会串。
 */
fun Modifier.focusUpTo(requester: FocusRequester?): Modifier =
    if (requester == null) this else focusProperties { up = requester }

fun Modifier.focusDownTo(requester: FocusRequester?): Modifier =
    if (requester == null) this else focusProperties { down = requester }

/**
 * 电视端最核心的交互原语：焦点即光标。
 * 聚焦时卡片放大、描边点亮、外发光；按压时轻微回弹。
 */
@Composable
fun FocusSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(d(14)),
    focusScale: Float = 1.045f,
    glow: Color = Rouge.Core,
    borderWidth: Dp = d(2f),
    enabled: Boolean = true,
    onLongClick: (() -> Unit)? = null,
    focusRequester: FocusRequester? = null,
    content: @Composable BoxScope.(focused: Boolean) -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val pressed by interaction.collectIsPressedAsState()
    val targetScale = when {
        pressed -> if (focusScale > 1f) focusScale - 0.02f else 0.985f
        focused -> focusScale
        else -> 1f
    }
    // 无过冲的短缓出：spring 的回弹会让焦点项边界在几帧内反复涨缩，
    // 带动外层 LazyList 反复做 bringIntoView 校正，整屏看着像在上下抖。
    val scale by animateFloatAsState(targetScale, tween(150), label = "focus-scale")
    val glowAlpha by animateFloatAsState(if (focused) 0.55f else 0f, tween(180), label = "focus-glow")
    val borderColor by animateColorAsState(
        if (focused) glow else Ink.LineSoft,
        tween(160),
        label = "focus-border",
    )
    Box(
        modifier = modifier
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .shadow(
                elevation = if (focused) d(16f) else d(0f),
                shape = shape,
                clip = false,
                ambientColor = glow.copy(alpha = glowAlpha),
                spotColor = glow.copy(alpha = glowAlpha),
            )
            .clip(shape)
            .background(Ink.Surface)
            .border(borderWidth, borderColor, shape)
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick,
                onLongClick = onLongClick,
            ),
    ) {
        content(focused)
    }
}

/** 纯焦点态容器，用于按钮、标签等已有背景的内容。 */
@Composable
fun focusRing(
    interaction: MutableInteractionSource,
    shape: Shape,
    glow: Color = Rouge.Core,
): Pair<Color, Float> {
    val focused by interaction.collectIsFocusedAsState()
    val color by animateColorAsState(if (focused) glow else Color.Transparent, tween(160), label = "ring-color")
    val scale by animateFloatAsState(if (focused) 1.06f else 1f, tween(150), label = "ring-scale")
    return color to scale
}

@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .width(d(4))
                    .height(d(20))
                    .background(Brush.verticalGradient(listOf(Rouge.Bright, Rouge.Deep)), RoundedCornerShape(d(2))),
            )
            Spacer(Modifier.width(d(10)))
            Text(
                title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = Paper.Primary,
            )
            if (subtitle != null) {
                Spacer(Modifier.width(d(12)))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = Paper.Tertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        trailing?.invoke()
    }
}

enum class ButtonTone { Primary, Ghost, Gold, Quiet }

/**
 * 安全地请求焦点：未 attach、目标不可聚焦时返回 false，而不是抛异常或误报成功。
 * 新版 Compose 的 requestFocus() 返回 Boolean，必须区分 true / false。
 */
fun FocusRequester.tryRequestFocus(): Boolean {
    val outcome = runCatching { requestFocus() }
    if (outcome.isFailure) return false
    val value: Any? = outcome.getOrNull()
    return value != false
}

/** 统一按钮：主按钮是胭脂红渐变，次按钮是磨砂玻璃，金色只给「冠军/会员」类操作。 */
@Composable
fun TvButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    tone: ButtonTone = ButtonTone.Primary,
    focusRequester: FocusRequester? = null,
    downFocus: FocusRequester? = null,
    upFocus: FocusRequester? = null,
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        when {
            pressed -> 0.97f
            focused -> 1.055f
            else -> 1f
        },
        tween(150),
        label = "btn",
    )
    val shape = RoundedCornerShape(d(999))
    val (fill, contentColor) = when (tone) {
        ButtonTone.Primary -> Brush.horizontalGradient(listOf(Rouge.Bright, Rouge.Core, Rouge.Deep)) to Color.White
        ButtonTone.Gold -> Brush.horizontalGradient(listOf(Gold.Bright, Gold.Core, Gold.Deep)) to Color(0xFF241704)
        ButtonTone.Ghost -> Brush.horizontalGradient(listOf(Color(0x26FFFFFF), Color(0x14FFFFFF))) to Paper.Primary
        ButtonTone.Quiet -> Brush.horizontalGradient(listOf(Color.Transparent, Color.Transparent)) to Paper.Secondary
    }
    val borderColor by animateColorAsState(
        when {
            focused && tone == ButtonTone.Primary -> Color.White.copy(alpha = 0.9f)
            focused -> Rouge.Bright
            tone == ButtonTone.Ghost -> Ink.LineSoft
            else -> Color.Transparent
        },
        tween(160),
        label = "btn-border",
    )
    Row(
        modifier = modifier
            // 焦点属性统一放在 clickable 之前（官方推荐顺序）：focusProperties 需要
            // 作用于外层，才能稳定地给「这个按钮的下一跳」定方向。
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            // 显式下移目标：hero 下方紧挨着轮播指示条，靠默认搜索容易落空或掉进细线。
            .then(if (downFocus != null) Modifier.focusProperties { down = downFocus } else Modifier)
            .focusUpTo(upFocus)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .then(if (focused) Modifier.shadow(d(18f), shape, ambientColor = Rouge.Core.copy(0.5f), spotColor = Rouge.Core.copy(0.6f)) else Modifier)
            .clip(shape)
            .background(fill)
            .border(d(1.4f), borderColor, shape)
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            )
            .padding(horizontal = d(26), vertical = d(13)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = contentColor, modifier = Modifier.size(d(19)))
            Spacer(Modifier.width(d(9)))
        }
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = contentColor.copy(alpha = if (enabled) 1f else 0.45f),
            maxLines = 1,
        )
    }
}

/** 圆形图标按钮，用于播放器、顶栏等紧凑位置。 */
@Composable
fun RoundIconButton(
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tone: ButtonTone = ButtonTone.Ghost,
    size: Dp = d(46),
    iconSize: Dp = d(21),
    contentDescription: String? = null,
    focusRequester: FocusRequester? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (focused) 1.12f else 1f, tween(150), label = "icon")
    val background = when (tone) {
        ButtonTone.Primary -> Brush.linearGradient(listOf(Rouge.Bright, Rouge.Deep))
        ButtonTone.Gold -> Brush.linearGradient(listOf(Gold.Bright, Gold.Deep))
        else -> Brush.linearGradient(listOf(Color(0x33FFFFFF), Color(0x14FFFFFF)))
    }
    Box(
        modifier = modifier
            // 同上：焦点属性放在 clickable 之前，方向定义才稳定。
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .size(size)
            .clip(CircleShape)
            .background(background)
            .border(d(1.4f), if (focused) Rouge.Bright else Ink.LineSoft, CircleShape)
            .combinedClickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription, tint = Paper.Primary, modifier = Modifier.size(iconSize))
    }
}

/** 轻量标签：题材、年份、清晰度。 */
@Composable
fun MetaChip(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Paper.Secondary,
    background: Color = Color(0x14FFFFFF),
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(d(999)))
            .background(background)
            .padding(horizontal = d(11), vertical = d(5)),
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = color, maxLines = 1)
    }
}

/** 带选中态的胶囊标签，用于分类、筛选、分段切换。 */
@Composable
fun SelectChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Color = Rouge.Core,
    focusRequester: FocusRequester? = null,
    upFocus: FocusRequester? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (focused) 1.08f else 1f, tween(150), label = "chip")
    val bg by animateColorAsState(
        when {
            selected -> accent.copy(alpha = 0.22f)
            focused -> Color(0x1FFFFFFF)
            else -> Color(0x0DFFFFFF)
        },
        tween(150),
        label = "chip-bg",
    )
    val content by animateColorAsState(
        when {
            selected -> Color.White
            focused -> Paper.Pure
            else -> Paper.Secondary
        },
        tween(150),
        label = "chip-text",
    )
    Box(
        modifier = modifier
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .focusUpTo(upFocus)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(d(999)))
            .background(bg)
            .border(
                d(1.2f),
                when {
                    focused -> accent
                    selected -> accent.copy(alpha = 0.55f)
                    else -> Ink.LineSoft
                },
                RoundedCornerShape(d(999)),
            )
            .combinedClickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = d(18), vertical = d(8)),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = content, maxLines = 1)
    }
}

/**
 * 图片交叉淡入时长。截图/预览渲染时置 0，避免依赖主线程动画时钟。
 */
val LocalImageCrossfadeMillis = staticCompositionLocalOf { 260 }

/** 图片加载占位：从深灰到稍亮的缓慢呼吸，避免大片纯黑块。 */
@Composable
fun SmartImage(
    url: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    fallbackTitle: String = "",
    /** 单独覆盖交叉淡入时长；传 0 表示不做淡入，交给外层动画控制。 */
    crossfadeMillis: Int? = null,
) {
    val context = LocalContext.current
    val fadeMillis = crossfadeMillis ?: LocalImageCrossfadeMillis.current
    Box(modifier.background(Brush.verticalGradient(listOf(Ink.SurfaceHigh, Ink.Base)))) {
        if (!url.isNullOrBlank()) {
            AsyncImage(
                model = ImageRequest.Builder(context).data(url).crossfade(fadeMillis).build(),
                contentDescription = fallbackTitle.ifBlank { null },
                contentScale = contentScale,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    fallbackTitle.take(1),
                    style = MaterialTheme.typography.displaySmall,
                    color = Color(0x33FFFFFF),
                )
            }
        }
    }
}

/** 细进度条，支持缓冲段。 */
@Composable
fun ThinProgress(
    progress: Float,
    modifier: Modifier = Modifier,
    buffered: Float = 0f,
    color: Color = Rouge.Bright,
    track: Color = Color(0x33FFFFFF),
    height: Dp = d(3),
) {
    Box(
        modifier
            .height(height)
            .clip(RoundedCornerShape(999.dp))
            .background(track),
    ) {
        if (buffered > 0f) {
            Box(
                Modifier
                    .fillMaxWidth(buffered.coerceIn(0f, 1f))
                    .height(height)
                    .background(Color(0x40FFFFFF)),
            )
        }
        Box(
            Modifier
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .height(height)
                .background(Brush.horizontalGradient(listOf(color, Rouge.Core))),
        )
    }
}

/** 角标：评分、集数、榜单名次。 */
@Composable
fun CornerBadge(
    text: String,
    modifier: Modifier = Modifier,
    background: Color = Color(0xE6F2415A),
    contentColor: Color = Color.White,
) {
    Box(
        modifier
            .clip(RoundedCornerShape(d(7)))
            .background(background)
            .padding(horizontal = d(8), vertical = d(3)),
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = contentColor, fontWeight = FontWeight.Bold)
    }
}

/** 顶部/底部渐隐，让海报列自然融入背景。 */
fun Modifier.edgeFade(horizontal: Boolean = true): Modifier = this.drawWithContent {
    drawContent()
    if (horizontal) {
        drawRect(
            brush = Brush.horizontalGradient(
                0f to Color.Black, 0.06f to Color.Transparent,
                0.94f to Color.Transparent, 1f to Color.Transparent,
            ),
            size = size,
        )
    }
}

@Composable
fun LoadingState(modifier: Modifier = Modifier, label: String = "正在加载") {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            PulseDots()
            Spacer(Modifier.height(d(18)))
            Text(label, style = MaterialTheme.typography.bodyMedium, color = Paper.Tertiary)
        }
    }
}

@Composable
fun PulseDots(color: Color = Rouge.Bright) {
    val transition = androidx.compose.animation.core.rememberInfiniteTransition(label = "dots")
    Row(horizontalArrangement = Arrangement.spacedBy(d(7))) {
        repeat(3) { index ->
            val alpha by transition.animateFloat(
                initialValue = 0.2f,
                targetValue = 1f,
                animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                    animation = tween(520, delayMillis = index * 130),
                    repeatMode = androidx.compose.animation.core.RepeatMode.Reverse,
                ),
                label = "dot-$index",
            )
            Box(
                Modifier
                    .size(d(7))
                    .clip(CircleShape)
                    .background(color.copy(alpha = alpha)),
            )
        }
    }
}

@Composable
fun ErrorState(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Rounded.CloudOff,
                null,
                tint = Paper.Tertiary,
                modifier = Modifier.size(d(42)),
            )
            Spacer(Modifier.height(d(14)))
            Text(message, style = MaterialTheme.typography.bodyMedium, color = Paper.Secondary)
            Spacer(Modifier.height(d(18)))
            TvButton(
                "重新加载",
                onRetry,
                tone = ButtonTone.Ghost,
                focusRequester = focusRequester,
            )
        }
    }
}

@Composable
fun EmptyState(title: String, subtitle: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier
                    .size(d(64))
                    .clip(CircleShape)
                    .background(Color(0x0FFFFFFF)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.MovieFilter,
                    null,
                    tint = Paper.Tertiary,
                    modifier = Modifier.size(d(30)),
                )
            }
            Spacer(Modifier.height(d(18)))
            Text(title, style = MaterialTheme.typography.titleLarge, color = Paper.Primary)
            Spacer(Modifier.height(d(6)))
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = Paper.Tertiary)
            if (action != null) {
                Spacer(Modifier.height(d(20)))
                action()
            }
        }
    }
}

/** 背景氛围光：两团极低饱和的红/金辉光 + 细噪点，给纯黑底增加空间感。 */
@Composable
fun AmbientBackground(content: @Composable BoxScope.() -> Unit) {
    Box(Modifier.fillMaxSize().background(Ink.Deep)) {
        Box(
            Modifier
                .fillMaxSize()
                .drawWithContent {
                    drawRect(
                        Brush.radialGradient(
                            colors = listOf(Color(0x33F2415A), Color.Transparent),
                            center = Offset(size.width * 0.92f, size.height * -0.05f),
                            radius = size.width * 0.62f,
                        ),
                    )
                    drawRect(
                        Brush.radialGradient(
                            colors = listOf(Color(0x1A63A9FF), Color.Transparent),
                            center = Offset(size.width * 0.08f, size.height * 1.1f),
                            radius = size.width * 0.55f,
                        ),
                    )
                    drawContent()
                },
        )
        content()
    }
}

@Composable
fun VerticalGradientScrim(color: Color = Ink.Deep, height: Dp = d(120), fromBottom: Boolean = true) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(height)
            .background(
                Brush.verticalGradient(
                    if (fromBottom) listOf(Color.Transparent, color) else listOf(color, Color.Transparent),
                ),
            ),
    )
}

@Composable
fun DotDivider() {
    Text("·", color = Paper.Disabled, style = MaterialTheme.typography.bodyMedium)
}

@Composable
fun KeyValue(label: String, value: String, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = Paper.Tertiary)
        Spacer(Modifier.width(d(8)))
        Text(value, style = MaterialTheme.typography.bodySmall, color = Paper.Secondary)
    }
}

@Composable
fun ScreenPadding(content: PaddingValues = PaddingValues(horizontal = ui.gutter)): PaddingValues = content
