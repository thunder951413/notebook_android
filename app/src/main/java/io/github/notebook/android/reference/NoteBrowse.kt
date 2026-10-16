package io.github.notebook.android

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import kotlinx.coroutines.launch
import io.github.notebook.android.data.NoteSummary
import java.util.Calendar

/**
 * Search and browsing helpers for large libraries.
 *
 * Patterns adopted from established note apps:
 * - several keywords narrow results (AND), "quoted phrases" stay together,
 *   -word excludes and #tag filters by tag (Bear search syntax);
 * - one-tap type/time filter chips while searching (Google Keep, Apple Notes);
 * - recent searches and recently opened notes before typing (Bear Quick Open,
 *   Obsidian quick switcher);
 * - pinned notes above everything and date sections (Apple Notes);
 * - a compact list density to see many notes at once.
 */
internal data class NoteSearchQuery(
    val terms: List<String>,
    val excluded: List<String>,
    val tags: List<String>,
) {
    val isEmpty get() = terms.isEmpty() && excluded.isEmpty() && tags.isEmpty()
    /** Words worth highlighting in titles and snippets. */
    val highlights get() = terms

    companion object {
        fun parse(raw: String): NoteSearchQuery {
            val terms = mutableListOf<String>()
            val excluded = mutableListOf<String>()
            val tags = mutableListOf<String>()
            val pattern = Regex("(-?)\"([^\"]+)\"|(\\S+)")
            for (match in pattern.findAll(raw.trim())) {
                val phrase = match.groupValues[2]
                if (phrase.isNotBlank()) {
                    if (match.groupValues[1] == "-") excluded += phrase.trim() else terms += phrase.trim()
                    continue
                }
                val token = match.groupValues[3]
                when {
                    token.length > 1 && token.startsWith("-") -> excluded += token.drop(1)
                    token.length > 1 && (token.startsWith("#") || token.startsWith("＃")) -> tags += token.drop(1)
                    token.isNotBlank() && token != "-" -> terms += token
                }
            }
            return NoteSearchQuery(terms.distinct(), excluded.distinct(), tags.distinct())
        }
    }
}

internal enum class TimeFilter(val label: String, val days: Int) {
    All("任何时间", 0),
    Today("今天", 1),
    Week("7 天内", 7),
    Month("30 天内", 30),
}

