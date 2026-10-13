package io.github.notebook.android

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AlarmOn
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.EventBusy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.notebook.android.data.NoteSummary
import java.util.Calendar

/**
 * Due-date attention for todos.
 *
 * - The todo button badge counts only *actionable* items (overdue + due today),
 *   the way Reminders / TickTick / Things badge "Today": counting every open
 *   todo produces a permanently large number that stops meaning anything.
 * - A banner at the top of the home screen previews todos that are overdue or
 *   due within [SOON_WINDOW_MS]. Complete / snooze / open act directly on it;
 *   snoozing and dismissing are device-local and never touch synced data.
 */
internal const val SOON_WINDOW_MS = 2 * 60 * 60 * 1000L
private const val DAY_MS = 24L * 60 * 60 * 1000

internal data class TodoDigest(
    val overdue: List<NoteSummary>,
    val dueToday: List<NoteSummary>,
    /** Overdue or due soon, soonest first; candidates for the banner. */
    val attention: List<NoteSummary>,
) {
    val badgeCount get() = overdue.size + dueToday.size
    val hasOverdue get() = overdue.isNotEmpty()
}

/** The moment a todo needs attention: its due time, or the reminder when no due time is set. */
internal fun NoteSummary.attentionAt(): Long? = dueAt ?: reminderAt

internal fun NoteSummary.isOpenTodo() = itemType == "todo" && deletedAt == null && completedAt == null

internal fun todoDigest(notes: List<NoteSummary>, now: Long = System.currentTimeMillis()): TodoDigest {
    val endOfToday = startOfToday(now) + DAY_MS
    val open = notes.filter { it.isOpenTodo() && it.attentionAt() != null }
    val overdue = open.filter { it.attentionAt()!! < now && !isEndOfDayToday(it.attentionAt()!!, now) }
    val overdueIds = overdue.mapTo(HashSet()) { it.id }
    val dueToday = open.filter { it.id !in overdueIds && it.attentionAt()!! < endOfToday }
    val attention = open.filter { it.attentionAt()!! <= now + SOON_WINDOW_MS }.sortedBy { it.attentionAt() }
    return TodoDigest(overdue.sortedBy { it.attentionAt() }, dueToday.sortedBy { it.attentionAt() }, attention)
}

/** "Due today" todos are stored at 23:59:59; they are not overdue during the day. */
private fun isEndOfDayToday(timestamp: Long, now: Long): Boolean {
    val endOfToday = startOfToday(now) + DAY_MS
    return timestamp in (endOfToday - 60_000) until endOfToday && now < endOfToday
}

private fun isEndOfDay(timestamp: Long): Boolean {
    val value = Calendar.getInstance().apply { timeInMillis = timestamp }
    return value.get(Calendar.HOUR_OF_DAY) == 23 && value.get(Calendar.MINUTE) == 59
}

/** Human wording: "已逾期 2 天", "15:00 到期 · 还有 40 分钟", "今天到期". */
internal fun dueDescription(timestamp: Long, now: Long = System.currentTimeMillis()): String {
    val clock = Calendar.getInstance().apply { timeInMillis = timestamp }
        .let { "%02d:%02d".format(it.get(Calendar.HOUR_OF_DAY), it.get(Calendar.MINUTE)) }
    val today = startOfToday(now)
    return when {
        timestamp < now -> {
            val late = now - timestamp
            when {
                late < 60 * 60_000L -> "已逾期 ${(late / 60_000).coerceAtLeast(1)} 分钟"
                late < DAY_MS -> "已逾期 ${late / (60 * 60_000L)} 小时"
                else -> "已逾期 ${late / DAY_MS} 天"
            }
        }
        isEndOfDay(timestamp) && timestamp < today + DAY_MS -> "今天到期"
        timestamp - now < 60 * 60_000L -> "$clock 到期 · 还有 ${((timestamp - now) / 60_000).coerceAtLeast(1)} 分钟"
        timestamp < today + DAY_MS -> "今天 $clock 到期"
        timestamp < today + 2 * DAY_MS -> if (isEndOfDay(timestamp)) "明天到期" else "明天 $clock 到期"
        else -> "${relativeDateLabel(timestamp, now)} 到期"
    }
}

/** Todo list sections in due order (Todoist / TickTick style). */
internal fun todoGroupLabel(note: NoteSummary, now: Long = System.currentTimeMillis()): String {
    val at = note.attentionAt() ?: return "无截止日期"
    val today = startOfToday(now)
    return when {
        at < now && !isEndOfDayToday(at, now) -> "已逾期"
        at < today + DAY_MS -> "今天"
        at < today + 2 * DAY_MS -> "明天"
        at < today + 7 * DAY_MS -> "7 天内"
        else -> "以后"
    }
}

/** Device-local snooze / dismiss state for the banner. */
internal class TodoBannerState(context: Context) {
    private val preferences = context.getSharedPreferences("todo-banner", Context.MODE_PRIVATE)
    fun hidden(note: NoteSummary, now: Long = System.currentTimeMillis()): Boolean {
        if (preferences.getLong("snooze:${note.id}", 0L) > now) return true
        // Dismissal holds until the due time changes.
        return preferences.getLong("dismiss:${note.id}", Long.MIN_VALUE) == (note.attentionAt() ?: 0L)
    }
    fun snooze(id: String, untilMs: Long) { preferences.edit().putLong("snooze:$id", untilMs).apply() }
    fun dismiss(note: NoteSummary) { preferences.edit().putLong("dismiss:${note.id}", note.attentionAt() ?: 0L).apply() }
}

@Composable
internal fun DueSoonBanner(
    items: List<NoteSummary>,
    now: Long,
    onOpen: (String) -> Unit,
    onComplete: (NoteSummary) -> Unit,
    onSnooze: (NoteSummary) -> Unit,
    onDismiss: (NoteSummary) -> Unit,
    onShowAll: () -> Unit,
) {
    val first = items.firstOrNull()
    AnimatedVisibility(first != null, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
        val note = first ?: return@AnimatedVisibility
        val at = note.attentionAt() ?: now
        val late = at < now && todoGroupLabel(note, now) == "已逾期"
        Surface(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).testTag("due-soon-banner"),
            shape = RoundedCornerShape(22.dp),
            color = if (late) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.tertiaryContainer,
            tonalElevation = 2.dp,
            shadowElevation = 2.dp,
        ) {
            Column(Modifier.clickable { onOpen(note.id) }.padding(start = 16.dp, end = 6.dp, top = 10.dp, bottom = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (late) Icons.Default.EventBusy else Icons.Default.AlarmOn, null, Modifier.size(20.dp),
                        tint = if (late) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary,
                    )
                    Column(Modifier.weight(1f).padding(start = 10.dp)) {
                        Text(note.title.ifBlank { "无标题待办" }, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            dueDescription(at, now) + if (items.size > 1) " · 另有 ${items.size - 1} 项" else "",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton({ onDismiss(note) }, Modifier.size(36.dp)) { Icon(Icons.Default.Close, "不再提示这一项", Modifier.size(18.dp)) }
                }
                Row(Modifier.fillMaxWidth().padding(start = 22.dp), horizontalArrangement = Arrangement.End) {
                    if (items.size > 1) TextButton(onShowAll) { Text("查看全部") }
                    TextButton({ onSnooze(note) }) { Text("1 小时后提醒") }
                    TextButton({ onComplete(note) }, Modifier.testTag("due-soon-complete")) { Text("完成", fontWeight = FontWeight.SemiBold) }
                }
            }
        }
    }
}
