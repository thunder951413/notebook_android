package io.github.notebook.android.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.github.notebook.android.data.ApiSyncOutboxEntity
import io.github.notebook.android.data.AssetEntity
import io.github.notebook.android.data.FolderEntity
import io.github.notebook.android.data.NoteEntity
import io.github.notebook.android.data.NotebookDatabase
import io.github.notebook.android.data.ReadingPositionEntity
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
    val objectPuts = mutableListOf<String>()
    val gets = mutableListOf<String>()

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
                        if (path.contains("/objects/")) objectPuts.add(path)
                        files[path] = request.body.readByteArray()
                        return MockResponse().setResponseCode(201)
                    }
                    "GET" -> {
                        gets.add(path)
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
    fun putHead(deviceId: String, journalBytes: Int, journalSha256: String, lastSeq: Long) {
        val head = """{"version":1,"deviceId":"$deviceId","lastSeq":$lastSeq,"journalBytes":$journalBytes,"journalSha256":"$journalSha256","updatedAt":"2026-08-04T00:00:00Z","stateEntries":1,"historyEntries":0}"""
        files["notebook_backup/journal/$deviceId.head.json"] = head.toByteArray(StandardCharsets.UTF_8)
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
        client = WebdavSyncClient(ApplicationProvider.getApplicationContext(), database.dao(), prefs, api, allowInsecureHttp = true)
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
            """{"seq":2,"ts":"2026-08-04T00:00:01Z","op":"upsert","type":"tag","id":"after-conflict","ver":0,"payload":{"id":"after-conflict","name":"不能越过冲突","color":"red","updatedAt":"2026-08-04T00:00:01Z"}}""",
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
        assertNull(database.dao().getTag("after-conflict"))
        assertFalse(prefs.getString("webdav-seqs:${client.fingerprint(settings())}", "{}").orEmpty().contains(remoteDevice))
    }

    @Test
    fun `large document bodies are externalized into content-addressed objects`() = runBlocking {
        val bigBody = "# 长文\n\n" + "x".repeat(3000)
        database.dao().put(note(body = bigBody))
        client.sync(settings())
        val journal = webdav.journalText(deviceId)!!
        val line = journal.split('\n').first { it.contains("\"type\":\"document\"") }
        val root = JsonParser.parseString(line).asJsonObject
        val marker = root["payload"].asJsonObject
        assertEquals(WebdavJournalProtocol.PAYLOAD_OBJECT_MARKER, marker.entrySet().single().key)
        val hash = marker.entrySet().single().value.asString
        assertTrue(webdav.files.containsKey("notebook_backup/objects/${hash.take(2)}/$hash"))
        // The object holds the complete document payload, including Markdown.
        val stored = webdav.files["notebook_backup/objects/${hash.take(2)}/$hash"]!!.toString(StandardCharsets.UTF_8)
        assertTrue(stored.contains("x".repeat(3000)))
        assertTrue(stored.contains("长文"))
    }

    @Test
    fun `pull materializes the full document payload marker`() = runBlocking {
        database.dao().put(note(body = "旧正文").copy(dirty = false))
        val remoteDevice = "30000000-0000-4000-8000-000000000004"
        val payload = JsonObject().apply {
            addProperty("pageId", noteId)
            addProperty("schemaVersion", 1)
            add("tiptapJson", TipTapCodec.encode("来自完整对象的新正文"))
            addProperty("updatedAt", "2026-08-04T00:00:01Z")
        }
        val bytes = payload.toString().toByteArray(StandardCharsets.UTF_8)
        val hash = WebdavJournalProtocol.sha256(bytes)
        webdav.putObject(hash, bytes)
        webdav.putJournal(remoteDevice, listOf(
            """{"seq":1,"ts":"2026-08-04T00:00:01Z","op":"upsert","type":"document","id":"$noteId","ver":0,"hash":"$hash","payload":{"${'$'}payloadObject":"$hash"}}""",
        ))

        client.sync(settings())

        assertEquals("来自完整对象的新正文", database.dao().get(noteId)!!.body)
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

    @Test
    fun `queued asset with missing local bytes recovers the verified remote object`() = runBlocking {
        val bytes = ByteArray(4096) { (it % 251).toByte() }
        val asset = missingAsset(bytes)
        webdav.putObject(asset.contentHash, bytes)
        val progress = mutableListOf<String>()

        client.sync(settings(), progress::add)

        val restored = database.dao().getAsset(asset.id)!!
        assertArrayEquals(bytes, File(restored.localPath!!).readBytes())
        assertTrue(progress.any { it.contains("恢复附件") })
        assertEquals(asset.caption, restored.caption)
        assertEquals(asset.width, restored.width)
        assertFalse(restored.dirty)
        assertNull(database.dao().apiOutboxItem("asset", asset.id))
        assertTrue(webdav.objectPuts.isEmpty())
        assertEquals(1, webdav.gets.count { it.endsWith(asset.contentHash) })
        assertTrue(webdav.journalText(deviceId)!!.contains(asset.contentHash))
    }

    @Test
    fun `stale absolute asset path is repaired from the app attachment directory`() = runBlocking {
        val bytes = "existing local attachment".toByteArray()
        val asset = missingAsset(bytes)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = AttachmentStorageContract.file(context, asset.relativePath).apply {
            parentFile?.mkdirs()
            writeBytes(bytes)
        }

        client.sync(settings())

        assertEquals(file.absolutePath, database.dao().getAsset(asset.id)!!.localPath)
        assertArrayEquals(bytes, webdav.files.getValue("notebook_backup/objects/${asset.contentHash.take(2)}/${asset.contentHash}"))
    }

    @Test
    fun `missing cloud and local attachment keeps edits queued until bytes are restored`() = runBlocking {
        val bytes = "recover later".toByteArray()
        val asset = missingAsset(bytes)

        val failure = runCatching { client.sync(settings()) }.exceptionOrNull()

        assertTrue(failure is MissingAttachmentException)
        assertTrue(failure!!.message.orEmpty().contains(asset.filename))
        assertTrue(database.dao().get(noteId)!!.dirty)
        assertTrue(database.dao().getAsset(asset.id)!!.dirty)
        assertNotNull(database.dao().apiOutboxItem("asset", asset.id))
        assertTrue(webdav.journalPuts.isEmpty())
        webdav.putObject(asset.contentHash, bytes)

        client.sync(settings())

        assertFalse(database.dao().get(noteId)!!.dirty)
        assertFalse(database.dao().getAsset(asset.id)!!.dirty)
    }

    @Test
    fun `corrupt recovery object is rejected without publishing or clearing dirty state`() = runBlocking {
        val asset = missingAsset("expected bytes".toByteArray())
        webdav.putObject(asset.contentHash, "corrupt".toByteArray())

        val failure = runCatching { client.sync(settings()) }.exceptionOrNull()

        assertTrue(failure?.message.orEmpty().contains("SHA-256"))
        assertTrue(database.dao().getAsset(asset.id)!!.dirty)
        assertNotNull(database.dao().apiOutboxItem("asset", asset.id))
        assertTrue(webdav.journalPuts.isEmpty())
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertFalse(AttachmentStorageContract.file(context, asset.relativePath).exists())
    }

    @Test
    fun `invalid directory XML fails before an empty repository can be seeded`() = runBlocking {
        database.dao().put(note())
        val invalidBodies = listOf(
            "", "<html>not WebDAV</html>", "<d:multistatus xmlns:d=\"DAV:\"><d:response>",
            """<!DOCTYPE multistatus [<!ENTITY external SYSTEM "file:///etc/passwd">]><multistatus xmlns="DAV:"><response><href>&external;</href></response></multistatus>""",
        )
        for (body in invalidBodies) {
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) =
                    if (request.method == "MKCOL") MockResponse().setResponseCode(405)
                    else MockResponse().setResponseCode(207).setBody(body)
            }

            val failure = runCatching { client.sync(settings()) }.exceptionOrNull()

            assertTrue("invalid directory accepted: $body", failure is java.io.IOException)
            assertEquals("无法解析 WebDAV 目录，请稍后重试", failure?.message)
            assertTrue(database.dao().get(noteId)!!.dirty)
            assertEquals(0, database.dao().apiOutboxCount(WebdavJournalProtocol.DEFAULT_WORKSPACE_ID))
        }
    }

    private suspend fun missingAsset(bytes: ByteArray): AssetEntity {
        val id = UUID.randomUUID().toString()
        val asset = AssetEntity(
            id, noteId, "image", "image.png", "image/png", "$noteId/$id-image.png",
            "/missing/$id-image.png", WebdavJournalProtocol.sha256(bytes), bytes.size.toLong(), true,
            width = 320, caption = "keep attachment metadata",
        )
        database.dao().put(note())
        database.dao().putAssets(listOf(asset))
        return asset
    }

    @Test
    fun `concurrent journal parent version records conflict without advancing cursor`() = runBlocking {
        database.dao().put(note().copy(dirty = false))
        database.dao().putApiVersion(io.github.notebook.android.data.ApiSyncVersionEntity(
            api.versionKey(WebdavJournalProtocol.DEFAULT_WORKSPACE_ID, "page", noteId), 2,
        ))
        val remoteDevice = "30000000-0000-4000-8000-000000000009"
        webdav.putJournal(remoteDevice, listOf(
            """{"seq":1,"ts":"2026-08-04T00:00:00Z","op":"upsert","type":"page","id":"$noteId","ver":1,"payload":{"id":"$noteId","workspaceId":"00000000-0000-4000-8000-000000000001","kind":"document","title":"远端分支","preview":"不同内容","favorite":false,"syncStatus":"synced","createdAt":"2026-08-04T00:00:00Z","updatedAt":"2026-08-04T00:00:00Z"}}""",
        ))

        client.sync(settings())

        assertTrue(database.dao().get(noteId)!!.conflict)
        assertNull(database.dao().apiCursor(WebdavJournalProtocol.DEFAULT_WORKSPACE_ID))
        assertEquals(2, database.dao().apiVersion(api.versionKey(WebdavJournalProtocol.DEFAULT_WORKSPACE_ID, "page", noteId))!!.version)
    }

    @Test
    fun `second v0 journal sibling conflicts while identical replay is consumed`() = runBlocking {
        val remoteDevice = "30000000-0000-4000-8000-000000000008"
        val first = """{"seq":1,"ts":"2026-08-04T00:00:00Z","op":"upsert","type":"page","id":"$noteId","ver":0,"payload":{"id":"$noteId","workspaceId":"00000000-0000-4000-8000-000000000001","kind":"document","title":"共同初始","preview":"相同","favorite":false,"syncStatus":"synced","createdAt":"2026-08-04T00:00:00Z","updatedAt":"2026-08-04T00:00:00Z"}}"""
        webdav.putJournal(remoteDevice, listOf(first))
        client.sync(settings())
        assertEquals(1, database.dao().apiVersion(api.versionKey(WebdavJournalProtocol.DEFAULT_WORKSPACE_ID,"page",noteId))!!.version)

        // A second identical v0 copy is a retry and can advance the device cursor.
        webdav.putJournal(remoteDevice, listOf(first.replace("\"seq\":1","\"seq\":2")))
        client.sync(settings())
        assertFalse(database.dao().get(noteId)!!.conflict)

        webdav.putJournal(remoteDevice, listOf(
            """{"seq":3,"ts":"2026-08-04T00:00:03Z","op":"upsert","type":"page","id":"$noteId","ver":0,"payload":{"id":"$noteId","workspaceId":"00000000-0000-4000-8000-000000000001","kind":"document","title":"并发分支","preview":"不同","favorite":false,"syncStatus":"synced","createdAt":"2026-08-04T00:00:00Z","updatedAt":"2026-08-04T00:00:03Z"}}""",
        ))
        client.sync(settings())
        assertTrue(database.dao().get(noteId)!!.conflict)
    }

    @Test
    fun `oversized journal is compacted on push without changing seq semantics`() {
        runBlocking {
        database.dao().put(note())
        // Seed an oversized journal for this device: 8000 updates of one entity.
        val big = (1..8000).joinToString("\n") { index ->
            """{"seq":$index,"ts":"2026-08-04T00:00:00Z","op":"upsert","type":"page","id":"page-x","ver":${index - 1},"payload":{"id":"page-x","title":"r$index","body":"${"x".repeat(500)}"}}"""
        } + "\n"
        assertTrue("seeded journal must exceed the 4 MB limit", big.toByteArray().size > 4 * 1024 * 1024)
        webdav.files["notebook_backup/journal/$deviceId.jsonl"] = big.toByteArray()
        // Mark everything already consumed so the pull does not replay 8000 rows.
        prefs.edit().putString("webdav-seqs:${client.fingerprint(settings())}", """{"$deviceId":8000}""").apply()

        client.sync(settings())

        val stored = webdav.files["notebook_backup/journal/$deviceId.jsonl"] ?: error("journal missing")
        assertTrue("journal was not compacted", stored.size < 4 * 1024 * 1024)
        val parsed = String(stored, Charsets.UTF_8).trim().split('\n').map { JsonParser.parseString(it).asJsonObject }
        // The compacted entity keeps only its latest record with the original seq.
        val pageX = parsed.filter { it["id"].asString == "page-x" }
        assertEquals(1, pageX.size)
        assertEquals(8000L, pageX.single()["seq"].asLong)
        // New entries keep monotonic seqs beyond the pre-compaction tail.
        val ownNote = parsed.filter { it["id"].asString == noteId }
        assertTrue(ownNote.isNotEmpty())
        assertTrue(ownNote.all { it["seq"].asLong > 8000 })
        // Leave the shared fixture clean for the tests that follow.
        webdav.files.remove("notebook_backup/journal/$deviceId.jsonl")
        }
    }

    @Test
    fun `push keeps malformed outbox rows while acknowledging published rows`() = runBlocking {
        val workspace = WebdavJournalProtocol.DEFAULT_WORKSPACE_ID
        database.dao().putApiOutbox(ApiSyncOutboxEntity(
            id = "malformed-row", workspaceId = workspace, entityType = "reading_position", entityId = "bad-row",
            expectedVersion = 0, operation = "upsert", payloadJson = "not json", createdAt = 1,
        ))
        database.dao().putApiOutbox(ApiSyncOutboxEntity(
            id = "published-row", workspaceId = workspace, entityType = "reading_position", entityId = "good-row",
            expectedVersion = 0, operation = "upsert",
            payloadJson = """{"pageId":"$noteId","anchorUtf16Offset":0,"viewportOffsetFraction":0,"updatedAt":"2026-08-04T00:00:00Z","deviceId":"$deviceId"}""",
            createdAt = 2,
        ))

        client.sync(settings())

        val remaining = database.dao().apiOutbox(workspace)
        assertEquals(listOf("malformed-row"), remaining.map { it.id })
        assertTrue(webdav.journalText(deviceId)!!.contains("good-row"))
    }

    @Test
    fun `unchanged head skips journal downloads`() = runBlocking {
        database.dao().put(note())
        client.sync(settings())
        assertTrue(webdav.files.containsKey("notebook_backup/journal/$deviceId.head.json"))
        webdav.gets.clear()

        client.sync(settings())

        assertFalse(webdav.gets.any { it.endsWith(".jsonl") })
        assertTrue(webdav.gets.any { it.endsWith(".head.json") })
        assertTrue(database.dao().apiOutbox(WebdavJournalProtocol.DEFAULT_WORKSPACE_ID).isEmpty())
    }

    @Test
    fun `head checksum mismatch stops the pull before applying data`() = runBlocking {
        val remoteDevice = "30000000-0000-4000-8000-000000000002"
        val line = """{"seq":1,"ts":"2026-08-04T00:00:00Z","op":"upsert","type":"tag","id":"tag-x","ver":0,"payload":{"id":"tag-x","name":"bad","color":"red","updatedAt":"2026-08-04T00:00:00Z"}}"""
        webdav.putJournal(remoteDevice, listOf(line))
        webdav.putHead(remoteDevice, line.toByteArray(StandardCharsets.UTF_8).size + 1, "0".repeat(64), 1)

        val result = runCatching { client.sync(settings()) }

        assertTrue(result.exceptionOrNull()?.message?.contains("校验失败") == true)
        assertNull(database.dao().getTag("tag-x"))
    }

    @Test
    fun `existing content addressed object is not uploaded twice`() = runBlocking {
        val body = "x".repeat(3000)
        database.dao().put(note(body = body))
        client.sync(settings())
        val uploaded = webdav.objectPuts.size
        val edited = database.dao().get(noteId)!!.copy(body = body, dirty = true)
        database.dao().put(edited)
        api.queueNote(WebdavJournalProtocol.DEFAULT_WORKSPACE_ID, edited)

        client.sync(settings())

        assertEquals(uploaded, webdav.objectPuts.size)
    }

    @Test
    fun `corrupt existing content addressed object is repaired before journal publication`() = runBlocking {
        val body = "repair-me-" + "x".repeat(3000)
        database.dao().put(note(body = body))
        client.sync(settings())
        val objectPath = webdav.files.keys.single { it.contains("/objects/") }
        val expected = webdav.files.getValue(objectPath)
        val uploaded = webdav.objectPuts.size
        webdav.files[objectPath] = "corrupt".toByteArray()
        val edited = database.dao().get(noteId)!!.copy(body = body, dirty = true)
        database.dao().put(edited)
        api.queueNote(WebdavJournalProtocol.DEFAULT_WORKSPACE_ID, edited)

        client.sync(settings())

        assertArrayEquals(expected, webdav.files.getValue(objectPath))
        assertEquals(uploaded + 1, webdav.objectPuts.size)
    }

    @Test
    fun `private note and every related entity remain local only`() = runBlocking {
        val workspace = WebdavJournalProtocol.DEFAULT_WORKSPACE_ID
        val folderId = "50000000-0000-4000-8000-000000000001"
        val stepId = "60000000-0000-4000-8000-000000000001"
        val assetId = "70000000-0000-4000-8000-000000000001"
        val secret = "PRIVATE-PLAINTEXT-MUST-NOT-LEAVE"
        val folder = FolderEntity(folderId, "$secret-folder", 0, "encryptedFolder")
        val privateNote = note(title = "$secret-title", body = "$secret-body").copy(folderId = folderId, folderName = "$secret-folder")
        val step = TodoStepEntity(stepId, noteId, "$secret-step")
        val bytes = "$secret-asset".toByteArray()
        val file = File(ApplicationProvider.getApplicationContext<Context>().filesDir, "attachments/$noteId/private.bin").apply {
            parentFile?.mkdirs(); writeBytes(bytes)
        }
        val asset = AssetEntity(assetId, noteId, "file", "private.bin", "application/octet-stream", "$noteId/private.bin", file.absolutePath, WebdavJournalProtocol.sha256(bytes), bytes.size.toLong(), true)
        val position = ReadingPositionEntity(noteId, 11, 0.25, System.currentTimeMillis(), deviceId)
        database.dao().putFolder(folder)
        database.dao().put(privateNote)
        database.dao().putStep(step)
        database.dao().putAssets(listOf(asset))
        database.dao().putReadingPosition(position)
        prefs.edit().putString("encryptedNoteIds", "[\"$noteId\"]").putString("encryptedMappings", "{\"$noteId\":\"$folderId\"}").apply()
        api.queueFolder(workspace, folder)
        api.queueNote(workspace, privateNote)
        api.queueStep(workspace, step)
        api.queueAsset(workspace, asset)
        api.queueReadingPosition(workspace, position)

        client.sync(settings())

        val remote = webdav.files.values.joinToString("\n") { String(it, Charsets.UTF_8) }
        assertFalse(remote.contains(secret))
        assertFalse(remote.contains(noteId))
        assertFalse(remote.contains(stepId))
        assertFalse(remote.contains(assetId))
        assertTrue(database.dao().apiOutbox(workspace).isEmpty())
        assertFalse(database.dao().get(noteId)!!.dirty)
        assertFalse(database.dao().getAsset(assetId)!!.dirty)
    }

    @Test
    fun `release transport rejects plaintext http`() {
        val error = runCatching {
            WebdavClient("http://127.0.0.1/dav/", "user", "password", "notebook_backup", allowInsecureHttp = false)
        }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        assertTrue(error?.message?.contains("HTTPS") == true)
    }

    @Test
    fun `redirect is rejected without forwarding basic credentials`() {
        val redirect = MockWebServer().apply { start() }
        val target = MockWebServer().apply { start() }
        try {
            redirect.enqueue(MockResponse().setResponseCode(302).setHeader("Location", target.url("/capture")))
            val redirected = WebdavClient(redirect.url("/dav/").toString(), "user", "password", "notebook_backup", allowInsecureHttp = true)

            val error = runCatching { redirected.ensureDirectory("notebook_backup") }.exceptionOrNull()

            assertTrue(error?.message?.contains("重定向") == true)
            assertEquals(0, target.requestCount)
        } finally {
            redirect.shutdown(); target.shutdown()
        }
    }

    @Test
    fun `server response body cannot echo secrets into sync errors`() {
        val hostile = MockWebServer().apply { start() }
        val marker = "PASSWORD-MUST-NOT-APPEAR"
        try {
            hostile.enqueue(MockResponse().setResponseCode(500).setBody(marker))
            val remote = WebdavClient(hostile.url("/dav/").toString(), "user", "password", "notebook_backup", allowInsecureHttp = true)

            val error = runCatching { remote.getText("notebook_backup/journal/missing.jsonl") }.exceptionOrNull()

            assertNotNull(error)
            assertFalse(error!!.message.orEmpty().contains(marker))
        } finally {
            hostile.shutdown()
        }
    }

    @Test
    fun `compaction keeps latest state tombstone and bounded revision history`() = runBlocking {
        val oldJournal = listOf(
            """{"seq":1,"ts":"2026-08-04T00:00:00Z","op":"upsert","type":"page","id":"gone","ver":0,"payload":{}}""",
            """{"seq":2,"ts":"2026-08-04T00:00:01Z","op":"delete","type":"page","id":"gone","ver":1,"payload":{}}""",
            """{"seq":3,"ts":"2026-08-04T00:00:02Z","op":"upsert","type":"revision","id":"r1","ver":0,"payload":{"value":"old"}}""",
            """{"seq":4,"ts":"2026-08-04T00:00:03Z","op":"upsert","type":"revision","id":"r1","ver":1,"payload":{"value":"new"}}""",
            """{"seq":5,"ts":"2026-08-04T00:00:04Z","op":"upsert","type":"revision","id":"r2","ver":0,"payload":{}}""",
        )
        webdav.putJournal(deviceId, oldJournal)
        prefs.edit().putString("webdav-seqs:${client.fingerprint(settings())}", """{"$deviceId":5}""").apply()
        database.dao().put(note())

        client.sync(settings())

        val compacted = webdav.journalText(deviceId)!!.lineSequence().filter(String::isNotBlank)
            .map { JsonParser.parseString(it).asJsonObject }.toList()
        val tombstone = compacted.single { it["id"].asString == "gone" }
        assertEquals("delete", tombstone["op"].asString)
        assertEquals(2L, tombstone["seq"].asLong)
        assertEquals(listOf(4L, 5L), compacted.filter { it["type"].asString == "revision" }.map { it["seq"].asLong })
        val head = JsonParser.parseString(webdav.files["notebook_backup/journal/$deviceId.head.json"]!!.toString(StandardCharsets.UTF_8)).asJsonObject
        assertEquals(3, head["stateEntries"].asInt)
        assertEquals(2, head["historyEntries"].asInt)
    }
}
