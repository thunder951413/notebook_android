package io.github.notebook.android

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.notebook.android.data.AssetEntity
import io.github.notebook.android.data.NoteEntity
import io.github.notebook.android.data.TodoStepEntity
import kotlinx.coroutines.delay
import java.util.Calendar
import java.util.TimeZone
import java.util.UUID

/** Text edits inside a step are saved this long after the last keystroke; checks, splits and removals save at once. */
private const val STEP_TEXT_SAVE_DELAY_MS = 600L
private const val DAY_MS = 24L * 60 * 60 * 1000

private data class StepLine(val id: String, val value: TextFieldValue, val checked: Boolean, val createdAt: Long) {
    fun toEntity(noteId: String) = TodoStepEntity(id, noteId, value.text, checked, 0, createdAt)
}

private fun newStepLine(text: String = "", id: String = UUID.randomUUID().toString()) =
    StepLine(id, TextFieldValue(text), false, System.currentTimeMillis())

private enum class ScheduleKind { Due, Reminder }

/**
 * The todo screen. The todo is its own text, with no separate title. Due date and reminder appear only once set,
 * with their actual time; unset ones are offered as small "add" buttons. Steps form one editing area: typing into
 * the empty row creates a step, Enter starts the next one (splitting at the caret), Ctrl/Shift+Enter on a hardware
 * keyboard breaks the line, and Backspace at the start of a step joins it to the previous one.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TodoDetail(
    note: NoteEntity,
    steps: List<TodoStepEntity>,
    attachments: List<AssetEntity>,
    libraryName: String,
    notificationsAllowed: Boolean,
    onChange: (NoteEntity) -> Unit,
    onSaveSteps: (List<TodoStepEntity>) -> Unit,
    onPickLibrary: () -> Unit,
    onRequestNotifications: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val readOnly = note.deletedAt != null
    val colors = MaterialTheme.colorScheme
    val keyboard = LocalSoftwareKeyboardController.current
    val now by produceState(System.currentTimeMillis()) { while (true) { delay(30_000); value = System.currentTimeMillis() } }
    var dialog by remember { mutableStateOf<ScheduleKind?>(null) }

    var item by remember(note.id) { mutableStateOf(TextFieldValue(note.title, TextRange(note.title.length))) }
    LaunchedEffect(note.title) { if (note.title != item.text) item = TextFieldValue(note.title, TextRange(note.title.length)) }
    var notes by remember(note.id) { mutableStateOf(TextFieldValue(note.body)) }
    LaunchedEffect(note.body) { if (note.body != notes.text) notes = TextFieldValue(note.body) }
    val itemFocus = remember { FocusRequester() }
    val stepEntry = remember { FocusRequester() }
    LaunchedEffect(note.id) { if (note.title.isBlank() && !readOnly) { itemFocus.requestFocus(); keyboard?.show() } }

    Column(
        modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).testTag("todo-detail"),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 8.dp, bottom = 48.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                RoundCheck(
                    checked = note.completedAt != null,
                    size = 28.dp,
                    label = if (note.completedAt != null) "标记为未完成" else "完成待办",
                    enabled = !readOnly,
                    modifier = Modifier.padding(top = 2.dp),
                ) { onChange(note.copy(completedAt = if (note.completedAt == null) System.currentTimeMillis() else null)) }
                PlainField(
                    value = item,
                    placeholder = "要做什么？",
                    style = TextStyle(fontSize = 22.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold, color = if (note.completedAt != null) colors.onSurfaceVariant else colors.onSurface),
                    readOnly = readOnly,
                    modifier = Modifier.weight(1f).padding(top = 5.dp).focusRequester(itemFocus).testTag("todo-item-field"),
                    onValueChange = { next ->
                        if (insertedLineBreak(item, next)) { stepEntry.requestFocus(); return@PlainField }
                        item = next
                        if (next.text != note.title) onChange(note.copy(title = next.text))
                    },
                )
                IconButton({ onChange(note.copy(important = !note.important)) }, enabled = !readOnly, modifier = Modifier.offset(y = (-6).dp)) {
                    Icon(
                        if (note.important) Icons.Default.Star else Icons.Default.StarBorder,
                        if (note.important) "取消重要" else "标为重要",
                        tint = if (note.important) Color(0xFFE8A200) else colors.onSurfaceVariant,
                    )
                }
            }

            ScheduleSection(note, now, readOnly, notificationsAllowed, onOpen = { dialog = it }, onChange = onChange, onOpenNotificationSettings = onOpenNotificationSettings)

            TodoSteps(note.id, steps, readOnly, entry = stepEntry, onSave = onSaveSteps)

            SectionLabel("备注", Modifier.padding(start = 40.dp, top = 28.dp, bottom = 4.dp))
            PlainField(
                value = notes,
                placeholder = "添加备注",
                style = TextStyle(fontSize = 16.sp, lineHeight = 25.sp, color = colors.onSurface),
                readOnly = readOnly,
                modifier = Modifier.fillMaxWidth().padding(start = 40.dp, end = 8.dp).heightIn(min = 48.dp).testTag("todo-notes-field"),
                onValueChange = { next -> notes = next; if (next.text != note.body) onChange(note.copy(body = next.text)) },
            )
            if (attachments.isNotEmpty()) Column(Modifier.padding(start = 40.dp, top = 12.dp)) { attachments.forEach { AttachmentView(it) } }
            Row(Modifier.padding(start = 34.dp, top = 20.dp)) { ReferenceMetaChip(libraryName, Icons.Default.Folder) { if (!readOnly) onPickLibrary() } }
        }
    }

    when (dialog) {
        ScheduleKind.Due -> ScheduleDialog(
            title = "截止时间",
            current = note.dueAt,
            fallback = endOfDay(now),
            allowAllDay = true,
            repeat = null,
            onDismiss = { dialog = null },
        ) { at, _ -> onChange(note.copy(dueAt = at)); dialog = null }
        ScheduleKind.Reminder -> ScheduleDialog(
            title = "提醒",
            current = note.reminderAt,
            fallback = nextWholeHour(now),
            allowAllDay = false,
            repeat = note.recurrence,
            onDismiss = { dialog = null },
        ) { at, rule -> onChange(note.copy(reminderAt = at, recurrence = rule ?: "none")); if (!notificationsAllowed) onRequestNotifications(); dialog = null }
        null -> Unit
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScheduleSection(
    note: NoteEntity,
    now: Long,
    readOnly: Boolean,
    notificationsAllowed: Boolean,
    onOpen: (ScheduleKind) -> Unit,
    onChange: (NoteEntity) -> Unit,
    onOpenNotificationSettings: () -> Unit,
) {
    val done = note.completedAt != null
    Column(Modifier.padding(start = 40.dp, end = 8.dp, top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        note.dueAt?.let { due ->
            val overdue = !done && due < now && !(isEndOfDayAt(due) && startOfToday(due) == startOfToday(now))
            ScheduleRow(
                icon = Icons.Outlined.Event,
                label = "截止时间",
                value = scheduleLabel(due, now, allDay = isEndOfDayAt(due)),
                hint = if (done) null else if (overdue) overdueLabel(due, now) else remainingLabel(due, now, allDay = isEndOfDayAt(due)),
                alert = overdue,
                readOnly = readOnly,
                onClick = { onOpen(ScheduleKind.Due) },
                onClear = { onChange(note.copy(dueAt = null)) },
            )
        }
        note.reminderAt?.let { at ->
            val repeating = note.recurrence != "none"
            ScheduleRow(
                icon = Icons.Outlined.NotificationsNone,
                label = "提醒",
                value = scheduleLabel(at, now, allDay = false),
                hint = when {
                    done -> null
                    repeating -> repeatLabel(note.recurrence)
                    at < now -> "已提醒"
                    else -> remainingLabel(at, now, allDay = false)
                },
                alert = false,
                readOnly = readOnly,
                warning = if (!notificationsAllowed && !done) "通知未开启，提醒不会弹出 · 点此开启" else null,
                onWarning = onOpenNotificationSettings,
                onClick = { onOpen(ScheduleKind.Reminder) },
                onClear = { onChange(note.copy(reminderAt = null, recurrence = "none")) },
            )
        }
        if (!readOnly && (note.dueAt == null || note.reminderAt == null)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (note.dueAt == null) AddScheduleButton("截止时间", Icons.Outlined.Event) { onOpen(ScheduleKind.Due) }
                if (note.reminderAt == null) AddScheduleButton("提醒", Icons.Outlined.NotificationsNone) { onOpen(ScheduleKind.Reminder) }
            }
        }
    }
}

@Composable
private fun ScheduleRow(
    icon: ImageVector,
    label: String,
    value: String,
    hint: String?,
    alert: Boolean,
    readOnly: Boolean,
    onClick: () -> Unit,
    onClear: () -> Unit,
    warning: String? = null,
    onWarning: () -> Unit = {},
) {
    val colors = MaterialTheme.colorScheme
    val accent = if (alert) colors.error else colors.primary
    Surface(shape = RoundedCornerShape(14.dp), color = colors.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(
                Modifier.clickable(enabled = !readOnly, onClick = onClick).padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(icon, null, Modifier.size(22.dp), tint = accent)
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(label, fontSize = 12.sp, lineHeight = 16.sp, color = colors.onSurfaceVariant)
                    Text(value, fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium, color = if (alert) colors.error else colors.onSurface)
                    if (hint != null) Text(hint, fontSize = 13.sp, lineHeight = 18.sp, color = if (alert) colors.error else colors.onSurfaceVariant)
                }
                if (!readOnly) IconButton(onClear) { Icon(Icons.Default.Close, "移除$label", Modifier.size(20.dp), tint = colors.onSurfaceVariant) }
            }
            if (warning != null) Text(
                warning,
                Modifier.fillMaxWidth().clickable(onClick = onWarning).padding(start = 48.dp, end = 14.dp, bottom = 10.dp),
                fontSize = 13.sp,
                lineHeight = 18.sp,
                color = colors.error,
            )
        }
    }
}

@Composable
private fun AddScheduleButton(label: String, icon: ImageVector, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.heightIn(min = 40.dp).clip(RoundedCornerShape(20.dp)).border(1.dp, colors.outlineVariant, RoundedCornerShape(20.dp))
            .clickable(onClick = onClick).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(18.dp), tint = colors.onSurfaceVariant)
        Spacer(Modifier.width(6.dp))
        Text("添加$label", fontSize = 14.sp, color = colors.onSurfaceVariant)
    }
}

@Composable
private fun TodoSteps(noteId: String, stored: List<TodoStepEntity>, readOnly: Boolean, entry: FocusRequester, onSave: (List<TodoStepEntity>) -> Unit) {
    val colors = MaterialTheme.colorScheme
    var lines by remember(noteId) { mutableStateOf(stored.map { StepLine(it.id, TextFieldValue(it.text), it.checked, it.createdAt) }) }
    var draft by remember(noteId) { mutableStateOf(newStepLine()) }
    var dirty by remember(noteId) { mutableStateOf(false) }
    var textEdits by remember(noteId) { mutableIntStateOf(0) }
    var focusedId by remember(noteId) { mutableStateOf<String?>(null) }
    var pendingFocus by remember(noteId) { mutableStateOf<String?>(null) }
    val requesters = remember(noteId) { mutableMapOf<String, FocusRequester>() }

    // Stored steps (initial load, sync) replace the local list only while nothing is being edited; a line whose
    // text did not change keeps its caret.
    LaunchedEffect(stored) {
        if (dirty || focusedId != null) return@LaunchedEffect
        val local = lines.associateBy { it.id }
        lines = stored.map { step ->
            local[step.id]?.takeIf { it.value.text == step.text }?.copy(checked = step.checked)
                ?: StepLine(step.id, TextFieldValue(step.text), step.checked, step.createdAt)
        }
    }
    fun flush() { if (!dirty) return; dirty = false; onSave(lines.map { it.toEntity(noteId) }) }
    fun commit(next: List<StepLine>, immediate: Boolean) { lines = next; dirty = true; if (immediate) flush() else textEdits++ }
    LaunchedEffect(textEdits) { if (textEdits > 0) { delay(STEP_TEXT_SAVE_DELAY_MS); flush() } }
    DisposableEffect(noteId) { onDispose { flush() } }
    LaunchedEffect(pendingFocus) { pendingFocus?.let { id -> requesters[id]?.requestFocus(); pendingFocus = null } }

    fun focus(id: String, caret: Int) {
        if (id == draft.id) draft = draft.copy(value = draft.value.copy(selection = TextRange(caret)))
        else lines = lines.map { if (it.id == id) it.copy(value = it.value.copy(selection = TextRange(caret.coerceAtMost(it.value.text.length)))) else it }
        pendingFocus = id
    }

    fun change(index: Int, next: TextFieldValue) {
        if (index == lines.size) {
            if (next.text.isEmpty()) { draft = draft.copy(value = next); return }
            if (insertedLineBreak(draft.value, next)) return
            // The draft row keeps its key and becomes this step, so focus and input-method composition carry over.
            commit(lines + draft.copy(value = next), immediate = true)
            draft = newStepLine()
            return
        }
        val line = lines[index]
        if (insertedLineBreak(line.value, next)) {
            val text = line.value.text
            val before = text.substring(0, line.value.selection.min)
            val after = text.substring(line.value.selection.max)
            if (after.isEmpty() && index == lines.lastIndex) { focus(draft.id, 0); return }
            val inserted = newStepLine(after)
            commit(lines.take(index) + line.copy(value = TextFieldValue(before, TextRange(before.length))) + inserted + lines.drop(index + 1), immediate = true)
            focus(inserted.id, 0)
            return
        }
        commit(lines.map { if (it.id == line.id) it.copy(value = next) else it }, immediate = next.text == line.value.text)
    }

    /** Hardware keys a soft keyboard does not send: Ctrl/Shift+Enter breaks the line, Backspace at the start joins steps. */
    fun key(index: Int, event: KeyEvent): Boolean {
        if (event.type != KeyEventType.KeyDown) return false
        val line = lines.getOrNull(index)
        val value = line?.value ?: draft.value
        if ((event.key == Key.Enter || event.key == Key.NumPadEnter) && (event.isCtrlPressed || event.isShiftPressed || event.isMetaPressed)) {
            val start = value.selection.min
            val broken = TextFieldValue(value.text.substring(0, start) + "\n" + value.text.substring(value.selection.max), TextRange(start + 1))
            if (line == null) { if (broken.text.isNotBlank()) { commit(lines + draft.copy(value = broken), immediate = true); draft = newStepLine() } }
            else commit(lines.map { if (it.id == line.id) it.copy(value = broken) else it }, immediate = false)
            return true
        }
        if (event.key == Key.Backspace && line != null && value.selection.collapsed && value.selection.start == 0) {
            val previous = lines.getOrNull(index - 1)
            if (previous == null && line.value.text.isNotEmpty()) return false
            if (previous != null) {
                val joined = previous.value.text + line.value.text
                commit(lines.take(index - 1) + previous.copy(value = TextFieldValue(joined, TextRange(previous.value.text.length))) + lines.drop(index + 1), immediate = true)
                pendingFocus = previous.id
            } else {
                commit(lines.drop(1), immediate = true)
                focus(lines.firstOrNull()?.id ?: draft.id, 0)
            }
            return true
        }
        return false
    }

    val total = lines.count { it.value.text.isNotBlank() }
    val finished = lines.count { it.checked && it.value.text.isNotBlank() }
    SectionLabel(if (total > 0) "步骤  $finished/$total" else "步骤", Modifier.padding(start = 40.dp, top = 28.dp, bottom = 2.dp))
    Column(Modifier.fillMaxWidth().testTag("todo-steps")) {
        val rows = if (readOnly) lines else lines + draft
        rows.forEachIndexed { index, line ->
            key(line.id) {
                val isDraft = line.id == draft.id
                val requester = requesters.getOrPut(line.id) { FocusRequester() }
                Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.Top) {
                    Box(Modifier.width(40.dp).padding(top = 7.dp), contentAlignment = Alignment.TopCenter) {
                        RoundCheck(
                            checked = line.checked,
                            size = 22.dp,
                            label = if (isDraft) "新步骤" else "完成步骤 ${line.value.text}",
                            enabled = !isDraft && !readOnly,
                            faint = isDraft,
                        ) { commit(lines.map { if (it.id == line.id) it.copy(checked = !it.checked) else it }, immediate = true) }
                    }
                    PlainField(
                        value = line.value,
                        placeholder = if (isDraft) "添加步骤" else "",
                        style = TextStyle(
                            fontSize = 17.sp,
                            lineHeight = 25.sp,
                            color = if (line.checked) colors.onSurfaceVariant else colors.onSurface,
                            textDecoration = if (line.checked) TextDecoration.LineThrough else null,
                        ),
                        readOnly = readOnly,
                        modifier = Modifier.weight(1f).padding(top = 9.dp, bottom = 9.dp).focusRequester(requester)
                            .then(if (isDraft) Modifier.focusRequester(entry) else Modifier)
                            .onFocusChanged { state -> if (state.isFocused) focusedId = line.id else if (focusedId == line.id) { focusedId = null; flush() } }
                            .onPreviewKeyEvent { key(index, it) }
                            .testTag(if (isDraft) "todo-step-draft" else "todo-step-${line.id}"),
                        onValueChange = { change(index, it) },
                    )
                    Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                        if (!isDraft && !readOnly && focusedId == line.id) IconButton({ commit(lines.filter { it.id != line.id }, immediate = true) }) {
                            Icon(Icons.Default.Close, "删除步骤", Modifier.size(18.dp), tint = colors.onSurfaceVariant)
                        }
                    }
                }
                if (!isDraft) HorizontalDivider(Modifier.padding(start = 40.dp, end = 12.dp), color = colors.outlineVariant.copy(alpha = .5f))
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun PlainField(
    value: TextFieldValue,
    placeholder: String,
    style: TextStyle,
    readOnly: Boolean,
    modifier: Modifier,
    onValueChange: (TextFieldValue) -> Unit,
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        readOnly = readOnly,
        textStyle = style,
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        decorationBox = { inner ->
            Box {
                if (value.text.isEmpty() && placeholder.isNotEmpty()) Text(placeholder, style = style.copy(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .7f), textDecoration = null))
                inner()
            }
        },
    )
}

