package io.github.notebook.android

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.work.WorkManager
import io.github.notebook.android.data.NoteEntity
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*

class HierarchyUiTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val app
        get() = rule.activity.application as NotebookApp

    @Before
    fun isolate() {
        WorkManager.getInstance(app).cancelAllWork().result.get()
        rule.activity.getSharedPreferences("note-navigation", 0).edit().clear().commit()
        runBlocking {
            app.repository.flushAll()
            app.database.clearAllTables()
            app.repository.saveFolder("旅行笔记", id = "h-library")
            listOf(
                    NoteEntity(
                        "h-root",
                        title = "旅行计划",
                        body = "主笔记 **正文**",
                        folderId = "h-library",
                        folderName = "旅行笔记",
                        sortOrder = 1000.0,
                    ),
                    NoteEntity(
                        "h-child",
                        title = "交通安排",
                        body = "子笔记正文",
                        parentPageId = "h-root",
                        folderId = "h-library",
                        folderName = "旅行笔记",
                    ),
                    NoteEntity(
                        "h-grand",
                        title = "酒店记录",
                        body = "酒店 **原文**",
                        parentPageId = "h-child",
                        folderId = "h-library",
                        folderName = "旅行笔记",
                    ),
                    NoteEntity(
                        "h-peer",
                        title = "备用计划",
                        body = "另一个主笔记",
                        folderId = "h-library",
                        folderName = "旅行笔记",
                        sortOrder = 2000.0,
                    ),
                )
                .forEach { app.repository.save(it) }
        }
        rule.waitUntil(5_000) {
            rule.onAllNodesWithTag("note-card-h-root").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun openTree() {
        rule.onNodeWithTag("open-note-hierarchy").performClick()
        rule.onNodeWithTag("note-hierarchy").assertIsDisplayed()
    }

    @Test
    fun searchShowsPathAndRevealsAllCollapsedAncestors() {
        openTree()
        rule.onNodeWithTag("hierarchy-search").performTextInput("酒店")
        rule.onNodeWithTag("hierarchy-result-h-grand").assertTextContains("旅行笔记 › 旅行计划 › 交通安排")
        rule.onNodeWithTag("hierarchy-reveal-h-grand").performClick()
        rule.waitUntil(5_000) {
            rule.onAllNodesWithTag("hierarchy-node-h-grand").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithTag("hierarchy-node-h-grand").assertIsSelected()
        rule.onNodeWithTag("hierarchy-open-h-grand").performClick()
        rule.onNodeWithTag("reader-note-h-grand").assertIsDisplayed()
        rule.onNodeWithTag("note-breadcrumb").assertIsDisplayed()
        assertEquals("酒店 **原文**", runBlocking { app.database.dao().get("h-grand")!!.body })
    }

    @Test
    fun diagramRefocusesChildAndOpensSameCanonicalNote() {
        openTree()
        rule.onNodeWithTag("hierarchy-map-mode").performClick()
        rule.onNodeWithTag("hierarchy-map-node-h-child").performClick()
        rule.onNodeWithTag("hierarchy-map-node-h-grand").assertExists()
        rule.onNodeWithTag("hierarchy-tree-mode").performClick()
        rule.onNodeWithTag("hierarchy-node-h-child").assertIsSelected()
        rule.onNodeWithTag("hierarchy-map-mode").performClick()
        rule.onNodeWithTag("hierarchy-map-open").performClick()
        rule.onNodeWithTag("reader-note-h-child").assertIsDisplayed()
        assertEquals("h-root", runBlocking { app.database.dao().get("h-child")!!.parentPageId })
    }

    @Test
    fun pinAndRecentShortcutsRetainIdentityAfterReopening() {
        openTree()
        rule.onNodeWithContentDescription("层级操作 旅行计划").performClick()
        rule.onNodeWithText("固定到快捷入口").performClick()
        rule.onNodeWithTag("hierarchy-quick-固定-h-root").assertExists()
        rule.activityRule.scenario.recreate()
        rule.waitUntil(5_000) {
            rule.onAllNodesWithTag("hierarchy-quick-固定-h-root").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithContentDescription("关闭笔记结构").performClick()
        openTree()
        rule.onNodeWithTag("hierarchy-quick-固定-h-root").performClick()
        rule.onNodeWithTag("reader-note-h-root").assertIsDisplayed()
        rule.onNodeWithContentDescription("返回").performClick()
        openTree()
        rule.onNodeWithTag("hierarchy-quick-最近打开-h-root").assertExists()
    }

    @Test
    fun movingAnotherNodeDoesNotReplaceCurrentReaderOrBody() {
        rule.onNodeWithTag("note-card-h-grand").performScrollTo().performClick()
        rule.onNodeWithTag("locate-current-note").performClick()
        rule.onNodeWithTag("hierarchy-tree").performScrollToNode(hasContentDescription("层级操作 备用计划"))
        rule.onNodeWithContentDescription("层级操作 备用计划").performClick()
        rule.onNodeWithText("移动到…").performClick()
        rule.onNodeWithTag("move-target-h-root").performClick()
        rule.waitUntil(5_000) {
            runBlocking { app.database.dao().get("h-peer")?.parentPageId == "h-root" }
        }
        rule.onNodeWithContentDescription("关闭笔记结构").performClick()
        rule.onNodeWithTag("reader-note-h-grand").assertIsDisplayed()
        assertEquals("酒店 **原文**", runBlocking { app.database.dao().get("h-grand")!!.body })
    }

    @Test
    fun creatingChildFromTreeKeepsParentBody() {
        openTree()
        rule.onNodeWithContentDescription("层级操作 旅行计划").performClick()
        rule.onNodeWithText("新建子笔记").performClick()
        rule.onNodeWithTag("title-field").performTextInput("新增子笔记")
        rule.onNodeWithTag("body-field").performTextInput("中文正文")
        rule.onNodeWithTag("preview-mode").performClick()
        rule.waitUntil(5_000) {
            runBlocking { app.database.dao().allNotes().any { it.title == "新增子笔记" } }
        }
        val child = runBlocking { app.database.dao().allNotes().single { it.title == "新增子笔记" } }
        assertEquals("h-root", child.parentPageId)
        assertEquals("中文正文", child.body)
        assertEquals("主笔记 **正文**", runBlocking { app.database.dao().get("h-root")!!.body })
    }

    @Test
    fun lockedNotesNeverAppearInTreeSearchRecentOrPinned() {
        runBlocking {
            app.repository.saveFolder("私密库", type = "encryptedFolder", id = "h-private-library")
            app.repository.markEncrypted("h-secret", "h-private-library")
            app.repository.save(
                NoteEntity(
                    "h-secret",
                    title = "保密标题",
                    body = "保密正文",
                    folderId = "h-private-library",
                    folderName = "私密库",
                )
            )
        }
        NoteNavigationHistory(rule.activity).visited("h-secret")
        NoteNavigationHistory(rule.activity).togglePin("h-secret")
        openTree()
        rule.onAllNodesWithText("保密标题").assertCountEquals(0)
        rule.onNodeWithTag("hierarchy-node-h-secret").assertDoesNotExist()
        rule.onNodeWithTag("hierarchy-search").performTextInput("保密")
        rule.onNodeWithTag("hierarchy-result-h-secret").assertDoesNotExist()
    }

    @Test
    fun scopedSearchCanSwitchToAllLibrariesWithoutLosingQuery() {
        runBlocking {
            app.repository.saveFolder("工作笔记", id = "h-work")
            app.repository.save(
                NoteEntity(
                    "h-external",
                    title = "其他主题",
                    body = "外部主题正文",
                    folderId = "h-work",
                    folderName = "工作笔记",
                )
            )
        }
        rule.onNodeWithTag("note-cards").performScrollToNode(hasTestTag("note-card-h-root"))
        rule.onNodeWithTag("note-children-h-root").performClick()
        rule.onNodeWithTag("hierarchy-scope").performClick()
        rule.onNodeWithTag("hierarchy-search").performTextInput("其他主题")
        rule.onNodeWithTag("hierarchy-result-h-external").assertDoesNotExist()
        rule.onNodeWithTag("hierarchy-scope").performClick()
        rule.onNodeWithTag("hierarchy-result-h-external").assertExists()
        rule.onNodeWithTag("hierarchy-result-h-external").performClick()
        rule.onNodeWithTag("reader-note-h-external").assertIsDisplayed()
    }

    @Test
    fun movingWholeSubtreeAcrossLibrariesKeepsBodiesAndRestoresPath() {
        runBlocking {
            app.repository.saveFolder("工作笔记", id = "h-work")
            app.repository.save(
                NoteEntity(
                    "h-work-root",
                    title = "项目入口",
                    body = "项目原文",
                    folderId = "h-work",
                    folderName = "工作笔记",
                )
            )
        }
        rule.onNodeWithTag("note-cards").performScrollToNode(hasTestTag("note-card-h-root"))
        rule.onNodeWithTag("note-card-h-root").performClick()
        rule.onNodeWithTag("locate-current-note").performClick()
        rule.onNodeWithContentDescription("层级操作 旅行计划").performClick()
        rule.onNodeWithText("移动到…").performClick()
        rule.onNodeWithTag("move-target-h-work-root").performClick()
        rule.waitUntil(5_000) {
            runBlocking { app.database.dao().get("h-grand")?.folderId == "h-work" }
        }
        rule.onNodeWithContentDescription("关闭笔记结构").performClick()
        rule.onNodeWithTag("reader-note-h-root").assertIsDisplayed()
        rule
            .onNodeWithTag("note-breadcrumb")
            .assertContentDescriptionEquals("完整路径：工作笔记 › 项目入口 › 旅行计划")
        for ((id, body) in
            listOf("h-root" to "主笔记 **正文**", "h-child" to "子笔记正文", "h-grand" to "酒店 **原文**")) {
            val stored = runBlocking { app.database.dao().get(id)!! }
            assertEquals("h-work", stored.folderId)
            assertEquals(body, stored.body)
        }
        assertEquals("h-work-root", runBlocking { app.database.dao().get("h-root")!!.parentPageId })
        assertEquals("h-root", runBlocking { app.database.dao().get("h-child")!!.parentPageId })
        assertEquals("h-child", runBlocking { app.database.dao().get("h-grand")!!.parentPageId })
        rule.activityRule.scenario.recreate()
        rule.waitUntil(5_000) {
            rule.onAllNodesWithTag("reader-note-h-root").fetchSemanticsNodes().isNotEmpty()
        }
        rule
            .onNodeWithTag("note-breadcrumb")
            .assertContentDescriptionEquals("完整路径：工作笔记 › 项目入口 › 旅行计划")
    }

    @Test
    fun lateDraftKeepsNewerMovedHierarchyWhileSavingItsBody() {
        val future = System.currentTimeMillis() + 60_000
        runBlocking {
            app.repository.save(app.repository.loadNote("h-child")!!.copy(treeUpdatedAt = future))
            app.repository.save(
                app.repository.loadNote("h-grand")!!.copy(treeUpdatedAt = future + 1)
            )
        }
        val old = runBlocking { app.repository.loadNote("h-child")!! }
        rule.onNodeWithTag("note-card-h-child").performScrollTo().performClick()
        runBlocking {
            app.repository.saveFolder("工作笔记", id = "h-work")
            app.repository.save(
                NoteEntity("h-work-root", title = "项目入口", folderId = "h-work", folderName = "工作笔记")
            )
            app.repository.movePage("h-child", "h-work-root")
            app.repository.flushDraft(old.copy(body = "迟到中文 **正文**")).join()
        }
        val saved = runBlocking { app.database.dao().get("h-child")!! }
        assertEquals("h-work-root", saved.parentPageId)
        assertEquals("h-work", saved.folderId)
        assertEquals("迟到中文 **正文**", saved.body)
        assertTrue(saved.treeUpdatedAt > future + 1)
        assertEquals("h-work", runBlocking { app.database.dao().get("h-grand")!!.folderId })
        rule.waitUntil(5_000) {
            rule
                .onAllNodesWithContentDescription("完整路径：工作笔记 › 项目入口 › 交通安排")
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
    }

    @Test
    fun switchingFromEditedNoteThroughStructureFlushesTextAndHidesOldKeyboard() {
        rule.onNodeWithTag("note-card-h-child").performScrollTo().performClick()
        rule.onNodeWithTag("edit-mode").performClick()
        rule.onNodeWithTag("body-field").performTextInput("追加")
        rule.onNodeWithTag("locate-current-note").performClick()
        rule.waitUntil(5_000) {
            !rule.activity.window.decorView.rootWindowInsets.isVisible(
                android.view.WindowInsets.Type.ime()
            )
        }
        rule
            .onNodeWithTag("hierarchy-tree")
            .performScrollToNode(hasTestTag("hierarchy-open-h-peer"))
        rule.onNodeWithTag("hierarchy-open-h-peer").performClick()
        rule.onNodeWithTag("reader-note-h-peer").assertIsDisplayed()
        assertEquals("子笔记正文追加", runBlocking { app.database.dao().get("h-child")!!.body })
    }

    @Test
    fun legalIdsDoNotCollideWithUnfiledSectionOrLibraryKeys() {
        runBlocking {
            app.repository.saveFolder("合法标识库", id = "unfiled")
            app.repository.save(
                NoteEntity(
                    "library:unfiled",
                    title = "归属明确",
                    body = "库内原文",
                    folderId = "unfiled",
                    folderName = "合法标识库",
                )
            )
            app.repository.save(NoteEntity("h-unfiled", title = "未分类内容", body = "未分类原文"))
        }
        openTree()
        rule
            .onNodeWithTag("hierarchy-tree")
            .performScrollToNode(hasTestTag("hierarchy-open-h-unfiled"))
        rule.onNodeWithTag("hierarchy-open-h-unfiled").assertIsDisplayed()
        rule
            .onNodeWithTag("hierarchy-tree")
            .performScrollToNode(hasTestTag("hierarchy-open-library:unfiled"))
        rule.onNodeWithTag("hierarchy-open-library:unfiled").performClick()
        rule.onNodeWithTag("reader-note-library:unfiled").assertIsDisplayed()
        rule.onNodeWithTag("note-breadcrumb").assertContentDescriptionEquals("完整路径：合法标识库 › 归属明确")
    }

    @Test
    fun sameTitleAndParentSearchResultsShowDifferentBodyPreviews() {
        runBlocking {
            app.repository.save(
                NoteEntity(
                    "h-record-one",
                    title = "验收记录",
                    body = "键盘触摸检查",
                    parentPageId = "h-root",
                    folderId = "h-library",
                    folderName = "旅行笔记",
                )
            )
            app.repository.save(
                NoteEntity(
                    "h-record-two",
                    title = "验收记录",
                    body = "切换动画检查",
                    parentPageId = "h-root",
                    folderId = "h-library",
                    folderName = "旅行笔记",
                )
            )
        }
        openTree()
        rule.onNodeWithTag("hierarchy-search").performTextInput("验收记录")
        rule.onNodeWithTag("hierarchy-result-h-record-one").assertTextContains("键盘触摸检查")
        rule.onNodeWithTag("hierarchy-result-h-record-two").assertTextContains("切换动画检查")
        rule.onNodeWithTag("hierarchy-result-h-record-two").performClick()
        rule.onNodeWithTag("reader-note-h-record-two").assertIsDisplayed()
        assertEquals("切换动画检查", runBlocking { app.database.dao().get("h-record-two")!!.body })
    }
}
