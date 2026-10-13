package io.github.notebook.android.sync

import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * In-memory view of which notes live in private (encrypted) folders.
 *
 * The data is stored in EncryptedSharedPreferences, where every read decrypts
 * and parses JSON. List rows and the editor ask "is this note private?" for
 * every note on every recomposition, so reading prefs directly made typing and
 * scrolling stutter on large libraries. Reads now hit an immutable snapshot;
 * every writer goes through [update] (or the change listener invalidates it).
 */
class PrivateNoteIndex(private val prefs: SharedPreferences, private val gson: Gson = Gson()) {
    data class Snapshot(val noteIds: Set<String>, val mappings: Map<String, String>) {
        fun isPrivate(noteId: String) = noteId in noteIds || noteId in mappings
        fun folderOf(noteId: String, folderId: String?) = mappings[noteId] ?: folderId?.takeIf { noteId in noteIds }
    }

    @Volatile private var cached: Snapshot? = null
    // Held as a field: SharedPreferences keeps listeners only weakly.
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null || key == NOTE_IDS || key == MAPPINGS) cached = null
    }

    init {
        runCatching { prefs.registerOnSharedPreferenceChangeListener(listener) }
    }

    fun snapshot(): Snapshot = cached ?: load().also { cached = it }

    fun invalidate() { cached = null }

    @Synchronized
    fun update(transform: (Snapshot) -> Snapshot) {
        val next = transform(load())
        prefs.edit()
            .putString(NOTE_IDS, gson.toJson(next.noteIds.toTypedArray()))
            .putString(MAPPINGS, gson.toJson(JsonObject().apply { next.mappings.forEach { (key, value) -> addProperty(key, value) } }))
            .apply()
        cached = next
    }

    private fun load(): Snapshot {
        val ids = runCatching { gson.fromJson(prefs.getString(NOTE_IDS, "[]"), Array<String>::class.java)?.toSet() }.getOrNull() ?: emptySet()
        val mappings = runCatching {
            JsonParser.parseString(prefs.getString(MAPPINGS, "{}")).asJsonObject.entrySet()
                .mapNotNull { (key, value) -> value.takeIf { it.isJsonPrimitive }?.asString?.let { key to it } }.toMap()
        }.getOrNull() ?: emptyMap()
        return Snapshot(ids, mappings)
    }

    companion object {
        const val NOTE_IDS = "encryptedNoteIds"
        const val MAPPINGS = "encryptedMappings"
    }
}
