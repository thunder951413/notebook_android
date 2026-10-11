package io.github.notebook.android

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal fun markdownEditingPages(source: String, limit: Int = 12000): List<IntRange> {
    if (source.isEmpty()) return listOf(0 until 0)
    val pages = mutableListOf<IntRange>()
    var start = 0
    while (start < source.length) {
        val proposed = minOf(start + limit, source.length)
        var end = proposed
        if (proposed < source.length) {
            val newline = source.lastIndexOf('\n', proposed)
            if (newline > start + limit / 2) end = newline + 1
            else if (Character.isLowSurrogate(source[proposed])) end--
        }
        pages += start until end
        start = end
    }
    return pages
}

/**
 * Bounds native text layout on very long documents; offsets remain global and saving stays
 * canonical.
 */
@Composable
internal fun LongMarkdownEditor(
    value: TextFieldValue,
    onValue: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
) {
    val pages = remember(value.text) { markdownEditingPages(value.text) }
    val selectedPage = pages.indexOfLast { value.selection.start >= it.first }.coerceAtLeast(0)
    val page = pages[selectedPage]
    val start = page.first
    val end = page.last + 1
    val local =
        TextFieldValue(
            value.text.substring(start, end),
            TextRange(
                (value.selection.start - start).coerceIn(0, end - start),
                (value.selection.end - start).coerceIn(0, end - start),
            ),
            value.composition?.let {
                TextRange(
                    (it.start - start).coerceIn(0, end - start),
                    (it.end - start).coerceIn(0, end - start),
                )
            },
        )
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(
                {
                    val offset = pages[selectedPage - 1].first
                    onValue(value.copy(selection = TextRange(offset)))
                },
                enabled = selectedPage > 0,
            ) {
                Text("上一页")
            }
            Text(
                "${selectedPage+1} / ${pages.size}",
                Modifier.padding(top = 14.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(
                {
                    val offset = pages[selectedPage + 1].first
                    onValue(value.copy(selection = TextRange(offset)))
                },
                enabled = selectedPage < pages.lastIndex,
            ) {
                Text("下一页")
            }
        }
        BasicTextField(
            local,
            { next ->
                val source = value.text.replaceRange(start, end, next.text)
                onValue(
                    TextFieldValue(
                        source,
                        TextRange(start + next.selection.start, start + next.selection.end),
                        next.composition?.let { TextRange(start + it.start, start + it.end) },
                    )
                )
            },
            modifier.fillMaxWidth().heightIn(min = 240.dp).testTag("body-field"),
            textStyle =
                TextStyle(
                    fontSize = 17.sp,
                    lineHeight = 29.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.tertiary),
        )
    }
}
