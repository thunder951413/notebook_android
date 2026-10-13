package io.github.notebook.android.sync

/**
 * When to talk to 坚果云.
 *
 * 坚果云 allows 600 WebDAV requests per 30 minutes for free accounts (1500
 * paid), shared by every device. A sync costs roughly 3–10 requests, so
 * syncing after every pause in typing can exhaust it. Like other WebDAV note
 * apps, edits are saved locally at once and uploaded at natural boundaries:
 *
 * - leaving a note, or the app going to the background: upload, but automatic
 *   uploads are at least [MIN_INTERVAL_MS] apart;
 * - a long editing session: at most one upload per [EDIT_MAX_WAIT_MS];
 * - returning to the app: download when the last sync is older than
 *   [FOREGROUND_STALE_MS];
 * - this device stays under [AUTO_REQUEST_BUDGET] requests per window so the
 *   other devices keep room; a manual sync is never held back.
 */
internal object SyncPacing {
    const val MIN_INTERVAL_MS = 60_000L
    const val EDIT_MAX_WAIT_MS = 5 * 60_000L
    const val FOREGROUND_STALE_MS = 2 * 60_000L
    const val BUDGET_WINDOW_MS = 30 * 60_000L
    const val AUTO_REQUEST_BUDGET = 240

    /** Delay before an automatic upload so runs stay [MIN_INTERVAL_MS] apart. */
    fun boundaryDelay(now: Long, lastAutoRunAt: Long): Long =
        (lastAutoRunAt + MIN_INTERVAL_MS - now).coerceIn(0L, MIN_INTERVAL_MS)

    fun foregroundPullDue(now: Long, lastSuccessAt: Long?): Boolean =
        lastSuccessAt == null || now - lastSuccessAt >= FOREGROUND_STALE_MS || now < lastSuccessAt

    /**
     * 0 when an automatic sync may run now; otherwise how long until enough
     * old requests leave the window.
     */
    fun budgetDelay(now: Long, requestTimes: List<Long>): Long {
        val recent = requestTimes.filter { now - it in 0 until BUDGET_WINDOW_MS }.sorted()
        if (recent.size < AUTO_REQUEST_BUDGET) return 0L
        // Wait until the window holds a little headroom again (one sync's worth).
        val freeing = recent[(recent.size - AUTO_REQUEST_BUDGET + 9).coerceIn(0, recent.size - 1)]
        return (freeing + BUDGET_WINDOW_MS - now).coerceAtLeast(60_000L)
    }
}

/** Every WebDAV request this process sent recently (each retry counts). */
internal object WebdavRequestLog {
    private val times = ArrayDeque<Long>()

    @Synchronized fun record(now: Long = System.currentTimeMillis()) {
        times.addLast(now)
        while (times.isNotEmpty() && now - times.first() > SyncPacing.BUDGET_WINDOW_MS) times.removeFirst()
    }

    @Synchronized fun snapshot(): List<Long> = times.toList()

    @Synchronized fun recentCount(now: Long = System.currentTimeMillis()): Int = times.count { now - it < SyncPacing.BUDGET_WINDOW_MS }

    @Synchronized internal fun clear() = times.clear()
}
