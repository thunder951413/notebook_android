package io.github.notebook.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import io.github.notebook.android.data.NoteEntity
import io.github.notebook.android.sync.WebdavSettings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * End-to-end v4 journal sync (publish → delete → restore from a fresh device)
 * against any WebDAV server. Run with the local fixture:
 *
 *   python3 scripts/test_webdav_server.py --port 2223 --require-auth &
 *   adb reverse tcp:2223 tcp:2223
 *   ./gradlew connectedDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.webdavBaseUrl=http://127.0.0.1:2223/dav/ \
 *     -Pandroid.testInstrumentationRunnerArguments.webdavRemotePath=notebook_backup
 *
 * or against real 坚果云 (credentials passed per run, never stored):
 *
 *   -Pandroid.testInstrumentationRunnerArguments.webdavBaseUrl=https://dav.jianguoyun.com/dav/ \
 *   -Pandroid.testInstrumentationRunnerArguments.webdavUsername=... \
 *   -Pandroid.testInstrumentationRunnerArguments.webdavAppPassword=...
 */
@RunWith(AndroidJUnit4::class)
class WebdavSyncInstrumentedTest {
    @Test fun firstSyncPublishesLibraryAndRemoteRestoresIt()=runBlocking {
        val arguments=InstrumentationRegistry.getArguments()
        val baseUrl=arguments.getString("webdavBaseUrl").orEmpty()
        assumeTrue("WebDAV fixture not configured",baseUrl.isNotBlank())
        val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as NotebookApp
        val repo=app.repository
        // The app schedules a background sync on startup (SyncWorker) which
        // holds the repository mutex (with exponential retries on failure);
        // cancel it so this test drives sync deterministically.
        WorkManager.getInstance(app).cancelAllWork()
        repo.saveWebdavSettings(WebdavSettings(
            baseUrl=baseUrl,
            username=arguments.getString("webdavUsername").orEmpty().ifBlank{"notebook"},
            appPassword=arguments.getString("webdavAppPassword").orEmpty().ifBlank{"test-app-password"},
            remotePath=arguments.getString("webdavRemotePath").orEmpty().ifBlank{"notebook_backup"},
        ))
        assertEquals(io.github.notebook.android.sync.SyncBackend.WEBDAV,repo.syncBackend())

        val id=UUID.randomUUID().toString()
        repo.save(NoteEntity(id=id,title="WebDAV 端到端",body="# 标题\n- [x] 完成",version=0,dirty=true))
        repo.sync()
        val stored=app.database.dao().get(id)!!
        assertFalse(stored.dirty)
        assertEquals(stored.version,stored.lastSyncedVersion)

        // Remove the local row, then prove a fresh device joining the same
        // journal replays every entry and restores the note. (v4 keeps
        // per-device read cursors, so this device's own push would otherwise
        // already be consumed.)
        app.database.dao().deleteNotePermanently(id)
        repo.rotateWebdavDeviceId()
        repo.sync()
        val restored=app.database.dao().get(id)
        assertEquals("WebDAV 端到端",restored?.title)
        assertEquals("# 标题\n- [x] 完成",restored?.body)
    }
}
