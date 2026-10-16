package io.github.notebook.android.sync

import io.github.notebook.android.data.ApiNotebookEntity
import io.github.notebook.android.data.FolderEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryPlacementTest {
    private fun notebook(id: String, order: Int = 0, deleted: Boolean = false) = ApiNotebookEntity(id, "w", id, sortOrder = order, deletedAt = if (deleted) 1L else null)
    private fun folder(id: String, notebookId: String, type: String = "noteFolder") = FolderEntity(id, id, type = type, notebookId = notebookId)

    @Test fun `a new library joins the notebook most libraries are in`() {
        val notebooks = listOf(notebook("work", 0), notebook("life", 1))
        val folders = listOf(folder("a", "life"), folder("b", "life"), folder("c", "work"), folder("d", "personal"), folder("p", "private-x", "encryptedFolder"))
        assertEquals("life", notebookForNewLibrary(folders, notebooks))
    }

    @Test fun `without libraries the first live notebook is used, and only an empty library falls back`() {
        assertEquals("b", notebookForNewLibrary(emptyList(), listOf(notebook("a", 0, deleted = true), notebook("b", 2), notebook("private-z", 0), notebook("c", 3))))
        assertEquals("personal", notebookForNewLibrary(listOf(folder("x", "personal")), emptyList()))
    }
}
