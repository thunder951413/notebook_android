package io.github.notebook.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.notebook.android.data.NoteEntity
import io.github.notebook.android.sync.SshSettings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SftpSyncTest {
    @Test fun uploadCreatesRemoteEnvelopeAndCleansCommittedVersion()=runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation();val fingerprint=InstrumentationRegistry.getArguments().getString("sftpFingerprint").orEmpty();assumeTrue("SFTP fixture not configured",fingerprint.startsWith("SHA256:"))
        val app=instrumentation.targetContext.applicationContext as NotebookApp;val repo=app.repository
        repo.saveSettings(SshSettings("10.0.2.2",2222,"notebook","test-password","/sync",fingerprint))
        val id=UUID.randomUUID().toString();repo.save(NoteEntity(id,title="SFTP 端到端",body="# 标题\n- [x] 完成"))
        val recording=java.io.File(app.cacheDir,"fixture.m4a").apply{writeBytes(ByteArray(4096){(it%251).toByte()})};val uploadedAsset=repo.addRecordedAudio(id,recording)
        repo.sync()
        val stored=app.database.dao().get(id)!!;assertFalse(stored.dirty);assertEquals(stored.version,stored.lastSyncedVersion)
        assertFalse(app.database.dao().assets(id).single().dirty)

        // Remove the local database row and bytes, then prove the same server can restore both.
        java.io.File(uploadedAsset.localPath!!).delete();app.database.dao().deleteNotePermanently(id)
        repo.sync()
        val restored=app.database.dao().get(id)!!;assertEquals("SFTP 端到端",restored.title);assertEquals("# 标题\n- [x] 完成",restored.body)
        val restoredAsset=app.database.dao().assets(id).single();assertTrue(java.io.File(restoredAsset.localPath!!).isFile);assertEquals(4096,java.io.File(restoredAsset.localPath!!).length())
    }
}
