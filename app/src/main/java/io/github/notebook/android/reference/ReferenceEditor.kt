@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package io.github.notebook.android

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.notebook.android.data.AssetEntity
import io.github.notebook.android.data.TagEntity

@Composable
internal fun ReferenceEditorBody(
    title: String,
    onTitle: (String) -> Unit,
    value: TextFieldValue,
    onValue: (TextFieldValue) -> Unit,
    onReference: () -> Unit,
    onImage: () -> Unit,
    onFile: () -> Unit,
    onAudio: () -> Unit,
    recording: Boolean,
    surface: EditorSurfaceState,
    onTable: (Int, Int) -> Unit,
    onCommand: (TextFieldValue) -> Unit = onValue,
    assets: List<AssetEntity> = emptyList(),
    titles: Map<String, String> = emptyMap(),
    onCaret: (Int) -> Unit = {
        onValue(value.copy(selection = androidx.compose.ui.text.TextRange(it)))
    },
    extras: @Composable ColumnScope.() -> Unit,
) {
    var panel by surface.panel
    var focused by remember { mutableStateOf(false) }
    var sourceMode by surface.sourceMode
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = remember { FocusRequester() }
    var restoreKeyboard by surface.restoreKeyboard
    val preferences = LocalContext.current.getSharedPreferences("editor_preferences", 0)
    LaunchedEffect(Unit) {
        if (
            panel == null &&
                surface.tableStart.intValue < 0 &&
                preferences.getBoolean("autoKeyboard", true)
        ) {
            focus.requestFocus()
            keyboard?.show()
        }
    }
    val textColor = MaterialTheme.colorScheme.onSurface
    val rich = remember(textColor) { MarkdownVisualTransformation(textColor) }
    fun closePanel() {
        panel = null
        if (restoreKeyboard) {
            focus.requestFocus()
            keyboard?.show()
        }
    }
    fun command(c: MarkdownCommand) {
        onCommand(MarkdownCommands.apply(value, c))
    }
    BackHandler(panel != null) { closePanel() }
    Column(Modifier.fillMaxSize().imePadding()) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)
        ) {
            BasicTextField(
                title,
                onTitle,
                Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 20.dp).testTag("title-field"),
                textStyle =
                    TextStyle(
                        fontSize = 30.sp,
                        lineHeight = 40.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.tertiary),
                decorationBox = { inner ->
                    if (title.isEmpty())
                        Text("标题", fontSize = 30.sp, color = MaterialTheme.colorScheme.outline)
                    inner()
                },
            )
            if (value.text.length > 150_000)
                LongMarkdownEditor(
                    value,
                    onValue,
                    Modifier.focusRequester(focus).onFocusChanged { focused = it.isFocused },
                )
            else if (!sourceMode)
                RichMarkdownFields(
                    value,
                    onValue,
                    onCommand,
                    assets,
                    titles,
                    focus,
                    { focused = it },
                    onCaret,
                    panel == null &&
                        surface.tableStart.intValue < 0 &&
                        (focused || preferences.getBoolean("autoKeyboard", true)),
                    onTable,
                )
            else
                BasicTextField(
                    value,
                    { next ->
                        onValue(
                            if (sourceMode) next else MarkdownCommands.normalizeInput(value, next)
                        )
                    },
                    Modifier.fillMaxWidth()
                        .heightIn(min = 220.dp)
                        .focusRequester(focus)
                        .onFocusChanged { focused = it.isFocused }
                        .testTag("body-field"),
                    textStyle =
                        TextStyle(
                            fontSize = 17.sp,
                            lineHeight = 29.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                        ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.tertiary),
                    visualTransformation =
                        if (sourceMode || value.text.length > 150_000) VisualTransformation.None
                        else rich,
                    decorationBox = { inner ->
                        if (value.text.isEmpty())
                            Text(
                                "开始记录…",
                                fontSize = 17.sp,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        inner()
                    },
                )
            extras()
            Spacer(Modifier.height(60.dp))
        }
        FloatingPill(
            Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
        ) {
            IconButton(
                {
                    restoreKeyboard = focused
                    keyboard?.hide()
                    panel = if (panel == "format") null else "format"
                },
                Modifier.testTag("format-tools"),
                enabled = focused || panel != null,
            ) {
                Icon(Icons.Default.TextFields, "格式")
            }
            IconButton({ onReference() }, Modifier.semantics { contentDescription = "插入双链" }) {
                Text("@", fontSize = 24.sp)
            }
            IconButton(onImage) { Icon(Icons.Default.Image, "添加图片") }
            IconButton({
                restoreKeyboard = focused
                keyboard?.hide()
                panel = if (panel == "blocks") null else "blocks"
            }) {
                Icon(Icons.Default.AddCircleOutline, "添加内容")
            }
            IconButton(
                { sourceMode = !sourceMode },
                Modifier.testTag("markdown-source-toggle"),
                enabled = value.text.length <= 150_000,
            ) {
                Icon(
                    if (sourceMode) Icons.Default.Visibility else Icons.Default.Code,
                    if (sourceMode) "显示格式" else "Markdown 源码",
                )
            }
        }
        AnimatedVisibility(
            panel != null,
            enter = slideInVertically(tween(220)) { it } + fadeIn(),
            exit = slideOutVertically(tween(180)) { it } + fadeOut(),
        ) {
            Surface(
                Modifier.fillMaxWidth().heightIn(max = 310.dp),
                shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp),
                color = MaterialTheme.colorScheme.surface,
            ) {
                Column(Modifier.padding(bottom = 12.dp)) {
                    SheetTitle(if (panel == "format") "格式" else "添加内容", ::closePanel)
                    if (panel == "format")
                        Column(Modifier.verticalScroll(rememberScrollState())) {
                            Row(
                                Modifier.fillMaxWidth()
                                    .horizontalScroll(rememberScrollState())
                                    .padding(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                listOf(
                                        MarkdownCommand.H1,
                                        MarkdownCommand.H2,
                                        MarkdownCommand.H3,
                                        MarkdownCommand.Body,
                                        MarkdownCommand.Quote,
                                    )
                                    .forEach { c ->
                                        OutlinedButton(
                                            { command(c) },
                                            shape = RoundedCornerShape(14.dp),
                                        ) {
                                            Text(c.label)
                                        }
                                    }
                            }
                            FlowRow(
                                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                listOf(
                                        MarkdownCommand.Bold,
                                        MarkdownCommand.Italic,
                                        MarkdownCommand.Strike,
                                        MarkdownCommand.InlineCode,
                                        MarkdownCommand.Bullet,
                                        MarkdownCommand.Numbered,
                                        MarkdownCommand.Task,
                                        MarkdownCommand.Indent,
                                        MarkdownCommand.Outdent,
                                    )
                                    .forEach { c ->
                                        TextButton(
                                            { command(c) },
                                            Modifier.testTag("format-${c.name}"),
                                        ) {
                                            Text(c.label)
                                        }
                                    }
                            }
                        }
                    else
                        FlowRow(
                            Modifier.padding(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            TextButton({
                                command(MarkdownCommand.Task)
                                closePanel()
                            }) {
                                Icon(Icons.Default.CheckBoxOutlineBlank, null)
                                Text("待办")
                            }
                            TextButton({
                                onCommand(
                                    MarkdownCommands.insert(
                                        value,
                                        "\n\n| 列 1 | 列 2 |\n| --- | --- |\n|  |  |\n\n",
                                    )
                                )
                                closePanel()
                            }) {
                                Icon(Icons.Default.TableChart, null)
                                Text("表格")
                            }
                            TextButton({
                                command(MarkdownCommand.Code)
                                closePanel()
                            }) {
                                Icon(Icons.Default.Code, null)
                                Text("代码块")
                            }
                            TextButton({
                                command(MarkdownCommand.Quote)
                                closePanel()
                            }) {
                                Icon(Icons.Default.FormatQuote, null)
                                Text("引用")
                            }
                            TextButton({
                                command(MarkdownCommand.Rule)
                                closePanel()
                            }) {
                                Text("分隔线")
                            }
                            TextButton({
                                panel = null
                                onFile()
                            }) {
                                Icon(Icons.Default.AttachFile, null)
                                Text("文件 / 视频")
                            }
                            TextButton({
                                panel = null
                                onAudio()
                            }) {
                                Icon(if (recording) Icons.Default.Stop else Icons.Default.Mic, null)
                                Text(if (recording) "停止录音" else "录音附件")
                            }
                        }
                }
            }
        }
    }
}

@Composable
internal fun NoteTagsSheet(
    tags: List<TagEntity>,
    ids: String,
    onDismiss: () -> Unit,
    onChange: (String) -> Unit,
    onNew: (String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        SheetTitle("标签", onDismiss)
        FlowRow(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            tags.forEach { tag ->
                val current = ids.split(',').filter(String::isNotBlank).toSet()
                FilterChip(
                    tag.id in current,
                    {
                        onChange(
                            (if (tag.id in current) current - tag.id else current + tag.id)
                                .joinToString(",")
                        )
                    },
                    { Text(tag.name) },
                )
            }
        }
        Row(Modifier.padding(24.dp).imePadding(), verticalAlignment = Alignment.CenterVertically) {
            TextField(
                name,
                { name = it.take(80) },
                Modifier.weight(1f),
                singleLine = true,
                placeholder = { Text("新标签") },
            )
            TextButton(
                {
                    onNew(name.trim())
                    name = ""
                },
                enabled = name.isNotBlank(),
            ) {
                Text("添加")
            }
        }
    }
}
