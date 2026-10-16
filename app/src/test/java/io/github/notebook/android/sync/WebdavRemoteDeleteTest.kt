package io.github.notebook.android.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.gson.JsonObject
import io.github.notebook.android.data.ApiSyncVersionEntity
import io.github.notebook.android.data.NoteEntity
import io.github.notebook.android.data.NotebookDatabase
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.UUID

/**
 * A delete published by another device is authoritative: it must remove the
 * note here even when the version it was written against does not match this
 * device's accepted version (only an unsent local edit may keep the note).
 */
@RunWith(RobolectricTestRunner::class)
class WebdavRemoteDeleteTest {
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

    private fun deleteRecord(type: String, parentVersion: Long) = JsonObject().apply {
        addProperty("ts", "2026-10-04T00:00:00Z"); addProperty("op", "delete"); addProperty("type", type)
        addProperty("id", noteId); addProperty("ver", parentVersion); add("payload", JsonObject())
    }

    private fun run(localVersion: Long, remoteParent: Long, localEdit: Boolean): NoteEntity? = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, NotebookDatabase::class.java).allowMainThreadQueries().build()
        val server = MockWebServer().apply { start() }
        try {
            val dav = Dav(server)
            val prefs = context.getSharedPreferences("delete-${UUID.randomUUID()}", Context.MODE_PRIVATE)
            prefs.edit().putString("device", deviceId).commit()
            val api = ApiSyncClient(context, database.dao(), allowInsecureHttp = true)
            val client = WebdavSyncClient(context, database.dao(), prefs, api, allowInsecureHttp = true)
            val settings = WebdavSettings(baseUrl = "http://127.0.0.1:${server.port}/dav/", username = "user", appPassword = "app-password", remotePath = "notebook_backup")
            database.dao().put(NoteEntity(id = noteId, title = "To delete", body = "body", previewText = "", dirty = true))
            client.sync(settings)
            for (type in listOf("page", "document")) database.dao().putApiVersion(ApiSyncVersionEntity(api.versionKey(workspace, type, noteId), localVersion))
            if (localEdit) {
                val edited = database.dao().get(noteId)!!.copy(body = "edited on phone", dirty = true, updatedAt = System.currentTimeMillis())
                database.dao().put(edited)
                api.queueNote(workspace, edited)
            }
            dav.append(peerId, listOf(deleteRecord("page", remoteParent), deleteRecord("document", remoteParent)))
            client.sync(settings)
            database.dao().get(noteId)
        } finally {
            database.close()
            server.shutdown()
        }
    }

    @Test fun `delete written against an older version still removes the note`() {
        assertNull(run(localVersion = 3, remoteParent = 1, localEdit = false))
    }

    @Test fun `delete written against version zero still removes the note`() {
        assertNull(run(localVersion = 2, remoteParent = 0, localEdit = false))
    }

    @Test fun `delete against a matching version removes the note`() {
        assertNull(run(localVersion = 2, remoteParent = 2, localEdit = false))
    }

    @Test fun `an unsent local edit keeps the note`() {
        assertNotNull(run(localVersion = 2, remoteParent = 2, localEdit = true))
    }
}
