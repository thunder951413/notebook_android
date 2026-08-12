package io.github.notebook.android.sync

import io.github.notebook.android.BuildConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.io.StringReader
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.XMLConstants
import org.w3c.dom.Element

/**
 * WebDAV transport for the v4 append-only journal repository (e.g. 坚果云).
 *
 * Repository layout under remotePath:
 *   journal/<deviceID>.jsonl   per-device append-only change log
 *   objects/<xx>/<sha256>      content-addressed note/attachment bytes
 *
 * Each device only ever writes its own journal file (single writer), so the
 * read-modify-write append below is race-free across devices.
 */
class WebdavClient(
    baseUrl: String,
    username: String,
    appPassword: String,
    remotePath: String,
    private val allowInsecureHttp: Boolean = BuildConfig.DEBUG,
) {
    private val base = baseUrl.trim().trimEnd('/')
    private val root = safeRemotePath(remotePath)
    private val authorization = "Basic " + android.util.Base64.encodeToString(
        "$username:$appPassword".toByteArray(StandardCharsets.UTF_8),
        android.util.Base64.NO_WRAP,
    )
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        // Do not carry Basic credentials through redirects. The configured
        // endpoint must already be the canonical WebDAV URL.
        .followRedirects(false)
        .followSslRedirects(false)
        // 坚果云 serves HTTP/2 but its h2 implementation stalls OkHttp streams
        // (responses never arrive, only the 60s read timeout fires). Force
        // HTTP/1.1 — the protocol the Electron/Python clients already use.
        .protocols(listOf(okhttp3.Protocol.HTTP_1_1))
        .build()
    private val ensuredDirectories = mutableSetOf<String>()
    private val knownObjects = mutableSetOf<String>()

    companion object {
        /** Idempotent WebDAV verbs make retrying a dropped connection safe. */
        private const val REQUEST_ATTEMPTS = 3
    }

    init {
        val uri = runCatching { java.net.URI(base) }.getOrElse { throw IllegalArgumentException("WebDAV 服务器地址格式不正确") }
        require(uri.scheme == "https" || (allowInsecureHttp && uri.scheme == "http")) { "正式版同步服务必须使用 HTTPS" }
        require(!uri.host.isNullOrBlank()) { "WebDAV 服务器地址缺少主机名" }
        require(username.isNotBlank()) { "请填写 WebDAV 用户名" }
        require(appPassword.isNotBlank()) { "请填写 WebDAV 应用密码" }
    }

    fun journalPath(deviceId: String) = "$root/journal/$deviceId.jsonl"
    fun journalHeadPath(deviceId: String) = "$root/journal/$deviceId.head.json"
    fun objectPath(hash: String) = "$root/objects/${hash.take(2)}/$hash"

    private fun url(path: String) = "$base/$path"
    private val propfindBody = """<?xml version="1.0" encoding="utf-8"?><d:propfind xmlns:d="DAV:"><d:allprop/></d:propfind>"""
        .toByteArray(StandardCharsets.UTF_8).toRequestBody("application/xml; charset=utf-8".toMediaType())

    private fun request(method: String, path: String, body: RequestBody? = null, depth: String? = null): Response {
        val builder = Request.Builder().url(url(path)).header("Authorization", authorization)
        if (depth != null) builder.header("Depth", depth)
        // 坚果云 closes keep-alive connections aggressively and carrier LTE/NR
        // handover can abort an idle TLS socket mid-request. Every WebDAV verb
        // is idempotent (PUT overwrites, MKCOL tolerates existing), so retrying
        // a failed attempt is always safe.
        var lastError: IOException? = null
        repeat(REQUEST_ATTEMPTS) {
            try {
                return http.newCall(builder.method(method, body).build()).execute()
            } catch (error: IOException) {
                lastError = error
            }
        }
        throw lastError ?: IOException("$method $path 失败")
    }

    private fun fail(response: Response, context: String): IOException {
        val code = response.code
        return when {
            code == 401 || code == 403 -> IOException("$context（HTTP $code）：WebDAV 认证失败，请检查用户名与应用密码")
            code in 300..399 -> IOException("$context（HTTP $code）：WebDAV 地址发生重定向，请填写最终的 HTTPS 地址")
            else -> IOException("$context（HTTP $code）")
        }
    }

    private fun Response.requireStatus(context: String) {
        if (!isSuccessful && code !in setOf(405, 409)) throw fail(this, context)
    }

    /** MKCOL every path segment; 坚果云 rejects nested MKCOL (409) when the parent is missing. */
    fun ensureDirectory(path: String) {
        var current = ""
        for (segment in path.split('/').filter(String::isNotBlank)) {
            current = if (current.isEmpty()) segment else "$current/$segment"
            if (current in ensuredDirectories) continue
            request("MKCOL", current).use { it.requireStatus("创建远端目录 $current 失败") }
            ensuredDirectories.add(current)
        }
    }

    fun putText(path: String, text: String) {
        val body = text.toByteArray(StandardCharsets.UTF_8).toRequestBody("application/octet-stream; charset=utf-8".toMediaType())
        request("PUT", path, body).use { it.requireStatus("上传 $path 失败") }
    }

    fun putObject(hash: String, bytes: ByteArray) {
        WebdavJournalProtocol.requireHash(hash)
        if (hash in knownObjects) return
        val existing = getBytes(objectPath(hash))
        if (existing != null && WebdavJournalProtocol.sha256(existing).equals(hash, ignoreCase = true)) {
            knownObjects.add(hash)
            return
        }
        ensureDirectory("$root/objects/${hash.take(2)}")
        val body = bytes.toRequestBody("application/octet-stream".toMediaType())
        request("PUT", objectPath(hash), body).use { it.requireStatus("上传对象 $hash 失败") }
        knownObjects.add(hash)
    }

    fun putObject(hash: String, file: File) {
        WebdavJournalProtocol.requireHash(hash)
        if (hash in knownObjects) return
        val existing = getBytes(objectPath(hash))
        if (existing != null && existing.inputStream().use(WebdavJournalProtocol::sha256).equals(hash, ignoreCase = true)) {
            knownObjects.add(hash)
            return
        }
        ensureDirectory("$root/objects/${hash.take(2)}")
        val body = file.asRequestBody("application/octet-stream".toMediaType())
        request("PUT", objectPath(hash), body).use { it.requireStatus("上传对象 $hash 失败") }
        knownObjects.add(hash)
    }

    /** GET as UTF-8 text, or null when the resource does not exist. */
    fun getText(path: String, maxBytes: Int = WebdavJournalProtocol.MAX_LOG_BYTES): String? =
        getBytes(path, maxBytes)?.toString(StandardCharsets.UTF_8)

    fun getBytes(path: String, maxBytes: Int = WebdavJournalProtocol.MAX_OBJECT_BYTES): ByteArray? {
        request("GET", path).use { response ->
            if (response.code == 404) return null
            if (!response.isSuccessful) throw fail(response, "下载 $path 失败")
            val length = response.body?.contentLength() ?: -1
            if (length > maxBytes) throw IOException("远端文件超过上限，已停止同步")
            val bytes = response.body?.bytes() ?: throw IOException("下载 $path 失败：响应为空")
            if (bytes.size > maxBytes) throw IOException("远端文件超过上限，已停止同步")
            return bytes
        }
    }

    /** Stream a content-addressed object into target, verifying SHA-256 and the transfer budget. */
    internal fun downloadObjectToFile(hash: String, target: File, budget: RemoteTransferLimits.Budget) {
        WebdavJournalProtocol.requireHash(hash)
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        var total = 0L
        request("GET", objectPath(hash)).use { response ->
            if (response.code == 404) throw IOException("远端对象 $hash 不存在")
            if (!response.isSuccessful) throw fail(response, "下载对象 $hash 失败")
            response.body?.let { body ->
                body.contentLength().takeIf { it >= 0 }?.let { RemoteTransferLimits.requireDownloadSize(it) }
                target.parentFile?.mkdirs()
                body.byteStream().use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(1024 * 1024)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            budget.consume(total, count.toLong())
                            digest.update(buffer, 0, count)
                            output.write(buffer, 0, count)
                        }
                    }
                }
            } ?: throw IOException("下载对象 $hash 失败：响应为空")
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        require(actual.equals(hash, ignoreCase = true)) { "对象 $hash 的 SHA-256 校验失败" }
    }

    /** PROPFIND Depth 1: basenames of the direct children of a collection. */
    fun listNames(path: String): List<String> {
        request("PROPFIND", path, propfindBody, depth = "1").use { response ->
            if (response.code == 404) return emptyList()
            if (!response.isSuccessful) throw fail(response, "读取远端目录 $path 失败")
            val text = response.body?.string().orEmpty()
            return parseHrefs(text).map { it.substringAfterLast('/').trimEnd('/') }.filter(String::isNotBlank)
        }
    }

    /** PROPFIND Depth 0: whether the resource exists. */
    fun exists(path: String): Boolean {
        request("PROPFIND", path, propfindBody, depth = "0").use { response ->
            if (response.code == 404) return false
            if (!response.isSuccessful) throw fail(response, "检查远端目录 $path 失败")
            return parseHrefs(response.body?.string().orEmpty()).isNotEmpty()
        }
    }

    /** Journal device IDs currently present in the repository. */
    fun listJournalNames(): List<String> =
        listNames("$root/journal").filter { it.endsWith(".jsonl") }.map { it.removeSuffix(".jsonl") }
            .filter { WebdavJournalProtocol.validDeviceId(it) }

    private fun parseHrefs(text: String): List<String> {
            if (text.isBlank()) return emptyList()
            return runCatching {
                val factory = DocumentBuilderFactory.newInstance().apply {
                    isNamespaceAware = true
                    setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
                    setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                    setFeature("http://xml.org/sax/features/external-general-entities", false)
                    setFeature("http://xml.org/sax/features/external-parameter-entities", false)
                    isXIncludeAware = false
                    isExpandEntityReferences = false
                }
                val document = factory.newDocumentBuilder().parse(org.xml.sax.InputSource(StringReader(text)))
                val hrefs = document.getElementsByTagNameNS("*", "href")
                (0 until hrefs.length).map { (hrefs.item(it) as Element).textContent.trim() }.filter(String::isNotBlank)
            }.getOrDefault(emptyList())
    }
}

/** Repository path relative to the WebDAV root; rejects traversal and absolute paths. */
internal fun safeRemotePath(value: String): String {
    val normalized = value.trim().replace('\\', '/').trimEnd('/')
    require(normalized.isNotBlank()) { "远端同步目录不能为空" }
    require(!normalized.startsWith('/')) { "远端同步目录请填写相对路径（例如 notebook_backup）" }
    require(normalized.none { it.isISOControl() }) { "远端同步目录不能包含控制字符" }
    require(!normalized.split('/').any { it == ".." || it == "." || it.isBlank() }) { "远端同步目录不安全" }
    return normalized
}
