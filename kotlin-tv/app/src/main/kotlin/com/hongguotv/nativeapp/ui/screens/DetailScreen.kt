// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hongguotv.core.Actor
import com.hongguotv.core.SeasonEntry
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
import com.hongguotv.nativeapp.ui.components.tryRequestFocus
import com.hongguotv.nativeapp.ui.data.ActorState
import com.hongguotv.nativeapp.ui.data.DetailState
import com.hongguotv.nativeapp.ui.theme.Gold
import com.hongguotv.nativeapp.ui.theme.Ink
import com.hongguotv.nativeapp.ui.theme.Paper
import com.hongguotv.nativeapp.ui.theme.Rouge
import com.hongguotv.nativeapp.ui.theme.d
import com.hongguotv.nativeapp.ui.theme.ui
import kotlinx.coroutines.launch

/**
 * 详情页：整幅剧照做底，信息压在画面左下，下面是演职员 / 选集 / 相关推荐。
 *
 * 布局要点：
 *  - 选集按可用宽度均分列宽（BoxWithConstraints + weight），不再出现「右边空一大片」；
 *  - 进入页面焦点直接落在「播放/继续观看」上，遥控器一进来就知道按确定会发生什么；
 *  - 系列剧用海报卡 + 第几季标签，当前季带「正在看」标记；
 *  - 点演员头像在本页弹出 [ActorSheet]：档案和作品同屏，返回即回原处，不切路由。
 */
@Composable
fun DetailScreen(
    state: DetailState,
    onBack: () -> Unit,
    onPlay: (Int, Long) -> Unit,
    onNextEpisode: (Int) -> Unit,
    onFavorite: () -> Unit,
    onLater: () -> Unit,
    onSeries: (Series) -> Unit,
    /** 点演员卡打开其档案弹窗；没有作品 ID 的演员也会弹窗，只是作品区显示「暂无作品」。 */
    onActor: (Actor) -> Unit,
    onExpandSynopsis: () -> Unit,
    modifier: Modifier = Modifier,
    /** 演员弹窗的数据；actor == null 时不显示。 */
    actor: ActorState = ActorState(loading = false, hasMore = false),
    onActorClose: () -> Unit = {},
    onActorDetail: (Series) -> Unit = {},
    onActorLoadMore: () -> Unit = {},
    onActorRetry: () -> Unit = {},
    /** 切季成功后希望落到哪一季的季签上，由上一级设置、请求一次后回清。 */
    focusSeasonId: String? = null,
    onSeasonFocusConsumed: () -> Unit = {},
    /** 屏幕顶部是否显示可点击的返回箭头；遥控器返回键始终可用，不受此开关影响。 */
    showBack: Boolean = true,
) {
    Box(modifier.fillMaxSize()) {
        val detail = state.detail
        when {
            state.loading && detail == null -> LoadingState(label = "正在打开剧集")
            state.error != null && detail == null -> ErrorState(state.error, onBack)
            detail != null -> DetailContent(
                state = state,
                detail = detail,
                onBack = onBack,
                onPlay = onPlay,
                onFavorite = onFavorite,
                onLater = onLater,
                onSeries = onSeries,
                onActor = onActor,
                actorSheetOpen = actor.actor != null,
                onExpandSynopsis = onExpandSynopsis,
                focusSeasonId = focusSeasonId,
                onSeasonFocusConsumed = onSeasonFocusConsumed,
                showBack = showBack,
            )
        }
        // 弹窗叠在详情页之上：详情页保持在组合里，关掉弹窗时选集/滚动位置原样还在。
        if (actor.actor != null) {
            ActorSheet(
                state = actor,
                onClose = onActorClose,
                onDetail = onActorDetail,
                onLoadMore = onActorLoadMore,
                onRetry = onActorRetry,
            )
        }
    }
}

