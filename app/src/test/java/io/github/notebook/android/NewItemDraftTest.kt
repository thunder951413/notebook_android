package io.github.notebook.android

import io.github.notebook.android.data.FolderEntity
import io.github.notebook.android.data.NoteSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test fun `private notes are excluded from unauthenticated reference choices`() {
        val public = summary("public")
        val private = summary("private")

        val locked = visibleReferenceNotes(listOf(public, private), "source", false) { it.id == "private" }
        val unlocked = visibleReferenceNotes(listOf(public, private), "source", true) { it.id == "private" }

        assertEquals(listOf("public"), locked.map { it.id })
        assertEquals(listOf("public", "private"), unlocked.map { it.id })
        assertFalse(canExposeNote(private, false) { it.id == "private" })
        assertTrue(canExposeNote(private, true) { it.id == "private" })
    }

    @Test fun `app link ids reject foreign links`() {
        assertEquals("deep-link", noteIdFromAppLink("notebook://page/deep-link"))
        assertNull(noteIdFromAppLink("https://example.com/page/nope"))
    }

    private fun summary(id: String) = NoteSummary(
        id = id, title = id, preview = "$id preview", createdAt = 0, updatedAt = 0,
        folderId = null, folderName = "未分类", icon = null, parentPageId = null,
        sortOrder = 0.0, treeUpdatedAt = 0, reminderAt = null, recurrence = "none",
        version = 0, tagIds = "", deletedAt = null, itemType = "note", dueAt = null,
        completedAt = null, important = false, dirty = false, conflict = false, lastSyncedVersion = 0
    )
}
