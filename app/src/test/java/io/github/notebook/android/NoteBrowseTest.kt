package io.github.notebook.android

import io.github.notebook.android.data.NoteSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class NoteBrowseTest {
    private fun summary(id: String, title: String, updatedAt: Long = 0L, folderName: String = "未分类") = NoteSummary(
        id = id, title = title, preview = "", createdAt = updatedAt, updatedAt = updatedAt, folderId = null, folderName = folderName,
        icon = null, parentPageId = null, sortOrder = 0.0, treeUpdatedAt = updatedAt, reminderAt = null, recurrence = "none",
        version = 1, tagIds = "", deletedAt = null, itemType = "note", dueAt = null, completedAt = null, important = false,
        dirty = false, conflict = false, lastSyncedVersion = 0,
    )

    @Test fun `query parser splits keywords phrases exclusions and tags`() {
        val query = NoteSearchQuery.parse("""会议  "产品 路线图" -草稿 #工作 ＃周报 -"旧版 计划"""")
        assertEquals(listOf("会议", "产品 路线图"), query.terms)
        assertEquals(listOf("草稿", "旧版 计划"), query.excluded)
        assertEquals(listOf("工作", "周报"), query.tags)
        assertTrue(NoteSearchQuery.parse("   ").isEmpty)
        assertEquals(listOf("a", "b"), NoteSearchQuery.parse("a - b").terms)
    }

    @Test fun `ranking puts exact and prefix title matches before body hits, newest first within a tier`() {
        val notes = listOf(
            summary("body-old", "无关标题", 1),
            summary("body-new", "另一篇", 5),
            summary("prefix", "会议记录 9 月", 2),
            summary("exact", "会议", 0),
            summary("contains", "周一的会议", 3),
        ).associateBy { it.id }
        val ranked = rankSearchHits(notes.keys, notes, NoteSearchQuery.parse("会议"))
        assertEquals(listOf("exact", "prefix", "contains", "body-new", "body-old"), ranked)
    }

    @Test fun `date sections follow Apple Notes style buckets`() {
        val now = Calendar.getInstance().apply { set(2026, Calendar.OCTOBER, 13, 15, 0, 0) }.timeInMillis
        val day = 24L * 60 * 60 * 1000
        assertEquals("今天", dateGroupLabel(now - 60_000, now))
        assertEquals("昨天", dateGroupLabel(now - day, now))
        assertEquals("最近 7 天", dateGroupLabel(now - 4 * day, now))
        assertEquals("最近 30 天", dateGroupLabel(now - 20 * day, now))
        assertEquals("6 月", dateGroupLabel(Calendar.getInstance().apply { set(2026, Calendar.JUNE, 2, 9, 0) }.timeInMillis, now))
        assertEquals("2025 年 12 月", dateGroupLabel(Calendar.getInstance().apply { set(2025, Calendar.DECEMBER, 2, 9, 0) }.timeInMillis, now))
        assertTrue(TimeFilter.Today.accepts(now - 60_000, now))
        assertTrue(!TimeFilter.Today.accepts(now - day, now))
        assertTrue(TimeFilter.Week.accepts(now - 6 * day, now))
    }

    @Test fun `pinyin full spelling and initials find Chinese titles`() {
        val table = mapOf('三' to "san", '星' to "xing", '会' to "hui", '议' to "yi")
        PinyinMatcher.install { value -> value.map { table[it] ?: it.toString() }.joinToString("") }
        try {
            assertTrue(PinyinMatcher.matches("三星 SdigitalCity", "sx"))
            assertTrue(PinyinMatcher.matches("三星 SdigitalCity", "sanxing"))
            assertTrue(PinyinMatcher.matches("周会议", "hy"))
            assertTrue(!PinyinMatcher.matches("三星", "x"))
            assertTrue(!PinyinMatcher.matches("English only", "eo"))
            val notes = listOf(summary("a", "会议纪要", 1), summary("b", "三星项目", 2)).associateBy { it.id }
            assertEquals(listOf("b", "a"), rankSearchHits(notes.keys, notes, NoteSearchQuery.parse("sx")))
        } finally {
            PinyinMatcher.install(null)
        }
    }

    @Test fun `swipe action follows the item type and state`() {
        val note = summary("n", "笔记")
        val todo = note.copy(itemType = "todo")
        assertEquals(RowSwipe.Delete, rowSwipe(note, selecting = false))
        assertEquals(RowSwipe.Complete, rowSwipe(todo, selecting = false))
        assertEquals(RowSwipe.Reopen, rowSwipe(todo.copy(completedAt = 1L), selecting = false))
        assertEquals(RowSwipe.None, rowSwipe(note.copy(deletedAt = 1L), selecting = false))
        assertEquals(RowSwipe.None, rowSwipe(note, selecting = true))
    }
}
