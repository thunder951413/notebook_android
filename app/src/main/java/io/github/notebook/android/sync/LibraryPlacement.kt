package io.github.notebook.android.sync

import io.github.notebook.android.data.ApiNotebookEntity
import io.github.notebook.android.data.FolderEntity

/**
 * The synced notebook a library created on the phone belongs to: the one most
 * existing libraries are in, else the first known notebook. Only with nothing
 * synced yet does it fall back to the local default "personal" (which the
 * desktop app adopts into its main notebook).
 */
fun notebookForNewLibrary(folders: List<FolderEntity>, notebooks: List<ApiNotebookEntity>): String {
    val live = notebooks.filter { it.deletedAt == null && !it.id.startsWith("private-") }
    val liveIds = live.map { it.id }.toSet()
    folders.filter { it.type == "noteFolder" && it.notebookId in liveIds }
        .groupingBy { it.notebookId }.eachCount()
        .maxByOrNull { it.value }?.key?.let { return it }
    return live.minByOrNull { it.sortOrder }?.id ?: "personal"
}
