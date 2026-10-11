package io.github.notebook.android

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.input.*
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.sp

internal enum class MarkdownCommand(val label: String) {
    H1("标题"),
    H2("副标题"),
    H3("小标题"),
    Body("正文"),
    Quote("引用"),
    Bold("粗体"),
    Italic("斜体"),
    Strike("删除线"),
    Bullet("无序列表"),
    Numbered("有序列表"),
    Task("待办"),
    Code("代码块"),
    InlineCode("行内代码"),
    Rule("分隔线"),
    Indent("增加缩进"),
    Outdent("减少缩进"),
}

internal object MarkdownCommands {
    fun insert(value: TextFieldValue, text: String): TextFieldValue {
        val start = value.selection.min
        val end = value.selection.max
        return TextFieldValue(
            value.text.replaceRange(start, end, text),
            TextRange(start + text.length),
        )
    }

    fun apply(value: TextFieldValue, command: MarkdownCommand): TextFieldValue =
        when (command) {
            MarkdownCommand.Bold -> wrap(value, "**")
            MarkdownCommand.Italic -> wrap(value, "_")
            MarkdownCommand.Strike -> wrap(value, "~~")
            MarkdownCommand.InlineCode -> wrap(value, "`")
            MarkdownCommand.Code -> {
                val selected = value.text.substring(value.selection.min, value.selection.max)
                insert(value, "\n\n```\n$selected\n```\n\n")
                    .copy(selection = TextRange(value.selection.min + 6 + selected.length))
            }
            MarkdownCommand.Rule -> insert(value, "\n\n---\n\n")
            else -> prefix(value, command)
        }

    fun normalizeInput(before: TextFieldValue, after: TextFieldValue): TextFieldValue {
        if (
            after.composition != null || !before.selection.collapsed || before.text.length > 150_000
        )
            return after
        val caret = before.selection.start
        if (after.text == before.text.substring(0, caret) + "\n" + before.text.substring(caret)) {
            val start = before.text.lastIndexOf('\n', (caret - 1).coerceAtLeast(-1)) + 1
            val line = before.text.substring(start, caret)
            val match =
                Regex("^([ \t]*)([-+*] \\[[ xX]] |[-+*] |(\\d+)\\. )").find(line) ?: return after
            if (line.length == match.value.length)
                return TextFieldValue(after.text.removeRange(start, caret), TextRange(start + 1))
            val prefix =
                match.groupValues[1] +
                    if (match.groupValues[3].isNotBlank())
                        "${match.groupValues[3].toIntOrNull()?.plus(1)?:1}. "
                    else match.groupValues[2].replace(Regex("\\[[xX]]"), "[ ]")
            return TextFieldValue(
                after.text.substring(0, caret + 1) + prefix + after.text.substring(caret + 1),
                TextRange(caret + 1 + prefix.length),
            )
        }
        if (caret > 0 && after.text == before.text.removeRange(caret - 1, caret)) {
            NotebookReferenceSyntax.parse(before.text)
                .firstOrNull { it.end == caret }
                ?.let { reference ->
                    return TextFieldValue(
                        before.text.removeRange(reference.start, reference.end),
                        TextRange(reference.start),
                    )
                }
            val map =
                MarkdownVisualTransformation(Color.Black)
                    .filter(AnnotatedString(before.text))
                    .offsetMapping
            if (map.originalToTransformed(caret) == map.originalToTransformed(caret - 1)) {
                val start = before.text.lastIndexOf('\n', (caret - 1).coerceAtLeast(-1)) + 1
                val prefix =
                    Regex("^(?:#{1,6} |>[ ]?|[ \t]*[-+*] \\[[ xX]] |[ \t]*[-+*] )")
                        .find(before.text.substring(start))
                if (prefix != null && start + prefix.value.length == caret)
                    return TextFieldValue(before.text.removeRange(start, caret), TextRange(start))
                var end = caret - 1
                while (
                    end > 0 && map.originalToTransformed(end) == map.originalToTransformed(end - 1)
                ) end--
                if (end > 0) {
                    val from = Character.offsetByCodePoints(before.text, end, -1)
                    return TextFieldValue(
                        before.text.removeRange(from, end),
                        TextRange(caret - (end - from)),
                    )
                }
            }
        }
        return after
    }

    private fun wrap(v: TextFieldValue, marker: String): TextFieldValue {
        val start = v.selection.min
        val end = v.selection.max
        val n = marker.length
        if (
            start >= n &&
                end - n >= start &&
                v.text.substring(start - n, start) == marker &&
                v.text.substring(end - n, end) == marker
        )
            return TextFieldValue(
                v.text.removeRange(end - n, end).removeRange(start - n, start),
                TextRange(start - n, end - 2 * n),
            )
        if (
            start >= n &&
                end + n <= v.text.length &&
                v.text.substring(start - n, start) == marker &&
                v.text.substring(end, end + n) == marker
        )
            return TextFieldValue(
                v.text.removeRange(end, end + n).removeRange(start - n, start),
                TextRange(start - n, end - n),
            )
        val next = v.text.replaceRange(start, end, marker + v.text.substring(start, end) + marker)
        return TextFieldValue(next, TextRange(start + n, end + n))
    }