@Composable
private fun DetailContent(
    state: DetailState,
    detail: com.hongguotv.core.Detail,
    onBack: () -> Unit,
    onPlay: (Int, Long) -> Unit,
    onFavorite: () -> Unit,
    onLater: () -> Unit,
    onSeries: (Series) -> Unit,
    onActor: (Actor) -> Unit,
    /** 演员弹窗是否正开着；关掉时把焦点还给刚才点的头像。 */
    actorSheetOpen: Boolean,
    onExpandSynopsis: () -> Unit,
    focusSeasonId: String?,
    onSeasonFocusConsumed: () -> Unit,
    showBack: Boolean = true,
) {
    val series = detail.series
    var episodesExpanded by remember(series.id) { mutableIntStateOf(0) }
    val playFocus = remember { FocusRequester() }
    val backFocus = remember { FocusRequester() }
    // 「展开全部 / 收起」按钮的落点：末尾的收起按钮消失后要有人接住焦点。
    val episodeToggleFocus = remember { FocusRequester() }
    // 每个季签一个 requester，切季后能把光标留在「刚点的那一季」上，方便连着换。
    val seasonFoci = remember(state.seasons) {
        state.seasons.associate { it.series.id to FocusRequester() }
    }

    // 入场焦点：等第一帧布局完成再请求，避免 requester 还没 attach。
    LaunchedEffect(series.id) {
        repeat(14) {
            if (playFocus.tryRequestFocus()) return@LaunchedEffect
            withFrameNanos { }
        }
    }

    // 从别的季切过来：等新季数据就绪后把光标交给对应季签，请求一次即清。
    LaunchedEffect(series.id, state.loading, focusSeasonId) {
        val target = focusSeasonId ?: return@LaunchedEffect
        if (state.loading || target != series.id) return@LaunchedEffect
        val request = seasonFoci[target]
        if (request != null) {
            repeat(12) {
                if (request.tryRequestFocus()) {
                    onSeasonFocusConsumed()
                    return@LaunchedEffect
                }
                withFrameNanos { }
            }
        }
        onSeasonFocusConsumed()
    }

    val episodes = detail.episodes
    val shown = if (episodesExpanded == 1) episodes.size else minOf(episodes.size, 24)
    val currentSeason = state.seasons.firstOrNull { it.series.id == series.id }

    LazyColumn(
        Modifier.fillMaxSize().focusGroup().focusRestorer(),
        contentPadding = PaddingValues(bottom = d(64)),
        verticalArrangement = Arrangement.spacedBy(d(26)),
    ) {
        item {
            DetailHero(
                state = state,
                detail = detail,
                backFocus = backFocus,
                playFocus = playFocus,
                onBack = onBack,
                onPlay = onPlay,
                onFavorite = onFavorite,
                onLater = onLater,
                onExpandSynopsis = onExpandSynopsis,
                showBack = showBack,
            )
        }
        // 演职员放在选集之前：它是「这部剧是什么」，属于内容本身；
        // 选集是「看哪一集」，会随「展开全部」长到十几行——排在它后面的区块
        // 会被一下子推到几屏以外，用户以为那里没内容。
        if (detail.actors.isNotEmpty()) {
            item {
                ActorSection(
                    actors = detail.actors,
                    onActor = onActor,
                    sheetOpen = actorSheetOpen,
                )
            }
        }
        if (episodes.isNotEmpty()) {
            item {
                EpisodeSection(
                    episodes = episodes,
                    shown = shown,
                    expanded = episodesExpanded == 1,
                    resumeIndex = state.resumeIndex,
                    resumePosition = state.resumePosition,
                    seasonLabel = currentSeason?.label,
                    seasons = state.seasons,
                    currentId = series.id,
                    seasonFoci = seasonFoci,
                    toggleFocus = episodeToggleFocus,
                    onToggleExpand = { episodesExpanded = if (episodesExpanded == 1) 0 else 1 },
                    onPlay = onPlay,
                    onSeason = onSeries,
                )
            }
        }
        if (detail.related.isNotEmpty()) {
            item {
                RelatedSection(related = detail.related, onSeries = onSeries)
            }
        }
    }
}

