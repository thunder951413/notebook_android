package io.github.notebook.android.sync

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.github.notebook.android.BuildConfig
import io.github.notebook.android.data.ApiSyncVersionEntity
import io.github.notebook.android.data.AssetEntity
import io.github.notebook.android.data.FolderEntity
import io.github.notebook.android.data.NotebookDao
import io.github.notebook.android.data.TagEntity
import io.github.notebook.android.data.TodoStepEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import kotlin.math.max

data class WebdavSettings(
    val baseUrl: String = "https://dav.jianguoyun.com/dav/",
    val username: String = "",
    val appPassword: String = "",
    val remotePath: String = "notebook_backup",
    /** Optional secret used only for private-note encryption (not WebDAV auth). */
    val syncPassword: String = "",
)

data class WebdavTestResult(val ok: Boolean, val remotePathExists: Boolean, val latencyMs: Long, val message: String)

internal data class WebdavJournalEntry(
    val seq: Long,
    val ts: String,
    val op: String,
    val type: String,
    val id: String,
    val ver: Long,
    val hash: String?,
    val payload: JsonObject,
)

/** Small commit marker written after a journal PUT. Old v4 clients ignore it. */
internal data class WebdavJournalHead(
    val version: Int,
    val deviceId: String,
    val lastSeq: Long,
    val journalBytes: Int,
    val journalSha256: String,
    val updatedAt: String,
    val stateEntries: Int,
    val historyEntries: Int,
)

/** Wire format shared with Notebook Next Electron/desktop (v4 append-only journal). */
internal object WebdavJournalProtocol {
    const val DEFAULT_WORKSPACE_ID = "00000000-0000-4000-8000-000000000001"
    const val EXTERNALIZE_BYTES = 2048
    const val MAX_LOG_BYTES = 100 * 1024 * 1024
    const val MAX_ENTRIES = 100_000
    const val MAX_OBJECT_BYTES = 100 * 1024 * 1024
    const val MAX_HEAD_BYTES = 64 * 1024
    const val MAX_REVISION_ENTRIES = 256
    const val MAX_REVISION_BYTES = 2 * 1024 * 1024
    const val OBJECT_MARKER = "${'$'}object"
    const val PAYLOAD_OBJECT_MARKER = "${'$'}payloadObject"
    private val DEVICE_ID_PATTERN = Regex("^[0-9a-f-]{36}$", RegexOption.IGNORE_CASE)
    private val HASH_PATTERN = Regex("^[0-9a-f]{64}$", RegexOption.IGNORE_CASE)

    fun validDeviceId(deviceId: String) = DEVICE_ID_PATTERN.matches(deviceId)
    fun requireDeviceId(deviceId: String): String {
        require(validDeviceId(deviceId)) { "设备 ID 不正确：$deviceId" }
        return deviceId
    }

    fun requireHash(hash: String): String {
        require(HASH_PATTERN.matches(hash)) { "对象哈希格式不正确：$hash" }
        return hash
    }

    fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    fun sha256(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(1024 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count > 0) digest.update(buffer, 0, count)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun parseHead(text: String): WebdavJournalHead {
        val root = JsonParser.parseString(text).asJsonObject
        val version = root["version"]?.asInt ?: error("远端日志索引缺少版本")
        require(version == 1) { "远端日志索引版本不支持" }
        val deviceId = requireDeviceId(root["deviceId"]?.asString ?: error("远端日志索引缺少设备 ID"))
        val lastSeq = root["lastSeq"]?.asLong ?: error("远端日志索引缺少序号")
        val journalBytes = root["journalBytes"]?.asInt ?: error("远端日志索引缺少大小")
        require(lastSeq >= 0 && journalBytes in 0..MAX_LOG_BYTES) { "远端日志索引不合法" }
        val hash = requireHash(root["journalSha256"]?.asString ?: error("远端日志索引缺少校验值"))
        val updatedAt = root["updatedAt"]?.takeIf { it.isJsonPrimitive }?.asString
            ?: error("远端日志索引缺少更新时间")
        val stateEntries = root["stateEntries"]?.asInt ?: error("远端日志索引缺少状态计数")
        val historyEntries = root["historyEntries"]?.asInt ?: error("远端日志索引缺少历史计数")
        require(stateEntries >= 0 && historyEntries >= 0) { "远端日志索引不合法" }
        return WebdavJournalHead(
            version, deviceId, lastSeq, journalBytes, hash, updatedAt, stateEntries, historyEntries,
        )
    }

    /** Parse a journal file body into entries, tolerating a trailing newline. */
    fun parseJournal(text: String): List<WebdavJournalEntry> {
        val gson = Gson()
        return text.split('\n').mapNotNull { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty()) return@mapNotNull null
            val root = runCatching { JsonParser.parseString(trimmed).asJsonObject }.getOrElse { error("远端日志包含无法解析的条目") }
            val op = root["op"]?.takeIf { it.isJsonPrimitive }?.asString
            require(op == "upsert" || op == "delete") { "远端日志包含非法操作" }
            WebdavJournalEntry(
                seq = root["seq"].takeIf { it.isJsonPrimitive }?.asLong ?: error("远端日志缺少序号"),
                ts = root["ts"]?.takeIf { it.isJsonPrimitive }?.asString.orEmpty(),
                op = op,
                type = root["type"]?.takeIf { it.isJsonPrimitive }?.asString.orEmpty().also { require(it.isNotBlank()) { "远端日志缺少实体类型" } },
                id = root["id"]?.takeIf { it.isJsonPrimitive }?.asString.orEmpty().also { require(it.isNotBlank()) { "远端日志缺少实体 ID" } },
                ver = root["ver"]?.takeIf { it.isJsonPrimitive }?.asLong ?: 0L,
                hash = root["hash"]?.takeIf { it.isJsonPrimitive }?.asString,
                payload = root["payload"]?.takeIf { it.isJsonObject }?.asJsonObject ?: error("远端日志缺少内容"),
            )
        }
    }
}

/** Per-device read cursor shared with the v4 journal repository. */
internal fun webdavDeviceId(prefs: SharedPreferences): String =
    prefs.getString("device", null) ?: UUID.randomUUID().toString().also { prefs.edit().putString("device", it).apply() }

/**
 * v4 append-only journal synchronizer over WebDAV (e.g. 坚果云), sharing the
 * ApiSync outbox/version tables so switching backends never loses queued
 * changes. Payload shapes match the /v1/sync API contract.
 */
