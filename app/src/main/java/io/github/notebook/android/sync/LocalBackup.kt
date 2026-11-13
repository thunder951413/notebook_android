package io.github.notebook.android.sync

import com.google.gson.GsonBuilder
import io.github.notebook.android.BuildConfig
import io.github.notebook.android.data.AssetEntity
import io.github.notebook.android.data.FolderEntity
import io.github.notebook.android.data.NoteEntity
import io.github.notebook.android.data.RemoteRevisionEntity
import io.github.notebook.android.data.TagEntity
import io.github.notebook.android.data.TodoStepEntity
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class DataSafetySummary(
    val activeNotes: Int,
    val activeTodos: Int,
    val trashedItems: Int,
    val privateItems: Int,
    val localAttachments: Int,
    val missingAttachments: Int,
    val pendingDrafts: Int,
    val pendingUploads: Int,
    val conflictCount: Int,
    val lastLocalBackupAt: Long?,
)

data class BackupExportSummary(
    val noteCount: Int,
    val todoCount: Int,
    val folderCount: Int,
    val tagCount: Int,
    val attachmentCount: Int,
    val skippedMissingAttachments: Int,
    val includedPrivate: Boolean,
)

data class BackupRestoreSummary(
    val restoredNotes: Int,
    val conflictCopies: Int,
    val skippedIdentical: Int,
    val restoredFolders: Int,
    val restoredTags: Int,
    val restoredAttachments: Int,
    val skippedPrivateLocked: Int,
) {
    val message: String
        get() = buildList {
            add("已恢复 $restoredNotes 条记录")
            if (conflictCopies > 0) add("已将 $conflictCopies 条有差异的备份另存为“备份恢复副本”（本机原版完整保留）")
            if (skippedIdentical > 0) add("跳过 $skippedIdentical 条相同内容")
            if (restoredAttachments > 0) add("恢复 $restoredAttachments 个附件")
            if (skippedPrivateLocked > 0) add("$skippedPrivateLocked 条私密内容需先解锁私密文件夹后再恢复")
        }.joinToString("；")
}

internal data class BackupManifest(
    val format: String = FORMAT_ID,
    val schemaVersion: Int = SCHEMA_VERSION,
    val appVersion: String = BuildConfig.VERSION_NAME,
    val exportedAt: String,
    val includedPrivate: Boolean,
    val dataSha256: String,
    val noteCount: Int,
    val todoCount: Int,
    val folderCount: Int,
    val tagCount: Int,
    val assetSha256: Map<String, String>,
) {
    companion object {
        const val FORMAT_ID = "io.github.notebook.android.backup"
        const val SCHEMA_VERSION = 1
    }
}

internal data class BackupNoteRecord(
    val note: NoteEntity,
    val privateFolderId: String? = null,
)

internal data class BackupAssetRecord(
    val asset: AssetEntity,
    val archiveEntry: String,
)

internal data class BackupLibraryPayload(
    val folders: List<FolderEntity> = emptyList(),
    val tags: List<TagEntity> = emptyList(),
    val notes: List<BackupNoteRecord> = emptyList(),
    val steps: List<TodoStepEntity> = emptyList(),
    val revisions: List<RemoteRevisionEntity> = emptyList(),
    val assets: List<BackupAssetRecord> = emptyList(),
)

internal data class ParsedBackupArchive(
    val manifest: BackupManifest,
    val payload: BackupLibraryPayload,
    val attachmentFiles: Map<String, File>,
)

/**
 * Portable, integrity-checked ZIP backup codec.
 *
 * Every archive contains:
 * 1. `manifest.json` with SHA-256 digests of canonical data and every binary attachment.
 * 2. `library/data.json` for lossless round-trip recovery into Room without schema coupling.
 * 3. `notes/<folder>/<title>-<id>.md` plain Markdown files so the user can always read their
 *    notes on any computer even without Notebook installed.
 * 4. `attachments/<assetId>-<name>` verified binary attachments.
 */
internal object LocalBackupCodec {
    const val MAX_ARCHIVE_BYTES = 512L * 1024 * 1024
    const val MAX_SINGLE_ENTRY_BYTES = 100L * 1024 * 1024
    const val MAX_ENTRIES = 25_000
    private val gson = GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create()

