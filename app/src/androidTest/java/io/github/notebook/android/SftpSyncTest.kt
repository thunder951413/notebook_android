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
    @Test fun permanentDeletionWinsOverConflictedRemoteNoteAndFreshReplica()=runBlocking{
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val fingerprint=InstrumentationRegistry.getArguments().getString("sftpFingerprint").orEmpty()
        assumeTrue("SFTP fixture not configured",fingerprint.startsWith("SHA256:"))
        val context=instrumentation.targetContext
        val app=context.applicationContext as NotebookApp;val repo=app.repository
        androidx.work.WorkManager.getInstance(app).cancelAllWork().result.get();repo.flushAll();app.database.clearAllTables()
        val settings=SshSettings("10.0.2.2",2222,"notebook","test-password","/sync",fingerprint)
        repo.saveSettings(settings)
        val id=UUID.randomUUID().toString()
        repo.save(NoteEntity(id,title="冲突原稿",body="共同正文"));repo.sync()
        val peerDb=androidx.room.Room.inMemoryDatabaseBuilder(context,io.github.notebook.android.data.NotebookDatabase::class.java).build()
        try{
            val peer=io.github.notebook.android.sync.SyncRepository(context,peerDb.dao(),context.getSharedPreferences("sftp-delete-peer-${UUID.randomUUID()}",0))
            peer.saveSettings(settings);peer.sync()
            peer.save(peerDb.dao().get(id)!!.copy(title="服务器修改",body="服务器版本"));peer.sync()
            repo.save(app.database.dao().get(id)!!.copy(title="本机修改",body="本机版本"));repo.sync()
            assertTrue("必须先复现真实同步冲突",app.database.dao().get(id)!!.conflict)
            repo.moveToTrash(id);repo.deletePermanently(id);repo.sync();repo.sync()
            assertNull(app.database.dao().get(id))
            peerDb.clearAllTables();peer.sync()
            assertNull("全新副本不能拉回已彻底删除的冲突笔记",peerDb.dao().get(id))
        }finally{peerDb.close()}
    }
    @Test fun existingRepositoryWithLegacyInvalidTombstonesSynchronizesTwice()=runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val arguments=InstrumentationRegistry.getArguments()
        val host=arguments.getString("existingSftpHost").orEmpty()
        val fingerprint=arguments.getString("existingSftpFingerprint").orEmpty()
        assumeTrue("Existing SFTP repository not configured",host.isNotBlank()&&fingerprint.startsWith("SHA256:"))
        val app=instrumentation.targetContext.applicationContext as NotebookApp
        val repo=app.repository
        repo.saveSettings(SshSettings(
            host=host,
            port=arguments.getString("existingSftpPort")?.toIntOrNull()?:22,
            username=arguments.getString("existingSftpUsername").orEmpty(),
            password=arguments.getString("existingSftpPassword").orEmpty(),
            path=arguments.getString("existingSftpPath").orEmpty(),
            fingerprint=fingerprint,
        ))

        repo.sync()
        assertTrue("Expected the real repository to contain notes",app.database.dao().allNoteIds().isNotEmpty())
        repo.sync()
        assertFalse(app.database.dao().allNoteIds().any{!io.github.notebook.android.sync.RepositoryIdentityContract.isValidNoteID(it)})
    }

    @Test fun uploadCreatesRemoteEnvelopeAndCleansCommittedVersion()=runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation();val fingerprint=InstrumentationRegistry.getArguments().getString("sftpFingerprint").orEmpty();assumeTrue("SFTP fixture not configured",fingerprint.startsWith("SHA256:"))
        val app=instrumentation.targetContext.applicationContext as NotebookApp;val repo=app.repository
        androidx.work.WorkManager.getInstance(app).cancelAllWork().result.get();repo.flushAll();app.database.clearAllTables()
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
