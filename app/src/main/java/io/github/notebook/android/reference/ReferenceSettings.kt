package io.github.notebook.android

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import io.github.notebook.android.ui.Appearance
import io.github.notebook.android.ui.BackgroundPreset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.notebook.android.sync.SyncRepository

@Composable
internal fun ReferenceSettings(
    repo: SyncRepository,
    onBack: () -> Unit,
    onSyncSettings: @Composable (() -> Unit) -> Unit,
    onManage: (String) -> Unit,
    startInSync: Boolean = false,
) {
    var sync by remember { mutableStateOf(startInSync) }
    var about by remember { mutableStateOf(false) }
    var tts by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val preferences = remember { context.getSharedPreferences("editor_preferences", 0) }
    var autoKeyboard by remember { mutableStateOf(preferences.getBoolean("autoKeyboard", true)) }
    var quickCreate by remember { mutableStateOf(preferences.getBoolean("quickCreate", true)) }
    if (sync) {
        // Opened straight from the menu's 立即同步: back leaves settings entirely.
        onSyncSettings { if (startInSync) onBack() else sync = false }
        return
    }
    if (tts) {
        TtsSettingsScreen(repo) { tts = false }
        return
    }
    BackHandler(onBack = onBack)
    Column(
        Modifier.fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RoundTool(Icons.Default.ArrowBack, "返回", onBack, filled = false)
            Text("设置", fontSize = 28.sp)
        }
        SettingsGroup("同步", listOf("同步配置与状态" to { sync = true }))
        BackgroundPicker()
        SettingsGroup("朗读", listOf("语音朗读（通义千问 TTS）" to { tts = true }))
        SettingsGroup(
            "管理",
            listOf("标签" to { onManage("tag") }, "待办文件夹" to { onManage("todoList") }),
        )
        Text(
            "编辑",
            Modifier.padding(start = 20.dp, top = 24.dp, bottom = 10.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 13.sp,
        )
        Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface) {
            Column {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("进入编辑时弹出键盘", Modifier.weight(1f))
                    Switch(
                        autoKeyboard,
                        {
                            autoKeyboard = it
                            preferences.edit().putBoolean("autoKeyboard", it).apply()
                        },
                    )
                }
                HorizontalDivider(Modifier.padding(horizontal = 20.dp))
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("长按 + 快速新建")
                        Text(
                            "长按首页创建按钮，直接新建文字笔记",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        quickCreate,
                        {
                            quickCreate = it
                            preferences.edit().putBoolean("quickCreate", it).apply()
                        },
                    )
                }
            }
        }
        SettingsGroup("应用", listOf("关于 Notebook" to { about = true }))
        Spacer(Modifier.height(32.dp))
    }
    if (about)
        AlertDialog(
            onDismissRequest = { about = false },
            title = { Text("Notebook") },
            text = {
                Text(
                    "版本 ${BuildConfig.VERSION_NAME}\n离线笔记 · Markdown · WebDAV 同步\n界面根据系统笔记实机研究重新设计。"
                )
            },
            confirmButton = { TextButton({ about = false }) { Text("知道了") } },
        )
}

/** Background colour choices; light and dark are remembered separately and applied with the system theme. */
@Composable
private fun BackgroundPicker() {
    val context = LocalContext.current
    val systemDark = isSystemInDarkTheme()
    Text(
        "外观",
        Modifier.padding(start = 20.dp, top = 24.dp, bottom = 10.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 13.sp,
    )
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
