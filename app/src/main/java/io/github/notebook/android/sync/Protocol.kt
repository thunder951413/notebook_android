package io.github.notebook.android.sync

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonElement
import io.github.notebook.android.data.ReadingPositionEntity
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64

object SwiftDateCodec {
    private const val APPLE_EPOCH_OFFSET_SECONDS=978307200.0
    fun encode(milliseconds:Long)=milliseconds/1000.0-APPLE_EPOCH_OFFSET_SECONDS
    fun decode(seconds:Double)=((seconds+APPLE_EPOCH_OFFSET_SECONDS)*1000).toLong()
}

object RemoteIndexMerger {
    fun merge(remote:JsonObject?,localEntries:Collection<JsonObject>,localDeletions:Collection<JsonObject>):JsonObject {
        val entries=linkedMapOf<String,JsonObject>();val deleted=linkedMapOf<String,JsonObject>()
        remote?.get("entries")?.asJsonArray?.forEach{entries[it.asJsonObject["noteID"].asString]=it.asJsonObject.deepCopy()}
        remote?.get("deletedEntries")?.asJsonArray?.forEach{deleted[it.asJsonObject["noteID"].asString]=it.asJsonObject.deepCopy()}
        localEntries.forEach{e->val id=e["noteID"].asString;val old=entries[id];if(old==null||e["version"].asLong>=old["version"].asLong){entries[id]=e.deepCopy();deleted.remove(id)}}
        localDeletions.forEach{d->val id=d["noteID"].asString;val old=deleted[id];if(old==null||d["deletedAt"].asDouble>=old["deletedAt"].asDouble){deleted[id]=d.deepCopy();entries.remove(id)}}
        return JsonObject().apply{add("entries",JsonArray().apply{entries.values.forEach(::add)});add("deletedEntries",JsonArray().apply{deleted.values.forEach(::add)})}
    }
}

object ReadingPositionProtocol {
    const val SCHEMA_VERSION=1

    fun wins(candidate:ReadingPositionEntity,current:ReadingPositionEntity?):Boolean=
        current==null||candidate.updatedAt>current.updatedAt||
            (candidate.updatedAt==current.updatedAt&&candidate.deviceId>current.deviceId)

    fun merge(local:Collection<ReadingPositionEntity>,remote:Collection<ReadingPositionEntity>):List<ReadingPositionEntity>{
        val merged=local.associateByTo(linkedMapOf(),ReadingPositionEntity::noteId)
        remote.forEach{candidate->if(wins(candidate,merged[candidate.noteId]))merged[candidate.noteId]=candidate}
        return merged.values.sortedBy(ReadingPositionEntity::noteId)
    }

    fun encode(position:ReadingPositionEntity)=JsonObject().apply{
        addProperty("noteID",position.noteId)
        addProperty("anchorUTF16Offset",position.anchorUtf16Offset)
        addProperty("viewportOffsetFraction",position.viewportOffsetFraction.coerceIn(-1.0,1.0))
        addProperty("updatedAt",Instant.ofEpochMilli(position.updatedAt).toString())
        addProperty("deviceID",position.deviceId)
    }

    fun decode(value:JsonObject)=ReadingPositionEntity(
        noteId=value["noteID"].asString,
        anchorUtf16Offset=(value["anchorUTF16Offset"]?.asInt?:0).coerceAtLeast(0),
        viewportOffsetFraction=(value["viewportOffsetFraction"]?.asDouble?:0.0).coerceIn(-1.0,1.0),
        updatedAt=Instant.parse(value["updatedAt"].asString).toEpochMilli(),
        deviceId=value["deviceID"]?.asString.orEmpty()
    )
}

fun sshSha256Fingerprint(key:ByteArray):String="SHA256:"+Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(key))

object BlockDocumentCodec {
    private const val COMPACT_THRESHOLD=512
    private const val COMPACT_GROUP_LINES=256

    fun encodeMarkdown(markdown:String,idPrefix:String="android"):JsonArray=JsonArray().apply{
        val lines=markdown.split('\n')
        if(lines.size>COMPACT_THRESHOLD){
            lines.chunked(COMPACT_GROUP_LINES).forEachIndexed{index,group->
                add(JsonObject().apply{addProperty("id","$idPrefix-$index");addProperty("type","paragraph");add("text",encodeInline(group.joinToString("\n")))})
            }
            return@apply
        }
        lines.forEachIndexed{index,line->
            val heading=Regex("^(#{1,6})\\s+(.*)$").matchEntire(line);val check=Regex("^- \\[( |x|X)]\\s+(.*)$").matchEntire(line);val ordered=Regex("^(\\d+)\\.\\s+(.*)$").matchEntire(line);val bullet=Regex("^[-*+]\\s+(.*)$").matchEntire(line)
            val block=JsonObject();block.addProperty("id","$idPrefix-$index")
            val content=when{heading!=null->{block.addProperty("type","heading");block.addProperty("level",heading.groupValues[1].length);heading.groupValues[2]};check!=null->{block.addProperty("type","todo_item");block.addProperty("checked",check.groupValues[1].lowercase()=="x");check.groupValues[2]};ordered!=null->{block.addProperty("type","list_item");block.addProperty("listStyle","ordered");block.addProperty("order",ordered.groupValues[1].toInt());ordered.groupValues[2]};bullet!=null->{block.addProperty("type","list_item");block.addProperty("listStyle","unordered");bullet.groupValues[1]};else->{block.addProperty("type","paragraph");line}}
            block.add("text",encodeInline(content));add(block)
        }
    }
    fun decodeMarkdown(blocks:JsonArray):String=blocks.mapNotNull{raw->val b=raw.asJsonObject;val spans=b["text"]?.takeIf(JsonElement::isJsonArray)?.asJsonArray?:return@mapNotNull null;val text=spans.joinToString(""){rawSpan->val s=rawSpan.asJsonObject;var value=s["text"]?.asString.orEmpty();s["link"]?.takeUnless{it.isJsonNull}?.asString?.let{value="[$value]($it)"};if(s["italic"]?.asBoolean==true)value="_"+value+"_";if(s["bold"]?.asBoolean==true)value="**$value**";value};when(b["type"]?.asString){"heading"->"#".repeat(b["level"]?.asInt?:1)+" "+text;"todo_item"->"- [${if(b["checked"]?.asBoolean==true)"x" else " "}] $text";"list_item"->if(b["listStyle"]?.asString=="ordered")"${b["order"]?.asInt?:1}. $text" else "- $text";else->text}}.joinToString("\n")

    private fun encodeInline(text:String):JsonArray{val result=JsonArray();val regex=Regex("(\\*\\*[^*]+\\*\\*|_[^_]+_|\\[[^]]+]\\([^)]+\\))");var cursor=0;fun add(value:String,bold:Boolean=false,italic:Boolean=false,link:String?=null){result.add(JsonObject().apply{addProperty("text",value);addProperty("bold",bold);addProperty("italic",italic);addProperty("underline",false);addProperty("strikethrough",false);link?.let{addProperty("link",it)}})};regex.findAll(text).forEach{m->if(m.range.first>cursor)add(text.substring(cursor,m.range.first));val token=m.value;when{token.startsWith("**")->add(token.drop(2).dropLast(2),bold=true);token.startsWith("_")->add(token.drop(1).dropLast(1),italic=true);else->{val split=token.indexOf("](");add(token.substring(1,split),link=token.substring(split+2,token.length-1))}};cursor=m.range.last+1};if(cursor<text.length)add(text.substring(cursor));if(result.size()==0)add("");return result}
}
