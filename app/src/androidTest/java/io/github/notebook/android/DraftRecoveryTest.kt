package io.github.notebook.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import io.github.notebook.android.data.DraftEntity
import io.github.notebook.android.data.NoteEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class DraftRecoveryTest {
    @Test fun persistedDraftIsRecoveredAfterProcessStyleRestart()=runBlocking{val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as NotebookApp;val id=UUID.randomUUID().toString();app.database.dao().putDraft(DraftEntity(id,Gson().toJson(NoteEntity(id,title="崩溃恢复",body="未正式提交"))));app.repository.recoverDrafts();val note=app.database.dao().get(id)!!;assertEquals("崩溃恢复",note.title);assertTrue(app.database.dao().allDrafts().none{it.noteId==id})}
}
