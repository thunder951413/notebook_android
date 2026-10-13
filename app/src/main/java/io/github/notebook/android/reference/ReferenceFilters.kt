package io.github.notebook.android

import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun BoxScope.ReferenceFilters(
    order: NoteOrder,
    descending: Boolean,
    content: ContentFilter,
    filing: FilingFilter,
    child: String?,
    onChild: (String?) -> Unit,
    onDismiss: () -> Unit,
    onOrder: (NoteOrder) -> Unit,
    onDescending: () -> Unit,
    onContent: (ContentFilter) -> Unit,
    onFiling: (FilingFilter) -> Unit,
    compact: Boolean = false,
    onCompact: () -> Unit = {},
    showDue: Boolean = false,
) {
    var displayedChild by remember { mutableStateOf("sort") }
    child?.let { displayedChild = it }
    val fraction by
        animateFloatAsState(if (child == null) 0f else 1f, tween(180), label = "filter-layer")
    val shift = with(LocalDensity.current) { 32.dp.toPx() }
    Box(
        Modifier.fillMaxSize()
            .background(Color.Black.copy(alpha = .12f))
            .clickable(onClick = onDismiss)
    )
    Surface(
        Modifier.align(Alignment.TopEnd)
            .statusBarsPadding()
            .padding(top = 64.dp, end = 16.dp)
            .width(256.dp)
            .graphicsLayer {
                scaleX = 1f - fraction * .05f
                scaleY = 1f - fraction * .05f
                translationX = -shift * fraction
                alpha = 1f - fraction * .55f
            },
        shape = RoundedCornerShape(24.dp),
        shadowElevation = 12.dp,
    ) {
        Column(Modifier.padding(vertical = 8.dp)) {
            ListItem(
                headlineContent = { Text("排序 · ${order.label}") },
                trailingContent = { Icon(Icons.Default.ChevronRight, null) },
                modifier = Modifier.clickable { onChild("sort") },
            )
            ListItem(
                headlineContent = { Text(content.label) },
                trailingContent = { Icon(Icons.Default.ChevronRight, null) },
                modifier = Modifier.clickable { onChild("type") },
            )
            ListItem(
                headlineContent = { Text(filing.label) },
                trailingContent = { Icon(Icons.Default.ChevronRight, null) },
                modifier = Modifier.clickable { onChild("filing") },
            )
            HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            ListItem(
                headlineContent = { Text("紧凑列表") },
                supportingContent = { Text(if (compact) "已开启：一屏显示更多笔记" else "关闭时以卡片显示摘要与图片") },
                leadingContent = { Icon(if (compact) Icons.Default.ViewHeadline else Icons.Default.ViewAgenda, null) },
                trailingContent = { Switch(compact, { onCompact() }) },
                modifier = Modifier.clickable(onClick = onCompact),
            )
        }
    }
    AnimatedVisibility(
        child != null,
        Modifier.align(Alignment.TopEnd)
            .statusBarsPadding()
            .padding(top = 80.dp, end = 16.dp)
            .width(240.dp),
        enter = fadeIn(tween(180)) + scaleIn(tween(200), initialScale = .92f),
        exit = fadeOut(tween(140)) + scaleOut(tween(160), targetScale = .96f),
    ) {
        Surface(shape = RoundedCornerShape(24.dp), shadowElevation = 14.dp) {
            Column(Modifier.padding(vertical = 8.dp)) {
                Row(
                    Modifier.fillMaxWidth().clickable { onChild(null) }.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.ChevronLeft, null, Modifier.size(20.dp))
                    Text(
                        "排序与筛选",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                HorizontalDivider()
                when (displayedChild) {
                    "sort" -> {
                        NoteOrder.entries.filter { it != NoteOrder.Due || showDue || order == NoteOrder.Due }.forEach { value ->
                            ListItem(
                                headlineContent = { Text(value.label) },
                                trailingContent = {
                                    if (order == value)
                                        Icon(
                                            Icons.Default.Check,
                                            null,
                                            tint = MaterialTheme.colorScheme.primary,
                                        )
                                },
                                modifier = Modifier.clickable { onOrder(value) },
                            )
                        }
                        ListItem(
                            headlineContent = {
                                Text(if (descending) "从新到旧 / 降序" else "从旧到新 / 升序")
                            },
                            leadingContent = {
                                Icon(
                                    if (descending) Icons.Default.South else Icons.Default.North,
                                    null,
                                )
                            },
                            modifier = Modifier.clickable(onClick = onDescending),
                        )
                    }
                    "type" ->
                        ContentFilter.entries.forEach { value ->
                            ListItem(
                                headlineContent = { Text(value.label) },
                                trailingContent = {
                                    if (content == value)
                                        Icon(
                                            Icons.Default.Check,
                                            null,
                                            tint = MaterialTheme.colorScheme.primary,
                                        )
                                },
                                modifier =
                                    Modifier.clickable {
                                        onContent(value)
                                        onDismiss()
                                    },
                            )
                        }
                    else ->
                        FilingFilter.entries.forEach { value ->
                            ListItem(
                                headlineContent = { Text(value.label) },
                                trailingContent = {
                                    if (filing == value)
                                        Icon(
                                            Icons.Default.Check,
                                            null,
                                            tint = MaterialTheme.colorScheme.primary,
                                        )
                                },
                                modifier =
                                    Modifier.clickable {
                                        onFiling(value)
                                        onDismiss()
                                    },
                            )
                        }
                }
            }
        }
    }
}
