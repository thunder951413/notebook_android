package io.github.notebook.android.tts

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** A synthesized piece on disk and the characters billed for it. */
class TtsAudio(val file: File, val characters: Int)

/**
 * Calls the configured 通义千问 speech synthesis endpoint (non-streaming): the
 * response carries a short-lived audio URL, which is downloaded to a local file.
 */
class TtsClient(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).writeTimeout(30, TimeUnit.SECONDS).build(),
) {
    /** Synthesizes [text] into an audio file inside [directory]; [TtsAudio.characters] is what the service billed. */
    suspend fun synthesize(settings: TtsSettings, text: String, directory: File): TtsAudio = withContext(Dispatchers.IO) {
        val link = requestAudio(settings, text)
        directory.mkdirs()
        val file = File.createTempFile("tts-", if (settings.api.isAudioStyle) ".mp3" else ".wav", directory)
        try {
            download(link.url, file)
            TtsAudio(file, link.characters ?: text.length)
        } catch (e: Throwable) {
            file.delete()
            throw e
        }
    }

    internal class AudioLink(val url: String, val characters: Int?)

    internal suspend fun requestAudio(settings: TtsSettings, text: String): AudioLink {
        val response = execute(buildRequest(settings, text))
        response.use {
            val body = it.body?.string().orEmpty()
            val json = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull()
            if (!it.isSuccessful) error(failureMessage(it.code, json))
            val parsed = json ?: error("朗读服务返回了无法识别的内容")
            return AudioLink(parseAudioUrl(parsed), parseBilledCharacters(parsed))
        }
    }

    private suspend fun download(url: String, target: File) {
        val response = execute(Request.Builder().url(secureAudioUrl(url)).build())
        response.use {
            if (!it.isSuccessful) error("下载朗读音频失败（${it.code}）")
            val body = it.body ?: error("朗读音频是空的")
            target.outputStream().use { out -> body.byteStream().use { input -> input.copyTo(out) } }
            if (target.length() == 0L) error("朗读音频是空的")
        }
    }

    private suspend fun execute(request: Request): Response = suspendCancellableCoroutine { continuation ->
        val call = http.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWithException(
                    IOException(if (e is java.net.SocketTimeoutException) "朗读服务响应超时" else "无法连接朗读服务：${e.message}", e),
                )
            }

            override fun onResponse(call: Call, response: Response) { continuation.resume(response) }
        })
    }

    companion object {
        private val LAN_HTTP = Regex("^http://(127\\.0\\.0\\.1|localhost|10\\.0\\.2\\.2|192\\.168\\.\\d+\\.\\d+)(:\\d+)?(/|$)")

        fun buildRequest(settings: TtsSettings, text: String): Request {
            val endpoint = normalizeEndpoint(settings.endpoint)
            require(settings.apiKey.isNotBlank()) { "请先填写朗读服务的 API Key" }
            require(endpoint.isNotEmpty() && !endpoint.contains('<')) { "请先填写完整的接口地址（业务空间 ID）" }
            require(endpoint.startsWith("https://") || LAN_HTTP.containsMatchIn(endpoint)) { "朗读接口地址必须使用 HTTPS（局域网地址除外）" }
            val url = endpoint.toHttpUrlOrNull() ?: throw IllegalArgumentException("接口地址格式不正确")
            val input = JsonObject().apply {
                addProperty("text", text)
                addProperty("voice", settings.voice.trim())
                when (settings.api) {
                    TtsApi.Qwen3Flash -> addProperty("language_type", settings.language.ifBlank { "Auto" })
                    TtsApi.QwenAudio, TtsApi.QianwenAudio -> {
                        addProperty("format", "mp3")
                        addProperty("sample_rate", 24000)
                        if (settings.instruction.isNotBlank()) addProperty("instruction", settings.instruction.trim())
                    }
                }
            }
            val body = JsonObject().apply {
                addProperty("model", settings.model.trim())
                add("input", input)
            }
            return Request.Builder().url(url)
                .header("Authorization", "Bearer ${settings.apiKey.trim()}")
                .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        }

        private val SOCKET_ENDPOINT = Regex("^wss://([^/]+)/api-ws/v1/inference/?$")

        /**
         * The platform documents a WebSocket address (`wss://HOST/api-ws/v1/inference`); the
         * same host serves the plain HTTP synthesis path this client uses.
         */
        fun normalizeEndpoint(raw: String): String {
            val endpoint = raw.trim()
            val socket = SOCKET_ENDPOINT.find(endpoint) ?: return endpoint
            return "https://${socket.groupValues[1]}/api/v1/services/audio/tts/SpeechSynthesizer"
        }

        fun parseAudioUrl(json: JsonObject): String =
            json.getAsJsonObject("output")?.getAsJsonObject("audio")?.get("url")?.takeUnless { it.isJsonNull }?.asString
                ?.takeIf(String::isNotBlank)
                ?: error(failureMessage(null, json).ifBlank { "朗读服务没有返回音频" })

        /** `usage.characters`: the character count the service charges for this request. */
        fun parseBilledCharacters(json: JsonObject): Int? =
            json.getAsJsonObject("usage")?.get("characters")?.takeUnless { it.isJsonNull }?.asInt?.takeIf { it > 0 }

        fun failureMessage(status: Int?, json: JsonObject?): String {
            val message = json?.get("message")?.takeUnless { it.isJsonNull }?.asString?.take(200).orEmpty()
            val code = json?.get("code")?.takeUnless { it.isJsonNull }?.asString.orEmpty()
            val hint = when {
                status == 401 || code == "InvalidApiKey" -> "API Key 无效或与接口地域不匹配"
                status == 429 -> "请求太频繁或额度不足"
                else -> ""
            }
            return listOf(hint, message, status?.let { "（HTTP $it）" }.orEmpty()).filter(String::isNotBlank).joinToString("：").ifBlank { "朗读服务出错" }
        }

        /** Result files are served from Alibaba Cloud storage, sometimes with a plain-http link. */
        fun secureAudioUrl(url: String): String {
            val trimmed = url.trim()
            val host = trimmed.toHttpUrlOrNull()?.host.orEmpty()
            return if (trimmed.startsWith("http://") && host.endsWith(".aliyuncs.com")) "https://" + trimmed.removePrefix("http://") else trimmed
        }
    }
}
