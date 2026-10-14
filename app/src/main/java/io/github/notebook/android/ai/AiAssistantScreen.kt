package io.github.notebook.android.ai

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
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
import androidx.compose.material.icons.filled.NoteAdd
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
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import io.github.notebook.android.data.NoteEntity
import io.github.notebook.android.data.NoteSummary
import io.github.notebook.android.sync.SyncRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.UUID

private data class Message(val id: String = UUID.randomUUID().toString(), val user: Boolean, val content: String, val sources: List<ContextSource> = emptyList(), val failed: Boolean = false)
private data class Hit(val note: NoteSummary, val path: String, val reason: String)
private enum class Tab(val label: String) { Ask("问答"), Generate("生成"), Search("搜索"), Merge("合并") }

/**
 * AI assistant: pick folders or notes as the scope, then ask, generate,
 * search by meaning, or merge the notes into one Markdown document.
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
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val notes by repo.notes.collectAsState(initial = emptyList())
    val folders by repo.folders.collectAsState(initial = emptyList())
    var settings by remember { mutableStateOf(repo.aiSettings()) }
    var aiScope by remember { mutableStateOf(if (initialNoteId != null) AiScope(noteIds = setOf(initialNoteId)) else AiScope()) }
    val scoped = remember(notes, folders, aiScope) { AiAssistant.resolve(aiScope, notes, folders) { repo.isEncrypted(it) } }
    var tab by remember { mutableStateOf(Tab.Ask) }
    val messages = remember { mutableStateListOf<Message>() }
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    var hits by remember { mutableStateOf<List<Hit>?>(null) }
    var merged by remember { mutableStateOf<List<AiAssistant.Merged>?>(null) }
    var showPicker by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    val client = remember { AiClient() }
    val listState = rememberLazyListState()
    LaunchedEffect(scoped) { merged = null; hits = null }
    LaunchedEffect(messages.size, busy) { if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size) }
    LaunchedEffect(notice) { if (notice != null) { kotlinx.coroutines.delay(2500); notice = null } }

    val label = when {
        aiScope.isEmpty -> "全部笔记"
        else -> (aiScope.folderIds.mapNotNull { id -> folders.firstOrNull { it.id == id }?.name } + aiScope.noteIds.mapNotNull { id -> notes.firstOrNull { it.id == id }?.title } + listOfNotNull(aiScope.extraNoteIds.size.takeIf { it > 0 }?.let { "$it 篇搜索结果" }))
            .let { if (it.size > 2) it.take(2).joinToString("、") + " 等 ${it.size} 项" else it.joinToString("、") }
    }

    suspend fun loadMerged(): List<AiAssistant.Merged> {
        merged?.let { return it }
        val loaded = scoped.map { summary -> AiAssistant.Merged(summary, AiAssistant.path(summary, notes), repo.loadNote(summary.id)?.body.orEmpty()) }
        merged = loaded
        return loaded
    }

    fun run(instruction: String, display: String, keepHistory: Boolean) {
        if (busy != null) return
        if (!settings.configured) { showSettings = true; return }
        if (scoped.isEmpty()) { notice = "范围里没有笔记"; return }
        val history = if (keepHistory) messages.filter { !it.failed }.map { ChatMessage(if (it.user) "user" else "assistant", it.content) } else emptyList()
        messages += Message(user = true, content = display)
        input = ""
        job = scope.launch {
            try {
                busy = "正在读取笔记…"
                val context = AiAssistant.context(loadMerged(), if (keepHistory) instruction else "")
                busy = "AI 正在思考…"
                val answer = client.complete(settings, AiAssistant.messages(instruction, context, history))
                val cited = AiAssistant.citedSources(answer).toSet()
                messages += Message(user = false, content = answer, sources = context.sources.filter { it.index in cited })
            } catch (error: kotlinx.coroutines.CancellationException) {
                messages += Message(user = false, content = "已停止。", failed = true)
            } catch (error: Throwable) {
                messages += Message(user = false, content = error.message ?: "AI 请求失败", failed = true)
            } finally { busy = null; job = null }
        }
    }

    fun search(query: String) {
        if (busy != null || query.isBlank()) return
        job = scope.launch {
            try {
                busy = "正在搜索…"
                val matching = repo.searchNoteIds(query).toSet()
                val keyword = scoped.filter { it.id in matching || it.title.contains(query, ignoreCase = true) }.map { Hit(it, AiAssistant.path(it, notes), it.preview.take(120)) }
                if (!settings.configured) { hits = keyword; return@launch }
                busy = "AI 正在按意思筛选…"
                val candidates = scoped.take(200).mapIndexed { index, note -> Triple(index + 1, "${note.title}（${AiAssistant.path(note, notes)}）", note.preview.replace(Regex("\\s+"), " ").take(160)) }
                val ranked = AiAssistant.parseSearchResults(client.complete(settings, AiAssistant.searchMessages(query, candidates)))
                val aiHits = ranked.mapNotNull { (index, reason) -> scoped.getOrNull(index - 1)?.let { Hit(it, AiAssistant.path(it, notes), reason) } }
                val seen = aiHits.map { it.note.id }.toSet()
                hits = aiHits + keyword.filter { it.note.id !in seen }
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Throwable) {
                notice = error.message ?: "搜索失败"
            } finally { busy = null; job = null }
        }
    }

    fun saveNote(title: String, markdown: String) {
        scope.launch {
            val folder = folders.firstOrNull { it.id in aiScope.folderIds } ?: scoped.firstOrNull()?.folderId?.let { id -> folders.firstOrNull { it.id == id } }
            val saved = repo.save(NoteEntity(UUID.randomUUID().toString(), title = title.take(60).ifBlank { "AI 回答" }, body = markdown, folderId = folder?.id, folderName = folder?.name ?: "未分类", viewMode = "preview"))
            notice = "已存为新笔记"
            onOpenNote(saved.id)
        }
    }

    fun share(markdown: String) {
        val intent = if (markdown.length < 100_000) Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, markdown) else {
            val directory = java.io.File(context.cacheDir, "exports").apply { mkdirs() }
            val file = java.io.File(directory, "${label.replace(Regex("[\\\\/:*?\"<>|]"), " ").take(40)}.md").apply { writeText(markdown) }
            Intent(Intent.ACTION_SEND).setType("text/markdown").putExtra(Intent.EXTRA_STREAM, FileProvider.getUriForFile(context, "${context.packageName}.files", file)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "分享合并的笔记"))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(8.dp)); Text("AI 助手") } },
                navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
                actions = { IconButton({ showSettings = true }) { Icon(Icons.Default.Settings, "AI 设置") } },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().imePadding()) {
            AssistChip(
                onClick = { showPicker = true },
                label = { Text("范围：$label · ${scoped.size} 篇", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                leadingIcon = { Icon(Icons.Default.FilterList, null, Modifier.size(AssistChipDefaults.IconSize)) },
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            PrimaryTabRow(selectedTabIndex = tab.ordinal) { Tab.entries.forEach { item -> Tab(selected = tab == item, onClick = { tab = item }, text = { Text(item.label) }) } }
            if (busy != null) LinearProgressIndicator(Modifier.fillMaxWidth())
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (tab) {
                    Tab.Ask, Tab.Generate -> LazyColumn(state = listState, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.fillMaxSize()) {
                        if (messages.isEmpty()) item {
                            Hint(
                                if (tab == Tab.Ask) "基于选中的笔记提问" else "基于选中的笔记生成内容",
                                if (tab == Tab.Ask) "回答会标出依据来自哪篇笔记，点来源可以打开它。" else "选择下面的一种，或者直接写你的要求。",
                                if (!settings.configured) ({ showSettings = true }) else null,
                            )
                        }
                        items(messages, key = { it.id }) { message ->
                            if (message.user) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                Surface(shape = RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp), color = MaterialTheme.colorScheme.secondaryContainer) { Text(message.content, Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) }
                            } else Column {
                                if (message.failed) Text(message.content, color = MaterialTheme.colorScheme.onSurfaceVariant) else renderMarkdown(message.content, Modifier.fillMaxWidth())
                                if (message.sources.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
                                    message.sources.forEach { source -> AssistChip(onClick = { onOpenNote(source.noteId) }, label = { Text("${source.index} ${source.title}", maxLines = 1, overflow = TextOverflow.Ellipsis) }) }
                                }
                                if (!message.failed) Row {
                                    TextButton({ clipboard.setText(AnnotatedString(message.content)); notice = "已复制" }) { Icon(Icons.Default.ContentCopy, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("复制") }
                                    TextButton({ saveNote(messages.getOrNull(messages.indexOf(message) - 1)?.content ?: "AI 回答", message.content) }) { Icon(Icons.Default.NoteAdd, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("存为笔记") }
                                }
                            }
                        }
                        busy?.let { item { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) } }
                    }
                    Tab.Search -> LazyColumn(contentPadding = PaddingValues(vertical = 8.dp), modifier = Modifier.fillMaxSize()) {
                        val current = hits
                        when {
                            current == null -> item { Box(Modifier.padding(16.dp)) { Hint("在范围里找笔记", if (settings.configured) "可以描述你要找的内容，AI 会按意思匹配。" else "按关键词搜索；设置 AI 后还能按意思查找。", null) } }
                            current.isEmpty() -> item { Box(Modifier.padding(16.dp)) { Hint("没有找到", "换个说法，或扩大范围。", null) } }
                            else -> {
                                item {
                                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Text("${current.size} 篇", Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        TextButton({ aiScope = AiScope(extraNoteIds = current.map { it.note.id }.toSet()); tab = Tab.Ask }) { Text("用这些笔记提问") }
                                    }
                                }
                                items(current, key = { it.note.id }) { hit ->
                                    ListItem(
                                        headlineContent = { Text(hit.note.title.ifBlank { "无标题" }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                        overlineContent = { Text(hit.path, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                        supportingContent = hit.reason.takeIf(String::isNotBlank)?.let { { Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis) } },
                                        modifier = Modifier.clickable { onOpenNote(hit.note.id) },
                                    )
                                }
                            }
                        }
                    }
                    Tab.Merge -> Column(Modifier.fillMaxSize().padding(16.dp)) {
                        val current = merged
                        if (current == null) {
                            Hint("把 ${scoped.size} 篇笔记合并成一份 Markdown", "按目录顺序排列，每篇以标题开头并注明位置。可以复制、分享、存为笔记，或拿去问 AI。", null)
                            Button({ scope.launch { busy = "正在合并…"; try { loadMerged() } finally { busy = null } } }, enabled = busy == null && scoped.isNotEmpty(), modifier = Modifier.padding(top = 12.dp)) { Text("合并") }
                        } else {
                            val text = remember(current, label) { AiAssistant.mergedMarkdown(current, label) }
                            Text("${current.size} 篇 · 约 ${text.length} 字", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                TextButton({ clipboard.setText(AnnotatedString(text)); notice = "已复制合并后的 Markdown" }) { Icon(Icons.Default.ContentCopy, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("复制") }
                                TextButton({ share(text) }) { Icon(Icons.Default.Share, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("分享") }
                                TextButton({ saveNote("合并：$label", text) }) { Icon(Icons.Default.NoteAdd, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("存为笔记") }
                                TextButton({ tab = Tab.Ask }) { Icon(Icons.Default.AutoAwesome, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("拿去问 AI") }
                            }
                            Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.weight(1f).fillMaxWidth()) {
                                Text(if (text.length > 40_000) text.take(40_000) + "\n\n…（预览只显示前 4 万字，复制和分享的是全部内容）" else text, fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 18.sp, modifier = Modifier.verticalScroll(rememberScrollState()).padding(12.dp))
                            }
                        }
                    }
                }
            }
            if (tab == Tab.Generate) FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AiAssistant.Task.entries.forEach { task -> AssistChip(enabled = busy == null, onClick = { run(task.instruction + if (input.isNotBlank()) "\n补充要求：${input.trim()}" else "", task.label + if (input.isNotBlank()) "：${input.trim()}" else "", false) }, label = { Text(task.label) }) }
            }
            if (tab != Tab.Merge) Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp).navigationBarsPadding(), verticalAlignment = Alignment.Bottom) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    placeholder = { Text(when (tab) { Tab.Ask -> "向 ${scoped.size} 篇笔记提问…"; Tab.Search -> "描述要找的内容…"; else -> "写下要求" }) },
                    modifier = Modifier.weight(1f).heightIn(max = 160.dp),
                    shape = RoundedCornerShape(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                if (busy != null && tab != Tab.Search) FilledIconButton({ job?.cancel() }) { Icon(Icons.Default.Stop, "停止") }
                else FilledIconButton(onClick = {
                    when (tab) {
                        Tab.Ask -> if (input.isNotBlank()) run(input.trim(), input.trim(), true)
                        Tab.Generate -> if (input.isNotBlank()) run(input.trim(), input.trim(), false)
                        Tab.Search -> search(input.trim())
                        Tab.Merge -> Unit
                    }
                }, enabled = input.isNotBlank() && busy == null) { Icon(Icons.AutoMirrored.Filled.Send, "发送") }
            }
            notice?.let { Text(it, Modifier.fillMaxWidth().padding(bottom = 6.dp), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall, textAlign = androidx.compose.ui.text.style.TextAlign.Center) }
        }
    }

    if (showPicker) ModalBottomSheet(onDismissRequest = { showPicker = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        ScopePicker(aiScope, notes.filter { it.deletedAt == null && it.itemType != "todo" && !repo.isEncrypted(it) }, folders.filter { it.type != "encryptedFolder" && it.type != "todoList" }, { aiScope = it }) { showPicker = false }
    }
    if (showSettings) AiSettingsDialog(settings, onDismiss = { showSettings = false }) { updated ->
        runCatching { repo.saveAiSettings(updated) }.onSuccess { settings = updated; showSettings = false; notice = "AI 设置已保存" }.onFailure { notice = it.message }
    }
}

@Composable
private fun Hint(title: String, body: String, onSetup: (() -> Unit)?) {
    Column(Modifier.fillMaxWidth().padding(top = 24.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        if (onSetup != null) Button(onSetup) { Text("设置 AI") }
    }
}

@Composable
private fun ScopePicker(scope: AiScope, notes: List<NoteSummary>, folders: List<io.github.notebook.android.data.FolderEntity>, onChange: (AiScope) -> Unit, onDone: () -> Unit) {
    var filter by remember { mutableStateOf("") }
    val ordered = remember(notes, folders) { AiAssistant.treeOrder(notes, folders) }
    val byId = remember(notes) { notes.associateBy { it.id } }
    fun depth(note: NoteSummary): Int { var depth = 0; var cursor = note.parentPageId?.let(byId::get); while (cursor != null && depth < 16) { depth++; cursor = cursor.parentPageId?.let(byId::get) }; return depth }
    fun covered(note: NoteSummary): Boolean { var cursor = note.parentPageId?.let(byId::get); var guard = 0; while (cursor != null && guard++ < 32) { if (cursor.id in scope.noteIds) return true; cursor = cursor.parentPageId?.let(byId::get) }; return note.folderId in scope.folderIds }
    Column(Modifier.fillMaxWidth().heightIn(max = 640.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("选择范围", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton({ onChange(AiScope()) }) { Text("全部笔记") }
            Button(onDone) { Text("完成") }
        }
        OutlinedTextField(filter, { filter = it }, placeholder = { Text("筛选笔记") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp))
        HorizontalDivider()
        LazyColumn(Modifier.fillMaxWidth()) {
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
                    Row(Modifier.fillMaxWidth().clickable(enabled = !inherited) { onChange(scope.copy(extraNoteIds = emptySet(), noteIds = if (note.id in scope.noteIds) scope.noteIds - note.id else scope.noteIds + note.id)) }.padding(start = (8 + (if (filter.isBlank()) depth(note) * 16 else 0) + 16).dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
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
