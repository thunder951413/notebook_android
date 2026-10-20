package io.github.notebook.android.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.gson.JsonParser
import io.github.notebook.android.data.ApiPageEntity
import io.github.notebook.android.data.NoteEntity
import io.github.notebook.android.data.NoteSummary
import io.github.notebook.android.data.NotebookDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LibraryRootsTest {
    private lateinit var database: NotebookDatabase
    private val workspace = "workspace-a"
    private val rootPayload = """{"id":"root","sectionId":"novels","title":"小说","legacyMetadata":{"notebookRootSectionId":"novels"}}"""

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), NotebookDatabase::class.java).allowMainThreadQueries().build()
    }
    @After fun tearDown() { database.close() }

    private fun summary(id: String, folderId: String?, preview: String = "") = NoteSummary(
        id = id, title = id, preview = preview, createdAt = 0, updatedAt = 0, folderId = folderId, folderName = "小说",
        icon = null, parentPageId = null, sortOrder = 0.0, treeUpdatedAt = 0, reminderAt = null, recurrence = "none",
        version = 1, tagIds = "", deletedAt = null, itemType = "note", dueAt = null,
        completedAt = null, important = false, dirty = false, conflict = false, lastSyncedVersion = 0,
    )

    @Test fun `a desktop library root page is recognised only for its own section`() {
        assertEquals("novels", libraryRootSectionId(rootPayload))
        assertNull(libraryRootSectionId("""{"sectionId":"other","legacyMetadata":{"notebookRootSectionId":"novels"}}"""))
        assertNull(libraryRootSectionId("""{"sectionId":"novels","deletedAt":"2026-10-20T00:00:00Z","legacyMetadata":{"notebookRootSectionId":"novels"}}"""))
        assertNull(libraryRootSectionId("""{"sectionId":"novels","legacyMetadata":{"future":"kept"}}"""))
        assertNull(libraryRootSectionId("not json"))
    }

    @Test fun `the root page is not listed unless it has its own text`() {
        val roots = mapOf("novels" to "root")
        val listed = withoutLibraryRoots(listOf(summary("root", "novels"), summary("book", "novels")), roots).map { it.id }
        assertEquals(listOf("book"), listed)
        assertEquals(listOf("root"), withoutLibraryRoots(listOf(summary("root", "novels", preview = "简介")), roots).map { it.id })
    }

    @Test fun `a top-level note of a library is uploaded under the library root page`() = runBlocking {
        database.dao().putApiPage(ApiPageEntity("root", rootPayload))
        val client = ApiSyncClient(ApplicationProvider.getApplicationContext(), database.dao(), allowInsecureHttp = true)
        client.queueNote(workspace, NoteEntity(id = "book", title = "新小说", folderId = "novels", folderName = "小说"))
        client.queueNote(workspace, NoteEntity(id = "todo", title = "读完", folderId = "novels", folderName = "小说", itemType = "todo"))
        val pages = database.dao().apiOutbox(workspace).filter { it.entityType == "page" }.associate { it.entityId to JsonParser.parseString(it.payloadJson).asJsonObject }
        assertEquals("root", pages.getValue("book")["parentPageId"].asString)
        assertFalse(pages.getValue("todo").has("parentPageId"))
    }
}
