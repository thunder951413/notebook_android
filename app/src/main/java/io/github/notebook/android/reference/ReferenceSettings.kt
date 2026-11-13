package io.github.notebook.android

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.automirrored.filled.NoteAdd
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.notebook.android.ai.AiServiceSettingsScreen
import io.github.notebook.android.sync.SyncRepository
import io.github.notebook.android.ui.Appearance
import io.github.notebook.android.ui.BackgroundPreset

private enum class SettingsPage { Home, DataSafety, Appearance, Reading, Tags, TodoFolders, Reminders, AI, Voice, About }

@Composable
internal fun ReferenceSettings(
    repo: SyncRepository,
    onBack: () -> Unit,
    onSyncSettings: () -> Unit,
    syncCard: @Composable () -> Unit,
    manage: @Composable (kind: String, onBack: () -> Unit) -> Unit,
    scrollState: ScrollState,
    encryptedUnlocked: Boolean = false,
) {
    var page by rememberSaveable { mutableStateOf(SettingsPage.Home) }
    val backToSettings = { page = SettingsPage.Home }
    when (page) {
        SettingsPage.DataSafety -> SettingsScreen("本地备份与数据安全", backToSettings) {
            LocalBackupSafetyContent(repo, encryptedUnlocked)
        }
        SettingsPage.Appearance -> SettingsScreen("外观", backToSettings) { BackgroundPicker() }
        SettingsPage.Reading -> ReadingSettingsScreen(backToSettings)
        SettingsPage.Tags -> manage("tag", backToSettings)
        SettingsPage.TodoFolders -> manage("todoList", backToSettings)
        SettingsPage.Reminders -> SettingsScreen("提醒权限", backToSettings) { ReminderPermissionCard() }
        SettingsPage.AI -> AiServiceSettingsScreen(repo, backToSettings)
        SettingsPage.Voice -> TtsSettingsScreen(repo, backToSettings)
        SettingsPage.About -> SettingsScreen("关于与诊断", backToSettings) { AppDiagnostics() }
        SettingsPage.Home -> SettingsHome(repo, onBack, onSyncSettings, syncCard, scrollState) { page = it }
    }
}

@Composable
private fun SettingsHome(
    repo: SyncRepository,
    onBack: () -> Unit,
    onSyncSettings: () -> Unit,
    syncCard: @Composable () -> Unit,
    scrollState: ScrollState,
    open: (SettingsPage) -> Unit,
) {
    val context = LocalContext.current
    val preferences = remember(context) { context.getSharedPreferences("editor_preferences", 0) }
    var autoKeyboard by remember { mutableStateOf(preferences.getBoolean("autoKeyboard", true)) }
    var directCreate by remember { mutableStateOf(preferences.getBoolean("directCreate", true)) }
    var startInTodos by remember { mutableStateOf(preferences.getBoolean("startInTodos", false)) }
    val ai = repo.aiSettings()
    val voice = repo.ttsSettings()
    val reminders = rememberReminderPermissions()
    val lastBackup = repo.lastLocalBackupAt()
    val backupSummary = remember(lastBackup) {
        if (lastBackup == null) "导出离线 ZIP 备份包、零覆盖安全恢复"
        else "上次备份：${java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA).format(java.util.Date(lastBackup))}"
    }

    SettingsScreen("设置", onBack, scrollState, "settings-home-scroll") {
        SettingsSection("同步与数据安全") {
            syncCard()
            SettingsDivider()
            SettingsLink("同步设置", Icons.Default.Tune, "连接、状态与修复", onClick = onSyncSettings)
            SettingsDivider()
            SettingsLink("本地备份与数据安全", Icons.Default.VerifiedUser, backupSummary) {
                open(SettingsPage.DataSafety)
            }
        }

        SettingsSection("使用偏好") {
            SettingsLink("外观", Icons.Default.Palette, "亮色与暗色背景") { open(SettingsPage.Appearance) }
            SettingsDivider()
            SettingsLink("阅读排版", Icons.Default.TextFields, "字号、行高、正文宽度") { open(SettingsPage.Reading) }
            SettingsDivider()
            SettingsToggle("进入编辑时弹出键盘", Icons.Default.Keyboard, autoKeyboard) {
                autoKeyboard = it
                preferences.edit().putBoolean("autoKeyboard", it).apply()
            }
            SettingsDivider()
            SettingsToggle(
                "点击 + 直接新建",
                Icons.AutoMirrored.Filled.NoteAdd,
                directCreate,
                "开启后点击 + 立即新建，长按 + 打开导入与更多新建方式",
            ) {
                directCreate = it
                preferences.edit().putBoolean("directCreate", it).apply()
            }
            SettingsDivider()
            SettingsToggle(
                "启动时优先打开待办",
                Icons.Default.Checklist,
                startInTodos,
                "有未完成待办时冷启动直接进入待办列表；关闭则默认进入笔记首页",
            ) {
                startInTodos = it
                preferences.edit().putBoolean("startInTodos", it).apply()
            }
        }

        SettingsSection("内容与提醒") {
            SettingsLink("标签管理", Icons.AutoMirrored.Filled.Label) { open(SettingsPage.Tags) }
            SettingsDivider()
            SettingsLink("待办文件夹", Icons.AutoMirrored.Filled.ListAlt) { open(SettingsPage.TodoFolders) }
            SettingsDivider()
            SettingsLink("提醒权限", Icons.Default.Notifications, reminders.summary) { open(SettingsPage.Reminders) }
        }

        SettingsSection("AI 与朗读") {
            SettingsLink("AI 服务", Icons.Default.AutoAwesome, if (ai.configured) ai.provider.label else "未配置") {
                open(SettingsPage.AI)
            }
            SettingsDivider()
            SettingsLink("语音朗读", Icons.Default.RecordVoiceOver, if (voice.configured) voice.api.label else "未配置") {
                open(SettingsPage.Voice)
            }
        }

        SettingsSection("关于与诊断") {
            SettingsLink("关于 Notebook", Icons.Default.Info, "版本 ${BuildConfig.VERSION_NAME} · 查看与导出运行日志") {
                open(SettingsPage.About)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsScreen(
    title: String,
    onBack: () -> Unit,
    scrollState: ScrollState = rememberScrollState(),
    scrollTag: String = "settings-page-scroll",
    content: @Composable ColumnScope.() -> Unit,
) {
    BackHandler(onBack = onBack)
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                Modifier.widthIn(max = 560.dp).fillMaxWidth().weight(1f, fill = false)
                    .testTag(scrollTag).verticalScroll(scrollState)
                    .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                content = content,
            )
        }
    }
}

@Composable
internal fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            title,
            Modifier.padding(start = 12.dp).semantics { heading() },
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
            Column(content = content)
        }
    }
}