@Composable
private fun DetailHero(
    state: DetailState,
    detail: com.hongguotv.core.Detail,
    backFocus: FocusRequester,
    playFocus: FocusRequester,
    onBack: () -> Unit,
    onPlay: (Int, Long) -> Unit,
    onFavorite: () -> Unit,
    onLater: () -> Unit,
    onExpandSynopsis: () -> Unit,
    showBack: Boolean = true,
) {
    val series = detail.series
    // 高度按屏高取一个下限，但不再写死：内容（标题 2 行 + 评分 + 简介 + 按钮）
    // 在矮屏上会比 58% 屏高更高，Box 又是底部对齐，多出来的部分会向上溢出——
    // 用户看到的「上方显示不全、按上也回不来」正是标题被裁到了画面外。
    // 改成 heightIn(下限) + 背景 matchParentSize，内容多高就撑多高，永不裁切。
    val screenHeight = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp
    val heroMinHeight = minOf(d(520), (screenHeight * 0.58f).dp).coerceAtLeast(d(300))
    Box(Modifier.fillMaxWidth().heightIn(min = heroMinHeight)) {
        // matchParentSize 不参与 Box 尺寸测量：高度由文字内容决定，背景再铺满。
        SmartImage(series.cover, Modifier.matchParentSize(), fallbackTitle = series.title)
        // 左→右压暗保证文字可读，右边留出剧照细节。
        Box(
            Modifier.matchParentSize().background(
                Brush.horizontalGradient(
                    0f to Ink.Deep.copy(0.96f),
                    0.38f to Ink.Deep.copy(0.86f),
                    0.70f to Ink.Deep.copy(0.38f),
                    1f to Color.Transparent,
                ),
            ),
        )
        // 上下融入页面底色，避免标题区和内容区之间有硬边。
        Box(
            Modifier.matchParentSize().background(
                Brush.verticalGradient(
                    0f to Ink.Deep.copy(0.70f),
                    0.26f to Color.Transparent,
                    0.58f to Ink.Deep.copy(0.42f),
                    1f to Ink.Deep,
                ),
            ),
        )
        // 屏幕返回箭头按设置显隐。backFocus 同时是播放键/收藏键的「上」目标：
        // 隐藏时按钮不再 attach，upFocus 传 null 降级为系统几何搜索，避免指向悬空 requester。
        if (showBack) {
            RoundIconButton(
                Icons.Rounded.ArrowBackIosNew,
                onBack,
                size = d(44),
                iconSize = d(17),
                modifier = Modifier.padding(start = ui.gutter, top = d(24)),
                contentDescription = "返回",
                focusRequester = backFocus,
            )
        } else {
            Spacer(Modifier.height(d(44 + 24)))
        }
        Row(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(horizontal = ui.gutter)
                .padding(bottom = d(22)),
            verticalAlignment = Alignment.Bottom,
        ) {
            Box(
                Modifier
                    .width(d(164))
                    .aspectRatio(2f / 3f)
                    .shadow(d(30f), RoundedCornerShape(d(16)), ambientColor = Color.Black, spotColor = Color.Black)
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
                Row(verticalAlignment = Alignment.CenterVertically) {
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
                        Spacer(Modifier.width(d(10)))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(d(8))) {
                        series.tags.split(" ", "/", "·", ",").filter { it.isNotBlank() }.take(3).forEach { MetaChip(it) }
                        if (detail.episodes.isNotEmpty()) MetaChip("全 ${detail.episodes.size} 集")
                        if (detail.social.heatText.isNotBlank()) MetaChip(detail.social.heatText)
                    }
                }
                Spacer(Modifier.height(d(14)))
                Text(
                    series.description.ifBlank { "暂无简介" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = Paper.Secondary,
                    maxLines = if (state.synopsisExpanded) 8 else 3,
                    overflow = TextOverflow.Ellipsis,
                )
                // 简介本来就一两行时不必给「展开」入口，避免多余的一行文字打断节奏。
                if (series.description.length > 56) {
                    Spacer(Modifier.height(d(6)))
                    Text(
                        if (state.synopsisExpanded) "收起简介" else "展开简介",
                        style = MaterialTheme.typography.labelSmall,
                        color = Rouge.Bright,
                        modifier = Modifier.clickable(onClick = onExpandSynopsis),
                    )
                }
                if (state.resumeProgress != null) {
                    Spacer(Modifier.height(d(14)))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ThinProgress(state.resumeProgress, Modifier.width(d(170)), height = d(3))
                        Spacer(Modifier.width(d(10)))
                        Text(
                            "上次看到第 ${state.resumeIndex + 1} 集 · ${formatClock(state.resumePosition)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = Paper.Tertiary,
                        )
                    }
                }
                Spacer(Modifier.height(d(16)))
                Row(horizontalArrangement = Arrangement.spacedBy(d(12))) {
                    TvButton(
                        text = if (state.resumeProgress != null) "继续第 ${state.resumeIndex + 1} 集" else "播放第 1 集",
                        onClick = { onPlay(state.resumeIndex, state.resumePosition) },
                        icon = Icons.Rounded.PlayArrow,
                        tone = ButtonTone.Primary,
                        focusRequester = playFocus,
                        upFocus = backFocus.takeIf { showBack },
                    )
                    TvButton(
                        text = if (state.favorite) "已收藏" else "加入收藏",
                        onClick = onFavorite,
                        icon = if (state.favorite) Icons.Rounded.Check else Icons.Rounded.Add,
                        tone = ButtonTone.Ghost,
                        upFocus = backFocus.takeIf { showBack },
                    )
                    // 「稍后看」与「收藏」是同一件事，只留一个入口，避免每次都要想点哪个。
                }
            }
        }
    }
}

