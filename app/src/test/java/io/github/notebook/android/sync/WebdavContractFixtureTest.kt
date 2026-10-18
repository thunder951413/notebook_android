package io.github.notebook.android.sync

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.Base64

/**
 * Kotlin runner for the shared WebDAV v4 protocol fixtures. The canonical
 * files live in the desktop repository at SyncContractFixtures/webdav-v4 and
 * are read there by the TypeScript and Rust runners; this repository carries
 * a copy under test resources so it builds on its own. When the desktop
 * checkout is next to this one, the copy must be identical.
 */
@RunWith(RobolectricTestRunner::class)
class WebdavContractFixtureTest {
    companion object {
        val NAMES = listOf(
            "base-url.json", "committed-view.json", "conflict.json", "journal-compact.json", "journal-head.json",
            "journal-parse.json", "journal-rebuild.json", "layout.json", "privacy-envelope.json", "propfind-listing.json", "pull-isolation.json", "pull-order.json", "remote-path.json",
        )

        fun fixtureText(name: String): String =
            requireNotNull(WebdavContractFixtureTest::class.java.classLoader!!.getResourceAsStream("sync-contract/webdav-v4/$name")) { name }
                .use { it.readBytes().toString(Charsets.UTF_8) }

        fun fixture(name: String): JsonObject = JsonParser.parseString(fixtureText(name)).asJsonObject
    }

    private fun JsonObject.cases(key: String = "cases"): List<JsonObject> = getAsJsonArray(key).map { it.asJsonObject }
    private fun JsonObject.label(): String = (get("name") ?: get("input"))?.toString().orEmpty()
    private fun JsonArray.longs() = map { it.asLong }
    private fun JsonElement?.isTrue() = this != null && !isJsonNull && asBoolean

    @Test fun `bundled fixtures match the desktop repository copy when it is checked out alongside`() {
        val canonical = listOf("../../SyncContractFixtures/webdav-v4", "../SyncContractFixtures/webdav-v4").map(::File).firstOrNull { it.isDirectory }
            ?: return
        for (name in NAMES) assertEquals("$name drifted from ${canonical.path}", File(canonical, name).readText(), fixtureText(name))
        assertEquals("fixture set", NAMES.toSet(), canonical.list()!!.filter { it.endsWith(".json") }.toSet())
    }

    @Test fun `remote-path`() {
        for (case in fixture("remote-path.json").cases()) {
            val result = runCatching { safeRemotePath(case["input"].asString) }
            if (case["error"].isTrue()) assertTrue("${case.label()} should be rejected", result.isFailure)
            else assertEquals(case.label(), case["expected"].asString, result.getOrThrow())
        }
    }

    @Test fun `base-url (Android release builds also reject loopback HTTP)`() {
        for (case in fixture("base-url.json").cases()) {
            val ok = runCatching { WebdavProtocolRules.requireBaseUrl(case["input"].asString, allowInsecureHttp = false) }.isSuccess
            assertEquals(case.label(), case["ok"].asBoolean && !case["loopback"].isTrue(), ok)
        }
    }

    @Test fun layout() {
        for (case in fixture("layout.json").cases()) {
            val client = WebdavClient("https://dav.example.com/dav/", "user", "password", case["root"].asString)
            assertEquals(case["journalPath"].asString, client.journalPath(case["deviceId"].asString))
            assertEquals(case["headPath"].asString, client.journalHeadPath(case["deviceId"].asString))
            assertEquals(case["objectPath"].asString, client.objectPath(case["hash"].asString))
        }
    }

