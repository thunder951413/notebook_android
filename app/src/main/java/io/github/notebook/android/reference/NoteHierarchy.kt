package io.github.notebook.android

import android.content.Context
import io.github.notebook.android.data.NoteEntity
import io.github.notebook.android.data.NoteSummary
import org.json.JSONArray

internal data class HierarchyRow(val note: NoteSummary, val depth: Int)

/** A display index over accessible summaries. Never repairs persisted relationships implicitly. */
internal class NoteHierarchyIndex(notes: List<NoteSummary>) {
    val byId = notes.filter { it.itemType == "note" && it.deletedAt == null }.associateBy { it.id }
    private val parents =
        byId
            .mapValues { (_, n) ->
                n.parentPageId?.takeIf { id ->
                    id != n.id && byId[id]?.folderId == n.folderId && id in byId
                }
            }
            .toMutableMap()
    val children: Map<String?, List<NoteSummary>>
    val counts: Map<String, Int>
    private val pathCache = mutableMapOf<String, List<NoteSummary>>()

    init {
        val finished = mutableSetOf<String>()
        for (id in byId.keys.sorted()) {
            val trail = mutableSetOf<String>()
            var cursor: String? = id
            while (cursor != null && cursor !in finished) {
                if (!trail.add(cursor)) {
                    parents[cursor] = null
                    break
                }
                cursor = parents[cursor]
            }
            finished.addAll(trail)
        }
        children =
            byId.values
                .groupBy { parents[it.id] }
                .mapValues { (_, bucket) ->
                    bucket.sortedWith(
                        compareBy<NoteSummary> { it.sortOrder }.thenBy { it.title }.thenBy { it.id }
                    )
                }
        val totals = byId.keys.associateWith { 0 }.toMutableMap()
        val preorder = rows(byId.keys)
        for (row in preorder.asReversed()) parents[row.note.id]?.let { parent ->
            totals[parent] = totals.getValue(parent) + 1 + totals.getValue(row.note.id)
        }
        counts = totals
    }

    fun parent(id: String) = parents[id]?.let(byId::get)

    fun ancestors(id: String): List<NoteSummary> =
        pathCache.getOrPut(id) {
            val result = mutableListOf<NoteSummary>()
            var cursor = parents[id]
            while (cursor != null) {
                byId[cursor]?.let(result::add)
                cursor = parents[cursor]
            }
            result.asReversed()
        }

    fun path(id: String, includeSelf: Boolean = true): String {
        val n = byId[id] ?: return ""
        return (listOf(n.folderName.ifBlank { "未分类" }) +
                ancestors(id).map { it.title.ifBlank { "无标题" } } +
                if (includeSelf) listOf(n.title.ifBlank { "无标题" }) else emptyList())
            .joinToString(" › ")
    }

    fun descendants(id: String): Set<String> {
        val result = mutableSetOf<String>()
        val queue = ArrayDeque<String>().apply { add(id) }
        while (queue.isNotEmpty()) children[queue.removeFirst()].orEmpty().forEach {
            if (result.add(it.id)) queue.add(it.id)
        }
        return result
    }

    fun rows(
        expanded: Set<String>,
        folderId: String? = null,
        scoped: Boolean = false,
    ): List<HierarchyRow> {
        val result = mutableListOf<HierarchyRow>()
        val stack = ArrayDeque<HierarchyRow>()
        children[null]
            .orEmpty()
            .filter { !scoped || it.folderId == folderId }
            .asReversed()
            .forEach { stack.addLast(HierarchyRow(it, 0)) }
        while (stack.isNotEmpty()) {
            val row = stack.removeLast()
            result.add(row)
            if (row.note.id in expanded)
                children[row.note.id].orEmpty().asReversed().forEach {
                    stack.addLast(HierarchyRow(it, row.depth + 1))
                }
        }
        return result
    }

    fun search(
        query: String,
        folderId: String? = null,
        scoped: Boolean = false,
    ): List<NoteSummary> {
        val terms = query.trim().lowercase().split(Regex("\\s+")).filter(String::isNotBlank)
        if (terms.isEmpty()) return emptyList()
        return byId.values
            .asSequence()
            .filter { !scoped || it.folderId == folderId }
            .mapNotNull { n ->
                val title = n.title.lowercase()
                val path = path(n.id).lowercase()
                if (!terms.all { it in path || it in n.preview.lowercase() }) null
                else
                    n to
                        when {
                            title == query.trim().lowercase() -> 0
                            title.startsWith(query.trim().lowercase()) -> 1
                            terms.all { it in title } -> 2
                            terms.all { it in path } -> 3
                            else -> 4
                        }
            }
            .sortedWith(
                compareBy<Pair<NoteSummary, Int>> { it.second }
                    .thenByDescending { it.first.updatedAt }
                    .thenBy { it.first.id }
            )
            .map { it.first }
            .take(100)
            .toList()
    }
}

internal class NoteNavigationHistory(context: Context) {
    private val preferences = context.getSharedPreferences("note-navigation", Context.MODE_PRIVATE)

    private fun read(key: String): List<String> =
        runCatching {
                val a = JSONArray(preferences.getString(key, "[]"))
                (0 until a.length()).map { a.getString(it) }
            }
            .getOrDefault(emptyList())

    private fun write(key: String, ids: List<String>) {
        preferences.edit().putString(key, JSONArray(ids).toString()).apply()
    }

    fun recent() = read("recent")

    fun pinned() = read("pinned")

    fun visited(id: String) {
        write("recent", (listOf(id) + recent().filter { it != id }).take(40))
    }

    /** Pin (newest first) or unpin several notes at once. */
    fun setPinned(ids: Collection<String>, pinned: Boolean): List<String> {
        val old = pinned()
        val next = if (pinned) ids.filter { it !in old } + old else old.filter { it !in ids }
        write("pinned", next)
        return next
    }

    fun togglePin(id: String): List<String> {
        val old = pinned()
        val next = if (id in old) old - id else listOf(id) + old
        write("pinned", next)
        return next
    }
}

internal fun NoteEntity.hierarchySummary() =
    NoteSummary(
        id,
        title,
        previewText,
        createdAt,
        updatedAt,
        folderId,
        folderName,
        icon,
        parentPageId,
        sortOrder,
        treeUpdatedAt,
        reminderAt,
        recurrence,
        version,
        tagIds,
        deletedAt,
        itemType,
        dueAt,
        completedAt,
        important,
        dirty,
        conflict,
        lastSyncedVersion,
    )