class WebdavSyncClient(
    private val context: Context,
    private val dao: NotebookDao,
    private val prefs: SharedPreferences,
    private val api: ApiSyncClient,
    private val allowInsecureHttp: Boolean = BuildConfig.DEBUG,
) {
    private val gson = GsonBuilder().disableHtmlEscaping().create()
    /** Entities that conflicted during the latest pull stay queued for the user. */
    private val conflictKeys = mutableSetOf<String>()

    fun fingerprint(settings: WebdavSettings) = "${settings.baseUrl.trim()}|${settings.username.trim()}|${settings.remotePath.trim()}"

    private fun client(settings: WebdavSettings) =
        WebdavClient(settings.baseUrl, settings.username, settings.appPassword, settings.remotePath, allowInsecureHttp = allowInsecureHttp)

    private fun validate(settings: WebdavSettings) {
        require(settings.baseUrl.isNotBlank()) { "请先配置坚果云 WebDAV 同步：服务器地址不能为空" }
        require(settings.username.isNotBlank()) { "请先配置坚果云 WebDAV 同步：用户名不能为空" }
        require(settings.appPassword.isNotBlank()) { "请先配置坚果云 WebDAV 同步：应用密码不能为空" }
        require(settings.remotePath.isNotBlank()) { "请先配置坚果云 WebDAV 同步：远端目录不能为空" }
    }

    suspend fun test(settings: WebdavSettings): WebdavTestResult = withContext(Dispatchers.IO) {
        val startedAt = System.currentTimeMillis()
        try {
            val client = client(settings)
            client.ensureDirectory(settings.remotePath.trim())
            val exists = client.exists("${settings.remotePath.trim()}/journal")
            WebdavTestResult(true, exists, System.currentTimeMillis() - startedAt, "")
        } catch (error: Throwable) {
            WebdavTestResult(false, false, System.currentTimeMillis() - startedAt, error.message ?: "无法连接 WebDAV 服务器")
        }
    }

    suspend fun sync(settings: WebdavSettings) {
        validate(settings)
        withContext(Dispatchers.IO) {
            conflictKeys.clear()
            val budget = RemoteTransferLimits.Budget()
            // Pull first so a device joining an existing repository adopts the
            // shared state before publishing; an empty repository is seeded by
            // the full local library (equivalent to the desktop migration).
            val client = client(settings)
            val deviceIds = pull(settings, client, budget)
            val workspace = WebdavJournalProtocol.DEFAULT_WORKSPACE_ID
            if (deviceIds.isEmpty()) queueInitialSnapshot()
            else if (dao.apiOutboxCount(workspace) == 0 && (dao.dirtyNoteCount() > 0 || dao.dirtyAssetCount() > 0)) api.queueDirtyRecords(workspace)
            push(settings, client)
            // Second pull catches entries published by other devices while this
            // device was pushing (mirrors the API backend's pull/push/pull).
            pull(settings, client, budget)
        }
    }

    // ---- Pull -----------------------------------------------------------------

    private suspend fun pull(settings: WebdavSettings, client: WebdavClient, budget: RemoteTransferLimits.Budget): List<String> {
        client.ensureDirectory("${settings.remotePath.trim()}/journal")
        val deviceIds = client.listJournalNames()
        val seqs = knownSeqs(settings)
        for (deviceId in deviceIds) {
            val known = seqs[deviceId] ?: 0L
            val head = client.getText(client.journalHeadPath(deviceId), WebdavJournalProtocol.MAX_HEAD_BYTES)
                ?.let { WebdavJournalProtocol.parseHead(it) }
            if (head != null) {
                require(head.deviceId == deviceId) { "远端日志索引设备 ID 不匹配" }
                if (head.lastSeq <= known) continue
            }
            // No head is a legacy v4 journal; retain its full-read compatibility.
            val text = client.getText(client.journalPath(deviceId), maxBytes = WebdavJournalProtocol.MAX_LOG_BYTES) ?: continue
            val entries = WebdavJournalProtocol.parseJournal(text)
            if (head != null) {
                val bytes = text.toByteArray(Charsets.UTF_8)
                val matchesHead = bytes.size == head.journalBytes &&
                    WebdavJournalProtocol.sha256(bytes).equals(head.journalSha256, ignoreCase = true)
                // A journal can have an uncommitted tail when a v1 writer put
                // the journal but lost its final head PUT. That tail is hidden
                // until a matching head arrives; any other mismatch is corrupt.
                if (!matchesHead) {
                    val committedPrefixMatches = bytes.size >= head.journalBytes &&
                        WebdavJournalProtocol.sha256(bytes.copyOfRange(0, head.journalBytes)).equals(head.journalSha256, ignoreCase = true)
                    require((entries.maxOfOrNull { it.seq } ?: 0L) > head.lastSeq && committedPrefixMatches) { "远端日志索引校验失败" }
                }
            }
            var committed = known
            for (entry in entries.asSequence().filter { it.seq <= (head?.lastSeq ?: Long.MAX_VALUE) }) {
                if (entry.seq <= known) continue
                if (!applyJournalEntry(client, entry, budget)) break
                committed = max(committed, entry.seq)
            }
            seqs[deviceId] = committed
        }
        saveKnownSeqs(settings, seqs)
        return deviceIds
    }

    /** Returns false for a conflict so the caller can keep the cursor at the committed prefix. */
    private suspend fun applyJournalEntry(client: WebdavClient, entry: WebdavJournalEntry, budget: RemoteTransferLimits.Budget): Boolean {
        val type = entry.type
        val id = entry.id
        val version = entry.ver
        val operation = entry.op
        var payload = entry.payload
        if (type == "document" && operation == "upsert") {
            val payloadMarker = payload[WebdavJournalProtocol.PAYLOAD_OBJECT_MARKER]
                ?.takeIf { it.isJsonPrimitive }?.asString
            if (payloadMarker != null) {
                val hash = WebdavJournalProtocol.requireHash(payloadMarker)
                val bytes = client.getBytes(client.objectPath(hash)) ?: error("远端对象 $hash 不存在")
                require(WebdavJournalProtocol.sha256(bytes).equals(hash, ignoreCase = true)) { "远端对象 $hash 的 SHA-256 校验失败" }
                payload = JsonParser.parseString(String(bytes, Charsets.UTF_8)).asJsonObject
            }
        }
        val pending = dao.apiOutboxItem(type, id)
        val affectedPage = api.pageIdFor(type, id, payload)
        val locallyDirty = affectedPage?.let { dao.get(it)?.dirty } == true
        if ((pending != null && version > pending.expectedVersion) || locallyDirty) {
            conflictKeys.add("$type:$id")
            affectedPage?.let { api.recordConflict(it, type, payload) }
            return false
        }
        // Restore externalized document bodies fetched from objects/.
        if (type == "document" && operation == "upsert") {
            val marker = payload["tiptapJson"]?.takeIf { it.isJsonObject }?.asJsonObject
                ?.get(WebdavJournalProtocol.OBJECT_MARKER)?.takeIf { it.isJsonPrimitive }?.asString
            if (marker != null) {
                val hash = WebdavJournalProtocol.requireHash(marker)
                val bytes = client.getBytes(client.objectPath(hash)) ?: error("远端对象 $marker 不存在")
                require(WebdavJournalProtocol.sha256(bytes).equals(hash, ignoreCase = true)) { "远端对象 $hash 的 SHA-256 校验失败" }
                payload = payload.deepCopy()
                payload.add("tiptapJson", JsonParser.parseString(String(bytes, Charsets.UTF_8)))
            }
        }
        if (type == "asset" && operation == "upsert") {
            val hash = entry.hash ?: payload.optionalString("objectHash") ?: payload.optionalString("checksum")
            require(hash != null) { "附件 $id 缺少对象哈希，已停止同步" }
            downloadAsset(client, id, payload, WebdavJournalProtocol.requireHash(hash), budget)
        }
        if (operation == "delete") api.applyDelete(type, id, payload) else when (type) {
            "notebook" -> dao.putApiNotebook(io.github.notebook.android.data.ApiNotebookEntity(
                id, payload.string("workspaceId", WebdavJournalProtocol.DEFAULT_WORKSPACE_ID), payload.string("name"),
                payload.optionalString("emoji"), payload.optionalString("color"), payload.integer("sortOrder"),
                payload.millis("createdAt"), payload.millis("updatedAt"), payload.optionalMillis("deletedAt"),
            ))
            "section" -> dao.putFolder(FolderEntity(
                id, payload.string("name"), payload.integer("sortOrder"), "noteFolder", payload.millis("updatedAt"),
                payload.string("notebookId", "personal"), payload.optionalString("parentSectionId"), payload.optionalString("color"),
            ))
            "tag" -> dao.putTag(TagEntity(id, payload.string("name"), payload.string("color", "gray"), payload.millis("updatedAt")))
            "page" -> api.applyPage(id, payload, version)
            "document" -> api.applyDocument(id, payload)
            "page_tag" -> api.applyPageTag(payload, true)
            "task_step" -> dao.putStep(TodoStepEntity(
                id, payload.string("pageId"), payload.string("text"), payload.boolean("checked"),
                payload.integer("sortOrder"), payload.millis("createdAt"),
            ))
            "reading_position" -> dao.putReadingPosition(io.github.notebook.android.data.ReadingPositionEntity(
                payload.string("pageId"), payload.integer("anchorUtf16Offset"), payload.double("viewportOffsetFraction"),
                payload.millis("updatedAt"), payload.string("deviceId"),
            ))
        }
        dao.putApiVersion(ApiSyncVersionEntity(api.versionKey(WebdavJournalProtocol.DEFAULT_WORKSPACE_ID, type, id), version))
        return true
    }

    private suspend fun downloadAsset(client: WebdavClient, id: String, payload: JsonObject, hash: String, budget: RemoteTransferLimits.Budget) {
        val filename = payload.string("filename", "attachment").replace(Regex("[^A-Za-z0-9._\\-\\u4e00-\\u9fff]"), "_")
        val relative = "next/$id/$filename"
        val target = File(context.filesDir, "attachments/$relative")
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, "${target.name}.download")
        try {
            client.downloadObjectToFile(hash, temp, budget)
            payload.optionalString("checksum")?.takeIf { it.isNotBlank() }?.let { expected ->
                require(expected.equals(hash, ignoreCase = true)) { "附件 $id 的 SHA-256 校验失败" }
            }
            if (!temp.renameTo(target)) { temp.copyTo(target, true); temp.delete() }
            dao.putAssets(listOf(AssetEntity(
                id, payload.string("pageId"), payload.string("kind", "file"), filename,
                payload.string("mimeType", "application/octet-stream"), relative, target.absolutePath, hash, target.length(), false,
            )))
        } catch (error: Throwable) {
            temp.delete()
            throw error
        }
    }

    // ---- Push -----------------------------------------------------------------

    /** First publication: mirror the desktop v3→v4 migration by uploading the
     *  full local library as version-0 entries. Encrypted folders stay local. */
    private suspend fun queueInitialSnapshot() {
        val workspace = WebdavJournalProtocol.DEFAULT_WORKSPACE_ID
        val encryptedNotes = encryptedNoteIds()
        dao.allFolders().filter { it.type != "encryptedFolder" }.forEach { api.queueFolder(workspace, it) }
        dao.allTags().forEach { api.queueTag(workspace, it) }
        dao.allNotes().filter { it.id !in encryptedNotes }.forEach { note ->
            api.queueNote(workspace, note)
            dao.assets(note.id).forEach { api.queueAsset(workspace, it) }
        }
        dao.allReadingPositions().filter { it.noteId !in encryptedNotes }.forEach { api.queueReadingPosition(workspace, it) }
    }

    /** Notes inside encrypted folders must never leave this device in plaintext. */
    private suspend fun encryptedNoteIds(): Set<String> {
        val ids = runCatching { gson.fromJson(prefs.getString("encryptedNoteIds", "[]"), Array<String>::class.java).toSet() }.getOrDefault(emptySet())
        val mappings = runCatching { JsonParser.parseString(prefs.getString("encryptedMappings", "{}")).asJsonObject.entrySet().associate { it.key to it.value.asString } }.getOrDefault(emptyMap())
        val encryptedFolderIds = dao.allFolders().filter { it.type == "encryptedFolder" }.map { it.id }.toSet()
        return ids + mappings.filterValues { it in encryptedFolderIds }.keys
    }

    private suspend fun push(settings: WebdavSettings, client: WebdavClient) {
        val workspace = WebdavJournalProtocol.DEFAULT_WORKSPACE_ID
        val outgoing = dao.apiOutbox(workspace, 200).filter { "${it.entityType}:${it.entityId}" !in conflictKeys }
        if (outgoing.isEmpty()) return
        val deviceId = WebdavJournalProtocol.requireDeviceId(webdavDeviceId(prefs))
        val encryptedNotes = encryptedNoteIds()
        val encryptedFolders = dao.allFolders().filter { it.type == "encryptedFolder" }.map { it.id }.toSet()
        val entries = mutableListOf<JsonObject>()
        // Only acknowledge rows that were actually serialized into the journal.
        // Malformed records stay queued for repair. Private records are removed
        // from the network outbox because they are intentionally local-only.
        val published = mutableListOf<io.github.notebook.android.data.ApiSyncOutboxEntity>()
        val localOnly = mutableListOf<io.github.notebook.android.data.ApiSyncOutboxEntity>()
        for (item in outgoing) {
            var payload = runCatching { JsonParser.parseString(item.payloadJson).asJsonObject }.getOrNull() ?: continue
            // Assets, steps, page-tag relations and reading positions use ids
            // different from their owning note, so resolve pageId from payload.
            val affectedPage = api.pageIdFor(item.entityType, item.entityId, payload)
            if (affectedPage in encryptedNotes || (item.entityType == "section" && item.entityId in encryptedFolders)) {
                localOnly.add(item)
                continue
            }
            val entry = JsonObject()
            entry.addProperty("ts", Instant.now().toString())
            entry.addProperty("op", item.operation)
            entry.addProperty("type", item.entityType)
            entry.addProperty("id", item.entityId)
            entry.addProperty("ver", item.expectedVersion)
            when (item.entityType) {
                "document" -> if (item.operation == "upsert") {
                    val bytes = gson.toJson(payload).toByteArray(Charsets.UTF_8)
                    if (bytes.size > WebdavJournalProtocol.EXTERNALIZE_BYTES) {
                        val hash = WebdavJournalProtocol.sha256(bytes)
                        client.putObject(hash, bytes)
                        payload = JsonObject().apply { addProperty(WebdavJournalProtocol.PAYLOAD_OBJECT_MARKER, hash) }
                        entry.addProperty("hash", hash)
                    }
                }
                "asset" -> if (item.operation == "upsert") {
                    val asset = dao.getAsset(item.entityId) ?: error("附件 ${item.entityId} 的元数据不存在")
                    val file = asset.localPath?.let(::File)?.takeIf(File::isFile) ?: error("附件 ${asset.filename} 缺少本地文件，已阻止不完整同步")
                    RemoteTransferLimits.requireUploadSize(file.length())
                    val hash = file.inputStream().use { WebdavJournalProtocol.sha256(it) }
                    client.putObject(hash, file)
                    payload.addProperty("objectHash", hash)
                    payload.addProperty("byteSize", file.length())
                    entry.addProperty("hash", hash)
                }
            }
            entry.add("payload", payload)
            entries.add(entry)
            published.add(item)
        }
        for (item in localOnly) {
            dao.deleteApiOutboxById(item.id)
            if (item.entityType == "page") dao.get(item.entityId)?.let { dao.put(it.copy(dirty = false)) }
            if (item.entityType == "asset") dao.getAsset(item.entityId)?.let { dao.putAssets(listOf(it.copy(dirty = false))) }
        }
        if (entries.isEmpty()) return
        val pushed = appendJournal(client, deviceId, entries)
        for (item in published) {
            dao.deleteApiOutboxById(item.id)
            val version = item.expectedVersion + 1
            dao.putApiVersion(ApiSyncVersionEntity(api.versionKey(workspace, item.entityType, item.entityId), version))
            if (item.entityType == "page") dao.get(item.entityId)?.let { dao.put(it.copy(dirty = false, lastSyncedVersion = version)) }
            if (item.entityType == "asset") dao.getAsset(item.entityId)?.let { dao.putAssets(listOf(it.copy(dirty = false))) }
            if (item.entityType == "reading_position") markReadingPositionSynced(item.payloadJson)
        }
        val seqs = knownSeqs(settings)
        seqs[deviceId] = max(seqs[deviceId] ?: 0L, pushed.first + pushed.second - 1)
        saveKnownSeqs(settings, seqs)
    }

    /** WebDAV has no append primitive: fetch the journal, append locally, PUT the whole file. */
    private fun appendJournal(client: WebdavClient, deviceId: String, entries: List<JsonObject>): Pair<Long, Int> {
        val path = client.journalPath(deviceId)
        // A device is the sole writer of its journal. It may have exceeded the
        // 4 MB steady-state limit before this push gets a chance to compact it,
        // so allow the larger object safety cap while reading our own log.
        val existing = client.getText(path, maxBytes = WebdavJournalProtocol.MAX_LOG_BYTES)
            ?.let { WebdavJournalProtocol.parseJournal(it) } ?: emptyList()
        // Keep the sequence numbers monotonic across compaction: other devices
        // filter by per-device cursors, so seq values must never shrink.
        val firstSeq = (existing.maxOfOrNull { it.seq } ?: 0L) + 1
        val lines = entries.mapIndexed { index, entry ->
            entry.deepCopy().apply { addProperty("seq", firstSeq + index) }
        }
        val appended = existing + lines.map { it.toJournalEntry() }
        // Compact every write. State is an LWW snapshot (including tombstones);
        // revisions are bounded history, not an ever-growing event stream.
        val state = appended.filterNot { it.type == "revision" }
            .groupBy { it.type to it.id }.values.map { it.maxBy { entry -> entry.seq } }
        val revisions = appended.filter { it.type == "revision" }
            .groupBy { it.type to it.id }.values.map { it.maxBy { entry -> entry.seq } }
            .sortedByDescending { it.seq }
        var revisionBytes = 0
        val retainedRevisions = mutableListOf<WebdavJournalEntry>()
        for (revision in revisions) {
            val size = utf8Size(gson.toJson(revision)) + 1
            if (retainedRevisions.size >= WebdavJournalProtocol.MAX_REVISION_ENTRIES) break
            if (retainedRevisions.isNotEmpty() && revisionBytes + size > WebdavJournalProtocol.MAX_REVISION_BYTES) break
            run {
                retainedRevisions.add(revision)
                revisionBytes += size
            }
        }
        val retained = (state + retainedRevisions).sortedBy { it.seq }
        if (retained.size > WebdavJournalProtocol.MAX_ENTRIES) error("设备日志条目数超过 10 万，请先压缩日志")
        val body = retained.joinToString("\n") { gson.toJson(it) } + "\n"
        if (utf8Size(body) > WebdavJournalProtocol.MAX_LOG_BYTES) error("设备日志压缩后超过 100 MB 上限")
        client.putText(path, body)
        // The head is the commit marker: readers trust it only after the journal
        // exists with the exact advertised bytes and checksum.
        val bodyBytes = body.toByteArray(Charsets.UTF_8)
        val head = JsonObject().apply {
            addProperty("version", 1)
            addProperty("deviceId", deviceId)
            addProperty("lastSeq", retained.maxOfOrNull { it.seq } ?: 0L)
            addProperty("journalBytes", bodyBytes.size)
            addProperty("journalSha256", WebdavJournalProtocol.sha256(bodyBytes))
            addProperty("updatedAt", Instant.now().toString())
            addProperty("stateEntries", state.size)
            addProperty("historyEntries", retainedRevisions.size)
        }
        client.putText(client.journalHeadPath(deviceId), gson.toJson(head))
        return firstSeq to entries.size
    }

    private fun utf8Size(text: String) = text.toByteArray(Charsets.UTF_8).size

    private suspend fun markReadingPositionSynced(payloadJson: String) {
        val payload = runCatching { JsonParser.parseString(payloadJson).asJsonObject }.getOrNull() ?: return
        val noteId = payload.optionalString("pageId") ?: return
        val updatedAt = payload.optionalMillis("updatedAt") ?: return
        dao.markReadingPositionSynced(noteId, updatedAt)
    }

    private fun JsonObject.toJournalEntry() = WebdavJournalEntry(
        seq = get("seq").asLong,
        ts = get("ts").asString,
        op = get("op").asString,
        type = get("type").asString,
        id = get("id").asString,
        ver = get("ver").asLong,
        hash = get("hash")?.takeUnless { it.isJsonNull }?.asString,
        payload = getAsJsonObject("payload"),
    )

    // ---- Per-repository read cursors -------------------------------------------

    private fun knownSeqsKey(settings: WebdavSettings) = "webdav-seqs:${fingerprint(settings)}"

    private fun knownSeqs(settings: WebdavSettings): MutableMap<String, Long> {
        val stored = runCatching { JsonParser.parseString(prefs.getString(knownSeqsKey(settings), "{}")).asJsonObject }.getOrDefault(JsonObject())
        return stored.entrySet().mapNotNull { (device, seq) ->
            if (seq.isJsonPrimitive && seq.asJsonPrimitive.isNumber && seq.asLong > 0) device to seq.asLong else null
        }.toMap().toMutableMap()
    }

    private fun saveKnownSeqs(settings: WebdavSettings, seqs: Map<String, Long>) {
        val json = JsonObject()
        seqs.forEach { (device, seq) -> if (seq > 0) json.addProperty(device, seq) }
        prefs.edit().putString(knownSeqsKey(settings), json.toString()).apply()
    }
}

private fun JsonObject.string(key: String, default: String = "") = get(key)?.takeUnless { it.isJsonNull }?.asString ?: default
private fun JsonObject.optionalString(key: String) = get(key)?.takeUnless { it.isJsonNull }?.asString
private fun JsonObject.integer(key: String, default: Int = 0) = get(key)?.takeUnless { it.isJsonNull }?.asInt ?: default
private fun JsonObject.double(key: String, default: Double = 0.0) = get(key)?.takeUnless { it.isJsonNull }?.asDouble ?: default
private fun JsonObject.boolean(key: String, default: Boolean = false) = get(key)?.takeUnless { it.isJsonNull }?.asBoolean ?: default
private fun JsonObject.millis(key: String) = optionalMillis(key) ?: System.currentTimeMillis()
private fun JsonObject.optionalMillis(key: String) = optionalString(key)?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
