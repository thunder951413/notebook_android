@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package io.github.notebook.android

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.notebook.android.data.*
import io.github.notebook.android.sync.SyncRepository
import kotlinx.coroutines.launch

private fun hierarchyLibraryKey(id: String?): String = if (id == null) "unfiled" else "folder:$id"

@Composable
internal fun NoteBreadcrumb(
    note: NoteEntity,
    notes: List<NoteSummary>,
    onOpen: (String) -> Unit,
    onStructure: () -> Unit,
) {
    val live = note.hierarchySummary()
    val index =
        remember(notes, live) {
            val stored = notes.firstOrNull { it.id == note.id }
            val positioned =
                if (stored != null && stored.treeUpdatedAt > live.treeUpdatedAt)
                    live.copy(
                        folderId = stored.folderId,
                        folderName = stored.folderName,
                        parentPageId = stored.parentPageId,
                        sortOrder = stored.sortOrder,
                        treeUpdatedAt = stored.treeUpdatedAt,
                    )
                else live
            NoteHierarchyIndex(notes.filter { it.id != note.id } + positioned)
        }
    val ancestors = index.ancestors(note.id)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp).testTag("note-breadcrumb").semantics {
            contentDescription = "完整路径：${index.path(note.id)}"
        },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.weight(1f).horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                index.byId[note.id]?.folderName?.ifBlank { "未分类" } ?: "未分类",
                Modifier.clickable(onClick = onStructure).padding(vertical = 10.dp),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (ancestors.size > 2) {
                Icon(Icons.Default.ChevronRight, null, Modifier.size(16.dp))
                Text(
                    "…",
                    Modifier.clickable(onClick = onStructure).padding(horizontal = 5.dp),
                    fontSize = 14.sp,
                )
            }
            ancestors.takeLast(2).forEach { parent ->
                Icon(
                    Icons.Default.ChevronRight,
                    null,
                    Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.outline,
                )
                Text(
                    parent.title.ifBlank { "无标题" },
                    Modifier.widthIn(max = 130.dp)
                        .clickable { onOpen(parent.id) }
                        .padding(vertical = 10.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        IconButton(onStructure, Modifier.testTag("locate-current-note")) {
            Icon(Icons.Default.AccountTree, "定位当前笔记", Modifier.size(20.dp))
        }
    }
}

@Composable
internal fun ReferenceHierarchySheet(
    notes: List<NoteSummary>,
    folders: List<FolderEntity>,
    repo: SyncRepository,
    currentId: String? = null,
    folderId: String? = null,
    scoped: Boolean = false,
    onDismiss: () -> Unit,
    onOpen: (String) -> Unit,
    onCreateChild: (String) -> Unit,
    onMoved: (NoteEntity) -> Unit = {},
    onStatus: (String) -> Unit = {},
) {
    val index = remember(notes) { NoteHierarchyIndex(notes) }
    var activeFolder by rememberSaveable { mutableStateOf(folderId) }
    var scopeEnabled by rememberSaveable { mutableStateOf(scoped) }
    val context = LocalContext.current
    val history = remember(context) { NoteNavigationHistory(context) }
    var pinned by remember { mutableStateOf(history.pinned()) }
    val recent = remember(index) { history.recent().mapNotNull(index.byId::get) }
    var query by rememberSaveable { mutableStateOf("") }
    var diagram by rememberSaveable { mutableStateOf(false) }
    var focusId by rememberSaveable {
        mutableStateOf(
            currentId
                ?: index.children[null]
                    .orEmpty()
                    .firstOrNull { !scoped || it.folderId == folderId }
                    ?.id
        )
    }
    var expanded by rememberSaveable {
        mutableStateOf(
            ArrayList(index.ancestors(focusId.orEmpty()).map { it.id } + listOfNotNull(focusId))
        )
    }
    var collapsedLibraries by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    var revealVersion by remember { mutableIntStateOf(0) }
    var moving by remember { mutableStateOf<NoteSummary?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val matches =
        remember(index, query, scopeEnabled, activeFolder) {
            index.search(query, activeFolder, scopeEnabled)
        }
    val rows =
        remember(index, expanded, activeFolder, scopeEnabled) {
            index.rows(expanded.toSet(), activeFolder, scopeEnabled)
        }
    val groups =
        rows.groupBy { it.note.folderId }.entries.sortedBy { it.value.first().note.folderName }
    val treeItems =
        buildList<Pair<String, HierarchyRow?>> {
            groups.forEach { (library, rows) ->
                val key = hierarchyLibraryKey(library)
                add("library:$key" to null)
                if (key !in collapsedLibraries) rows.forEach { add("note:${it.note.id}" to it) }
            }
        }
    fun reveal(id: String) {
        query = ""
        diagram = false
        focusId = id
        expanded = ArrayList((expanded + index.ancestors(id).map { it.id } + id).distinct())
        collapsedLibraries =
            ArrayList(collapsedLibraries - hierarchyLibraryKey(index.byId[id]?.folderId))
        keyboard?.hide()
        focus.clearFocus()
        revealVersion++
    }
    LaunchedEffect(revealVersion, diagram) {
        if (!diagram && query.isBlank() && (currentId != null || revealVersion > 0)) {
            val position = treeItems.indexOfFirst { it.second?.note?.id == focusId }
            if (position >= 0) listState.animateScrollToItem(position + 1)
        }
    }
    fun open(id: String) {
        keyboard?.hide()
        focus.clearFocus()
        onDismiss()
        onOpen(id)
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.background,
    ) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.93f).testTag("note-hierarchy")) {
            SheetTitle("笔记结构", onDismiss)
            Row(
                Modifier.padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    query,
                    {
                        query = it
                        if (it.isNotBlank()) diagram = false
                    },
                    Modifier.weight(1f).testTag("hierarchy-search"),
                    singleLine = true,
                    placeholder = { Text("标题、路径或正文摘要") },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    trailingIcon = {
                        if (query.isNotBlank())
                            IconButton({ query = "" }) { Icon(Icons.Default.Close, "清空结构搜索") }
                    },
                    shape = RoundedCornerShape(18.dp),
                )
                IconButton({ reveal(focusId ?: currentId ?: return@IconButton) }) {
                    Icon(Icons.Default.MyLocation, "定位所选笔记")
                }
            }
            Row(
                Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilterChip(
                    !diagram,
                    { if (diagram && focusId != null) reveal(focusId!!) else diagram = false },
                    { Text("结构树") },
                    modifier = Modifier.testTag("hierarchy-tree-mode"),
                    leadingIcon = { Icon(Icons.Default.AccountTree, null, Modifier.size(18.dp)) },
                    colors =
                        FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.tertiaryContainer,
                            selectedLeadingIconColor = MaterialTheme.colorScheme.onSurface,
                        ),
                )
                Spacer(Modifier.width(8.dp))
                FilterChip(
                    diagram,
                    {
                        query = ""
                        keyboard?.hide()
                        focus.clearFocus()
                        diagram = true
                    },
                    { Text("结构图") },
                    modifier = Modifier.testTag("hierarchy-map-mode"),
                    leadingIcon = { Icon(Icons.Default.Schema, null, Modifier.size(18.dp)) },
                    colors =
                        FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.tertiaryContainer,
                            selectedLeadingIconColor = MaterialTheme.colorScheme.onSurface,
                        ),
                )
                Spacer(Modifier.weight(1f))
                TextButton(
                    {
                        activeFolder = index.byId[focusId]?.folderId ?: folderId
                        scopeEnabled = !scopeEnabled
                    },
                    Modifier.testTag("hierarchy-scope"),
                ) {
                    Text(if (scopeEnabled) "当前库" else "全部", fontSize = 12.sp)
                }
                Text(
                    "${if(query.isNotBlank()) matches.size else index.byId.values.count { !scopeEnabled || it.folderId == activeFolder }} 篇",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            error?.let {
                Text(
                    it,
                    Modifier.padding(horizontal = 20.dp),
                    color = MaterialTheme.colorScheme.error,
                )
            }
            when {
                query.isNotBlank() -> {
                    LazyColumn(Modifier.weight(1f).testTag("hierarchy-search-results")) {
                        if (matches.size == 100)
                            item {
                                Text(
                                    "显示 100 项，可继续输入关键词缩小范围",
                                    Modifier.padding(20.dp),
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        if (matches.isEmpty()) item { EmptyNotes("没有找到笔记", "试试标题中的词或所属笔记库") }
                        items(matches, key = { it.id }) { n ->
                            ListItem(
                                headlineContent = {
                                    Text(
                                        highlighted(
                                            n.title.ifBlank { "无标题" },
                                            query,
                                            MaterialTheme.colorScheme.tertiary,
                                        ),
                                        maxLines = 2,
                                    )
                                },
                                supportingContent = {
                                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                        Text(
                                            index.path(n.id, false),
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        if (n.preview.isNotBlank())
                                            Text(
                                                cardPreview(n.preview).take(120),
                                                fontSize = 12.sp,
                                                lineHeight = 16.sp,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                    }
                                },
                                leadingContent = { Icon(Icons.Default.Description, null) },
                                trailingContent = {
                                    IconButton(
                                        { reveal(n.id) },
                                        Modifier.testTag("hierarchy-reveal-${n.id}"),
                                    ) {
                                        Icon(Icons.Default.MyLocation, "在结构中定位 ${n.title}")
                                    }
                                },
                                modifier =
                                    Modifier.clickable { open(n.id) }
                                        .testTag("hierarchy-result-${n.id}"),
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            )
                        }
                    }
                }
                diagram ->
                    HierarchyDiagram(
                        index,
                        focusId,
                        { focusId = it },
                        ::open,
                        ::reveal,
                        { id ->
                            onDismiss()
                            onCreateChild(id)
                        },
                        Modifier.weight(1f),
                    )
                else ->
                    LazyColumn(
                        Modifier.weight(1f).testTag("hierarchy-tree"),
                        state = listState,
                        contentPadding = PaddingValues(bottom = 24.dp),
                    ) {
                        item(key = "quick-access") {
                            val pins =
                                pinned.mapNotNull(index.byId::get).filter {
                                    !scopeEnabled || it.folderId == activeFolder
                                }
                            val visits =
                                recent
                                    .filter { !scopeEnabled || it.folderId == activeFolder }
                                    .take(6)
                            if (pins.isNotEmpty()) HierarchyQuickAccess("固定", pins, ::open)
                            if (visits.isNotEmpty()) HierarchyQuickAccess("最近打开", visits, ::open)
                        }
                        if (treeItems.isEmpty()) item { EmptyNotes("还没有笔记结构", "先创建一篇主笔记，再添加子笔记") }
                        items(treeItems, key = { it.first }) { (key, row) ->
                            if (row == null) {
                                val library = key.removePrefix("library:")
                                val first =
                                    groups
                                        .first { hierarchyLibraryKey(it.key) == library }
                                        .value
                                        .first()
                                        .note
                                val color = folders.firstOrNull { it.id == first.folderId }?.color
                                Row(
                                    Modifier.fillMaxWidth()
                                        .clickable {
                                            collapsedLibraries =
                                                ArrayList(
                                                    if (library in collapsedLibraries)
                                                        collapsedLibraries - library
                                                    else collapsedLibraries + library
                                                )
                                        }
                                        .padding(horizontal = 16.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    LibraryCover(libraryColor(color), Modifier.size(30.dp, 24.dp))
                                    Text(
                                        first.folderName.ifBlank { "未分类" },
                                        Modifier.weight(1f).padding(start = 10.dp),
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 15.sp,
                                    )
                                    Icon(
                                        if (library in collapsedLibraries)
                                            Icons.Default.ChevronRight
                                        else Icons.Default.ExpandMore,
                                        "折叠或展开笔记库",
                                        Modifier.size(20.dp),
                                    )
                                }
                            } else
                                HierarchyTreeRow(
                                    row,
                                    index.counts[row.note.id] ?: 0,
                                    row.note.id in expanded,
                                    row.note.id == focusId,
                                    row.note.id in pinned,
                                    onToggle = {
                                        expanded =
                                            ArrayList(
                                                if (row.note.id in expanded) expanded - row.note.id
                                                else expanded + row.note.id
                                            )
                                    },
                                    onOpen = { open(row.note.id) },
                                    onPin = { pinned = history.togglePin(row.note.id) },
                                    onCreate = {
                                        onDismiss()
                                        onCreateChild(row.note.id)
                                    },
                                    onMove = { moving = row.note },
                                )
                        }
                    }
            }
        }
    }
    moving?.let { n ->
        MovePageDialog(
            n,
            notes.filter { repo.isEncrypted(it) == repo.isEncrypted(n) },
            { moving = null },
        ) { parent ->
            moving = null
            scope.launch {
                runCatching { repo.movePage(n.id, parent) }
                    .onSuccess {
                        onMoved(it)
                        if (scopeEnabled) activeFolder = it.folderId
                        reveal(it.id)
                        onStatus("已移动笔记及其子笔记")
                    }
                    .onFailure { error = it.localizedMessage ?: "移动失败，请重试" }
            }
        }
    }
}

@Composable
private fun HierarchyQuickAccess(
    label: String,
    notes: List<NoteSummary>,
    onOpen: (String) -> Unit,
) {
    Column(Modifier.padding(top = 4.dp, bottom = 8.dp)) {
        Text(
            label,
            Modifier.padding(horizontal = 20.dp),
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            notes.forEach { n ->
                AssistChip(
                    { onOpen(n.id) },
                    {
                        Text(
                            n.title.ifBlank { "无标题" },
                            Modifier.widthIn(max = 160.dp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    modifier = Modifier.testTag("hierarchy-quick-$label-${n.id}"),
                )
            }
        }
    }
}

@Composable
private fun HierarchyTreeRow(
    row: HierarchyRow,
    count: Int,
    expanded: Boolean,
    selected: Boolean,
    pinned: Boolean,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
    onPin: () -> Unit,
    onCreate: () -> Unit,
    onMove: () -> Unit,
) {
    val n = row.note
    var menu by remember { mutableStateOf(false) }
    val line = MaterialTheme.colorScheme.outlineVariant
    Row(
        Modifier.fillMaxWidth()
            .background(
                if (selected) MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = .45f)
                else Color.Transparent
            )
            .testTag("hierarchy-node-${n.id}")
            .semantics { this.selected = selected },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(
            Modifier.padding(start = 12.dp).width((row.depth.coerceAtMost(6) * 14).dp).height(56.dp)
        ) {
            repeat(row.depth.coerceAtMost(6)) { i ->
                val x = (i * 14 + 7).dp.toPx()
                drawLine(line, Offset(x, 0f), Offset(x, size.height), 1.dp.toPx())
            }
        }
        IconButton(
            onToggle,
            Modifier.size(40.dp).testTag("hierarchy-toggle-${n.id}"),
            enabled = count > 0,
        ) {
            if (count > 0)
                Icon(
                    if (expanded) Icons.Default.ExpandMore else Icons.Default.ChevronRight,
                    if (expanded) "折叠 ${n.title}" else "展开 ${n.title}",
                    Modifier.size(20.dp),
                )
        }
        Row(
            Modifier.weight(1f)
                .clickable(onClick = onOpen)
                .padding(vertical = 14.dp)
                .testTag("hierarchy-open-${n.id}"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (n.icon.isNullOrBlank())
                Icon(
                    Icons.Default.Description,
                    null,
                    Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            else Text(n.icon, fontSize = 17.sp)
            Column(Modifier.weight(1f).padding(start = 8.dp)) {
                Text(
                    n.title.ifBlank { "无标题" },
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    fontSize = 14.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                )
                if (row.depth > 6)
                    Text(
                        "第 ${row.depth + 1} 层",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
            }
            if (pinned)
                Icon(
                    Icons.Default.PushPin,
                    "已固定",
                    Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.tertiary,
                )
            if (count > 0)
                Text(
                    count.toString(),
                    Modifier.padding(start = 8.dp).semantics {
                        contentDescription = "共 $count 篇下级笔记"
                    },
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
        }
        Box {
            IconButton({ menu = true }, Modifier.size(44.dp)) {
                Icon(Icons.Default.MoreVert, "层级操作 ${n.title}", Modifier.size(18.dp))
            }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem(
                    { Text(if (pinned) "取消固定" else "固定到快捷入口") },
                    {
                        menu = false
                        onPin()
                    },
                )
                DropdownMenuItem(
                    { Text("新建子笔记") },
                    {
                        menu = false
                        onCreate()
                    },
                )
                DropdownMenuItem(
                    { Text("移动到…") },
                    {
                        menu = false
                        onMove()
                    },
                )
            }
        }
    }
}

@Composable
private fun HierarchyDiagram(
    index: NoteHierarchyIndex,
    focusId: String?,
    onFocus: (String) -> Unit,
    onOpen: (String) -> Unit,
    onReveal: (String) -> Unit,
    onCreate: (String) -> Unit,
    modifier: Modifier,
) {
    val n = index.byId[focusId]
    val line = MaterialTheme.colorScheme.outlineVariant
    if (n == null) {
        Box(modifier.fillMaxWidth()) { EmptyNotes("还没有结构图", "先创建一篇主笔记") }
        return
    }
    val parent = index.parent(n.id)
    val children = index.children[n.id].orEmpty()
    val siblings =
        index.children[parent?.id].orEmpty().filter { it.id != n.id && it.folderId == n.folderId }
    Column(
        modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .testTag("hierarchy-diagram"),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            index.path(n.id, false),
            Modifier.fillMaxWidth().padding(bottom = 12.dp),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (parent != null) {
            DiagramCard(parent, "上级笔记", false, { onFocus(parent.id) }, Modifier.fillMaxWidth(.8f))
            Canvas(Modifier.size(1.dp, 22.dp)) {
                drawLine(
                    line,
                    Offset(size.width / 2, 0f),
                    Offset(size.width / 2, size.height),
                    1.dp.toPx(),
                )
            }
        }
        DiagramCard(
            n,
            "当前关注 · ${children.size} 篇直接子笔记",
            true,
            { onOpen(n.id) },
            Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton({ onOpen(n.id) }, Modifier.testTag("hierarchy-map-open")) { Text("打开笔记") }
            TextButton({ onReveal(n.id) }, Modifier.testTag("hierarchy-map-reveal")) {
                Icon(Icons.Default.MyLocation, null, Modifier.size(16.dp))
                Text("在树中定位")
            }
        }
        if (siblings.isNotEmpty()) {
            Text(
                "同级笔记",
                Modifier.fillMaxWidth().padding(top = 6.dp),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                siblings.take(20).forEach { s ->
                    AssistChip(
                        { onFocus(s.id) },
                        {
                            Text(
                                s.title.ifBlank { "无标题" },
                                Modifier.widthIn(max = 150.dp),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                    )
                }
            }
        }
        if (children.isEmpty()) {
            Text(
                "还没有子笔记",
                Modifier.padding(top = 20.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton({ onCreate(n.id) }) {
                Icon(Icons.Default.Add, null)
                Text("新建子笔记")
            }
        } else {
            Text(
                "子笔记 · 点按切换关注",
                Modifier.fillMaxWidth().padding(top = 12.dp),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val diagramRows = children.take(24).chunked(2)
            val rowTops = remember(n.id, children.map { it.id }) { mutableStateMapOf<Int, Float>() }
            Box(Modifier.fillMaxWidth()) {
                Canvas(Modifier.matchParentSize()) {
                    rowTops[diagramRows.lastIndex]?.let { last ->
                        drawLine(
                            line,
                            Offset(size.width / 2, 0f),
                            Offset(size.width / 2, (last - 10.dp.toPx()).coerceAtLeast(0f)),
                            1.dp.toPx(),
                        )
                    }
                    diagramRows.forEachIndexed { rowIndex, row ->
                        rowTops[rowIndex]?.let { top ->
                            val fork = top - 10.dp.toPx()
                            row.indices.forEach { i ->
                                val x = size.width * (i + .5f) / row.size
                                drawLine(
                                    line,
                                    Offset(size.width / 2, fork),
                                    Offset(x, fork),
                                    1.dp.toPx(),
                                )
                                drawLine(line, Offset(x, fork), Offset(x, top), 1.dp.toPx())
                            }
                        }
                    }
                }
                Column {
                    diagramRows.forEachIndexed { rowIndex, row ->
                        Spacer(Modifier.height(20.dp))
                        Row(
                            Modifier.onGloballyPositioned {
                                rowTops[rowIndex] = it.positionInParent().y
                            },
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            row.forEach { child ->
                                DiagramCard(
                                    child,
                                    "${index.counts[child.id] ?: 0} 篇下级",
                                    false,
                                    { onFocus(child.id) },
                                    Modifier.weight(1f).testTag("hierarchy-map-node-${child.id}"),
                                )
                            }
                        }
                    }
                }
            }
            if (children.size > 24)
                TextButton({ onReveal(n.id) }) { Text("在结构树中查看全部 ${children.size} 篇子笔记") }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun DiagramCard(
    n: NoteSummary,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    Surface(
        modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        color =
            if (selected) MaterialTheme.colorScheme.tertiaryContainer
            else MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border =
            BorderStroke(
                1.dp,
                if (selected) MaterialTheme.colorScheme.tertiary
                else MaterialTheme.colorScheme.outlineVariant,
            ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                label,
                fontSize = 10.sp,
                color =
                    if (selected) MaterialTheme.colorScheme.onSurface.copy(alpha = .85f)
                    else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                n.title.ifBlank { "无标题" },
                Modifier.padding(top = 6.dp),
                fontSize = if (selected) 19.sp else 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
