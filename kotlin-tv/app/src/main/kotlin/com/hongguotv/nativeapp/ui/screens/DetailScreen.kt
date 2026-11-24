// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowBackIosNew
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hongguotv.core.Series
import com.hongguotv.nativeapp.ui.components.ButtonTone
import com.hongguotv.nativeapp.ui.components.ErrorState
import com.hongguotv.nativeapp.ui.components.LoadingState
import com.hongguotv.nativeapp.ui.components.MetaChip
import com.hongguotv.nativeapp.ui.components.PosterCard
import com.hongguotv.nativeapp.ui.components.RoundIconButton
import com.hongguotv.nativeapp.ui.components.SmartImage
import com.hongguotv.nativeapp.ui.components.ThinProgress
import com.hongguotv.nativeapp.ui.components.TvButton
import com.hongguotv.nativeapp.ui.data.DetailState
import com.hongguotv.nativeapp.ui.theme.Gold
import com.hongguotv.nativeapp.ui.theme.Ink
import com.hongguotv.nativeapp.ui.theme.Paper
import com.hongguotv.nativeapp.ui.theme.Rouge
import com.hongguotv.nativeapp.ui.theme.d
import com.hongguotv.nativeapp.ui.theme.ui

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DetailScreen(
    state: DetailState,
    onBack: () -> Unit,
    onPlay: (Int, Long) -> Unit,
    onNextEpisode: (Int) -> Unit,
    onFavorite: () -> Unit,
    onLater: () -> Unit,
    onSeries: (Series) -> Unit,
    onExpandSynopsis: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize()) {
        val detail = state.detail
        if (state.loading && detail == null) {
            LoadingState(label = "正在打开剧集")
        } else if (state.error != null && detail == null) {
            ErrorState(state.error, onBack)
        } else if (detail != null) {
            var episodesExpanded by remember(detail.series.id) { mutableIntStateOf(0) }
            val episodes = detail.episodes
            val shown = if (episodesExpanded == 1) episodes.size else minOf(episodes.size, 24)
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = d(60)),
                verticalArrangement = Arrangement.spacedBy(d(30)),
            ) {
                item {
                    DetailHeader(
                        state = state,
                        onBack = onBack,
                        onPlay = onPlay,
                        onFavorite = onFavorite,
                        onLater = onLater,
                        onExpandSynopsis = onExpandSynopsis,
                    )
                }
                if (episodes.isNotEmpty()) {
                    item {
                        Column(Modifier.padding(horizontal = ui.gutter)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("选集", style = MaterialTheme.typography.headlineSmall, color = Paper.Primary, fontWeight = FontWeight.SemiBold)
                                Spacer(Modifier.width(d(10)))
                                Text("共 ${episodes.size} 集", style = MaterialTheme.typography.bodySmall, color = Paper.Tertiary)
                                Spacer(Modifier.weight(1f))
                                if (episodes.size > 24) {
                                    TvButton(
                                        if (episodesExpanded == 1) "收起" else "展开全部",
                                        { episodesExpanded = if (episodesExpanded == 1) 0 else 1 },
                                        tone = ButtonTone.Quiet,
                                    )
                                }
                            }
                            Spacer(Modifier.height(d(16)))
                            val rows = episodes.take(shown).chunked(12)
                            Column(verticalArrangement = Arrangement.spacedBy(d(10))) {
                                rows.forEach { row ->
                                    Row(horizontalArrangement = Arrangement.spacedBy(d(10))) {
                                        row.forEachIndexed { column, _ ->
                                            val index = rows.indexOf(row) * 12 + column
                                            EpisodeCell(
                                                index = index,
                                                current = index == state.resumeIndex,
                                                onClick = {
                                                    val start = if (index == state.resumeIndex) state.resumePosition else 0L
                                                    onPlay(index, start)
                                                },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                if (state.seasons.isNotEmpty()) {
                    item {
                        Column(Modifier.padding(horizontal = ui.gutter)) {
                            Text("同系列", style = MaterialTheme.typography.headlineSmall, color = Paper.Primary, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(d(14)))
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(d(10))) {
                                items(state.seasons, key = { it.id }) { season ->
                                    SeasonChip(season.title, onSeries = { onSeries(season) })
                                }
                            }
                        }
                    }
                }
                if (detail.related.isNotEmpty()) {
                    item {
                        Column {
                            Text(
                                "相关推荐",
                                style = MaterialTheme.typography.headlineSmall,
                                color = Paper.Primary,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = ui.gutter),
                            )
                            Spacer(Modifier.height(d(16)))
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = ui.gutter),
                                horizontalArrangement = Arrangement.spacedBy(d(16)),
                            ) {
                                items(detail.related, key = { it.id }) { series ->
                                    PosterCard(
                                        series = series,
                                        onClick = { onSeries(series) },
                                        subtitle = series.tags.take(14).ifBlank { series.badge },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailHeader(
    state: DetailState,
    onBack: () -> Unit,
    onPlay: (Int, Long) -> Unit,
    onFavorite: () -> Unit,
    onLater: () -> Unit,
    onExpandSynopsis: () -> Unit,
) {
    val detail = state.detail ?: return
    val series = detail.series
    Box(Modifier.fillMaxWidth().height(ui.dp(520))) {
        Box(Modifier.fillMaxSize()) {
            SmartImage(series.cover, Modifier.fillMaxSize(), fallbackTitle = series.title)
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        0f to Ink.Deep.copy(0.94f),
                        0.42f to Ink.Deep.copy(0.72f),
                        0.75f to Ink.Deep.copy(0.36f),
                        1f to Color.Transparent,
                    ),
                ),
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Ink.Deep.copy(0.72f),
                        0.28f to Color.Transparent,
                        0.62f to Ink.Deep.copy(0.35f),
                        1f to Ink.Deep,
                    ),
                ),
        )
        RoundIconButton(
            Icons.Rounded.ArrowBackIosNew,
            onBack,
            size = d(44),
            iconSize = d(17),
            modifier = Modifier.padding(start = ui.gutter, top = d(26)),
            contentDescription = "返回",
        )
        Row(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(horizontal = ui.gutter)
                .padding(bottom = d(30)),
            verticalAlignment = Alignment.Bottom,
        ) {
            Box(
                Modifier
                    .width(d(206))
                    .aspectRatio(2f / 3f)
                    .shadow(d(28f), RoundedCornerShape(d(16)), ambientColor = Color.Black, spotColor = Color.Black)
                    .clip(RoundedCornerShape(d(16)))
                    .border(d(1.4f), Color(0x33FFFFFF), RoundedCornerShape(d(16))),
            ) {
                SmartImage(series.cover, Modifier.fillMaxSize(), fallbackTitle = series.title)
            }
            Spacer(Modifier.width(d(30)))
            Column(Modifier.weight(1f)) {
                Text(
                    series.title,
                    style = MaterialTheme.typography.displaySmall,
                    color = Paper.Pure,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(d(12)))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(d(8)), verticalArrangement = Arrangement.spacedBy(d(8))) {
                    if (detail.social.rating > 0) {
                        Row(
                            Modifier
                                .clip(RoundedCornerShape(d(999)))
                                .background(Gold.Core.copy(alpha = 0.16f))
                                .padding(horizontal = d(11), vertical = d(5)),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Rounded.Star, null, tint = Gold.Bright, modifier = Modifier.size(d(14)))
                            Spacer(Modifier.width(d(5)))
                            Text(
                                String.format(java.util.Locale.CHINA, "%.1f", detail.social.rating),
                                style = MaterialTheme.typography.labelSmall,
                                color = Gold.Bright,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                    series.tags.split(" ", "/", "·", ",").filter { it.isNotBlank() }.take(4).forEach { MetaChip(it) }
                    if (detail.episodes.isNotEmpty()) MetaChip("全 ${detail.episodes.size} 集")
                    if (detail.social.heatText.isNotBlank()) MetaChip(detail.social.heatText)
                }
                Spacer(Modifier.height(d(14)))
                val synopsis = series.description.ifBlank { "暂无简介" }
                Text(
                    synopsis,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Paper.Secondary,
                    maxLines = if (state.synopsisExpanded) 8 else 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(d(6)))
                Text(
                    if (state.synopsisExpanded) "收起简介" else "展开简介",
                    style = MaterialTheme.typography.labelSmall,
                    color = Rouge.Bright,
                    modifier = Modifier.clickable(onClick = onExpandSynopsis),
                )
                if (state.resumeProgress != null) {
                    Spacer(Modifier.height(d(16)))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ThinProgress(state.resumeProgress, Modifier.width(d(160)), height = d(3))
                        Spacer(Modifier.width(d(10)))
                        Text(
                            "上次看到第 ${state.resumeIndex + 1} 集",
                            style = MaterialTheme.typography.labelSmall,
                            color = Paper.Tertiary,
                        )
                    }
                }
                Spacer(Modifier.height(d(22)))
                Row(horizontalArrangement = Arrangement.spacedBy(d(12))) {
                    TvButton(
                        text = if (state.resumeProgress != null) "继续第 ${state.resumeIndex + 1} 集" else "播放第 1 集",
                        onClick = { onPlay(state.resumeIndex, state.resumePosition) },
                        icon = Icons.Rounded.PlayArrow,
                        tone = ButtonTone.Primary,
                    )
                    TvButton(
                        text = if (state.favorite) "已追剧" else "加入追剧",
                        onClick = onFavorite,
                        icon = if (state.favorite) Icons.Rounded.Check else Icons.Rounded.Add,
                        tone = ButtonTone.Ghost,
                    )
                    TvButton("稍后看", onLater, icon = Icons.Rounded.Schedule, tone = ButtonTone.Quiet)
                }
            }
        }
    }
}

@Composable
private fun EpisodeCell(index: Int, current: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Box(
        Modifier
            .width(d(48))
            .height(d(42))
            .clip(RoundedCornerShape(d(9)))
            .background(
                when {
                    focused -> Color(0x33F2415A)
                    current -> Color(0x1FF2415A)
                    else -> Color(0x0FFFFFFF)
                },
            )
            .border(
                d(1.2f),
                when {
                    focused -> Rouge.Bright
                    current -> Rouge.Core.copy(0.5f)
                    else -> Color(0x14FFFFFF)
                },
                RoundedCornerShape(d(9)),
            )
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            (index + 1).toString(),
            style = MaterialTheme.typography.labelLarge,
            color = if (focused || current) Paper.Pure else Paper.Secondary,
        )
    }
}

@Composable
private fun SeasonChip(title: String, onSeries: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Box(
        Modifier
            .clip(RoundedCornerShape(d(999)))
            .background(if (focused) Color(0x1FFFFFFF) else Color(0x0DFFFFFF))
            .border(d(1.2f), if (focused) Rouge.Bright else Ink.LineSoft, RoundedCornerShape(d(999)))
            .clickable(interactionSource = interaction, indication = null, onClick = onSeries)
            .padding(horizontal = d(18), vertical = d(10)),
    ) {
        Text(title, style = MaterialTheme.typography.labelMedium, color = if (focused) Paper.Pure else Paper.Secondary)
    }
}