    @Test fun `journal-parse`() {
        for (case in fixture("journal-parse.json").cases()) {
            val result = runCatching { WebdavJournalProtocol.parseJournal(case["text"].asString) }
            if (case["error"].isTrue()) {
                assertTrue("${case.label()} should be rejected", result.isFailure)
                continue
            }
            val entries = result.getOrThrow()
            val expected = case.getAsJsonArray("expected").map { it.asJsonObject }
            assertEquals(case.label(), expected.size, entries.size)
            for ((actual, row) in entries.zip(expected)) {
                assertEquals(case.label(), row["seq"].asLong, actual.seq)
                assertEquals(case.label(), row["op"].asString, actual.op)
                assertEquals(case.label(), row["type"].asString, actual.type)
                assertEquals(case.label(), row["id"].asString, actual.id)
                assertEquals(case.label(), row["ver"].asLong, actual.ver)
                assertEquals(case.label(), row["hash"]?.asString, actual.hash)
            }
        }
    }

    @Test fun `journal-compact`() {
        for (case in fixture("journal-compact.json").cases()) {
            val entries = WebdavJournalProtocol.parseJournal(case.getAsJsonArray("entries").joinToString("\n") { it.toString() })
            val compacted = WebdavProtocolRules.compact(entries)
            assertEquals(case.label(), case.getAsJsonArray("expectedSeqs").longs(), compacted.entries.map { it.seq })
            assertEquals(case.label(), case["stateEntries"].asInt, compacted.stateEntries)
            assertEquals(case.label(), case["historyEntries"].asInt, compacted.historyEntries)
        }
    }

    @Test fun `journal-head`() {
        val value = fixture("journal-head.json")
        for (case in value.cases("build")) {
            val body = case["body"].asString
            val bytes = body.toByteArray(Charsets.UTF_8)
            val expected = case["expected"].asJsonObject
            assertEquals(case.label(), expected["lastSeq"].asLong, WebdavJournalProtocol.parseJournal(body).maxOfOrNull { it.seq } ?: 0L)
            assertEquals(case.label(), expected["journalBytes"].asInt, bytes.size)
            assertEquals(case.label(), expected["journalSha256"].asString, WebdavJournalProtocol.sha256(bytes))
        }
        for (case in value.cases("parse")) {
            val result = runCatching { WebdavJournalProtocol.parseHead(case["text"].asString, case["deviceId"].asString) }
            if (case["error"].isTrue()) {
                assertTrue("${case.label()} should be rejected", result.isFailure)
                continue
            }
            val head = result.getOrThrow()
            val expected = case["expected"].asJsonObject
            assertEquals(expected["lastSeq"].asLong, head.lastSeq)
            assertEquals(expected["journalBytes"].asInt, head.journalBytes)
            assertEquals(expected["journalSha256"].asString, head.journalSha256)
            assertEquals(expected["stateEntries"].asInt, head.stateEntries)
            assertEquals(expected["historyEntries"].asInt, head.historyEntries)
        }
    }

    @Test fun `committed-view`() {
        val value = fixture("committed-view.json")
        val text = value["journal"].asString
        val journal = WebdavJournalProtocol.parseJournal(text)
        for (case in value.cases()) {
            val head = case["head"].takeUnless { it.isJsonNull }?.asJsonObject?.let {
                WebdavJournalHead(1, "fixture", it["lastSeq"].asLong, it["journalBytes"].asInt, it["journalSha256"].asString, "", 0, 0)
            }
            val expected = case["expected"].asJsonObject
            val plan = WebdavProtocolRules.planJournalRead(head?.lastSeq, case["known"].asLong)
            assertEquals(case.label(), expected["fetch"].asBoolean, plan.fetch)
            if (!plan.fetch) {
                assertEquals(0, expected.getAsJsonArray("seqs").size())
                continue
            }
            val view = WebdavProtocolRules.committedView(journal, text, head, plan.known)
            assertEquals(case.label(), expected.getAsJsonArray("seqs").longs(), view.seqs)
            assertEquals(case.label(), expected["reset"].asBoolean, plan.reset || view.reset)
            assertEquals(case.label(), expected["mismatch"].asBoolean, view.mismatch)
        }
    }

