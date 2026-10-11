package io.github.notebook.android

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Before
import org.junit.Test
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import kotlinx.coroutines.runBlocking
import androidx.lifecycle.Lifecycle
import io.github.notebook.android.data.FolderEntity
import io.github.notebook.android.data.NoteEntity
import io.github.notebook.android.data.PageLinkEntity
import io.github.notebook.android.sync.WebdavSettings
import androidx.work.WorkManager
import com.google.gson.JsonArray
import com.google.gson.JsonObject

class MainActivityTest {
    @get:Rule val rule=createAndroidComposeRule<MainActivity>()

    @Before fun isolateAppState(){
        val app=rule.activity.application as NotebookApp
        WorkManager.getInstance(app).cancelAllWork().result.get()
        runBlocking{
            app.repository.flushAll()
            app.database.clearAllTables()
        }
        app.repository.saveWebdavSettings(WebdavSettings("https://example.invalid/dav/","","","notebook_test"))
    }

    private fun createNote(){rule.onNodeWithTag("new-item").performClick();rule.onNodeWithTag("create-text-note").performClick()}
    private fun openNavigation(){if(rule.onAllNodesWithContentDescription("导航").fetchSemanticsNodes().isEmpty())rule.onNodeWithContentDescription("更多").performClick();rule.onNodeWithContentDescription("导航").performClick()}
    @Test fun createEditAndSaveNote(){
        createNote()
        rule.onNodeWithTag("title-field").performTextInput("设备测试笔记")
        rule.onNodeWithTag("body-field").performTextInput("正文内容")
        rule.onNodeWithTag("preview-mode").performClick()
        rule.waitUntil(5_000){rule.onAllNodesWithText("设备测试笔记").fetchSemanticsNodes().isNotEmpty()}
        rule.onAllNodesWithText("设备测试笔记",useUnmergedTree=true).assertAny(hasText("设备测试笔记"))
    }

    @Test fun backNavigationFlushesUnsavedTyping(){
        val title="返回自动保存-${System.nanoTime()}"
        val app=rule.activity.application as NotebookApp
        createNote();rule.onNodeWithTag("title-field").performTextInput(title);rule.onNodeWithTag("body-field").performTextInput("没有点击完成")
        rule.activity.runOnUiThread{rule.activity.onBackPressedDispatcher.onBackPressed()}
        rule.waitUntil(8_000){runBlocking{app.database.dao().allNotes().any{it.title==title&&it.body=="没有点击完成"}}}
    }

    @Test fun debounceSaveSurvivesActivityRecreation(){
        val title="重建恢复-${System.nanoTime()}"
        createNote();rule.onNodeWithTag("title-field").performTextInput(title);rule.onNodeWithTag("body-field").performTextInput("旋转或系统重建后保留")
        rule.waitUntil(5_000){runBlocking{((rule.activity.application as NotebookApp).database.dao().allNotes().any{it.title==title&&it.body=="旋转或系统重建后保留"})}}
        rule.activityRule.scenario.recreate()
        rule.waitUntil(5_000){rule.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty()}
    }

    @Test fun immediateRecreationFlushesPendingKeystrokes(){
        val title="立即重建-${System.nanoTime()}";createNote();rule.onNodeWithTag("title-field").performTextInput(title);rule.onNodeWithTag("body-field").performTextInput("不足防抖时间")
        rule.activityRule.scenario.recreate()
        rule.waitUntil(5_000){rule.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty()}
    }

    @Test fun enteringBackgroundImmediatelyFlushesDraft(){
        val title="后台刷新-${System.nanoTime()}";val app=rule.activity.application as NotebookApp;createNote();rule.onNodeWithTag("title-field").performTextInput(title)
        rule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        val deadline=System.currentTimeMillis()+5_000;while(System.currentTimeMillis()<deadline&&!runBlocking{app.database.dao().allNotes().any{it.title==title}})Thread.sleep(50)
        assertTrue(runBlocking{app.database.dao().allNotes().any{it.title==title}})
        rule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
    }

