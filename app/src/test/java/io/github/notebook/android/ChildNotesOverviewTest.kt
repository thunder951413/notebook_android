package io.github.notebook.android

import io.github.notebook.android.data.NoteEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class ChildNotesOverviewTest {
    private fun note(id: String, parent: String? = null, body: String = "") =
        NoteEntity(id, title = id, body = body, parentPageId = parent, folderId = "library", folderName = "library", sortOrder = 1000.0).hierarchySummary()

    @Test
    fun aCardShowsTheBodyOrHowManySubNotesAnEmptyParentHolds() {
        assertEquals("开头一行", childAwarePreview("  开头一行 ", 3))
        assertEquals("3 个子笔记", childAwarePreview("", 3))
        assertEquals("1 个子笔记", childAwarePreview("   ", 1))
        assertEquals("", childAwarePreview("", 0))
    }

    @Test
    fun theOverviewListsDirectChildrenOnly() {
        val index = NoteHierarchyIndex(listOf(note("parent"), note("a", "parent"), note("b", "parent"), note("a1", "a")))
        assertEquals(listOf("a", "b"), index.children["parent"].orEmpty().map { it.id })
        assertEquals(1, index.children["a"].orEmpty().size)
        assertEquals(0, index.children["b"].orEmpty().size)
    }
}
