package io.github.notebook.android

/**
 * Canonical Notebook references live in Markdown. Everything returned by this parser is a
 * rebuildable projection used by navigation and rendering; callers must keep the source intact.
 */
internal data class NotebookReference(
    val pageId: String,
    val kind: String,
    val targetId: String?,
    val alias: String?,
    val embed: Boolean,
    val start: Int,
    val end: Int
) {
    val stableKey: String get() = "$pageId:$kind:${targetId.orEmpty()}:${if (embed) "embed" else "link"}:$start"
    val uri: String get() = buildString {
        append("notebook://page/").append(pageId)
        when (kind) {
            "block" -> append("?block=").append(targetId)
            "group" -> append("?group=").append(targetId)
        }
    }
}

internal object NotebookReferenceSyntax {
    private val reference = Regex(
        """(!)?\[\[page:([A-Za-z0-9][A-Za-z0-9._:-]*)(?:#(?:\^([A-Za-z0-9][A-Za-z0-9._:-]*)|group:([A-Za-z0-9][A-Za-z0-9._:-]*)))?(?:\|([^\]\n]+))?\]\]"""
    )
    private val blockAnchor = Regex("""[ \t]+\^([A-Za-z0-9][A-Za-z0-9._:-]*)[ \t]*$""", RegexOption.MULTILINE)
    private val groupBoundary = Regex(
        """^[ \t]*<!--[ \t]*notebook:group[ \t]+([A-Za-z0-9][A-Za-z0-9._:-]*):(?:start(?:[ \t]+title="([^"\n]*)")?|end)[ \t]*-->[ \t]*(?:\r?\n)?""",
        setOf(RegexOption.MULTILINE)
    )

    fun parse(markdown: String): List<NotebookReference> = reference.findAll(markdown).map { match ->
        val blockId = match.groupValues[3].ifBlank { null }
        val groupId = match.groupValues[4].ifBlank { null }
        NotebookReference(
            pageId = match.groupValues[2],
            kind = when {
                blockId != null -> "block"
                groupId != null -> "group"
                else -> "page"
            },
            targetId = blockId ?: groupId,
            alias = match.groupValues[5].trim().ifBlank { null },
            embed = match.groupValues[1].isNotEmpty(),
            start = match.range.first,
            end = match.range.last + 1
        )
    }.toList()

    /**
     * Produces safe display Markdown. References become app-local links; embeds use current target
     * content when available and deliberately do not recursively expand nested embeds.
     */
    fun forDisplay(
        markdown: String,
        pageTitle: (String) -> String? = { null },
        targetContent: (NotebookReference) -> String? = { null }
    ): String {
        val withoutBoundaries = groupBoundary.replace(markdown, "")
        val withoutAnchors = blockAnchor.replace(withoutBoundaries, "")
        return reference.replace(withoutAnchors) { match ->
            val parsed = parse(match.value).single()
            val fallback = pageTitle(parsed.pageId)?.ifBlank { null } ?: "关联页面 ${parsed.pageId.take(8)}"
            val dynamic = targetContent(parsed)?.trim()?.takeIf(String::isNotEmpty)
            val label = parsed.alias ?: when (parsed.kind) {
                "page" -> fallback
                else -> dynamic?.lineSequence()?.firstOrNull()?.removeSuffix(" ^${parsed.targetId}")?.take(80) ?: fallback
            }
            if (parsed.embed) {
                val body = dynamic?.let { stripProtocolMarkers(it) }
                if (body == null) "> [嵌入引用：$label](${parsed.uri})"
                else "$body\n\n[打开原文 · $label](${parsed.uri})"
            } else "[$label](${parsed.uri})"
        }
    }

    fun extractTarget(markdown: String, reference: NotebookReference): String? {
        return when (reference.kind) {
            "page" -> markdown
            "block" -> reference.targetId?.let { extractBlock(markdown, it) }
            "group" -> reference.targetId?.let { extractGroup(markdown, it) }
            else -> null
        }
    }

    private fun extractBlock(markdown: String, blockId: String): String? {
        val anchor = Regex("""[ \t]+\^${Regex.escape(blockId)}[ \t]*$""", RegexOption.MULTILINE).find(markdown) ?: return null
        var start = anchor.range.first
        while (start > 0) {
            val previousBreak = markdown.lastIndexOf('\n', start - 1)
            if (previousBreak < 0) { start = 0; break }
            val previousLineStart = markdown.lastIndexOf('\n', previousBreak - 1) + 1
            if (markdown.substring(previousLineStart, previousBreak).isBlank()) break
            start = previousLineStart
        }
        var end = markdown.indexOf("\n\n", anchor.range.last + 1).let { if (it < 0) markdown.length else it }
        if (end < start) end = markdown.length
        return markdown.substring(start, end)
            .replace(Regex("""[ \t]+\^${Regex.escape(blockId)}[ \t]*$""", RegexOption.MULTILINE), "")
            .trim()
    }

    private fun extractGroup(markdown: String, groupId: String): String? {
        val escaped = Regex.escape(groupId)
        val start = Regex(
            """^[ \t]*<!--[ \t]*notebook:group[ \t]+$escaped:start(?:[ \t]+title="[^"\n]*")?[ \t]*-->[ \t]*\r?\n?""",
            RegexOption.MULTILINE
        ).find(markdown) ?: return null
        val end = Regex(
            """^[ \t]*<!--[ \t]*notebook:group[ \t]+$escaped:end[ \t]*-->[ \t]*""",
            RegexOption.MULTILINE
        ).find(markdown, start.range.last + 1) ?: return null
        return markdown.substring(start.range.last + 1, end.range.first).trim()
    }

    private fun stripProtocolMarkers(markdown: String): String =
        blockAnchor.replace(groupBoundary.replace(markdown, ""), "")
}
