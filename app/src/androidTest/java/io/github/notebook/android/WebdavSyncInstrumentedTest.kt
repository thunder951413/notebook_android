package io.github.notebook.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import io.github.notebook.android.data.NotebookDatabase
import io.github.notebook.android.sync.SyncRepository
import org.junit.After
import io.github.notebook.android.data.NoteEntity
import io.github.notebook.android.data.AssetEntity
import io.github.notebook.android.data.FolderEntity
import io.github.notebook.android.data.ReadingPositionEntity
import io.github.notebook.android.data.TodoStepEntity
import io.github.notebook.android.sync.ApiSyncClient
import io.github.notebook.android.sync.WebdavJournalProtocol
import io.github.notebook.android.sync.WebdavSettings
import kotlinx.coroutines.runBlocking
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.net.URI
import android.util.Base64

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
    private data class FixtureRequest(val method:String,val path:String)
    private data class FixtureSnapshot(val requests:List<FixtureRequest>,val files:Map<String,ByteArray>)
    private class FixtureContext(context:Context):ContextWrapper(context) {
        val database=Room.inMemoryDatabaseBuilder(context,NotebookDatabase::class.java).build()
        val prefs=context.getSharedPreferences("webdav-device-test-${UUID.randomUUID()}",0)
        val repository=SyncRepository(this,database.dao(),prefs)
    }
    private var fixture:FixtureContext?=null

    @After fun closeFixture(){fixture?.database?.close()}

    private fun fixtureArguments(suffix:String=""):Pair<WebdavSettings,FixtureContext> {
        val arguments=InstrumentationRegistry.getArguments()
        val baseUrl=arguments.getString("webdavBaseUrl").orEmpty()
        assumeTrue("WebDAV fixture not configured",baseUrl.isNotBlank())
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        WorkManager.getInstance(context).cancelAllWork().result.get()
        val app=FixtureContext(context).also{fixture=it}
        val basePath=arguments.getString("webdavRemotePath").orEmpty().ifBlank{"notebook_backup"}
        val settings=WebdavSettings(
            baseUrl=baseUrl,
            username=arguments.getString("webdavUsername").orEmpty().ifBlank{"notebook"},
            appPassword=arguments.getString("webdavPassword").orEmpty()
                .ifBlank{arguments.getString("webdavAppPassword").orEmpty()}.ifBlank{"test-app-password"},
            remotePath=basePath+suffix+"_${UUID.randomUUID()}",
        )
        fixtureRequest(settings,"/__test__/reset-stats","""{"clearFaults":true}""")
        return settings to app
    }

    private fun fixtureRequest(settings:WebdavSettings,path:String,payload:String?=null):String {
        val uri=URI(settings.baseUrl)
        val url="${uri.scheme}://${uri.host}${if(uri.port>=0)":${uri.port}" else ""}$path"
        val builder=Request.Builder().url(url).header("Authorization",Credentials.basic(settings.username,settings.appPassword))
        if(payload==null)builder.get() else builder.post(payload.toRequestBody("application/json".toMediaType()))
        return OkHttpClient().newCall(builder.build()).execute().use{response->
            assertTrue("fixture admin $path failed: ${response.code}",response.isSuccessful)
            response.body?.string().orEmpty()
        }
    }

    private fun fixtureSnapshot(settings:WebdavSettings):FixtureSnapshot {
        val root=JsonParser.parseString(fixtureRequest(settings,"/__test__/snapshot")).asJsonObject
        val requests=root["requests"].asJsonArray.map{raw->val item=raw.asJsonObject;FixtureRequest(item["method"].asString,item["path"].asString)}
        val files=root["files"].asJsonObject.entrySet().associate{it.key to Base64.decode(it.value.asString,Base64.DEFAULT)}
        return FixtureSnapshot(requests,files)
    }

    @Test fun firstSyncPublishesLibraryAndRemoteRestoresIt()=runBlocking {
        val (settings,app)=fixtureArguments("_roundtrip")
        val repo=app.repository
        // The app schedules a background sync on startup (SyncWorker) which
        // holds the repository mutex (with exponential retries on failure);
        // cancel it so this test drives sync deterministically.
        repo.saveWebdavSettings(settings)
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

    @Test fun unchangedSyncOnlyReadsSmallHeads()=runBlocking {
        val (settings,app)=fixtureArguments("_fastpath")
        val repo=app.repository
        repo.saveWebdavSettings(settings)
        val id=UUID.randomUUID().toString()
        val note=NoteEntity(id=id,title="fast-path",body="small",dirty=true)
        app.database.dao().put(note)
        ApiSyncClient(app,app.database.dao(),allowInsecureHttp=true).queueNote(WebdavJournalProtocol.DEFAULT_WORKSPACE_ID,note)
        repo.sync()

        fixtureRequest(settings,"/__test__/reset-stats","{}")
        repo.sync()
        val requests=fixtureSnapshot(settings).requests.filter{it.path.startsWith(settings.remotePath)}

        assertFalse(requests.any{it.method=="PUT"})
        assertFalse(requests.any{it.method=="GET"&&it.path.endsWith(".jsonl")})
        assertTrue(requests.any{it.method=="GET"&&it.path.endsWith(".head.json")})
    }

    @Test fun missingLocalAttachmentRecoversEvenAfterJournalCursorWasConsumed()=runBlocking {
        val (settings,app)=fixtureArguments("_missing_local_attachment")
        val repo=app.repository
        repo.saveWebdavSettings(settings)
        val id=UUID.randomUUID().toString()
        val assetId=UUID.randomUUID().toString()
        val bytes=ByteArray(4096){(it%251).toByte()}
        val file=java.io.File(app.filesDir,"attachments/$id/$assetId.png").apply{parentFile?.mkdirs();writeBytes(bytes)}
        val note=NoteEntity(id=id,title="attachment recovery",body="keep this edit",dirty=true)
        val asset=AssetEntity(assetId,id,"image","image.png","image/png","$id/$assetId.png",file.absolutePath,WebdavJournalProtocol.sha256(bytes),bytes.size.toLong(),true)
        app.database.dao().put(note)
        app.database.dao().putAssets(listOf(asset))
        val api=ApiSyncClient(app,app.database.dao(),allowInsecureHttp=true)
        api.queueNote(WebdavJournalProtocol.DEFAULT_WORKSPACE_ID,note)
        repo.sync()

        assertTrue(file.delete())
        app.database.dao().putAssets(listOf(asset))
        api.queueAsset(WebdavJournalProtocol.DEFAULT_WORKSPACE_ID,asset)
        fixtureRequest(settings,"/__test__/reset-stats","{}")
        val progress=mutableListOf<String>()
        repo.sync(progress::add)

        val restored=app.database.dao().getAsset(assetId)!!
        assertArrayEquals(bytes,java.io.File(restored.localPath!!).readBytes())
        assertFalse(restored.dirty)
        assertNull(app.database.dao().apiOutboxItem("asset",assetId))
        assertTrue(progress.any{it.contains("恢复附件")})
        val requests=fixtureSnapshot(settings).requests
        assertEquals(1,requests.count{it.method=="GET"&&it.path.endsWith(asset.contentHash)})
        assertFalse(requests.any{it.method=="PUT"&&it.path.endsWith(asset.contentHash)})
    }

    @Test fun transientJournalFailureKeepsOutboxAndRetryRecovers()=runBlocking {
        val (settings,app)=fixtureArguments("_retry")
        val repo=app.repository
        repo.saveWebdavSettings(settings)
        val id=UUID.randomUUID().toString()
        val note=NoteEntity(id=id,title="retry-note",body="must survive",dirty=true)
        app.database.dao().put(note)
        ApiSyncClient(app,app.database.dao(),allowInsecureHttp=true).queueNote(WebdavJournalProtocol.DEFAULT_WORKSPACE_ID,note)
        fixtureRequest(settings,"/__test__/fail","""{"method":"PUT","suffix":".jsonl","count":1,"code":503}""")

        val failed=runCatching{repo.sync()}
        assertTrue(failed.isFailure)
        assertTrue(app.database.dao().get(id)!!.dirty)
        assertTrue(app.database.dao().apiOutbox(WebdavJournalProtocol.DEFAULT_WORKSPACE_ID).any{it.entityId==id})

        repo.sync()
        assertFalse(app.database.dao().get(id)!!.dirty)
        assertFalse(app.database.dao().apiOutbox(WebdavJournalProtocol.DEFAULT_WORKSPACE_ID).any{it.entityId==id})
    }

    @Test fun privateNoteRelationsNeverReachWebdav()=runBlocking {
        val (settings,app)=fixtureArguments("_privacy")
        val repo=app.repository
        repo.saveWebdavSettings(settings.copy(syncPassword="not-used-on-android"))
        val dao=app.database.dao()
        val api=ApiSyncClient(app,dao,allowInsecureHttp=true)
        val folderId=UUID.randomUUID().toString()
        val noteId=UUID.randomUUID().toString()
        val stepId=UUID.randomUUID().toString()
        val assetId=UUID.randomUUID().toString()
        val secret="PRIVATE-${UUID.randomUUID()}"
        val folder=FolderEntity(folderId,"$secret-folder",0,"encryptedFolder")
        val note=NoteEntity(id=noteId,title="$secret-title",body="$secret-body",folderId=folderId,folderName="$secret-folder",dirty=true)
        val step=TodoStepEntity(stepId,noteId,"$secret-step")
        val bytes="$secret-asset".toByteArray()
        val file=java.io.File(app.filesDir,"attachments/$noteId/$assetId.bin").apply{parentFile?.mkdirs();writeBytes(bytes)}
        val asset=AssetEntity(assetId,noteId,"file","$secret.bin","application/octet-stream","$noteId/$assetId.bin",file.absolutePath,WebdavJournalProtocol.sha256(bytes),bytes.size.toLong(),true)
        val position=ReadingPositionEntity(noteId,9,0.5,System.currentTimeMillis(),"privacy-test")
        dao.putFolder(folder);dao.put(note);dao.putStep(step);dao.putAssets(listOf(asset));dao.putReadingPosition(position)
        repo.markEncrypted(noteId,folderId)
        api.queueFolder(WebdavJournalProtocol.DEFAULT_WORKSPACE_ID,folder)
        api.queueNote(WebdavJournalProtocol.DEFAULT_WORKSPACE_ID,note)
        api.queueStep(WebdavJournalProtocol.DEFAULT_WORKSPACE_ID,step)
        api.queueAsset(WebdavJournalProtocol.DEFAULT_WORKSPACE_ID,asset)
        api.queueReadingPosition(WebdavJournalProtocol.DEFAULT_WORKSPACE_ID,position)

        repo.sync()

        val remote=fixtureSnapshot(settings).files.filterKeys{it.startsWith(settings.remotePath)}.values
            .joinToString("\n"){String(it,Charsets.UTF_8)}
        assertFalse(remote.contains(secret))
        assertFalse(remote.contains(noteId))
        assertFalse(remote.contains(stepId))
        assertFalse(remote.contains(assetId))
        assertTrue(dao.apiOutbox(WebdavJournalProtocol.DEFAULT_WORKSPACE_ID).none{it.entityId in setOf(noteId,stepId,assetId,"$noteId:privacy-test")})
    }

    @Test fun authenticationFailureIsRedacted()=runBlocking {
        val (settings,app)=fixtureArguments("_auth")
        val marker="secret-${UUID.randomUUID()}"
        app.repository.saveWebdavSettings(settings.copy(appPassword=marker))

        val result=app.repository.testWebdav()

        assertFalse(result.ok)
        assertTrue(result.message.contains("认证失败"))
        assertFalse(result.message.contains(marker))
        assertFalse(result.message.contains("Basic "))
    }

    @Test fun corruptContentAddressedObjectIsRepairedBeforePublish()=runBlocking {
        val (settings,app)=fixtureArguments("_object_repair")
        val repo=app.repository
        repo.saveWebdavSettings(settings)
        val id=UUID.randomUUID().toString()
        val body="OBJECT-REPAIR-"+"x".repeat(4000)
        val note=NoteEntity(id=id,title="object repair",body=body,dirty=true)
        val api=ApiSyncClient(app,app.database.dao(),allowInsecureHttp=true)
        app.database.dao().put(note)
        api.queueNote(WebdavJournalProtocol.DEFAULT_WORKSPACE_ID,note)
        repo.sync()
        val objectPath=fixtureSnapshot(settings).files.keys.first{it.startsWith("${settings.remotePath}/objects/")}
        fixtureRequest(settings,"/__test__/corrupt","""{"path":"$objectPath","dataBase64":"${Base64.encodeToString("corrupt".toByteArray(),Base64.NO_WRAP)}"}""")

        val edited=app.database.dao().get(id)!!.copy(body=body,dirty=true)
        app.database.dao().put(edited)
        api.queueNote(WebdavJournalProtocol.DEFAULT_WORKSPACE_ID,edited)
        repo.sync()

        val repaired=fixtureSnapshot(settings).files.getValue(objectPath)
        val expectedHash=objectPath.substringAfterLast('/')
        assertEquals(expectedHash,WebdavJournalProtocol.sha256(repaired))
    }
}
