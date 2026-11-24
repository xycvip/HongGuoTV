// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hongguotv.core.Series
import com.hongguotv.nativeapp.ui.components.EmptyState
import com.hongguotv.nativeapp.ui.components.PosterCard
import com.hongguotv.nativeapp.ui.data.ShelfEntry
import com.hongguotv.nativeapp.ui.theme.Paper
import com.hongguotv.nativeapp.ui.theme.d
import com.hongguotv.nativeapp.ui.theme.ui

@Composable
fun CollectionScreen(
    title: String,
    entries: List<ShelfEntry>,
    onDetail: (Series) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (entries.isEmpty()) {
        Box(modifier.fillMaxSize().padding(top = d(96)), contentAlignment = androidx.compose.ui.Alignment.Center) {
            EmptyState("这里还没有内容", "去首页或剧场挑一部喜欢的剧吧。")
        }
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(if (ui.compact) 5 else 8),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = ui.gutter, end = ui.gutter, top = d(96), bottom = d(60)),
        horizontalArrangement = Arrangement.spacedBy(d(16)),
        verticalArrangement = Arrangement.spacedBy(d(26)),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column {
                Text(title, style = MaterialTheme.typography.displaySmall, color = Paper.Pure, fontWeight = FontWeight.Bold)
                androidx.compose.foundation.layout.Spacer(Modifier.height(d(6)))
                Text("共 ${entries.size} 部 · 按返回键回到上一页", style = MaterialTheme.typography.bodySmall, color = Paper.Tertiary)
                androidx.compose.foundation.layout.Spacer(Modifier.height(d(16)))
            }
        }
        items(entries, key = { it.series.id }) { entry ->
            PosterCard(
                series = entry.series,
                onClick = { onDetail(entry.series) },
                fillWidth = true,
                subtitle = entry.label,
                progress = entry.progress,
            )
        }
    }
}
