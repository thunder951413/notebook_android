package io.github.notebook.android.ai

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.github.notebook.android.data.FolderEntity
import io.github.notebook.android.data.NoteSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The AI assistant's notebook-side rules (shared with the desktop app in
 * apps/renderer/src/ai/assistant.ts) and a minimal OpenAI-compatible client.
 */

enum class AiProvider(val label: String, val baseUrl: String, val model: String) {
    DeepSeek("DeepSeek", "https://api.deepseek.com", "deepseek-v4-flash"),
    OpenAI("OpenAI", "https://api.openai.com/v1", "gpt-5-mini"),
    Custom("自定义（OpenAI 兼容）", "", ""),
}

data class AiSettings(val provider: AiProvider = AiProvider.DeepSeek, val baseUrl: String = AiProvider.DeepSeek.baseUrl, val model: String = AiProvider.DeepSeek.model, val apiKey: String = "") {
    val configured get() = apiKey.isNotBlank() && baseUrl.isNotBlank() && model.isNotBlank()
}

data class ChatMessage(val role: String, val content: String)

/** Folders and note subtrees picked by the user. Empty means all notes. */
data class AiScope(val folderIds: Set<String> = emptySet(), val noteIds: Set<String> = emptySet(), val extraNoteIds: Set<String> = emptySet()) {
    val isEmpty get() = folderIds.isEmpty() && noteIds.isEmpty() && extraNoteIds.isEmpty()
}

data class ContextSource(val index: Int, val noteId: String, val title: String, val path: String)
data class AiContext(val text: String, val sources: List<ContextSource>, val truncated: Boolean)

object AiAssistant {
    const val CONTEXT_CHARACTERS = 120_000

    enum class Task(val label: String, val instruction: String) {
        Summary("总结要点", "把这些笔记的要点整理成一份简明的总结：先用两三句话概括，再分条列出关键信息。"),
        Outline("整理提纲", "把这些笔记的内容整理成层次清楚的提纲（多级列表），合并重复内容。"),
        Weekly("写周报", "根据这些笔记写一份周报：本周完成、进行中、问题与风险、下周计划。没有依据的部分写“无”。"),
        Todos("提取待办", "从这些笔记中找出所有待办事项、未完成的任务和需要跟进的事情，用任务列表（- [ ]）列出，并注明来自哪篇笔记。"),
    }

    val SYSTEM_PROMPT = listOf(
        "你是用户个人笔记应用里的 AI 助手。",
        "只能依据用户提供的笔记内容回答；笔记里没有的内容要明确说“笔记中没有找到”，不要编造。",
        "笔记按 [编号] 标明。引用事实时在句末用 [编号] 标注来源，例如 [2]；可以同时引用多个，例如 [1][3]。",
        "用中文回答，格式用 Markdown（标题、列表、表格、代码块）。回答简洁、直接。",
    ).joinToString("\n")

    /** Notes in scope, in tree order; to-dos and deleted notes excluded. */
    fun resolve(scope: AiScope, notes: List<NoteSummary>, folders: List<FolderEntity>, isPrivate: (NoteSummary) -> Boolean): List<NoteSummary> {
        val usable = notes.filter { it.deletedAt == null && it.itemType != "todo" && !isPrivate(it) }
        val ordered = treeOrder(usable, folders)
        if (scope.isEmpty) return ordered
        val children = usable.groupBy { it.parentPageId }
        val picked = mutableSetOf<String>()
        fun addSubtree(id: String) { if (picked.add(id)) children[id]?.forEach { addSubtree(it.id) } }
        usable.forEach { if (it.folderId in scope.folderIds || it.id in scope.extraNoteIds) picked += it.id }
        scope.noteIds.forEach { if (usable.any { note -> note.id == it }) addSubtree(it) }
        return ordered.filter { it.id in picked }
    }

