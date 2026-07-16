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

    @Test fun `summary query never loads large note payloads`()=runBlocking {
        val body="正文".repeat(2_000)
        val preview="持久化摘要"
        dao.put(NoteEntity("large",title="大笔记",body=body,previewText=preview,snapshotJson="x".repeat(3_000_000)))
        val summary=dao.observeNoteSummaries().first().single()
        assertEquals("large",summary.id)
        assertEquals(preview,summary.preview)
    }

    @Test fun `editable partial update preserves sync snapshots`()=runBlocking {
        dao.put(NoteEntity("n",title="旧标题",body="旧正文",snapshotJson="local-snapshot",conflictSnapshotJson="remote-snapshot"))
        dao.updateEditable(NoteEditableUpdate("n","新标题","新正文","新摘要",1,2,null,"未分类",null,"none",2,"",null,"note",null,null,false,true,false,0))
        val stored=dao.get("n")!!
        assertEquals("新正文",stored.body)
        assertEquals("local-snapshot",stored.snapshotJson)
        assertEquals("remote-snapshot",stored.conflictSnapshotJson)
    }

    @Test fun `large body and snapshots can be read in cursor safe chunks`()=runBlocking {
        val body="正文块".repeat(800_000)
        val snapshot="快照块".repeat(800_000)
        dao.put(NoteEntity("chunked",body=body,snapshotJson=snapshot))
        suspend fun chunks(length:Int?,read:suspend(Int,Int)->String?)=buildString{var start=1;while(start<=(length?:0)){val chunk=read(start,64*1024)?:break;append(chunk);start+=chunk.length}}
        assertEquals(body,chunks(dao.bodyLength("chunked")){start,length->dao.bodyChunk("chunked",start,length)})
        assertEquals(snapshot,chunks(dao.snapshotLength("chunked")){start,length->dao.snapshotChunk("chunked",start,length)})
    }

    @Test fun `large crash draft can be recovered in cursor safe chunks`()=runBlocking {
        val payload="草稿".repeat(900_000)
        dao.putDraft(DraftEntity("large-draft",payload,42))
        val rebuilt=buildString{var start=1;while(start<=(dao.draftPayloadLength("large-draft")?:0)){val chunk=dao.draftPayloadChunk("large-draft",start,64*1024)?:break;append(chunk);start+=chunk.length}}
        assertEquals(payload,rebuilt)
        assertEquals(listOf("large-draft"),dao.draftNoteIds())
    }

    @Test fun `full body search returns ids without loading payloads`()=runBlocking {
        dao.put(NoteEntity("match",body="开头"+"填充".repeat(1_000)+"深处关键词",snapshotJson="x".repeat(3_000_000)))
        assertEquals(listOf("match"),dao.searchNoteIds("深处关键词"))
    }

    @Test fun `note deletion cascades steps and assets`()=runBlocking {
        dao.put(NoteEntity("n"));dao.putStep(TodoStepEntity("s","n","步骤"));dao.putAssets(listOf(AssetEntity("a","n","file","a.txt","text/plain","n/a.txt")));dao.putReadingPosition(ReadingPositionEntity("n",42,.1,99,"phone"))
        dao.deleteNotePermanently("n")
        assertTrue(dao.steps("n").isEmpty());assertTrue(dao.assets("n").isEmpty());assertNull(dao.readingPosition("n"))
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

    @Test fun `reading position is independent from note version and dirty state`()=runBlocking {
        dao.put(NoteEntity("n",version=7,dirty=false))
        dao.putReadingPosition(ReadingPositionEntity("n",321,.25,1234,"android"))
        val note=dao.get("n")!!;val position=dao.readingPosition("n")!!
        assertEquals(7,note.version);assertFalse(note.dirty);assertEquals(321,position.anchorUtf16Offset);assertEquals(.25,position.viewportOffsetFraction,0.0)
    }
}
