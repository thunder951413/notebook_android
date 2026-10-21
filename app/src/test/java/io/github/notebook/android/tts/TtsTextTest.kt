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
}
