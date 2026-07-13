package io.github.notebook.android.sync

import com.google.gson.JsonObject
import org.junit.Assert.*
import org.junit.Test

class ProtocolTest {
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

    private fun entry(id:String,version:Long)=JsonObject().apply{addProperty("noteID",id);addProperty("version",version);addProperty("contentHash","");addProperty("historyCount",0)}
}