/**
 * 演职员：圆形头像横滑，选中弹出档案弹窗（简介 + 全部作品）。
 *
 * 位置在 hero 与选集之间——它描述「这部剧是什么」，属于内容本身；
 * 而选集是「看哪一集」，且会随「展开全部」从 2 行涨到十几行，
 * 排在它后面的区块会被整体推到几屏以外，用户会以为那里没有内容。
 *
 * 可聚焦：这是唯一能「从剧看到人」的入口，不给焦点等于把入口藏起来。
 * 官方 celebrities 按主演排序，横向滑到底能看到全部（21 人的剧也能翻完）。
 */
@Composable
private fun ActorSection(actors: List<Actor>, onActor: (Actor) -> Unit, sheetOpen: Boolean) {
    // 每位演员一个 requester：弹窗关掉时要把焦点还给「刚才点的那张脸」。
    // 不还的话，Dialog 关闭后焦点会落到几何搜索的起点，用户得重新从选集摸回来。
    val foci = remember(actors) { actors.associate { it.id to FocusRequester() } }
    var lastOpened by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(sheetOpen) {
        if (sheetOpen) return@LaunchedEffect
        val id = lastOpened ?: return@LaunchedEffect
        lastOpened = null
        val target = foci[id] ?: return@LaunchedEffect
        repeat(10) {
            if (target.tryRequestFocus()) return@LaunchedEffect
            withFrameNanos { }
        }
    }
    Column(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = ui.gutter)) {
            SectionTitle(
                title = "演职员",
                subtitle = "共 ${actors.size} 位 · 选中查看档案与作品",
            )
        }
        Spacer(Modifier.height(d(16)))
        LazyRow(
            contentPadding = PaddingValues(horizontal = ui.gutter),
            horizontalArrangement = Arrangement.spacedBy(d(14)),
        ) {
            items(actors) { actor ->
                ActorCard(
                    actor = actor,
                    onClick = {
                        lastOpened = actor.id
                        onActor(actor)
                    },
                    focusRequester = foci[actor.id],
                )
            }
        }
    }
}

