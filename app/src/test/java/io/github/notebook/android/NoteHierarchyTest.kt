package io.github.notebook.android

import io.github.notebook.android.data.NoteEntity
import org.junit.Assert.*
import org.junit.Test

class NoteHierarchyTest {
    private fun note(
        id: String,
        parent: String? = null,
        title: String = id,
        folder: String = "library",
        order: Double = 1000.0,
    ) =
        NoteEntity(
                id,
                title = title,
                parentPageId = parent,
                folderId = folder,
                folderName = folder,
                sortOrder = order,
            )
            .hierarchySummary()

    @Test
    fun collapseAndManualOrderPreserveDepthAndCounts() {
        val index =
            NoteHierarchyIndex(
                listOf(
                    note("other", order = 2000.0),
                    note("grand", "child"),
                    note("root"),
                    note("child", "root"),
                )
            )
        assertEquals(listOf("root", "other"), index.rows(emptySet()).map { it.note.id })
        assertEquals(listOf(0, 1, 2, 0), index.rows(setOf("root", "child")).map { it.depth })
        assertEquals(2, index.counts["root"])
        assertEquals(1, index.counts["child"])
        assertEquals(setOf("child", "grand"), index.descendants("root"))
    }

    @Test
    fun missingAndForeignLibraryParentsBecomeVisibleRoots() {
        val index =
            NoteHierarchyIndex(
                listOf(
                    note("missing", "deleted"),
                    note("foreign", "parent", folder = "other"),
                    note("parent"),
                )
            )
        assertEquals(
            setOf("missing", "foreign", "parent"),
            index.rows(emptySet()).map { it.note.id }.toSet(),
        )
        assertNull(index.parent("foreign"))
        assertEquals("other › foreign", index.path("foreign"))
    }

    @Test
    fun cyclesAreDisplayRootsWithoutDroppingNodesOrMutatingOriginals() {
        val notes = listOf(note("a", "b"), note("b", "a"), note("c", "b"), note("self", "self"))
        val index = NoteHierarchyIndex(notes)
        assertEquals(
            notes.map { it.id }.toSet(),
            index.rows(index.byId.keys).map { it.note.id }.toSet(),
        )
        assertEquals("b", notes.first().parentPageId)
        assertTrue(index.ancestors("c").size <= 2)
    }

    @Test
    fun sameTitlesHaveDistinctPathsAndAncestors() {
        val index =
            NoteHierarchyIndex(
                listOf(
                    note("work", title = "工作"),
                    note("travel", title = "旅行"),
                    note("one", "work", "记录"),
                    note("two", "travel", "记录"),
                )
            )
        assertEquals("library › 工作 › 记录", index.path("one"))
        assertEquals("library › 旅行 › 记录", index.path("two"))
        assertEquals(listOf("travel"), index.ancestors("two").map { it.id })
        assertEquals("two", index.search("旅行 记录").single().id)
    }

    @Test
    fun titleMatchesRankAheadOfPathAndSummaryMatches() {
        val notes =
            listOf(
                note("path", "parent", "公交"),
                note("parent", title = "酒店计划"),
                note("exact", title = "酒店"),
                note("summary").copy(preview = "酒店住宿"),
            )
        val result = NoteHierarchyIndex(notes).search("酒店").map { it.id }
        assertEquals(listOf("exact", "parent", "path", "summary"), result)
    }

    @Test
    fun scopedSearchAndRowsStayInsideSelectedLibrary() {
        val index =
            NoteHierarchyIndex(
                listOf(note("one", title = "记录"), note("two", title = "记录", folder = "other"))
            )
        assertEquals(listOf("one"), index.search("记录", "library", true).map { it.id })
        assertEquals(listOf("two"), index.rows(emptySet(), "other", true).map { it.note.id })
    }

    @Test
    fun deletedAndTaskNotesNeverBecomeActiveStructureNodes() {
        val index =
            NoteHierarchyIndex(
                listOf(
                    note("active"),
                    note("deleted").copy(deletedAt = 3),
                    note("task").copy(itemType = "todo"),
                )
            )
        assertEquals(setOf("active"), index.byId.keys)
    }

    @Test
    fun inaccessibleParentCannotLeakIntoPathsOrSearch() {
        val index = NoteHierarchyIndex(listOf(note("accessible", "locked-id")))
        assertEquals("library › accessible", index.path("accessible"))
        assertTrue(index.search("locked-id").isEmpty())
    }

    @Test
    fun fiveThousandWideAndDeepNodesNeedNoRecursiveTraversal() {
        val wide = (0 until 5000).map { note("wide-$it") }
        assertEquals(5000, NoteHierarchyIndex(wide).rows(emptySet()).size)
        val deep = (0 until 1500).map { note("deep-$it", if (it == 0) null else "deep-${it - 1}") }
        val index = NoteHierarchyIndex(deep)
        assertEquals(1500, index.rows(index.byId.keys).size)
        assertEquals(1499, index.counts["deep-0"])
        assertEquals(1499, index.ancestors("deep-1499").size)
    }
}
