package io.github.notebook.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import io.github.notebook.android.data.NoteEntity
import io.github.notebook.android.sync.ApiSyncClient
import io.github.notebook.android.sync.WebdavJournalProtocol
import io.github.notebook.android.sync.WebdavSettings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Host-driven phases used to verify durable outbox recovery across force-stop and offline startup. */
@RunWith(AndroidJUnit4::class)
class WebdavProcessDeathInstrumentedTest {
    private val stateName="webdav-process-death-test"

    private fun app():NotebookApp = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as NotebookApp

    private fun settings():WebdavSettings {
        val arguments=InstrumentationRegistry.getArguments()
        val baseUrl=arguments.getString("webdavBaseUrl").orEmpty()
        assumeTrue("WebDAV fixture not configured",baseUrl.isNotBlank())
        return WebdavSettings(
            baseUrl=baseUrl,
            username=arguments.getString("webdavUsername").orEmpty().ifBlank{"notebook"},
            appPassword=arguments.getString("webdavPassword").orEmpty().ifBlank{"test-app-password"},
            remotePath=arguments.getString("webdavRemotePath").orEmpty().ifBlank{"notebook_backup_process_death"},
        )
    }

    @Test fun prepareDurablePendingChange()=runBlocking {
        val app=app()
        WorkManager.getInstance(app).cancelAllWork()
        app.repository.saveWebdavSettings(settings())
        val id=UUID.randomUUID().toString()
        val marker="PROCESS-DEATH-${UUID.randomUUID()}"
        val note=NoteEntity(id=id,title=marker,body="$marker-body",dirty=true)
        app.database.dao().put(note)
        ApiSyncClient(app,app.database.dao(),allowInsecureHttp=true).queueNote(WebdavJournalProtocol.DEFAULT_WORKSPACE_ID,note)
        app.getSharedPreferences(stateName,0).edit().putString("noteId",id).putString("marker",marker).commit()
        assertTrue(app.database.dao().apiOutbox(WebdavJournalProtocol.DEFAULT_WORKSPACE_ID).any{it.entityId==id})
    }

    @Test fun offlineRestartKeepsPendingChange()=runBlocking {
        val app=app()
        WorkManager.getInstance(app).cancelAllWork()
        val id=app.getSharedPreferences(stateName,0).getString("noteId",null)!!
        val failed=runCatching{app.repository.sync()}
        assertTrue(failed.isFailure)
        assertTrue(app.database.dao().get(id)!!.dirty)
        assertTrue(app.database.dao().apiOutbox(WebdavJournalProtocol.DEFAULT_WORKSPACE_ID).any{it.entityId==id})
    }

    @Test fun restoredNetworkPublishesPendingChange()=runBlocking {
        val app=app()
        WorkManager.getInstance(app).cancelAllWork()
        val state=app.getSharedPreferences(stateName,0)
        val id=state.getString("noteId",null)!!
        val marker=state.getString("marker",null)!!
        app.repository.sync()
        assertFalse(app.database.dao().get(id)!!.dirty)
        assertFalse(app.database.dao().apiOutbox(WebdavJournalProtocol.DEFAULT_WORKSPACE_ID).any{it.entityId==id})

        // Replay from a fresh device cursor to prove the durable change reached
        // the remote journal rather than only being marked clean locally.
        // Simulate the empty Room database and empty version table of a newly
        // joined device while retaining the isolated WebDAV configuration.
        app.database.clearAllTables()
        app.repository.rotateWebdavDeviceId()
        app.repository.sync()
        val restored=app.database.dao().get(id)
        assertNotNull(restored)
        assertTrue(restored!!.body.contains(marker))
    }
}