@Composable
private fun ActorCard(
    actor: Actor,
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    // 焦点放大一点就够：电视上 1.05 已经能看出「选中了」，再大就会和相邻头像挤在一起。
    // 与海报卡同一条缓动：默认 spring 会过冲，连续按右键时整排头像看着在抖。
    val scale by animateFloatAsState(if (focused) 1.05f else 1f, tween(150), label = "actorFocus")
    Column(
        Modifier
            .width(d(100))
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .clip(RoundedCornerShape(d(16)))
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            )
            .padding(vertical = d(4)),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        SmartImage(
            url = actor.avatar,
            modifier = Modifier
                .size(d(84))
                .clip(CircleShape)
                .border(
                    d(if (focused) 2.2f else 1.4f),
                    if (focused) Rouge.Bright else Color(0x26FFFFFF),
                    CircleShape,
                ),
            fallbackTitle = actor.name.take(1),
        )
        Spacer(Modifier.height(d(10)))
        Text(
            actor.name,
            style = MaterialTheme.typography.labelLarge,
            color = if (focused) Paper.Pure else Paper.Secondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        if (actor.role.isNotBlank()) {
            Spacer(Modifier.height(d(2)))
            Text(
                "饰 ${actor.role}",
                style = MaterialTheme.typography.labelSmall,
                color = Paper.Tertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** 选集：季切换条压在标题上方，按可用宽度均分列宽铺满整行。 */
@Composable
private fun EpisodeSection(
    episodes: List<String>,
    shown: Int,
    expanded: Boolean,
    resumeIndex: Int,
    resumePosition: Long,
    seasonLabel: String?,
    seasons: List<SeasonEntry>,
    currentId: String,
    seasonFoci: Map<String, FocusRequester>,
    /** 展开/收起按钮的落点：收起后末尾按钮会消失，焦点必须有人接。 */
    toggleFocus: FocusRequester,
    onToggleExpand: () -> Unit,
    onPlay: (Int, Long) -> Unit,
    onSeason: (Series) -> Unit,
) {
    val scope = rememberCoroutineScope()
    Column(Modifier.padding(horizontal = ui.gutter)) {
        // 多季才显示切换条：三五个季签一行排开，比整排海报省一大块高度。
        if (seasons.size > 1) {
            SeasonStrip(seasons = seasons, currentId = currentId, foci = seasonFoci, onSelect = onSeason)
            Spacer(Modifier.height(d(18)))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionTitle(
                title = "选集",
                subtitle = listOfNotNull(seasonLabel, "共 ${episodes.size} 集").joinToString(" · "),
            )
            Spacer(Modifier.weight(1f))
            if (episodes.size > 24) {
                TvButton(
                    text = if (expanded) "收起" else "展开全部",
                    onClick = onToggleExpand,
                    tone = ButtonTone.Quiet,
                    focusRequester = toggleFocus,
                )
            }
        }
        Spacer(Modifier.height(d(16)))
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val gap = d(10)
            val columns = if (ui.compact) 8 else 12
            val rows = episodes.take(shown).chunked(columns)
            Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                rows.forEachIndexed { rowIndex, row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                        row.forEachIndexed { column, _ ->
                            val index = rowIndex * columns + column
                            EpisodeCell(
                                index = index,
                                current = index == resumeIndex,
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    val start = if (index == resumeIndex) resumePosition else 0L
                                    onPlay(index, start)
                                },
                            )
                        }
                        // 最后一行不满时用等宽占位撑住，格子宽度与满行一致。
                        repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
        // 展开后几十集会把顶部那颗「收起」推出屏幕，末尾再给一个：
        // 不然用户收起前要先滚回选集开头，几十行的滚动在电视上很折磨。
        // 这颗按钮收起后会跟着消失，所以先把焦点交给顶部那颗，再切状态。
        if (expanded && episodes.size > shown) {
            Spacer(Modifier.height(d(14)))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                TvButton(
                    text = "收起选集",
                    onClick = {
                        onToggleExpand()
                        scope.launch {
                            withFrameNanos { }
                            toggleFocus.requestFocus()
                        }
                    },
                    tone = ButtonTone.Quiet,
                )
            }
        }
    }
}

/** 季切换条：第一季 / 第二季 / … 当前季高亮，聚焦描边，点一下直接换季。 */
@Composable
private fun SeasonStrip(
    seasons: List<SeasonEntry>,
    currentId: String,
    foci: Map<String, FocusRequester>,
    onSelect: (Series) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(d(10)),
    ) {
        seasons.forEach { entry ->
            SeasonChip(
                entry = entry,
                current = entry.series.id == currentId,
                focusRequester = foci[entry.series.id],
                onClick = { if (entry.series.id != currentId) onSelect(entry.series) },
            )
        }
    }
}

@Composable
private fun SeasonChip(
    entry: SeasonEntry,
    current: Boolean,
    focusRequester: FocusRequester?,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Row(
        Modifier
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .clip(RoundedCornerShape(d(999)))
            .background(
                when {
                    current -> Brush.horizontalGradient(listOf(Rouge.Bright, Rouge.Deep))
                    focused -> SolidColor(Color(0x2EFFFFFF))
                    else -> SolidColor(Color(0x12FFFFFF))
                },
            )
            .border(
                d(1.4f),
                when {
                    focused -> Rouge.Bright
                    current -> Color.Transparent
                    else -> Color(0x1FFFFFFF)
                },
                RoundedCornerShape(d(999)),
            )
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = d(20), vertical = d(10)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (current) {
            Box(Modifier.size(d(6)).clip(CircleShape).background(Color.White))
            Spacer(Modifier.width(d(8)))
        }
        Text(
            entry.label,
            style = MaterialTheme.typography.labelLarge,
            color = if (current || focused) Paper.Pure else Paper.Secondary,
            fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

@Composable
private fun RelatedSection(related: List<Series>, onSeries: (Series) -> Unit) {
    Column {
        Column(Modifier.padding(horizontal = ui.gutter)) {
            SectionTitle(title = "相关推荐", subtitle = "看这部的人也在追")
            Spacer(Modifier.height(d(16)))
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = ui.gutter),
            horizontalArrangement = Arrangement.spacedBy(d(16)),
        ) {
            items(related, key = { it.id }) { series ->
                PosterCard(
                    series = series,
                    onClick = { onSeries(series) },
                    subtitle = series.tags.take(14).ifBlank { series.badge },
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String, subtitle: String? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .width(d(4))
                .height(d(20))
                .background(Brush.verticalGradient(listOf(Rouge.Bright, Rouge.Deep)), RoundedCornerShape(d(2))),
        )
        Spacer(Modifier.width(d(10)))
        Text(title, style = MaterialTheme.typography.headlineSmall, color = Paper.Primary, fontWeight = FontWeight.SemiBold)
        if (!subtitle.isNullOrBlank()) {
            Spacer(Modifier.width(d(12)))
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Paper.Tertiary)
        }
    }
}

@Composable
private fun EpisodeCell(index: Int, current: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Box(
        modifier
            .height(d(46))
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
            fontWeight = if (current) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

private fun formatClock(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return String.format(java.util.Locale.CHINA, "%02d:%02d", total / 60, total % 60)
}
