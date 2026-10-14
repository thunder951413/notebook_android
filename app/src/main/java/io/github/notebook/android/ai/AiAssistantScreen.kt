package io.github.notebook.android.ai

import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.MergeType
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import io.github.notebook.android.data.FolderEntity
import io.github.notebook.android.data.NoteEntity
import io.github.notebook.android.data.NoteSummary
import io.github.notebook.android.sync.SyncRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

/** One entry in the conversation. Search and merge results are messages too. */
private sealed interface Entry {
    val id: String
    data class Question(override val id: String = UUID.randomUUID().toString(), val text: String) : Entry
    data class Answer(override val id: String = UUID.randomUUID().toString(), val question: String, val text: String, val sources: List<ContextSource>) : Entry
    data class Hits(override val id: String = UUID.randomUUID().toString(), val query: String, val hits: List<Hit>) : Entry
    data class Merged(override val id: String = UUID.randomUUID().toString(), val count: Int, val markdown: String) : Entry
    data class Failure(override val id: String = UUID.randomUUID().toString(), val text: String) : Entry
}

private data class Hit(val note: NoteSummary, val path: String, val reason: String)

private const val PREFS = "ai_assistant"
private const val SCOPE_KEY = "scope"

private fun savedScope(context: Context) = AiAssistant.scopeFromJson(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(SCOPE_KEY, null))
private fun saveScope(context: Context, scope: AiScope) { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(SCOPE_KEY, AiAssistant.scopeToJson(scope)).apply() }