    /** Folder by folder (in folder order), parents before children. */
    fun treeOrder(notes: List<NoteSummary>, folders: List<FolderEntity>): List<NoteSummary> {
        val ids = notes.map { it.id }.toSet()
        val children = notes.groupBy { it.parentPageId?.takeIf { parent -> parent in ids } }
        val result = mutableListOf<NoteSummary>()
        fun walk(note: NoteSummary) { result += note; children[note.id]?.sortedBy { it.sortOrder }?.forEach(::walk) }
        val roots = children[null].orEmpty()
        val folderOrder = folders.sortedBy { it.sortOrder }.map { it.id }
        roots.sortedWith(compareBy<NoteSummary>({ folderOrder.indexOf(it.folderId).let { index -> if (index < 0) Int.MAX_VALUE else index } }, { it.sortOrder })).forEach(::walk)
        return result
    }

    fun path(note: NoteSummary, notes: List<NoteSummary>): String {
        val byId = notes.associateBy { it.id }
        val parts = ArrayDeque<String>()
        var cursor = note.parentPageId?.let(byId::get)
        var guard = 0
        while (cursor != null && guard++ < 32) { parts.addFirst(cursor.title.ifBlank { "无标题" }); cursor = cursor.parentPageId?.let(byId::get) }
        parts.addFirst(note.folderName.ifBlank { "未分类" })
        return parts.joinToString(" / ")
    }

    fun shiftHeadings(markdown: String): String {
        var fence: String? = null
        return markdown.lines().joinToString("\n") { line ->
            val marker = Regex("^\\s*(```+|~~~+)").find(line)?.groupValues?.get(1)
            when {
                marker != null -> { fence = if (fence == null) marker.substring(0, 1) else if (marker.startsWith(fence!!)) null else fence; line }
                fence != null -> line
                else -> Regex("^(#{1,6})(\\s)").replace(line) { "#".repeat(minOf(6, it.groupValues[1].length + 1)) + it.groupValues[2] }
            }
        }
    }

    data class Merged(val note: NoteSummary, val path: String, val markdown: String)

    fun mergedMarkdown(notes: List<Merged>, title: String?): String {
        val parts = notes.joinToString("\n\n---\n\n") { "## ${it.note.title.ifBlank { "无标题" }}\n\n> ${it.path}\n\n${shiftHeadings(it.markdown.trim()).ifBlank { "（空白笔记）" }}" }
        return (if (title != null) "# $title\n\n" else "") + parts + "\n"
    }

    /**
     * Numbered sources the model cites as [1], [2]… Whole notes while they
     * fit the budget; when they do not, notes sharing the most words with the
     * request go first.
     */
    fun context(notes: List<Merged>, request: String, budget: Int = CONTEXT_CHARACTERS): AiContext {
        val total = notes.sumOf { it.markdown.length }
        val ordered = if (total <= budget || request.isBlank()) notes else {
            val terms = request.lowercase().split(Regex("[\\s，。、,.?？!！]+")).filter { it.length >= 2 } + request.lowercase().windowed(2).filter { it.none(Char::isWhitespace) }
            notes.sortedByDescending { note -> val text = (note.note.title + "\n" + note.markdown).lowercase(); terms.count { text.contains(it) } }
        }
        val sources = mutableListOf<ContextSource>()
        val blocks = mutableListOf<String>()
        var used = 0
        var truncated = total > budget
        for (note in ordered) {
            val remaining = budget - used
            if (remaining <= 200) { truncated = true; break }
            val text = if (note.markdown.length > remaining) note.markdown.take(remaining) + "\n…（已截断）" else note.markdown
            val index = sources.size + 1
            sources += ContextSource(index, note.note.id, note.note.title.ifBlank { "无标题" }, note.path)
            blocks += "[$index] ${note.note.title}（${note.path}）\n${text.trim()}"
            used += text.length
        }
        return AiContext(blocks.joinToString("\n\n====\n\n"), sources, truncated)
    }

