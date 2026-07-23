package io.github.notebook.android.sync

import com.google.gson.JsonObject
import io.github.notebook.android.data.ReadingPositionEntity
import org.junit.Assert.*
import org.junit.Test

class ProtocolTest {
    @Test fun `compressed repository contract round trips utf8 and selects gzip path`() {
        val index=JsonObject().apply{add("repositoryFormat",JsonObject().apply{addProperty("version",2);addProperty("noteEncoding","gzip")})}
        val text="{\"title\":\"压缩同步\"}"
        assertTrue(CompressedRepositoryContract.enabled(index))
        assertTrue(CompressedRepositoryContract.notePath("/repo","note-id",index).endsWith(".json.gz"))
        assertEquals(text,CompressedRepositoryContract.decode(CompressedRepositoryContract.encode(text)))
        assertEquals("/repo/notes/note-id.json",CompressedRepositoryContract.notePath("/repo","note-id",null))
    }
    @Test fun `swift date codec round trips milliseconds`() {
        val values=listOf(978307200000L,0L,1_725_000_123_456L)
        values.forEach{assertEquals(it,SwiftDateCodec.decode(SwiftDateCodec.encode(it)))}
    }

    @Test fun `index merge retains unrelated remote entries`() {
        val remote=JsonObject().apply{add("entries",com.google.gson.JsonArray().apply{add(entry("remote",4))});add("deletedEntries",com.google.gson.JsonArray())}
        val merged=RemoteIndexMerger.merge(remote,listOf(entry("local",2)),emptyList())
        assertEquals(setOf("remote","local"),merged["entries"].asJsonArray.map{it.asJsonObject["noteID"].asString}.toSet())
    }

    @Test fun `new deletion wins over entry`() {
        val remote=JsonObject().apply{add("entries",com.google.gson.JsonArray().apply{add(entry("same",4))});add("deletedEntries",com.google.gson.JsonArray())}
        val deletion=JsonObject().apply{addProperty("noteID","same");addProperty("deletedAt",99.0);addProperty("deletedByDeviceID","phone")}
        val merged=RemoteIndexMerger.merge(remote,emptyList(),listOf(deletion))
        assertTrue(merged["entries"].asJsonArray.isEmpty);assertEquals("same",merged["deletedEntries"].asJsonArray[0].asJsonObject["noteID"].asString)
    }

    @Test fun `ssh fingerprint uses OpenSSH format`() {
        assertEquals("SHA256:LPJNul+wow4m6DsqxbninhsWHlwfp0JecwQzYpOLmCQ",sshSha256Fingerprint("hello".toByteArray()))
    }

    @Test fun `remote repository path expands only leading tilde and rejects traversal`() {
        assertEquals("/home/user/notebook backup",resolveRemoteRepositoryPath("~/notebook backup/","/home/user"))
        assertEquals("/srv/notebook",resolveRemoteRepositoryPath("/srv/notebook","/home/user"))
        assertEquals("/home/user/notebook",resolveRemoteRepositoryPath("notebook","/home/user"))
        assertThrows(IllegalArgumentException::class.java){resolveRemoteRepositoryPath("~/../escape","/home/user")}
        assertThrows(IllegalArgumentException::class.java){resolveRemoteRepositoryPath("/","/home/user")}
    }

    @Test fun `remote write lock contract matches mac client`() {
        assertEquals("/srv/notebook/notes/.index-write.lock",RemoteRepositoryLockContract.lockPath("/srv/notebook"))
        assertEquals("owner",RemoteRepositoryLockContract.OWNER_FILE_NAME)
        assertEquals(30,RemoteRepositoryLockContract.MAXIMUM_ATTEMPTS)
        assertEquals(1_800L,RemoteRepositoryLockContract.STALE_AFTER_SECONDS)
    }

    @Test fun `android edit preserves remote history and records previous snapshot`() {
        val previousSnapshot=JsonObject().apply{add("metadata",JsonObject().apply{addProperty("title","旧标题");addProperty("updatedAt",42.0);addProperty("version",7)});add("document",JsonObject())}
        val existing=JsonObject().apply{
            addProperty("deviceID","mac-device");addProperty("currentVersion",7);add("currentSnapshot",previousSnapshot)
            add("history",com.google.gson.JsonArray().apply{add(JsonObject().apply{addProperty("id","existing");addProperty("version",6);addProperty("contentHash","old")})})
        }
        val history=LegacyHistoryContract.preserveAndAppend(existing,"note-id",8,"android-device",100.0){"hash-${it.length}"}
        assertEquals(2,history.size())
        assertEquals("existing",history[0].asJsonObject["id"].asString)
        assertEquals(7,history[1].asJsonObject["version"].asInt)
        assertEquals("旧标题",history[1].asJsonObject["title"].asString)
        assertEquals("mac-device",history[1].asJsonObject["authorDeviceID"].asString)
        assertEquals(previousSnapshot,history[1].asJsonObject["snapshot"].asJsonObject)
    }

