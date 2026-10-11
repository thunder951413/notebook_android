package io.github.notebook.android

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.*
import org.junit.Test

class MarkdownEditingTest {
    @Test
    fun visualFormattingPreservesSourceAndMonotoneBoundedOffsets() {
        val samples =
            listOf(
                "# 标题\n\n中文 **粗体**、_斜体_与~~删除~~\n- [x] 完成\n- [ ] 下一项",
                "## 😀组合输入\n- 项目\n> 引用\n```\n**原样代码**\n```",
                "未知 <span style=\"color:red\">格式</span>\n![[page:123]]\n![图](notebook-asset:abc)",
                "**空行**\n\n\n结尾",
                "",
                "# ",
                "**",
            )
        for (source in samples) {
            val transformed =
                MarkdownVisualTransformation(Color.Black).filter(AnnotatedString(source))
            val map = transformed.offsetMapping
            val forward = (0..source.length).map(map::originalToTransformed)
            val reverse = (0..transformed.text.length).map(map::transformedToOriginal)
            assertTrue(forward.zipWithNext().all { it.first <= it.second })
            assertTrue(reverse.zipWithNext().all { it.first <= it.second })
            assertTrue(forward.all { it in 0..transformed.text.length })
            assertTrue(reverse.all { it in 0..source.length })
            assertEquals(transformed.text.length, forward.last())
            assertEquals(source.length, reverse.last())
        }
        val display =
            MarkdownVisualTransformation(Color.Black).filter(AnnotatedString(samples[0])).text.text
        assertTrue(display.startsWith("标题"))
        assertTrue(display.contains("中文 粗体、斜体与删除"))
        assertTrue(display.contains("☑"))
        assertTrue(display.contains("☐"))
    }

    @Test
    fun formattingSelectedChineseTogglesWithoutMovingSelectionOutOfText() {
        val original = TextFieldValue("前缀中文后缀", TextRange(2, 4))
        val bold = MarkdownCommands.apply(original, MarkdownCommand.Bold)
        assertEquals("前缀**中文**后缀", bold.text)
        assertEquals("中文", bold.text.substring(bold.selection.min, bold.selection.max))
        assertEquals(original, MarkdownCommands.apply(bold, MarkdownCommand.Bold))
    }

    @Test
    fun multipleLineHeadingAndListCommandsDoNotDestroyAdjacentBlocks() {
        val original = TextFieldValue("保留\n第一行\n第二行\n尾部", TextRange(3, 10))
        val value = MarkdownCommands.apply(original, MarkdownCommand.H2)
        assertEquals("保留\n## 第一行\n## 第二行\n尾部", value.text)
        val body = MarkdownCommands.apply(value, MarkdownCommand.Body)
        assertEquals(original.text, body.text)
    }

    @Test
    fun insertAtCaretAndCancelReferenceNeedNoPlaceholderMutation() {
        val value = TextFieldValue("左右", TextRange(1))
        val result = MarkdownCommands.insert(value, "[[page:abc]]")
        assertEquals("左[[page:abc]]右", result.text)
        assertEquals(TextRange(13), result.selection)
        assertEquals("左右", value.text)
    }

    @Test
    fun undoTypingCoalescesAndRestoresSelectionAndTitle() {
        val history = EditorUndoHistory()
        val a = EditorSnapshot("旧标题", TextFieldValue("中文", TextRange(1)))
        val b = EditorSnapshot("新标题", TextFieldValue("中文输入", TextRange(4)))
        val c = EditorSnapshot("新标题", TextFieldValue("中文输入完成", TextRange(6)))
        history.record(a, true, 1000)
        history.record(b, true, 1200)
        assertEquals(a, history.undo(c))
        assertEquals(c, history.redo(a))
        history.record(b, false, 1300)
        assertFalse(history.canRedo)
    }

    @Test
    fun tableEscapedPipesAlignmentAndChineseRoundTrip() {
        val text = "| 名称 | 说明 |\n| :--- | ---: |\n| 中文 | a\\|b |\n"
        val table = MarkdownTable.parse(text)
        assertEquals("a|b", table.rows[1][1])
        assertEquals(table, MarkdownTable.parse(table.markdown()))
        assertEquals(listOf(":---", "---:"), table.separators)
    }

