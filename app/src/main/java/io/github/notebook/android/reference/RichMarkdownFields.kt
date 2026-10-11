package io.github.notebook.android

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.notebook.android.data.AssetEntity

private data class EditingProjection(val source: String, val blocks: List<MarkdownBlock>)

private fun editingProjection(source: String): EditingProjection {
    val parsed = MarkdownDocumentParser.parse(source).blocks.toMutableList()
    if (parsed.isEmpty()) parsed += MarkdownBlock("empty", 0, 0, "", MarkdownBlockKind.Paragraph)
    if (
        parsed.last().kind == MarkdownBlockKind.Table ||
            NotebookAssetSyntax.images(parsed.last().text).let {
                it.isNotEmpty() && NotebookAssetSyntax.withoutImages(parsed.last().text).isBlank()
            }
    )
        parsed +=
            MarkdownBlock("tail", source.length, source.length, "", MarkdownBlockKind.Paragraph)
    return EditingProjection(source, parsed)
}

/** Canonical ranges stay global; each native text field lays out only its own block. */
@Composable
internal fun RichMarkdownFields(
    value: TextFieldValue,
    onInput: (TextFieldValue) -> Unit,
    onCommand: (TextFieldValue) -> Unit,
    assets: List<AssetEntity>,
    titles: Map<String, String>,
    focus: FocusRequester,
    onFocus: (Boolean) -> Unit,
    onCaret: (Int) -> Unit,
    requestOwnerFocus: Boolean,
    onTable: (Int, Int) -> Unit,
) {
    val cache = remember { arrayOf(editingProjection(value.text)) }
    if (value.composition == null && cache[0].source != value.text)
        cache[0] = editingProjection(value.text)
    val projection = cache[0]
    val delta = value.text.length - projection.source.length
    val blocks = projection.blocks
    var composingOwner by remember { mutableIntStateOf(0) }
    val owner =
        if (value.composition != null && delta != 0) composingOwner.coerceIn(0, blocks.lastIndex)
        else blocks.indexOfLast { value.selection.start >= it.startUtf16 }.coerceAtLeast(0)
    val color = MaterialTheme.colorScheme.onSurface
    val rich = remember(color, titles) { MarkdownVisualTransformation(color, titles) }
    LaunchedEffect(owner, blocks[owner].startUtf16, requestOwnerFocus) {
        if (requestOwnerFocus && value.composition == null) runCatching { focus.requestFocus() }
    }
    Column(Modifier.fillMaxWidth().onFocusChanged { onFocus(it.hasFocus) }.focusGroup()) {
        blocks.forEachIndexed { index, block ->
            val shift = if (delta != 0 && index > owner) delta else 0
            val start = (block.startUtf16 + shift).coerceIn(0, value.text.length)
            val end =
                (block.endUtf16 + shift + if (delta != 0 && index == owner) delta else 0).coerceIn(
                    start,
                    value.text.length,
                )
            val source = value.text.substring(start, end)
            val selected = index == owner
            fun change(next: TextFieldValue, innerStart: Int = start, innerEnd: Int = end) {
                composingOwner = index
                val text = value.text.replaceRange(innerStart, innerEnd, next.text)
                onInput(
                    TextFieldValue(
                        text,
                        TextRange(
                            innerStart + next.selection.start,
                            innerStart + next.selection.end,
                        ),
                        next.composition?.let {
                            TextRange(innerStart + it.start, innerStart + it.end)
                        },
                    )
                )
            }
            val inputModifier =
                Modifier.fillMaxWidth()
                    .then(
                        if (selected) Modifier.focusRequester(focus).testTag("body-field")
                        else Modifier.testTag("body-block-$index")
                    )
                    .onFocusChanged {
                        if (it.isFocused && !selected) {
                            composingOwner = index
                            val caret =
                                if (
                                    block.kind == MarkdownBlockKind.Code &&
                                        source.indexOf('\n') >= 0
                                )
                                    start + source.indexOf('\n') + 1
                                else start
                            onCaret(caret)
                        }
                    }
            val local =
                TextFieldValue(
                    source,
                    TextRange(
                        (value.selection.start - start).coerceIn(0, source.length),
                        (value.selection.end - start).coerceIn(0, source.length),
                    ),
                    value.composition
                        ?.takeIf { selected }
                        ?.let {
                            TextRange(
                                (it.start - start).coerceIn(0, source.length),
                                (it.end - start).coerceIn(0, source.length),
                            )
                        },
                )
            when {
                block.kind == MarkdownBlockKind.Table -> {
                    val table =
                        remember(source) { runCatching { MarkdownTable.parse(source) }.getOrNull() }
                    if (table != null)
                        Surface(
                            Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            shape = RoundedCornerShape(20.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                        ) {
                            Column(Modifier.padding(14.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        "表格",
                                        Modifier.weight(1f),
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    IconButton({ onTable(start, end) }) {
                                        Icon(Icons.Default.OpenInFull, "编辑表格")
                                    }
                                }
                                table.rows.take(3).forEachIndexed { rowIndex, row ->
                                    Row {
                                        repeat(table.columns.coerceAtMost(6)) { column ->
                                            Box(
                                                Modifier.weight(1f)
                                                    .heightIn(min = 42.dp)
                                                    .border(
                                                        1.dp,
                                                        MaterialTheme.colorScheme.outlineVariant,
                                                    )
                                                    .padding(8.dp)
                                            ) {
                                                Text(
                                                    row.getOrElse(column) { "" },
                                                    maxLines = 2,
                                                    fontSize = 14.sp,
                                                    fontWeight =
                                                        if (rowIndex == 0) FontWeight.SemiBold
                                                        else FontWeight.Normal,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                }
                NotebookAssetSyntax.images(source).let {
                    it.isNotEmpty() && NotebookAssetSyntax.withoutImages(source).isBlank()
                } -> {
                    NotebookAssetSyntax.images(source).forEach { image ->
                        assets
                            .firstOrNull { it.id == image.assetId }
                            ?.let { asset -> AttachmentView(asset, image.title ?: asset.caption) }
                    }
                }
                block.kind == MarkdownBlockKind.Blank && !selected -> Spacer(Modifier.height(8.dp))
                block.kind == MarkdownBlockKind.Code &&
                    source.indexOf('\n') >= 0 &&
                    source.trimEnd().let { it.endsWith("```") || it.endsWith("~~~") } -> {
                    val firstNewline = source.indexOf('\n')
                    val closingStart = source.lastIndexOf('\n', source.trimEnd().lastIndex) + 1
                    val payloadStart = start + firstNewline + 1
                    val payloadEnd = (start + closingStart - 1).coerceAtLeast(payloadStart)
                    val payload = value.text.substring(payloadStart, payloadEnd)
                    val codeValue =
                        TextFieldValue(
                            payload,
                            TextRange(
                                (value.selection.start - payloadStart).coerceIn(0, payload.length),
                                (value.selection.end - payloadStart).coerceIn(0, payload.length),
                            ),
                            value.composition
                                ?.takeIf { selected }
                                ?.let {
                                    TextRange(
                                        (it.start - payloadStart).coerceIn(0, payload.length),
                                        (it.end - payloadStart).coerceIn(0, payload.length),
                                    )
                                },
                        )
                    val clipboard = LocalClipboardManager.current
                    Surface(
                        Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    source.substring(0, firstNewline).trim().drop(3).ifBlank {
                                        "代码"
                                    },
                                    Modifier.weight(1f),
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                IconButton({ clipboard.setText(AnnotatedString(payload)) }) {
                                    Icon(Icons.Default.ContentCopy, "复制代码", Modifier.size(18.dp))
                                }
                            }
                            BasicTextField(
                                codeValue,
                                { change(it, payloadStart, payloadEnd) },
                                inputModifier.heightIn(min = 64.dp, max = 480.dp),
                                textStyle =
                                    TextStyle(
                                        fontSize = 16.sp,
                                        lineHeight = 26.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = color,
                                    ),
                                cursorBrush = SolidColor(MaterialTheme.colorScheme.tertiary),
                            )
                        }
                    }
                }
                else ->
                    BasicTextField(
                        local,
                        { next ->
                            val text = value.text.replaceRange(start, end, next.text)
                            composingOwner = index
                            onInput(
                                MarkdownCommands.normalizeInput(
                                    value,
                                    TextFieldValue(
                                        text,
                                        TextRange(
                                            start + next.selection.start,
                                            start + next.selection.end,
                                        ),
                                        next.composition?.let {
                                            TextRange(start + it.start, start + it.end)
                                        },
                                    ),
                                )
                            )
                        },
                        inputModifier.heightIn(min = if (source.isBlank()) 38.dp else 28.dp),
                        textStyle = TextStyle(fontSize = 17.sp, lineHeight = 29.sp, color = color),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.tertiary),
                        visualTransformation = rich,
                        decorationBox = { inner ->
                            if (value.text.isEmpty())
                                Text(
                                    "开始记录…",
                                    fontSize = 17.sp,
                                    color = MaterialTheme.colorScheme.outline,
                                )
                            inner()
                        },
                    )
            }
        }
    }
}
