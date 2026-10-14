package io.github.notebook.android.data

import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * Turning loose Markdown, plain-text and HTML files into notes. Same rules as
 * the desktop app (apps/renderer/src/data/textImport.ts).
 */
object TextImport {
    enum class Kind { Markdown, Text, Html }

    const val MAX_BYTES = 5L * 1024 * 1024
    val MIME_TYPES = arrayOf("text/plain", "text/markdown", "text/x-markdown", "text/html", "application/octet-stream")

    data class Skip(val name: String, val reason: String)

    fun kind(name: String): Kind? = when (name.substringAfterLast('.', "").lowercase()) {
        "md", "markdown" -> Kind.Markdown
        "txt", "text" -> Kind.Text
        "html", "htm" -> Kind.Html
        else -> null
    }

    /** UTF-8 (BOM or not) / UTF-16 with BOM first; GB18030 covers files from older Chinese tools. */
    fun decode(bytes: ByteArray): String {
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) return String(bytes, 2, bytes.size - 2, StandardCharsets.UTF_16LE)
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) return String(bytes, 2, bytes.size - 2, StandardCharsets.UTF_16BE)
        val strict = { charset: Charset -> runCatching { charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString() }.getOrNull() }
        val text = strict(StandardCharsets.UTF_8)
            ?: runCatching { Charset.forName("GB18030") }.getOrNull()?.let(strict)
            ?: String(bytes, StandardCharsets.UTF_8)
        return text.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')
    }

    fun title(fileName: String, source: String, kind: Kind): String {
        val base = fileName.substringAfterLast('/').substringAfterLast('\\')
        val fromName = if (kind(base) != null) base.substringBeforeLast('.').trim() else base.trim()
        if (fromName.isNotEmpty() && !Regex("^(untitled|新建文本文档|未命名)", RegexOption.IGNORE_CASE).containsMatchIn(fromName)) return fromName
        if (kind == Kind.Html) Regex("<title[^>]*>([^<]+)</title>", RegexOption.IGNORE_CASE).find(source)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        val first = source.lineSequence().map { it.replace(Regex("^#+\\s*"), "").trim() }.firstOrNull { it.isNotEmpty() }
        return (first ?: fromName).take(80).ifEmpty { "导入的笔记" }
    }

    /** Plain text keeps its line breaks and is never read as Markdown. */
    fun plainTextToMarkdown(text: String): String = text.split(Regex("\n{2,}")).joinToString("\n\n") { paragraph ->
        paragraph.split('\n').joinToString("  \n") { line ->
            line.replace(Regex("[\\\\`*_\\[\\]<>]")) { "\\" + it.value }
                .replace(Regex("^(\\s*)([#>+-]|\\d+[.)])(\\s)"), "$1\\\\$2$3")
        }
    }.trim()

    /** Drops a leading "# Title" that only repeats the note title. */
    fun stripRepeatedTitle(markdown: String, title: String): String {
        val match = Regex("^\\s*#\\s+(.+?)\\s*#*\\s*(\n|$)").find(markdown) ?: return markdown
        return if (match.groupValues[1].trim() == title.trim()) markdown.substring(match.range.last + 1).trimStart('\n') else markdown
    }

    /**
     * A small HTML → Markdown converter for saved web pages and exported
     * documents: headings, paragraphs, line breaks, lists, links, emphasis,
     * code and quotes. Scripts, styles and all other markup are dropped.
     */
    fun htmlToMarkdown(html: String): String {
        var text = html
        val bodyMatch = Regex("<body[^>]*>([\\s\\S]*)</body>", RegexOption.IGNORE_CASE).find(text)
        if (bodyMatch != null) text = bodyMatch.groupValues[1]
        text = text.replace(Regex("<(script|style|noscript|template|head)[^>]*>[\\s\\S]*?</\\1>", RegexOption.IGNORE_CASE), "")
            .replace(Regex("<!--[\\s\\S]*?-->"), "")
        // Preformatted blocks keep their text verbatim.
        val blocks = mutableListOf<String>()
        text = Regex("<pre[^>]*>([\\s\\S]*?)</pre>", RegexOption.IGNORE_CASE).replace(text) {
            blocks += "```\n" + unescape(it.groupValues[1].replace(Regex("<[^>]+>"), "")).trimEnd() + "\n```"
            "\n\n\u0000${blocks.size - 1}\u0000\n\n"
        }
        text = text.replace(Regex("\\s+"), " ")
        for (level in 1..6) text = text.replace(Regex("<h$level[^>]*>(.*?)</h$level>", RegexOption.IGNORE_CASE)) { "\n\n${"#".repeat(level)} ${it.groupValues[1].trim()}\n\n" }
        text = text
            .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\u0001")
            .replace(Regex("<li[^>]*>", RegexOption.IGNORE_CASE), "\n- ")
            .replace(Regex("</li>", RegexOption.IGNORE_CASE), "")
            .replace(Regex("</(p|div|section|article|ul|ol|table|tr|blockquote|header|footer)>", RegexOption.IGNORE_CASE), "\n\n")
            .replace(Regex("<(p|div|section|article|ul|ol|table|tr|header|footer)[^>]*>", RegexOption.IGNORE_CASE), "\n\n")
            .replace(Regex("<blockquote[^>]*>", RegexOption.IGNORE_CASE), "\n\n> ")
            .replace(Regex("<(td|th)[^>]*>", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("<(strong|b)[^>]*>(.*?)</\\1>", RegexOption.IGNORE_CASE)) { "**${it.groupValues[2]}**" }
            .replace(Regex("<(em|i)[^>]*>(.*?)</\\1>", RegexOption.IGNORE_CASE)) { "*${it.groupValues[2]}*" }
            .replace(Regex("<code[^>]*>(.*?)</code>", RegexOption.IGNORE_CASE)) { "`${it.groupValues[1]}`" }
            .replace(Regex("<a[^>]*href=\"(https?://[^\"]+)\"[^>]*>(.*?)</a>", RegexOption.IGNORE_CASE)) { "[${it.groupValues[2]}](${it.groupValues[1]})" }
            .replace(Regex("<img[^>]*alt=\"([^\"]*)\"[^>]*>", RegexOption.IGNORE_CASE)) { if (it.groupValues[1].isBlank()) "" else "（图片：${it.groupValues[1]}）" }
            .replace(Regex("<[^>]+>"), "")
        text = unescape(text)
            .lines().joinToString("\n") { it.trim() }
            .replace(Regex("[ \t]*\u0001[ \t]*"), "  \n")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
        return Regex("\u0000(\\d+)\u0000").replace(text) { blocks[it.groupValues[1].toInt()] }
    }

    private fun unescape(value: String) = value
        .replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
        .replace(Regex("&#(\\d+);")) { it.groupValues[1].toIntOrNull()?.let { code -> String(Character.toChars(code)) } ?: it.value }
        .replace("&amp;", "&")

    /** Markdown body for an imported file. */
    fun toMarkdown(kind: Kind, source: String, title: String): String = when (kind) {
        Kind.Markdown -> stripRepeatedTitle(source, title)
        Kind.Text -> plainTextToMarkdown(source)
        Kind.Html -> stripRepeatedTitle(htmlToMarkdown(source), title)
    }

    fun summary(imported: Int, skipped: List<Skip>): String {
        if (skipped.isEmpty()) return "已导入 $imported 篇笔记"
        val reasons = skipped.take(3).joinToString("、") { "${it.name}（${it.reason}）" }
        return "已导入 $imported 篇笔记，跳过 ${skipped.size} 个文件：$reasons${if (skipped.size > 3) " 等" else ""}"
    }
}
