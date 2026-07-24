package io.github.notebook.android

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertTrue
import kotlinx.coroutines.runBlocking
import androidx.lifecycle.Lifecycle
import io.github.notebook.android.data.FolderEntity
import io.github.notebook.android.data.NoteEntity

class MainActivityTest {
    @get:Rule val rule=createAndroidComposeRule<MainActivity>()

    @Test fun createEditAndSaveNote(){
        rule.onNodeWithTag("new-item").performClick()
        rule.onNodeWithTag("title-field").performTextInput("设备测试笔记")
        rule.onNodeWithTag("body-field").performTextInput("正文内容")
        rule.onNodeWithTag("preview-mode").performClick()
        rule.waitUntil(5_000){rule.onAllNodesWithText("设备测试笔记").fetchSemanticsNodes().isNotEmpty()}
        rule.onAllNodesWithText("设备测试笔记",useUnmergedTree=true).assertAny(hasText("设备测试笔记"))
    }

    @Test fun backNavigationFlushesUnsavedTyping(){
        val title="返回自动保存-${System.nanoTime()}"
        rule.onNodeWithTag("new-item").performClick();rule.onNodeWithTag("title-field").performTextInput(title);rule.onNodeWithTag("body-field").performTextInput("没有点击完成")
        rule.activity.runOnUiThread{rule.activity.onBackPressedDispatcher.onBackPressed()}
        rule.waitUntil(5_000){rule.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty()}
    }

    @Test fun debounceSaveSurvivesActivityRecreation(){
        val title="重建恢复-${System.nanoTime()}"
        rule.onNodeWithTag("new-item").performClick();rule.onNodeWithTag("title-field").performTextInput(title);rule.onNodeWithTag("body-field").performTextInput("旋转或系统重建后保留")
        rule.waitUntil(5_000){runBlocking{((rule.activity.application as NotebookApp).database.dao().allNotes().any{it.title==title&&it.body=="旋转或系统重建后保留"})}}
        rule.activityRule.scenario.recreate()
        rule.waitUntil(5_000){rule.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty()}
    }

    @Test fun immediateRecreationFlushesPendingKeystrokes(){
        val title="立即重建-${System.nanoTime()}";rule.onNodeWithTag("new-item").performClick();rule.onNodeWithTag("title-field").performTextInput(title);rule.onNodeWithTag("body-field").performTextInput("不足防抖时间")
        rule.activityRule.scenario.recreate()
        rule.waitUntil(5_000){rule.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty()}
    }

    @Test fun enteringBackgroundImmediatelyFlushesDraft(){
        val title="后台刷新-${System.nanoTime()}";val app=rule.activity.application as NotebookApp;rule.onNodeWithTag("new-item").performClick();rule.onNodeWithTag("title-field").performTextInput(title)
        rule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        val deadline=System.currentTimeMillis()+5_000;while(System.currentTimeMillis()<deadline&&!runBlocking{app.database.dao().allNotes().any{it.title==title}})Thread.sleep(50)
        assertTrue(runBlocking{app.database.dao().allNotes().any{it.title==title}})
        rule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
    }

    @Test fun drawerScrollsToFoldersAndSettings(){
        val app=rule.activity.application as NotebookApp
        runBlocking{repeat(30){index->app.database.dao().putFolder(FolderEntity("scroll-test-$index","滚动文件夹 $index",index))}}
        rule.activityRule.scenario.recreate()
        if(rule.onAllNodesWithContentDescription("导航").fetchSemanticsNodes().isNotEmpty())rule.onNodeWithContentDescription("导航").performClick()
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
            app.database.dao().putFolder(FolderEntity(selectedId,selectedName,10_000,"noteFolder"))
            app.database.dao().putFolder(FolderEntity(targetId,targetName,10_001,"noteFolder"))
            app.database.dao().put(NoteEntity("target-note-$suffix",title=targetNote,folderId=targetId,folderName=targetName))
        }
        rule.activityRule.scenario.recreate()
        rule.onNodeWithContentDescription("导航").performClick()
        rule.onNodeWithTag("drawer-scroll").performScrollToNode(hasText(selectedName))
        rule.onNodeWithText(selectedName).performClick()
        rule.onNodeWithContentDescription("导航").performClick()
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

