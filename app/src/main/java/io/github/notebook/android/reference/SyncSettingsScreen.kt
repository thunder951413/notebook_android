package io.github.notebook.android

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import io.github.notebook.android.sync.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private enum class SyncSettingsPage(val title: String) {
    Overview("同步设置"),
    Connection("连接配置"),
    Repair("同步状态与修复"),
    Migration("旧仓库迁移"),
}

private data class SyncFeedback(val text: String, val error: Boolean = false)

@Composable
internal fun SettingsFlow(
    repo: SyncRepository,
    startWithSync: Boolean,
    onBack: () -> Unit,
    manage: @Composable (String, () -> Unit) -> Unit,
) {
    var page by rememberSaveable {
        mutableStateOf(if (startWithSync) SyncSettingsPage.Overview else null)
    }
    var autoSyncPending by rememberSaveable { mutableStateOf(startWithSync) }
    val homeScroll = rememberScrollState()
    val overviewScroll = rememberScrollState()
    val detailScroll = key(page) { rememberScrollState() }
    val scope = rememberCoroutineScope()
    val lastSuccess by repo.lastSyncSuccess.collectAsState()
    var configured by remember { mutableStateOf(repo.hasRemoteConfiguration()) }
    var backend by remember { mutableStateOf(repo.syncBackend()) }
    var webdav by remember { mutableStateOf(repo.webdavSettings()) }
    var ssh by remember { mutableStateOf(repo.settings()) }
    var syncFeedback by remember { mutableStateOf<SyncFeedback?>(null) }
    var pageFeedback by remember { mutableStateOf<SyncFeedback?>(null) }
    var syncing by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var newKey by remember { mutableStateOf<HostKeyConfirmationRequiredException?>(null) }
    var changedKey by remember { mutableStateOf<HostKeyChangedException?>(null) }
    var repairing by remember { mutableStateOf(false) }
    val busy = syncing || testing || repairing

    fun refreshConfiguration() {
        backend = repo.syncBackend()
        configured = repo.hasRemoteConfiguration()
    }

    fun open(next: SyncSettingsPage?) {
        pageFeedback = null
        page = next
    }

    fun backFromOverview() {
        if (startWithSync) onBack() else open(null)
    }

    suspend fun syncNow(prefix: String? = null) {
        if (syncing || testing || repairing) return
        syncing = true
        newKey = null
        changedKey = null
        pageFeedback = null
        fun report(text: String, error: Boolean = false) {
            syncFeedback = SyncFeedback(if (prefix == null) text else "$prefix · $text", error)
        }
        report("正在连接服务器…")
        try {
            repo.sync { report(it) }
            report(repo.syncCompletionMessage())
        } catch (error: CancellationException) {
            throw error
        } catch (error: HostKeyConfirmationRequiredException) {
            newKey = error
            report("首次连接需要确认服务器身份", error = true)
        } catch (error: HostKeyChangedException) {
            changedKey = error
            report("服务器身份已变化，需要确认", error = true)
        } catch (error: Exception) {
            report("同步失败：${describeSyncError(error)}", error = true)
        } finally {
            syncing = false
        }
    }

    LaunchedEffect(Unit) {
        if (autoSyncPending) {
            autoSyncPending = false
            if (configured) syncNow()
        }
    }

    val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        val payload = result.contents ?: return@rememberLauncherForActivityResult
        runCatching {
            when (val parsed = parseNotebookQrConfig(payload)) {
                is ParsedQrConfig.Webdav -> {
                    repo.saveWebdavSettings(parsed.settings)
                    webdav = repo.webdavSettings()
                    if (page == SyncSettingsPage.Migration) open(SyncSettingsPage.Connection)
                    "已导入坚果云配置"
                }
                is ParsedQrConfig.Ssh -> {
                    repo.saveSettings(parsed.settings)
                    ssh = repo.settings()
                    if (page == SyncSettingsPage.Connection) open(SyncSettingsPage.Migration)
                    "已导入 SSH 配置"
                }
            }
        }.onSuccess { prefix ->
            refreshConfiguration()
            scope.launch { syncNow(prefix) }
        }.onFailure { syncFeedback = SyncFeedback(it.message ?: "无法读取配置二维码", error = true) }
    }

    fun scan() {
        pageFeedback = null
        scanner.launch(
            ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setPrompt("请将取景框对准电脑端生成的同步配置二维码")
                .setBeepEnabled(false).setOrientationLocked(false),
        )
    }

    val keyPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            try {
                repo.importPrivateKey(uri, ssh.privateKeyPassphrase)
                ssh = ssh.copy(privateKeyPath = repo.settings().privateKeyPath)
                pageFeedback = SyncFeedback("私钥已导入")
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                pageFeedback = SyncFeedback(error.localizedMessage ?: "无法导入私钥", error = true)
            }
        }
    }

    fun trustHost(fingerprint: String) {
        repo.trustHostKey(fingerprint)
        ssh = repo.settings()
        scope.launch { syncNow() }
    }

    newKey?.let { key ->
        HostIdentityDialog(
            title = "确认服务器身份",
            fingerprints = "SHA-256 指纹\n${key.actual}",
            confirmLabel = "指纹一致，信任并继续",
            onDismiss = { newKey = null },
        ) { newKey = null; trustHost(key.actual) }
    }
    changedKey?.let { key ->
        HostIdentityDialog(
            title = "服务器身份已变化",
            fingerprints = "原指纹\n${key.expected}\n\n新指纹\n${key.actual}",
            confirmLabel = "信任新指纹并继续",
            onDismiss = { changedKey = null },
        ) { changedKey = null; trustHost(key.actual) }
    }

    val overviewCard: @Composable () -> Unit = {
        SyncOverviewCard(
            configured, backend, lastSuccess, syncFeedback, syncing, busy,
            onSync = { scope.launch { syncNow() } },
            onScan = ::scan,
        )
    }
    val current = page
    when (current) {
        null -> ReferenceSettings(
            repo, onBack, { open(SyncSettingsPage.Overview) }, overviewCard, manage, homeScroll,
        )
        SyncSettingsPage.Overview -> SettingsScreen(current.title, ::backFromOverview, overviewScroll) {
            Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) { overviewCard() }
            if (configured && backend == SyncBackend.WEBDAV) SyncOverviewCounts(repo, busy, lastSuccess)
            SettingsSection("配置与维护") {
                SettingsLink("连接配置", Icons.Default.SettingsEthernet, "坚果云 WebDAV、扫码与隐私同步密钥") {
                    open(SyncSettingsPage.Connection)
                }
                SettingsDivider()
                SettingsLink("同步状态与修复", Icons.Default.Build, "待上传、冲突副本与异常记录") {
                    open(SyncSettingsPage.Repair)
                }
                SettingsDivider()
                SettingsLink("旧仓库迁移", Icons.Default.History, "SSH/SFTP 与旧版 HTTP API") {
                    open(SyncSettingsPage.Migration)
                }
            }
        }
        else -> SettingsScreen(current.title, { open(SyncSettingsPage.Overview) }, detailScroll) {
            val revealFeedback = busy || pageFeedback?.error == true
            LaunchedEffect(current, revealFeedback) {
                if (revealFeedback) detailScroll.animateScrollTo(0)
            }
            pageFeedback?.let { SyncFeedbackCard(it) }
            syncFeedback?.takeIf { pageFeedback == null }?.let { SyncFeedbackCard(it, syncing) }
            when (current) {
                SyncSettingsPage.Connection -> WebdavConnection(
                    webdav, { webdav = it }, busy, ::scan,
                    onTest = {
                        testing = true
                        pageFeedback = SyncFeedback("正在检测连接…")
                        scope.launch {
                            try {
                                val result = repo.testWebdav(webdav)
                                pageFeedback = when {
                                    !result.ok -> SyncFeedback("连接失败：${result.message}", error = true)
                                    result.remotePathExists -> SyncFeedback("连接正常（${result.latencyMs} ms），远端目录可访问。")
                                    else -> SyncFeedback("连接正常。首次同步时会创建远端目录。")
                                }
                            } catch (error: CancellationException) {
                                throw error
                            } catch (error: Exception) {
                                pageFeedback = SyncFeedback("连接失败：${describeSyncError(error)}", error = true)
                            } finally {
                                testing = false
                            }
                        }
                    },
                    onSave = {
                        runCatching { repo.saveWebdavSettings(webdav) }
                            .onSuccess {
                                webdav = repo.webdavSettings()
                                refreshConfiguration()
                                scope.launch { syncNow() }
                            }
                            .onFailure { pageFeedback = SyncFeedback(it.localizedMessage ?: "同步配置保存失败", error = true) }
                    },
                )
                SyncSettingsPage.Repair -> {
                    if (backend == SyncBackend.WEBDAV && configured) {
                        SyncHealthCard(repo, busy, lastSuccess) { action ->
                            repairing = true
                            pageFeedback = SyncFeedback("正在处理同步记录…")
                            scope.launch {
                                try {
                                    when (action) {
                                        SyncRepair.Reread -> repo.resetSyncReadPositions()
                                        SyncRepair.Republish -> repo.republishAll()
                                        SyncRepair.Rebuild -> repo.rebuildOwnJournal()
                                        SyncRepair.Retry -> repo.retrySyncIssues()
                                        SyncRepair.Dismiss -> repo.dismissSyncIssues()
                                    }
                                    pageFeedback = SyncFeedback("已处理同步记录")
                                } catch (error: CancellationException) {
                                    throw error
                                } catch (error: Exception) {
                                    pageFeedback = SyncFeedback("修复失败：${describeSyncError(error)}", error = true)
                                    return@launch
                                } finally {
                                    repairing = false
                                }
                                if (action.syncAfter) syncNow()
                            }
                        }
                    } else {
                        Text("配置坚果云 WebDAV 后，可以查看异常记录并执行修复。")
                        OutlinedButton({ open(SyncSettingsPage.Connection) }) { Text("打开连接配置") }
                    }
                    OutlinedButton(onClick = {
                        scope.launch {
                            runCatching { repo.exportSyncDiagnostics() }
                                .onSuccess { pageFeedback = SyncFeedback("同步诊断已保存（仅实体标识与校验值）") }
                                .onFailure {
                                    if (it is CancellationException) throw it
                                    pageFeedback = SyncFeedback("诊断导出失败：${it.localizedMessage}", error = true)
                                }
                        }
                    }, enabled = !busy) { Text("导出同步诊断") }
                }
                SyncSettingsPage.Migration -> LegacyMigration(
                    ssh, { ssh = it }, backend, busy, ::scan,
                    onImportKey = { keyPicker.launch(arrayOf("*/*")) },
                    onClearKey = {
                        repo.clearPrivateKey()
                        ssh = ssh.copy(privateKeyPath = "", privateKeyPassphrase = "")
                    },
                    onSave = { updated ->
                        ssh = updated
                        repo.saveSettings(updated)
                        refreshConfiguration()
                        scope.launch { syncNow() }
                    },
                    onWebdav = { open(SyncSettingsPage.Connection) },
                )
                SyncSettingsPage.Overview -> Unit
            }
        }
    }
}

