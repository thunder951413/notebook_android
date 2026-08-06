package io.github.notebook.android.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.github.notebook.android.data.FolderEntity
import io.github.notebook.android.data.NoteEntity
import io.github.notebook.android.data.NotebookDatabase
import io.github.notebook.android.data.TagEntity
import io.github.notebook.android.data.TodoStepEntity
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.UUID

/** In-memory WebDAV endpoint for the transport and engine tests. */
private class FakeWebdavServer(private val server: MockWebServer) {
    val files = mutableMapOf<String, ByteArray>()
    private val dirs = mutableSetOf<String>()
    val journalPuts = mutableListOf<String>()

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val raw = request.path.orEmpty().substringBefore('?')
                val path = raw.removePrefix("/dav/")
                when (request.method) {
                    "MKCOL" -> {
                        if (dirs.contains(path) || files.containsKey(path) || dirs.any { it.startsWith("$path/") } || files.keys.any { it.startsWith("$path/") }) {
                            return MockResponse().setResponseCode(405)
                        }
                        dirs.add(path)
                        return MockResponse().setResponseCode(201)
                    }
                    "PUT" -> {
                        if (path.endsWith(".jsonl") && path.contains("/journal/")) journalPuts.add(path)
                        files[path] = request.body.readByteArray()
                        return MockResponse().setResponseCode(201)
                    }
                    "GET" -> {
                        val data = files[path] ?: return MockResponse().setResponseCode(404)
                        return MockResponse().setResponseCode(200).setBody(okio.Buffer().apply { write(data) })
                    }
                    "PROPFIND" -> {
                        val depth = request.headers["Depth"] ?: "1"
                        val exists = dirs.contains(path) || files.containsKey(path) || dirs.any { it.startsWith("$path/") }
                        if (!exists) return MockResponse().setResponseCode(404)
                        val children = mutableListOf<String>()
                        if (depth == "0") {
                            if (dirs.contains(path) || files.containsKey(path)) children.add(path)
                        } else {
                            dirs.filter { it != path && it.startsWith("$path/") && !it.removePrefix("$path/").contains('/') }
                                .forEach { children.add("$it/") }
                            files.keys.filter { it != path && it.startsWith("$path/") && !it.removePrefix("$path/").contains('/') }
                                .forEach { children.add(it) }
                        }
                        val responses = children.joinToString("\n") { href ->
                            val isDir = dirs.contains(href.trimEnd('/'))
                            "  <d:response><d:href>$href</d:href><d:propstat><d:prop>${if (isDir) "<d:resourcetype><d:collection/></d:resourcetype>" else "<d:resourcetype/>"}<d:displayname>${href.substringAfterLast('/')}</d:displayname></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>"
                        }
                        val body = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<d:multistatus xmlns:d=\"DAV:\">\n$responses\n</d:multistatus>"
                        return MockResponse().setResponseCode(207).setHeader("Content-Type", "application/xml").setBody(body)
                    }
                    "OPTIONS" -> return MockResponse().setResponseCode(200).setHeader("DAV", "1")
                    else -> return MockResponse().setResponseCode(405)
                }
            }
        }
    }

    fun journalText(deviceId: String): String? {
        val bytes = files["notebook_backup/journal/$deviceId.jsonl"] ?: return null
        return String(bytes, StandardCharsets.UTF_8)
    }
    fun putJournal(deviceId: String, lines: List<String>) {
        files["notebook_backup/journal/$deviceId.jsonl"] = (lines.joinToString("\n") + "\n").toByteArray(StandardCharsets.UTF_8)
        dirs.add("notebook_backup/journal")
    }
    fun putObject(hash: String, data: ByteArray) {
        files["notebook_backup/objects/${hash.take(2)}/$hash"] = data
        dirs.add("notebook_backup/objects/${hash.take(2)}")
    }
}

