package io.github.notebook.android

import io.github.notebook.android.sync.BlockDocumentCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotebookReferenceSyntaxTest {
    @Test fun `v3 reference syntax survives legacy markdown block round trip`() {
        val source = """
            # 引用测试

            [[page:target-page|目标笔记]]

            ![[page:target-page#^b-a1b2c3|关键结论]]

            <!-- notebook:group g-123:start title="两个自然段" -->
            第一段 ^b-first

            第二段
            <!-- notebook:group g-123:end -->
        """.trimIndent()

        assertEquals(source, BlockDocumentCodec.decodeMarkdown(BlockDocumentCodec.encodeMarkdown(source)))
    }

    @Test fun `reader hides protocol markers while retaining readable reference labels`() {
        val source = """
            [[page:target-page|目标笔记]]
            ![[page:target-page#group:g-123|两个自然段]]
            正文 ^b-first
            <!-- notebook:group g-123:end -->
        """.trimIndent()
        val display = NotebookReferenceSyntax.forDisplay(source)

        assertTrue(display.contains("[目标笔记](notebook://page/target-page)"))
        assertTrue(display.contains("[嵌入引用：两个自然段](notebook://page/target-page?group=g-123)"))
        assertTrue(display.contains("正文"))
        assertFalse(display.contains("[[page:"))
        assertFalse(display.contains("^b-first"))
        assertFalse(display.contains("notebook:group"))
    }

    @Test fun `parser retains page block group alias and embed semantics`() {
        val references = NotebookReferenceSyntax.parse("""
            [[page:page-a]]
            [[page:page-b#^block-1|固定文字]]
            ![[page:page-c#group:group-1]]
        """.trimIndent())
        assertEquals(listOf("page","block","group"),references.map{it.kind})
        assertEquals(listOf(false,false,true),references.map{it.embed})
        assertEquals("固定文字",references[1].alias)
        assertEquals("block-1",references[1].targetId)
    }

    @Test fun `block and contiguous group content resolve from canonical markdown`() {
        val target = """
            # 标题

            第一段
            第二行 ^block-a

            <!-- notebook:group group-a:start title="范围" -->
            第二段。

            第三段。
            <!-- notebook:group group-a:end -->
        """.trimIndent()
        val block = NotebookReferenceSyntax.parse("[[page:p#^block-a]]").single()
        val group = NotebookReferenceSyntax.parse("![[page:p#group:group-a]]").single()
        assertEquals("第一段\n第二行",NotebookReferenceSyntax.extractTarget(target,block))
        assertEquals("第二段。\n\n第三段。",NotebookReferenceSyntax.extractTarget(target,group))
    }

    @Test fun `embed renders target content without leaking protocol markers`() {
        val reference = "![[page:p#^b-one]]"
        val display = NotebookReferenceSyntax.forDisplay(reference,{ "目标页" }) {
            "动态正文 ^b-one"
        }
        assertTrue(display.contains("动态正文"))
        assertTrue(display.contains("打开原文"))
        assertFalse(display.contains("^b-one"))
    }
}