    @Test fun drawerScrollsToFoldersAndSettings(){
        val app=rule.activity.application as NotebookApp
        runBlocking{repeat(30){index->app.database.dao().putFolder(FolderEntity("scroll-test-$index","滚动文件夹 $index",index))}}
        rule.activityRule.scenario.recreate()
        openNavigation()
        rule.onNodeWithTag("drawer-scroll").performScrollToNode(hasText("查看全部文件夹",substring=true))
        rule.onAllNodes(hasText("查看全部文件夹",substring=true),useUnmergedTree=true).onFirst().performClick()
        rule.onNodeWithTag("drawer-scroll").performScrollToNode(hasText("滚动文件夹 29"))
        rule.onNodeWithText("滚动文件夹 29").assertIsDisplayed()
        rule.onNodeWithTag("drawer-scroll").performScrollToNode(hasText("设置与同步"))
        rule.onNodeWithText("设置与同步").assertIsDisplayed()
    }

    @Test fun folderBelowSelectedFolderRemainsClickable(){
        val suffix=System.nanoTime().toString()
        val selectedId="selected-folder-$suffix";val targetId="target-folder-$suffix"
        val selectedName="已选目录-$suffix";val targetName="下方目录-$suffix";val targetNote="下方目录笔记-$suffix"
        val app=rule.activity.application as NotebookApp
        runBlocking{
            repeat(7){index->app.database.dao().putFolder(FolderEntity("target-filler-$suffix-$index","占位文件夹 $index",index))}
            app.database.dao().putFolder(FolderEntity(selectedId,selectedName,10_000,"noteFolder"))
            app.database.dao().putFolder(FolderEntity(targetId,targetName,10_001,"noteFolder"))
            app.database.dao().put(NoteEntity("target-note-$suffix",title=targetNote,folderId=targetId,folderName=targetName))
        }
        rule.activityRule.scenario.recreate()
        openNavigation()
        rule.onNodeWithTag("drawer-scroll").performScrollToNode(hasText("查看全部文件夹",substring=true))
        rule.onAllNodes(hasText("查看全部文件夹",substring=true),useUnmergedTree=true).onFirst().performClick()
        rule.onNodeWithTag("drawer-scroll").performScrollToNode(hasText(selectedName))
        rule.onNodeWithText(selectedName).performClick()
        openNavigation()
        rule.onNodeWithTag("drawer-scroll").performScrollToNode(hasText(targetName))
        rule.onNodeWithText(targetName).assertIsDisplayed().performClick()
        rule.onNodeWithText(targetNote).assertIsDisplayed()
    }

    @Test fun existingNoteOpensInMarkdownAndCanSwitchToEdit(){
        val app=rule.activity.application as NotebookApp;val title="Markdown 阅读-${System.nanoTime()}"
        runBlocking{app.database.dao().put(NoteEntity("markdown-mode-test",title=title,body="# 一级标题\n\n**粗体内容**"))}
        rule.activityRule.scenario.recreate();rule.onNodeWithText(title).performClick()
        rule.onNodeWithTag("markdown-view").assertIsDisplayed();rule.onNodeWithTag("edit-mode").assertIsDisplayed();rule.onAllNodesWithTag("body-field").assertCountEquals(0)
        rule.onNodeWithTag("edit-mode").performClick();rule.onNodeWithTag("body-field").assertIsDisplayed();rule.onNodeWithTag("preview-mode").assertIsDisplayed()
    }

    @Test fun newNoteStartsInEditModeAndShowsBriefSavedConfirmation(){createNote();rule.onNodeWithTag("body-field").assertIsDisplayed();rule.onNodeWithTag("preview-mode").assertIsDisplayed();rule.onNodeWithContentDescription("保存").assertIsDisplayed().performClick();rule.waitUntil(5_000){rule.onAllNodesWithContentDescription("已保存").fetchSemanticsNodes().isNotEmpty()};rule.mainClock.advanceTimeBy(1_000);rule.waitForIdle();rule.waitUntil(5_000){rule.onAllNodesWithContentDescription("已保存").fetchSemanticsNodes().isEmpty()}}