/**
 * The AI conversation page. The scope bar on top remembers the last choice
 * (a picked note includes the notes below it). Common actions sit above the
 * input; "导出" turns selected answers into a new note.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AiAssistantScreen(
    repo: SyncRepository,
    initialNoteId: String?,
    renderMarkdown: @Composable (String, Modifier) -> Unit,
    onOpenNote: (String) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val notes by repo.notes.collectAsState(initial = emptyList())
    val folders by repo.folders.collectAsState(initial = emptyList())
    val tags by repo.tags.collectAsState(initial = emptyList())
    var settings by remember { mutableStateOf(repo.aiSettings()) }
    var aiScope by remember { mutableStateOf(if (initialNoteId != null) AiScope(noteIds = setOf(initialNoteId)) else savedScope(context)) }
    LaunchedEffect(aiScope) { saveScope(context, aiScope) }
    val scoped = remember(notes, folders, aiScope) { AiAssistant.resolve(aiScope, notes, folders) { repo.isEncrypted(it) } }
    val entries = remember { mutableStateListOf<Entry>() }
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    var merged by remember { mutableStateOf<List<AiAssistant.Merged>?>(null) }
    var showPicker by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }
    val selected = remember { mutableStateListOf<String>() }
    var exportDialog by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    val client = remember { AiClient() }
    val listState = rememberLazyListState()
    BackHandler { if (exporting) { exporting = false; selected.clear() } else onBack() }
    LaunchedEffect(scoped) { merged = null }
    LaunchedEffect(entries.size, busy) { if (entries.isNotEmpty()) listState.animateScrollToItem(entries.size) }
    LaunchedEffect(notice) { if (notice != null) { delay(2500); notice = null } }

    val label = when {
        aiScope.isEmpty -> "全部笔记"
        else -> (aiScope.folderIds.mapNotNull { id -> folders.firstOrNull { it.id == id }?.name } + aiScope.noteIds.mapNotNull { id -> notes.firstOrNull { it.id == id }?.title?.ifBlank { "无标题" } } + aiScope.tagIds.mapNotNull { id -> tags.firstOrNull { it.id == id }?.name?.let { "#$it" } } + listOfNotNull(aiScope.extraNoteIds.size.takeIf { it > 0 }?.let { "$it 篇搜索结果" }))
            .let { if (it.isEmpty()) "全部笔记" else if (it.size > 2) it.take(2).joinToString("、") + " 等 ${it.size} 项" else it.joinToString("、") }
    }

    suspend fun loadMerged(): List<AiAssistant.Merged> {
        merged?.let { return it }
        val loaded = scoped.map { summary -> AiAssistant.Merged(summary, AiAssistant.path(summary, notes), repo.loadNote(summary.id)?.body.orEmpty()) }
        merged = loaded
        return loaded
    }

    fun launch(block: suspend () -> Unit) {
        if (busy != null) return
        job = scope.launch {
            try { block() }
            catch (error: kotlinx.coroutines.CancellationException) { entries += Entry.Failure(text = "已停止。") }
            catch (error: Throwable) { entries += Entry.Failure(text = error.message ?: "AI 请求失败") }
            finally { busy = null; job = null }
        }
    }

    fun ask(instruction: String, display: String, conversational: Boolean) {
        if (!settings.configured) { showSettings = true; return }
        if (scoped.isEmpty()) { notice = "范围里没有笔记"; return }
        val history = if (!conversational) emptyList() else entries.flatMap {
            when (it) { is Entry.Question -> listOf(ChatMessage("user", it.text)); is Entry.Answer -> listOf(ChatMessage("assistant", it.text)); else -> emptyList() }
        }
        entries += Entry.Question(text = display)
        input = ""
        launch {
            busy = "正在读取笔记…"
            val context = AiAssistant.context(loadMerged(), if (conversational) instruction else "")
            busy = "AI 正在思考…"
            val answer = client.complete(settings, AiAssistant.messages(instruction, context, history))
            val cited = AiAssistant.citedSources(answer).toSet()
            entries += Entry.Answer(question = display, text = answer, sources = context.sources.filter { it.index in cited })
        }
    }

    fun search(query: String) {
        if (query.isBlank()) { notice = "先在下面写要找的内容"; return }
        entries += Entry.Question(text = "搜索：$query")
        input = ""
        launch {
            busy = "正在搜索…"
            val matching = repo.searchNoteIds(query)
            val keyword = scoped.filter { it.id in matching || it.title.contains(query, ignoreCase = true) }.map { Hit(it, AiAssistant.path(it, notes), it.preview.take(120)) }
            val hits = if (!settings.configured) keyword else {
                busy = "AI 正在按意思筛选…"
                val candidates = scoped.take(200).mapIndexed { index, note -> Triple(index + 1, "${note.title}（${AiAssistant.path(note, notes)}）", note.preview.replace(Regex("\\s+"), " ").take(160)) }
                val ranked = AiAssistant.parseSearchResults(client.complete(settings, AiAssistant.searchMessages(query, candidates)))
                val aiHits = ranked.mapNotNull { (index, reason) -> scoped.getOrNull(index - 1)?.let { Hit(it, AiAssistant.path(it, notes), reason) } }
                val seen = aiHits.map { it.note.id }.toSet()
                aiHits + keyword.filter { it.note.id !in seen }
            }
            entries += Entry.Hits(query = query, hits = hits)
        }
    }

    fun merge() {
        if (scoped.isEmpty()) { notice = "范围里没有笔记"; return }
        entries += Entry.Question(text = "合并 ${scoped.size} 篇笔记")
        launch {
            busy = "正在合并…"
            val notesInScope = loadMerged()
            entries += Entry.Merged(count = notesInScope.size, markdown = AiAssistant.mergedMarkdown(notesInScope, label))
        }
    }

    fun share(markdown: String) {
        val intent = if (markdown.length < 100_000) Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, markdown) else {
            val directory = java.io.File(context.cacheDir, "exports").apply { mkdirs() }
            val file = java.io.File(directory, "${label.replace(Regex("[\\\\/:*?\"<>|]"), " ").take(40)}.md").apply { writeText(markdown) }
            Intent(Intent.ACTION_SEND).setType("text/markdown").putExtra(Intent.EXTRA_STREAM, FileProvider.getUriForFile(context, "${context.packageName}.files", file)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "分享"))
    }

    fun saveNote(title: String, markdown: String, location: NoteLocation, open: Boolean) {
        scope.launch {
            runCatching {
                val folder = if (location.inbox) repo.inboxFolder() else location.folder
                val parentId = location.parent?.id
                val siblings = notes.filter { it.folderId == folder?.id && it.parentPageId == parentId && it.deletedAt == null }
                val id = UUID.randomUUID().toString()
                if (folder?.type == "encryptedFolder") repo.markEncrypted(id, folder.id)
                repo.save(NoteEntity(id, title = title.ifBlank { "AI 回答" }.take(80), body = markdown, folderId = folder?.id, folderName = folder?.name ?: "未分类", parentPageId = parentId, sortOrder = (siblings.maxOfOrNull { it.sortOrder } ?: 0.0) + 1000.0, viewMode = "preview"))
            }.onSuccess { note ->
                notice = if (location.inbox) "已存到“待处理”" else "已生成笔记"
                exporting = false; selected.clear()
                if (open) onOpenNote(note.id)
            }.onFailure { notice = it.message ?: "保存失败" }
        }
    }

    val answers = entries.filterIsInstance<Entry.Answer>()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { if (exporting) Text("选择要导出的回复") else Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(8.dp)); Text("AI") } },
                navigationIcon = { IconButton({ if (exporting) { exporting = false; selected.clear() } else onBack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, if (exporting) "取消导出" else "返回") } },
                actions = {
                    if (!exporting) {
                        TextButton({ if (answers.isEmpty()) notice = "还没有可以导出的回复" else { exporting = true; selected.clear(); answers.lastOrNull()?.let { selected += it.id } } }, modifier = Modifier.testTag("ai-export")) { Icon(Icons.Default.NoteAdd, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("导出") }
                        IconButton({ showSettings = true }) { Icon(Icons.Default.Settings, "AI 设置") }
                    } else TextButton({ if (selected.size == answers.size) selected.clear() else { selected.clear(); selected += answers.map { it.id } } }) { Text(if (selected.size == answers.size) "全不选" else "全选") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().imePadding()) {
            // Scope bar.
            Surface(onClick = { showPicker = true }, enabled = !exporting, shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).testTag("ai-scope")) {
                Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.FilterList, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text("范围：$label", Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                    Text("${scoped.size} 篇", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (busy != null) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
            LazyColumn(state = listState, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.weight(1f).fillMaxWidth()) {
                if (entries.isEmpty()) item {
                    Column(Modifier.fillMaxWidth().padding(top = 32.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("基于这 ${scoped.size} 篇笔记对话", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text("直接提问，或用下面的常用功能。回答会标出依据来自哪篇笔记；点右上角“导出”可以把选中的回复生成一篇新笔记。", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                        if (!settings.configured) Button({ showSettings = true }, Modifier.padding(top = 8.dp)) { Text("设置 AI") }
                    }
                }
                items(entries, key = { it.id }) { entry ->
                    when (entry) {
                        is Entry.Question -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            Surface(shape = RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp), color = MaterialTheme.colorScheme.secondaryContainer) { Text(entry.text, Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) }
                        }
                        is Entry.Answer -> Row(Modifier.fillMaxWidth().then(if (exporting) Modifier.clickable { if (entry.id in selected) selected -= entry.id else selected += entry.id } else Modifier)) {
                            if (exporting) Checkbox(entry.id in selected, { if (it) selected += entry.id else selected -= entry.id })
                            Column(Modifier.weight(1f)) {
                                renderMarkdown(entry.text, Modifier.fillMaxWidth())
                                if (entry.sources.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
                                    entry.sources.forEach { source -> AssistChip(onClick = { if (!exporting) onOpenNote(source.noteId) }, label = { Text("${source.index} ${source.title}", maxLines = 1, overflow = TextOverflow.Ellipsis) }) }
                                }
                                if (!exporting) Row {
                                    TextButton({ clipboard.setText(AnnotatedString(entry.text)); notice = "已复制" }) { Icon(Icons.Default.ContentCopy, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("复制") }
                                    TextButton({ exporting = true; selected.clear(); selected += entry.id; exportDialog = true }) { Icon(Icons.Default.NoteAdd, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("存为笔记") }
                                }
                            }
                        }
                        is Entry.Hits -> Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(vertical = 6.dp)) {
                                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(if (entry.hits.isEmpty()) "没有找到相关笔记" else "找到 ${entry.hits.size} 篇", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                                    if (entry.hits.isNotEmpty()) TextButton({ aiScope = AiScope(extraNoteIds = entry.hits.map { it.note.id }.toSet()); notice = "已把范围换成这 ${entry.hits.size} 篇" }) { Text("用这些提问") }
                                }
                                entry.hits.take(12).forEach { hit ->
                                    ListItem(
                                        headlineContent = { Text(hit.note.title.ifBlank { "无标题" }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                        overlineContent = { Text(hit.path, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                        supportingContent = hit.reason.takeIf(String::isNotBlank)?.let { { Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis) } },
                                        modifier = Modifier.clickable(enabled = !exporting) { onOpenNote(hit.note.id) },
                                    )
                                }
                            }
                        }
                        is Entry.Merged -> Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(14.dp)) {
                                Text("已合并 ${entry.count} 篇，约 ${entry.markdown.length} 字", style = MaterialTheme.typography.labelLarge)
                                Text(entry.markdown.take(240).replace(Regex("\\s+"), " ") + "…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(vertical = 6.dp))
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    TextButton({ clipboard.setText(AnnotatedString(entry.markdown)); notice = "已复制合并后的 Markdown" }) { Icon(Icons.Default.ContentCopy, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("复制") }
                                    TextButton({ share(entry.markdown) }) { Icon(Icons.Default.Share, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("分享") }
                                    TextButton({ saveNote("合并：$label", entry.markdown, NoteLocation(inbox = true), open = true) }) { Icon(Icons.Default.Inbox, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("存到待处理") }
                                }
                            }
                        }
                        is Entry.Failure -> Text(entry.text, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                busy?.let { item { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) } }
            }
            if (exporting) {
                HorizontalDivider()
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp).navigationBarsPadding(), verticalAlignment = Alignment.CenterVertically) {
                    Text("已选 ${selected.size} 条回复", Modifier.weight(1f))
                    Button({ exportDialog = true }, enabled = selected.isNotEmpty(), modifier = Modifier.testTag("ai-export-confirm")) { Text("生成笔记") }
                }
            } else {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AiAssistant.Task.entries.forEach { task ->
                        AssistChip(enabled = busy == null, onClick = { ask(task.instruction + if (input.isNotBlank()) "\n补充要求：${input.trim()}" else "", task.label + if (input.isNotBlank()) "：${input.trim()}" else "", false) }, label = { Text(task.label) })
                    }
                    AssistChip(enabled = busy == null, onClick = { search(input.trim()) }, label = { Text("按意思搜索") }, leadingIcon = { Icon(Icons.Default.Search, null, Modifier.size(AssistChipDefaults.IconSize)) })
                    AssistChip(enabled = busy == null, onClick = { merge() }, label = { Text("合并笔记") }, leadingIcon = { Icon(Icons.Default.MergeType, null, Modifier.size(AssistChipDefaults.IconSize)) })
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp).navigationBarsPadding(), verticalAlignment = Alignment.Bottom) {
                    OutlinedTextField(value = input, onValueChange = { input = it }, placeholder = { Text("向 ${scoped.size} 篇笔记提问…") }, modifier = Modifier.weight(1f).heightIn(max = 160.dp).testTag("ai-input"), shape = RoundedCornerShape(20.dp))
                    Spacer(Modifier.width(8.dp))
                    if (busy != null) FilledIconButton({ job?.cancel() }) { Icon(Icons.Default.Stop, "停止") }
                    else FilledIconButton(onClick = { if (input.isNotBlank()) ask(input.trim(), input.trim(), true) }, enabled = input.isNotBlank()) { Icon(Icons.AutoMirrored.Filled.Send, "发送") }
                }
            }
            AnimatedVisibility(notice != null) { Text(notice.orEmpty(), Modifier.fillMaxWidth().padding(bottom = 6.dp), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center) }
        }
    }

    if (showPicker) ModalBottomSheet(onDismissRequest = { showPicker = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        ScopePicker(aiScope, notes.filter { it.deletedAt == null && it.itemType != "todo" && !repo.isEncrypted(it) }, folders.filter { it.type != "encryptedFolder" && it.type != "todoList" }, tags, { aiScope = it }) { showPicker = false }
    }
    if (showSettings) AiSettingsDialog(settings, onDismiss = { showSettings = false }) { updated ->
        runCatching { repo.saveAiSettings(updated) }.onSuccess { settings = updated; showSettings = false; notice = "AI 设置已保存" }.onFailure { notice = it.message }
    }
    if (exportDialog) {
        val items = answers.filter { it.id in selected }.map { AiAssistant.ExportItem(it.question, it.text, it.sources) }
        ExportDialog(
            defaultTitle = AiAssistant.exportTitle(items),
            count = items.size,
            folders = folders.filter { it.type == "noteFolder" },
            notes = notes.filter { it.deletedAt == null && it.itemType != "todo" && !repo.isEncrypted(it) },
            onDismiss = { exportDialog = false },
        ) { title, location, open ->
            exportDialog = false
            saveNote(title, AiAssistant.exportMarkdown(items), location, open)
        }
    }
}

/** Where a new note goes: 待处理, a library's top level, or below a note. */
data class NoteLocation(val inbox: Boolean = false, val folder: FolderEntity? = null, val parent: NoteSummary? = null)