@Composable
internal fun SettingsLink(
    title: String,
    icon: ImageVector,
    summary: String? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    SettingsRow(Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick), title, icon, summary) {
        Icon(Icons.Default.ChevronRight, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SettingsToggle(title: String, icon: ImageVector, checked: Boolean, summary: String? = null, onChange: (Boolean) -> Unit) {
    SettingsRow(Modifier.toggleable(checked, role = Role.Switch, onValueChange = onChange), title, icon, summary) {
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun SettingsRow(modifier: Modifier, title: String, icon: ImageVector, summary: String?, trailing: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).then(modifier).padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (summary != null) Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        trailing()
    }
}

@Composable
internal fun SettingsDivider() {
    HorizontalDivider(Modifier.padding(start = 52.dp, end = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
}

/** Background colour choices; light and dark are remembered separately and applied with the system theme. */
@Composable
private fun BackgroundPicker() {
    val context = LocalContext.current
    val systemDark = isSystemInDarkTheme()
    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(vertical = 14.dp)) {
            PresetRow("亮色模式背景", if (systemDark) null else "当前", BackgroundPreset.light, Appearance.light) {
                Appearance.choose(context, it)
            }
            HorizontalDivider(Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
            PresetRow("暗色模式背景", if (systemDark) "当前" else null, BackgroundPreset.dark, Appearance.dark) {
                Appearance.choose(context, it)
            }
            Text(
                "手机在亮色和暗色之间切换时，会自动用各自选好的背景。",
                Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PresetRow(
    title: String,
    badge: String?,
    presets: List<BackgroundPreset>,
    selected: BackgroundPreset,
    onPick: (BackgroundPreset) -> Unit,
) {
    Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f))
        if (badge != null) Text(badge, fontSize = 12.sp, color = MaterialTheme.colorScheme.tertiary)
    }
    Row(
        Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        presets.forEach { preset ->
            val chosen = preset == selected
            Column(
                Modifier.clip(RoundedCornerShape(12.dp))
                    .clickable { onPick(preset) }
                    .semantics { contentDescription = "${preset.label}${if (chosen) "，已选中" else ""}" }
                    .padding(4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier.size(46.dp)
                        .border(
                            if (chosen) 2.5.dp else 1.dp,
                            if (chosen) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.outline.copy(alpha = .5f),
                            androidx.compose.foundation.shape.CircleShape,
                        )
                        .padding(4.dp)
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .background(preset.page),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(Modifier.size(20.dp).clip(androidx.compose.foundation.shape.CircleShape).background(preset.card))
                    if (chosen) Icon(Icons.Default.Check, null, Modifier.size(16.dp), tint = if (preset.dark) Color.White else Color(0xFF191919))
                }
                Text(preset.label, Modifier.padding(top = 4.dp), fontSize = 12.sp)
            }
        }
    }
}
