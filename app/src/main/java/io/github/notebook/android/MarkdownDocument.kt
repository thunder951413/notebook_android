package io.github.notebook.android

import java.security.MessageDigest

internal enum class MarkdownBlockKind {
    Heading,
    Paragraph,
    List,
    Quote,
    Code,
    Table,
    Rule,
    Blank
}

internal data class MarkdownBlock(
    val id: String,
    val startUtf16: Int,
    val endUtf16: Int,
    val text: String,
    val kind: MarkdownBlockKind,
    val headingLevel: Int? = null,
    val headingTitle: String? = null,
    val headingPath: String? = null
)

internal data class MarkdownHeading(
    val blockId: String,
    val sourceOffsetUtf16: Int,
    val level: Int,
    val title: String,
    val path: String
)

internal data class ParsedMarkdownDocument(
    val blocks: List<MarkdownBlock>,
    val headings: List<MarkdownHeading>
)

/**
 * Splits Markdown at semantic block boundaries so every block can be rendered lazily without
 * breaking fenced code, tables or list runs. Kotlin String offsets are UTF-16 offsets, matching
 * Android TextView and the macOS reading-position protocol.
 */
internal object MarkdownDocumentParser {
    private val headingPattern = Regex("^(#{1,6})[\\t ]+(.+?)\\s*$")
    private val listPattern = Regex("^[\\t ]*(?:[-+*][\\t ]+|\\d+[.)][\\t ]+|[-+*][\\t ]+\\[[ xX]][\\t ]+).+")
    private val quotePattern = Regex("^[\\t ]*>.*")
    private val tableSeparatorPattern = Regex("^[\\t ]*\\|?[\\t ]*:?-{3,}:?[\\t ]*(?:\\|[\\t ]*:?-{3,}:?[\\t ]*)+\\|?[\\t ]*$")
    private val rulePattern = Regex("^[\\t ]{0,3}(?:([-*_])[\\t ]*){3,}$")

    private data class SourceLine(
        val start: Int,
        val end: Int,
        val content: String
    )

    fun parse(markdown: String, preferredBlockChars: Int = 16 * 1024): ParsedMarkdownDocument {
        if (markdown.isEmpty()) return ParsedMarkdownDocument(emptyList(), emptyList())
        val lines = sourceLines(markdown)
        val rawBlocks = mutableListOf<RawBlock>()
        var index = 0
        while (index < lines.size) {
            val line = lines[index]
            val trimmed = line.content.trimStart()
            if (line.content.isBlank()) {
                val start = index
                while (index + 1 < lines.size && lines[index + 1].content.isBlank()) index++
                rawBlocks += RawBlock(lines[start].start, lines[index].end, MarkdownBlockKind.Blank)
                index++
                continue
            }

            val fence = fenceMarker(trimmed)
            if (fence != null) {
                val start = index
                index++
                while (index < lines.size) {
                    if (closesFence(lines[index].content.trimStart(), fence)) {
                        index++
                        break
                    }
                    index++
                }
                rawBlocks += RawBlock(lines[start].start, lines[index - 1].end, MarkdownBlockKind.Code)
                continue
            }

            val headingMatch = headingPattern.matchEntire(line.content)
            if (headingMatch != null) {
                rawBlocks += RawBlock(
                    line.start,
                    line.end,
                    MarkdownBlockKind.Heading,
                    headingLevel = headingMatch.groupValues[1].length,
                    headingTitle = headingMatch.groupValues[2].trim()
                )
                index++
                continue
            }

            if (isTableStart(lines, index)) {
                val start = index
                index += 2
                while (index < lines.size && looksLikeTableRow(lines[index].content)) index++
                rawBlocks += RawBlock(lines[start].start, lines[index - 1].end, MarkdownBlockKind.Table)
                continue
            }

            if (listPattern.matches(line.content)) {
                val start = index
                index++
                while (index < lines.size && !lines[index].content.isBlank() &&
                    (listPattern.matches(lines[index].content) || lines[index].content.startsWith(" ") || lines[index].content.startsWith("\t"))) {
                    index++
                }
                rawBlocks += RawBlock(lines[start].start, lines[index - 1].end, MarkdownBlockKind.List)
                continue
            }

            if (quotePattern.matches(line.content)) {
                val start = index
                index++
                while (index < lines.size && quotePattern.matches(lines[index].content)) index++
                rawBlocks += RawBlock(lines[start].start, lines[index - 1].end, MarkdownBlockKind.Quote)
                continue
            }

            if (rulePattern.matches(line.content)) {
                rawBlocks += RawBlock(line.start, line.end, MarkdownBlockKind.Rule)
                index++
                continue
            }

            val start = index
            index++
            while (index < lines.size && !startsNewBlock(lines, index)) index++
            val paragraphEnd = lines[index - 1].end
            splitLargeParagraph(markdown, lines[start].start, paragraphEnd, preferredBlockChars, rawBlocks)
        }

        val headings = mutableListOf<MarkdownHeading>()
        val headingStack = mutableListOf<Pair<Int, String>>()
        val duplicateIDs = mutableMapOf<String, Int>()
        val blocks = rawBlocks.map { raw ->
            val text = markdown.substring(raw.start, raw.end)
            val headingPath = if (raw.kind == MarkdownBlockKind.Heading) {
                val level = raw.headingLevel ?: 1
                while (headingStack.isNotEmpty() && headingStack.last().first >= level) headingStack.removeAt(headingStack.lastIndex)
                headingStack += level to raw.headingTitle.orEmpty()
                headingStack.joinToString(" / ") { it.second }
            } else {
                headingStack.joinToString(" / ") { it.second }.ifBlank { null }
            }
            val baseID = stableID(raw.kind, headingPath, text)
            val occurrence = duplicateIDs.getOrDefault(baseID, 0)
            duplicateIDs[baseID] = occurrence + 1
            val id = if (occurrence == 0) baseID else "$baseID-$occurrence"
            MarkdownBlock(id, raw.start, raw.end, text, raw.kind, raw.headingLevel, raw.headingTitle, headingPath).also { block ->
                if (block.kind == MarkdownBlockKind.Heading) {
                    headings += MarkdownHeading(
                        blockId = block.id,
                        sourceOffsetUtf16 = block.startUtf16,
                        level = block.headingLevel ?: 1,
                        title = block.headingTitle.orEmpty(),
                        path = block.headingPath.orEmpty()
                    )
                }
            }
        }
        return ParsedMarkdownDocument(blocks, headings)
    }

