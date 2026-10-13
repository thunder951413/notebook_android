package io.github.notebook.android

import io.github.notebook.android.data.NoteSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class TodoRemindersTest {
    private val now = Calendar.getInstance().apply { set(2026, Calendar.OCTOBER, 13, 14, 0, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
    private val minute = 60_000L
    private val hour = 60 * minute
    private val day = 24 * hour
    private val endOfToday = Calendar.getInstance().apply { timeInMillis = now; set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 59); set(Calendar.SECOND, 59); set(Calendar.MILLISECOND, 999) }.timeInMillis

    private fun todo(id: String, dueAt: Long?, completed: Boolean = false, deleted: Boolean = false, reminderAt: Long? = null, type: String = "todo") = NoteSummary(
        id = id, title = id, preview = "", createdAt = 0, updatedAt = 0, folderId = null, folderName = "未分类",
        icon = null, parentPageId = null, sortOrder = 0.0, treeUpdatedAt = 0, reminderAt = reminderAt, recurrence = "none",
        version = 1, tagIds = "", deletedAt = if (deleted) 1 else null, itemType = type, dueAt = dueAt,
        completedAt = if (completed) 1 else null, important = false, dirty = false, conflict = false, lastSyncedVersion = 0,
    )

    @Test fun `badge counts overdue and due today only`() {
        val digest = todoDigest(listOf(
            todo("late", now - 2 * day),
            todo("soon", now + 30 * minute),
            todo("tonight", endOfToday),
            todo("tomorrow", now + day),
            todo("undated", null),
            todo("done", now - hour, completed = true),
            todo("trashed", now - hour, deleted = true),
            todo("note", now - hour, type = "note"),
            todo("reminder-only", null, reminderAt = now + 10 * minute),
        ), now)
        assertEquals(listOf("late"), digest.overdue.map { it.id })
        assertEquals(setOf("soon", "tonight", "reminder-only"), digest.dueToday.map { it.id }.toSet())
        assertEquals(4, digest.badgeCount)
        assertTrue(digest.hasOverdue)
        // Banner: overdue and within two hours, soonest first; end-of-day items wait.
        assertEquals(listOf("late", "reminder-only", "soon"), digest.attention.map { it.id })
    }

    @Test fun `due wording and todo sections`() {
        assertEquals("已逾期 2 天", dueDescription(now - 2 * day, now))
        assertEquals("已逾期 3 小时", dueDescription(now - 3 * hour, now))
        assertEquals("14:40 到期 · 还有 40 分钟", dueDescription(now + 40 * minute, now))
        assertEquals("今天到期", dueDescription(endOfToday, now))
        assertEquals("今天 18:00 到期", dueDescription(now + 4 * hour, now))
        assertEquals("明天 14:00 到期", dueDescription(now + day, now))
        assertEquals("已逾期", todoGroupLabel(todo("a", now - minute), now))
        assertEquals("今天", todoGroupLabel(todo("b", endOfToday), now))
        assertEquals("明天", todoGroupLabel(todo("c", now + day), now))
        assertEquals("7 天内", todoGroupLabel(todo("d", now + 3 * day), now))
        assertEquals("以后", todoGroupLabel(todo("e", now + 30 * day), now))
        assertEquals("无截止日期", todoGroupLabel(todo("f", null), now))
    }

    @Test fun `due ordering puts soonest first and undated last`() {
        val ordered = orderedNotes(listOf(todo("x", null), todo("b", now + day), todo("a", now - day)), NoteOrder.Due, descending = true)
        assertEquals(listOf("a", "b", "x"), ordered.map { it.id })
    }
}
