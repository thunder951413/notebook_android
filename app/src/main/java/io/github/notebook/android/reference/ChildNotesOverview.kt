package io.github.notebook.android

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.notebook.android.data.NoteSummary

/**
 * Second line of a note card: the body's start, or, for a note with no body
 * but sub-notes, how many there are.
 */
internal fun childAwarePreview(preview: String, directChildren: Int): String =
    preview.trim().ifEmpty { if (directChildren > 0) "$directChildren 个子笔记" else "" }

/**
 * What a note with no body but sub-notes shows instead of a blank page: its
 * sub-notes, as a list. Writing a body stays one tap away.
 *
 * [grandchildren] maps a note id to how many notes are directly under it.
 */
@Composable
internal fun ChildNotesOverview(
    title: String,
    icon: String?,
    children: List<NoteSummary>,
    grandchildren: Map<String, Int>,
    onOpen: (String) -> Unit,
    onCompose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    LazyColumn(
        modifier.testTag("child-overview"),
        contentPadding = PaddingValues(bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item(key = "head") {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(Modifier.size(44.dp), shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                    Box(contentAlignment = Alignment.Center) {
                        val glyph = icon?.takeIf { it.isNotBlank() }
                        if (glyph != null) Text(glyph, fontSize = 22.sp)
                        else Icon(Icons.Default.FolderOpen, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(title.ifBlank { "无标题" }, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("目录页 · ${children.size} 个子笔记", Modifier.padding(top = 2.dp), fontSize = 13.sp, color = muted)
                }
            }
        }
        items(children, key = { it.id }) { child ->
            val nested = grandchildren[child.id] ?: 0
            val secondary = childAwarePreview(cardPreview(child.preview), nested).ifEmpty { "没有更多内容" }
            val glyph = child.icon?.takeIf { it.isNotBlank() }
            Surface(
                Modifier.fillMaxWidth().clickable { onOpen(child.id) }.testTag("child-${child.id}"),
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surface,
            ) {
                Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
                        if (glyph != null) Text(glyph, fontSize = 20.sp)
                        else Icon(if (nested > 0) Icons.Default.FolderOpen else Icons.Default.Description, null, tint = muted)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(child.title.ifBlank { "无标题" }, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(secondary.replace('\n', ' '), fontSize = 13.sp, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (nested > 0) Text("$nested", Modifier.padding(start = 8.dp), fontSize = 12.sp, color = muted)
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = muted)
                }
            }
        }
        item(key = "compose") {
            TextButton(onCompose, Modifier.testTag("compose-body")) { Text("添加正文…", color = muted) }
        }
    }
}