@Composable
private fun ExportDialog(defaultTitle: String, count: Int, folders: List<FolderEntity>, notes: List<NoteSummary>, onDismiss: () -> Unit, onSave: (String, NoteLocation, Boolean) -> Unit) {
    var title by remember { mutableStateOf(defaultTitle) }
    var location by remember { mutableStateOf(NoteLocation(inbox = true)) }
    var query by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("把 $count 条回复生成笔记") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text("标题") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("放到", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
                LocationRow("待处理", location.inbox) { location = NoteLocation(inbox = true) }
                folders.sortedBy { it.sortOrder }.filter { it.name != io.github.notebook.android.sync.INBOX_NAME }.forEach { folder -> LocationRow(folder.name, !location.inbox && location.parent == null && location.folder?.id == folder.id) { location = NoteLocation(folder = folder) } }
                LocationRow("未分类", !location.inbox && location.parent == null && location.folder == null) { location = NoteLocation() }
                OutlinedTextField(query, { query = it }, placeholder = { Text("或搜索一篇笔记，放在它下面") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                val matches = remember(query, notes) { if (query.isBlank()) emptyList() else notes.filter { it.title.contains(query.trim(), ignoreCase = true) }.take(20) }
                (listOfNotNull(location.parent?.takeIf { parent -> matches.none { it.id == parent.id } }) + matches).forEach { note ->
                    LocationRow("“${note.title.ifBlank { "无标题" }}”下面", location.parent?.id == note.id) { location = NoteLocation(folder = folders.firstOrNull { it.id == note.folderId }, parent = note) }
                }
            }
        },
        confirmButton = { Button({ onSave(title.trim(), location, true) }, modifier = Modifier.testTag("ai-export-save")) { Text("生成并打开") } },
        dismissButton = { Row { TextButton(onDismiss) { Text("取消") }; TextButton({ onSave(title.trim(), location, false) }) { Text("只保存") } } },
    )
}

