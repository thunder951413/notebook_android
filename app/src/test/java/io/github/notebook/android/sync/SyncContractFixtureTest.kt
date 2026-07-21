package io.github.notebook.android.sync

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

class SyncContractFixtureTest {
    private val fixtureNames=listOf(
        "library-envelope.json",
        "note-envelope.json",
        "note-index.json",
        "reading-positions.json"
    )

    @Test fun `shared sync contract fixture set is unchanged`() {
        val combined=ByteArrayOutputStream()
        fixtureNames.forEach{name->combined.write(name.toByteArray());combined.write(0);combined.write(fixtureBytes(name))}
        assertEquals(
            "51e9cb5da4e074b75de3b9a22db7a26730e38459022296b775f2d3aba5c94ddc",
            sha256(combined.toByteArray())
        )
    }

    @Test fun `shared note envelope preserves all cross platform structures`() {
        val envelope=fixtureObject("note-envelope.json")
        val snapshot=envelope["currentSnapshot"].asJsonObject
        val metadata=snapshot["metadata"].asJsonObject
        val document=snapshot["document"].asJsonObject
        val blocks=document["blocks"].asJsonArray
        val assets=snapshot["assets"].asJsonArray

        assertEquals(envelope["noteID"].asString,metadata["id"].asString)
        assertEquals(envelope["currentVersion"].asLong,metadata["version"].asLong)
        assertEquals(metadata["blockCount"].asInt,blocks.size())
        assertEquals(metadata["assetCount"].asInt,assets.size())
        assertEquals(
            listOf("heading","paragraph","todo_item","table","attachment"),
            blocks.map{it.asJsonObject["type"].asString}
        )
        assertEquals("asset-fixture-1",assets[0].asJsonObject["reference"].asJsonObject["id"].asString)
        assertEquals(envelope["noteID"].asString,snapshot["todoSteps"].asJsonArray[0].asJsonObject["noteID"].asString)
        assertEquals(envelope["noteID"].asString,envelope["readingPosition"].asJsonObject["noteID"].asString)
        assertTrue(BlockDocumentCodec.decodeMarkdown(blocks).contains("Unicode 内容"))
        assertTrue(SwiftDateCodec.decode(metadata["updatedAt"].asDouble)>0)
    }

    @Test fun `shared library index and reading fixtures retain safety semantics`() {
        val library=fixtureObject("library-envelope.json")
        val encrypted=library["encryptedData"].asJsonObject
        val encryptedNote=encrypted["encryptedNoteIDs"].asJsonArray[0].asString
        val folder=encrypted["encryptedFolders"].asJsonArray[0].asJsonObject["id"].asString
        assertEquals(folder,encrypted["noteToFolderMapping"].asJsonObject[encryptedNote].asString)
        assertEquals(1,library["deletedFolders"].asJsonArray.size())
        assertEquals(1,library["deletedTags"].asJsonArray.size())

        val index=fixtureObject("note-index.json")
        val active=index["entries"].asJsonArray.map{it.asJsonObject["noteID"].asString}.toSet()
        val deleted=index["deletedEntries"].asJsonArray.map{it.asJsonObject["noteID"].asString}.toSet()
        assertTrue(active.intersect(deleted).isEmpty())
        assertEquals(64,index["entries"].asJsonArray[0].asJsonObject["contentHash"].asString.length)

        val positions=fixtureObject("reading-positions.json")
        assertEquals(ReadingPositionProtocol.SCHEMA_VERSION,positions["schemaVersion"].asInt)
        val decoded=ReadingPositionProtocol.decode(positions["positions"].asJsonArray[0].asJsonObject)
        assertEquals(321,decoded.anchorUtf16Offset)
        assertEquals(.25,decoded.viewportOffsetFraction,0.0001)
    }

    private fun fixtureObject(name:String):JsonObject=JsonParser.parseString(fixtureBytes(name).toString(Charsets.UTF_8)).asJsonObject
    private fun fixtureBytes(name:String)=checkNotNull(javaClass.classLoader?.getResourceAsStream("sync-contract/$name")){"Missing fixture $name"}.use{it.readBytes()}
    private fun sha256(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
}
