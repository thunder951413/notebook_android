package io.github.notebook.android

import io.github.notebook.android.data.FolderEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NewItemDraftTest {
    private val endOfToday = 1_800_000_000_000L

    @Test fun planDestinationsCreateTodoItems() {
        listOf(Destination.Today, Destination.Important, Destination.Todos).forEach { destination ->
            val draft = newItemDraft(destination, null, null, null, endOfToday, "item-${destination.name}")
            assertEquals("todo", draft.itemType)
        }
    }

    @Test fun todoListSelectionOverridesStaleNoteDestination() {
        val folder = FolderEntity("plan-folder", "计划", type = "todoList")
        val draft = newItemDraft(Destination.All, folder, folder.id, null, endOfToday, "planned-item")

        assertEquals("todo", draft.itemType)
        assertEquals(folder.id, draft.folderId)
        assertEquals(folder.name, draft.folderName)
    }

    @Test fun todayAndImportantDefaultsAreAppliedOnlyToTodos() {
        val today = newItemDraft(Destination.Today, null, null, null, endOfToday, "today-item")
        val important = newItemDraft(Destination.Important, null, null, null, endOfToday, "important-item")
        val note = newItemDraft(Destination.All, null, null, null, endOfToday, "note-item")

        assertEquals(endOfToday, today.dueAt)
        assertEquals(true, important.important)
        assertNull(note.dueAt)
        assertEquals(false, note.important)
        assertEquals("note", note.itemType)
    }
}
