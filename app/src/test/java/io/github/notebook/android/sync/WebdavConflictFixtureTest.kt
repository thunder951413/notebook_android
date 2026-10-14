package io.github.notebook.android.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.github.notebook.android.data.ApiSyncVersionEntity
import io.github.notebook.android.data.NoteEntity
import io.github.notebook.android.data.NotebookDatabase
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.UUID

/**
 * Android runner for SyncContractFixtures/webdav-v4/conflict.json: the whole
 * WebdavSyncClient pipeline must settle each scenario exactly like the
 * renderer's storage-renderer merge (packages/storage-renderer).
 */
@RunWith(RobolectricTestRunner::class)
class WebdavConflictFixtureTest {
    private val deviceId = "00000000-0000-4000-8000-0000000000aa"
    private val peerId = "30000000-0000-4000-8000-0000000000bb"
    private val noteId = "20000000-0000-4000-8000-0000000000cc"
    private val workspace = WebdavJournalProtocol.DEFAULT_WORKSPACE_ID

    /** Minimal in-memory WebDAV server: MKCOL/PUT/GET/PROPFIND over a path map. */
    private class Dav(server: MockWebServer) {
        val files = mutableMapOf<String, ByteArray>()
        val dirs = mutableSetOf<String>()
        init {
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path.orEmpty().substringBefore('?').removePrefix("/dav/").trimEnd('/')
                    return when (request.method) {
                        "MKCOL" -> if (path in dirs) MockResponse().setResponseCode(405) else MockResponse().setResponseCode(201).also { dirs.add(path) }
                        "PUT" -> MockResponse().setResponseCode(201).also { files[path] = request.body.readByteArray() }
                        "GET" -> files[path]?.let { MockResponse().setResponseCode(200).setBody(okio.Buffer().write(it)) } ?: MockResponse().setResponseCode(404)
                        "PROPFIND" -> {
                            if (path !in dirs && path !in files && dirs.none { it.startsWith("$path/") }) return MockResponse().setResponseCode(404)
                            val children = if (request.headers["Depth"] == "0") listOf(path) else
                                (dirs + files.keys).filter { it.startsWith("$path/") && !it.removePrefix("$path/").contains('/') }
                            val body = children.joinToString("") { "<d:response><d:href>/dav/$it</d:href></d:response>" }
                            MockResponse().setResponseCode(207).setBody("<?xml version=\"1.0\"?><d:multistatus xmlns:d=\"DAV:\">$body</d:multistatus>")
                        }
                        else -> MockResponse().setResponseCode(405)
                    }
                }
            }
        }
        fun journal(device: String) = files["notebook_backup/journal/$device.jsonl"]?.toString(Charsets.UTF_8).orEmpty()
        /** Appends committed records (journal and matching commit marker) as a writer would. */
        fun append(device: String, records: List<JsonObject>) {
            val existing = WebdavJournalProtocol.parseJournal(journal(device))
            var seq = existing.maxOfOrNull { it.seq } ?: 0L
            val lines = journal(device).lineSequence().filter(String::isNotBlank).toMutableList()
            records.forEach { lines += it.deepCopy().apply { addProperty("seq", ++seq) }.toString() }
            val body = lines.joinToString("\n") + "\n"
            val bytes = body.toByteArray(Charsets.UTF_8)
            dirs += listOf("notebook_backup", "notebook_backup/journal")
            files["notebook_backup/journal/$device.jsonl"] = bytes
            files["notebook_backup/journal/$device.head.json"] = JsonObject().apply {
                addProperty("version", 1); addProperty("deviceId", device); addProperty("lastSeq", seq)
                addProperty("journalBytes", bytes.size); addProperty("journalSha256", WebdavJournalProtocol.sha256(bytes))
                addProperty("updatedAt", "2026-10-01T00:00:00Z"); addProperty("stateEntries", lines.size); addProperty("historyEntries", 0)
            }.toString().toByteArray(Charsets.UTF_8)
        }
    }

    @Test fun `conflict json scenarios`() = runBlocking {
        val value = WebdavContractFixtureTest.fixture("conflict.json")
        val title = value["pageTitle"].asString
        for (scenario in value.getAsJsonArray("scenarios").map { it.asJsonObject }) {
            val name = scenario["name"].asString
            val context = ApplicationProvider.getApplicationContext<Context>()
            val database = Room.inMemoryDatabaseBuilder(context, NotebookDatabase::class.java).allowMainThreadQueries().build()
            val server = MockWebServer().apply { start() }
            try {
                val dav = Dav(server)
                val prefs = context.getSharedPreferences("contract-${UUID.randomUUID()}", Context.MODE_PRIVATE)
                prefs.edit().putString("device", deviceId).commit()
                val api = ApiSyncClient(context, database.dao(), allowInsecureHttp = true)
                val client = WebdavSyncClient(context, database.dao(), prefs, api, allowInsecureHttp = true)
                val settings = WebdavSettings(baseUrl = "http://127.0.0.1:${server.port}/dav/", username = "user", appPassword = "app-password", remotePath = "notebook_backup")
                val baseline = scenario["baseline"].asJsonObject
                // Publish the baseline once, then pin the accepted version.
                database.dao().put(NoteEntity(id = noteId, title = title, body = baseline["markdown"].asString, previewText = "", dirty = true))
                client.sync(settings)
                for (type in listOf("page", "document")) database.dao().putApiVersion(ApiSyncVersionEntity(api.versionKey(workspace, type, noteId), baseline["version"].asLong))
                scenario["localEdit"].takeUnless { it.isJsonNull }?.asString?.let { edit ->
                    val edited = database.dao().get(noteId)!!.copy(body = edit, dirty = true, updatedAt = System.currentTimeMillis())
                    database.dao().put(edited)
                    api.queueNote(workspace, edited)
                }
                val remote = scenario["remote"].asJsonObject
                val document = JsonObject().apply {
                    addProperty("pageId", noteId); addProperty("schemaVersion", 3); addProperty("markdown", remote["markdown"].asString)
                    addProperty("updatedAt", "2026-10-04T00:00:00Z")
                }
                val record = JsonObject().apply {
                    addProperty("ts", "2026-10-04T00:00:00Z"); addProperty("op", "upsert"); addProperty("type", "document")
                    addProperty("id", noteId); addProperty("ver", remote["ver"].asLong); add("payload", document)
                }
                if (remote["ownDevice"].asBoolean) {
                    dav.append(deviceId, listOf(record))
                    // A lost read cursor makes this device replay its own journal.
                    client.resetReadPositions(settings)
                } else dav.append(peerId, listOf(record))

                client.sync(settings)

                val expected = scenario["expected"].asJsonObject
                assertEquals(name, expected["markdown"].asString.trim(), database.dao().get(noteId)!!.body.trim())
                val copies = database.dao().allNotes().filter { it.id != noteId }
                val copy = expected["conflictCopy"].takeUnless { it.isJsonNull }?.asString
                if (copy == null) assertTrue("$name: no conflict copy", copies.isEmpty())
                else {
                    assertEquals(name, 1, copies.size)
                    assertEquals(name, copy.trim(), copies.single().body.trim())
                    assertTrue(name, copies.single().title.contains(title))
                }
                expected["republishedVersion"]?.takeUnless { it.isJsonNull }?.asLong?.let { version ->
                    val published = dav.journal(deviceId).lineSequence().filter(String::isNotBlank).map { JsonParser.parseString(it).asJsonObject }
                        .last { it["type"].asString == "document" && it["id"].asString == noteId }
                    assertEquals(name, version, published["ver"].asLong)
                }
            } finally {
                database.close()
                server.shutdown()
            }
        }
    }
}