    @Test fun topDeleteRequiresConfirmationAndMovesNoteToTrash(){val title="明确删除-${System.nanoTime()}";val app=rule.activity.application as NotebookApp;createNote();rule.onNodeWithTag("title-field").performTextInput(title);rule.onNodeWithTag("preview-mode").performClick();rule.waitUntil(5_000){rule.onAllNodesWithContentDescription("笔记更多").fetchSemanticsNodes().isNotEmpty()};rule.onNodeWithContentDescription("笔记更多").performClick();rule.onNodeWithTag("delete-item").assertIsDisplayed().performClick();rule.onNodeWithText("删除笔记？").assertIsDisplayed();rule.onNodeWithText("删除").performClick();rule.waitUntil(5_000){runBlocking{app.database.dao().allNotes().any{it.title==title&&it.deletedAt!=null}}}}

    @Test fun longPressSelectionRequiresConfirmationAndMovesNoteToTrash(){
        val suffix=System.nanoTime().toString();val id="selection-delete-$suffix";val title="长按删除-$suffix"
        val app=rule.activity.application as NotebookApp
        runBlocking{app.database.dao().put(NoteEntity(id,title=title,body="确认后才删除"))}
        rule.activityRule.scenario.recreate()
        rule.onNodeWithTag("note-card-$id").performTouchInput{longClick()}
        rule.onNodeWithText("已选择 1 项").assertIsDisplayed()
        rule.onNodeWithContentDescription("删除").performClick()
        rule.onNodeWithText("删除 1 篇笔记？").assertIsDisplayed()
        assertTrue(runBlocking{app.database.dao().get(id)!!.deletedAt==null})
        rule.onNodeWithText("删除").performClick()
        rule.waitUntil(5_000){runBlocking{app.database.dao().get(id)?.deletedAt!=null}}
        runBlocking{app.repository.flushDraft(NoteEntity(id,title=title,body="迟到草稿")).join()}
        assertTrue(runBlocking{app.database.dao().get(id)!!.deletedAt!=null})
    }

    @Test fun todoListsAppearInDrawerAndCreateTodos(){
        val app=rule.activity.application as NotebookApp;runBlocking{app.database.dao().putFolder(FolderEntity("todo-list-test","工作提醒",0,"todoList"));app.database.dao().put(NoteEntity("todo-in-list",title="列表中的待办",itemType="todo",folderId="todo-list-test",folderName="工作提醒"))};rule.activityRule.scenario.recreate()
        openNavigation()
        rule.onNodeWithTag("drawer-scroll").performScrollToNode(hasText("工作提醒"));rule.onNodeWithText("工作提醒").assertIsDisplayed().performClick();rule.onNodeWithText("列表中的待办").assertIsDisplayed()
        createNote();rule.onNodeWithTag("body-field").assertIsDisplayed();rule.onNodeWithText("已完成").assertIsDisplayed()
    }

