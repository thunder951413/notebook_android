package io.github.notebook.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownDocumentTest {
    @Test fun `semantic parser keeps fenced code and tables intact`() {
        val source = """
            # 标题

            正文第一行
            正文第二行

            | A | B |
            |---|---|
            | 1 | 2 |

            ```kotlin
            val value = "# 不是标题"
            ```
        """.trimIndent()

        val document = MarkdownDocumentParser.parse(source, preferredBlockChars = 32)

        assertEquals(1, document.headings.size)
        assertEquals("标题", document.headings.single().title)
        assertEquals(1, document.blocks.count { it.kind == MarkdownBlockKind.Table })
        assertEquals(1, document.blocks.count { it.kind == MarkdownBlockKind.Code })
        assertTrue(document.blocks.first { it.kind == MarkdownBlockKind.Code }.text.contains("不是标题"))
    }

    @Test fun `block ids remain stable when content is inserted before a block`() {
        val original = MarkdownDocumentParser.parse("# A\n\n固定段落\n")
        val changed = MarkdownDocumentParser.parse("前置内容\n\n# A\n\n固定段落\n")
        val originalParagraph = original.blocks.first { it.text.contains("固定段落") }
        val changedParagraph = changed.blocks.first { it.text.contains("固定段落") }
        assertEquals(originalParagraph.id, changedParagraph.id)
    }

    @Test fun `large paragraph splits on utf16 source boundaries without loss`() {
        val source = (1..200).joinToString("\n") { "第 $it 行内容" }
        val document = MarkdownDocumentParser.parse(source, preferredBlockChars = 128)
        assertTrue(document.blocks.size > 1)
        assertEquals(source, document.blocks.joinToString("") { it.text })
        document.blocks.zipWithNext().forEach { (left, right) -> assertEquals(left.endUtf16, right.startUtf16) }
    }

    @Test fun `six hundred kilobyte document remains lossless and addressable`() {
        val source = buildString {
            repeat(4_000) { chapter ->
                append("## 第 ").append(chapter).append(" 节\n\n")
                append("这是用于验证长文阅读窗口、中文换行和 UTF-16 锚点的正文。")
                append("内容编号 ").append(chapter).append("，并包含 emoji 🧭。".repeat(5))
                append("\n\n")
            }
        }
        assertTrue(source.toByteArray().size > 600 * 1024)

        val document = MarkdownDocumentParser.parse(source)

        assertEquals(source, document.blocks.joinToString("") { it.text })
        assertEquals(4_000, document.headings.size)
        assertEquals(source.length, document.blocks.last().endUtf16)
        assertTrue(document.blocks.map { it.id }.toSet().size == document.blocks.size)
    }
}