    fun messages(instruction: String, context: AiContext, history: List<ChatMessage> = emptyList()) = listOf(
        ChatMessage("system", SYSTEM_PROMPT),
        ChatMessage("user", "以下是我选中的笔记：\n\n${context.text}${if (context.truncated) "\n\n（注意：笔记太长，部分内容已截断。）" else ""}"),
        ChatMessage("assistant", "好的，我已阅读这些笔记。"),
    ) + history.takeLast(6) + ChatMessage("user", instruction)

    fun searchMessages(query: String, candidates: List<Triple<Int, String, String>>) = listOf(
        ChatMessage("system", "你帮助用户在自己的笔记里查找内容。根据含义而不是字面匹配判断相关性。只输出 JSON。"),
        ChatMessage("user", "候选笔记：\n${candidates.joinToString("\n\n") { "[${it.first}] ${it.second}\n${it.third}" }}\n\n要找的是：$query\n\n按相关程度从高到低，输出最多 10 个相关的笔记，格式：{\"results\":[{\"index\":编号,\"reason\":\"一句话说明为什么相关\"}]}。都不相关就输出 {\"results\":[]}。"),
    )

    fun parseSearchResults(content: String): List<Pair<Int, String>> {
        val json = Regex("\\{[\\s\\S]*\\}").find(content)?.value ?: return emptyList()
        return runCatching {
            JsonParser.parseString(json).asJsonObject.getAsJsonArray("results").mapNotNull { element ->
                val item = element.asJsonObject
                val index = item.get("index")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt ?: return@mapNotNull null
                index to (item.get("reason")?.takeIf { it.isJsonPrimitive }?.asString ?: "")
            }
        }.getOrDefault(emptyList())
    }

    fun citedSources(answer: String): List<Int> = Regex("\\[(\\d{1,3})]").findAll(answer).map { it.groupValues[1].toInt() }.distinct().sorted().toList()
}

/** OpenAI-compatible `chat/completions`, non-streaming. */
class AiClient(private val http: OkHttpClient = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(180, TimeUnit.SECONDS).writeTimeout(60, TimeUnit.SECONDS).build()) {
    suspend fun complete(settings: AiSettings, messages: List<ChatMessage>): String = withContext(Dispatchers.IO) {
        require(settings.configured) { "请先设置 AI 服务和 API Key" }
        val base = settings.baseUrl.trim().trimEnd('/')
        require(base.startsWith("https://") || Regex("^http://(127\\.0\\.0\\.1|localhost|10\\.0\\.2\\.2|192\\.168\\.\\d+\\.\\d+)(:\\d+)?(/|$)").containsMatchIn(base)) { "AI 服务地址必须使用 HTTPS（局域网地址除外）" }
        val body = JsonObject().apply {
            addProperty("model", settings.model.trim())
            addProperty("stream", false)
            add("messages", Gson().toJsonTree(messages.map { mapOf("role" to it.role, "content" to it.content) }))
        }
        val request = Request.Builder().url("$base/chat/completions").header("Authorization", "Bearer ${settings.apiKey.trim()}")
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        val call = http.newCall(request)
        val response = suspendCancellableCoroutine<Response> { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { continuation.resumeWithException(IOException(if (e is java.net.SocketTimeoutException) "AI 服务响应超时" else "无法连接 AI 服务：${e.message}", e)) }
                override fun onResponse(call: Call, response: Response) { continuation.resume(response) }
            })
        }
        response.use {
            val text = it.body?.string().orEmpty()
            val json = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull()
            if (!it.isSuccessful) {
                val message = json?.getAsJsonObject("error")?.get("message")?.asString
                error(message?.take(300) ?: "AI 服务返回 ${it.code}")
            }
            json?.getAsJsonArray("choices")?.firstOrNull()?.asJsonObject?.getAsJsonObject("message")?.get("content")?.asString?.takeIf(String::isNotBlank)
                ?: error("AI 服务没有返回正文")
        }
    }
}