@Composable
private fun LocationRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, onClick)
        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ScopePicker(scope: AiScope, notes: List<NoteSummary>, folders: List<FolderEntity>, tags: List<io.github.notebook.android.data.TagEntity>, onChange: (AiScope) -> Unit, onDone: () -> Unit) {
    var filter by remember { mutableStateOf("") }
    var byTag by remember { mutableStateOf(scope.tagIds.isNotEmpty() && scope.noteIds.isEmpty() && scope.folderIds.isEmpty()) }
    val tagCounts = remember(notes) { notes.flatMap { it.tagIdSet() }.groupingBy { it }.eachCount() }
    val ordered = remember(notes, folders) { AiAssistant.treeOrder(notes, folders) }
    val byId = remember(notes) { notes.associateBy { it.id } }
    fun depth(note: NoteSummary): Int { var depth = 0; var cursor = note.parentPageId?.let(byId::get); while (cursor != null && depth < 16) { depth++; cursor = cursor.parentPageId?.let(byId::get) }; return depth }
    fun covered(note: NoteSummary): Boolean { var cursor = note.parentPageId?.let(byId::get); var guard = 0; while (cursor != null && guard++ < 32) { if (cursor.id in scope.noteIds) return true; cursor = cursor.parentPageId?.let(byId::get) }; return note.folderId in scope.folderIds }
    Column(Modifier.fillMaxWidth().heightIn(max = 640.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("选择范围", style = MaterialTheme.typography.titleMedium)
                Text(if (byTag) "带任一勾选标签的笔记都会用上" else "勾选笔记时，它下面的笔记也会一起用上", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton({ onChange(AiScope()) }) { Text("全部笔记") }
            Button(onDone) { Text("完成") }
        }
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 8.dp)) {
            SegmentedButton(selected = !byTag, onClick = { byTag = false }, shape = SegmentedButtonDefaults.itemShape(0, 2), modifier = Modifier.testTag("ai-scope-notes")) { Text("按笔记" + (scope.folderIds.size + scope.noteIds.size).takeIf { it > 0 }?.let { "（$it）" }.orEmpty()) }
            SegmentedButton(selected = byTag, onClick = { byTag = true }, shape = SegmentedButtonDefaults.itemShape(1, 2), modifier = Modifier.testTag("ai-scope-tags")) { Text("按标签" + scope.tagIds.size.takeIf { it > 0 }?.let { "（$it）" }.orEmpty()) }
        }
        OutlinedTextField(filter, { filter = it }, placeholder = { Text(if (byTag) "筛选标签" else "筛选笔记") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp))
        HorizontalDivider()
        if (byTag) LazyColumn(Modifier.fillMaxWidth()) {
            val shown = tags.filter { filter.isBlank() || it.name.contains(filter.trim(), ignoreCase = true) }.sortedByDescending { tagCounts[it.id] ?: 0 }
            if (shown.isEmpty()) item(key = "no-tags") { Text(if (tags.isEmpty()) "还没有标签" else "没有匹配的标签", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(shown, key = { "tag-${it.id}" }) { tag ->
                Row(Modifier.fillMaxWidth().clickable { onChange(scope.copy(extraNoteIds = emptySet(), tagIds = if (tag.id in scope.tagIds) scope.tagIds - tag.id else scope.tagIds + tag.id)) }.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(tag.id in scope.tagIds, null)
                    Text("#${tag.name}", Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${tagCounts[tag.id] ?: 0} 篇", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = 8.dp))
                }
            }
        } else LazyColumn(Modifier.fillMaxWidth()) {
            folders.sortedBy { it.sortOrder }.forEach { folder ->
                val inFolder = ordered.filter { it.folderId == folder.id && (filter.isBlank() || it.title.contains(filter, ignoreCase = true)) }
                if (inFolder.isEmpty() && filter.isNotBlank()) return@forEach
                item(key = "folder-${folder.id}") {
                    Row(Modifier.fillMaxWidth().clickable { onChange(scope.copy(extraNoteIds = emptySet(), folderIds = if (folder.id in scope.folderIds) scope.folderIds - folder.id else scope.folderIds + folder.id)) }.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(folder.id in scope.folderIds, null)
                        Text(folder.name, fontWeight = FontWeight.SemiBold)
                    }
                }
                items(inFolder, key = { it.id }) { note ->
                    val inherited = covered(note)
                    Row(Modifier.fillMaxWidth().clickable(enabled = !inherited) { onChange(scope.copy(extraNoteIds = emptySet(), noteIds = if (note.id in scope.noteIds) scope.noteIds - note.id else scope.noteIds + note.id)) }.padding(start = (24 + (if (filter.isBlank()) depth(note) * 16 else 0)).dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(inherited || note.id in scope.noteIds, null, enabled = !inherited)
                        Text(note.title.ifBlank { "无标题" }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            val unfiled = ordered.filter { it.folderId == null && (filter.isBlank() || it.title.contains(filter, ignoreCase = true)) }
            if (unfiled.isNotEmpty()) {
                item(key = "unfiled") { Text("未分类", Modifier.padding(horizontal = 16.dp, vertical = 10.dp), fontWeight = FontWeight.SemiBold) }
                items(unfiled, key = { it.id }) { note ->
                    val inherited = covered(note)
                    Row(Modifier.fillMaxWidth().clickable(enabled = !inherited) { onChange(scope.copy(extraNoteIds = emptySet(), noteIds = if (note.id in scope.noteIds) scope.noteIds - note.id else scope.noteIds + note.id)) }.padding(start = (24 + (if (filter.isBlank()) depth(note) * 16 else 0)).dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(inherited || note.id in scope.noteIds, null, enabled = !inherited)
                        Text(note.title.ifBlank { "无标题" }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AiSettingsDialog(current: AiSettings, onDismiss: () -> Unit, onSave: (AiSettings) -> Unit) {
    var provider by remember { mutableStateOf(current.provider) }
    var baseUrl by remember { mutableStateOf(current.baseUrl) }
    var model by remember { mutableStateOf(current.model) }
    var key by remember { mutableStateOf(current.apiKey) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("AI 设置") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    AiProvider.entries.forEachIndexed { index, item ->
                        SegmentedButton(selected = provider == item, onClick = { provider = item; if (item != AiProvider.Custom) { baseUrl = item.baseUrl; model = item.model } }, shape = SegmentedButtonDefaults.itemShape(index, AiProvider.entries.size)) { Text(if (item == AiProvider.Custom) "自定义" else item.label, maxLines = 1) }
                    }
                }
                OutlinedTextField(baseUrl, { baseUrl = it }, label = { Text("接口地址") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(model, { model = it }, label = { Text("模型") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(key, { key = it }, label = { Text("API Key") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
                Text("API Key 只加密保存在这台手机上，不会同步到其他设备。自定义可填任意 OpenAI 兼容接口，例如通义、Kimi 或局域网里的 Ollama（http://192.168.x.x:11434/v1）。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { Button({ onSave(AiSettings(provider, baseUrl.trim(), model.trim(), key.trim())) }, enabled = baseUrl.isNotBlank() && model.isNotBlank()) { Text("保存") } },
        dismissButton = { TextButton(onDismiss) { Text("取消") } },
    )
}