    @Test fun permanentDeleteFromConflictDetailClosesAndSurvivesRecreation(){
        val app=rule.activity.application as NotebookApp
        val id="deleted-conflict-${System.nanoTime()}";val title="待彻底删除的冲突笔记"
        runBlocking{app.database.dao().put(NoteEntity(id,title=title,body="冲突正文",deletedAt=System.currentTimeMillis(),conflict=true,conflictSnapshotJson="{}"))}
        openNavigation();rule.onNodeWithTag("drawer-scroll").performScrollToNode(hasText("同步冲突"));rule.onNodeWithText("同步冲突").performClick()
        rule.onNodeWithText(title).performClick()
        rule.onNodeWithText("彻底删除").performClick()
        rule.onNodeWithText("彻底删除笔记？").assertIsDisplayed()
        rule.onAllNodesWithText("彻底删除").onLast().performClick()
        rule.waitUntil(5_000){runBlocking{app.database.dao().get(id)==null}}
        rule.waitUntil(5_000){rule.onAllNodesWithText(title).fetchSemanticsNodes().isEmpty()}
        rule.activityRule.scenario.recreate()
        runBlocking{app.repository.flushAll();app.repository.recoverDrafts()}
        assertTrue(runBlocking{app.database.dao().get(id)==null})
        rule.onAllNodesWithText(title).assertCountEquals(0)
    }

    @Test fun permanentDeleteFromTrashSelectionRemovesConflictAndKeepsOtherNotes(){
        val app=rule.activity.application as NotebookApp
        val id="trash-conflict-${System.nanoTime()}"
        runBlocking{
            app.database.dao().put(NoteEntity(id,title="回收站冲突笔记",deletedAt=System.currentTimeMillis(),conflict=true))
            app.database.dao().put(NoteEntity("untouched-note",title="保留笔记",body="保留正文"))
        }
        rule.onNodeWithContentDescription("更多").performClick();rule.onNodeWithText("最近删除").performClick()
        rule.onNodeWithTag("note-card-$id").performTouchInput{longClick()}
        rule.onNodeWithText("彻底删除").performClick()
        rule.onNodeWithText("彻底删除 1 篇笔记？").assertIsDisplayed();rule.onNodeWithText("删除").performClick()
        rule.waitUntil(5_000){runBlocking{app.database.dao().get(id)==null}}
        assertEquals("保留正文",runBlocking{app.database.dao().get("untouched-note")!!.body})
    }

    @Test fun privateFoldersAreShownLockedAndNotesStayOutOfAllNotes(){
        val app=rule.activity.application as NotebookApp;runBlocking{app.database.dao().putFolder(FolderEntity("encrypted-test","私人资料",0,"encryptedFolder"));app.database.dao().put(NoteEntity("encrypted-note-test",title="不能公开显示",folderId="encrypted-test",folderName="私人资料"))};app.repository.markEncrypted("encrypted-note-test","encrypted-test");rule.activityRule.scenario.recreate()
        rule.onAllNodesWithText("不能公开显示").assertCountEquals(0)
        openNavigation()
        rule.onNodeWithTag("drawer-scroll").performScrollToNode(hasText("私密文件夹"));rule.onNodeWithText("私密文件夹").assertIsDisplayed();rule.onNodeWithTag("drawer-scroll").performScrollToNode(hasText("点击解锁私密内容"));rule.onNodeWithText("点击解锁私密内容").assertIsDisplayed();rule.onAllNodesWithText("私人资料").assertCountEquals(0)
    }

    @Test fun referencePickerInsertsCanonicalPageLink(){
        val suffix=System.nanoTime().toString();val targetId="reference-target-$suffix";val sourceId="reference-source-$suffix";val targetTitle="双链目标-$suffix";val sourceTitle="双链来源-$suffix"
        val app=rule.activity.application as NotebookApp
        runBlocking{app.database.dao().put(NoteEntity(targetId,title=targetTitle,body="目标正文"));app.database.dao().put(NoteEntity(sourceId,title=sourceTitle,body="",viewMode="text"))}
        rule.activityRule.scenario.recreate();rule.onNodeWithText(sourceTitle).performClick();rule.onNodeWithTag("edit-mode").performClick()
        rule.onNodeWithContentDescription("插入双链").performClick()
        rule.onNodeWithText(targetTitle).assertIsDisplayed().performClick()
        rule.onNodeWithTag("body-field").assertTextContains(targetTitle,substring=true)
        rule.onNodeWithTag("preview-mode").performClick()
        rule.waitUntil(5_000){runBlocking{app.database.dao().get(sourceId)?.body=="[[page:$targetId]]"}}
    }

