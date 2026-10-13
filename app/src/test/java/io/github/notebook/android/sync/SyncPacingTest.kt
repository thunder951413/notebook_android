package io.github.notebook.android.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncPacingTest {
    private val now = 10_000_000_000L

    @Test fun automaticUploadsStayAMinuteApart() {
        assertEquals(0L, SyncPacing.boundaryDelay(now, 0L))
        assertEquals(0L, SyncPacing.boundaryDelay(now, now - 90_000))
        assertEquals(40_000L, SyncPacing.boundaryDelay(now, now - 20_000))
        // A clock that jumped backwards never yields more than one interval.
        assertEquals(SyncPacing.MIN_INTERVAL_MS, SyncPacing.boundaryDelay(now, now + 3_600_000))
    }

    @Test fun foregroundDownloadsOnlyWhenStale() {
        assertTrue(SyncPacing.foregroundPullDue(now, null))
        assertFalse(SyncPacing.foregroundPullDue(now, now - 30_000))
        assertTrue(SyncPacing.foregroundPullDue(now, now - SyncPacing.FOREGROUND_STALE_MS))
        assertTrue(SyncPacing.foregroundPullDue(now, now + 60_000))
    }

    @Test fun requestBudgetPostponesUntilHeadroomReturns() {
        val underBudget = List(SyncPacing.AUTO_REQUEST_BUDGET - 1) { now - it * 1_000L }
        assertEquals(0L, SyncPacing.budgetDelay(now, underBudget))
        // Requests older than the window do not count.
        val old = List(1_000) { now - SyncPacing.BUDGET_WINDOW_MS - 1 - it }
        assertEquals(0L, SyncPacing.budgetDelay(now, old))
        // 300 requests, one every 5 s: wait until only budget-10 remain recent.
        val burst = List(300) { now - it * 5_000L }
        val wait = SyncPacing.budgetDelay(now, burst)
        val remaining = burst.count { it + SyncPacing.BUDGET_WINDOW_MS > now + wait }
        assertTrue("remaining=$remaining", remaining <= SyncPacing.AUTO_REQUEST_BUDGET - 10)
        assertTrue(wait >= 60_000L)
    }

    @Test fun requestLogKeepsOnlyTheWindow() {
        WebdavRequestLog.clear()
        WebdavRequestLog.record(now - SyncPacing.BUDGET_WINDOW_MS - 5_000)
        WebdavRequestLog.record(now - 1_000)
        WebdavRequestLog.record(now)
        assertEquals(2, WebdavRequestLog.recentCount(now))
        WebdavRequestLog.clear()
    }
}