    private data class RawBlock(
        val start: Int,
        val end: Int,
        val kind: MarkdownBlockKind,
        val headingLevel: Int? = null,
        val headingTitle: String? = null
    )

    private fun sourceLines(markdown: String): List<SourceLine> {
        val result = mutableListOf<SourceLine>()
        var start = 0
        while (start < markdown.length) {
            val newline = markdown.indexOf('\n', start)
            val end = if (newline == -1) markdown.length else newline + 1
            val contentEnd = if (newline == -1) end else newline
            result += SourceLine(start, end, markdown.substring(start, contentEnd).removeSuffix("\r"))
            start = end
        }
        return result
    }

    private fun fenceMarker(trimmed: String): String? {
        val marker = when {
            trimmed.startsWith("```") -> "`"
            trimmed.startsWith("~~~") -> "~"
            else -> return null
        }
        val count = trimmed.takeWhile { it.toString() == marker }.length
        return marker.repeat(count.coerceAtLeast(3))
    }

    private fun closesFence(trimmed: String, fence: String): Boolean =
        trimmed.takeWhile { it == fence[0] }.length >= fence.length

    private fun looksLikeTableRow(value: String): Boolean = value.count { it == '|' } >= 1 && value.isNotBlank()

    private fun isTableStart(lines: List<SourceLine>, index: Int): Boolean =
        index + 1 < lines.size && looksLikeTableRow(lines[index].content) && tableSeparatorPattern.matches(lines[index + 1].content)

    private fun startsNewBlock(lines: List<SourceLine>, index: Int): Boolean {
        val value = lines[index].content
        return value.isBlank() || fenceMarker(value.trimStart()) != null || headingPattern.matches(value) ||
            isTableStart(lines, index) || listPattern.matches(value) || quotePattern.matches(value) || rulePattern.matches(value)
    }

    private fun splitLargeParagraph(
        markdown: String,
        start: Int,
        end: Int,
        preferredBlockChars: Int,
        output: MutableList<RawBlock>
    ) {
        var cursor = start
        val limit = preferredBlockChars.coerceAtLeast(1024)
        while (end - cursor > limit) {
            val proposed = cursor + limit
            val newline = markdown.lastIndexOf('\n', proposed).takeIf { it >= cursor + limit / 2 }
            val split = (newline?.plus(1) ?: proposed).coerceIn(cursor + 1, end)
            output += RawBlock(cursor, split, MarkdownBlockKind.Paragraph)
            cursor = split
        }
        if (cursor < end) output += RawBlock(cursor, end, MarkdownBlockKind.Paragraph)
    }

    private fun stableID(kind: MarkdownBlockKind, headingPath: String?, text: String): String {
        val normalizedPrefix = text.trim().replace(Regex("\\s+"), " ").take(256)
        val input = "${kind.name}|${headingPath.orEmpty()}|$normalizedPrefix"
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return kind.name.lowercase() + "-" + digest.take(8).joinToString("") { "%02x".format(it) }
    }
}
