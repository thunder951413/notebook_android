package io.github.notebook.android.data

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.notebook.android.sync.SyncRepository
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class)
class LibraryOperationsTest {
    private lateinit var db: NotebookDatabase
    private lateinit var repo: SyncRepository
    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        db =
            Room.inMemoryDatabaseBuilder(context, NotebookDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        repo =
            SyncRepository(
                context,
                db.dao(),
                context.getSharedPreferences(UUID.randomUUID().toString(), 0),
            )
    }

    @After
    fun close() {
        db.close()
    }

    @Test
    fun renamePreservesLibraryParentAndColor() = runBlocking {
        val folder =
            FolderEntity(
                "f",
                "旧",
                4,
                "noteFolder",
                notebookId = "book",
                parentSectionId = "section",
                color = "#ABCDEF",
            )
        db.dao().putFolder(folder)
        repo.saveFolder("新名称", id = "f")
        val saved = db.dao().getFolder("f")!!
        assertEquals("#ABCDEF", saved.color)
        assertEquals("book", saved.notebookId)
        assertEquals("section", saved.parentSectionId)
        assertEquals(4, saved.sortOrder)
    }

    @Test
    fun movingCarriesDescendantsAndDissolvingPreservesNotes() = runBlocking {
        val a = FolderEntity("a", "A")
        val b = FolderEntity("b", "B")
        db.dao().putFolder(a)
        db.dao().putFolder(b)
        repo.save(NoteEntity("root", body = "原文", folderId = a.id, folderName = a.name))
        repo.save(
            NoteEntity(
                "child",
                body = "子页",
                parentPageId = "root",
                folderId = a.id,
                folderName = a.name,
            )
        )
        repo.moveToLibrary("root", b)
        assertEquals("b", db.dao().get("child")!!.folderId)
        assertEquals("root", db.dao().get("child")!!.parentPageId)
        repo.dissolveLibrary("b", false)
        assertNull(db.dao().getFolder("b"))
        assertNull(db.dao().get("root")!!.folderId)
        assertNull(db.dao().get("child")!!.deletedAt)
        assertEquals("子页", db.dao().get("child")!!.body)
    }

    @Test
    fun deletingLibraryTrashesContentsAndKeepsTheirBytesRecoverable() = runBlocking {
        val folder = FolderEntity("f", "临时库")
        db.dao().putFolder(folder)
        repo.save(NoteEntity("n", body = "保留正文", folderId = "f", folderName = folder.name))
        repo.dissolveLibrary("f", true)
        assertNotNull(db.dao().get("n")!!.deletedAt)
        assertEquals("保留正文", db.dao().get("n")!!.body)
        repo.restore("n")
        assertNull(db.dao().get("n")!!.deletedAt)
        assertNull(db.dao().get("n")!!.folderId)
    }

    @Test
    fun duplicateCopiesAssetBytesAndUnknownDocumentInsteadOfSharingDeletedFiles() = runBlocking {
        val id = UUID.randomUUID().toString()
        val assetId = UUID.randomUUID().toString()
        val file =
            File(context.filesDir, "attachments/$id/$assetId.png").apply {
                parentFile?.mkdirs()
                writeBytes(byteArrayOf(1, 2, 3, 4))
            }
        repo.save(
            NoteEntity(
                id,
                title = "原笔记",
                body = "![图片](notebook-asset:$assetId)",
                snapshotJson = "{\"unknown\":\"preserved\"}",
            )
        )
        db.dao()
            .putAssets(
                listOf(
                    AssetEntity(
                        assetId,
                        id,
                        "image",
                        "图.png",
                        "image/png",
                        "$id/$assetId.png",
                        file.absolutePath,
                        size = 4,
                    )
                )
            )
        val copy = repo.duplicateNote(id)
        val copied = db.dao().assets(copy.id).single()
        assertNotEquals(assetId, copied.id)
        assertTrue(copy.body.contains(copied.id))
        assertArrayEquals(file.readBytes(), File(copied.localPath!!).readBytes())
        assertEquals("{\"unknown\":\"preserved\"}", db.dao().get(copy.id)!!.snapshotJson)
        repo.moveToTrash(id)
        repo.deletePermanently(id)
        assertTrue(File(copied.localPath).isFile)
        assertEquals(4, File(copied.localPath).length().toInt())
    }

