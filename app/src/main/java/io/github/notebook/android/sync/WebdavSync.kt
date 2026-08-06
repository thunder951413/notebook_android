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

/** Wire format shared with Notebook Next Electron/desktop (v4 append-only journal). */
internal object WebdavJournalProtocol {
    const val DEFAULT_WORKSPACE_ID = "00000000-0000-4000-8000-000000000001"
    const val EXTERNALIZE_BYTES = 2048
    const val MAX_LOG_BYTES = 4 * 1024 * 1024
    const val MAX_ENTRIES = 100_000
    const val MAX_OBJECT_BYTES = 100 * 1024 * 1024
    const val OBJECT_MARKER = "${'$'}object"
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
) {
    private val gson = GsonBuilder().disableHtmlEscaping().create()
    /** Entities that conflicted during the latest pull stay queued for the user. */
    private val conflictKeys = mutableSetOf<String>()

    fun fingerprint(settings: WebdavSettings) = "${settings.baseUrl.trim()}|${settings.username.trim()}|${settings.remotePath.trim()}"

    private fun client(settings: WebdavSettings) =
        WebdavClient(settings.baseUrl, settings.username, settings.appPassword, settings.remotePath, allowInsecureHttp = BuildConfig.DEBUG)

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
            val deviceIds = pull(settings, budget)
            if (deviceIds.isEmpty()) queueInitialSnapshot() else api.queueDirtyRecords(WebdavJournalProtocol.DEFAULT_WORKSPACE_ID)
            push(settings)
            // Second pull catches entries published by other devices while this
            // device was pushing (mirrors the API backend's pull/push/pull).
            pull(settings, budget)
        }
    }

    // ---- Pull -----------------------------------------------------------------

    private suspend fun pull(settings: WebdavSettings, budget: RemoteTransferLimits.Budget): List<String> {
        val client = client(settings)
        client.ensureDirectory("${settings.remotePath.trim()}/journal")
        val deviceIds = client.listJournalNames()
        val seqs = knownSeqs(settings)
        for (deviceId in deviceIds) {
            val text = client.getText(client.journalPath(deviceId)) ?: continue
            val entries = WebdavJournalProtocol.parseJournal(text)
            val known = seqs[deviceId] ?: 0L
            for (entry in entries) {
                if (entry.seq <= known) continue
                applyJournalEntry(client, entry, budget)
            }
            seqs[deviceId] = max(known, entries.lastOrNull()?.seq ?: 0L)
        }
        saveKnownSeqs(settings, seqs)
        return deviceIds
    }

    private suspend fun applyJournalEntry(client: WebdavClient, entry: WebdavJournalEntry, budget: RemoteTransferLimits.Budget) {
        val type = entry.type
        val id = entry.id
        val version = entry.ver
        val operation = entry.op
        var payload = entry.payload
        // Restore externalized document bodies fetched from objects/.
        if (type == "document" && operation == "upsert") {
            val marker = payload["tiptapJson"]?.takeIf { it.isJsonObject }?.asJsonObject
                ?.get(WebdavJournalProtocol.OBJECT_MARKER)?.takeIf { it.isJsonPrimitive }?.asString
            if (marker != null) {
                val hash = WebdavJournalProtocol.requireHash(marker)
                val bytes = client.getBytes(client.objectPath(hash)) ?: error("远端对象 $marker 不存在")
                payload = payload.deepCopy()
                payload.add("tiptapJson", JsonParser.parseString(String(bytes, Charsets.UTF_8)))
            }
        }
        if (type == "asset" && operation == "upsert") {
            val hash = entry.hash ?: payload.optionalString("objectHash") ?: payload.optionalString("checksum")
            require(hash != null) { "附件 $id 缺少对象哈希，已停止同步" }
            downloadAsset(client, id, payload, WebdavJournalProtocol.requireHash(hash), budget)
        }
        val pending = dao.apiOutboxItem(type, id)
        val affectedPage = api.pageIdFor(type, id, payload)
        val locallyDirty = affectedPage?.let { dao.get(it)?.dirty } == true
        if ((pending != null && version > pending.expectedVersion) || locallyDirty) {
            conflictKeys.add("$type:$id")
            affectedPage?.let { api.recordConflict(it, type, payload) }
            dao.putApiVersion(ApiSyncVersionEntity(api.versionKey(WebdavJournalProtocol.DEFAULT_WORKSPACE_ID, type, id), version))
            return
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
        dao.allReadingPositions().forEach { api.queueReadingPosition(workspace, it) }
    }

    /** Notes inside encrypted folders must never leave this device in plaintext. */
    private suspend fun encryptedNoteIds(): Set<String> {
        val ids = runCatching { gson.fromJson(prefs.getString("encryptedNoteIds", "[]"), Array<String>::class.java).toSet() }.getOrDefault(emptySet())
        val mappings = runCatching { JsonParser.parseString(prefs.getString("encryptedMappings", "{}")).asJsonObject.entrySet().associate { it.key to it.value.asString } }.getOrDefault(emptyMap())
        val encryptedFolderIds = dao.allFolders().filter { it.type == "encryptedFolder" }.map { it.id }.toSet()
        return ids + mappings.filterValues { it in encryptedFolderIds }.keys
    }

    private suspend fun push(settings: WebdavSettings) {
        val workspace = WebdavJournalProtocol.DEFAULT_WORKSPACE_ID
        val outgoing = dao.apiOutbox(workspace, 200).filter { "${it.entityType}:${it.entityId}" !in conflictKeys }
        if (outgoing.isEmpty()) return
        val deviceId = WebdavJournalProtocol.requireDeviceId(webdavDeviceId(prefs))
        val client = client(settings)
        val encryptedNotes = encryptedNoteIds()
        val entries = mutableListOf<JsonObject>()
        for (item in outgoing) {
            val payload = runCatching { JsonParser.parseString(item.payloadJson).asJsonObject }.getOrNull() ?: continue
            // Encrypted notes never sync in plaintext through the v4 journal.
            if (item.entityId in encryptedNotes && item.entityType in setOf("page", "document", "asset", "task_step", "page_tag")) continue
            val entry = JsonObject()
            entry.addProperty("ts", Instant.now().toString())
            entry.addProperty("op", item.operation)
            entry.addProperty("type", item.entityType)
            entry.addProperty("id", item.entityId)
            entry.addProperty("ver", item.expectedVersion)
            when (item.entityType) {
                "document" -> if (item.operation == "upsert") {
                    val tiptap = payload["tiptapJson"]
                    if (tiptap != null && tiptap.isJsonObject) {
                        val json = gson.toJson(tiptap)
                        if (json.length > WebdavJournalProtocol.EXTERNALIZE_BYTES) {
                            val bytes = json.toByteArray(Charsets.UTF_8)
                            val hash = WebdavJournalProtocol.sha256(bytes)
                            client.putObject(hash, bytes)
                            payload.add("tiptapJson", JsonObject().apply { addProperty(WebdavJournalProtocol.OBJECT_MARKER, hash) })
                            entry.addProperty("hash", hash)
                        }
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
        }
        if (entries.isEmpty()) return
        val pushed = appendJournal(client, deviceId, entries)
        for (item in outgoing) {
            dao.deleteApiOutboxById(item.id)
            val version = item.expectedVersion + 1
            dao.putApiVersion(ApiSyncVersionEntity(api.versionKey(workspace, item.entityType, item.entityId), version))
            if (item.entityType == "page") dao.get(item.entityId)?.let { dao.put(it.copy(dirty = false, lastSyncedVersion = version)) }
            if (item.entityType == "asset") dao.getAsset(item.entityId)?.let { dao.putAssets(listOf(it.copy(dirty = false))) }
        }
        val seqs = knownSeqs(settings)
        seqs[deviceId] = max(seqs[deviceId] ?: 0L, pushed.first + pushed.second - 1)
        saveKnownSeqs(settings, seqs)
    }

    /** WebDAV has no append primitive: fetch the journal, append locally, PUT the whole file. */
    private fun appendJournal(client: WebdavClient, deviceId: String, entries: List<JsonObject>): Pair<Long, Int> {
        val path = client.journalPath(deviceId)
        val existing = client.getText(path)?.let { WebdavJournalProtocol.parseJournal(it) } ?: emptyList()
        if (existing.size + entries.size > WebdavJournalProtocol.MAX_ENTRIES) error("设备日志条目数超过 10 万，请先压缩日志")
        val firstSeq = (existing.lastOrNull()?.seq ?: 0L) + 1
        val lines = entries.mapIndexed { index, entry ->
            entry.deepCopy().apply { addProperty("seq", firstSeq + index) }
        }
        val body = (existing.map { existingEntry ->
            JsonObject().apply {
                addProperty("seq", existingEntry.seq)
                addProperty("ts", existingEntry.ts)
                addProperty("op", existingEntry.op)
                addProperty("type", existingEntry.type)
                addProperty("id", existingEntry.id)
                addProperty("ver", existingEntry.ver)
                existingEntry.hash?.let { addProperty("hash", it) }
                add("payload", existingEntry.payload)
            }
        } + lines).joinToString("\n") { gson.toJson(it) } + "\n"
        if (body.length > WebdavJournalProtocol.MAX_LOG_BYTES) error("设备日志超过 4 MB 上限")
        client.putText(path, body)
        return firstSeq to entries.size
    }

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