    @Test fun `pull-isolation`() {
        val value = fixture("pull-isolation.json")
        val device = value["deviceId"].asString
        for (case in value.cases()) {
            val expected = case["expected"].asJsonObject
            val headText = case["headText"].takeUnless { it.isJsonNull }?.asString
            val head = try { headText?.let { WebdavJournalProtocol.parseHead(it, device) } } catch (error: Exception) {
                assertEquals(case.label(), false, expected["fetch"].asBoolean)
                assertEquals(case.label(), "head-corrupt", expected["blocked"].asString)
                continue
            }
            val listed = case["listedBytes"].takeUnless { it.isJsonNull }?.asLong
            val plan = WebdavProtocolRules.planJournalReadSized(head, case["known"].asLong, listed)
            assertEquals(case.label(), expected["fetch"].asBoolean, plan.fetch)
            if (!plan.fetch) {
                assertTrue(case.label(), expected["blocked"].isJsonNull)
                assertEquals(0, expected.getAsJsonArray("seqs").size())
                assertEquals(case.label(), expected["reset"].asBoolean, plan.reset)
                continue
            }
            val text = case["journalText"].takeUnless { it.isJsonNull }?.asString
            val assessment = WebdavProtocolRules.assessJournal(text, head, plan.known)
            assertEquals(case.label(), expected["blocked"].takeUnless { it.isJsonNull }?.asString, assessment.blocked?.code)
            assertEquals(case.label(), expected.getAsJsonArray("seqs").longs(), assessment.seqs)
            assertEquals(case.label(), expected["reset"].asBoolean, plan.reset || assessment.reset)
        }
    }

    @Test fun `journal-rebuild`() {
        for (case in fixture("journal-rebuild.json").cases()) {
            val journal = case["journalText"].takeUnless { it.isJsonNull }?.asString
            val head = case["headText"].takeUnless { it.isJsonNull }?.asString
            assertEquals(case.label(), case["expectedFloor"].asLong, WebdavProtocolRules.rebuildFloor(journal, head))
        }
    }

    @Test fun `pull-order`() {
        val value = fixture("pull-order.json")
        fun rows(key: String) = value.getAsJsonArray(key).map { it.asJsonObject["deviceId"].asString to it.asJsonObject["seq"].asLong }
        assertEquals(rows("expected"), WebdavProtocolRules.pullOrder(rows("entries"), { it.first }, { it.second }))
    }

    @Test fun `propfind-listing`() {
        for (case in fixture("propfind-listing.json").cases()) {
            val names = parseWebdavHrefs(case["text"].asString).mapNotNull(::webdavHrefBasename)
            assertEquals(case.label(), case.getAsJsonArray("names").map { it.asString }, names)
        }
    }

    @Test fun `privacy-envelope`() {
        val value = fixture("privacy-envelope.json")
        val cipher = WebdavPrivacyCipher(value["secret"].asString)
        val decoder = Base64.getDecoder()
        for (case in value.cases()) {
            val context = "${case["type"].asString}:${case["id"].asString}:${case["op"].asString}:${case["ver"].asLong}"
            val plaintext = case["plaintext"].asString
            val envelope = cipher.encrypt(context, plaintext.toByteArray(Charsets.UTF_8), decoder.decode(case["salt"].asString), decoder.decode(case["iv"].asString))
            assertEquals(case.label(), case["envelope"], envelope)
            val decrypted = cipher.decrypt(context, case["envelope"].asJsonObject)
            assertArrayEquals(case.label(), plaintext.toByteArray(Charsets.UTF_8), decrypted)
        }
        for (case in value.cases("rejects")) {
            val rejecting = WebdavPrivacyCipher(case["secret"]?.asString ?: value["secret"].asString)
            val result = runCatching {
                rejecting.decryptPayload(case["type"].asString, case["id"].asString, case["envelope"].asJsonObject, case["op"].asString, case["ver"].asLong)
            }
            assertFalse("${case.label()} should be rejected", result.isSuccess)
        }
    }
}