internal fun startOfToday(now: Long = System.currentTimeMillis()): Long =
    Calendar.getInstance().apply {
        timeInMillis = now
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

internal fun TimeFilter.accepts(timestamp: Long, now: Long = System.currentTimeMillis()): Boolean =
    this == TimeFilter.All || timestamp >= startOfToday(now) - (days - 1) * DAY_MS

private const val DAY_MS = 24L * 60 * 60 * 1000

/** Apple Notes style section label for a timestamp. */
internal fun dateGroupLabel(timestamp: Long, now: Long = System.currentTimeMillis()): String {
    val today = startOfToday(now)
    return when {
        timestamp >= today -> "今天"
        timestamp >= today - DAY_MS -> "昨天"
        timestamp >= today - 6 * DAY_MS -> "最近 7 天"
        timestamp >= today - 29 * DAY_MS -> "最近 30 天"
        else -> {
            val value = Calendar.getInstance().apply { timeInMillis = timestamp }
            val current = Calendar.getInstance().apply { timeInMillis = now }
            if (value.get(Calendar.YEAR) == current.get(Calendar.YEAR)) "${value.get(Calendar.MONTH) + 1} 月"
            else "${value.get(Calendar.YEAR)} 年 ${value.get(Calendar.MONTH) + 1} 月"
        }
    }
}

/** Short, scannable time for list rows. */
internal fun relativeDateLabel(timestamp: Long, now: Long = System.currentTimeMillis()): String {
    val today = startOfToday(now)
    val value = Calendar.getInstance().apply { timeInMillis = timestamp }
    val time = "%02d:%02d".format(value.get(Calendar.HOUR_OF_DAY), value.get(Calendar.MINUTE))
    val current = Calendar.getInstance().apply { timeInMillis = now }
    return when {
        timestamp >= today -> time
        timestamp >= today - DAY_MS -> "昨天 $time"
        value.get(Calendar.YEAR) == current.get(Calendar.YEAR) -> "${value.get(Calendar.MONTH) + 1}月${value.get(Calendar.DAY_OF_MONTH)}日"
        else -> "${value.get(Calendar.YEAR)}/${value.get(Calendar.MONTH) + 1}/${value.get(Calendar.DAY_OF_MONTH)}"
    }
}

/** Highlight every query word (case-insensitive) in [value]. */
internal fun highlightedTerms(value: String, terms: List<String>, color: Color): AnnotatedString =
    buildAnnotatedString {
        append(value)
        for (term in terms) {
            if (term.isBlank()) continue
            var from = 0
            while (from < value.length) {
                val found = value.indexOf(term, from, true)
                if (found < 0) break
                addStyle(SpanStyle(color = color, fontWeight = FontWeight.SemiBold), found, found + term.length)
                from = found + term.length
            }
        }
    }

/** Rank search hits: title matches first, then folder/tag/body; newer first within a tier. */
internal fun rankSearchHits(
    ids: Collection<String>,
    notes: Map<String, NoteSummary>,
    query: NoteSearchQuery,
): List<String> {
    fun score(note: NoteSummary): Int {
        val title = note.title
        var score = 0
        for ((index, term) in query.terms.withIndex()) {
            when {
                title.equals(term, true) -> score += 400
                title.startsWith(term, true) -> score += if (index == 0) 250 else 120
                title.contains(term, true) -> score += 100
                PinyinMatcher.matches(title, term) -> score += 90
                note.folderName.contains(term, true) -> score += 20
            }
        }
        if (query.terms.isNotEmpty() && query.terms.all { title.contains(it, true) }) score += 150
        if (note.important) score += 5
        return score
    }
    return ids.mapNotNull(notes::get)
        .sortedWith(compareByDescending<NoteSummary> { score(it) }.thenByDescending { it.updatedAt })
        .map { it.id }
}

/** Shown in search before typing: recent queries and recently opened notes. */
@Composable
internal fun SearchStartPanel(
    recentSearches: List<String>,
    recentNotes: List<NoteSummary>,
    onSearch: (String) -> Unit,
    onClearSearches: () -> Unit,
    onOpen: (String) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        if (recentSearches.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("最近搜索", Modifier.weight(1f), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClearSearches) { Text("清除", fontSize = 13.sp) }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                recentSearches.forEach { value ->
                    SuggestionChip({ onSearch(value) }, { Text(value, maxLines = 1) }, icon = { Icon(Icons.Default.History, null, Modifier.size(16.dp)) })
                }
            }
            Spacer(Modifier.height(12.dp))
        }
        if (recentNotes.isNotEmpty()) {
            Text("最近打开", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 6.dp))
            recentNotes.forEach { note ->
                Row(
                    Modifier.fillMaxWidth().clickable { onOpen(note.id) }.padding(vertical = 10.dp).testTag("recent-note-${note.id}"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.Description, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(note.title.ifBlank { "无标题" }, Modifier.weight(1f).padding(start = 12.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(relativeDateLabel(note.updatedAt), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            "提示：多个关键词用空格分隔；\"引号\" 精确匹配；-词语 排除；#标签 按标签筛选。",
            fontSize = 12.sp,
            lineHeight = 17.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** One-tap filters while searching (type and time). */
@Composable
internal fun SearchFilterChips(
    content: ContentFilter,
    time: TimeFilter,
    onContent: (ContentFilter) -> Unit,
    onTime: (TimeFilter) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TimeFilter.entries.drop(1).forEach { value ->
            FilterChip(time == value, { onTime(if (time == value) TimeFilter.All else value) }, { Text(value.label) },
                leadingIcon = if (time == value) ({ Icon(Icons.Default.Check, null, Modifier.size(16.dp)) }) else null)
        }
        ContentFilter.entries.drop(1).forEach { value ->
            FilterChip(content == value, { onContent(if (content == value) ContentFilter.All else value) }, { Text(value.label) },
                leadingIcon = if (content == value) ({ Icon(Icons.Default.Check, null, Modifier.size(16.dp)) }) else null)
        }
    }
}

/** Sticky section header for pinned / date groups. */
@Composable
internal fun NoteSectionHeader(label: String, count: Int? = null) {
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 6.dp, end = 6.dp, top = 10.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (label == "置顶") Icon(Icons.Default.PushPin, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.tertiary)
            Text(label, Modifier.padding(start = if (label == "置顶") 4.dp else 0.dp), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
            count?.let { Text("  $it", fontSize = 12.sp, color = MaterialTheme.colorScheme.outline) }
        }
    }
}

/** Dense row: many notes per screen for large libraries. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun CompactNoteRow(
    note: NoteSummary,
    preview: String,
    terms: List<String>,
    pinned: Boolean,
    selecting: Boolean,
    selected: Boolean,
    path: String?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val accent = MaterialTheme.colorScheme.tertiary
    Surface(
        Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick).testTag("note-row-${note.id}"),
        shape = RoundedCornerShape(14.dp),
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            if (selecting) Checkbox(selected, { onClick() }, Modifier.size(32.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (pinned) Icon(Icons.Default.PushPin, "已置顶", Modifier.size(13.dp).padding(end = 2.dp), tint = accent)
                    Text(
                        highlightedTerms(note.title.ifBlank { "无标题" }, terms, accent),
                        Modifier.weight(1f), fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    val due = note.attentionAt()?.takeIf { note.isOpenTodo() }
                    Text(
                        due?.let { dueDescription(it) } ?: relativeDateLabel(note.updatedAt), Modifier.padding(start = 8.dp), fontSize = 11.sp,
                        color = if (due != null && due < System.currentTimeMillis() && todoGroupLabel(note) == "已逾期") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                val secondary = path?.takeIf { it.isNotBlank() }?.let { "$it · $preview" } ?: preview
                if (secondary.isNotBlank())
                    Text(
                        highlightedTerms(secondary.replace('\n', ' '), terms, accent),
                        fontSize = 12.sp, lineHeight = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
            }
        }
    }
}

/**
 * Pinyin matching for Chinese titles, as Chinese note and contact apps do:
 * "sx" or "sanxing" finds “三星”. Uses the platform ICU transliterator
 * (Android 10+); older systems and JVM tests simply fall back to plain text.
 */
internal object PinyinMatcher {
    private data class Keys(val full: String, val initials: String)
    private val cache = java.util.concurrent.ConcurrentHashMap<String, Keys>()
    @Volatile private var transliterate: ((String) -> String)? = null
    @Volatile private var unavailable = false
    private val han = Regex("[\\u3400-\\u9fff]")
    private val asciiTerm = Regex("^[A-Za-z]{2,}$")

    /** Test hook and lazy platform binding. */
    internal fun install(function: ((String) -> String)?) {
        transliterate = function
        unavailable = false
        cache.clear()
    }

    private fun converter(): ((String) -> String)? {
        transliterate?.let { return it }
        if (unavailable || android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) return null
        return runCatching { icuTransliterator() }
            .onSuccess { transliterate = it }
            .getOrElse {
                unavailable = true
                null
            }
    }

    @androidx.annotation.RequiresApi(android.os.Build.VERSION_CODES.Q)
    private fun icuTransliterator(): (String) -> String {
        val icu = android.icu.text.Transliterator.getInstance("Han-Latin; Latin-ASCII; Lower")
        return { value -> synchronized(icu) { icu.transliterate(value) } }
    }

    private fun keys(text: String): Keys? {
        if (!han.containsMatchIn(text)) return null
        cache[text]?.let { return it }
        val convert = converter() ?: return null
        // One transliteration call per title: Han characters joined by spaces
        // keep exact syllable boundaries; fall back per character if needed.
        val hanChars = text.filter { han.matches(it.toString()) }
        val joined = runCatching { convert(hanChars.toList().joinToString(" ")).trim().split(Regex("\\s+")) }.getOrNull()
        val pinyin = if (joined != null && joined.size == hanChars.length) joined
            else hanChars.map { char -> runCatching { convert(char.toString()).trim() }.getOrDefault("") }
        var hanIndex = 0
        val syllables = text.mapNotNull { char ->
            when {
                han.matches(char.toString()) -> pinyin.getOrNull(hanIndex++)?.takeIf { it.isNotEmpty() }
                char.isLetterOrDigit() -> char.lowercaseChar().toString()
                else -> null
            }
        }
        val result = Keys(syllables.joinToString(""), syllables.joinToString("") { it.take(1) })
        if (cache.size > 20_000) cache.clear()
        cache[text] = result
        return result
    }

    /** True when an ASCII keyword matches the title's pinyin or its initials. */
    fun matches(title: String, term: String): Boolean {
        if (!asciiTerm.matches(term)) return false
        val keys = keys(title) ?: return false
        val needle = term.lowercase()
        return keys.initials.contains(needle) || keys.full.contains(needle)
    }
}

/**
 * Right-edge fast scroller for long lists (Contacts / Photos style): drag the
 * thumb to move through hundreds of notes; a bubble shows the date section.
 */
@Composable
internal fun BoxScope.NoteFastScroller(
    state: androidx.compose.foundation.lazy.LazyListState,
    labelForKey: (Any) -> String?,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val density = androidx.compose.ui.platform.LocalDensity.current
    val total by remember(state) { derivedStateOf { state.layoutInfo.totalItemsCount } }
    if (total < 40) return
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    var trackHeightPx by remember { mutableIntStateOf(1) }
    val scrolling = state.isScrollInProgress
    var recentlyActive by remember { mutableStateOf(false) }
    LaunchedEffect(scrolling, dragging) {
        if (scrolling || dragging) recentlyActive = true
        else { kotlinx.coroutines.delay(1_200); recentlyActive = false }
    }
    val alpha by androidx.compose.animation.core.animateFloatAsState(if (recentlyActive || dragging) 1f else 0f, label = "fast-scroller")
    if (alpha <= 0f) return
    val fraction by remember(state) {
        derivedStateOf {
            val info = state.layoutInfo
            val count = info.totalItemsCount
            if (count <= 1) 0f
            else {
                val visible = info.visibleItemsInfo.size.coerceAtLeast(1)
                (state.firstVisibleItemIndex.toFloat() / (count - visible).coerceAtLeast(1)).coerceIn(0f, 1f)
            }
        }
    }
    val shown = if (dragging) dragFraction else fraction
    val thumbHeight = 44.dp
    val thumbHeightPx = with(density) { thumbHeight.toPx() }
    val label by remember(state) {
        derivedStateOf { state.layoutInfo.visibleItemsInfo.firstNotNullOfOrNull { labelForKey(it.key) } }
    }
    fun jump(y: Float) {
        dragFraction = ((y - thumbHeightPx / 2) / (trackHeightPx - thumbHeightPx).coerceAtLeast(1f)).coerceIn(0f, 1f)
        val target = (dragFraction * (state.layoutInfo.totalItemsCount - 1)).toInt().coerceAtLeast(0)
        scope.launch { state.scrollToItem(target) }
    }
    Box(
        modifier
            .align(Alignment.CenterEnd)
            .fillMaxHeight()
            // Narrow strip: card menus near the edge stay tappable.
            .width(22.dp)
            .padding(vertical = 8.dp)
            .alpha(alpha)
            .onSizeChanged { trackHeightPx = it.height.coerceAtLeast(1) }
            .pointerInput(state) {
                detectVerticalDragGestures(
                    onDragStart = { offset -> dragging = true; jump(offset.y) },
                    onDragEnd = { dragging = false },
                    onDragCancel = { dragging = false },
                ) { change, _ -> change.consume(); jump(change.position.y) }
            }
            .testTag("fast-scroller"),
    ) {
        val offsetPx = (shown * (trackHeightPx - thumbHeightPx)).toInt()
        Surface(
            Modifier.align(Alignment.TopEnd).offset { IntOffset(0, offsetPx) }.padding(end = 3.dp).size(6.dp, thumbHeight),
            shape = RoundedCornerShape(3.dp),
            color = if (dragging) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .55f),
        ) {}
        if (dragging && label != null)
            Surface(
                Modifier.align(Alignment.TopEnd)
                    .offset { IntOffset(-with(density) { 30.dp.roundToPx() }, (offsetPx + thumbHeightPx / 2 - with(density) { 20.dp.toPx() }).toInt()) }
                    .wrapContentWidth(unbounded = true, align = Alignment.End),
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.inverseSurface,
                shadowElevation = 4.dp,
            ) {
                Text(label!!, Modifier.padding(horizontal = 16.dp, vertical = 9.dp), color = MaterialTheme.colorScheme.inverseOnSurface, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            }
    }
}

/** Search start: jump straight to a tag (Bear/Keep-style label shortcuts). */
@Composable
internal fun SearchTagShortcuts(tags: List<Pair<String, Int>>, onTag: (String) -> Unit) {
    if (tags.isEmpty()) return
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        Text("按标签查找", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp, bottom = 6.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            tags.forEach { (name, count) ->
                AssistChip({ onTag(name) }, { Text("#$name  $count", maxLines = 1) })
            }
        }
    }
}

/**
 * Swipe an open todo to the left to complete it. The row is only swipeable
 * while [enabled]; if saving fails the row springs back.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SwipeToCompleteRow(enabled: Boolean, onComplete: suspend () -> Boolean, content: @Composable () -> Unit) {
    if (!enabled) {
        content()
        return
    }
    val scope = rememberCoroutineScope()
    lateinit var state: SwipeToDismissBoxState
    state = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) {
                scope.launch { if (!onComplete()) state.reset() }
                true
            } else false
        },
        positionalThreshold = { distance -> distance * .4f },
    )
    SwipeToDismissBox(
        state,
        modifier = Modifier.testTag("swipe-complete"),
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            Box(
                Modifier.fillMaxSize().clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.primaryContainer).padding(end = 20.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text("完成", Modifier.padding(start = 6.dp), color = MaterialTheme.colorScheme.onPrimaryContainer, fontWeight = FontWeight.Medium)
                }
            }
        },
    ) { content() }
}