    private fun prefix(v: TextFieldValue, c: MarkdownCommand): TextFieldValue {
        val from = v.text.lastIndexOf('\n', (v.selection.min - 1).coerceAtLeast(-1)) + 1
        val to = v.text.indexOf('\n', v.selection.max).let { if (it < 0) v.text.length else it }
        val lines = v.text.substring(from, to).split('\n')
        val prefix =
            when (c) {
                MarkdownCommand.H1 -> "# "
                MarkdownCommand.H2 -> "## "
                MarkdownCommand.H3 -> "### "
                MarkdownCommand.Quote -> "> "
                MarkdownCommand.Bullet -> "- "
                MarkdownCommand.Task -> "- [ ] "
                else -> ""
            }
        var deltaStart = 0
        var deltaEnd = 0
        val next =
            lines
                .mapIndexed { index, line ->
                    val old =
                        Regex("^(?:#{1,6} |>[ ]?|[-+*] \\[[ xX]] |[-+*] |\\d+\\. )")
                            .find(line)
                            ?.value
                            .orEmpty()
                    val result =
                        when (c) {
                            MarkdownCommand.Indent -> "    $line"
                            MarkdownCommand.Outdent -> line.removePrefix("    ").removePrefix("\t")
                            MarkdownCommand.Numbered -> "${index+1}. " + line.removePrefix(old)
                            else -> prefix + line.removePrefix(old)
                        }
                    val delta = result.length - line.length
                    if (index == 0) deltaStart = delta
                    deltaEnd += delta
                    result
                }
                .joinToString("\n")
        val text = v.text.replaceRange(from, to, next)
        return TextFieldValue(
            text,
            TextRange(
                (v.selection.min + deltaStart).coerceIn(from, text.length),
                (v.selection.max + deltaEnd).coerceIn(from, text.length),
            ),
        )
    }
}

