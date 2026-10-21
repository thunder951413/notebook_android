package io.github.notebook.android.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsTextTest {
    @Test fun `markdown syntax is dropped but words stay`() {
        val markdown = """
            # 标题

            这是 **粗体** 和 `代码`，见 [链接文字](https://example.com/x) 与 [[另一篇]]。
            ![图](notebook://asset/1)
            - [x] 完成的事
            1. 第一步
            > 引用一句
            ```kotlin
            val hidden = 1
            ```
            | 名称 | 数量 |
            |---|---|
            | 苹果 | 3 |
            ---
            访问 https://example.com/page 查看
        """.trimIndent()
        val text = TtsText.plainText(markdown)
        assertEquals(
            listOf("标题", "这是 粗体 和 代码，见 链接文字 与 另一篇。", "完成的事", "第一步", "引用一句", "名称，数量", "苹果，3", "访问  查看"),
            text.lines(),
        )
        assertFalse(text.contains("hidden"))
    }

    @Test fun `page references vanish`() {
        assertEquals("前文后文", TtsText.plainText("前文[[page:abc123]]后文"))
    }

    @Test fun `first piece is short and none exceeds the limit`() {
        val sentence = "这是一个用来测试分段的句子，大约二十个字。"
        val text = sentence.repeat(60)
        val pieces = TtsText.split(text, firstMax = 100, max = 400)
        assertTrue(pieces.first().length <= 100)
        assertTrue(pieces.all { it.length <= 400 })
        assertEquals(text, pieces.joinToString("").replace(" ", ""))
    }

    @Test fun `a sentence longer than the limit is cut`() {
        val long = "很长很长的一句话，".repeat(120) + "结束。"
        val pieces = TtsText.split(long, firstMax = 100, max = 400)
        assertTrue(pieces.size > 2)
        assertTrue(pieces.all { it.length <= 400 })
        assertEquals(long, pieces.joinToString("").replace(" ", ""))
    }

    @Test fun `blank or symbol only text gives nothing`() {
        assertTrue(TtsText.split("\n\n ... \n").isEmpty())
        assertTrue(TtsText.split("").isEmpty())
    }

    @Test fun `each piece reports where it starts in the text`() {
        val text = "第一句。第二句！\n第三句？"
        val pieces = TtsText.splitWithStarts(text, firstMax = 4, max = 5)
        assertEquals(listOf("第一句。", "第二句！", "第三句？"), pieces.map { it.text })
        assertEquals(listOf(0, 4, 9), pieces.map { it.start })
        pieces.forEach { assertTrue(text.startsWith(it.text.take(2), it.start)) }
    }

    @Test fun `spoken text maps back to the markdown it came from`() {
        val spans = listOf(SpokenSpan(0, 0, 0), SpokenSpan(3, 100, 40), SpokenSpan(13, 200, 10))
        assertEquals(0, spans.sourceOffsetAt(20, 0))
        assertEquals(100, spans.sourceOffsetAt(20, 3))
        assertEquals(120, spans.sourceOffsetAt(20, 8))
        assertEquals(200, spans.sourceOffsetAt(20, 13))
        assertEquals(-1, emptyList<SpokenSpan>().sourceOffsetAt(20, 5))
    }
}
