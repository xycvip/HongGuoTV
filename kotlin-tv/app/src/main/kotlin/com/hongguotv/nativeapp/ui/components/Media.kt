// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hongguotv.core.Series
import com.hongguotv.nativeapp.ui.theme.Gold
import com.hongguotv.nativeapp.ui.theme.Ink
import com.hongguotv.nativeapp.ui.theme.Paper
import com.hongguotv.nativeapp.ui.theme.Rouge
import com.hongguotv.nativeapp.ui.theme.d
import com.hongguotv.nativeapp.ui.theme.ui

/**
 * 竖版海报卡。海报本身是视觉主体，文字只在下方保留一行极简信息；
 * 聚焦时整卡上浮、描边点亮并浮出播放图标，遥控器上的「选中感」非常明确。
 */
@Composable
fun PosterCard(
    series: Series,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = ui.dp(168),
    fillWidth: Boolean = false,
    subtitle: String? = null,
    progress: Float? = null,
    rank: Int? = null,
    showTitle: Boolean = true,
    onLongClick: (() -> Unit)? = null,
    focusRequester: FocusRequester? = null,
    upFocus: FocusRequester? = null,
    completed: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    // 原来是 0.62 阻尼的弹簧 + 2dp 上移：过冲会把卡片顶到相邻卡片上，
    // 连续移焦点时整行看着像在抖。改成短促缓出的纯缩放，只保留「亮起来、略放大」。
    val scale by animateFloatAsState(
        targetValue = if (focused) 1.04f else 1f,
        animationSpec = tween(150),
        label = "poster",
    )

    Column(
        modifier = modifier
            .then(if (fillWidth) Modifier.fillMaxWidth() else Modifier.width(width))
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            },
        verticalArrangement = Arrangement.spacedBy(d(9)),
    ) {
        Box(
            Modifier
                // 焦点属性放在 clickable 之前（官方推荐顺序），方向定义与 requester 绑定更稳。
                .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                .focusUpTo(upFocus)
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .then(
                    if (focused) Modifier.shadow(d(22f), RoundedCornerShape(d(14)), ambientColor = Rouge.Core.copy(0.55f), spotColor = Rouge.Core.copy(0.5f))
                    else Modifier,
                )
                .clip(RoundedCornerShape(d(14)))
                .background(Ink.SurfaceHigh)
                .border(
                    d(2),
                    if (focused) Brush.linearGradient(listOf(Color.White, Rouge.Bright, Rouge.Core))
                    else Brush.linearGradient(listOf(Ink.LineSoft, Ink.LineSoft)),
                    RoundedCornerShape(d(14)),
                )
                .combinedClickable(
                    interactionSource = interaction,
                    indication = null,
                    onClick = onClick,
                    onLongClick = onLongClick,
                ),
        ) {
            // 列表里滚动/换焦点时不要每次淡入：260ms 交叉淡入叠在缩放上，看着就是画面在晃。
            SmartImage(series.cover, Modifier.fillMaxSize(), fallbackTitle = series.title, crossfadeMillis = 0)
            // 底部压暗，保证角标与进度在任何海报上都可读
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0.55f to Color.Transparent,
                            0.82f to Color(0x66000000),
                            1f to Color(0xCC000000),
                        ),
                    ),
            )
            if (rank != null) {
                RankNumeral(rank, Modifier.align(Alignment.TopStart).padding(d(8)))
            } else if (completed) {
                // 已看完角标：与首页/历史「看下一集·已看完」口径一致，仅在无榜单名次时占位左上角。
                Box(Modifier.align(Alignment.TopStart).padding(d(8))) {
                    CornerBadge("已看完", background = Color(0xCC1B8A4C))
                }
            }
            if (!series.badge.isNullOrBlank() && rank == null) {
                Box(Modifier.align(Alignment.TopEnd).padding(d(8))) {
                    CornerBadge(series.badge, background = Color(0xCC0B0D12))
                }
            }
            if (focused) {
                Box(
                    Modifier
                        .align(Alignment.Center)
                        .size(d(46))
                        .clip(CircleShape)
                        .background(Color(0xCCF2415A))
                        .border(d(1.5f), Color.White.copy(alpha = 0.65f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.PlayArrow, null, tint = Color.White, modifier = Modifier.size(d(26)))
                }
            }
            if (progress != null) {
                ThinProgress(
                    progress = progress,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(horizontal = d(8), vertical = d(7)),
                    color = Rouge.Bright,
                    height = d(3),
                )
            }
        }
        if (showTitle) {
            Column(Modifier.padding(horizontal = d(2))) {
                Text(
                    series.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (focused) Paper.Pure else Paper.Primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val line = subtitle ?: series.tags.takeIf { it.isNotBlank() }
                if (!line.isNullOrBlank()) {
                    Spacer(Modifier.height(d(3)))
                    Text(
                        line,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (focused) Paper.Secondary else Paper.Tertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** 榜单名次：冠军用鎏金，其余用半透明墨色。 */
@Composable
fun RankNumeral(rank: Int, modifier: Modifier = Modifier) {
    val isTop = rank <= 3
    val brush = when (rank) {
        1 -> Brush.linearGradient(listOf(Gold.Bright, Gold.Core))
        2 -> Brush.linearGradient(listOf(Color(0xFFE8EDF6), Color(0xFF9BA6B8)))
        3 -> Brush.linearGradient(listOf(Color(0xFFE9B58A), Color(0xFFB7743F)))
        else -> Brush.linearGradient(listOf(Color.White.copy(0.7f), Color.White.copy(0.4f)))
    }
    Box(
        modifier
            .size(d(30))
            .clip(RoundedCornerShape(d(8)))
            .background(Color(0xD9090B10))
            .border(d(1f), if (isTop) Gold.Core.copy(0.6f) else Ink.LineSoft, RoundedCornerShape(d(8))),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            rank.toString(),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Black,
            color = if (isTop) (if (rank == 1) Gold.Bright else Color(0xFFE8EDF6)) else Paper.Secondary,
        )
    }
}

/**
 * 首页主视觉：整幅剧照 + 左侧渐层文案。
 * 背景图会以极慢的速度推近（Ken Burns），轮播切换时交叉淡入，画面不死板。
 */
@Composable
fun HeroBanner(
    series: Series,
    eyebrow: String,
    meta: List<String>,
    summary: String,
    progressPercent: Float?,
    resumeLabel: String?,
    onPlay: () -> Unit,
    onDetail: () -> Unit,
    onFavorite: () -> Unit,
    isFavorite: Boolean,
    modifier: Modifier = Modifier,
    height: Dp = ui.dp(452),
    /**
     * 外层共享的缩放相位。轮播交叉淡入时新旧两张海报必须共用同一个缩放值，
     * 否则一张在放大、一张在缩小，整幅画面会肉眼可见地抖。
     */
    imageScale: State<Float>? = null,
    /** 为空表示沿用全局淡入时长；hero 传 0，避免和 Crossfade 叠成双重淡入。 */
    imageCrossfadeMillis: Int? = null,
    downFocus: FocusRequester? = null,
    playFocus: FocusRequester? = null,
    favoriteFocus: FocusRequester? = null,
    detailFocus: FocusRequester? = null,
    upFocus: FocusRequester? = null,
) {
    val breathe = rememberInfiniteTransition(label = "hero")
    val zoom by breathe.animateFloat(
        initialValue = 1f,
        targetValue = 1.07f,
        animationSpec = infiniteRepeatable(tween(18000), RepeatMode.Reverse),
        label = "hero-zoom",
    )
    Box(modifier.fillMaxWidth().height(height).clipToBounds()) {
        // 在 draw 阶段读缩放值：每帧只重绘这一层，不触发整块 hero 重组。
        Box(Modifier.fillMaxSize().graphicsLayer {
            val scale = imageScale?.value ?: zoom
            scaleX = scale
            scaleY = scale
        }) {
            SmartImage(
                series.cover,
                Modifier.fillMaxSize(),
                fallbackTitle = series.title,
                crossfadeMillis = imageCrossfadeMillis,
            )
        }
        // 左→右压暗 + 底部融入页面底色，让文字区域绝对干净
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        0f to Ink.Deep.copy(alpha = 0.97f),
                        0.34f to Ink.Deep.copy(alpha = 0.88f),
                        0.62f to Ink.Deep.copy(alpha = 0.35f),
                        1f to Color.Transparent,
                    ),
                ),
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Ink.Deep.copy(alpha = 0.55f),
                        0.35f to Color.Transparent,
                        0.72f to Ink.Deep.copy(alpha = 0.45f),
                        1f to Ink.Deep,
                    ),
                ),
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth(0.62f)
                .padding(start = ui.gutter, bottom = d(62), end = d(20)),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(d(6))
                        .clip(CircleShape)
                        .background(Rouge.Bright),
                )
                Spacer(Modifier.width(d(8)))
                Text(
                    eyebrow,
                    style = MaterialTheme.typography.labelMedium,
                    color = Gold.Bright,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.height(d(14)))
            Text(
                series.title,
                style = MaterialTheme.typography.displayMedium,
                color = Paper.Pure,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(d(12)))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(d(10))) {
                meta.forEachIndexed { index, item ->
                    if (index > 0) DotDivider()
                    Text(item, style = MaterialTheme.typography.bodyMedium, color = Paper.Secondary)
                }
            }
            if (summary.isNotBlank()) {
                Spacer(Modifier.height(d(12)))
                Text(
                    summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Paper.Secondary.copy(alpha = 0.92f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (progressPercent != null && resumeLabel != null) {
                Spacer(Modifier.height(d(16)))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(d(10))) {
                    ThinProgress(
                        progress = progressPercent,
                        modifier = Modifier.width(d(150)),
                        height = d(3),
                    )
                    Text(resumeLabel, style = MaterialTheme.typography.labelSmall, color = Paper.Tertiary)
                }
            }
            Spacer(Modifier.height(d(26)))
            Row(horizontalArrangement = Arrangement.spacedBy(d(14)), verticalAlignment = Alignment.CenterVertically) {
                TvButton(
                    text = if (progressPercent != null) "继续观看" else "立即播放",
                    onClick = onPlay,
                    icon = Icons.Rounded.PlayArrow,
                    tone = ButtonTone.Primary,
                    focusRequester = playFocus,
                    downFocus = downFocus,
                    upFocus = upFocus,
                    modifier = Modifier.focusProperties {
                        if (playFocus != null) left = playFocus
                        if (favoriteFocus != null) right = favoriteFocus
                    },
                )
                TvButton(
                    text = if (isFavorite) "已收藏" else "加入收藏",
                    onClick = onFavorite,
                    icon = if (isFavorite) Icons.Rounded.Check else Icons.Rounded.Add,
                    tone = ButtonTone.Ghost,
                    focusRequester = favoriteFocus,
                    downFocus = downFocus,
                    upFocus = upFocus,
                    modifier = Modifier.focusProperties {
                        if (playFocus != null) left = playFocus
                        if (detailFocus != null) right = detailFocus
                    },
                )
                TvButton(
                    text = "剧集详情",
                    onClick = onDetail,
                    tone = ButtonTone.Quiet,
                    focusRequester = detailFocus,
                    downFocus = downFocus,
                    upFocus = upFocus,
                    modifier = Modifier.focusProperties {
                        if (favoriteFocus != null) left = favoriteFocus
                        if (detailFocus != null) right = detailFocus
                    },
                )
            }
        }
        // 右侧悬浮的评分徽标
        if (series.badge.isNotBlank()) {
            Row(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = d(104), end = ui.gutter)
                    .clip(RoundedCornerShape(d(999)))
                    .background(Color(0x66090B10))
                    .border(d(1f), Gold.Core.copy(0.35f), RoundedCornerShape(d(999)))
                    .padding(horizontal = d(14), vertical = d(8)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Star, null, tint = Gold.Bright, modifier = Modifier.size(d(15)))
                Spacer(Modifier.width(d(6)))
                Text(series.badge, style = MaterialTheme.typography.labelMedium, color = Gold.Bright)
            }
        }
    }
}

