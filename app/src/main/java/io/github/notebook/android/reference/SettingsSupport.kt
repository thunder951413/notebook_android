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
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.notebook.android.diagnostics.DiagnosticLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
internal fun AppDiagnostics() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var exporting by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }

    SettingsSection("应用信息") {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Notebook", style = MaterialTheme.typography.titleLarge)
            Text("版本 ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyMedium)
        }
    }
    SettingsSection("运行日志") {
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
}
