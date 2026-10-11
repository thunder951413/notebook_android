@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package io.github.notebook.android

import android.app.Activity
import android.content.Intent
import android.graphics.*
import android.graphics.pdf.PdfDocument
import android.text.*
import android.text.style.*
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import io.github.notebook.android.data.NoteEntity
import java.io.*
import java.util.zip.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal enum class ExportFormat(val label: String, val extension: String, val mime: String) {
    Markdown("Markdown", "md", "text/markdown"),
    Text("文本", "txt", "text/plain"),
    Word("Word", "docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
    Pdf("PDF", "pdf", "application/pdf"),
    Image("图片", "png", "image/png"),
}

internal enum class ImageTemplate(val label: String, val background: Int, val foreground: Int) {
    Classic("经典", 0xFFFAF6EE.toInt(), 0xFF29251F.toInt()),
    Paper("纸张", 0xFFEAF1F4.toInt(), 0xFF24313D.toInt()),
    Simple("简约", Color.WHITE, 0xFF191919.toInt()),
    Dark("黑金", 0xFF25231E.toInt(), 0xFFE5CF9C.toInt()),
}

internal object NoteExporter {
    fun filename(title: String, format: ExportFormat) =
        title.ifBlank { "笔记" }.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").take(80) +
            "." +
            format.extension

    fun plain(note: NoteEntity, context: android.content.Context) =
        note.title + "\n\n" + createMarkdownRenderer(context).toMarkdown(note.body).toString()

    fun write(
        note: NoteEntity,
        format: ExportFormat,
        context: android.content.Context,
        output: OutputStream,
        template: ImageTemplate = ImageTemplate.Classic,
        ratio: String = "长图",
    ) {
        when (format) {
            ExportFormat.Markdown -> output.write(note.body.toByteArray(Charsets.UTF_8))
            ExportFormat.Text -> output.write(plain(note, context).toByteArray(Charsets.UTF_8))
            ExportFormat.Word -> docx(note, context, output)
            ExportFormat.Pdf -> pdf(note, context, output)
            ExportFormat.Image -> {
                val bitmap = image(note, context, template, ratio)
                try {
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                } finally {
                    bitmap.recycle()
                }
            }
        }
    }

    private fun xml(value: String) =
        value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .filter { it == '\n' || it == '\t' || it.code >= 32 }

    private fun docx(note: NoteEntity, context: android.content.Context, output: OutputStream) {
        ZipOutputStream(output).use { zip ->
            fun entry(name: String, value: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(value.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            entry(
                "[Content_Types].xml",
                """<?xml version="1.0"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>""",
            )
            entry(
                "_rels/.rels",
                """<?xml version="1.0"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>""",
            )
            val paragraphs =
                plain(note, context)
                    .lines()
                    .mapIndexed { index, line ->
                        "<w:p><w:r>" +
                            (if (index == 0) "<w:rPr><w:b/><w:sz w:val=\"40\"/></w:rPr>" else "") +
                            "<w:t xml:space=\"preserve\">${xml(line)}</w:t></w:r></w:p>"
                    }
                    .joinToString("")
            entry(
                "word/document.xml",
                """<?xml version="1.0" encoding="UTF-8"?><w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>$paragraphs<w:sectPr><w:pgSz w:w="11906" w:h="16838"/><w:pgMar w:top="1134" w:bottom="1134" w:left="1134" w:right="1134"/></w:sectPr></w:body></w:document>""",
            )
        }
    }

    private fun layout(
        note: NoteEntity,
        context: android.content.Context,
        width: Int,
        size: Float,
        color: Int,
    ): StaticLayout {
        val text =
            SpannableStringBuilder(note.title.ifBlank { "无标题" } + "\n\n").apply {
                setSpan(StyleSpan(Typeface.BOLD), 0, length - 2, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(RelativeSizeSpan(1.55f), 0, length - 2, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                append(createMarkdownRenderer(context).toMarkdown(note.body))
            }
        val paint =
            TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = size
                this.color = color
            }
        return StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setLineSpacing(size * .3f, 1f)
            .setIncludePad(false)
            .build()
    }

    private fun pdf(note: NoteEntity, context: android.content.Context, output: OutputStream) {
        val layout = layout(note, context, 499, 14f, Color.BLACK)
        val pageHeight = 746
        val document = PdfDocument()
        try {
            var firstLine = 0
            var number = 1
            while (firstLine < layout.lineCount) {
                val top = layout.getLineTop(firstLine)
                var last = firstLine + 1
                while (
                    last < layout.lineCount && layout.getLineBottom(last) - top <= pageHeight
                ) last++
                val bottom = layout.getLineBottom(last - 1)
                val page =
                    document.startPage(PdfDocument.PageInfo.Builder(595, 842, number++).create())
                page.canvas.save()
                page.canvas.translate(48f, 48f - top)
                page.canvas.clipRect(0, top, 499, bottom)
                layout.draw(page.canvas)
                page.canvas.restore()
                document.finishPage(page)
                firstLine = last
            }
            document.writeTo(output)
        } finally {
            document.close()
        }
    }

    fun image(
        note: NoteEntity,
        context: android.content.Context,
        template: ImageTemplate,
        ratio: String,
    ): Bitmap {
        val width = 1080
        val targetHeight =
            when (ratio) {
                "1:1" -> 1080
                "3:4" -> 1440
                else -> null
            }
        var textSize = 36f
        var layout = layout(note, context, width - 144, textSize, template.foreground)
        while (targetHeight != null && layout.height + 180 > targetHeight && textSize > 16f) {
            textSize *= .9f
            layout = layout(note, context, width - 144, textSize, template.foreground)
        }
        require(targetHeight == null || layout.height + 180 <= targetHeight) {
            "此比例放不下完整正文，请选择长图或 PDF"
        }
        val height = targetHeight ?: maxOf(480, layout.height + 180)
        require(height <= 16000) { "正文过长，图片超过 16000 像素；请使用 PDF 导出完整文档" }
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            val canvas = Canvas(this)
            canvas.drawColor(template.background)
            canvas.translate(72f, 80f)
            layout.draw(canvas)
        }
    }
}

@Composable
internal fun ExportSheet(note: NoteEntity, onDismiss: () -> Unit, onFlush: suspend () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var format by rememberSaveable { mutableStateOf(ExportFormat.Markdown) }
    var template by rememberSaveable { mutableStateOf(ImageTemplate.Classic) }
    var ratio by rememberSaveable { mutableStateOf("长图") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(format, template, ratio, note.body) {
        if (format == ExportFormat.Image) {
            runCatching {
                    withContext(Dispatchers.Default) {
                        NoteExporter.image(note, context, template, ratio)
                    }
                }
                .onSuccess { preview = it }
                .onFailure {
                    error = it.localizedMessage
                    preview = null
                }
        } else preview = null
    }
    val save =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result
            ->
            if (result.resultCode == Activity.RESULT_OK)
                result.data?.data?.let { uri ->
                    busy = true
                    scope.launch {
                        runCatching {
                                onFlush()
                                withContext(Dispatchers.IO) {
                                    context.contentResolver.openOutputStream(uri)?.use {
                                        NoteExporter.write(
                                            note,
                                            format,
                                            context,
                                            it,
                                            template,
                                            ratio,
                                        )
                                    } ?: error("无法写入文件")
                                }
                            }
                            .onSuccess { error = "文件已保存" }
                            .onFailure { error = it.localizedMessage }
                        busy = false
                    }
                }
        }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            SheetTitle("分享与导出", onDismiss)
            FlowRow(Modifier.padding(horizontal = 16.dp)) {
                ExportFormat.entries.forEach { value ->
                    FilterChip(
                        format == value,
                        {
                            format = value
                            error = null
                        },
                        { Text(value.label) },
                        modifier = Modifier.padding(end = 8.dp),
                    )
                }
            }
            if (format == ExportFormat.Image) {
                FlowRow(Modifier.padding(horizontal = 16.dp)) {
                    ImageTemplate.entries.forEach { value ->
                        FilterChip(
                            template == value,
                            {
                                template = value
                                error = null
                            },
                            { Text(value.label) },
                            modifier = Modifier.padding(end = 8.dp),
                        )
                    }
                }
                Row(Modifier.padding(horizontal = 16.dp)) {
                    listOf("长图", "3:4", "1:1").forEach { value ->
                        FilterChip(
                            ratio == value,
                            {
                                ratio = value
                                error = null
                            },
                            { Text(value) },
                            modifier = Modifier.padding(end = 8.dp),
                        )
                    }
                }
                preview?.let {
                    Image(
                        it.asImageBitmap(),
                        "导出图片预览",
                        Modifier.fillMaxWidth().heightIn(max = 240.dp).padding(16.dp),
                    )
                }
            }
            Text(
                "Markdown 导出保留正文源；其他格式导出可读文字，原附件继续留在笔记中。",
                Modifier.padding(20.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            error?.let {
                Text(
                    it,
                    Modifier.padding(horizontal = 20.dp),
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    {
                        save.launch(
                            Intent(Intent.ACTION_CREATE_DOCUMENT)
                                .addCategory(Intent.CATEGORY_OPENABLE)
                                .setType(format.mime)
                                .putExtra(
                                    Intent.EXTRA_TITLE,
                                    NoteExporter.filename(note.title, format),
                                )
                        )
                    },
                    Modifier.weight(1f),
                    enabled = !busy,
                ) {
                    Text("保存文件")
                }
                Button(
                    {
                        busy = true
                        scope.launch {
                            runCatching {
                                    onFlush()
                                    val file =
                                        withContext(Dispatchers.IO) {
                                            File(
                                                    context.cacheDir,
                                                    "exports/${NoteExporter.filename(note.title,format)}",
                                                )
                                                .apply {
                                                    parentFile?.mkdirs()
                                                    outputStream().use {
                                                        NoteExporter.write(
                                                            note,
                                                            format,
                                                            context,
                                                            it,
                                                            template,
                                                            ratio,
                                                        )
                                                    }
                                                }
                                        }
                                    val uri =
                                        FileProvider.getUriForFile(
                                            context,
                                            "${context.packageName}.files",
                                            file,
                                        )
                                    context.startActivity(
                                        Intent.createChooser(
                                            Intent(Intent.ACTION_SEND)
                                                .setType(format.mime)
                                                .putExtra(Intent.EXTRA_STREAM, uri)
                                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                                            "分享笔记",
                                        )
                                    )
                                }
                                .onFailure { error = it.localizedMessage }
                            busy = false
                        }
                    },
                    Modifier.weight(1f),
                    enabled = !busy,
                ) {
                    Text(if (busy) "正在导出…" else "分享")
                }
            }
        }
    }
}
