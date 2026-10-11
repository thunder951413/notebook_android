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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var search by rememberSaveable { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var filters by remember { mutableStateOf(false) }
    var filterChild by remember { mutableStateOf<String?>(null) }
    var createMenu by remember { mutableStateOf(false) }
    var order by rememberSaveable { mutableStateOf(NoteOrder.Updated) }
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
    val preferences = LocalContext.current.getSharedPreferences("editor_preferences", 0)
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
    val visible =
        remember(notes, order, descending, content, filing, assets, linkIds) {
            orderedNotes(
                notes.filter { note ->
                    (filing == FilingFilter.All ||
                        if (filing == FilingFilter.Unfiled) note.folderId == null
                        else note.folderId != null) &&
                        (content == ContentFilter.All ||
                            when (content) {
                                ContentFilter.Image ->
                                    assets.any { it.noteId == note.id && it.kind == "image" }
                                ContentFilter.Audio ->
                                    assets.any { it.noteId == note.id && it.kind == "audio" }
                                ContentFilter.Video ->
                                    assets.any {
                                        it.noteId == note.id && it.mimeType.startsWith("video/")
                                    }
                                ContentFilter.File ->
                                    assets.any {
                                        it.noteId == note.id &&
                                            it.kind == "file" &&
                                            !it.mimeType.startsWith("video/")
                                    }
                                ContentFilter.Link -> note.id in linkIds
                                else -> true
                            })
                },
                order,
                descending,
            )
        }
    var snippets by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    LaunchedEffect(query, notes.map { it.id }) {
        snippets =
            if (query.isBlank()) emptyMap()
            else notes.take(100).associate { it.id to repo.searchSnippet(it.id, query) }
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
                    .then(if (createMenu) Modifier.blur(12.dp) else Modifier)
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
                            .padding(horizontal = 12.dp, vertical = 12.dp)
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
                                else
                                    Text(
                                        "Notebook",
                                        Modifier.weight(1f).padding(start = 8.dp),
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Medium,
                                    )
                                if (
                                    folder != null ||
                                        destination != Destination.All ||
                                        tagId != null
                                )
                                    Spacer(Modifier.weight(1f))
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
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (search) "搜索结果 · ${visible.size}"
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
                                                "${all.count{it.folderId==library.id&&it.deletedAt==null&&!repo.isEncrypted(it)}} 篇",
                                                fontSize = 12.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                                }
                            }
                        }
                    else
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
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            if (visible.isEmpty())
                                item {
                                    EmptyNotes(
                                        if (search) "没有找到相关笔记" else "从一篇笔记开始",
                                        if (search) "试试其他关键词" else "记录想法，随时回来继续",
                                        onCreate =
                                            if (search || destination == Destination.Trash) null
                                            else onCreate,
                                    )
                                }
                            items(visible, key = { it.id }) { note ->
                                var bounds by remember(note.id) { mutableStateOf<Rect?>(null) }
                                ReferenceNoteCard(
                                    note,
                                    cardPreview(snippets[note.id] ?: note.preview),
                                    query,
                                    selecting,
                                    selected.contains(note.id),
                                    loadingId == note.id,
                                    assets.firstOrNull {
                                        it.noteId == note.id && it.kind == "image"
                                    },
                                    onClick = {
                                        if (selecting)
                                            selected =
                                                if (note.id in selected) selected - note.id
                                                else selected + note.id
                                        else {
                                            bounds?.let(onCardBounds)
                                            onOpen(note.id)
                                        }
                                    },
                                    onBounds = { bounds = it },
                                    onLongClick = {
                                        selecting = true
                                        selected = selected + note.id
                                    },
                                    onMenu = {
                                        selecting = true
                                        selected = setOf(note.id)
                                    },
                                )
                            }
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
            Modifier.align(Alignment.BottomStart).width(homeWidth),
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
                    Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    if (globalHome) {
                        if (tabletEditor == null)
                            FloatingRound {
                                IconButton({
                                    tab = 0
                                    onSelect(Destination.Todos, null, null)
                                }) {
                                    Icon(Icons.Default.Checklist, "全部待办")
                                }
                            }
                        FloatingPill(Modifier.weight(1f)) {
                            listOf("笔记", "笔记库").forEachIndexed { index, label ->
                                Box(
                                    Modifier.weight(1f)
                                        .height(44.dp)
                                        .testTag("home-tab-$index")
                                        .clip(CircleShape)
                                        .background(
                                            if (tab == index)
                                                MaterialTheme.colorScheme.secondaryContainer
                                            else Color.Transparent
                                        )
                                        .clickable {
                                            tab = index
                                            onQuery("")
                                        },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        label,
                                        fontSize = 15.sp,
                                        fontWeight =
                                            if (tab == index) FontWeight.SemiBold
                                            else FontWeight.Normal,
                                    )
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
                        FloatingRound {
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
        if (createMenu) {
            Box(Modifier.width(homeWidth).fillMaxHeight()) {
                Box(
                    Modifier.fillMaxSize()
                        .background(MaterialTheme.colorScheme.background.copy(alpha = .4f))
                        .clickable { createMenu = false }
                )
                Column(
                    Modifier.align(Alignment.BottomEnd)
                        .padding(start = 72.dp, end = 16.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Surface(shape = RoundedCornerShape(24.dp), shadowElevation = 12.dp) {
                        Column {
                            ListItem(
                                headlineContent = { Text(if (isTodo) "新建待办" else "文字笔记") },
                                leadingContent = { Icon(Icons.Default.EditNote, null) },
                                modifier =
                                    Modifier.clickable {
                                            createMenu = false
                                            onCreate()
                                        }
                                        .testTag("create-text-note"),
                            )
                            ListItem(
                                headlineContent = { Text("导入 Markdown / 文本") },
                                leadingContent = { Icon(Icons.Default.FileOpen, null) },
                                modifier =
                                    Modifier.clickable {
                                        createMenu = false
                                        onImport()
                                    },
                            )
                            if (folder != null)
                                ListItem(
                                    headlineContent = { Text("添加已有笔记") },
                                    leadingContent = { Icon(Icons.Default.LibraryAdd, null) },
                                    modifier =
                                        Modifier.clickable {
                                            createMenu = false
                                            existingPicker = true
                                        },
                                )
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
                )
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
private fun ReferenceNoteCard(
    note: NoteSummary,
    preview: String,
    query: String,
    selecting: Boolean,
    selected: Boolean,
    loading: Boolean,
    image: AssetEntity?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onMenu: () -> Unit,
    onBounds: (Rect) -> Unit = {},
) {
    val accent = MaterialTheme.colorScheme.tertiary
    Surface(
        Modifier.fillMaxWidth()
            .onGloballyPositioned { onBounds(it.boundsInRoot()) }
            .combinedClickable(enabled = !loading, onClick = onClick, onLongClick = onLongClick)
            .testTag("note-card-${note.id}"),
        shape = RoundedCornerShape(26.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Row(
            Modifier.padding(start = 16.dp, end = 10.dp, top = 16.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    highlighted(note.title.ifBlank { "无标题" }, query, accent),
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (preview.isNotBlank())
                    Text(
                        highlighted(preview.replace('\n', ' '), query, accent),
                        Modifier.padding(top = 7.dp),
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        SimpleDateFormat("M月d日 HH:mm", Locale.CHINA).format(Date(note.updatedAt)),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                    Spacer(Modifier.weight(1f))
                    if (!selecting)
                        IconButton(onMenu, Modifier.size(28.dp)) {
                            Icon(
                                Icons.Default.MoreHoriz,
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
                        query,
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