@RunWith(RobolectricTestRunner::class)
class WebdavSyncTest {
    private lateinit var database: NotebookDatabase
    private lateinit var server: MockWebServer
    private lateinit var webdav: FakeWebdavServer
    private lateinit var prefs: android.content.SharedPreferences
    private lateinit var client: WebdavSyncClient
    private lateinit var api: ApiSyncClient
    private val deviceId = "00000000-0000-4000-8000-000000000001"
    private val noteId = "20000000-0000-4000-8000-000000000001"

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), NotebookDatabase::class.java).allowMainThreadQueries().build()
        server = MockWebServer().apply { start() }
        webdav = FakeWebdavServer(server)
        prefs = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("webdav-test-${UUID.randomUUID()}", android.content.Context.MODE_PRIVATE)
        prefs.edit().putString("device", deviceId).apply()
        api = ApiSyncClient(ApplicationProvider.getApplicationContext(), database.dao(), allowInsecureHttp = true)
        client = WebdavSyncClient(ApplicationProvider.getApplicationContext(), database.dao(), prefs, api)
    }

    @After
    fun tearDown() {
        database.close()
        server.shutdown()
    }

    private fun settings() = WebdavSettings(
        baseUrl = "http://127.0.0.1:${server.port}/dav/",
        username = "user",
        appPassword = "app-password",
        remotePath = "notebook_backup",
    )

    private fun note(title: String = "本地笔记", body: String = "# 标题\n- [x] 完成") = NoteEntity(
        id = noteId, title = title, body = body, previewText = body.take(30),
        createdAt = System.currentTimeMillis(), updatedAt = System.currentTimeMillis(),
        folderId = null, folderName = "未分类", version = 0, dirty = true,
    )

    @Test
    fun `first sync publishes the full local library as version-0 journal entries`() = runBlocking {
        database.dao().putFolder(FolderEntity("folder-1", "收集箱", 0, "noteFolder", System.currentTimeMillis()))
        database.dao().putTag(TagEntity("tag-1", "重要", "red", System.currentTimeMillis()))
        database.dao().put(note())
        database.dao().putStep(TodoStepEntity("step-1", noteId, "买牛奶", false, 0, System.currentTimeMillis()))

        val result = runCatching { client.sync(settings()) }

        assertTrue("sync failed: ${result.exceptionOrNull()}", result.isSuccess)
        val journal = webdav.journalText(deviceId) ?: error("journal not published")
        assertTrue(journal.contains("\"type\":\"page\""))
        assertTrue(journal.contains("\"type\":\"document\""))
        assertTrue(journal.contains("\"type\":\"section\""))
        assertTrue(journal.contains("\"type\":\"tag\""))
        assertTrue(journal.contains("\"type\":\"task_step\""))
        // Every first-publication entry starts at version 0.
        journal.split('\n').filter { it.isNotBlank() }.forEach { line ->
            val root = JsonParser.parseString(line).asJsonObject
            assertEquals(0L, root["ver"].asLong)
            assertTrue("missing seq on $line", root["seq"].asLong > 0)
        }
        // Entries were acknowledged and the device is clean.
        assertTrue(database.dao().apiOutbox(WebdavJournalProtocol.DEFAULT_WORKSPACE_ID).isEmpty())
        assertFalse(database.dao().get(noteId)!!.dirty)
    }

    @Test
    fun `pull applies remote pages and externalized document objects`() = runBlocking {
        val remoteDevice = "30000000-0000-4000-8000-000000000002"
        val body = "来自远端的正文"
        val tiptap = JsonObject().apply {
            addProperty("type", "doc")
            add("content", com.google.gson.JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("type", "paragraph")
                    add("content", com.google.gson.JsonArray().apply {
                        add(JsonObject().apply { addProperty("type", "text"); addProperty("text", body) })
                    })
                })
            })
        }
        val objectHash = WebdavJournalProtocol.sha256(tiptap.toString().toByteArray(StandardCharsets.UTF_8))
        webdav.putObject(objectHash, tiptap.toString().toByteArray(StandardCharsets.UTF_8))
        webdav.putJournal(remoteDevice, listOf(
            """{"seq":1,"ts":"2026-08-04T00:00:00Z","op":"upsert","type":"page","id":"$noteId","ver":0,"payload":{"id":"$noteId","workspaceId":"00000000-0000-4000-8000-000000000001","kind":"document","title":"远端笔记","preview":"正文","favorite":false,"syncStatus":"synced","createdAt":"2026-08-04T00:00:00Z","updatedAt":"2026-08-04T00:00:00Z"}}""",
            """{"seq":2,"ts":"2026-08-04T00:00:01Z","op":"upsert","type":"document","id":"$noteId","ver":0,"hash":"$objectHash","payload":{"pageId":"$noteId","schemaVersion":1,"tiptapJson":{"${'$'}object":"$objectHash"},"updatedAt":"2026-08-04T00:00:01Z"}}""",
        ))

        client.sync(settings())

        val note = database.dao().get(noteId) ?: error("remote note not applied")
        assertEquals("远端笔记", note.title)
        assertEquals(body, note.body)
        assertFalse(note.dirty)
        // The same entries are not re-applied on the next sync.
        val updatedAt = note.updatedAt
        client.sync(settings())
        assertEquals(updatedAt, database.dao().get(noteId)!!.updatedAt)
    }

    @Test
    fun `a conflicting remote edit marks the page and stays out of the push`() = runBlocking {
        database.dao().put(note(title = "本地修改"))
        client.sync(settings()) // initial publish at ver 0
        val remoteDevice = "30000000-0000-4000-8000-000000000002"
        webdav.putJournal(remoteDevice, listOf(
            """{"seq":1,"ts":"2026-08-04T00:00:00Z","op":"upsert","type":"page","id":"$noteId","ver":1,"payload":{"id":"$noteId","workspaceId":"00000000-0000-4000-8000-000000000001","kind":"document","title":"远端改写","preview":"","favorite":false,"syncStatus":"synced","createdAt":"2026-08-04T00:00:00Z","updatedAt":"2026-08-04T00:00:00Z"}}""",
        ))
        // Local edit after the initial publish, queued through the shared outbox.
        val edited = database.dao().get(noteId)!!.copy(title = "本地改写", dirty = true)
        database.dao().put(edited)
        api.queueNote(WebdavJournalProtocol.DEFAULT_WORKSPACE_ID, edited)

        client.sync(settings())

        assertTrue(database.dao().get(noteId)!!.conflict)
        // The conflicted entity was not published into this device's journal.
        val journal = webdav.journalText(deviceId)!!
        assertFalse(journal.contains("本地改写"))
        assertTrue(database.dao().apiOutbox(WebdavJournalProtocol.DEFAULT_WORKSPACE_ID).isNotEmpty())
    }

    @Test
    fun `large document bodies are externalized into content-addressed objects`() = runBlocking {
        val bigBody = "# 长文\n\n" + "x".repeat(3000)
        database.dao().put(note(body = bigBody))
        client.sync(settings())
        val journal = webdav.journalText(deviceId)!!
        val line = journal.split('\n').first { it.contains("\"type\":\"document\"") }
        val root = JsonParser.parseString(line).asJsonObject
        val marker = root["payload"].asJsonObject["tiptapJson"].asJsonObject
        assertEquals(WebdavJournalProtocol.OBJECT_MARKER, marker.entrySet().single().key)
        val hash = marker.entrySet().single().value.asString
        assertTrue(webdav.files.containsKey("notebook_backup/objects/${hash.take(2)}/$hash"))
        // The object holds the original TipTap JSON.
        val stored = webdav.files["notebook_backup/objects/${hash.take(2)}/$hash"]!!.toString(StandardCharsets.UTF_8)
        assertTrue(stored.contains("x".repeat(3000)))
        assertTrue(stored.contains("长文"))
    }

    @Test
    fun `asset bytes round trip through objects with sha256 verification`() = runBlocking {
        val assetId = "40000000-0000-4000-8000-000000000001"
        val context = ApplicationProvider.getApplicationContext<Context>()
        val assetFile = File(context.filesDir, "attachments/$noteId/$assetId-a.png").apply {
            parentFile?.mkdirs()
            writeBytes(ByteArray(4096) { (it % 251).toByte() })
        }
        val hash = assetFile.inputStream().use { WebdavJournalProtocol.sha256(it) }
        database.dao().put(note())
        database.dao().putAssets(listOf(io.github.notebook.android.data.AssetEntity(
            assetId, noteId, "image", "a.png", "image/png", "$noteId/$assetId-a.png", assetFile.absolutePath, hash, 4096, true,
        )))

        client.sync(settings())

        val journal = webdav.journalText(deviceId)!!
        val line = journal.split('\n').first { it.contains("\"type\":\"asset\"") }
        val root = JsonParser.parseString(line).asJsonObject
        assertEquals(hash, root["hash"].asString)
        assertEquals(hash, root["payload"].asJsonObject["objectHash"].asString)
        assertTrue(webdav.files.containsKey("notebook_backup/objects/${hash.take(2)}/$hash"))

        // Now wipe the local asset bytes and restore them from the remote object.
        val otherDevice = "30000000-0000-4000-8000-000000000002"
        webdav.putJournal(otherDevice, listOf(
            """{"seq":1,"ts":"2026-08-04T00:00:00Z","op":"upsert","type":"asset","id":"$assetId","ver":0,"hash":"$hash","payload":{"id":"$assetId","pageId":"$noteId","kind":"image","filename":"a.png","mimeType":"image/png","byteSize":4096,"objectHash":"$hash","createdAt":"2026-08-04T00:00:00Z"}}""",
        ))
        database.dao().deleteAsset(assetId)
        assetFile.delete()

        client.sync(settings())

        val restored = database.dao().getAsset(assetId) ?: error("asset not restored")
        val restoredFile = File(restored.localPath!!)
        assertTrue(restoredFile.isFile)
        assertEquals(4096, restoredFile.length())
        assertEquals(hash, restored.contentHash)
    }
}