    @Test fun renderedNotebookLinkNavigatesToTargetPage(){
        val suffix=System.nanoTime().toString()
        val targetId="123e4567-e89b-42d3-a456-426614174000"
        val sourceId="223e4567-e89b-42d3-a456-426614174000"
        val targetTitle="可点击双链目标-$suffix"
        val sourceTitle="可点击双链来源-$suffix"
        val targetBody="双链跳转后的正文-$suffix"
        val app=rule.activity.application as NotebookApp
        runBlocking{
            app.database.dao().put(NoteEntity(targetId,title=targetTitle,body=targetBody))
            app.database.dao().put(NoteEntity(sourceId,title=sourceTitle,body="[[page:$targetId]]"))
        }
        rule.activityRule.scenario.recreate()
        rule.onNodeWithText(sourceTitle).performClick()
        val openReference=rule.onNodeWithTag("open-reference-$targetId")
        openReference.assertTextContains(targetTitle,substring=true).performClick()
        rule.waitUntil(5_000){rule.onAllNodesWithTag("reader-note-$targetId").fetchSemanticsNodes().isNotEmpty()}
        rule.onNodeWithTag("reader-note-$targetId").assertTextEquals(targetTitle).assertIsDisplayed()
    }

    @Test fun backlinksPanelShowsSourcePage(){
        val suffix=System.nanoTime().toString();val targetId="backlink-target-$suffix";val sourceId="backlink-source-$suffix";val targetTitle="被引用-$suffix";val sourceTitle="引用来源-$suffix"
        val app=rule.activity.application as NotebookApp
        runBlocking{app.database.dao().put(NoteEntity(targetId,title=targetTitle));app.database.dao().put(NoteEntity(sourceId,title=sourceTitle,body="[[page:$targetId]]"));app.database.dao().replacePageLinks(sourceId,listOf(PageLinkEntity("link-$suffix",sourceId,targetId,"page",null,null,false,0,System.currentTimeMillis())))}
        rule.activityRule.scenario.recreate();rule.onNodeWithText(targetTitle).performClick()
        rule.onNodeWithContentDescription("笔记更多").performClick();rule.onNodeWithText("反向链接").performClick()
        rule.onNodeWithText(sourceTitle).assertIsDisplayed()
    }

    @Test fun historyPanelPreviewsRemoteV3Revision(){
        val suffix=System.nanoTime().toString();val id="history-ui-$suffix";val title="历史界面-$suffix";val old="历史版本正文-$suffix"
        val envelope=JsonObject().apply{addProperty("schemaVersion",3);add("contentObjects",JsonObject().apply{addProperty("hash-old",old)});add("history",JsonArray().apply{add(JsonObject().apply{addProperty("id","revision-$suffix");addProperty("createdAt","2026-07-20T00:00:00Z");addProperty("reason","autosave");addProperty("contentHash","hash-old")})})}
        val app=rule.activity.application as NotebookApp
        runBlocking{app.database.dao().put(NoteEntity(id,title=title,body="当前版本",snapshotJson=envelope.toString(),dirty=false))}
        assertEquals(1,runBlocking{app.repository.revisions(id).size})
        rule.activityRule.scenario.recreate();rule.onNodeWithText(title).performClick()
        rule.onNodeWithContentDescription("笔记更多").performClick();rule.onNodeWithText("版本历史").performClick()
        rule.waitUntil(5_000){rule.onAllNodesWithTag("history-revision-revision-$suffix",useUnmergedTree=true).fetchSemanticsNodes().isNotEmpty()}
        rule.onNodeWithTag("history-revision-revision-$suffix",useUnmergedTree=true).performClick()
        rule.onNodeWithTag("history-preview",useUnmergedTree=true).assertIsDisplayed()
        rule.onNodeWithText("恢复此版本").assertIsDisplayed()
    }

}
