package io.github.notebook.android.ai

import io.github.notebook.android.data.FolderEntity
import io.github.notebook.android.data.NoteSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiAssistantTest {
    private fun note(id: String, title: String, folder: String? = "f1", parent: String? = null, order: Double = 0.0, type: String = "note") =
        NoteSummary(id, title, "", 0, 0, folder, "工作", null, parent, order, 0, null, "none", 1, "", null, type, null, null, false, false, false, 0)

    private val folders = listOf(FolderEntity("f1", "工作", 0), FolderEntity("f2", "生活", 1))
    private val notes = listOf(
        note("a", "模块手册", order = 1.0), note("a1", "uboot", parent = "a", order = 1.0), note("a11", "编译", parent = "a1"),
        note("a2", "drm", parent = "a", order = 2.0), note("b", "入职", order = 2.0), note("t", "买菜", type = "todo"),
        note("secret", "密码", order = 3.0), note("c", "旅行", folder = "f2"),
    )

    @Test fun `scope includes subtrees in tree order and leaves out todos and private notes`() {
        val private = { n: NoteSummary -> n.id == "secret" }
        assertEquals(listOf("a", "a1", "a11", "a2"), AiAssistant.resolve(AiScope(noteIds = setOf("a")), notes, folders, private).map { it.id })
        assertEquals(listOf("b", "c"), AiAssistant.resolve(AiScope(folderIds = setOf("f2"), extraNoteIds = setOf("b", "t", "secret")), notes, folders, private).map { it.id })
        assertEquals(7 - 1, AiAssistant.resolve(AiScope(), notes, folders, private).size)
    }

    @Test fun `path and merged markdown`() {
        assertEquals("工作 / 模块手册 / uboot", AiAssistant.path(notes[2], notes))
        val merged = AiAssistant.mergedMarkdown(listOf(AiAssistant.Merged(notes[1], "工作 / 模块手册", "# 步骤\n```\n# keep\n```")), "合并")
        assertEquals("# 合并\n\n## uboot\n\n> 工作 / 模块手册\n\n## 步骤\n```\n# keep\n```\n", merged)
    }

    @Test fun `context respects the budget and prefers matching notes`() {
        val merged = listOf(AiAssistant.Merged(notes[0], "p", "x".repeat(900)), AiAssistant.Merged(notes[1], "p", "uboot 编译步骤 " + "y".repeat(900)))
        val context = AiAssistant.context(merged, "uboot 编译", budget = 1_000)
        assertEquals("uboot", context.sources.first().title)
        assertTrue(context.truncated)
    }

    @Test fun `citations and search results`() {
        assertEquals(listOf(1, 2, 10), AiAssistant.citedSources("见 [2] 和 [1][2]，以及 [10]"))
        assertEquals(listOf(3 to "讲编译"), AiAssistant.parseSearchResults("结果：{\"results\":[{\"index\":3,\"reason\":\"讲编译\"},{\"index\":\"x\"}]}"))
        assertEquals(emptyList<Pair<Int, String>>(), AiAssistant.parseSearchResults("无法解析"))
    }

    @Test fun `export builds one section per answer with note links`() {
        val markdown = AiAssistant.exportMarkdown(listOf(
            AiAssistant.ExportItem("uboot 怎么编译？", "先配置再编译 [1]", listOf(ContextSource(1, "a1", "uboot|说明]", "工作"))),
            AiAssistant.ExportItem("总结要点", "要点", emptyList()),
        ))
        assertEquals("## uboot 怎么编译？\n\n先配置再编译 [1]\n\n来源：\n- [1] [[page:a1|uboot 说明\\]]]\n\n---\n\n## 总结要点\n\n要点\n", markdown)
        assertEquals("AI：uboot 怎么编译？", AiAssistant.exportTitle(listOf(AiAssistant.ExportItem("uboot 怎么编译？", "", emptyList()))))
    }

    @Test fun `scope survives a round trip and bad input is empty`() {
        val scope = AiScope(folderIds = setOf("f1"), noteIds = setOf("a"))
        assertEquals(scope, AiAssistant.scopeFromJson(AiAssistant.scopeToJson(scope)))
        assertEquals(AiScope(), AiAssistant.scopeFromJson("not json"))
        assertEquals(AiScope(), AiAssistant.scopeFromJson(null))
    }
}
