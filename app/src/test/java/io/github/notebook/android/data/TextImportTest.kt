package io.github.notebook.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TextImportTest {
    @Test fun `recognises formats`() {
        assertEquals(TextImport.Kind.Markdown, TextImport.kind("a.MD"))
        assertEquals(TextImport.Kind.Text, TextImport.kind("b.txt"))
        assertEquals(TextImport.Kind.Html, TextImport.kind("c.htm"))
        assertNull(TextImport.kind("d.pdf"))
    }

    @Test fun `decodes utf8 with bom and gb18030`() {
        assertEquals("笔记", TextImport.decode(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "笔记".toByteArray()))
        assertEquals("中文", TextImport.decode(byteArrayOf(0xD6.toByte(), 0xD0.toByte(), 0xCE.toByte(), 0xC4.toByte())))
        assertEquals("a\nb", TextImport.decode("a\r\nb".toByteArray()))
    }

    @Test fun `titles and plain text`() {
        assertEquals("会议记录", TextImport.title("会议记录.md", "# 别的", TextImport.Kind.Markdown))
        assertEquals("第一行", TextImport.title("新建文本文档.txt", "\n第一行\n第二行", TextImport.Kind.Text))
        assertEquals("\\# 不是标题  \n\\- 不是列表\n\n新段落", TextImport.plainTextToMarkdown("# 不是标题\n- 不是列表\n\n新段落"))
        assertEquals("正文", TextImport.stripRepeatedTitle("# 计划\n\n正文", "计划"))
    }

    @Test fun `html becomes markdown`() {
        val markdown = TextImport.htmlToMarkdown("<html><head><title>T</title><style>p{}</style></head><body><h1>标题</h1><p>一段<strong>重点</strong>，<a href=\"https://example.com\">链接</a><br>换行</p><ul><li>甲</li><li>乙</li></ul><pre>code &lt;x&gt;\n  indented</pre><script>alert(1)</script></body></html>")
        assertTrue(markdown, markdown.startsWith("# 标题\n\n一段**重点**，[链接](https://example.com)  \n换行"))
        assertTrue(markdown, markdown.contains("- 甲\n- 乙"))
        assertTrue(markdown, markdown.contains("```\ncode <x>\n  indented\n```"))
        assertTrue(markdown, !markdown.contains("alert"))
    }

    @Test fun `summary`() {
        assertEquals("已导入 1 篇笔记，跳过 1 个文件：a.pdf（不支持的格式）", TextImport.summary(1, listOf(TextImport.Skip("a.pdf", "不支持的格式"))))
    }
}
