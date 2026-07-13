package io.github.notebook.android.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import android.app.Application

@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,application=Application::class)
class DatabaseTest {
    private lateinit var db:NotebookDatabase
    private lateinit var dao:NotebookDao
    @Before fun open(){db=Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(),NotebookDatabase::class.java).allowMainThreadQueries().build();dao=db.dao()}
    @After fun close()=db.close()

    @Test fun `recycle bin rows remain observable`()=runBlocking {
        val deleted=NoteEntity("deleted",title="回收站",deletedAt=123L)
        dao.put(deleted)
        assertEquals(deleted,dao.observeNotes().first().single())
    }

    @Test fun `note deletion cascades steps and assets`()=runBlocking {
        dao.put(NoteEntity("n"));dao.putStep(TodoStepEntity("s","n","步骤"));dao.putAssets(listOf(AssetEntity("a","n","file","a.txt","text/plain","n/a.txt")))
        dao.deleteNotePermanently("n")
        assertTrue(dao.steps("n").isEmpty());assertTrue(dao.assets("n").isEmpty())
    }

    @Test fun `updating note never deletes its steps or assets`()=runBlocking {
        dao.put(NoteEntity("n"));dao.putStep(TodoStepEntity("s","n","步骤"));dao.putAssets(listOf(AssetEntity("a","n","file","a.txt","text/plain","n/a.txt")))
        dao.put(dao.get("n")!!.copy(title="修改后",version=2))
        assertEquals("步骤",dao.steps("n").single().text);assertEquals("a.txt",dao.assets("n").single().filename)
    }

    @Test fun `conflict fields and snapshot survive room round trip`()=runBlocking {
        val note=NoteEntity("n",conflict=true,snapshotJson="{\"local\":1}",conflictSnapshotJson="{\"remote\":2}",lastSyncedVersion=7)
        dao.put(note)
        assertEquals(note,dao.get("n"))
    }

    @Test fun `late sync acknowledgement cannot clear a newer local edit`()=runBlocking {
        dao.put(NoteEntity("n",version=3,dirty=true))
        dao.markSynced("n",2)
        assertTrue(dao.get("n")!!.dirty)
        dao.markSynced("n",3)
        assertFalse(dao.get("n")!!.dirty);assertEquals(3,dao.get("n")!!.lastSyncedVersion)
    }

    @Test fun `draft survives independently until formal note commit`()=runBlocking {
        dao.putDraft(DraftEntity("new-note","{\"title\":\"草稿\"}",42))
        assertNull(dao.get("new-note"));assertEquals("new-note",dao.allDrafts().single().noteId)
        dao.deleteDraft("new-note");assertTrue(dao.allDrafts().isEmpty())
    }
}
