package io.github.notebook.android.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.notebook.android.data.AssetEntity
import io.github.notebook.android.data.FolderEntity
import io.github.notebook.android.data.NoteEntity
import io.github.notebook.android.data.NotebookDatabase
import io.github.notebook.android.data.TagEntity
import io.github.notebook.android.data.TodoStepEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
class LocalBackupTest {
    private lateinit var context: Context
    private lateinit var database: NotebookDatabase
    private lateinit var repo: SyncRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, NotebookDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val testPrefs = context.getSharedPreferences("local-backup-test-${java.util.UUID.randomUUID()}", Context.MODE_PRIVATE)
        repo = SyncRepository(context, database.dao(), preferences = testPrefs)
    }

    @After
    fun tearDown() {
        if (::repo.isInitialized) runBlocking { repo.flushAll() }
        if (::database.isInitialized) database.close()
    }

    @Test
    fun `codec writes portable markdown and rejects tampered archive`() {
        val attachmentDir = File(context.cacheDir, "backup-codec-test").apply { mkdirs() }
        val attachmentFile = File(attachmentDir, "photo.bin").apply { writeBytes("attachment-bytes".toByteArray()) }
        val note = NoteEntity(
            id = "note-1",
            title = "2026 年度日志",
            body = "# 标题\n\n重要记录内容",
            folderId = "folder-1",
            folderName = "工作日志",
            createdAt = 1_710_000_000_000L,
            updatedAt = 1_710_000_500_000L,
            tagIds = "tag-1",
        )
        val folder = FolderEntity("folder-1", "工作日志", type = "noteFolder")
        val tag = TagEntity("tag-1", "周报")
        val step = TodoStepEntity("step-1", "note-1", "核对备份", true, 0)
        val asset = AssetEntity(
            id = "asset-1",
            noteId = "note-1",
            kind = "file",
            filename = "photo.bin",
            mimeType = "application/octet-stream",
            relativePath = "attachments/photo.bin",
            size = attachmentFile.length(),
            localPath = attachmentFile.absolutePath,
        )

        val zipFile = File(attachmentDir, "valid.zip")
        val exportSummary = zipFile.outputStream().use { out ->
            LocalBackupCodec.writeArchive(
                output = out,
                folders = listOf(folder),
                tags = listOf(tag),
                notes = listOf(BackupNoteRecord(note)),
                steps = listOf(step),
                revisions = emptyList(),
                assets = listOf(asset to attachmentFile),
                includedPrivate = false,
                exportedAtMs = 1_710_001_000_000L,
            )
        }
        assertEquals(1, exportSummary.noteCount)
        assertEquals(1, exportSummary.attachmentCount)

        ZipFile(zipFile).use { zip ->
            val entryNames = zip.entries().asSequence().map { it.name }.toList()
            assertTrue(entryNames.contains("manifest.json"))
            assertTrue(entryNames.contains("library/data.json"))
            assertTrue(entryNames.any { it.startsWith("notes/") && it.endsWith(".md") })
        }

        val staging = File(attachmentDir, "staging")
        val parsed = zipFile.inputStream().use { LocalBackupCodec.readArchive(it, staging) }
        assertEquals(1, parsed.payload.notes.size)
        assertEquals("2026 年度日志", parsed.payload.notes.single().note.title)
        val entryName = parsed.payload.assets.single().archiveEntry
        assertEquals("attachment-bytes", parsed.attachmentFiles[entryName]!!.readText())

        // Tamper with library/data.json and verify SHA-256 integrity check blocks restore.
        val tamperedBytes = ByteArrayOutputStream().also { buffer ->
            ZipInputStream(zipFile.inputStream()).use { zin ->
                ZipOutputStream(buffer).use { zout ->
                    var entry = zin.nextEntry
                    while (entry != null) {
                        val content = zin.readBytes()
                        zout.putNextEntry(ZipEntry(entry.name))
                        if (entry.name == "library/data.json") {
                            zout.write(String(content).replace("重要记录内容", "被篡改内容").toByteArray())
                        } else {
                            zout.write(content)
                        }
                        zout.closeEntry()
                        entry = zin.nextEntry
                    }
                }
            }
        }.toByteArray()

        val tamperedResult = runCatching {
            LocalBackupCodec.readArchive(ByteArrayInputStream(tamperedBytes), File(attachmentDir, "staging-bad"))
        }
        assertTrue(tamperedResult.isFailure)
    }

    @Test
    fun `export flushes drafts excludes locked private notes and restore preserves modified local notes`() = runBlocking {
        val publicFolder = FolderEntity("f-public", "日常", type = "noteFolder")
        val privateFolder = FolderEntity("f-private", "私密保险箱", type = "encryptedFolder")
        database.dao().putFolders(listOf(publicFolder, privateFolder))

        val publicNote = NoteEntity("n-public", title = "公开日志", body = "初始正文", folderId = publicFolder.id, folderName = publicFolder.name)
        val privateNote = NoteEntity("n-private", title = "私密日记", body = "绝密内容", folderId = privateFolder.id, folderName = privateFolder.name)
        repo.save(publicNote)
        repo.markEncrypted(privateNote.id, privateFolder.id)
        repo.save(privateNote)

        // Queue an unsaved draft edit and export immediately — export must flush drafts first!
        repo.queueDraft(publicNote.copy(body = "草稿自动刷新后的最新正文"))
        val archiveBytes = ByteArrayOutputStream()
        val exported = repo.exportLocalBackup(
            output = archiveBytes,
            includePrivate = true,
            encryptedUnlocked = false,
        )
        assertEquals(1, exported.noteCount)
        assertFalse(exported.includedPrivate)

        val staging = File(context.cacheDir, "restore-non-destructive")
        val parsed = LocalBackupCodec.readArchive(ByteArrayInputStream(archiveBytes.toByteArray()), staging)
        assertEquals(listOf("n-public"), parsed.payload.notes.map { it.note.id })
        assertEquals("草稿自动刷新后的最新正文", parsed.payload.notes.single().note.body)

        // Modify the local note so it differs from the backup, then restore:
        // local note MUST NOT be overwritten, and backup version must be saved as a recovery copy!
        val currentLocal = repo.loadNote("n-public")!!
        repo.save(currentLocal.copy(body = "本机后来新增的段落，绝不能被覆盖"))

        val restoreSummary = repo.restoreParsedBackup(parsed, encryptedUnlocked = false)
        assertEquals(0, restoreSummary.restoredNotes)
        assertEquals(1, restoreSummary.conflictCopies)

        val afterLocal = repo.loadNote("n-public")!!
        assertEquals("本机后来新增的段落，绝不能被覆盖", afterLocal.body)
        val allSaved = database.dao().allNotes()
        val conflictCopy = allSaved.firstOrNull { it.id != "n-public" && it.title.contains("备份恢复副本") }
        assertNotNull(conflictCopy)
        assertEquals("草稿自动刷新后的最新正文", conflictCopy!!.body)
    }

    @Test
    fun `safe folder and tag deletion retain notes and manual snapshot plus revision restore are reversible`() = runBlocking {
        val folder = FolderEntity("folder-safe", "项目资料", type = "noteFolder")
        val tagKeep = TagEntity("tag-keep", "保留")
        val tagDrop = TagEntity("tag-drop", "待删")
        database.dao().putFolders(listOf(folder))
        database.dao().putTags(listOf(tagKeep, tagDrop))

        val note = NoteEntity(
            id = "note-safe",
            title = "架构设计",
            body = "第一版正文",
            folderId = folder.id,
            folderName = folder.name,
            tagIds = "tag-keep,tag-drop",
        )
        repo.save(note)
        assertNotNull(repo.createManualSnapshot(note.id))

        val updated = repo.loadNote(note.id)!!.copy(body = "第二版正文")
        repo.save(updated)

        val revisionsBeforeRestore = repo.revisions(note.id)
        val v1Revision = revisionsBeforeRestore.first { it.markdown.contains("第一版正文") }
        val restored = repo.restoreRevision(note.id, v1Revision.id)
        assertEquals("第一版正文", restored.body)

        // Restoring v1 must have automatically backed up "第二版正文" into revision history!
        val revisionsAfterRestore = repo.revisions(note.id)
        assertTrue(revisionsAfterRestore.any { it.markdown.contains("第二版正文") })

        // Safe delete tag & folder without losing the note
        repo.safeDeleteTag(tagDrop.id)
        assertEquals("tag-keep", repo.loadNote(note.id)!!.tagIds)
        assertNull(database.dao().allTags().firstOrNull { it.id == tagDrop.id })

        repo.safeDeleteFolder(folder.id, deleteItems = false)
        assertNull(database.dao().getFolder(folder.id))
        val surviving = repo.loadNote(note.id)!!
        assertNull(surviving.folderId)
        assertEquals("未分类", surviving.folderName)
        assertNull(surviving.deletedAt)
    }
}
