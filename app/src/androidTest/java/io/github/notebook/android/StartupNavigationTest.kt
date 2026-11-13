package io.github.notebook.android

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.github.notebook.android.data.NoteEntity
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class StartupNavigationTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val app get() =
        InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as NotebookApp
    private val note = NoteEntity("startup-note", title = "启动测试笔记")
    private val todo = NoteEntity("startup-todo", title = "启动测试待办", itemType = "todo")

    @Before fun clearNotes() {
        runBlocking {
            app.repository.flushAll()
            app.database.clearAllTables()
        }
        app.getSharedPreferences("editor_preferences", 0).edit().remove("startInTodos").commit()
    }

    @Test fun coldLaunchDefaultsToNotesHomeEvenWhenOpenTodosExist() {
        runBlocking {
            app.database.dao().put(note)
            app.database.dao().put(todo)
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitForText(note.title)
            compose.onNodeWithText(note.title).assertIsDisplayed()

            scenario.recreate()
            waitForText(note.title)
            compose.onNodeWithText(note.title).assertIsDisplayed()
        }
    }

    @Test fun coldLaunchOpensTodosWhenStartInTodosPreferenceEnabled() {
        app.getSharedPreferences("editor_preferences", 0).edit().putBoolean("startInTodos", true).commit()
        runBlocking {
            app.database.dao().put(note)
            app.database.dao().put(todo)
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitForText("全部待办")
            compose.onNodeWithText(todo.title).assertIsDisplayed()

            compose.onNodeWithContentDescription("返回首页").performClick()
            waitForText(note.title)

            scenario.moveToState(Lifecycle.State.CREATED)
            scenario.moveToState(Lifecycle.State.RESUMED)
            compose.onNodeWithText(note.title).assertIsDisplayed()
        }
    }

    @Test fun coldLaunchWithOnlyCompletedOrDeletedTodosShowsNotes() {
        app.getSharedPreferences("editor_preferences", 0).edit().putBoolean("startInTodos", true).commit()
        runBlocking {
            val dao = app.database.dao()
            dao.put(note)
            dao.put(todo.copy(completedAt = 1L))
            dao.put(todo.copy(id = "startup-deleted-todo", deletedAt = 1L))
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            waitForText(note.title)
            compose.onNodeWithText(note.title).assertIsDisplayed()
        }
    }

    private fun waitForText(text: String) {
        compose.waitUntil(5_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }
}

