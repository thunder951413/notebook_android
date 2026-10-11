@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package io.github.notebook.android

import android.app.Activity
import android.content.pm.ActivityInfo
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

internal data class MarkdownTable(val rows: List<List<String>>, val separators: List<String>) {
    val columns
        get() = rows.maxOfOrNull { it.size } ?: 2

    fun markdown(): String =
        rows
            .mapIndexed { index, row ->
                "| " +
                    (0 until columns).joinToString(" | ") { i ->
                        row.getOrElse(i) { "" }.replace("|", "\\|").replace("\n", "<br>")
                    } +
                    " |" +
                    (if (index == 0)
                        "\n| " +
                            (0 until columns).joinToString(" | ") {
                                separators.getOrElse(it) { "---" }
                            } +
                            " |"
                    else "")
            }
            .joinToString("\n") + "\n"

    companion object {
        fun parse(text: String): MarkdownTable {
            val lines = text.trim().lines()
            require(lines.size >= 2) { "表格缺少标题或分隔行" }
            val rows = lines.filterIndexed { index, _ -> index != 1 }.map(::splitRow)
            return MarkdownTable(rows, splitRow(lines[1]))
        }

        private fun splitRow(line: String): List<String> {
            val source = line.trim().removePrefix("|").removeSuffix("|")
            val cells = mutableListOf<String>()
            val current = StringBuilder()
            var escaped = false
            var code = false
            source.forEach { char ->
                when {
                    escaped -> {
                        if (char != '|') current.append('\\')
                        current.append(char)
                        escaped = false
                    }
                    char == '\\' -> escaped = true
                    char == '`' -> {
                        code = !code
                        current.append(char)
                    }
                    char == '|' && !code -> {
                        cells +=
                            current
                                .toString()
                                .trim()
                                .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
                        current.clear()
                    }
                    else -> current.append(char)
                }
            }
            if (escaped) current.append('\\')
            cells +=
                current
                    .toString()
                    .trim()
                    .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
            return cells
        }
    }
}

@Composable
internal fun TableEditorDialog(
    markdown: String,
    readOnly: Boolean,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var table by
        rememberSaveable(
            markdown,
            stateSaver = Saver(save = { it.markdown() }, restore = { MarkdownTable.parse(it) }),
        ) {
            mutableStateOf(MarkdownTable.parse(markdown))
        }
    fun finish() {
        if (!readOnly) onSave(table.markdown())
        onDismiss()
    }
    val context = LocalContext.current
    val activity =
        generateSequence(context) { (it as? android.content.ContextWrapper)?.baseContext }
            .filterIsInstance<Activity>()
            .firstOrNull()
    val availableWidth = LocalConfiguration.current.screenWidthDp.dp - 32.dp
    val cellWidth = maxOf(160.dp, availableWidth / table.columns)
    val isPhone = LocalConfiguration.current.screenWidthDp < 600
    var landscape by rememberSaveable { mutableStateOf(isPhone) }
    val oldOrientation = rememberSaveable {
        activity?.requestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }
    LaunchedEffect(Unit) {
        activity?.requestedOrientation =
            if (landscape) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            else ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
    }
    DisposableEffect(activity) {
        onDispose {
            if (activity?.isChangingConfigurations == false)
                activity.requestedOrientation = oldOrientation
        }
    }
    Dialog(
        onDismissRequest = ::finish,
        properties =
            DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Surface(
            Modifier.fillMaxSize().testTag("table-editor"),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(
                Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()
            ) {
                Row(Modifier.fillMaxWidth().padding(8.dp)) {
                    RoundTool(Icons.Default.ArrowBack, "关闭表格", ::finish, filled = false)
                    Text("表格", Modifier.weight(1f).padding(12.dp))
                    RoundTool(
                        Icons.Default.ScreenRotation,
                        "切换表格方向",
                        {
                            landscape = !landscape
                            activity?.requestedOrientation =
                                if (landscape) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                                else ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                        },
                        filled = false,
                    )
                    if (!readOnly) TextButton(::finish) { Text("完成") }
                }
                Column(
                    Modifier.weight(1f)
                        .verticalScroll(rememberScrollState())
                        .horizontalScroll(rememberScrollState())
                        .padding(16.dp)
                ) {
                    table.rows.forEachIndexed { rowIndex, row ->
                        Row {
                            repeat(table.columns) { column ->
                                val cell = row.getOrElse(column) { "" }
                                if (readOnly)
                                    Surface(
                                        Modifier.width(cellWidth)
                                            .heightIn(min = 60.dp)
                                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                        color =
                                            if (rowIndex == 0)
                                                MaterialTheme.colorScheme.secondaryContainer
                                            else MaterialTheme.colorScheme.surface,
                                    ) {
                                        Text(cell, Modifier.padding(12.dp))
                                    }
                                else
                                    OutlinedTextField(
                                        cell,
                                        { next ->
                                            val rows =
                                                table.rows
                                                    .map { it.toMutableList() }
                                                    .toMutableList()
                                            while (rows[rowIndex].size < table.columns) rows[
                                                    rowIndex]
                                                .add("")
                                            rows[rowIndex][column] = next
                                            table = table.copy(rows = rows)
                                        },
                                        Modifier.width(cellWidth)
                                            .heightIn(min = 60.dp)
                                            .testTag("table-cell-$rowIndex-$column"),
                                        singleLine = false,
                                        shape = androidx.compose.ui.graphics.RectangleShape,
                                    )
                            }
                        }
                    }
                }
                if (!readOnly)
                    Row(
                        Modifier.fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(8.dp)
                            .padding(
                                bottom =
                                    maxOf(
                                        WindowInsets.navigationBars
                                            .asPaddingValues()
                                            .calculateBottomPadding(),
                                        24.dp,
                                    )
                            )
                    ) {
                        TextButton({
                            table =
                                table.copy(rows = table.rows + listOf(List(table.columns) { "" }))
                        }) {
                            Text("添加行")
                        }
                        TextButton({
                            table =
                                table.copy(
                                    rows = table.rows.map { it + "" },
                                    separators = table.separators + "---",
                                )
                        }) {
                            Text("添加列")
                        }
                        TextButton(
                            { table = table.copy(rows = table.rows.dropLast(1)) },
                            enabled = table.rows.size > 2,
                        ) {
                            Text("删除末行")
                        }
                        TextButton(
                            {
                                table =
                                    table.copy(
                                        rows = table.rows.map { it.take(table.columns - 1) },
                                        separators = table.separators.take(table.columns - 1),
                                    )
                            },
                            enabled = table.columns > 1,
                        ) {
                            Text("删除末列")
                        }
                    }
            }
        }
    }
}
