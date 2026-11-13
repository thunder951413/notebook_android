@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package io.github.notebook.android

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.notebook.android.diagnostics.DiagnosticEntry
import io.github.notebook.android.diagnostics.DiagnosticLogger
import io.github.notebook.android.diagnostics.diagnosticEventLabel
import io.github.notebook.android.sync.DataSafetySummary
import io.github.notebook.android.sync.SyncRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.time.Instant
import java.util.Date
import java.util.Locale

private val SYNC_FRACTION_REGEX = Regex("(?<![0-9/\\-])(\\d+)\\s*/\\s*(\\d+)(?![0-9/\\-])")
private val SYNC_PERCENT_REGEX = Regex("(\\d{1,3})%")

/** Extracts a determinate progress fraction in [0f, 1f] when a sync status string reports X/Y or N%. */
internal fun parseSyncProgressFraction(text: String?): Float? {
    if (text.isNullOrBlank()) return null
    SYNC_FRACTION_REGEX.find(text)?.let { match ->
        val current = match.groupValues[1].toFloatOrNull() ?: return@let
        val total = match.groupValues[2].toFloatOrNull() ?: return@let
        if (total > 0f) return (current / total).coerceIn(0f, 1f)
    }
    SYNC_PERCENT_REGEX.find(text)?.let { match ->
        val pct = match.groupValues[1].toFloatOrNull() ?: return@let
        return (pct / 100f).coerceIn(0f, 1f)
    }
    return null
}

internal data class ReminderPermissions(val notifications: Boolean, val exactAlarms: Boolean) {
    val summary: String
        get() = when {
            !notifications -> "通知未允许"
            !exactAlarms -> "使用非精确提醒"
            else -> "通知和精确提醒已允许"
        }
}

@Composable
internal fun rememberReminderPermissions(refreshKey: Any? = null): ReminderPermissions {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    fun read(): ReminderPermissions {
        val notifications = Build.VERSION.SDK_INT < 33 ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        return ReminderPermissions(notifications, Build.VERSION.SDK_INT < 31 || alarmManager.canScheduleExactAlarms())
    }
    var permissions by remember(context, refreshKey) { mutableStateOf(read()) }
    DisposableEffect(owner, context, refreshKey) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) permissions = read()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    return permissions
}

@Composable
internal fun ReminderPermissionCard() {
    val context = LocalContext.current
    var notificationResult by remember { mutableStateOf<Boolean?>(null) }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        notificationResult = it
    }
    val permissions = rememberReminderPermissions(notificationResult)
    val notifications = permissions.notifications

    SettingsSection("系统权限") {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("通知", style = MaterialTheme.typography.titleMedium)
            Text(if (notifications) "已允许" else "未允许", style = MaterialTheme.typography.bodyMedium)
            if (!notifications && Build.VERSION.SDK_INT >= 33) {
                OutlinedButton({
                    if (notificationResult == false) {
                        context.startActivity(
                            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                        )
                    } else notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }) { Text("允许通知") }
            }
        }
        HorizontalDivider(Modifier.padding(horizontal = 16.dp))
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("精确提醒", style = MaterialTheme.typography.titleMedium)
            Text(
                if (permissions.exactAlarms) "已允许" else "将使用非精确提醒",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (!permissions.exactAlarms && Build.VERSION.SDK_INT >= 31) {
                OutlinedButton({
                    val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))
                    runCatching { context.startActivity(intent) }
                }) { Text("允许精确提醒") }
            }
        }
    }
}