/** 横向剧照卡，用于「接着看」等需要展示进度的场景。 */
@Composable
fun ResumeCard(
    series: Series,
    label: String,
    progress: Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = ui.dp(300),
    focusRequester: FocusRequester? = null,
    upFocus: FocusRequester? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    // 与海报卡片同一套节奏：不做弹簧过冲，避免一排卡片连续移焦点时整行发颤。
    val scale by animateFloatAsState(
        targetValue = if (focused) 1.04f else 1f,
        animationSpec = tween(150),
        label = "resume",
    )
    Column(
        modifier
            .width(width)
            .graphicsLayer { scaleX = scale; scaleY = scale },
        verticalArrangement = Arrangement.spacedBy(d(8)),
    ) {
        Box(
            Modifier
                .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                .focusUpTo(upFocus)
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .then(if (focused) Modifier.shadow(d(20f), RoundedCornerShape(d(13)), ambientColor = Rouge.Core.copy(0.5f)) else Modifier)
                .clip(RoundedCornerShape(d(13)))
                .background(Ink.SurfaceHigh)
                .border(d(2), if (focused) Rouge.Bright else Ink.LineSoft, RoundedCornerShape(d(13)))
                .combinedClickable(interactionSource = interaction, indication = null, onClick = onClick),
        ) {
            SmartImage(series.cover, Modifier.fillMaxSize(), fallbackTitle = series.title)
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to Color(0xCC000000))),
            )
            Box(
                Modifier
                    .align(Alignment.Center)
                    .size(d(42))
                    .clip(CircleShape)
                    .background(Color(0x99000000))
                    .border(d(1.2f), Color.White.copy(0.5f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.PlayArrow, null, tint = Color.White, modifier = Modifier.size(d(24)))
            }
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = Paper.Secondary,
                modifier = Modifier.align(Alignment.BottomStart).padding(d(10)),
            )
            ThinProgress(
                progress,
                Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
                height = d(3),
            )
        }
        Text(
            series.title,
            style = MaterialTheme.typography.titleSmall,
            color = if (focused) Paper.Pure else Paper.Primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