    @Test fun newNoteStartsInEditModeAndShowsBriefSavedConfirmation(){rule.onNodeWithTag("new-item").performClick();rule.onNodeWithTag("body-field").assertIsDisplayed();rule.onNodeWithTag("preview-mode").assertIsDisplayed();rule.onNodeWithContentDescription("保存").assertIsDisplayed().performClick();rule.waitUntil(5_000){rule.onAllNodesWithContentDescription("已保存").fetchSemanticsNodes().isNotEmpty()};rule.waitUntil(2_000){rule.onAllNodesWithContentDescription("已保存").fetchSemanticsNodes().isEmpty()}}

    @Test fun topDeleteRequiresConfirmationAndMovesNoteToTrash(){val title="明确删除-${System.nanoTime()}";val app=rule.activity.application as NotebookApp;rule.onNodeWithTag("new-item").performClick();rule.onNodeWithTag("title-field").performTextInput(title);rule.onNodeWithTag("delete-item").assertIsDisplayed().performClick();rule.onNodeWithText("删除笔记？").assertIsDisplayed();rule.onNodeWithText("删除").performClick();rule.waitUntil(5_000){runBlocking{app.database.dao().allNotes().any{it.title==title&&it.deletedAt!=null}}}}

    @Test fun swipeLeftRequiresConfirmationAndMovesNoteToTrash(){
        val suffix=System.nanoTime().toString();val id="swipe-delete-$suffix";val title="左滑删除-$suffix"
        val app=rule.activity.application as NotebookApp
        runBlocking{app.database.dao().put(NoteEntity(id,title=title,body="保留到确认后再删除"))}
        rule.activityRule.scenario.recreate()
        rule.onNodeWithTag("swipe-delete-$id").assertIsDisplayed().performTouchInput{swipeLeft()}
        rule.onNodeWithTag("delete-action-$id").assertIsDisplayed().performClick()
        rule.onNodeWithText("删除笔记？").assertIsDisplayed()
        assertTrue(runBlocking{app.database.dao().allNotes().any{it.id==id&&it.deletedAt==null}})
        rule.onNodeWithText("删除").performClick()
        rule.waitUntil(5_000){runBlocking{app.database.dao().allNotes().any{it.id==id&&it.deletedAt!=null}}}
        runBlocking{app.repository.flushDraft(NoteEntity(id,title=title,body="迟到的编辑器草稿")).join()}
        assertTrue(runBlocking{app.database.dao().allNotes().any{it.id==id&&it.deletedAt!=null}})
    }

    @Test fun todoListsAppearInDrawerAndCreateTodos(){
        val app=rule.activity.application as NotebookApp;runBlocking{app.database.dao().putFolder(FolderEntity("todo-list-test","工作提醒",0,"todoList"));app.database.dao().put(NoteEntity("todo-in-list",title="列表中的待办",itemType="todo",folderId="todo-list-test",folderName="工作提醒"))};rule.activityRule.scenario.recreate()
        if(rule.onAllNodesWithContentDescription("导航").fetchSemanticsNodes().isNotEmpty())rule.onNodeWithContentDescription("导航").performClick()
        rule.onNodeWithTag("drawer-scroll").performScrollToNode(hasText("工作提醒"));rule.onNodeWithText("工作提醒").assertIsDisplayed().performClick();rule.onNodeWithText("列表中的待办").assertIsDisplayed()
        rule.onNodeWithTag("new-item").performClick();rule.onNodeWithTag("body-field").assertIsDisplayed();rule.onNodeWithText("编辑计划").assertIsDisplayed();rule.onNodeWithText("已完成").assertIsDisplayed()
    }

    @Test fun encryptedFoldersAreShownLockedAndNotesStayOutOfAllNotes(){
        val app=rule.activity.application as NotebookApp;runBlocking{app.database.dao().putFolder(FolderEntity("encrypted-test","私人资料",0,"encryptedFolder"));app.database.dao().put(NoteEntity("encrypted-note-test",title="不能公开显示",folderId="encrypted-test",folderName="私人资料"))};app.repository.markEncrypted("encrypted-note-test","encrypted-test");rule.activityRule.scenario.recreate()
        rule.onAllNodesWithText("不能公开显示").assertCountEquals(0)
        if(rule.onAllNodesWithContentDescription("导航").fetchSemanticsNodes().isNotEmpty())rule.onNodeWithContentDescription("导航").performClick()
        rule.onNodeWithTag("drawer-scroll").performScrollToNode(hasText("加密文件夹"));rule.onNodeWithText("加密文件夹").assertIsDisplayed();rule.onNodeWithTag("drawer-scroll").performScrollToNode(hasText("点击解锁"));rule.onNodeWithText("点击解锁").assertIsDisplayed();rule.onAllNodesWithText("私人资料").assertCountEquals(0)
    }

}
