package io.github.notebook.android.sync

import com.google.gson.JsonParser
import io.github.notebook.android.data.NoteSummary

/**
 * The desktop represents each library (a legacy section) by one ordinary page that carries
 * `legacyMetadata.notebookRootSectionId`; the library's notes hang under it. On Android the library
 * itself is that level, so the root page is not listed as a note and its children are the library's
 * top-level notes.
 *
 * @param payloadJson the stored remote page payload.
 * @returns the library (section) id the page stands for, or null when it is not a live library root.
 */
internal fun libraryRootSectionId(payloadJson: String): String? {
    val page = runCatching { JsonParser.parseString(payloadJson).asJsonObject }.getOrNull() ?: return null
    if (page.has("deletedAt") && !page["deletedAt"].isJsonNull) return null
    val marker = page["legacyMetadata"]?.takeIf { it.isJsonObject }?.asJsonObject?.get("notebookRootSectionId")
        ?.takeIf { it.isJsonPrimitive }?.asString ?: return null
    val section = page["sectionId"]?.takeIf { it.isJsonPrimitive }?.asString
    return marker.takeIf { it == section }
}

/**
 * Leaves library root pages out of a note list. A root page that has its own text stays listed,
 * so that text remains reachable.
 *
 * @param rootPageIds the root page ids by library id, from [libraryRootSectionId].
 */
internal fun withoutLibraryRoots(notes: List<NoteSummary>, rootPageIds: Map<String, String>): List<NoteSummary> {
    if (rootPageIds.isEmpty()) return notes
    return notes.filter { note -> !(note.folderId != null && rootPageIds[note.folderId] == note.id && note.preview.isBlank()) }
}
