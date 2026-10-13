@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package io.github.notebook.android

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import io.github.notebook.android.data.*
import io.github.notebook.android.sync.SyncRepository
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

internal enum class NoteOrder(val label: String) {
    Updated("修改时间"),
    Created("创建时间"),
    Title("标题"),
    Due("截止时间"),
}

internal enum class ContentFilter(val label: String) {
    All("全部类型"),
    Image("图片"),
    Audio("录音"),
    Video("视频"),
    File("文件"),
    Link("链接"),
}

internal enum class FilingFilter(val label: String) {
    All("全部笔记"),
    Filed("已分类"),
    Unfiled("未分类"),
}

internal fun orderedNotes(
    notes: List<NoteSummary>,
    order: NoteOrder,
    descending: Boolean,
): List<NoteSummary> {
    val comparator =
        when (order) {
            NoteOrder.Updated -> compareBy<NoteSummary> { it.updatedAt }
            NoteOrder.Created -> compareBy { it.createdAt }
            NoteOrder.Title -> compareBy { it.title.lowercase() }
            // Soonest first, undated last, regardless of the direction toggle.
            NoteOrder.Due -> return notes.sortedWith(compareBy<NoteSummary> { it.attentionAt() ?: Long.MAX_VALUE }.thenByDescending { it.updatedAt }.thenBy { it.id })
        }
    return notes.sortedWith(
        if (descending) comparator.reversed().thenBy { it.id } else comparator.thenBy { it.id }
    )
}

internal fun highlighted(value: String, query: String, color: Color): AnnotatedString =
    buildAnnotatedString {
        append(value)
        if (query.isNotBlank()) {
            var from = 0
            while (from < value.length) {
                val found = value.indexOf(query, from, true)
                if (found < 0) break
                addStyle(SpanStyle(color = color), found, found + query.length)
                from = found + query.length
            }
        }
    }

internal fun cardPreview(value: String): String {
    val clean =
        NotebookAssetSyntax.withoutImages(value)
            .replace(Regex("\\s*\\(notebook-asset:[^)]*\\)?"), "")
    return MarkdownVisualTransformation(Color.Black).filter(AnnotatedString(clean)).text.text
}

