package io.github.notebook.android.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.gson.JsonParser
import io.github.notebook.android.data.NoteEntity
import io.github.notebook.android.data.NotebookDatabase
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ApiSyncTest {
    private lateinit var database:NotebookDatabase
    private lateinit var server:MockWebServer
    private val workspace="workspace-a"

    @Before fun setUp(){
        database=Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(),NotebookDatabase::class.java).allowMainThreadQueries().build()
        server=MockWebServer().apply{start()}
    }
    @After fun tearDown(){database.close();server.shutdown()}

    @Test fun `first sync pulls page hierarchy and tiptap body`()=runBlocking {
        server.enqueue(json("""{"cursor":4,"has_more":false,"changes":[
          {"cursor":1,"entity_type":"notebook","entity_id":"book","version":1,"operation":"upsert","payload":{"id":"book","workspaceId":"workspace-a","name":"迁移笔记本","sortOrder":0,"createdAt":"2026-01-01T00:00:00Z","updatedAt":"2026-01-01T00:00:00Z"}},
          {"cursor":2,"entity_type":"section","entity_id":"section","version":1,"operation":"upsert","payload":{"id":"section","notebookId":"book","name":"旧资料","sortOrder":0,"createdAt":"2026-01-01T00:00:00Z","updatedAt":"2026-01-01T00:00:00Z"}},
          {"cursor":3,"entity_type":"page","entity_id":"page","version":1,"operation":"upsert","payload":{"id":"page","workspaceId":"workspace-a","sectionId":"section","kind":"document","title":"迁移完成","preview":"正文","favorite":true,"locked":true,"legacyMetadata":{"future":"kept"},"syncStatus":"synced","createdAt":"2026-01-01T00:00:00Z","updatedAt":"2026-01-01T00:00:00Z"}},
          {"cursor":4,"entity_type":"document","entity_id":"page","version":1,"operation":"upsert","payload":{"pageId":"page","schemaVersion":1,"tiptapJson":{"type":"doc","content":[{"type":"paragraph","content":[{"type":"text","text":"来自网页的正文"}]}]},"updatedAt":"2026-01-01T00:00:00Z"}}
        ]}"""))
        server.enqueue(json("""{"cursor":4,"has_more":false,"changes":[]}"""))
        ApiSyncClient(ApplicationProvider.getApplicationContext(),database.dao(),allowInsecureHttp=true).sync(settings())
        val note=database.dao().get("page")
        assertEquals("迁移完成",note?.title);assertEquals("来自网页的正文",note?.body);assertFalse(note!!.dirty)
        assertEquals("book",database.dao().getFolder("section")?.notebookId)
        assertEquals(4L,database.dao().apiCursor(workspace))
        val client=ApiSyncClient(ApplicationProvider.getApplicationContext(),database.dao(),allowInsecureHttp=true)
        client.queueNote(workspace,note.copy(title="仅修改标题",dirty=true))
        val pagePayload=JsonParser.parseString(database.dao().apiOutbox(workspace).first{it.entityType=="page"}.payloadJson).asJsonObject
        assertTrue(pagePayload["favorite"].asBoolean);assertTrue(pagePayload["locked"].asBoolean);assertEquals("kept",pagePayload["legacyMetadata"].asJsonObject["future"].asString)
        assertEquals("document",pagePayload["kind"].asString)
        assertTrue(database.dao().apiOutbox(workspace).first{it.entityType=="document"}.payloadJson.contains("来自网页的正文"))
    }

    @Test fun `dirty android page is pushed with page and document operations`()=runBlocking {
        database.dao().put(NoteEntity(id="phone-page",title="手机修改",body="# 正文",dirty=true))
        // Operation ids are generated, so return the ids extracted from the request
        // after inspecting the pushed body.
        var pushedTypes=emptySet<String>()
        server.dispatcher=object:okhttp3.mockwebserver.Dispatcher(){override fun dispatch(request:okhttp3.mockwebserver.RecordedRequest):MockResponse{
            if(request.path?.startsWith("/v1/sync/pull")==true)return json("""{"cursor":2,"has_more":false,"changes":[]}""")
            val root=JsonParser.parseString(request.body.clone().readUtf8()).asJsonObject;pushedTypes=root["changes"].asJsonArray.map{it.asJsonObject["entity_type"].asString}.toSet();val applied=root["changes"].asJsonArray.map{raw->val c=raw.asJsonObject;"""{"operation_id":"${c["operation_id"].asString}","entity_type":"${c["entity_type"].asString}","entity_id":"${c["entity_id"].asString}","version":1,"cursor":1}"""}
            return json("""{"applied":[${applied.joinToString(",")}],"conflicts":[]}""")
        }}
        ApiSyncClient(ApplicationProvider.getApplicationContext(),database.dao(),allowInsecureHttp=true).sync(settings())
        assertEquals(setOf("page","document"),pushedTypes)
        assertTrue(database.dao().apiOutbox(workspace).isEmpty())
    }

    @Test fun `first pull never overwrites an unsynced legacy android edit`()=runBlocking {
        database.dao().put(NoteEntity(id="same",title="手机标题",body="手机未同步正文",dirty=true))
        server.enqueue(json("""{"cursor":2,"has_more":false,"changes":[
          {"cursor":1,"entity_type":"page","entity_id":"same","version":3,"operation":"upsert","payload":{"id":"same","workspaceId":"workspace-a","kind":"document","title":"网页标题","preview":"网页","favorite":false,"syncStatus":"synced","createdAt":"2026-01-01T00:00:00Z","updatedAt":"2026-01-01T00:00:00Z"}},
          {"cursor":2,"entity_type":"document","entity_id":"same","version":4,"operation":"upsert","payload":{"pageId":"same","schemaVersion":1,"tiptapJson":{"type":"doc","content":[{"type":"paragraph","content":[{"type":"text","text":"网页正文"}]}]},"updatedAt":"2026-01-01T00:00:00Z"}}
        ]}"""))
        server.enqueue(json("""{"cursor":2,"has_more":false,"changes":[]}"""))
        ApiSyncClient(ApplicationProvider.getApplicationContext(),database.dao(),allowInsecureHttp=true).sync(settings())
        val note=database.dao().get("same")!!
        assertEquals("手机标题",note.title);assertEquals("手机未同步正文",note.body);assertTrue(note.dirty);assertTrue(note.conflict)
        val conflicts=JsonParser.parseString(note.conflictSnapshotJson).asJsonObject["apiConflicts"].asJsonObject
        assertTrue(conflicts.has("page"));assertTrue(conflicts.has("document"));assertTrue(database.dao().apiOutbox(workspace).isEmpty())
    }

    private fun settings()=ApiSyncSettings(server.url("/").toString().trimEnd('/'),workspace,"")
    private fun json(body:String)=MockResponse().setResponseCode(200).setHeader("Content-Type","application/json").setBody(body)
}
