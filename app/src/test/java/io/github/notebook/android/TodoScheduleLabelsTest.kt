package io.github.notebook.android

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar

class TodoScheduleLabelsTest {
    private fun at(month: Int, day: Int, hour: Int, minute: Int, year: Int = 2026, second: Int = 0) =
        Calendar.getInstance().apply { clear(); set(year, month, day, hour, minute, second) }.timeInMillis
    private val now = at(Calendar.OCTOBER, 20, 14, 0)

    @Test fun `dates read as today, tomorrow or a dated weekday`() {
        assertEquals("今天 18:00", scheduleLabel(at(Calendar.OCTOBER, 20, 18, 0), now, allDay = false))
        assertEquals("明天 09:30", scheduleLabel(at(Calendar.OCTOBER, 21, 9, 30), now, allDay = false))
        assertEquals("昨天 08:00", scheduleLabel(at(Calendar.OCTOBER, 19, 8, 0), now, allDay = false))
        assertEquals("10月23日 周五", scheduleLabel(at(Calendar.OCTOBER, 23, 23, 59, second = 59), now, allDay = true))
        assertEquals("2027年1月3日 周日 07:05", scheduleLabel(at(Calendar.JANUARY, 3, 7, 5, year = 2027), now, allDay = false))
    }

    @Test fun `remaining and overdue time use the largest whole unit`() {
        assertEquals("还有 40 分钟", remainingLabel(now + 40 * 60_000L, now, allDay = false))
        assertEquals("还有 4 小时", remainingLabel(at(Calendar.OCTOBER, 20, 18, 0), now, allDay = false))
        assertEquals("今天内", remainingLabel(at(Calendar.OCTOBER, 20, 23, 59, second = 59), now, allDay = true))
        assertEquals("还有 3 天", remainingLabel(at(Calendar.OCTOBER, 23, 23, 59, second = 59), now, allDay = true))
        assertEquals("已逾期 2 小时", overdueLabel(at(Calendar.OCTOBER, 20, 12, 0), now))
        assertEquals("已逾期 2 天", overdueLabel(at(Calendar.OCTOBER, 18, 13, 0), now))
    }

    @Test fun `repeat rules have readable names`() {
        assertEquals("每周重复", repeatLabel("weekly"))
        assertEquals("不重复", repeatLabel("none"))
    }
}
