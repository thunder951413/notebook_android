package io.github.notebook.android.tts

import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TtsClientTest {
    @get:Rule val folder = TemporaryFolder()
    private val server = MockWebServer()

    @Before fun start() = server.start()
    @After fun stop() = server.shutdown()

    private fun settings(api: TtsApi = TtsApi.Qwen3Flash) = TtsSettings(
        api = api, endpoint = server.url("/api/v1/tts").toString(), apiKey = "sk-test", model = api.defaultModel,
        voice = "Serena", language = "Chinese", instruction = "语气温柔",
    )

    @Test fun `千问3 request carries model voice and language`() {
        val request = TtsClient.buildRequest(settings(), "你好")
        val buffer = okio.Buffer().also { request.body!!.writeTo(it) }
        val body = JsonParser.parseString(buffer.readUtf8()).asJsonObject
        assertEquals("qwen3-tts-flash", body["model"].asString)
        val input = body.getAsJsonObject("input")
        assertEquals("你好", input["text"].asString)
        assertEquals("Serena", input["voice"].asString)
        assertEquals("Chinese", input["language_type"].asString)
        assertEquals("Bearer sk-test", request.header("Authorization"))
    }

    @Test fun `audio tts request asks for mp3 and passes the instruction`() {
        val request = TtsClient.buildRequest(settings(TtsApi.QwenAudio).copy(model = "qwen-audio-3.1-tts-flash"), "你好")
        val buffer = okio.Buffer().also { request.body!!.writeTo(it) }
        val input = JsonParser.parseString(buffer.readUtf8()).asJsonObject.getAsJsonObject("input")
        assertEquals("mp3", input["format"].asString)
        assertEquals("语气温柔", input["instruction"].asString)
        assertTrue(!input.has("language_type"))
    }

    @Test fun `unfinished or insecure settings are rejected before any request`() {
        listOf(
            settings().copy(apiKey = ""),
            settings(TtsApi.QwenAudio).copy(endpoint = TtsApi.QwenAudio.defaultEndpoint),
            settings().copy(endpoint = "http://example.com/tts"),
        ).forEach { bad ->
            try { TtsClient.buildRequest(bad, "x"); fail("accepted $bad") } catch (_: IllegalArgumentException) {}
        }
    }

    @Test fun `synthesize downloads the returned audio`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"output":{"audio":{"url":"${server.url("/audio.wav")}"},"finish_reason":"stop"},"usage":{"characters":42},"request_id":"r"}"""))
        server.enqueue(MockResponse().setBody(okio.Buffer().writeUtf8("RIFFdata")))
        val audio = TtsClient().synthesize(settings(), "你好", folder.newFolder())
        assertEquals("RIFFdata", audio.file.readText())
        // The billed count from the service wins over the length of the text.
        assertEquals(42, audio.characters)
        assertEquals("/api/v1/tts", server.takeRequest().path)
        val download = server.takeRequest()
        assertEquals("/audio.wav", download.path)
        // The signed storage link never receives the API key.
        assertEquals(null, download.getHeader("Authorization"))
    }

    @Test fun `without usage in the reply the text length is counted`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"output":{"audio":{"url":"${server.url("/audio.wav")}"}}}"""))
        server.enqueue(MockResponse().setBody(okio.Buffer().writeUtf8("RIFF")))
        assertEquals(2, TtsClient().synthesize(settings(), "你好", folder.newFolder()).characters)
    }

    @Test fun `service errors become readable messages`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"code":"InvalidApiKey","message":"Invalid API-key provided."}"""))
        try {
            TtsClient().synthesize(settings(), "你好", folder.newFolder())
            fail("expected failure")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("API Key"))
            assertTrue(e.message!!.contains("401"))
        }
    }

    @Test fun `plain http result links on aliyun are upgraded`() {
        assertEquals(
            "https://dashscope-result-bj.oss-cn-beijing.aliyuncs.com/a.wav?x=1",
            TtsClient.secureAudioUrl("http://dashscope-result-bj.oss-cn-beijing.aliyuncs.com/a.wav?x=1"),
        )
        assertEquals("http://localhost:8080/a.wav", TtsClient.secureAudioUrl("http://localhost:8080/a.wav"))
    }
}