@Composable
internal fun ReferenceHome(
    notes: List<NoteSummary>,
    all: List<NoteSummary>,
    folders: List<FolderEntity>,
    tags: List<TagEntity>,
    repo: SyncRepository,
    destination: Destination,
    folderId: String?,
    tagId: String?,
    query: String,
    onQuery: (String) -> Unit,
    /** Ranked result ids while searching (null = not searching / still loading). */
    searchOrder: List<String>? = null,
    searchSnippets: Map<String, String> = emptyMap(),
    searchTerms: List<String> = emptyList(),
    loadingId: String?,
    status: String?,
    syncing: Boolean,
    error: String?,
    onOpen: (String) -> Unit,
    onCreate: () -> Unit,
    onSelect: (Destination, String?, String?) -> Unit,
    onNavigation: () -> Unit,
    onSettings: () -> Unit,
    onSync: () -> Unit,
    onImport: () -> Unit,
    onStatus: (String) -> Unit,
    onCardBounds: (Rect) -> Unit = {},
    tabletEditor: (@Composable () -> Unit)? = null,
    encryptedUnlocked: Boolean = false,
    onCreateChild: (String) -> Unit = {},
    onHierarchyOpen: (String) -> Unit = onOpen,
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var hierarchy by rememberSaveable { mutableStateOf(false) }
    var hierarchyAnchor by rememberSaveable { mutableStateOf<String?>(null) }
    val hierarchyNotes = remember(all, encryptedUnlocked) { all.filter { canExposeNote(it, encryptedUnlocked, repo::isEncrypted) } }
    val hierarchyIndex = remember(hierarchyNotes) { NoteHierarchyIndex(hierarchyNotes) }
    if (hierarchy)
        ReferenceHierarchySheet(
            hierarchyNotes,
            folders,
            repo,
            currentId = hierarchyAnchor,
            folderId = folderId,
            scoped = folderId != null || destination == Destination.Unfiled,
            onDismiss = { hierarchy = false },
            onOpen = onHierarchyOpen,
            onCreateChild = onCreateChild,
            onStatus = onStatus,
        )
    var search by rememberSaveable { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var filters by remember { mutableStateOf(false) }
    var filterChild by remember { mutableStateOf<String?>(null) }
    var createMenu by remember { mutableStateOf(false) }
    val createTransition = remember { MutableTransitionState(false) }
    createTransition.targetState = createMenu
    val homeBlur by
        animateDpAsState(if (createMenu) 12.dp else 0.dp, tween(180), label = "create-blur")
    val todoContext =
        destination in setOf(Destination.Today, Destination.Important, Destination.Todos) ||
            folders.firstOrNull { it.id == folderId }?.type == "todoList"
    var order by rememberSaveable(todoContext) { mutableStateOf(if (todoContext) NoteOrder.Due else NoteOrder.Updated) }
    var descending by rememberSaveable { mutableStateOf(true) }
    var content by rememberSaveable { mutableStateOf(ContentFilter.All) }
    var filing by rememberSaveable { mutableStateOf(FilingFilter.All) }
    var selecting by rememberSaveable { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var libraryEditor by remember { mutableStateOf<FolderEntity?>(null) }
    var createLibrary by remember { mutableStateOf(false) }
    var deleteLibrary by remember { mutableStateOf<FolderEntity?>(null) }
    var moving by remember { mutableStateOf(false) }
    var existingPicker by remember { mutableStateOf(false) }
    var confirmAction by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val preferences = remember(context) { context.getSharedPreferences("editor_preferences", 0) }
    val navigation = remember(context) { NoteNavigationHistory(context) }
    var pinnedIds by remember { mutableStateOf(navigation.pinned()) }
    LaunchedEffect(hierarchy) { if (!hierarchy) pinnedIds = navigation.pinned() }
    var compact by remember { mutableStateOf(preferences.getBoolean("homeCompact", false)) }
    var time by rememberSaveable { mutableStateOf(TimeFilter.All) }
    var recentSearches by remember { mutableStateOf(repo.recentSearches()) }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = remember { FocusRequester() }
    val listStates =
        rememberSaveable(
            saver =
                Saver<MutableMap<String, LazyListState>, List<Any>>(
                    save = { states ->
                        states.flatMap { (key, state) ->
                            listOf(
                                key,
                                state.firstVisibleItemIndex,
                                state.firstVisibleItemScrollOffset,
                            )
                        }
                    },
                    restore = { values ->
                        values
                            .chunked(3)
                            .associate { row ->
                                row[0] as String to LazyListState(row[1] as Int, row[2] as Int)
                            }
                            .toMutableMap()
                    },
                )
        ) {
            mutableMapOf<String, LazyListState>()
        }
    val routeKey =
        "${destination.name}:${folderId.orEmpty()}:${tagId.orEmpty()}:${if(search)"search" else "list"}"
    val listState = listStates.getOrPut(routeKey) { LazyListState() }
    val globalHome = destination == Destination.All && folderId == null && tagId == null
    val librariesVisible = tab == 1 && globalHome
    val isTodo =
        destination.newItemType == "todo" ||
            folders.firstOrNull { it.id == folderId }?.type == "todoList"
    val libraryState = rememberLazyListState()
    val folder = folders.firstOrNull { it.id == folderId }
    val linkIds by repo.linkNoteIds.collectAsState(initial = emptyList())
    val assets by repo.allAssets.collectAsState(initial = emptyList())
    val publicLibraries = folders.filter { it.type == "noteFolder" }.sortedBy { it.sortOrder }
    // Precomputed once per attachment change: per-row lookups used to scan
    // every attachment for every note on each recomposition.
    val imageByNote = remember(assets) { assets.filter { it.kind == "image" }.groupBy { it.noteId }.mapValues { it.value.first() } }
    val notesByKind = remember(assets) {
        mapOf(
            ContentFilter.Image to assets.filter { it.kind == "image" }.mapTo(HashSet()) { it.noteId },
            ContentFilter.Audio to assets.filter { it.kind == "audio" }.mapTo(HashSet()) { it.noteId },
            ContentFilter.Video to assets.filter { it.mimeType.startsWith("video/") }.mapTo(HashSet()) { it.noteId },
            ContentFilter.File to assets.filter { it.kind == "file" && !it.mimeType.startsWith("video/") }.mapTo(HashSet()) { it.noteId },
        )
    }
    val linkSet = remember(linkIds) { linkIds.toHashSet() }
    val visible =
        remember(notes, order, descending, content, filing, notesByKind, linkSet, time, searchOrder) {
            val filtered = notes.filter { note ->
                (filing == FilingFilter.All ||
                    if (filing == FilingFilter.Unfiled) note.folderId == null
                    else note.folderId != null) &&
                    (content == ContentFilter.All ||
                        if (content == ContentFilter.Link) note.id in linkSet
                        else notesByKind[content]?.contains(note.id) == true) &&
                    time.accepts(note.updatedAt)
            }
            if (searchOrder != null) {
                val rank = HashMap<String, Int>(searchOrder.size).apply { searchOrder.forEachIndexed { index, id -> put(id, index) } }
                filtered.sortedBy { rank[it.id] ?: Int.MAX_VALUE }
            } else orderedNotes(filtered, order, descending)
        }
    val highlightTerms = if (searchTerms.isNotEmpty()) searchTerms else listOfNotNull(query.trim().takeIf { it.isNotEmpty() })
    val pinnedSet = remember(pinnedIds) { pinnedIds.toHashSet() }
    val pinnedVisible = if (search) emptyList() else visible.filter { it.id in pinnedSet }.sortedBy { pinnedIds.indexOf(it.id) }
    val unpinnedVisible = if (search || pinnedVisible.isEmpty()) visible else visible.filter { it.id !in pinnedSet }
    val dateSections =
        remember(unpinnedVisible, order, search) {
            if (search || order == NoteOrder.Title) emptyList()
            else if (order == NoteOrder.Due) {
                val now = System.currentTimeMillis()
                val sections = mutableListOf<Pair<String, MutableList<NoteSummary>>>()
                for (note in unpinnedVisible) {
                    val label = todoGroupLabel(note, now)
                    if (sections.lastOrNull()?.first == label) sections.last().second += note
                    else sections += label to mutableListOf(note)
                }
                sections
            }
            else {
                val sections = mutableListOf<Pair<String, MutableList<NoteSummary>>>()
                for (note in unpinnedVisible) {
                    val label = dateGroupLabel(if (order == NoteOrder.Created) note.createdAt else note.updatedAt)
                    if (sections.lastOrNull()?.first == label) sections.last().second += note
                    else sections += label to mutableListOf(note)
                }
                sections
            }
        }
    LaunchedEffect(search) { if (search) recentSearches = repo.recentSearches() }
    // Fast-scroller bubble: which section a lazy-list key belongs to.
    val sectionByKey =
        remember(pinnedVisible, dateSections, unpinnedVisible) {
            HashMap<Any, String>().apply {
                pinnedVisible.forEach { put("pinned-${it.id}", "置顶") }
                put("header-pinned", "置顶")
                if (dateSections.isNotEmpty())
                    dateSections.forEach { (label, group) ->
                        put("header-$label", label)
                        group.forEach { put(it.id, label) }
                    }
                else unpinnedVisible.forEach { note -> put(note.id, note.title.trim().take(1).uppercase().ifBlank { "#" }) }
            }
        }
    val tagShortcuts =
        remember(all, tags) {
            val counts = HashMap<String, Int>()
            all.forEach { note -> if (note.deletedAt == null) note.tagIds.split(',').forEach { id -> if (id.isNotBlank()) counts[id] = (counts[id] ?: 0) + 1 } }
            tags.mapNotNull { tag -> counts[tag.id]?.let { tag.name to it } }.sortedByDescending { it.second }.take(12)
        }
    var clock by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(30_000)
            clock = System.currentTimeMillis()
        }
    }
    val todos = remember(hierarchyNotes, clock) { todoDigest(hierarchyNotes, clock) }
    val bannerState = remember(context) { TodoBannerState(context) }
    var bannerRevision by remember { mutableIntStateOf(0) }
    val bannerItems = remember(todos, bannerRevision, clock) { todos.attention.filterNot { bannerState.hidden(it, clock) } }
    val libraryCounts =
        remember(all) {
            HashMap<String, Int>().apply {
                all.forEach { note -> if (note.deletedAt == null && note.folderId != null && !repo.isEncrypted(note)) put(note.folderId, (get(note.folderId) ?: 0) + 1) }
            }
        }
    LaunchedEffect(visible.map { it.id }) {
        selected = selected.intersect(visible.map { it.id }.toSet())
    }
    LaunchedEffect(search) {
        if (search) {
            focus.requestFocus()
            keyboard?.show()
        }
    }
    BackHandler(
        search ||
            selecting ||
            folderId != null ||
            destination != Destination.All ||
            tagId != null ||
            createMenu ||
            filters ||
            menu
    ) {
        when {
            filters -> {
                if (filterChild != null) filterChild = null else filters = false
            }
            menu -> menu = false
            createMenu -> createMenu = false
            search && (content != ContentFilter.All || time != TimeFilter.All) && query.isBlank() -> {
                content = ContentFilter.All
                time = TimeFilter.All
            }
            selecting -> {
                selecting = false
                selected = emptySet()
            }
            search -> {
                keyboard?.hide()
                search = false
                onQuery("")
            }
            else -> onSelect(Destination.All, null, null)
        }
    }
    fun perform(label: String, block: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            runCatching { block() }
                .onSuccess {
                    onStatus(label)
                    selecting = false
                    selected = emptySet()
                }
                .onFailure { onStatus("操作失败：${it.localizedMessage}") }
            busy = false
        }
    }
    val noteEntry: @Composable (NoteSummary, Boolean) -> Unit = { note, isPinned ->
        val raw = searchSnippets[note.id] ?: note.preview
        // Markdown-to-plain conversion is not free; do it once per text.
        val preview = remember(note.id, raw) { cardPreview(raw) }
        val coordinates = remember(note.id) { arrayOfNulls<androidx.compose.ui.layout.LayoutCoordinates>(1) }
        val path = hierarchyIndex.path(note.id, false).takeIf { search || note.parentPageId != null }
        val click = {
            if (selecting)
                selected = if (note.id in selected) selected - note.id else selected + note.id
            else {
                coordinates[0]?.takeIf { it.isAttached }?.boundsInRoot()?.let(onCardBounds)
                if (search && query.isNotBlank()) repo.rememberSearch(query)
                onOpen(note.id)
            }
        }
        val longClick = {
            selecting = true
            selected = selected + note.id
        }
        if (compact)
            Box(Modifier.onGloballyPositioned { coordinates[0] = it }) {
                CompactNoteRow(note, preview, highlightTerms, isPinned, selecting, note.id in selected, path, click, longClick)
            }
        else
            ReferenceNoteCard(
                note,
                preview,
                highlightTerms,
                selecting,
                selected.contains(note.id),
                loadingId == note.id,
                imageByNote[note.id],
                onClick = click,
                onPositioned = { coordinates[0] = it },
                onLongClick = longClick,
                onMenu = {
                    selecting = true
                    selected = setOf(note.id)
                },
                path = path,
                childCount = hierarchyIndex.counts[note.id] ?: 0,
                onChildren = {
                    hierarchyAnchor = note.id
                    hierarchy = true
                },
                pinned = isPinned,
            )
    }
    val title =
        folder?.name
            ?: tags.firstOrNull { it.id == tagId }?.name
            ?: if (destination == Destination.All) "笔记" else destination.title
    BoxWithConstraints(
        Modifier.fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .navigationBarsPadding()
    ) {
        val homeWidth =
            if (tabletEditor != null) (maxWidth * .4f).coerceAtLeast(280.dp) else maxWidth
        Row(Modifier.fillMaxSize()) {
            Column(
                (if (tabletEditor != null) Modifier.width(homeWidth) else Modifier.weight(1f))
                    .fillMaxHeight()
                    .then(if (homeBlur > 0.dp) Modifier.blur(homeBlur) else Modifier)
            ) {
                AnimatedContent(
                    targetState = if (selecting) 2 else if (search) 1 else 0,
                    transitionSpec = {
                        fadeIn(tween(180)) + expandVertically() togetherWith
                            fadeOut(tween(130)) + shrinkVertically()
                    },
                    label = "home-toolbar",
                ) { mode ->
                    Row(
                        Modifier.fillMaxWidth()
                            .statusBarsPadding()
                            .padding(start = 12.dp, end = 12.dp, top = 0.dp, bottom = 4.dp)
                            .height(48.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        when (mode) {
                            2 -> {
                                TextButton({
                                    selecting = false
                                    selected = emptySet()
                                }) {
                                    Text("取消")
                                }
                                Text(
                                    "已选择 ${selected.size} 项",
                                    Modifier.weight(1f),
                                    fontWeight = FontWeight.SemiBold,
                                )
                                TextButton({
                                    selected =
                                        if (selected.size == visible.size) emptySet()
                                        else visible.map { it.id }.toSet()
                                }) {
                                    Text(
                                        if (selected.size == visible.size && visible.isNotEmpty())
                                            "取消全选"
                                        else "全选"
                                    )
                                }
                            }
                            1 -> {
                                Surface(
                                    Modifier.weight(1f),
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.surface,
                                ) {
                                    Row(
                                        Modifier.padding(horizontal = 14.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Icon(Icons.Default.Search, null, Modifier.size(20.dp))
                                        TextField(
                                            query,
                                            onQuery,
                                            Modifier.weight(1f)
                                                .focusRequester(focus)
                                                .testTag("home-search"),
                                            placeholder = {
                                                Text(if (folder != null) "搜索库内笔记" else "搜索笔记")
                                            },
                                            singleLine = true,
                                            colors =
                                                TextFieldDefaults.colors(
                                                    focusedContainerColor = Color.Transparent,
                                                    unfocusedContainerColor = Color.Transparent,
                                                    focusedIndicatorColor = Color.Transparent,
                                                    unfocusedIndicatorColor = Color.Transparent,
                                                ),
                                            trailingIcon = {
                                                if (query.isNotEmpty())
                                                    IconButton({ onQuery("") }) {
                                                        Icon(Icons.Default.Cancel, "清空搜索")
                                                    }
                                            },
                                        )
                                    }
                                }
                                TextButton({
                                    keyboard?.hide()
                                    search = false
                                    content = ContentFilter.All
                                    time = TimeFilter.All
                                    onQuery("")
                                }) {
                                    Text("取消")
                                }
                            }
                            else -> {
                                if (
                                    folder != null ||
                                        destination != Destination.All ||
                                        tagId != null
                                )
                                    RoundTool(
                                        Icons.Default.ArrowBack,
                                        "返回首页",
                                        { onSelect(Destination.All, null, null) },
                                        filled = false,
                                    )
                                else Spacer(Modifier.weight(1f))
                                if (
                                    folder != null ||
                                        destination != Destination.All ||
                                        tagId != null
                                )
                                    Spacer(Modifier.weight(1f))
                                RoundTool(
                                    Icons.Default.AccountTree,
                                    "笔记结构",
                                    {
                                        hierarchyAnchor = null
                                        hierarchy = true
                                    },
                                    modifier = Modifier.testTag("open-note-hierarchy"),
                                )
                                RoundTool(
                                    Icons.Default.Search,
                                    "搜索笔记",
                                    {
                                        search = true
                                        tab = 0
                                    },
                                )
                                Box {
                                    RoundTool(Icons.Default.MoreHoriz, "更多", { menu = true })
                                    DropdownMenu(menu, { menu = false }) {
                                        DropdownMenuItem(
                                            { Text("排序与筛选") },
                                            {
                                                menu = false
                                                filters = true
                                            },
                                            leadingIcon = { Icon(Icons.Default.Sort, null) },
                                        )
                                        DropdownMenuItem(
                                            { Text("选择笔记") },
                                            {
                                                menu = false
                                                selecting = true
                                            },
                                            enabled = visible.isNotEmpty(),
                                        )
                                        if (folder?.type == "noteFolder") {
                                            DropdownMenuItem(
                                                { Text("编辑笔记库") },
                                                {
                                                    menu = false
                                                    libraryEditor = folder
                                                },
                                            )
                                            DropdownMenuItem(
                                                { Text("删除笔记库") },
                                                {
                                                    menu = false
                                                    deleteLibrary = folder
                                                },
                                            )
                                        }
                                        DropdownMenuItem(
                                            { Text("最近删除") },
                                            {
                                                menu = false
                                                tab = 0
                                                onSelect(Destination.Trash, null, null)
                                            },
                                            leadingIcon = {
                                                Icon(Icons.Default.DeleteOutline, null)
                                            },
                                        )
                                        DropdownMenuItem(
                                            { Text("导航、标签与私密库") },
                                            {
                                                menu = false
                                                onNavigation()
                                            },
                                            leadingIcon = { Icon(Icons.Default.Menu, "导航") },
                                        )
                                        DropdownMenuItem(
                                            { Text("立即同步") },
                                            {
                                                menu = false
                                                onSync()
                                            },
                                            enabled = !syncing,
                                        )
                                        DropdownMenuItem(
                                            { Text("设置与同步") },
                                            {
                                                menu = false
                                                onSettings()
                                            },
                                            leadingIcon = { Icon(Icons.Default.Settings, null) },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                if (globalHome && !search && !selecting && tab == 0)
                    DueSoonBanner(
                        bannerItems,
                        clock,
                        onOpen = { id -> onOpen(id) },
                        onComplete = { item ->
                            scope.launch {
                                runCatching { repo.loadNote(item.id)?.let { repo.save(it.copy(completedAt = System.currentTimeMillis())) } }
                                    .onSuccess { onStatus("已完成「${item.title.ifBlank { "无标题待办" }}」") }
                                    .onFailure { onStatus("操作失败：${it.localizedMessage}") }
                            }
                        },
                        onSnooze = { item ->
                            bannerState.snooze(item.id, System.currentTimeMillis() + 60 * 60_000L)
                            bannerRevision++
                        },
                        onDismiss = { item ->
                            bannerState.dismiss(item)
                            bannerRevision++
                        },
                        onShowAll = { onSelect(Destination.Todos, null, null) },
                    )
                if (search) SearchFilterChips(content, time, { content = it }, { time = it })
                if (search && (folder != null || tagId != null))
                    Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("仅搜索「$title」", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton({ onSelect(Destination.All, null, null) }) { Text("搜索全部笔记", fontSize = 12.sp) }
                    }
                status?.let {
                    Surface(
                        Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        color =
                            if (it.contains("失败")) MaterialTheme.colorScheme.errorContainer
                            else MaterialTheme.colorScheme.secondaryContainer,
                    ) {
                        Row(
                            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (syncing)
                                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Text(it, Modifier.weight(1f).padding(start = 6.dp), fontSize = 12.sp)
                            if (!syncing)
                                IconButton({ onStatus("") }, Modifier.size(30.dp)) {
                                    Icon(Icons.Default.Close, "关闭提示", Modifier.size(16.dp))
                                }
                        }
                    }
                }
                error?.let {
                    Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
                }
                if (!globalHome || search || librariesVisible || selecting)
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            if (search) (if (query.isBlank() && content == ContentFilter.All && time == TimeFilter.All) "搜索" else "找到 ${visible.size} 篇")
                            else if (librariesVisible) "笔记库" else title,
                            Modifier.weight(1f),
                            fontSize = if (search) 18.sp else 32.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        if (!search && !selecting && (tab == 0 || folder != null))
                            TextButton({ filters = true }) {
                                Icon(Icons.Default.Tune, "筛选", Modifier.size(19.dp))
                                Text("${visible.size}", Modifier.padding(start = 6.dp))
                            }
                    }
                if (destination == Destination.Trash)
                    Text(
                        "已删除的内容会保留在此，直到你恢复或彻底删除。",
                        Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                if (folder != null && !search) {
                    Column(
                        Modifier.fillMaxWidth().padding(bottom = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        LibraryCover(
                            libraryColor(folder.color),
                            Modifier.size(92.dp, 76.dp),
                            folder.type == "encryptedFolder",
                        )
                        Text(
                            "${notes.size} 篇笔记",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                AnimatedContent(
                    targetState = librariesVisible && !search,
                    modifier = Modifier.weight(1f),
                    transitionSpec = {
                        fadeIn(tween(220)) +
                            slideInHorizontally {
                                if (targetState) it / 8 else -it / 8
                            } togetherWith fadeOut(tween(160))
                    },
                    label = "home-tab",
                ) { libraries ->
                    if (libraries)
                        LazyColumn(
                            Modifier.fillMaxSize(),
                            state = libraryState,
                            contentPadding =
                                PaddingValues(start = 16.dp, end = 16.dp, bottom = 104.dp),
                        ) {
                            if (publicLibraries.isEmpty())
                                item {
                                    EmptyNotes("把笔记放进笔记库", "用颜色整理不同主题", true) {
                                        createLibrary = true
                                    }
                                }
                            items(publicLibraries.chunked(3), key = { it.first().id }) { row ->
                                Row(
                                    Modifier.fillMaxWidth().padding(vertical = 12.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    row.forEach { library ->
                                        Column(
                                            Modifier.weight(1f)
                                                .testTag("library-card-${library.id}")
                                                .combinedClickable(
                                                    onClick = {
                                                        onSelect(Destination.All, library.id, null)
                                                    },
                                                    onLongClick = { libraryEditor = library },
                                                )
                                                .padding(4.dp),
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                        ) {
                                            LibraryCover(
                                                libraryColor(library.color),
                                                Modifier.fillMaxWidth().height(82.dp),
                                            )
                                            Text(
                                                library.name,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                fontWeight = FontWeight.Medium,
                                                fontSize = 14.sp,
                                            )
                                            Text(
                                                "${libraryCounts[library.id] ?: 0} 篇",
                                                fontSize = 12.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                                }
                            }
                        }
                    else Box(Modifier.fillMaxSize()) {
                        LazyColumn(
                            Modifier.fillMaxSize().testTag("note-cards"),
                            state = listState,
                            contentPadding =
                                PaddingValues(
                                    start = 16.dp,
                                    end = 16.dp,
                                    top = 4.dp,
                                    bottom = 112.dp,
                                ),
                            verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 12.dp),
                        ) {
                            if (globalHome && !search && !selecting) {
                                item(key = "spotlight-header") {
                                    HomeSpotlight(
                                        notes.firstOrNull { it.important } ?: notes.firstOrNull(),
                                        onCardBounds,
                                        onOpen,
                                    )
                                    Row(
                                        Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            "全部 ${visible.size}",
                                            Modifier.weight(1f),
                                            fontSize = 16.sp,
                                            lineHeight = 20.sp,
                                            fontWeight = FontWeight.SemiBold,
                                        )
                                        TextButton({ filters = true }, Modifier.height(32.dp)) {
                                            Text(
                                                "筛选",
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                            Icon(
                                                Icons.Default.FilterList,
                                                "筛选",
                                                Modifier.padding(start = 4.dp).size(20.dp),
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                    Spacer(Modifier.height(8.dp))
                                }
                            }
                            if (search && query.isBlank() && content == ContentFilter.All && time == TimeFilter.All) {
                                item(key = "search-start") {
                                    SearchStartPanel(
                                        recentSearches,
                                        remember(all) { navigation.recent().mapNotNull { id -> hierarchyNotes.firstOrNull { it.id == id && it.deletedAt == null } }.take(8) },
                                        onSearch = onQuery,
                                        onClearSearches = {
                                            repo.clearSearchHistory()
                                            recentSearches = emptyList()
                                        },
                                        onOpen = { id ->
                                            keyboard?.hide()
                                            onOpen(id)
                                        },
                                    )
                                }
                                if (tagShortcuts.isNotEmpty())
                                    item(key = "search-tags") { SearchTagShortcuts(tagShortcuts) { name -> onQuery("#$name ") } }
                                return@LazyColumn
                            }
                            if (visible.isEmpty())
                                item {
                                    EmptyNotes(
                                        if (search) "没有找到相关笔记" else "从一篇笔记开始",
                                        if (search) "换个关键词，或去掉筛选条件试试" else "记录想法，随时回来继续",
                                        onCreate =
                                            if (search || destination == Destination.Trash) null
                                            else onCreate,
                                        onRestore =
                                            if (!search && globalHome && all.isEmpty() && !repo.hasRemoteConfiguration()) onSettings
                                            else null,
                                    )
                                }
                            if (pinnedVisible.isNotEmpty()) {
                                stickyHeader(key = "header-pinned") { NoteSectionHeader("置顶", pinnedVisible.size) }
                                items(pinnedVisible, key = { "pinned-${it.id}" }) { note -> noteEntry(note, true) }
                            }
                            if (dateSections.isNotEmpty())
                                dateSections.forEach { (label, group) ->
                                    stickyHeader(key = "header-$label") { NoteSectionHeader(label, group.size) }
                                    items(group, key = { it.id }) { note -> noteEntry(note, false) }
                                }
                            else items(unpinnedVisible, key = { it.id }) { note -> noteEntry(note, false) }
                        }
                        if (!selecting) NoteFastScroller(listState, { key -> sectionByKey[key] })
                    }
                }
            }
            if (tabletEditor != null) {
                VerticalDivider()
                Box(Modifier.weight(1f).fillMaxHeight()) { tabletEditor() }
            }
        }
        AnimatedVisibility(
            !search,
            Modifier.align(Alignment.BottomStart)
                .width(homeWidth)
                .zIndex(if (createMenu) 2f else 0f),
            enter = fadeIn() + slideInVertically { it },
            exit = fadeOut() + slideOutVertically { it },
        ) {
            if (selecting)
                FloatingPill(
                    Modifier.padding(horizontal = 12.dp, vertical = 16.dp).fillMaxWidth()
                ) {
                    val enabled = selected.isNotEmpty() && !busy
                    if (destination == Destination.Trash) {
                        TextButton(
                            { perform("已恢复") { selected.forEach { repo.restore(it) } } },
                            enabled = enabled,
                        ) {
                            Text("恢复")
                        }
                        TextButton({ confirmAction = "permanent" }, enabled = enabled) {
                            Text("彻底删除", color = MaterialTheme.colorScheme.error)
                        }
                    } else {
                        IconButton(
                            {
                                perform("已复制") {
                                    clipboard.setText(
                                        AnnotatedString(
                                            selected
                                                .mapNotNull { repo.loadNote(it) }
                                                .joinToString("\n\n") { it.title + "\n" + it.body }
                                        )
                                    )
                                }
                            },
                            enabled = enabled,
                        ) {
                            Icon(Icons.Default.ContentCopy, "复制内容")
                        }
                        IconButton(
                            { perform("已创建副本") { selected.forEach { repo.duplicateNote(it) } } },
                            enabled = enabled,
                        ) {
                            Icon(Icons.Default.CopyAll, "创建副本")
                        }
                        val allPinned = selected.isNotEmpty() && selected.all { it in pinnedSet }
                        IconButton(
                            {
                                pinnedIds = navigation.setPinned(selected, !allPinned)
                                onStatus(if (allPinned) "已取消置顶" else "已置顶，显示在列表最上方")
                                selecting = false
                                selected = emptySet()
                            },
                            enabled = enabled,
                        ) {
                            Icon(Icons.Default.PushPin, if (allPinned) "取消置顶" else "置顶")
                        }
                        IconButton({ moving = true }, enabled = enabled) {
                            Icon(Icons.Default.DriveFileMove, "移动到笔记库")
                        }
                        if (folder != null && folder.type != "encryptedFolder")
                            IconButton(
                                {
                                    perform("已移出笔记库") {
                                        selected.forEach { repo.moveToLibrary(it, null) }
                                    }
                                },
                                enabled = enabled,
                            ) {
                                Icon(Icons.Default.FolderOff, "移出笔记库")
                            }
                        IconButton({ confirmAction = "trash" }, enabled = enabled) {
                            Icon(
                                Icons.Default.DeleteOutline,
                                "删除",
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            else
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    if (globalHome) {
                        if (tabletEditor == null)
                            FloatingRound {
                                IconButton({
                                    if (createMenu) {
                                        createMenu = false
                                        onSettings()
                                    } else {
                                        tab = 0
                                        onSelect(Destination.Todos, null, null)
                                    }
                                }) {
                                    if (createMenu || todos.badgeCount == 0)
                                        Icon(
                                            if (createMenu) Icons.Default.Settings
                                            else Icons.Default.Checklist,
                                            if (createMenu) "设置" else "全部待办",
                                        )
                                    else
                                        BadgedBox(
                                            badge = {
                                                Badge(
                                                    containerColor =
                                                        if (todos.hasOverdue) MaterialTheme.colorScheme.error
                                                        else MaterialTheme.colorScheme.tertiary,
                                                    contentColor =
                                                        if (todos.hasOverdue) MaterialTheme.colorScheme.onError
                                                        else MaterialTheme.colorScheme.onTertiary,
                                                ) { Text(if (todos.badgeCount > 99) "99+" else "${todos.badgeCount}") }
                                            },
                                            modifier = Modifier.testTag("todo-badge"),
                                        ) {
                                            Icon(
                                                Icons.Default.Checklist,
                                                "全部待办，${todos.badgeCount} 项今天到期或已逾期",
                                            )
                                        }
                                }
                            }
                        FloatingPill(
                            Modifier.weight(1f)
                                .then(if (homeBlur > 0.dp) Modifier.blur(homeBlur) else Modifier)
                        ) {
                            listOf("笔记", "笔记库").forEachIndexed { index, label ->
                                Box(
                                    Modifier.weight(1f)
                                        .height(44.dp)
                                        .testTag("home-tab-$index")
                                        .clip(CircleShape)
                                        .clickable(enabled = !createMenu) {
                                            tab = index
                                            onQuery("")
                                        },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    val foreground =
                                        if (tab == index) MaterialTheme.colorScheme.onSurface
                                        else MaterialTheme.colorScheme.onSurfaceVariant
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(2.dp),
                                    ) {
                                        Icon(
                                            if (index == 0) Icons.Default.Description
                                            else Icons.Default.AutoStories,
                                            null,
                                            Modifier.size(24.dp),
                                            tint = foreground,
                                        )
                                        Text(
                                            label,
                                            fontSize = 11.sp,
                                            lineHeight = 14.sp,
                                            color = foreground,
                                            fontWeight =
                                                if (tab == index) FontWeight.SemiBold
                                                else FontWeight.Normal,
                                        )
                                    }
                                }
                            }
                        }
                    } else
                        FloatingPill(Modifier.weight(1f)) {
                            if (folder != null)
                                TextButton({ existingPicker = true }) {
                                    Icon(Icons.Default.LibraryAdd, null)
                                    Text("添加已有笔记")
                                }
                            else Text(title, Modifier.padding(horizontal = 12.dp), fontSize = 14.sp)
                        }
                    if (destination.newItemType != null)
                        FloatingRound(prominent = true) {
                            Box(
                                Modifier.size(48.dp)
                                    .testTag("new-item")
                                    .combinedClickable(
                                        onClick = {
                                            if (librariesVisible) createLibrary = true
                                            else createMenu = !createMenu
                                        },
                                        onLongClick = {
                                            if (preferences.getBoolean("quickCreate", true)) {
                                                if (librariesVisible) createLibrary = true
                                                else onCreate()
                                            }
                                        },
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    if (createMenu) Icons.Default.Close else Icons.Default.Add,
                                    if (createMenu) "关闭新建" else if (isTodo) "新建待办" else "新建笔记",
                                    Modifier.size(28.dp),
                                )
                            }
                        }
                }
        }
        androidx.compose.animation.AnimatedVisibility(
            visibleState = createTransition,
            enter = fadeIn(tween(180)) + slideInVertically(tween(220)) { it / 10 },
            exit = fadeOut(tween(160)) + slideOutVertically(tween(180)) { it / 10 },
        ) {
            Box(Modifier.width(homeWidth).fillMaxHeight()) {
                Box(
                    Modifier.fillMaxSize()
                        .background(MaterialTheme.colorScheme.background.copy(alpha = .4f))
                        .clickable { createMenu = false }
                )
                Column(
                    Modifier.align(Alignment.BottomEnd)
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CreateSourceCard(
                            if (isTodo) "新建待办" else "写笔记",
                            "轻松记录，快捷编辑",
                            Icons.Default.TextFields,
                            Modifier.weight(1f).testTag("create-text-note"),
                        ) {
                            createMenu = false
                            onCreate()
                        }
                        CreateSourceCard(
                            "导入笔记",
                            "Markdown 与文本",
                            Icons.Default.FileOpen,
                            Modifier.weight(1f),
                        ) {
                            createMenu = false
                            onImport()
                        }
                    }
                    if (folder != null) {
                        Surface(
                            shape = RoundedCornerShape(24.dp),
                            modifier =
                                Modifier.fillMaxWidth().clickable {
                                    createMenu = false
                                    existingPicker = true
                                },
                        ) {
                            Row(
                                Modifier.padding(20.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Default.LibraryAdd, null)
                                Text("添加已有笔记", Modifier.padding(start = 12.dp))
                            }
                        }
                    }
                }
            }
        }
        if (filters)
            Box(Modifier.width(homeWidth).fillMaxHeight()) {
                ReferenceFilters(
                    order,
                    descending,
                    content,
                    filing,
                    filterChild,
                    { filterChild = it },
                    {
                        filters = false
                        filterChild = null
                    },
                    { order = it },
                    { descending = !descending },
                    { content = it },
                    { filing = it },
                    compact,
                    {
                        compact = !compact
                        preferences.edit().putBoolean("homeCompact", compact).apply()
                    },
                    showDue = todoContext,
                )
            }
        // Long lists: one tap back to the top.
        val farDown by remember(listState) { derivedStateOf { listState.firstVisibleItemIndex > 12 } }
        if (farDown && !librariesVisible && !selecting && !createMenu)
            SmallFloatingActionButton(
                { scope.launch { listState.animateScrollToItem(0) } },
                Modifier.align(Alignment.BottomEnd).padding(end = 20.dp, bottom = 96.dp).testTag("scroll-to-top"),
                containerColor = MaterialTheme.colorScheme.surface,
            ) {
                Icon(Icons.Default.VerticalAlignTop, "回到顶部")
            }
    }
    if (createLibrary || libraryEditor != null)
        LibraryEditorSheet(
            libraryEditor,
            {
                createLibrary = false
                libraryEditor = null
            },
        ) { name, color ->
            scope.launch {
                runCatching {
                        repo.saveFolder(
                            name,
                            id = libraryEditor?.id ?: java.util.UUID.randomUUID().toString(),
                            color = color,
                        )
                    }
                    .onSuccess {
                        createLibrary = false
                        libraryEditor = null
                    }
                    .onFailure { onStatus("保存笔记库失败：${it.localizedMessage}") }
            }
        }
    if (moving)
        LibraryMoveSheet(
            folders.filter {
                it.type ==
                    if (notes.firstOrNull { n -> n.id in selected }?.let(repo::isEncrypted) == true)
                        "encryptedFolder"
                    else if (notes.firstOrNull { n -> n.id in selected }?.itemType == "todo")
                        "todoList"
                    else "noteFolder"
            },
            folderId,
            { moving = false },
        ) { target ->
            moving = false
            perform("已移动笔记") { selected.forEach { repo.moveToLibrary(it, target) } }
        }
    if (existingPicker && folder != null)
        ExistingNoteSheet(
            all.filter {
                it.itemType == "note" &&
                    it.deletedAt == null &&
                    it.folderId != folder.id &&
                    !repo.isEncrypted(it)
            },
            folder,
            { existingPicker = false },
        ) { ids ->
            existingPicker = false
            perform("已添加 ${ids.size} 篇笔记") { ids.forEach { repo.moveToLibrary(it, folder) } }
        }
    deleteLibrary?.let { library ->
        AlertDialog(
            onDismissRequest = { deleteLibrary = null },
            title = { Text("删除笔记库“${library.name}”？") },
            text = { Text("解散笔记库会保留全部笔记；也可以将库内笔记一起移入最近删除。") },
            confirmButton = {
                TextButton({
                    deleteLibrary = null
                    perform("笔记库已解散") {
                        repo.dissolveLibrary(library.id, false)
                        onSelect(Destination.All, null, null)
                    }
                }) {
                    Text("解散并保留笔记")
                }
            },
            dismissButton = {
                Column {
                    TextButton({
                        deleteLibrary = null
                        perform("笔记库已删除") {
                            repo.dissolveLibrary(library.id, true)
                            onSelect(Destination.All, null, null)
                        }
                    }) {
                        Text("连同笔记一起删除", color = MaterialTheme.colorScheme.error)
                    }
                    TextButton({ deleteLibrary = null }) { Text("取消") }
                }
            },
        )
    }
    confirmAction?.let { action ->
        AlertDialog(
            onDismissRequest = { confirmAction = null },
            title = {
                Text(
                    if (action == "permanent") "彻底删除 ${selected.size} 篇笔记？"
                    else "删除 ${selected.size} 篇笔记？"
                )
            },
            text = { Text(if (action == "permanent") "此操作无法恢复。" else "所选笔记及其子页面会移入最近删除。") },
            confirmButton = {
                Button(
                    {
                        confirmAction = null
                        perform(if (action == "permanent") "已彻底删除" else "已移入最近删除") {
                            selected.forEach {
                                if (action == "permanent") repo.deletePermanently(it)
                                else repo.moveToTrash(it)
                            }
                        }
                    },
                    colors =
                        ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        ),
                ) {
                    Text("删除")
                }
            },
            dismissButton = { TextButton({ confirmAction = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun HomeSpotlight(note: NoteSummary?, onBounds: (Rect) -> Unit, onOpen: (String) -> Unit) {
    var bounds by remember { mutableStateOf<Rect?>(null) }
    Column(
        Modifier.fillMaxWidth()
            .padding(start = 4.dp, end = 4.dp, bottom = 12.dp)
            .onGloballyPositioned { bounds = it.boundsInRoot() }
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                Icons.Default.AutoAwesome,
                null,
                Modifier.size(22.dp),
                tint = MaterialTheme.colorScheme.tertiary,
            )
            Text("灵光一刻", fontSize = 16.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold)
        }
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                cardPreview(note?.preview.orEmpty()).take(80).ifBlank { "记录当下，随时回来继续" },
                Modifier.weight(1f),
                fontSize = 24.sp,
                lineHeight = 32.sp,
                fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (note != null)
                IconButton(
                    {
                        bounds?.let(onBounds)
                        onOpen(note.id)
                    },
                    Modifier.testTag("open-spotlight"),
                ) {
                    Icon(Icons.Default.ArrowForward, "打开摘录笔记")
                }
        }
        Text(
            if (note == null) "想法、片段和日常，都可以从这里开始" else "摘自「${note.title.ifBlank { "无标题" }}」",
            Modifier.padding(top = 6.dp),
            fontSize = 13.sp,
            lineHeight = 18.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun CreateSourceCard(
    title: String,
    description: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Surface(
        modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(26.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Column(
            Modifier.heightIn(min = 126.dp).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(icon, null, Modifier.size(30.dp), tint = MaterialTheme.colorScheme.tertiary)
            Text(title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Text(
                description,
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ReferenceNoteCard(
    note: NoteSummary,
    preview: String,
    terms: List<String>,
    selecting: Boolean,
    selected: Boolean,
    loading: Boolean,
    image: AssetEntity?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onMenu: () -> Unit,
    onPositioned: (androidx.compose.ui.layout.LayoutCoordinates) -> Unit = {},
    path: String? = null,
    childCount: Int = 0,
    onChildren: () -> Unit = {},
    pinned: Boolean = false,
) {
    val accent = MaterialTheme.colorScheme.tertiary
    Surface(
        Modifier.fillMaxWidth()
            // Store coordinates only; bounds are computed when the card is opened.
            .onGloballyPositioned(onPositioned)
            .combinedClickable(enabled = !loading, onClick = onClick, onLongClick = onLongClick)
            .testTag("note-card-${note.id}"),
        shape = RoundedCornerShape(26.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Row(
            Modifier.padding(start = 16.dp, end = 10.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    highlightedTerms(note.title.ifBlank { "无标题" }, terms, accent),
                    fontSize = 18.sp,
                    lineHeight = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (preview.isNotBlank())
                    Text(
                        highlightedTerms(preview.replace('\n', ' '), terms, accent),
                        Modifier.padding(top = 4.dp),
                        fontSize = 14.sp,
                        lineHeight = 18.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                if (!path.isNullOrBlank())
                    Row(
                        Modifier.fillMaxWidth().padding(top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            path,
                            Modifier.weight(1f),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (!selecting)
                            IconButton(
                                onChildren,
                                Modifier.size(32.dp).testTag("note-path-${note.id}"),
                            ) {
                                Icon(
                                    Icons.Default.MyLocation,
                                    "在结构中定位 ${note.title}",
                                    Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                    }
                Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (pinned)
                        Icon(
                            Icons.Default.PushPin,
                            "已置顶",
                            Modifier.padding(end = 4.dp).size(13.dp),
                            tint = accent,
                        )
                    // Open todos show what matters: when they are due.
                    val due = note.attentionAt()?.takeIf { note.isOpenTodo() }
                    val now = System.currentTimeMillis()
                    val overdue = due != null && todoGroupLabel(note, now) == "已逾期"
                    Text(
                        when {
                            due != null -> dueDescription(due, now)
                            note.itemType == "todo" && note.completedAt != null -> "已完成"
                            else -> "${relativeDateLabel(note.updatedAt)} 更新"
                        },
                        Modifier.weight(1f),
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        color = if (overdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (note.reminderAt != null)
                        Icon(
                            Icons.Default.NotificationsNone,
                            null,
                            Modifier.padding(start = 6.dp).size(13.dp),
                        )
                    if (note.conflict)
                        Icon(
                            Icons.Default.Warning,
                            null,
                            Modifier.padding(start = 6.dp).size(13.dp),
                            tint = MaterialTheme.colorScheme.error,
                        )
                    if (!selecting && childCount > 0)
                        Text(
                            "$childCount 下级",
                            Modifier.clickable(onClick = onChildren)
                                .padding(horizontal = 6.dp, vertical = 4.dp)
                                .testTag("note-children-${note.id}"),
                            fontSize = 11.sp,
                            color = accent,
                        )
                    if (!selecting)
                        IconButton(onMenu, Modifier.size(20.dp)) {
                            Icon(
                                Icons.Default.MoreVert,
                                "笔记菜单",
                                Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                }
            }
            if (image?.localPath != null)
                coil.compose.AsyncImage(
                    image.localPath,
                    "图片附件",
                    Modifier.padding(start = 8.dp).size(62.dp).clip(RoundedCornerShape(12.dp)),
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                )
            if (selecting)
                Checkbox(
                    selected,
                    { onClick() },
                    colors = CheckboxDefaults.colors(checkedColor = accent),
                    modifier = Modifier.testTag("select-${note.id}"),
                )
            if (loading)
                CircularProgressIndicator(
                    Modifier.padding(start = 8.dp).size(18.dp),
                    strokeWidth = 2.dp,
                )
        }
    }
}

@Composable
private fun LibraryEditorSheet(
    original: FolderEntity?,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit,
) {
    var name by remember(original?.id) { mutableStateOf(original?.name.orEmpty()) }
    var color by remember(original?.id) { mutableStateOf(original?.color ?: LibraryPalette[6]) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            Modifier.fillMaxWidth().imePadding().padding(bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            SheetTitle(if (original == null) "新建笔记库" else "编辑笔记库", onDismiss)
            LibraryCover(libraryColor(color), Modifier.size(120.dp, 100.dp))
            TextField(
                name,
                { name = it.take(80) },
                Modifier.fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 16.dp)
                    .testTag("library-name"),
                placeholder = { Text("笔记库名称") },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                colors =
                    TextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.background,
                        unfocusedContainerColor = MaterialTheme.colorScheme.background,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                    ),
            )
            LibraryPalette.chunked(6).forEach { row ->
                Row(
                    Modifier.padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    row.forEach { value ->
                        Box(
                            Modifier.size(32.dp)
                                .clip(CircleShape)
                                .background(libraryColor(value))
                                .clickable { color = value },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (color == value)
                                Icon(
                                    Icons.Default.Check,
                                    null,
                                    Modifier.size(18.dp),
                                    tint = Color(0xFF4A4A4A),
                                )
                        }
                    }
                }
            }
            Button(
                { onSave(name.trim(), color) },
                enabled = name.isNotBlank(),
                modifier =
                    Modifier.fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 16.dp)
                        .testTag("save-library"),
                shape = CircleShape,
                colors =
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.onSurface,
                        contentColor = MaterialTheme.colorScheme.surface,
                    ),
            ) {
                Text("保存")
            }
        }
    }
}

@Composable
internal fun LibraryMoveSheet(
    libraries: List<FolderEntity>,
    current: String?,
    onDismiss: () -> Unit,
    onMove: (FolderEntity?) -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        SheetTitle("移动到笔记库", onDismiss)
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp)) {
            item {
                ListItem(
                    headlineContent = { Text("未分类") },
                    leadingContent = { Icon(Icons.Default.FolderOff, null) },
                    modifier = Modifier.clickable(enabled = current != null) { onMove(null) },
                )
            }
            items(libraries, key = { it.id }) { folder ->
                ListItem(
                    headlineContent = { Text(folder.name) },
                    leadingContent = {
                        LibraryCover(libraryColor(folder.color), Modifier.size(48.dp, 40.dp))
                    },
                    trailingContent = {
                        if (folder.id == current)
                            Text("当前", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    },
                    modifier = Modifier.clickable(enabled = folder.id != current) { onMove(folder) },
                )
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ExistingNoteSheet(
    notes: List<NoteSummary>,
    folder: FolderEntity,
    onDismiss: () -> Unit,
    onAdd: (Set<String>) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(setOf<String>()) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.88f)) {
            SheetTitle("添加到 ${folder.name}", onDismiss) {
                TextButton({ onAdd(selected) }, enabled = selected.isNotEmpty()) {
                    Text("添加 (${selected.size})")
                }
            }
            TextField(
                query,
                { query = it },
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                singleLine = true,
                placeholder = { Text("搜索笔记") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
            )
            LazyColumn(
                Modifier.weight(1f),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(
                    notes.filter {
                        query.isBlank() ||
                            it.title.contains(query, true) ||
                            it.preview.contains(query, true)
                    },
                    key = { it.id },
                ) { note ->
                    ReferenceNoteCard(
                        note,
                        note.preview,
                        listOfNotNull(query.trim().takeIf { it.isNotEmpty() }),
                        true,
                        note.id in selected,
                        false,
                        null,
                        {
                            selected =
                                if (note.id in selected) selected - note.id else selected + note.id
                        },
                        {},
                        {},
                    )
                }
            }
        }
    }
}
