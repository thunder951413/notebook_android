package io.github.notebook.android

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Inline Markdown inside a table cell: **bold**, *italic*, `code`, ~~strike~~
 * and [links](url). Unmatched markers stay as typed, so nothing is lost.
 */
internal fun inlineMarkdown(source: String, accent: Color, codeBackground: Color): AnnotatedString = buildAnnotatedString {
    var index = 0
    fun closing(marker: String, from: Int): Int {
        val end = source.indexOf(marker, from)
        return if (end > from) end else -1
    }
    while (index < source.length) {
        val rest = source.substring(index)
        val (marker, style) = when {
            rest.startsWith("**") -> "**" to SpanStyle(fontWeight = FontWeight.Bold)
            rest.startsWith("__") -> "__" to SpanStyle(fontWeight = FontWeight.Bold)
            rest.startsWith("~~") -> "~~" to SpanStyle(textDecoration = TextDecoration.LineThrough)
            rest.startsWith("`") -> "`" to SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground)
            rest.startsWith("*") -> "*" to SpanStyle(fontStyle = FontStyle.Italic)
            rest.startsWith("_") && (index == 0 || !source[index - 1].isLetterOrDigit()) -> "_" to SpanStyle(fontStyle = FontStyle.Italic)
            else -> "" to null
        }
        if (style != null) {
            val end = closing(marker, index + marker.length)
            if (end > 0) {
                withStyleRange(style) { append(source.substring(index + marker.length, end)) }
                index = end + marker.length
                continue
            }
        }
        if (rest.startsWith("[")) {
            val link = Regex("^\\[([^\\]]+)]\\(([^)\\s]+)\\)").find(rest)
            if (link != null) {
                withStyleRange(SpanStyle(color = accent, textDecoration = TextDecoration.Underline)) { append(link.groupValues[1]) }
                index += link.value.length
                continue
            }
        }
        append(source[index])
        index++
    }
}

private inline fun AnnotatedString.Builder.withStyleRange(style: SpanStyle, block: AnnotatedString.Builder.() -> Unit) {
    val start = length
    block()
    addStyle(style, start, length)
}

/**
 * Reader table drawn with Compose text instead of a TextView table span.
 * Table spans re-measure every cell while drawing, which made long notes with
 * several tables drop frames when they opened.
 */
@Composable
internal fun ReaderTable(table: MarkdownTable, fontSizeSp: Float, modifier: Modifier = Modifier) {
    val columns = table.columns.coerceAtLeast(1)
    val accent = MaterialTheme.colorScheme.tertiary
    val codeBackground = MaterialTheme.colorScheme.surface
    val outline = MaterialTheme.colorScheme.outlineVariant
    val headerColor = MaterialTheme.colorScheme.surface.copy(alpha = .55f)
    val cells = remember(table, accent, codeBackground) {
        table.rows.map { row -> (0 until columns).map { inlineMarkdown(row.getOrElse(it) { "" }, accent, codeBackground) } }
    }
    val wide = columns > 3
    val scroll = rememberScrollState()
    val fontSize = (fontSizeSp - 2f).coerceIn(12f, 20f).sp
    Column(
        modifier
            .then(if (wide) Modifier.horizontalScroll(scroll) else Modifier)
            .border(0.5.dp, outline),
    ) {
        cells.forEachIndexed { rowIndex, row ->
            Row(
                Modifier.then(if (wide) Modifier else Modifier.fillMaxWidth())
                    .height(IntrinsicSize.Min)
                    .then(if (rowIndex == 0) Modifier.background(headerColor) else Modifier),
            ) {
                row.forEach { cell ->
                    Box(
                        (if (wide) Modifier.width(140.dp) else Modifier.weight(1f))
                            .fillMaxHeight()
                            .border(0.5.dp, outline)
                            .padding(horizontal = 8.dp, vertical = 7.dp),
                    ) {
                        Text(
                            cell,
                            fontSize = fontSize,
                            lineHeight = fontSize * 1.4f,
                            fontWeight = if (rowIndex == 0) FontWeight.SemiBold else FontWeight.Normal,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
    }
}