@Composable
internal fun LocalBackupSafetyContent(
    repo: SyncRepository,
    encryptedUnlocked: Boolean = false,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var summary by remember { mutableStateOf<DataSafetySummary?>(null) }
    var working by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var includePrivate by remember { mutableStateOf(false) }
    var confirmPrivateExport by remember { mutableStateOf<(() -> Unit)?>(null) }
    var refreshTick by remember { mutableIntStateOf(0) }

    LaunchedEffect(refreshTick, working, encryptedUnlocked) {
        if (!working) {
            summary = runCatching { repo.dataSafetySummary() }.getOrNull()
        }
    }

    val saveBackupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        working = true
        feedback = "正在落盘全部草稿并写入校验备份包…" to false
        scope.launch {
            try {
                val exported = withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        repo.exportLocalBackup(out, includePrivate = includePrivate, encryptedUnlocked = encryptedUnlocked)
                    } ?: error("无法写入目标文件")
                }
                val suffix = if (exported.skippedMissingAttachments > 0) "（${exported.skippedMissingAttachments} 个云端未下载附件未纳入）" else ""
                feedback = "备份完成：${exported.noteCount} 篇笔记、${exported.todoCount} 条待办、${exported.attachmentCount} 个附件已写入并完成 SHA-256 校验$suffix" to false
                refreshTick++
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                feedback = "导出备份失败：${error.localizedMessage ?: "请重试"}" to true
            } finally {
                working = false
            }
        }
    }

    val restoreBackupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        working = true
        feedback = "正在校验备份包 SHA-256 清单并安全合并…" to false
        scope.launch {
            try {
                val restored = repo.restoreLocalBackup(uri, encryptedUnlocked = encryptedUnlocked)
                feedback = restored.message to false
                refreshTick++
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                feedback = "恢复备份失败：${error.localizedMessage ?: "文件损坏或格式不兼容"}" to true
            } finally {
                working = false
            }
        }
    }

    fun startBackupToDocument() {
        val filename = "Notebook-Backup-${SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())}.zip"
        if (includePrivate && encryptedUnlocked && (summary?.privateItems ?: 0) > 0) {
            confirmPrivateExport = { saveBackupLauncher.launch(filename) }
        } else {
            saveBackupLauncher.launch(filename)
        }
    }

    fun shareBackupArchive() {
        val action: () -> Unit = {
            working = true
            feedback = "正在生成可分享的安全备份包…" to false
            scope.launch {
                try {
                    val dir = File(context.cacheDir, "exports").apply { mkdirs() }
                    val file = File(dir, "Notebook-Backup-${SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())}.zip")
                    val exported = withContext(Dispatchers.IO) {
                        file.outputStream().use { out ->
                            repo.exportLocalBackup(out, includePrivate = includePrivate, encryptedUnlocked = encryptedUnlocked)
                        }
                    }
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                    val intent = Intent(Intent.ACTION_SEND).setType("application/zip")
                        .putExtra(Intent.EXTRA_STREAM, uri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    runCatching { context.startActivity(Intent.createChooser(intent, "分享 Notebook 本地备份")) }
                    feedback = "已生成备份包：${exported.noteCount} 篇笔记、${exported.todoCount} 条待办、${exported.attachmentCount} 个附件" to false
                    refreshTick++
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    feedback = "生成备份失败：${error.localizedMessage ?: "请重试"}" to true
                } finally {
                    working = false
                }
            }
        }
        if (includePrivate && encryptedUnlocked && (summary?.privateItems ?: 0) > 0) {
            confirmPrivateExport = action
        } else {
            action()
        }
    }

    feedback?.let { (text, isError) ->
        Surface(
            Modifier.fillMaxWidth(),
            color = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
            shape = MaterialTheme.shapes.medium,
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (working) LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(text, style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    SettingsSection("本机数据安全状态") {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val s = summary
            if (s == null) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    SafetyMetric("公开笔记", "${s.activeNotes} 篇")
                    SafetyMetric("待办事项", "${s.activeTodos} 条")
                    SafetyMetric("本机附件", "${s.localAttachments} 个")
                }
                HorizontalDivider()
                Text(
                    buildString {
                        append(if (s.pendingDrafts == 0) "全部编辑草稿已落盘保存" else "正在等待落盘草稿 ${s.pendingDrafts} 篇")
                        append(" · ")
                        append("待上传队列 ${s.pendingUploads} 条")
                        if (s.conflictCount > 0) append(" · 冲突保护副本 ${s.conflictCount} 篇")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (s.missingAttachments > 0) {
                    Text(
                        "提示：有 ${s.missingAttachments} 个附件记录尚未下载到本机，建议先完成一次云同步后再做全量离线备份。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Text(
                    s.lastLocalBackupAt?.let {
                        "上次本地全量备份：${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(it))}"
                    } ?: "尚未创建过本地 ZIP 备份包（建议定期或在迁移前备份一次）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }

    SettingsSection("导出离线备份包 (.zip)") {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                "导出前会自动将所有未保存草稿写入数据库。备份包内同时包含完整恢复数据、SHA-256 完整性校验清单、附件原文件，以及按目录整理的纯 Markdown 文档（即使脱离本应用也可在任意电脑直接阅读）。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val privateCount = summary?.privateItems ?: 0
            if (privateCount > 0) {
                if (encryptedUnlocked) {
                    Row(
                        Modifier.fillMaxWidth().clickable { includePrivate = !includePrivate }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(includePrivate, { includePrivate = it })
                        Text(
                            "包含已解锁的 $privateCount 条私密内容（导出文件为明文，请仅保存在受信任位置）",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                } else {
                    Text(
                        "私密保护：检测到 $privateCount 条私密记录当前处于锁定状态，默认不会写入明文备份包。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = ::startBackupToDocument,
                    enabled = !working,
                    modifier = Modifier.weight(1f).testTag("export-local-backup"),
                ) {
                    Icon(Icons.Default.SaveAlt, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("保存到手机")
                }
                OutlinedButton(
                    onClick = ::shareBackupArchive,
                    enabled = !working,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.Share, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("生成并分享")
                }
            }
        }
    }

    SettingsSection("从备份包安全恢复") {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                "零覆盖保护：恢复前会严格校验备份包 SHA-256 哈希；若本机已存在同 ID 笔记且正文不同，系统绝不覆盖本机内容，而是将备份内容另存为「备份恢复副本」供你比对。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = { restoreBackupLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) },
                enabled = !working,
                modifier = Modifier.fillMaxWidth().testTag("restore-local-backup"),
            ) {
                Icon(Icons.Default.RestorePage, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("选择备份包 (.zip) 安全合并恢复")
            }
        }
    }

    confirmPrivateExport?.let { proceed ->
        AlertDialog(
            onDismissRequest = { confirmPrivateExport = null },
            icon = { Icon(Icons.Default.Security, null) },
            title = { Text("确认导出私密内容？") },
            text = { Text("导出的 ZIP 备份包为通用明文归档，包含已解锁私密文件夹中的笔记与附件。请确认保存位置安全。") },
            confirmButton = {
                Button({
                    confirmPrivateExport = null
                    proceed()
                }) { Text("确认导出") }
            },
            dismissButton = { TextButton({ confirmPrivateExport = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun SafetyMetric(label: String, value: String) {
    Column {
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun AppDiagnostics() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var exporting by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    SettingsSection("应用信息") {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Notebook", style = MaterialTheme.typography.titleLarge)
            Text("版本 ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyMedium)
            Text(
                "本地数据库开启自动草稿防抖落盘、冲突副本保护与 SHA-256 同步完整性校验。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    SettingsSection("导出诊断包") {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "导出最近约 2 MiB 的运行日志。日志不含笔记标题、正文、密码、令牌、原始网址或服务器响应。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = {
                exporting = true
                failed = false
                scope.launch {
                    try {
                        val archive = withContext(Dispatchers.IO) { DiagnosticLogger.exportArchive(context) }
                        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", archive)
                        val share = Intent(Intent.ACTION_SEND).setType("application/zip")
                            .putExtra(Intent.EXTRA_STREAM, uri)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        context.startActivity(Intent.createChooser(share, "分享 Notebook 诊断日志"))
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        failed = true
                    } finally {
                        exporting = false
                    }
                }
            }, enabled = !exporting) {
                Icon(Icons.Default.Share, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("导出并分享诊断日志")
            }
            if (failed) Text("诊断日志导出失败", color = MaterialTheme.colorScheme.error)
        }
    }
    DiagnosticLogViewerSection()
}

@Composable
internal fun DiagnosticLogViewerSection(initialFilter: String = "ALL") {
    val clipboard = LocalClipboardManager.current
    var entries by remember { mutableStateOf<List<DiagnosticEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var levelFilter by remember { mutableStateOf(initialFilter) }
    var keyword by remember { mutableStateOf("") }
    var expandedIndex by remember { mutableIntStateOf(-1) }
    var refreshCount by remember { mutableIntStateOf(0) }
    var copiedHint by remember { mutableStateOf(false) }

    LaunchedEffect(refreshCount) {
        loading = true
        entries = withContext(Dispatchers.IO) { DiagnosticLogger.readRecentEntries(200) }
        loading = false
    }

    val filtered = remember(entries, levelFilter, keyword) {
        entries.filter { entry ->
            val matchesFilter = when (levelFilter) {
                "ERROR" -> entry.level == "ERROR"
                "WARN" -> entry.level == "WARN" || entry.level == "ERROR"
                "SYNC" -> entry.event.startsWith("sync.")
                else -> true
            }
            val needle = keyword.trim()
            val matchesKeyword = needle.isEmpty() ||
                entry.event.contains(needle, ignoreCase = true) ||
                diagnosticEventLabel(entry.event).contains(needle, ignoreCase = true) ||
                entry.errorClass?.contains(needle, ignoreCase = true) == true
            matchesFilter && matchesKeyword
        }
    }

    SettingsSection("内置运行日志查看器") {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "最近记录 (${filtered.size}/${entries.size})",
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                )
                TextButton({
                    val summary = filtered.take(50).joinToString("\n") { e ->
                        "[${e.timestamp}] ${e.level} ${e.event} (${diagnosticEventLabel(e.event)})" +
                            (e.durationMs?.let { " ${it}ms" } ?: "") +
                            (e.errorClass?.let { " err=$it" } ?: "") +
                            (if (e.counts.isNotEmpty()) " counts=${e.counts}" else "")
                    }
                    clipboard.setText(AnnotatedString(summary.ifBlank { "暂无匹配日志" }))
                    copiedHint = true
                }) {
                    Text(if (copiedHint) "已复制" else "复制摘要")
                }
                IconButton({ copiedHint = false; refreshCount++ }) {
                    Icon(Icons.Default.Refresh, "刷新日志", Modifier.size(20.dp))
                }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("ALL" to "全部", "ERROR" to "仅错误", "WARN" to "警告与错误", "SYNC" to "同步事件").forEach { (key, label) ->
                    FilterChip(
                        selected = levelFilter == key,
                        onClick = { levelFilter = key; expandedIndex = -1 },
                        label = { Text(label, fontSize = 12.sp) },
                    )
                }
            }
            OutlinedTextField(
                value = keyword,
                onValueChange = { keyword = it; expandedIndex = -1 },
                modifier = Modifier.fillMaxWidth().testTag("diagnostic-log-search"),
                singleLine = true,
                placeholder = { Text("筛选事件名或异常类型（如 sync、draft）") },
                trailingIcon = {
                    if (keyword.isNotEmpty()) {
                        IconButton({ keyword = "" }) { Icon(Icons.Default.Clear, "清空筛选") }
                    }
                },
            )
            if (loading) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            } else if (filtered.isEmpty()) {
                Text(
                    "当前筛选条件下没有日志记录。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                filtered.take(40).forEachIndexed { index, entry ->
                    val isError = entry.level == "ERROR"
                    val isWarn = entry.level == "WARN"
                    Surface(
                        Modifier.fillMaxWidth().clickable {
                            expandedIndex = if (expandedIndex == index) -1 else index
                        },
                        shape = RoundedCornerShape(12.dp),
                        color = when {
                            isError -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f)
                            isWarn -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.45f)
                            else -> MaterialTheme.colorScheme.surfaceContainerLow
                        },
                    ) {
                        Column(Modifier.padding(horizontal = 12.dp, vertical = 9.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    entry.level,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = when {
                                        isError -> MaterialTheme.colorScheme.error
                                        isWarn -> MaterialTheme.colorScheme.tertiary
                                        else -> MaterialTheme.colorScheme.primary
                                    },
                                )
                                Text(
                                    diagnosticEventLabel(entry.event),
                                    Modifier.weight(1f),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    formatDiagnosticTimestamp(entry.timestamp),
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            val meta = buildList {
                                add(entry.event)
                                entry.durationMs?.let { add("${it} ms") }
                                if (entry.counts.isNotEmpty()) {
                                    add(entry.counts.entries.joinToString(", ") { "${it.key}=${it.value}" })
                                }
                                entry.errorClass?.substringAfterLast('.')?.let { add(it) }
                            }.joinToString(" · ")
                            Text(
                                meta,
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = if (expandedIndex == index) 4 else 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (expandedIndex == index && (entry.errorClass != null || entry.stack.isNotEmpty())) {
                                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                                entry.errorClass?.let {
                                    Text("异常类：$it", fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                                }
                                if (entry.stack.isNotEmpty()) {
                                    Text(
                                        entry.stack.joinToString("\n"),
                                        fontSize = 11.sp,
                                        lineHeight = 15.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
                if (filtered.size > 40) {
                    Text(
                        "仅展示最近 40 条匹配项，如需全量分析请点击上方「导出并分享诊断日志」。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private fun formatDiagnosticTimestamp(isoText: String): String =
    runCatching {
        val ms = Instant.parse(isoText).toEpochMilli()
        SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA).format(Date(ms))
    }.getOrDefault(isoText.takeLast(12))
