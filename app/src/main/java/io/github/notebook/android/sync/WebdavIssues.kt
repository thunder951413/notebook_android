package io.github.notebook.android.sync

import android.content.SharedPreferences
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

/**
 * Why a peer's journal cannot be read as a whole. The codes are part of the
 * cross-platform contract (fixture: pull-isolation.json); messages are local.
 */
internal enum class WebdavBlockCode(val code: String) {
    JOURNAL_CORRUPT("journal-corrupt"),
    HEAD_CORRUPT("head-corrupt"),
    JOURNAL_TRUNCATED("journal-truncated"),
    JOURNAL_MISSING("journal-missing"),
    JOURNAL_TOO_LARGE("journal-too-large"),
}

/** A device whose journal could not be trusted; every other device is read as usual. */
internal data class WebdavBlockedDevice(val deviceId: String, val code: WebdavBlockCode, val message: String, val knownSeq: Long, val headLastSeq: Long? = null, val journalLastSeq: Long? = null)

/** Why one remote record was set aside (same vocabulary as the desktop clients). */
internal object QuarantineReason {
    const val UNKNOWN_TYPE = "unknown-type"
    const val INVALID_PAYLOAD = "invalid-payload"
    const val WORKSPACE_MISMATCH = "workspace-mismatch"
    const val MISSING_OBJECT = "missing-object"
    const val OBJECT_CORRUPT = "object-corrupt"
    const val NEEDS_KEY = "needs-key"
    const val BAD_KEY = "bad-key"
}

/** A private record that could not be read: no key configured, a key that does not fit, or damaged data. */
internal class WebdavPrivacyException(val reason: String, message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)

/** A record type this app version does not know (written by a newer version). */
internal class UnknownRecordTypeException(type: String) : IllegalArgumentException("不支持的记录类型：$type")

internal data class MaterializeFailure(val reason: String, val message: String)

/**
 * Sorts a failure while reading or applying one remote record. Only trouble
 * with the record's own data is set aside; network, storage and cancellation
 * errors say nothing about the record and must fail the sync, otherwise an
 * outage would silently turn every record into a "problem".
 */
internal fun classifyMaterializeFailure(error: Throwable, applying: Boolean = false): MaterializeFailure? {
    val message = error.message.orEmpty()
    return when {
        error is CancellationException -> null
        error is WebdavObjectNotFoundException -> MaterializeFailure(QuarantineReason.MISSING_OBJECT, message)
        error is IOException -> null
        error is WebdavPrivacyException -> MaterializeFailure(error.reason, message)
        error is UnknownRecordTypeException -> MaterializeFailure(QuarantineReason.UNKNOWN_TYPE, message)
        error is IllegalArgumentException && (message.contains("SHA-256 校验失败") || message.contains("内容校验失败")) -> MaterializeFailure(QuarantineReason.OBJECT_CORRUPT, message)
        error is IllegalArgumentException -> MaterializeFailure(QuarantineReason.INVALID_PAYLOAD, message)
        // Parsing problems surface as these while decoding; while applying they
        // could just as well come from the database, so they are not trusted.
        !applying && (error is IllegalStateException || error is JsonParseException || error is ClassCastException || error is NumberFormatException || error is UnsupportedOperationException) ->
            MaterializeFailure(QuarantineReason.INVALID_PAYLOAD, message)
        else -> null
    }
}

/** One set-aside record, kept (with its raw payload) so it can be retried. */
internal data class QuarantinedRecord(
    val deviceId: String,
    val seq: Long,
    val type: String,
    val id: String,
    val op: String,
    val ver: Long,
    val reason: String,
    val message: String,
    val payload: JsonObject,
    val hash: String? = null,
    /** The payload came out of authenticated decryption (private data). */
    val encrypted: Boolean = false,
    val attempts: Int = 1,
    val firstSeen: Long = System.currentTimeMillis(),
) {
    val key get() = "$deviceId|$seq"
}

/**
 * Remote records this device could not apply, per repository. They are stored
 * before the read position moves past them, so a crash re-reads (idempotent)
 * instead of losing them. More than [MAX_ITEMS] stops the pull rather than
 * hiding real trouble.
 */
internal class WebdavQuarantineStore(private val prefs: SharedPreferences, private val repository: String) {
    companion object { const val MAX_ITEMS = 1000 }

    private val storeKey get() = "webdav-quarantine:$repository"

    fun list(): List<QuarantinedRecord> {
        val root = runCatching { JsonParser.parseString(prefs.getString(storeKey, "{}")).asJsonObject }.getOrDefault(JsonObject())
        return root.entrySet().mapNotNull { (_, value) ->
            runCatching {
                val item = value.asJsonObject
                QuarantinedRecord(
                    deviceId = item["device"].asString, seq = item["seq"].asLong, type = item["type"].asString, id = item["id"].asString,
                    op = item["op"].asString, ver = item["ver"].asLong, reason = item["reason"].asString, message = item["message"].asString,
                    payload = item["payload"].asJsonObject, hash = item["hash"]?.takeIf { it.isJsonPrimitive }?.asString,
                    encrypted = item["encrypted"]?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false,
                    attempts = item["attempts"].asInt, firstSeen = item["firstSeen"].asLong,
                )
            }.getOrNull()
        }.sortedWith(compareBy({ it.firstSeen }, { it.seq }))
    }

    fun count() = list().size

    /** Add or update records; an existing one keeps its first-seen time and counts the attempt. */
    fun put(records: List<QuarantinedRecord>) {
        if (records.isEmpty()) return
        val existing = list().associateBy { it.key }
        val unseen = records.map { it.key }.toSet().count { it !in existing }
        if (existing.size + unseen > MAX_ITEMS) throw IllegalStateException("同步问题过多（超过 $MAX_ITEMS 条），请先在同步设置中处理或删除")
        val merged = existing.toMutableMap()
        for (record in records) {
            val before = existing[record.key]
            merged[record.key] = record.copy(attempts = (before?.attempts ?: 0) + 1, firstSeen = before?.firstSeen ?: record.firstSeen)
        }
        write(merged.values)
    }

    fun remove(keys: Collection<String>) {
        if (keys.isEmpty()) return
        write(list().filter { it.key !in keys.toSet() })
    }

    private fun write(records: Collection<QuarantinedRecord>) {
        val root = JsonObject()
        for (record in records) root.add(record.key, JsonObject().apply {
            addProperty("device", record.deviceId); addProperty("seq", record.seq); addProperty("type", record.type); addProperty("id", record.id)
            addProperty("op", record.op); addProperty("ver", record.ver); addProperty("reason", record.reason); addProperty("message", record.message.take(300))
            add("payload", record.payload); record.hash?.let { addProperty("hash", it) }; if (record.encrypted) addProperty("encrypted", true)
            addProperty("attempts", record.attempts); addProperty("firstSeen", record.firstSeen)
        })
        prefs.edit().putString(storeKey, root.toString()).commit()
    }
}
