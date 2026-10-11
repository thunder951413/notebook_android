package io.github.notebook.android

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import io.github.notebook.android.data.*
import io.github.notebook.android.sync.WebdavSettings
import java.io.File
import java.util.zip.ZipFile
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*

class ReferenceUiTest {
    @get:Rule(order = 0) val rule = createAndroidComposeRule<MainActivity>()
    @get:Rule(order = 1)
    val failureCapture =
        object : org.junit.rules.TestWatcher() {
            override fun failed(error: Throwable, description: org.junit.runner.Description) {
                val instrumentation = InstrumentationRegistry.getInstrumentation()
                val file =
                    File(
                        instrumentation.targetContext.getExternalFilesDir(null),
                        "ui-failure-${description.methodName}.png",
                    )
                instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                    file.outputStream().use {
                        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                    }
                    bitmap.recycle()
                }
                runCatching {
                    println(rule.onRoot(useUnmergedTree = true).printToString().take(9000))
                }
            }
        }
    private val app
        get() = rule.activity.application as NotebookApp

    private fun waitForStableEditorTools() {
        rule.runOnIdle {
            rule.activity.window.decorView.findFocus()?.let { view ->
                rule.activity
                    .getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                    .showSoftInput(view, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
            }
        }
        rule.waitUntil(5_000) {
            rule.activity.window.decorView.rootWindowInsets.isVisible(
                android.view.WindowInsets.Type.ime()
            )
        }
        var previous: androidx.compose.ui.geometry.Rect? = null
        var stableSince = System.currentTimeMillis()
        rule.waitUntil(5_000) {
            val node = rule.onAllNodesWithTag("format-tools").fetchSemanticsNodes().singleOrNull()
            val bounds = node?.boundsInRoot
            if (bounds != previous) {
                previous = bounds
                stableSince = System.currentTimeMillis()
            }
            bounds != null && System.currentTimeMillis() - stableSince >= 250
        }
    }

    @Before
    fun isolate() {
        WorkManager.getInstance(app).cancelAllWork().result.get()
        runBlocking {
            app.repository.flushAll()
            app.database.clearAllTables()
        }
        app.repository.saveWebdavSettings(
            WebdavSettings("https://example.invalid/", "", "", "ui_test")
        )
    }

    @Test
    fun createColoredLibraryAddExistingAndDissolveRetainsBody() {
        runBlocking {
            app.repository.save(NoteEntity("library-ui-note", title = "待整理笔记", body = "保留正文"))
        }
        rule.onNodeWithTag("home-tab-1").performClick()
        rule.onNodeWithTag("new-item").performClick()
        rule.onNodeWithTag("library-name").performTextInput("测试笔记库")
        rule.onNodeWithTag("save-library").performClick()
        rule.waitUntil(5_000) {
            rule.onAllNodesWithText("测试笔记库").fetchSemanticsNodes().isNotEmpty()
        }
        val libraryId = runBlocking {
            app.database.dao().allFolders().single { it.name == "测试笔记库" }.id
        }
        rule.onNodeWithTag("library-card-$libraryId").performClick()
        rule.onNodeWithText("添加已有笔记").performClick()
        rule.onNodeWithTag("note-card-library-ui-note").performClick()
        rule.onNodeWithText("添加 (1)").performClick()
        rule.waitUntil(5_000) {
            runBlocking { app.database.dao().get("library-ui-note")?.folderId != null }
        }
        rule.onNodeWithContentDescription("更多").performClick()
        rule.onNodeWithText("删除笔记库").performClick()
        rule.onNodeWithText("解散并保留笔记").performClick()
        rule.waitUntil(5_000) {
            runBlocking { app.database.dao().get("library-ui-note")?.folderId == null }
        }
        assertEquals("保留正文", runBlocking { app.database.dao().get("library-ui-note")!!.body })
        assertNull(runBlocking { app.database.dao().get("library-ui-note")!!.deletedAt })
    }

    @Test
    fun longPressDuplicateAndRestoreKeepCanonicalContent() {
        runBlocking {
            app.repository.save(
                NoteEntity("selection-ui", title = "多选样本", body = "## 标题\n中文 **格式**")
            )
        }
        rule.onNodeWithTag("note-card-selection-ui").performTouchInput { longClick() }
        rule.onNodeWithContentDescription("创建副本").performClick()
        rule.waitUntil(5_000) {
            runBlocking { app.database.dao().allNotes().any { it.title == "副本 多选样本" } }
        }
        val copied = runBlocking { app.database.dao().allNotes().single { it.title == "副本 多选样本" } }
        assertEquals("## 标题\n中文 **格式**", copied.body)
        runBlocking { app.repository.moveToTrash(copied.id) }
        rule.onNodeWithContentDescription("更多").performClick()
        rule.onNodeWithText("最近删除").performClick()
        rule.onNodeWithTag("note-card-${copied.id}").performClick()
        rule.onNodeWithText("恢复").performClick()
        rule.waitUntil(5_000) {
            runBlocking { app.database.dao().get(copied.id)?.deletedAt == null }
        }
        rule.waitUntil(5_000) {
            runCatching { rule.onNodeWithTag("edit-mode").assertIsDisplayed() }.isSuccess
        }
    }

    @Test
    fun editingAndClosingFormatPanelShowKeyboardWithoutTestForcingIt() {
        val prefs = app.getSharedPreferences("editor_preferences", 0)
        val original = prefs.getBoolean("autoKeyboard", true)
        try {
            prefs.edit().putBoolean("autoKeyboard", true).commit()
            runBlocking {
                app.repository.save(
                    NoteEntity("natural-keyboard", title = "自动键盘样本", body = "保留中文正文")
                )
            }
            rule.onNodeWithText("自动键盘样本").performClick()
            rule.onNodeWithTag("edit-mode").performClick()
            rule.waitUntil(5_000) {
                rule.activity.window.decorView.rootWindowInsets.isVisible(
                    android.view.WindowInsets.Type.ime()
                )
            }
            rule.onNodeWithTag("format-tools").performSemanticsAction(SemanticsActions.OnClick) {
                it()
            }
            rule.waitUntil(5_000) {
                !rule.activity.window.decorView.rootWindowInsets.isVisible(
                    android.view.WindowInsets.Type.ime()
                )
            }
            rule.onNodeWithContentDescription("关闭格式").performClick()
            rule.waitUntil(5_000) {
                rule.activity.window.decorView.rootWindowInsets.isVisible(
                    android.view.WindowInsets.Type.ime()
                )
            }
            assertEquals(
                "保留中文正文",
                runBlocking { app.database.dao().get("natural-keyboard")!!.body },
            )
        } finally {
            prefs.edit().putBoolean("autoKeyboard", original).commit()
        }
    }

    @Test
    fun disablingAutoKeyboardAfterPanelUseRemainsEffectiveWhenEditingAgain() {
        val prefs = app.getSharedPreferences("editor_preferences", 0)
        val original = prefs.getBoolean("autoKeyboard", true)
        try {
            prefs.edit().putBoolean("autoKeyboard", true).commit()
            runBlocking {
                app.repository.save(
                    NoteEntity("keyboard-preference", title = "键盘偏好样本", body = "一段正文")
                )
            }
            rule.onNodeWithText("键盘偏好样本").performClick()
            rule.onNodeWithTag("edit-mode").performClick()
            waitForStableEditorTools()
            rule.onNodeWithContentDescription("添加内容").performSemanticsAction(
                SemanticsActions.OnClick
            ) {
                it()
            }
            rule.waitUntil(5_000) {
                !rule.activity.window.decorView.rootWindowInsets.isVisible(
                    android.view.WindowInsets.Type.ime()
                )
            }
            rule.onNodeWithContentDescription("关闭添加内容").performClick()
            waitForStableEditorTools()
            rule.onNodeWithTag("preview-mode").performClick()
            rule.waitUntil(5_000) {
                rule.onAllNodesWithTag("edit-mode").fetchSemanticsNodes().isNotEmpty() &&
                    !rule.activity.window.decorView.rootWindowInsets.isVisible(
                        android.view.WindowInsets.Type.ime()
                    )
            }
            prefs.edit().putBoolean("autoKeyboard", false).commit()
            rule.onNodeWithTag("edit-mode").performClick()
            rule.waitForIdle()
            android.os.SystemClock.sleep(400)
            assertFalse(
                "此前的面板恢复状态不能覆盖用户的自动键盘偏好",
                rule.activity.window.decorView.rootWindowInsets.isVisible(
                    android.view.WindowInsets.Type.ime()
                ),
            )
        } finally {
            prefs.edit().putBoolean("autoKeyboard", original).commit()
        }
    }

    @Test
    fun formattingUndoRedoAndPanelSwitchingSaveActualMarkdown() {
        rule.onNodeWithTag("new-item").performTouchInput { longClick() }
        rule.onNodeWithTag("title-field").performTextInput("格式样本")
        rule.onNodeWithTag("body-field").performTextInput("中文段落")
        waitForStableEditorTools()
        // Native IME animation can move the window between Compose coordinate lookup and injection.
        // ADB recording separately verifies physical taps; this assertion exercises the bound
        // action.
        rule.onNodeWithTag("format-tools").performSemanticsAction(SemanticsActions.OnClick) { it() }
        rule.onNodeWithText("小标题").performClick()
        rule.onNodeWithTag("body-field").assertTextEquals("中文段落")
        rule.waitUntil(5_000) {
            runBlocking {
                app.database.dao().allNotes().any { it.title == "格式样本" && it.body == "### 中文段落" }
            }
        }
        rule.onNodeWithContentDescription("关闭格式").performClick()
        rule.onNodeWithContentDescription("撤销").performClick()
        rule.onNodeWithTag("body-field").assertTextEquals("中文段落")
        rule.onNodeWithContentDescription("重做").performClick()
        rule.onNodeWithTag("preview-mode").performClick()
        rule.waitUntil(5_000) {
            runBlocking {
                app.database.dao().allNotes().any { it.title == "格式样本" && it.body == "### 中文段落" }
            }
        }
    }

    @Test
    fun tableEditsAndDirectionReturnPreserveCells() {
        runBlocking {
            app.repository.save(
                NoteEntity(
                    "table-ui",
                    title = "表格样本",
                    body =
                        "## 上文\n\n第一段\n第二段\n第三段\n第四段\n第五段\n\n| A | B |\n| --- | --- |\n| 中文 | 保留 |\n",
                )
            )
        }
        rule.onNodeWithTag("note-card-table-ui").performClick()
        rule.onNodeWithContentDescription("全屏表格").performClick()
        rule.waitUntil(5_000) {
            rule.onAllNodesWithTag("table-cell-1-0").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithTag("table-cell-1-0").performTextReplacement("新的中文")
        rule.onNodeWithContentDescription("切换表格方向").performClick()
        rule.waitUntil(5_000) {
            rule.onAllNodesWithTag("table-cell-1-0").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithTag("table-cell-1-0").assertTextEquals("新的中文")
        rule.onNodeWithText("完成").performClick()
        rule.waitUntil(5_000) {
            runBlocking { app.database.dao().get("table-ui")?.body?.contains("新的中文") == true }
        }
        rule.waitUntil(5_000) {
            rule.onAllNodesWithTag("markdown-view").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithTag("markdown-view").assertIsDisplayed()
    }

    @Test
    fun largeUnicodeNoteEditsLastPageWithoutTruncatingEarlierText() {
        val id = java.util.UUID.randomUUID().toString()
        val source = "第一行\n" + "中文长文😀".repeat(40000) + "\n最后标记"
        runBlocking { app.repository.save(NoteEntity(id, title = "长文样本", body = source)) }
        rule.onNodeWithTag("note-card-$id").performClick()
        rule.mainClock.advanceTimeBy(400)
        rule.waitForIdle()
        rule.onNodeWithTag("edit-mode").performClick()
        rule.waitUntil(5_000) {
            rule.onAllNodesWithTag("body-field").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithTag("body-field").assertTextContains("最后标记", substring = true)
        rule.onNodeWithTag("body-field").performTextInput("附加")
        rule.onNodeWithTag("preview-mode").performClick()
        rule.waitUntil(8_000) { runBlocking { app.database.dao().get(id)?.body == source + "附加" } }
    }

    @Test
    fun untouchedBlankNoteDoesNotCreateDatabaseRow() {
        rule.onNodeWithTag("new-item").performTouchInput { longClick() }
        rule.mainClock.advanceTimeBy(400)
        rule.waitForIdle()
        rule.onNodeWithTag("preview-mode").performClick()
        rule.waitUntil(5_000) {
            rule.onAllNodesWithTag("body-field").fetchSemanticsNodes().isEmpty()
        }
        assertTrue(runBlocking { app.database.dao().allNoteIds().isEmpty() })
    }

    @Test
    fun navigationFromLibraryTabShowsTodoResultsAndReturnsToLibraries() {
        val id = java.util.UUID.randomUUID().toString()
        runBlocking { app.repository.save(NoteEntity(id, title = "导航待办样本", itemType = "todo")) }
        rule.onNodeWithTag("home-tab-1").performClick()
        rule.onNodeWithContentDescription("更多").performClick()
        rule.onNodeWithContentDescription("导航").performClick()
        rule.onNodeWithText("全部待办").performClick()
        rule.onNodeWithTag("note-card-$id").assertIsDisplayed()
        rule.onNodeWithContentDescription("返回首页").performClick()
        rule.onNodeWithText("把笔记放进笔记库").assertIsDisplayed()
    }

    @Test
    fun nativeCodeAndParagraphEditsRetainOtherCanonicalBlocks() {
        val id = java.util.UUID.randomUUID().toString()
        val source = "## 标题\n\n第一段\n\n```kotlin\nval old = 1\n```\n\n尾段"
        runBlocking { app.repository.save(NoteEntity(id, title = "内容块样本", body = source)) }
        rule.onNodeWithTag("note-card-$id").performClick()
        rule.onNodeWithTag("edit-mode").performClick()
        rule.onNodeWithTag("body-field").assertTextEquals("尾段")
        rule.onNodeWithTag("body-field").performTextInput("追加")
        rule.onNodeWithContentDescription("复制代码").assertIsDisplayed()
        rule.onNode(hasSetTextAction() and hasText("val old = 1")).performScrollTo().performClick()
        rule.waitUntil(5_000) {
            rule
                .onAllNodesWithTag("body-field")
                .fetchSemanticsNodes()
                .singleOrNull()
                ?.config
                ?.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.EditableText)
                ?.text == "val old = 1"
        }
        rule.onNodeWithTag("body-field").performTextReplacement("val updated = 2")
        rule.onNodeWithContentDescription("添加内容").performSemanticsAction(SemanticsActions.OnClick) {
            it()
        }
        rule.waitUntil(5_000) {
            rule.onAllNodesWithTag("markdown-source-toggle").fetchSemanticsNodes().isNotEmpty() &&
                !rule.activity.window.decorView.rootWindowInsets.isVisible(
                    android.view.WindowInsets.Type.ime()
                )
        }
        rule.onNodeWithTag("markdown-source-toggle").performClick()
        val expected = source.replace("val old = 1", "val updated = 2") + "追加"
        rule.onNodeWithTag("body-field").assertTextEquals(expected)
        rule.onNodeWithTag("preview-mode").performClick()
        rule.waitUntil(5_000) { runBlocking { app.database.dao().get(id)?.body == expected } }
    }

    @Test
    fun insertingNativeCodeFocusesPayloadAndSupportsUndo() {
        rule.onNodeWithTag("new-item").performTouchInput { longClick() }
        rule.onNodeWithTag("title-field").performTextInput("新代码样本")
        rule.onNodeWithTag("body-field").performClick()
        rule.mainClock.advanceTimeBy(400)
        rule.waitForIdle()
        waitForStableEditorTools()
        rule.onNodeWithContentDescription("添加内容").performSemanticsAction(SemanticsActions.OnClick) {
            it()
        }
        rule.waitUntil(5_000) { rule.onAllNodesWithText("代码块").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("代码块").performClick()
        rule.onNodeWithContentDescription("复制代码").assertIsDisplayed()
        rule.onNodeWithTag("body-field").performTextInput("中文代码")
        rule.onNodeWithTag("preview-mode").performClick()
        rule.waitUntil(5_000) {
            runBlocking {
                app.database.dao().allNotes().any {
                    it.title == "新代码样本" && it.body == "\n\n```\n中文代码\n```\n\n"
                }
            }
        }
    }

    @Test
    fun androidInputConnectionCompositionCommitsWithoutDuplicatingChineseText() {
        rule.onNodeWithTag("new-item").performTouchInput { longClick() }
        rule.onNodeWithTag("title-field").performTextInput("组合输入样本")
        rule.onNodeWithTag("body-field").performClick()
        rule.waitForIdle()
        rule.runOnIdle {
            rule.activity
                .getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                .hideSoftInputFromWindow(rule.activity.window.decorView.windowToken, 0)
        }
        rule.waitUntil(5_000) {
            !rule.activity.window.decorView.rootWindowInsets.isVisible(
                android.view.WindowInsets.Type.ime()
            )
        }
        rule.runOnIdle {
            val focused = rule.activity.window.decorView.findFocus()
            val input = focused.onCreateInputConnection(android.view.inputmethod.EditorInfo())
            assertTrue(input.beginBatchEdit())
            assertTrue(input.setComposingText("中", 1))
            assertTrue(input.setComposingText("中文组合", 1))
            assertTrue(input.commitText("中文组合输入", 1))
            input.endBatchEdit()
        }
        rule.waitForIdle()
        rule.onNodeWithTag("body-field").assertTextEquals("中文组合输入")
        rule.onNodeWithTag("preview-mode").performClick()
        rule.waitUntil(5_000) {
            runBlocking {
                app.database.dao().allNotes().any { it.title == "组合输入样本" && it.body == "中文组合输入" }
            }
        }
    }

    @Test
    fun exportsAreReadableAndContainTheActualNote() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val note = NoteEntity("export", title = "导出样本", body = "## 中文标题\n\n**正文**与第二段\n\n- [x] 完成")
        val dir = File(context.getExternalFilesDir(null), "ui-study-exports").apply { mkdirs() }
        ImageTemplate.entries.forEach { template ->
            listOf("长图", "3:4", "1:1").forEach { ratio ->
                val bitmap = NoteExporter.image(note, context, template, ratio)
                assertEquals(1080, bitmap.width)
                if (ratio == "1:1") assertEquals(1080, bitmap.height)
                if (ratio == "3:4") assertEquals(1440, bitmap.height)
                File(dir, "${template.name}-${ratio.replace(":","-")}.png").outputStream().use {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                bitmap.recycle()
            }
        }
        ExportFormat.entries.forEach { format ->
            val file = File(dir, NoteExporter.filename(note.title, format))
            file.outputStream().use { NoteExporter.write(note, format, context, it) }
            assertTrue("${format.label} empty", file.length() > 0)
        }
        assertEquals(note.body, File(dir, "导出样本.md").readText())
        assertTrue(File(dir, "导出样本.txt").readText().contains("中文标题"))
        ZipFile(File(dir, "导出样本.docx")).use { zip ->
            val xml = zip.getInputStream(zip.getEntry("word/document.xml")).reader().readText()
            assertTrue(xml.contains("导出样本"))
            assertTrue(xml.contains("正文"))
        }
        assertTrue(
            File(dir, "导出样本.pdf").inputStream().use { String(it.readNBytes(4)) }.startsWith("%PDF")
        )
        assertNotNull(android.graphics.BitmapFactory.decodeFile(File(dir, "导出样本.png").absolutePath))
    }
}