    @Test
    fun richBackspaceDeletesVisibleCharacterInsteadOfBreakingClosingMarkers() {
        val before = TextFieldValue("**中文**", TextRange(6))
        val after = MarkdownCommands.normalizeInput(before, TextFieldValue("**中文*", TextRange(5)))
        assertEquals("**中**", after.text)
        assertEquals(TextRange(5), after.selection)
    }

    @Test
    fun taskEnterContinuesUncheckedItemAndEmptyItemEndsList() {
        val before = TextFieldValue("- [x] 已完成", TextRange(9))
        val next =
            MarkdownCommands.normalizeInput(before, TextFieldValue("- [x] 已完成\n", TextRange(10)))
        assertEquals("- [x] 已完成\n- [ ] ", next.text)
        val end =
            MarkdownCommands.normalizeInput(
                TextFieldValue("- [ ] ", TextRange(6)),
                TextFieldValue("- [ ] \n", TextRange(7)),
            )
        assertEquals("\n", end.text)
    }

    @Test
    fun inlineCodeKeepsLiteralMarkdownMarkers() {
        val display =
            MarkdownVisualTransformation(Color.Black)
                .filter(AnnotatedString("`**代码**` 与 **格式**"))
                .text
                .text
        assertEquals("**代码** 与 格式", display)
    }

    @Test
    fun longDocumentPagesPreserveEveryUtf16UnitWithoutSplittingEmoji() {
        val source = "中文😀".repeat(5000)
        val pages = markdownEditingPages(source, 1024)
        assertEquals(source, pages.joinToString("") { source.substring(it) })
        assertTrue(pages.drop(1).all { !Character.isLowSurrogate(source[it.first]) })
        assertTrue(pages.all { it.last - it.first < 1024 })
    }

    @Test
    fun titleBodyAndFormattingHaveSeparateUndoGroupsEvenWithinOneTypingBurst() {
        val history = EditorUndoHistory()
        val initial = EditorSnapshot("", TextFieldValue(""))
        val title = EditorSnapshot("标题", TextFieldValue(""))
        val body = EditorSnapshot("标题", TextFieldValue("正文"))
        val formatted = EditorSnapshot("标题", TextFieldValue("### 正文"))
        history.record(initial, true, 1000, "title")
        history.record(title, true, 1100, "body")
        history.record(body, false, 1200)
        assertEquals(body, history.undo(formatted))
        assertEquals(title, history.undo(body))
        assertEquals(initial, history.undo(title))
    }

    @Test
    fun referenceLabelsAreAtomicButReferencesInsideCodeRemainLiteral() {
        val token = "[[page:abc|关联中文]]"
        val source = "$token 与 `$token`\n```\n$token\n```"
        val transformed = MarkdownVisualTransformation(Color.Black).filter(AnnotatedString(source))
        assertTrue(transformed.text.text.startsWith("@ 关联中文 与 "))
        assertEquals(2, Regex(Regex.escape(token)).findAll(transformed.text.text).count())
        val reverse =
            (0..transformed.text.length).map(transformed.offsetMapping::transformedToOriginal)
        assertTrue(reverse.zipWithNext().all { it.first <= it.second })
        val backspace =
            MarkdownCommands.normalizeInput(
                TextFieldValue(token, TextRange(token.length)),
                TextFieldValue(token.dropLast(1), TextRange(token.length - 1)),
            )
        assertEquals("", backspace.text)
    }

    @Test
    fun insertedCodeSelectsPayloadAndMultilineTableCellsRoundTrip() {
        val code =
            MarkdownCommands.apply(TextFieldValue("中文", TextRange(0, 2)), MarkdownCommand.Code)
        assertEquals("中文", code.text.substring(6, code.selection.start))
        val table = MarkdownTable(listOf(listOf("标题"), listOf("第一行\n第二行")), listOf("---"))
        assertEquals(table, MarkdownTable.parse(table.markdown()))
    }
}