/** Markdown remains authoritative; visual marker removal is a reversible offset mapping only. */
internal class MarkdownVisualTransformation(
    private val color: Color,
    private val titles: Map<String, String> = emptyMap(),
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val source = text.text
        val hidden = BooleanArray(source.length)
        val replacements = mutableMapOf<Int, String>()
        val spans = mutableListOf<Triple<IntRange, SpanStyle, Boolean>>()
        fun hide(start: Int, end: Int) {
            for (i in start until end) hidden[i] = true
        }
        var offset = 0
        var fenced = false
        source.split('\n').forEach { line ->
            val fence = line.trimStart().startsWith("```") || line.trimStart().startsWith("~~~")
            if (fence) {
                fenced = !fenced
            } else if (!fenced) {
                Regex("^(#{1,6}) ").find(line)?.let { m ->
                    hide(offset, offset + m.value.length)
                    spans +=
                        Triple(
                            (offset + m.value.length) until (offset + line.length),
                            SpanStyle(
                                fontWeight = FontWeight.Bold,
                                fontSize =
                                    when (m.groupValues[1].length) {
                                        1 -> 26.sp
                                        2 -> 22.sp
                                        else -> 18.sp
                                    },
                            ),
                            false,
                        )
                }
                Regex("^([ \\t]*)([-+*] )\\[([ xX])] ").find(line)?.let { m ->
                    val at = offset + m.groupValues[1].length
                    hide(at, offset + m.value.length)
                    replacements[at] = if (m.groupValues[3].equals("x", true)) "☑  " else "☐  "
                    if (m.groupValues[3].equals("x", true))
                        spans +=
                            Triple(
                                (offset + m.value.length) until (offset + line.length),
                                SpanStyle(
                                    color = color.copy(alpha = .45f),
                                    textDecoration = TextDecoration.LineThrough,
                                ),
                                false,
                            )
                }
                if (!line.contains(Regex("^[ \\t]*[-+*] \\[[ xX]] ")))
                    Regex("^([ \\t]*)[-+*] ").find(line)?.let { m ->
                        val at = offset + m.groupValues[1].length
                        hide(at, offset + m.value.length)
                        replacements[at] = "•  "
                    }
                Regex("^> ").find(line)?.let { m ->
                    hide(offset, offset + 2)
                    replacements[offset] = "│  "
                    spans +=
                        Triple(
                            (offset + 2) until (offset + line.length),
                            SpanStyle(color = color.copy(alpha = .65f)),
                            false,
                        )
                }
                val inlineCodeRanges =
                    Regex("(?<!\\\\)`([^`]+)`").findAll(line).map { it.range }.toList()
                NotebookReferenceSyntax.parse(line)
                    .filter { reference ->
                        inlineCodeRanges.none {
                            reference.start <= it.last && reference.end > it.first
                        }
                    }
                    .forEach { reference ->
                        val start = offset + reference.start
                        val end = offset + reference.end
                        hide(start, end)
                        replacements[start] =
                            "@ " +
                                (reference.alias
                                    ?: titles[reference.pageId]?.replace('\n', ' ')?.take(40)
                                    ?: "关联笔记")
                        spans +=
                            Triple(
                                start until end,
                                SpanStyle(
                                    color = Color(0xFFBD6600),
                                    textDecoration = TextDecoration.Underline,
                                ),
                                true,
                            )
                    }
                val patterns =
                    listOf(
                        Regex("(?<!\\\\)\\*\\*(.+?)\\*\\*") to
                            SpanStyle(fontWeight = FontWeight.Bold),
                        Regex("(?<!\\\\)~~(.+?)~~") to
                            SpanStyle(textDecoration = TextDecoration.LineThrough),
                        Regex("(?<![\\w\\\\])_(.+?)_(?!\\w)") to
                            SpanStyle(fontStyle = FontStyle.Italic),
                        Regex("(?<!\\\\)`([^`]+)`") to
                            SpanStyle(
                                fontFamily = FontFamily.Monospace,
                                background = color.copy(alpha = .07f),
                            ),
                    )
                val codeRanges = Regex("(?<!\\\\)`([^`]+)`").findAll(line).map { it.range }.toList()
                patterns.forEach { (pattern, style) ->
                    pattern
                        .findAll(line)
                        .filter { m ->
                            style.fontFamily == FontFamily.Monospace ||
                                codeRanges.none { range ->
                                    m.range.first <= range.last && m.range.last >= range.first
                                }
                        }
                        .forEach { m ->
                            val inner = m.groups[1]!!
                            hide(offset + m.range.first, offset + inner.range.first)
                            hide(offset + inner.range.last + 1, offset + m.range.last + 1)
                            spans +=
                                Triple(
                                    (offset + inner.range.first) until
                                        (offset + inner.range.last + 1),
                                    style,
                                    false,
                                )
                        }
                }
            }
            offset += line.length + 1
        }
        val atomicRanges =
            NotebookReferenceSyntax.parse(source)
                .filter { replacements.containsKey(it.start) }
                .associateBy { it.start }
        val display = StringBuilder()
        val originalToDisplay = IntArray(source.length + 1)
        val displayToOriginal = mutableListOf(0)
        for (i in source.indices) {
            originalToDisplay[i] = display.length
            val emit = replacements[i] ?: if (hidden[i]) "" else source[i].toString()
            emit.forEach { char ->
                display.append(char)
                displayToOriginal += atomicRanges[i]?.end ?: (i + 1)
            }
            if (emit.isEmpty()) displayToOriginal[displayToOriginal.lastIndex] = i + 1
        }
        originalToDisplay[source.length] = display.length
        val annotated = buildAnnotatedString {
            append(display.toString())
            spans.forEach { (range, style, _) ->
                val a = originalToDisplay[range.first.coerceIn(0, source.length)]
                val b = originalToDisplay[(range.last + 1).coerceIn(0, source.length)]
                if (b > a) addStyle(style, a, b)
            }
        }
        return TransformedText(
            annotated,
            object : OffsetMapping {
                override fun originalToTransformed(offset: Int) =
                    originalToDisplay[offset.coerceIn(0, source.length)]

                override fun transformedToOriginal(offset: Int) =
                    displayToOriginal[offset.coerceIn(0, display.length)]
            },
        )
    }
}

internal data class EditorSnapshot(val title: String, val value: TextFieldValue)

/** Bounded undo history includes selections and titles and coalesces consecutive typing. */
internal class EditorUndoHistory {
    private val undo = ArrayDeque<EditorSnapshot>()
    private val redo = ArrayDeque<EditorSnapshot>()
    private var lastTypingAt = 0L
    private var lastTypingField: String? = null
    val canUndo
        get() = undo.isNotEmpty()

    val canRedo
        get() = redo.isNotEmpty()

    fun record(
        before: EditorSnapshot,
        typing: Boolean = false,
        now: Long = System.currentTimeMillis(),
        field: String = "body",
    ) {
        if (!typing || field != lastTypingField || now - lastTypingAt > 600 || undo.isEmpty())
            undo.addLast(before)
        lastTypingAt = if (typing) now else 0
        lastTypingField = if (typing) field else null
        redo.clear()
        while (
            undo.size > 80 ||
                (undo.size > 1 && undo.sumOf { it.value.text.length.toLong() } > 2_000_000)
        ) undo.removeFirst()
    }

    fun undo(current: EditorSnapshot): EditorSnapshot? {
        if (undo.isEmpty()) return null
        redo.addLast(current)
        lastTypingAt = 0
        return undo.removeLast()
    }

    fun redo(current: EditorSnapshot): EditorSnapshot? {
        if (redo.isEmpty()) return null
        undo.addLast(current)
        lastTypingAt = 0
        return redo.removeLast()
    }
}