@Composable
private fun SyncOverviewCard(
    configured: Boolean,
    backend: SyncBackend,
    lastSuccess: Long?,
    feedback: SyncFeedback?,
    syncing: Boolean,
    busy: Boolean,
    onSync: () -> Unit,
    onScan: () -> Unit,
) {
    val title = when {
        !configured -> "尚未配置同步"
        backend == SyncBackend.SSH -> "SSH/SFTP 迁移"
        backend == SyncBackend.API -> "旧版 HTTP API"
        else -> "坚果云 WebDAV"
    }
    Column(
        Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.CloudSync, null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    if (configured) lastSyncLabel(lastSuccess) else "扫描电脑端二维码，连接坚果云 WebDAV",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (feedback != null) SyncFeedbackCard(feedback, syncing)
        Button(
            onClick = if (configured) onSync else onScan,
            modifier = Modifier.fillMaxWidth(),
            enabled = !busy,
        ) {
            Icon(if (configured) Icons.Default.Sync else Icons.Default.QrCodeScanner, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(if (configured) "立即同步" else "扫码配置")
        }
    }
}

private fun lastSyncLabel(lastSuccess: Long?): String {
    if (lastSuccess == null) return "尚未成功同步"
    val relative = android.text.format.DateUtils.getRelativeTimeSpanString(
        lastSuccess, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS,
    )
    return "上次成功同步：$relative"
}

@Composable
private fun SyncOverviewCounts(repo: SyncRepository, busy: Boolean, lastSuccess: Long?) {
    var counts by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    LaunchedEffect(busy, lastSuccess) {
        if (!busy) {
            counts = try {
                repo.pendingUploadCount() to repo.conflictCopyCount()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                null
            }
        }
    }
    counts?.let { (pending, conflicts) ->
        Text(
            "等待上传 $pending 条 · 冲突副本 $conflicts 篇",
            Modifier.padding(horizontal = 12.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SyncFeedbackCard(feedback: SyncFeedback, working: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = if (feedback.error) colors.errorContainer else colors.secondaryContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (working) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Text(feedback.text, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun WebdavConnection(
    settings: WebdavSettings,
    update: (WebdavSettings) -> Unit,
    busy: Boolean,
    onScan: () -> Unit,
    onTest: () -> Unit,
    onSave: () -> Unit,
) {
    val focus = LocalFocusManager.current
    Text("坚果云 WebDAV", style = MaterialTheme.typography.titleLarge)
    Button(onScan, Modifier.fillMaxWidth(), enabled = !busy) {
        Icon(Icons.Default.QrCodeScanner, null)
        Spacer(Modifier.width(8.dp))
        Text("扫描电脑端配置二维码")
    }
    Text(
        "在电脑端打开「设置 → 同步与连接 → 生成配置二维码」。手动填写时，请在坚果云网页版的安全选项中生成应用密码。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedTextField(settings.baseUrl, { update(settings.copy(baseUrl = it)) }, Modifier.fillMaxWidth(), enabled = !busy, singleLine = true, label = { Text("服务器地址") })
    OutlinedTextField(settings.username, { update(settings.copy(username = it)) }, Modifier.fillMaxWidth(), enabled = !busy, singleLine = true, label = { Text("用户名") })
    OutlinedTextField(settings.appPassword, { update(settings.copy(appPassword = it)) }, Modifier.fillMaxWidth(), enabled = !busy, singleLine = true, label = { Text("应用密码") }, visualTransformation = PasswordVisualTransformation())
    OutlinedTextField(settings.remotePath, { update(settings.copy(remotePath = it)) }, Modifier.fillMaxWidth(), enabled = !busy, singleLine = true, label = { Text("远程目录") })
    OutlinedTextField(
        settings.syncPassword, { update(settings.copy(syncPassword = it)) }, Modifier.fillMaxWidth(),
        enabled = !busy, singleLine = true, label = { Text("隐私同步密钥（可选）") },
        visualTransformation = PasswordVisualTransformation(),
        supportingText = {
            Text("各设备使用同一密钥后，私密笔记、附件和历史会加密同步。密钥只保存在设备上，请保留以便恢复。未配置密钥时，私密内容仅保存在本机。")
        },
    )
    Text(
        "本机访问私密文件夹由设备锁保护，本地数据依赖 Android 系统存储加密。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton({ focus.clearFocus(); onTest() }, Modifier.weight(1f), enabled = !busy) { Text("检测连接") }
        Button({ focus.clearFocus(); onSave() }, Modifier.weight(1f), enabled = !busy) { Text("保存并同步") }
    }
}

@Composable
private fun LegacyMigration(
    settings: SshSettings,
    update: (SshSettings) -> Unit,
    backend: SyncBackend,
    busy: Boolean,
    onScan: () -> Unit,
    onImportKey: () -> Unit,
    onClearKey: () -> Unit,
    onSave: (SshSettings) -> Unit,
    onWebdav: () -> Unit,
) {
    var port by remember(settings.port) { mutableStateOf(settings.port.toString()) }
    val validPort = port.toIntOrNull()?.takeIf { it in 1..65535 }
    Text("旧配置会保留。完成迁移后，请切换到坚果云 WebDAV 进行日常同步。", style = MaterialTheme.typography.bodySmall)
    if (backend == SyncBackend.API) {
        Text("当前使用旧版 HTTP API，可在同步概览继续同步已有仓库。", style = MaterialTheme.typography.bodySmall)
    }
    OutlinedButton(onWebdav) { Text("打开坚果云配置") }
    HorizontalDivider()
    Text("SSH/SFTP", style = MaterialTheme.typography.titleLarge)
    OutlinedButton(onScan, enabled = !busy) { Text("扫描旧配置二维码") }
    OutlinedTextField(settings.host, { update(settings.copy(host = it)) }, Modifier.fillMaxWidth(), enabled = !busy, singleLine = true, label = { Text("服务器地址") })
    OutlinedTextField(port, { port = it }, Modifier.fillMaxWidth(), enabled = !busy, singleLine = true, isError = validPort == null, label = { Text("端口") })
    OutlinedTextField(settings.username, { update(settings.copy(username = it)) }, Modifier.fillMaxWidth(), enabled = !busy, singleLine = true, label = { Text("用户名") })
    OutlinedTextField(settings.password, { update(settings.copy(password = it)) }, Modifier.fillMaxWidth(), enabled = !busy, singleLine = true, label = { Text("密码（可选）") }, visualTransformation = PasswordVisualTransformation())
    OutlinedTextField(settings.path, { update(settings.copy(path = it)) }, Modifier.fillMaxWidth(), enabled = !busy, singleLine = true, label = { Text("远程目录") })
    HorizontalDivider()
    Text("SSH 私钥", style = MaterialTheme.typography.titleMedium)
    if (settings.privateKeyPath.isNotBlank()) Text("已导入私钥", style = MaterialTheme.typography.bodySmall)
    OutlinedTextField(
        settings.privateKeyPassphrase, { update(settings.copy(privateKeyPassphrase = it)) }, Modifier.fillMaxWidth(),
        enabled = !busy, singleLine = true, label = { Text("私钥口令（可选）") },
        visualTransformation = PasswordVisualTransformation(),
    )
    OutlinedButton(onImportKey, enabled = !busy) { Text(if (settings.privateKeyPath.isBlank()) "导入私钥文件" else "重新导入私钥") }
    if (settings.privateKeyPath.isNotBlank()) OutlinedButton(onClearKey, enabled = !busy) { Text("清除私钥") }
    if (settings.fingerprint.isNotBlank()) Text("已信任主机指纹：${settings.fingerprint}", style = MaterialTheme.typography.bodySmall)
    Button(
        onClick = {
            validPort?.let { onSave(settings.copy(port = it)) }
        },
        modifier = Modifier.fillMaxWidth(),
        enabled = !busy && validPort != null,
    ) { Text("保存旧配置并迁移") }
}

@Composable
private fun HostIdentityDialog(
    title: String,
    fingerprints: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Security, null) },
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("请与服务器管理员提供的指纹核对，确认一致后再继续。")
                Text(fingerprints, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { Button(onConfirm) { Text(confirmLabel) } },
        dismissButton = { TextButton(onDismiss) { Text("取消") } },
    )
}

private enum class SyncRepair(
    val label: String,
    val confirmation: String = "",
    val detail: String = "",
    val confirmLabel: String = "开始并同步",
    val syncAfter: Boolean = true,
) {
    Reread(
        "重新读取云端",
        "重新读取全部云端记录？",
        "忽略本机记住的读取位置，从头核对坚果云上所有设备的记录。已有内容不会被删除或重复，记录多时会多用一些流量。",
    ),
    Republish(
        "重新上传本机",
        "重新上传本机全部内容？",
        "把本机所有笔记、附件和设置重新排队上传，作为当前版本的后续版本。其他设备会自动合并，不会产生重复笔记。",
    ),
    Rebuild(
        "重建本机日志",
        "重建本机日志？",
        "重建会保留一份损坏日志的副本，再重新上传本机内容。日志序号从原处继续，其他设备已读取的内容不会重复或丢失。",
        "重建并重新上传",
    ),
    Retry("重试异常记录", syncAfter = false),
    Dismiss(
        "删除异常记录",
        "删除异常记录？",
        "本机会停止尝试读取这些云端记录。其他设备上的原始记录和本机笔记会保留。此操作无法撤销。",
        "删除",
        syncAfter = false,
    ),
}

@Composable
private fun SyncHealthCard(
    repo: SyncRepository,
    busy: Boolean,
    lastSuccess: Long?,
    onRepair: (SyncRepair) -> Unit,
) {
    var confirmation by remember { mutableStateOf<SyncRepair?>(null) }
    var readError by remember { mutableStateOf(false) }
    var issues by remember { mutableStateOf<WebdavSyncIssues?>(null) }
    LaunchedEffect(busy, lastSuccess) {
        if (!busy) {
            try {
                issues = repo.syncIssues()
                readError = false
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                readError = true
            }
        }
    }
    SettingsSection("同步状态") {
        Text(lastSyncLabel(lastSuccess), Modifier.padding(start = 12.dp, top = 12.dp), style = MaterialTheme.typography.bodySmall)
        Box(Modifier.padding(bottom = 12.dp)) { SyncOverviewCounts(repo, busy, lastSuccess) }
    }
    SettingsSection("修复") {
        SettingsLink(SyncRepair.Reread.label, Icons.Default.CloudDownload, "其他设备的修改还未出现在本机", enabled = !busy) {
            confirmation = SyncRepair.Reread
        }
        SettingsDivider()
        SettingsLink(SyncRepair.Republish.label, Icons.Default.CloudUpload, "云端目录被清空或新设备缺少笔记", enabled = !busy) {
            confirmation = SyncRepair.Republish
        }
    }
    Text(
        "多台设备同时编辑同一篇笔记时，本机版本会保留，另一版本另存为冲突副本，可对比后删除。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    if (readError) Text("无法读取同步问题", color = MaterialTheme.colorScheme.error)
    issues?.takeUnless { it.isEmpty }?.let { found ->
        SettingsSection("需要处理的同步问题") {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val ownDevice = repo.ownDeviceId()
                found.blocked.forEach { record ->
                    val device = if (record.deviceId == ownDevice) "本机" else "设备 ${record.deviceId.take(8)}"
                    val reason = when (record.code) {
                        WebdavBlockCode.JOURNAL_CORRUPT -> "日志含有无法解析的记录"
                        WebdavBlockCode.HEAD_CORRUPT -> "提交标记损坏"
                        WebdavBlockCode.JOURNAL_TRUNCATED -> "日志被截断，部分记录丢失"
                        WebdavBlockCode.JOURNAL_MISSING -> "日志文件缺失"
                        WebdavBlockCode.JOURNAL_TOO_LARGE -> "日志超过 100 MB"
                    }
                    Text("$device：$reason。请在该设备上选择「重建本机日志」。", style = MaterialTheme.typography.bodySmall)
                }
                if (found.blocked.any { it.deviceId == ownDevice }) {
                    Button({ confirmation = SyncRepair.Rebuild }, enabled = !busy) { Text(SyncRepair.Rebuild.label) }
                }
                found.quarantined.groupingBy { it.reason }.eachCount().forEach { (reason, count) ->
                    Text("$count 条：${syncIssueSummary(reason)}", style = MaterialTheme.typography.bodySmall)
                }
                if (found.quarantined.isNotEmpty()) {
                    OutlinedButton({ onRepair(SyncRepair.Retry) }, enabled = !busy) { Text(SyncRepair.Retry.label) }
                    OutlinedButton({ confirmation = SyncRepair.Dismiss }, enabled = !busy) { Text(SyncRepair.Dismiss.label) }
                }
            }
        }
    }
    confirmation?.let { action ->
        AlertDialog(
            onDismissRequest = { confirmation = null },
            title = { Text(action.confirmation) },
            text = { Text(action.detail) },
            confirmButton = {
                Button({
                    confirmation = null
                    onRepair(action)
                }, enabled = !busy) { Text(action.confirmLabel) }
            },
            dismissButton = { TextButton({ confirmation = null }) { Text("取消") } },
        )
    }
}

private fun syncIssueSummary(reason: String) = when (reason) {
    "needs-key" -> "需要隐私同步密钥，填写后会自动读取"
    "bad-key" -> "隐私同步密钥不匹配，请确认各设备使用同一密钥"
    "unknown-type" -> "来自更新版本的记录，升级应用后会自动读取"
    "missing-object" -> "云端文件缺失，请在产生该内容的设备上重新上传本机后重试"
    "object-corrupt" -> "云端文件已损坏，请在产生该内容的设备上重新上传本机后重试"
    "workspace-mismatch" -> "属于其他工作区，可以删除"
    else -> "记录内容不正确，升级应用后会重试一次"
}