    fun writeArchive(
        output: OutputStream,
        folders: List<FolderEntity>,
        tags: List<TagEntity>,
        notes: List<BackupNoteRecord>,
        steps: List<TodoStepEntity>,
        revisions: List<RemoteRevisionEntity>,
        assets: List<Pair<AssetEntity, File?>>,
        includedPrivate: Boolean,
        exportedAtMs: Long = System.currentTimeMillis(),
    ): BackupExportSummary {
        val tagNames = tags.associate { it.id to it.name }
        val folderNames = folders.associate { it.id to it.name }
        val stepsByNote = steps.groupBy { it.noteId }
        val presentAssets = mutableListOf<Pair<BackupAssetRecord, File>>()
        val assetHashes = linkedMapOf<String, String>()
        var skippedMissing = 0

        for ((asset, file) in assets) {
            if (file == null || !file.isFile) {
                skippedMissing++
                continue
            }
            val safeName = sanitizeSegment(asset.filename.ifBlank { "attachment" }).take(80)
            val entryName = "attachments/${sanitizeSegment(asset.id)}-$safeName"
            val hash = file.inputStream().use(::sha256)
            presentAssets += BackupAssetRecord(asset.copy(localPath = null, contentHash = hash, size = file.length()), entryName) to file
            assetHashes[entryName] = hash
        }

        val payload = BackupLibraryPayload(
            folders = folders,
            tags = tags,
            notes = notes.map { record ->
                record.copy(
                    note = record.note.copy(
                        snapshotJson = null,
                        conflictSnapshotJson = null,
                    ),
                )
            },
            steps = steps,
            revisions = revisions,
            assets = presentAssets.map { it.first },
        )
        val dataBytes = gson.toJson(payload).toByteArray(StandardCharsets.UTF_8)
        val manifest = BackupManifest(
            exportedAt = Instant.ofEpochMilli(exportedAtMs).toString(),
            includedPrivate = includedPrivate,
            dataSha256 = sha256(dataBytes),
            noteCount = notes.count { it.note.itemType != "todo" && it.note.deletedAt == null },
            todoCount = notes.count { it.note.itemType == "todo" && it.note.deletedAt == null },
            folderCount = folders.size,
            tagCount = tags.size,
            assetSha256 = assetHashes,
        )
        val manifestBytes = gson.toJson(manifest).toByteArray(StandardCharsets.UTF_8)

        ZipOutputStream(output).use { zip ->
            putBytes(zip, "manifest.json", manifestBytes)
            putBytes(zip, "library/data.json", dataBytes)

            val usedMarkdownPaths = HashSet<String>()
            for (record in notes) {
                val note = record.note
                if (note.deletedAt != null) continue
                val folderLabel = sanitizeSegment(
                    folderNames[record.privateFolderId ?: note.folderId]
                        ?: note.folderName.ifBlank { "未分类" },
                ).ifBlank { "未分类" }
                val baseTitle = sanitizeSegment(note.title.ifBlank { "无标题" }).take(60).ifBlank { "无标题" }
                val shortId = sanitizeSegment(note.id.take(8)).ifBlank { "note" }
                var mdPath = "notes/$folderLabel/$baseTitle-$shortId.md"
                var suffix = 2
                while (!usedMarkdownPaths.add(mdPath)) {
                    mdPath = "notes/$folderLabel/$baseTitle-$shortId-$suffix.md"
                    suffix++
                }
                val markdown = renderPortableMarkdown(note, tagNames, folderLabel, stepsByNote[note.id].orEmpty())
                putBytes(zip, mdPath, markdown.toByteArray(StandardCharsets.UTF_8))
            }

            for ((record, file) in presentAssets) {
                zip.putNextEntry(ZipEntry(record.archiveEntry))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }

        return BackupExportSummary(
            noteCount = manifest.noteCount,
            todoCount = manifest.todoCount,
            folderCount = folders.size,
            tagCount = tags.size,
            attachmentCount = presentAssets.size,
            skippedMissingAttachments = skippedMissing,
            includedPrivate = includedPrivate,
        )
    }

    fun readArchive(input: InputStream, stagingDir: File): ParsedBackupArchive {
        stagingDir.mkdirs()
        var manifestBytes: ByteArray? = null
        var dataBytes: ByteArray? = null
        val stagedAttachments = LinkedHashMap<String, Pair<File, String>>()
        var totalBytes = 0L
        var entryCount = 0

        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entryCount++
                require(entryCount <= MAX_ENTRIES) { "备份包文件数量超出安全上限" }
                val name = normalizeEntryName(entry.name)
                if (entry.isDirectory) {
                    zip.closeEntry()
                    continue
                }
                when {
                    name == "manifest.json" -> {
                        val (bytes, read) = readBoundedBytes(zip, 4L * 1024 * 1024, totalBytes)
                        totalBytes = read
                        manifestBytes = bytes
                    }
                    name == "library/data.json" -> {
                        val (bytes, read) = readBoundedBytes(zip, MAX_SINGLE_ENTRY_BYTES, totalBytes)
                        totalBytes = read
                        dataBytes = bytes
                    }
                    name.startsWith("attachments/") -> {
                        val target = File(stagingDir, sanitizeSegment(name.removePrefix("attachments/")))
                        require(target.canonicalPath.startsWith(stagingDir.canonicalPath)) { "备份包附件路径不安全" }
                        val digest = MessageDigest.getInstance("SHA-256")
                        var entryBytes = 0L
                        target.outputStream().use { out ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val n = zip.read(buffer)
                                if (n < 0) break
                                if (n == 0) continue
                                entryBytes += n
                                totalBytes += n
                                require(entryBytes <= MAX_SINGLE_ENTRY_BYTES) { "备份包内单个附件超过 100 MiB 上限" }
                                require(totalBytes <= MAX_ARCHIVE_BYTES) { "备份包总大小超过 512 MiB 上限" }
                                digest.update(buffer, 0, n)
                                out.write(buffer, 0, n)
                            }
                        }
                        stagedAttachments[name] = target to digest.digest().joinToString("") { "%02x".format(it) }
                    }
                    else -> {
                        // Portable `notes/*.md` files are for external human reading; drain boundedly.
                        val buffer = ByteArray(32 * 1024)
                        var entryBytes = 0L
                        while (true) {
                            val n = zip.read(buffer)
                            if (n < 0) break
                            entryBytes += n
                            totalBytes += n
                            require(entryBytes <= MAX_SINGLE_ENTRY_BYTES && totalBytes <= MAX_ARCHIVE_BYTES) { "备份包大小超出安全限制" }
                        }
                    }
                }
                zip.closeEntry()
            }
        }

        val rawManifest = manifestBytes ?: error("不是有效的 Notebook 备份包（缺少 manifest.json）")
        val rawData = dataBytes ?: error("备份包不完整（缺少 library/data.json）")
        val manifest = runCatching {
            gson.fromJson(String(rawManifest, StandardCharsets.UTF_8), BackupManifest::class.java)
        }.getOrNull() ?: error("备份包清单格式无法解析")
        require(manifest.format == BackupManifest.FORMAT_ID && manifest.schemaVersion == BackupManifest.SCHEMA_VERSION) {
            "不支持的备份包版本"
        }
        require(sha256(rawData) == manifest.dataSha256) { "备份包数据校验失败（SHA-256 不一致），已中止恢复以保护本机数据" }
        manifest.assetSha256.forEach { (entryName, expectedHash) ->
            val staged = stagedAttachments[entryName] ?: error("备份包缺少附件文件：$entryName")
            require(staged.second == expectedHash) { "备份包附件校验失败（$entryName），已中止恢复" }
        }
        val payload = runCatching {
            gson.fromJson(String(rawData, StandardCharsets.UTF_8), BackupLibraryPayload::class.java)
        }.getOrNull() ?: error("备份包内容无法解析")
        return ParsedBackupArchive(
            manifest = manifest,
            payload = payload,
            attachmentFiles = stagedAttachments.mapValues { it.value.first },
        )
    }

    private fun renderPortableMarkdown(
        note: NoteEntity,
        tagNames: Map<String, String>,
        folderLabel: String,
        steps: List<TodoStepEntity>,
    ): String = buildString {
        appendLine("---")
        appendLine("id: ${note.id}")
        appendLine("title: ${note.title.replace('\n', ' ')}")
        appendLine("type: ${note.itemType}")
        appendLine("folder: $folderLabel")
        val resolvedTags = note.tagIds.split(',').mapNotNull { tagNames[it.trim()] }.filter { it.isNotBlank() }
        if (resolvedTags.isNotEmpty()) appendLine("tags: [${resolvedTags.joinToString(", ")}]")
        appendLine("createdAt: ${Instant.ofEpochMilli(note.createdAt)}")
        appendLine("updatedAt: ${Instant.ofEpochMilli(note.updatedAt)}")
        if (note.dueAt != null) appendLine("dueAt: ${Instant.ofEpochMilli(note.dueAt)}")
        if (note.completedAt != null) appendLine("completedAt: ${Instant.ofEpochMilli(note.completedAt)}")
        appendLine("---")
        appendLine()
        if (note.title.isNotBlank()) {
            appendLine("# ${note.title}")
            appendLine()
        }
        if (note.body.isNotBlank()) {
            appendLine(note.body)
            appendLine()
        }
        if (steps.isNotEmpty()) {
            steps.sortedBy { it.sortOrder }.forEach { step ->
                appendLine("- [${if (step.checked) "x" else " "}] ${step.text}")
            }
        }
    }

    private fun putBytes(zip: ZipOutputStream, name: String, bytes: ByteArray) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(bytes)
        zip.closeEntry()
    }

    private fun readBoundedBytes(zip: ZipInputStream, maxEntry: Long, initialTotal: Long): Pair<ByteArray, Long> {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(32 * 1024)
        var entryBytes = 0L
        var total = initialTotal
        while (true) {
            val n = zip.read(buffer)
            if (n < 0) break
            if (n == 0) continue
            entryBytes += n
            total += n
            require(entryBytes <= maxEntry) { "备份包内文件超出安全上限" }
            require(total <= MAX_ARCHIVE_BYTES) { "备份包总大小超过 512 MiB 上限" }
            out.write(buffer, 0, n)
        }
        return out.toByteArray() to total
    }

    private fun normalizeEntryName(raw: String): String {
        val normalized = raw.replace('\\', '/').trimStart('/')
        require(normalized.isNotBlank() && normalized.none { it.isISOControl() }) { "备份包包含非法条目名" }
        require(normalized.split('/').none { it == "." || it == ".." }) { "备份包包含不安全的相对路径" }
        return normalized
    }

    private fun sanitizeSegment(raw: String): String =
        raw.replace(Regex("[^A-Za-z0-9._\\-\\u4e00-\\u9fff]"), "_").trim('_').ifBlank { "item" }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun sha256(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(128 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            if (n > 0) digest.update(buffer, 0, n)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