    @Test fun `android keeps all remote history without duplicating current remote snapshot`() {
        val snapshot=JsonObject().apply{add("metadata",JsonObject().apply{addProperty("title","标题");addProperty("updatedAt",42.0);addProperty("version",7)});add("document",JsonObject())}
        val existing=JsonObject().apply{
            addProperty("deviceID","mac");addProperty("currentVersion",7);add("currentSnapshot",snapshot)
            add("history",com.google.gson.JsonArray().apply{repeat(100){index->add(JsonObject().apply{addProperty("id","history-$index");addProperty("version",index);addProperty("contentHash","hash-$index")})};add(JsonObject().apply{addProperty("id","same");addProperty("version",7);addProperty("contentHash","snapshot-hash")})})
        }
        val history=LegacyHistoryContract.preserveAndAppend(existing,"note-id",8,"android",100.0){"snapshot-hash"}
        assertEquals(101,history.size())
        assertEquals("same",history.last().asJsonObject["id"].asString)
        assertEquals(1,history.count{it.asJsonObject["contentHash"].asString=="snapshot-hash"})
    }

    @Test fun `markdown semantic blocks round trip`() {
        val source="# 标题\n普通段落\n- 项目\n2. 第二项\n- [x] 完成\n- [ ] 待办"
        val blocks=BlockDocumentCodec.encodeMarkdown(source)
        assertEquals(listOf("heading","paragraph","list_item","list_item","todo_item","todo_item"),blocks.map{it.asJsonObject["type"].asString})
        assertEquals(source,BlockDocumentCodec.decodeMarkdown(blocks))
    }

    @Test fun `non textual attachment blocks do not create blank content`() {
        val blocks=BlockDocumentCodec.encodeMarkdown("正文")
        blocks.add(JsonObject().apply{addProperty("type","attachment");addProperty("id","asset")})
        assertEquals("正文",BlockDocumentCodec.decodeMarkdown(blocks))
    }

    @Test fun `inline bold italic and links become rich spans`() {
        val source="普通 **粗体** _斜体_ [链接](https://example.com)"
        val blocks=BlockDocumentCodec.encodeMarkdown(source)
        val spans=blocks[0].asJsonObject["text"].asJsonArray
        assertTrue(spans.any{it.asJsonObject["bold"]?.asBoolean==true})
        assertTrue(spans.any{it.asJsonObject["italic"]?.asBoolean==true})
        assertTrue(spans.any{it.asJsonObject["link"]?.asString=="https://example.com"})
        assertEquals(source,BlockDocumentCodec.decodeMarkdown(blocks))
    }

    @Test fun `large markdown is compacted without changing content`() {
        val source=(0 until 1_200).joinToString("\n"){"第 $it 行"}
        val first=BlockDocumentCodec.encodeMarkdown(source,"note")
        val second=BlockDocumentCodec.encodeMarkdown(source,"note")
        assertTrue(first.size()<10)
        assertEquals(source,BlockDocumentCodec.decodeMarkdown(first))
        assertEquals(first.map{it.asJsonObject["id"].asString},second.map{it.asJsonObject["id"].asString})
    }

    @Test fun `reading position uses mac ISO dates and deterministic last writer wins`() {
        val older=ReadingPositionEntity("note",10,0.0,1_700_000_000_000,"phone-a")
        val tieWinner=older.copy(anchorUtf16Offset=30,deviceId="phone-z")
        val encoded=ReadingPositionProtocol.encode(older)
        assertTrue(encoded["updatedAt"].asString.endsWith("Z"))
        assertEquals(older,ReadingPositionProtocol.decode(encoded))
        assertEquals(tieWinner,ReadingPositionProtocol.merge(listOf(older),listOf(tieWinner)).single())
    }

    @Test fun `tiptap adapter preserves common rich editor structure`() {
        val source="# 标题\n普通段落\n"
        val encoded=TipTapCodec.encode(source)
        assertEquals("doc",encoded["type"].asString)
        assertEquals("heading",encoded["content"].asJsonArray[0].asJsonObject["type"].asString)
        assertEquals(source.trimEnd(),TipTapCodec.decode(encoded))
        val rich=com.google.gson.JsonParser.parseString("""{"type":"doc","content":[{"type":"paragraph","content":[{"type":"text","text":"Notebook","marks":[{"type":"bold"}]},{"type":"text","text":" Next"}]}]}""")
        assertEquals("**Notebook** Next",TipTapCodec.decode(rich))
        val reparsed=TipTapCodec.encode("**Notebook** [Next](https://example.com)")
        val spans=reparsed["content"].asJsonArray[0].asJsonObject["content"].asJsonArray
        assertEquals("bold",spans[0].asJsonObject["marks"].asJsonArray[0].asJsonObject["type"].asString)
        assertEquals("https://example.com",spans.last().asJsonObject["marks"].asJsonArray[0].asJsonObject["attrs"].asJsonObject["href"].asString)
        assertEquals("Notebook Next",TipTapCodec.plainText("# Notebook\n\n**Next**"))
    }

    private fun entry(id:String,version:Long)=JsonObject().apply{addProperty("noteID",id);addProperty("version",version);addProperty("contentHash","");addProperty("historyCount",0)}
}