    @Test
    fun fullBodySnippetFindsHitBeyondPreviewWithoutLoadingSnapshot() = runBlocking {
        repo.save(
            NoteEntity(
                "deep",
                body = "开头" + "填充".repeat(1200) + "深处关键词" + "尾部".repeat(100),
                snapshotJson = "x".repeat(3_000_000),
            )
        )
        assertTrue(repo.searchSnippet("deep", "深处关键词").contains("深处关键词"))
        assertTrue(repo.searchSnippet("deep", "深处关键词").length <= 180)
    }

    @Test
    fun repositoryLoadsAndSavesUnicodeChunksWithEmojiWithoutSkippingCharacters() = runBlocking {
        val body = "开始\n" + "中文😀🫠".repeat(40000) + "\n末尾"
        repo.save(NoteEntity("unicode", body = body))
        val loaded = repo.loadNote("unicode")!!
        assertEquals(body, loaded.body)
        repo.save(loaded.copy(body = loaded.body + "追加"))
        assertEquals(body + "追加", db.dao().get("unicode")!!.body)
    }

    @Test
    fun duplicateRemapsOverlappingLegacyAssetIdsAsWholeIdentifiers() = runBlocking {
        val id = UUID.randomUUID().toString()
        val ids = listOf("legacy-asset:page:sidecar:1", "legacy-asset:page:sidecar:10")
        repo.save(
            NoteEntity(
                id,
                body = ids.joinToString("\n") { "![图片](notebook-asset:$it)" },
                snapshotJson =
                    "{\"first\":\"${ids[0]}\",\"second\":\"${ids[1]}\",\"filename\":\"${ids[1]}.png\"}",
            )
        )
        ids.forEachIndexed { i, assetId ->
            val f =
                File(context.filesDir, "attachments/$id/$i.png").apply {
                    parentFile!!.mkdirs()
                    writeBytes(byteArrayOf(i.toByte()))
                }
            db.dao()
                .putAssets(
                    listOf(
                        AssetEntity(
                            assetId,
                            id,
                            "image",
                            "$i.png",
                            "image/png",
                            "$id/$i.png",
                            f.absolutePath,
                            size = 1,
                        )
                    )
                )
        }
        val copy = repo.duplicateNote(id)
        val assets = db.dao().assets(copy.id).associateBy { it.filename }
        val references = io.github.notebook.android.NotebookAssetSyntax.images(copy.body)
        assertEquals(
            listOf(assets.getValue("0.png").id, assets.getValue("1.png").id),
            references.map { it.assetId },
        )
        val json = com.google.gson.JsonParser.parseString(copy.snapshotJson).asJsonObject
        assertEquals(assets.getValue("0.png").id, json["first"].asString)
        assertEquals(assets.getValue("1.png").id, json["second"].asString)
        assertEquals("${ids[1]}.png", json["filename"].asString)
    }

    @Test
    fun duplicateReadsLargeBodyAndUnknownSnapshotThroughChunkedQueries() = runBlocking {
        val id = UUID.randomUUID().toString()
        val body = "中文😀".repeat(20000)
        val snapshot = "unknown-" + "保留".repeat(500000)
        repo.save(NoteEntity(id, body = body, snapshotJson = snapshot))
        val copy = repo.duplicateNote(id)
        assertEquals(body, copy.body)
        assertEquals(snapshot, copy.snapshotJson)
        assertEquals(body, repo.loadNote(copy.id)!!.body)
        assertEquals(snapshot.codePointCount(0, snapshot.length), db.dao().snapshotLength(copy.id))
    }
}