@Composable
private fun RoundCheck(
    checked: Boolean,
    size: androidx.compose.ui.unit.Dp,
    label: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    faint: Boolean = false,
    onToggle: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier.size(size + 12.dp).clip(CircleShape).clickable(enabled = enabled, onClick = onToggle).semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(size).clip(CircleShape)
                .then(if (checked) Modifier.background(colors.primary) else Modifier.border(2.dp, colors.outline.copy(alpha = if (faint) .35f else 1f), CircleShape)),
            contentAlignment = Alignment.Center,
        ) { if (checked) Icon(Icons.Default.Check, null, Modifier.size(size * .7f), tint = colors.onPrimary) }
    }
}

/** True when [next] is [previous] with exactly one line break typed at the caret (the Enter key), not a paste. */
private fun insertedLineBreak(previous: TextFieldValue, next: TextFieldValue): Boolean {
    if (next.composition != null) return false
    val start = previous.selection.min
    return next.text == previous.text.substring(0, start) + "\n" + previous.text.substring(previous.selection.max)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ScheduleDialog(
    title: String,
    current: Long?,
    fallback: Long,
    allowAllDay: Boolean,
    repeat: String?,
    onDismiss: () -> Unit,
    onConfirm: (Long, String?) -> Unit,
) {
    val base = current ?: fallback
    val local = remember(base) { Calendar.getInstance().apply { timeInMillis = base } }
    // DatePicker works in UTC days; hand it the local calendar date at UTC midnight.
    val date = rememberDatePickerState(initialSelectedDateMillis = utcMidnight(local))
    val time = rememberTimePickerState(local.get(Calendar.HOUR_OF_DAY), local.get(Calendar.MINUTE), true)
    var allDay by remember { mutableStateOf(allowAllDay && isEndOfDayAt(base)) }
    var rule by remember { mutableStateOf(repeat ?: "none") }
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            Modifier.fillMaxWidth().widthIn(max = 420.dp).heightIn(max = (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp - 72).dp).padding(horizontal = 8.dp),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Column(Modifier.padding(vertical = 16.dp)) {
                // Everything but the buttons scrolls on short screens; the buttons stay visible.
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                    Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
                    DatePicker(date, title = null, headline = null, showModeToggle = false, colors = DatePickerDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh))
                    if (allowAllDay) Row(Modifier.fillMaxWidth().clickable { allDay = !allDay }.padding(horizontal = 24.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("只设日期，不设具体时间", Modifier.weight(1f), fontSize = 15.sp)
                        Switch(allDay, { allDay = it })
                    }
                    if (!allDay) Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) { TimePicker(time) }
                    if (repeat != null) {
                        Text("重复", fontWeight = FontWeight.Medium, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
                            REPEAT_CHOICES.forEachIndexed { i, (value, label) ->
                                SegmentedButton(selected = rule == value, onClick = { rule = value }, shape = SegmentedButtonDefaults.itemShape(i, REPEAT_CHOICES.size)) { Text(label) }
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 16.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onDismiss) { Text("取消") }
                    Spacer(Modifier.width(8.dp))
                    Button({
                        val day = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = date.selectedDateMillis ?: utcMidnight(local) }
                        val chosen = Calendar.getInstance().apply {
                            clear()
                            set(day.get(Calendar.YEAR), day.get(Calendar.MONTH), day.get(Calendar.DAY_OF_MONTH))
                            if (allDay) { set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 59); set(Calendar.SECOND, 59); set(Calendar.MILLISECOND, 999) }
                            else { set(Calendar.HOUR_OF_DAY, time.hour); set(Calendar.MINUTE, time.minute) }
                        }
                        onConfirm(chosen.timeInMillis, if (repeat != null) rule else null)
                    }) { Text("确定") }
                }
            }
        }
    }
}

