package io.github.notebook.android.data

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.notebook.android.sync.SyncRepository
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class)
class TrashRestoreTest {
    private lateinit var db: NotebookDatabase
    private lateinit var repo: SyncRepository

    @Before fun setup() {
        val context: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, NotebookDatabase::class.java).allowMainThreadQueries().build()
        repo = SyncRepository(context, db.dao(), context.getSharedPreferences(UUID.randomUUID().toString(), 0))
    }

    @After fun close() { db.close() }

    private fun snapshot() = runBlocking { db.dao().allNotes().map { Triple(it.id, it.title + "|" + it.body + "|" + it.parentPageId, it.deletedAt == null) }.sortedBy { it.first } }

    @Test fun `trashing and restoring a page with children brings back exactly the same notes`() = runBlocking {
        db.dao().putFolder(FolderEntity("f", "工作"))
        repo.save(NoteEntity("p", title = "目录", body = "", folderId = "f", folderName = "工作", sortOrder = 1000.0))
        repo.save(NoteEntity("c1", title = "子一", body = "正文一", folderId = "f", folderName = "工作", parentPageId = "p", sortOrder = 1000.0))
        repo.save(NoteEntity("c2", title = "子二", body = "正文二", folderId = "f", folderName = "工作", parentPageId = "p", sortOrder = 2000.0))
        repo.save(NoteEntity("g", title = "孙", body = "正文三", folderId = "f", folderName = "工作", parentPageId = "c1", sortOrder = 1000.0))
        val before = snapshot()
        repo.moveToTrash("p")
        assertEquals(listOf(false, false, false, false), snapshot().map { it.third })
        // The trash lists every page; restoring them all (parent first or child first) must not add anything.
        repo.restore("c1")
        repo.restore("p")
        repo.restore("c2")
        assertEquals(before, snapshot())
        assertEquals(4, repo.notes.first().size)
    }

    @Test fun `a child deleted earlier on its own stays in the trash when its parent is restored`() = runBlocking {
        db.dao().putFolder(FolderEntity("f", "工作"))
        repo.save(NoteEntity("p", title = "目录", folderId = "f", folderName = "工作", sortOrder = 1000.0))
        repo.save(NoteEntity("keep", title = "保留", body = "正文", folderId = "f", folderName = "工作", parentPageId = "p", sortOrder = 1000.0))
        repo.save(NoteEntity("old", title = "", body = "", folderId = "f", folderName = "工作", parentPageId = "p", sortOrder = 2000.0))
        repo.moveToTrash("old")
        Thread.sleep(5)
        repo.moveToTrash("p")
        repo.restore("p")
        val live = db.dao().allNotes().filter { it.deletedAt == null }.map { it.id }.sorted()
        assertEquals(listOf("keep", "p"), live)
        repo.restore("old")
        assertEquals("p", db.dao().get("old")!!.parentPageId)
        assertEquals(null, db.dao().get("old")!!.deletedAt)
    }
}