private val REPEAT_CHOICES = listOf("none" to "一次", "daily" to "每天", "weekly" to "每周", "monthly" to "每月")

private fun utcMidnight(local: Calendar): Long =
    Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
        clear(); set(local.get(Calendar.YEAR), local.get(Calendar.MONTH), local.get(Calendar.DAY_OF_MONTH))
    }.timeInMillis

private fun isEndOfDayAt(timestamp: Long): Boolean =
    Calendar.getInstance().apply { timeInMillis = timestamp }.let { it.get(Calendar.HOUR_OF_DAY) == 23 && it.get(Calendar.MINUTE) == 59 }

private fun endOfDay(now: Long): Long = startOfToday(now) + DAY_MS - 1

private fun nextWholeHour(now: Long): Long =
    Calendar.getInstance().apply { timeInMillis = now + 60 * 60_000L; set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis

private val WEEKDAYS = arrayOf("周日", "周一", "周二", "周三", "周四", "周五", "周六")

/** "今天 18:00", "明天", "10月21日 周二 09:30", "2027年1月3日 周日"; [allDay] leaves out the clock. */
internal fun scheduleLabel(timestamp: Long, now: Long, allDay: Boolean): String {
    val value = Calendar.getInstance().apply { timeInMillis = timestamp }
    val current = Calendar.getInstance().apply { timeInMillis = now }
    val dayOffset = Math.floorDiv(startOfToday(timestamp) - startOfToday(now) + DAY_MS / 2, DAY_MS)
    val day = when (dayOffset) {
        0L -> "今天"
        1L -> "明天"
        -1L -> "昨天"
        else -> {
            val weekday = WEEKDAYS[value.get(Calendar.DAY_OF_WEEK) - 1]
            val monthDay = "${value.get(Calendar.MONTH) + 1}月${value.get(Calendar.DAY_OF_MONTH)}日"
            if (value.get(Calendar.YEAR) == current.get(Calendar.YEAR)) "$monthDay $weekday" else "${value.get(Calendar.YEAR)}年$monthDay $weekday"
        }
    }
    return if (allDay) day else "$day %02d:%02d".format(value.get(Calendar.HOUR_OF_DAY), value.get(Calendar.MINUTE))
}

/** "还有 40 分钟", "还有 5 小时", "还有 3 天"; an all-day date counts whole days. */
internal fun remainingLabel(timestamp: Long, now: Long, allDay: Boolean): String {
    val left = timestamp - now
    val days = Math.floorDiv(startOfToday(timestamp) - startOfToday(now) + DAY_MS / 2, DAY_MS)
    return when {
        allDay && days == 0L -> "今天内"
        allDay || left >= DAY_MS -> "还有 $days 天"
        left >= 60 * 60_000L -> "还有 ${left / (60 * 60_000L)} 小时"
        else -> "还有 ${(left / 60_000).coerceAtLeast(1)} 分钟"
    }
}

/** "已逾期 15 分钟", "已逾期 3 小时", "已逾期 2 天". */
internal fun overdueLabel(timestamp: Long, now: Long): String {
    val late = now - timestamp
    return when {
        late < 60 * 60_000L -> "已逾期 ${(late / 60_000).coerceAtLeast(1)} 分钟"
        late < DAY_MS -> "已逾期 ${late / (60 * 60_000L)} 小时"
        else -> "已逾期 ${late / DAY_MS} 天"
    }
}

internal fun repeatLabel(rule: String): String = when (rule) {
    "daily" -> "每天重复"
    "weekly" -> "每周重复"
    "monthly" -> "每月重复"
    else -> "不重复"
}
