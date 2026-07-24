package io.github.notebook.android.sync

import android.content.Context
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.github.notebook.android.BuildConfig
import io.github.notebook.android.data.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit

data class ApiSyncSettings(
    val baseUrl:String="",
    val workspaceId:String="00000000-0000-4000-8000-000000000001",
    val token:String=""
)

/** Versioned HTTP synchronizer shared with Notebook Next web/desktop. */
class ApiSyncClient(private val context:Context,private val dao:NotebookDao) {
    private val gson:Gson=GsonBuilder().disableHtmlEscaping().create()
    private val http=OkHttpClient.Builder().connectTimeout(15,TimeUnit.SECONDS).readTimeout(60,TimeUnit.SECONDS).writeTimeout(60,TimeUnit.SECONDS).build()

    suspend fun sync(settings:ApiSyncSettings) {
        val normalized=settings.copy(baseUrl=settings.baseUrl.trim().trimEnd('/'),workspaceId=settings.workspaceId.trim(),token=settings.token.trim())
        validate(normalized)
        // On a freshly connected Android install, receive the web migration first.
        // Clean records from the legacy SSH database may then be safely replaced.
        if((dao.apiCursor(normalized.workspaceId)?:0L)==0L) pullAll(normalized)
        queueDirtyRecords(normalized.workspaceId)
        push(normalized)
        pullAll(normalized)
    }

    private fun validate(s:ApiSyncSettings) {
        require(s.baseUrl.isNotBlank()){"请填写 Notebook Next 服务地址"}
        require(s.workspaceId.isNotBlank()){"工作区 ID 不能为空"}
        val uri=runCatching{URI(s.baseUrl)}.getOrElse{throw IllegalArgumentException("服务地址格式不正确")}
        require(uri.scheme=="https"||(BuildConfig.DEBUG&&uri.scheme=="http")){"正式版同步服务必须使用 HTTPS"}
        require(!uri.host.isNullOrBlank()){"服务地址缺少主机名"}
    }

    private fun versionKey(workspaceId:String,type:String,id:String)="$workspaceId:$type:$id"
    private suspend fun enqueue(workspaceId:String,type:String,id:String,operation:String,payload:JsonObject) {
        val existing=dao.apiOutboxItem(type,id)
        val expected=existing?.expectedVersion?:dao.apiVersion(versionKey(workspaceId,type,id))?.version?:0L
        dao.deleteApiOutbox(type,id)
        dao.putApiOutbox(ApiSyncOutboxEntity(UUID.randomUUID().toString(),workspaceId,type,id,expected,operation,gson.toJson(payload)))
    }

    suspend fun queueNote(workspaceId:String,note:NoteEntity) {
        val page=dao.apiPage(note.id)?.payloadJson?.let{runCatching{JsonParser.parseString(it).asJsonObject.deepCopy()}.getOrNull()}?:JsonObject()
        page.apply{
            addProperty("id",note.id);addProperty("workspaceId",workspaceId)
            if(note.folderId!=null)addProperty("sectionId",note.folderId)else remove("sectionId")
            addProperty("kind",if(note.itemType=="todo")"task" else string("kind","document"))
            addProperty("title",note.title);addProperty("preview",note.previewText)
            if(!has("favorite"))addProperty("favorite",false);addProperty("createdAt",iso(note.createdAt));addProperty("updatedAt",iso(note.updatedAt))
            if(note.deletedAt!=null)addProperty("deletedAt",iso(note.deletedAt))else remove("deletedAt");if(note.reminderAt!=null)addProperty("reminderAt",iso(note.reminderAt))else remove("reminderAt")
            if(note.recurrence!="none"&&note.recurrence.isNotBlank())addProperty("recurrenceRule",note.recurrence)else remove("recurrenceRule")
            if(note.dueAt!=null)addProperty("dueAt",iso(note.dueAt))else remove("dueAt");if(note.completedAt!=null)addProperty("completedAt",iso(note.completedAt))else remove("completedAt")
            addProperty("important",note.important);addProperty("syncStatus","pending");addProperty("legacyVersion",note.version)
        }
        dao.putApiPage(ApiPageEntity(note.id,gson.toJson(page),note.updatedAt))
        enqueue(workspaceId,"page",note.id,"upsert",page)
        val preserved=dao.apiDocument(note.id)?.tiptapJson?.let{runCatching{JsonParser.parseString(it)}.getOrNull()}
        val document=preserved?.takeIf{TipTapCodec.decode(it)==note.body}?:TipTapCodec.encode(note.body)
        dao.putApiDocument(ApiDocumentEntity(note.id,gson.toJson(document),1,note.updatedAt))
        enqueue(workspaceId,"document",note.id,"upsert",JsonObject().apply{addProperty("pageId",note.id);addProperty("schemaVersion",1);add("tiptapJson",document);addProperty("updatedAt",iso(note.updatedAt))})
        val tagIds=note.tagIds.split(',').map(String::trim).filter(String::isNotBlank).toSet()
        tagIds.forEach{tagId->enqueue(workspaceId,"page_tag","${note.id}:$tagId","upsert",JsonObject().apply{addProperty("pageId",note.id);addProperty("tagId",tagId)})}
        dao.steps(note.id).forEach{step->enqueue(workspaceId,"task_step",step.id,"upsert",stepPayload(step))}
        dao.assets(note.id).filter{it.dirty}.forEach{asset->enqueue(workspaceId,"asset",asset.id,"upsert",assetPayload(asset))}
    }

    suspend fun queueFolder(workspaceId:String,folder:FolderEntity) = enqueue(workspaceId,"section",folder.id,"upsert",JsonObject().apply{
        addProperty("id",folder.id);addProperty("notebookId",folder.notebookId);folder.parentSectionId?.let{addProperty("parentSectionId",it)}
        addProperty("name",folder.name);folder.color?.let{addProperty("color",it)};addProperty("sortOrder",folder.sortOrder)
        addProperty("createdAt",iso(folder.updatedAt));addProperty("updatedAt",iso(folder.updatedAt))
    })
    suspend fun queueTag(workspaceId:String,tag:TagEntity) = enqueue(workspaceId,"tag",tag.id,"upsert",JsonObject().apply{
        addProperty("id",tag.id);addProperty("workspaceId",workspaceId);addProperty("name",tag.name);addProperty("color",tag.color);addProperty("createdAt",iso(tag.updatedAt));addProperty("updatedAt",iso(tag.updatedAt))
    })
    suspend fun queueStep(workspaceId:String,step:TodoStepEntity)=enqueue(workspaceId,"task_step",step.id,"upsert",stepPayload(step))
    suspend fun queueAsset(workspaceId:String,asset:AssetEntity)=enqueue(workspaceId,"asset",asset.id,"upsert",assetPayload(asset))
    suspend fun queueDelete(workspaceId:String,type:String,id:String)=enqueue(workspaceId,type,id,"delete",JsonObject())
    suspend fun queueReadingPosition(workspaceId:String,p:ReadingPositionEntity)=enqueue(workspaceId,"reading_position","${p.noteId}:${p.deviceId}","upsert",JsonObject().apply{
        addProperty("id","${p.noteId}:${p.deviceId}");addProperty("pageId",p.noteId);addProperty("anchorUtf16Offset",p.anchorUtf16Offset);addProperty("viewportOffsetFraction",p.viewportOffsetFraction);addProperty("updatedAt",iso(p.updatedAt));addProperty("deviceId",p.deviceId)
    })

    suspend fun acceptConflict(workspaceId:String,entityType:String,entityId:String,payload:JsonObject) {
        when(entityType){
            "page"->applyPage(entityId,payload,dao.apiVersion(versionKey(workspaceId,entityType,entityId))?.version?:0)
            "document"->applyDocument(entityId,payload)
            "task_step"->dao.putStep(TodoStepEntity(entityId,payload.string("pageId"),payload.string("text"),payload.boolean("checked"),payload.integer("sortOrder"),payload.millis("createdAt")))
        }
        pageIdFor(entityType,entityId,payload)?.let{pageId->dao.get(pageId)?.let{dao.put(it.copy(dirty=false,conflict=false,conflictSnapshotJson=null))}}
    }

    private suspend fun queueDirtyRecords(workspaceId:String) {
        dao.dirtyNotes().filterNot{it.conflict}.forEach{queueNote(workspaceId,it)}
        dao.dirtyAssets().forEach{asset->if(dao.apiOutboxItem("asset",asset.id)==null)enqueue(workspaceId,"asset",asset.id,"upsert",assetPayload(asset))}
    }

    private suspend fun push(s:ApiSyncSettings) {
        val outgoing=dao.apiOutbox(s.workspaceId,200)
        if(outgoing.isEmpty())return
        outgoing.filter{it.entityType=="asset"}.forEach{item->
            val url=assetUrl(s,item.entityId)
            if(item.operation=="delete") execute(s,Request.Builder().url(url).delete().build()).close()
            else {
                val asset=dao.getAsset(item.entityId)?:error("附件 ${item.entityId} 的元数据不存在")
                val file=asset.localPath?.let(::File)?.takeIf(File::isFile)?:error("附件 ${asset.filename} 缺少本地文件，已阻止不完整同步")
                execute(s,Request.Builder().url(url).put(file.readBytes().toRequestBody(asset.mimeType.toMediaType())).build()).close()
            }
        }
        val requestJson=JsonObject().apply{addProperty("workspace_id",s.workspaceId);add("changes",JsonArray().apply{outgoing.forEach{item->add(JsonObject().apply{
            addProperty("operation_id",item.id);addProperty("entity_type",item.entityType);addProperty("entity_id",item.entityId);addProperty("expected_version",item.expectedVersion);addProperty("operation",item.operation);add("payload",JsonParser.parseString(item.payloadJson))
        })}})}
        val response=execute(s,Request.Builder().url("${s.baseUrl}/v1/sync/push").post(gson.toJson(requestJson).toRequestBody(JSON)).build())
        val root=response.use{JsonParser.parseString(it.body?.string().orEmpty()).asJsonObject}
        root["applied"]?.asJsonArray?.forEach{raw->val item=raw.asJsonObject;val operationId=item.string("operation_id");val type=item.string("entity_type");val id=item.string("entity_id");val version=item["version"].asLong;dao.deleteApiOutboxById(operationId);dao.putApiVersion(ApiSyncVersionEntity(versionKey(s.workspaceId,type,id),version));if(type=="page")dao.get(id)?.let{dao.put(it.copy(dirty=false,lastSyncedVersion=version))};if(type=="asset")dao.getAsset(id)?.let{dao.putAssets(listOf(it.copy(dirty=false)))}}
        root["conflicts"]?.asJsonArray?.forEach{raw->val item=raw.asJsonObject;val type=item.string("entity_type");val id=item.string("entity_id");val currentVersion=item["current_version"].asLong;dao.deleteApiOutboxById(item.string("operation_id"));dao.putApiVersion(ApiSyncVersionEntity(versionKey(s.workspaceId,type,id),currentVersion));pageIdFor(type,id,item["current_payload"]?.asJsonObject)?.let{pageId->recordConflict(pageId,type,item["current_payload"]?.asJsonObject?:JsonObject())}}
    }

    private suspend fun pullAll(s:ApiSyncSettings) {
        var cursor=dao.apiCursor(s.workspaceId)?:0L
        do {
            val url="${s.baseUrl}/v1/sync/pull?workspace_id=${encoded(s.workspaceId)}&cursor=$cursor&limit=200"
            val root=execute(s,Request.Builder().url(url).get().build()).use{JsonParser.parseString(it.body?.string().orEmpty()).asJsonObject}
            root["changes"].asJsonArray.forEach{applyChange(s,it.asJsonObject)}
            cursor=root["cursor"].asLong;dao.putApiCursor(ApiSyncCursorEntity(s.workspaceId,cursor))
            val more=root["has_more"].asBoolean
        } while(more)
    }

    private suspend fun applyChange(s:ApiSyncSettings,change:JsonObject) {
        val type=change.string("entity_type");val id=change.string("entity_id");val version=change["version"].asLong;val operation=change.string("operation");val payload=change["payload"].asJsonObject
        val pending=dao.apiOutboxItem(type,id);val affectedPage=pageIdFor(type,id,payload);val locallyDirty=affectedPage?.let{dao.get(it)?.dirty}==true
        if((pending!=null&&version>pending.expectedVersion)||locallyDirty){
            affectedPage?.let{recordConflict(it,type,payload)}
            dao.putApiVersion(ApiSyncVersionEntity(versionKey(s.workspaceId,type,id),version));return
        }
        if(operation=="delete") applyDelete(type,id,payload) else when(type){
            "notebook"->dao.putApiNotebook(ApiNotebookEntity(id,payload.string("workspaceId",s.workspaceId),payload.string("name"),payload.optionalString("emoji"),payload.optionalString("color"),payload.integer("sortOrder"),payload.millis("createdAt"),payload.millis("updatedAt"),payload.optionalMillis("deletedAt")))
            "section"->dao.putFolder(FolderEntity(id,payload.string("name"),payload.integer("sortOrder"),"noteFolder",payload.millis("updatedAt"),payload.string("notebookId","personal"),payload.optionalString("parentSectionId"),payload.optionalString("color")))
            "tag"->dao.putTag(TagEntity(id,payload.string("name"),payload.string("color","gray"),payload.millis("updatedAt")))
            "page"->applyPage(id,payload,version)
            "document"->applyDocument(id,payload)
            "page_tag"->applyPageTag(payload,true)
            "task_step"->dao.putStep(TodoStepEntity(id,payload.string("pageId"),payload.string("text"),payload.boolean("checked"),payload.integer("sortOrder"),payload.millis("createdAt")))
            "asset"->applyAsset(s,id,payload)
            "reading_position"->dao.putReadingPosition(ReadingPositionEntity(payload.string("pageId"),payload.integer("anchorUtf16Offset"),payload.double("viewportOffsetFraction"),payload.millis("updatedAt"),payload.string("deviceId")))
        }
        dao.putApiVersion(ApiSyncVersionEntity(versionKey(s.workspaceId,type,id),version))
    }

    private suspend fun applyPage(id:String,p:JsonObject,serverVersion:Long) {
        val local=dao.get(id);val folderId=p.optionalString("sectionId");val folderName=folderId?.let{dao.getFolder(it)?.name}?:"未分类"
        val note=NoteEntity(id=id,title=p.string("title"),body=local?.body.orEmpty(),previewText=p.string("preview"),createdAt=p.millis("createdAt"),updatedAt=p.millis("updatedAt"),folderId=folderId,folderName=folderName,reminderAt=p.optionalMillis("reminderAt"),recurrence=p.string("recurrenceRule","none"),version=p["legacyVersion"]?.asLong?:local?.version?:1,tagIds=local?.tagIds.orEmpty(),deletedAt=p.optionalMillis("deletedAt"),itemType=if(p.string("kind","document")=="task")"todo" else "note",dueAt=p.optionalMillis("dueAt"),completedAt=p.optionalMillis("completedAt"),important=p.boolean("important"),viewMode=local?.viewMode?:"preview",dirty=false,conflict=false,snapshotJson=local?.snapshotJson,conflictSnapshotJson=null,lastSyncedVersion=serverVersion)
        dao.put(note);dao.putApiPage(ApiPageEntity(id,gson.toJson(p),note.updatedAt))
    }

    private suspend fun applyDocument(id:String,p:JsonObject) {
        val json=p["tiptapJson"]?:JsonObject().apply{addProperty("type","doc");add("content",JsonArray())}
        val updated=p.optionalMillis("updatedAt")?:System.currentTimeMillis();dao.putApiDocument(ApiDocumentEntity(id,gson.toJson(json),p.integer("schemaVersion",1),updated))
        dao.get(id)?.let{note->val markdown=TipTapCodec.decode(json);dao.put(note.copy(body=markdown,previewText=TipTapCodec.plainText(markdown).take(240),updatedAt=maxOf(note.updatedAt,updated),dirty=false))}
    }

    private suspend fun applyPageTag(p:JsonObject,add:Boolean) {val pageId=p.string("pageId");val tagId=p.string("tagId");dao.get(pageId)?.let{note->val ids=note.tagIds.split(',').filter(String::isNotBlank).toMutableSet();if(add)ids+=tagId else ids-=tagId;dao.put(note.copy(tagIds=ids.joinToString(",")))}}
    private suspend fun applyAsset(s:ApiSyncSettings,id:String,p:JsonObject) {
        val response=execute(s,Request.Builder().url(assetUrl(s,id)).get().build());val bytes=response.use{it.body?.bytes()?:error("附件 $id 下载为空")};val filename=p.string("filename","attachment").replace(Regex("[^A-Za-z0-9._\\-\\u4e00-\\u9fff]"),"_");val relative="next/$id/$filename";val target=File(context.filesDir,"attachments/$relative");target.parentFile?.mkdirs();val temp=File(target.parentFile,"${target.name}.download");temp.writeBytes(bytes);if(!temp.renameTo(target)){temp.copyTo(target,true);temp.delete()};dao.putAssets(listOf(AssetEntity(id,p.string("pageId"),p.string("kind","file"),filename,p.string("mimeType","application/octet-stream"),relative,target.absolutePath,p.optionalString("checksum")?:sha(bytes),bytes.size.toLong(),false)))
    }

    private suspend fun recordConflict(pageId:String,type:String,payload:JsonObject){dao.get(pageId)?.let{note->
        val wrapper=note.conflictSnapshotJson?.let{runCatching{JsonParser.parseString(it).asJsonObject}.getOrNull()}?.takeIf{it.has("apiConflicts")}?:JsonObject().apply{add("apiConflicts",JsonObject())}
        wrapper["apiConflicts"].asJsonObject.add(type,payload.deepCopy());dao.put(note.copy(conflict=true,conflictSnapshotJson=gson.toJson(wrapper)))
    }}

    private suspend fun applyDelete(type:String,id:String,p:JsonObject){when(type){"notebook"->dao.deleteApiNotebook(id);"section"->dao.deleteFolder(id);"tag"->dao.deleteTag(id);"page"->{dao.deleteApiPage(id);dao.deleteNotePermanently(id)};"document"->dao.deleteApiDocument(id);"task_step"->dao.deleteStep(id);"asset"->dao.deleteAsset(id);"page_tag"->applyPageTag(if(p.size()>0)p else JsonObject().apply{val parts=id.split(':',limit=2);addProperty("pageId",parts.firstOrNull().orEmpty());addProperty("tagId",parts.getOrNull(1).orEmpty())},false);"reading_position"->p.optionalString("pageId")?.let{dao.deleteReadingPosition(it)}}}

    private fun stepPayload(s:TodoStepEntity)=JsonObject().apply{addProperty("id",s.id);addProperty("pageId",s.noteId);addProperty("text",s.text);addProperty("checked",s.checked);addProperty("sortOrder",s.sortOrder);addProperty("createdAt",iso(s.createdAt))}
    private fun assetPayload(a:AssetEntity)=JsonObject().apply{addProperty("id",a.id);addProperty("pageId",a.noteId);addProperty("kind",a.kind);addProperty("filename",a.filename);addProperty("mimeType",a.mimeType);addProperty("byteSize",a.size);addProperty("checksum",a.contentHash);addProperty("createdAt",iso(File(a.localPath?:"").takeIf(File::exists)?.lastModified()?:System.currentTimeMillis()))}
    private fun pageIdFor(type:String,id:String,p:JsonObject?)=when(type){"page","document"->id;"task_step","asset","page_tag","reading_position"->p?.optionalString("pageId")?:id.substringBefore(':');else->null}
    private fun execute(s:ApiSyncSettings,request:Request)=http.newCall(request.newBuilder().apply{if(s.token.isNotBlank())header("Authorization","Bearer ${s.token}")}.build()).execute().also{if(!it.isSuccessful){val detail=it.body?.string().orEmpty().take(500);it.close();throw IllegalStateException("同步服务返回 ${it.code}${if(detail.isBlank())"" else "：$detail"}")}}
    private fun assetUrl(s:ApiSyncSettings,id:String)="${s.baseUrl}/v1/sync/assets/${encoded(id)}?workspace_id=${encoded(s.workspaceId)}"
    companion object {private val JSON="application/json; charset=utf-8".toMediaType();private fun encoded(v:String)=URLEncoder.encode(v,StandardCharsets.UTF_8.name());private fun iso(ms:Long)=Instant.ofEpochMilli(ms).toString();private fun sha(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}}
}

/** Adapter between the web editor's TipTap JSON and the existing native Markdown surface. */
object TipTapCodec {
    private val parser by lazy{org.commonmark.parser.Parser.builder().build()}
    private val textRenderer by lazy{org.commonmark.renderer.text.TextContentRenderer.builder().build()}
    fun plainText(markdown:String)=textRenderer.render(parser.parse(markdown)).trim().lineSequence().take(3).joinToString(" ").replace(Regex("\\s+")," ")
    fun encode(markdown:String)=JsonObject().apply{
        addProperty("type","doc")
        add("content",JsonArray().apply{children(parser.parse(markdown)).forEach{add(block(it))}})
    }
    fun decode(root:JsonElement):String {val content=root.takeIf{it.isJsonObject}?.asJsonObject?.get("content")?.takeIf{it.isJsonArray}?.asJsonArray?:return "";return content.joinToString("\n"){render(it,0)}.trimEnd()}
    private fun block(node:org.commonmark.node.Node):JsonObject=when(node){
        is org.commonmark.node.Heading->container("heading",node).apply{add("attrs",JsonObject().apply{addProperty("level",node.level)})}
        is org.commonmark.node.Paragraph->container("paragraph",node)
        is org.commonmark.node.BulletList->containerBlocks("bulletList",node)
        is org.commonmark.node.OrderedList->containerBlocks("orderedList",node).apply{add("attrs",JsonObject().apply{addProperty("start",node.startNumber)})}
        is org.commonmark.node.ListItem->containerBlocks("listItem",node)
        is org.commonmark.node.BlockQuote->containerBlocks("blockquote",node)
        is org.commonmark.node.FencedCodeBlock->JsonObject().apply{addProperty("type","codeBlock");node.info?.takeIf(String::isNotBlank)?.let{add("attrs",JsonObject().apply{addProperty("language",it)})};addTextNode(node.literal.trimEnd('\n'))}
        is org.commonmark.node.IndentedCodeBlock->JsonObject().apply{addProperty("type","codeBlock");addTextNode(node.literal.trimEnd('\n'))}
        is org.commonmark.node.ThematicBreak->JsonObject().apply{addProperty("type","horizontalRule")}
        else->containerBlocks("paragraph",node)
    }
    private fun container(type:String,node:org.commonmark.node.Node)=JsonObject().apply{addProperty("type",type);val values=inline(children(node),emptyList());if(values.isNotEmpty())add("content",JsonArray().apply{values.forEach(::add)})}
    private fun containerBlocks(type:String,node:org.commonmark.node.Node)=JsonObject().apply{addProperty("type",type);add("content",JsonArray().apply{children(node).forEach{add(block(it))}})}
    private fun inline(nodes:List<org.commonmark.node.Node>,marks:List<JsonObject>):List<JsonObject> = nodes.flatMap{node->when(node){
        is org.commonmark.node.Text->listOf(textNode(node.literal,marks))
        is org.commonmark.node.Code->listOf(textNode(node.literal,marks+mark("code")))
        is org.commonmark.node.StrongEmphasis->inline(children(node),marks+mark("bold"))
        is org.commonmark.node.Emphasis->inline(children(node),marks+mark("italic"))
        is org.commonmark.node.Link->inline(children(node),marks+mark("link",JsonObject().apply{addProperty("href",node.destination);node.title?.takeIf(String::isNotBlank)?.let{addProperty("title",it)}}))
        is org.commonmark.node.SoftLineBreak->listOf(textNode("\n",marks))
        is org.commonmark.node.HardLineBreak->listOf(JsonObject().apply{addProperty("type","hardBreak")})
        is org.commonmark.node.Image->listOf(textNode(node.title?.ifBlank{node.destination}?:node.destination,marks))
        else->inline(children(node),marks)
    }}
    private fun textNode(value:String,marks:List<JsonObject>)=JsonObject().apply{addProperty("type","text");addProperty("text",value);if(marks.isNotEmpty())add("marks",JsonArray().apply{marks.forEach{add(it.deepCopy())}})}
    private fun mark(type:String,attrs:JsonObject?=null)=JsonObject().apply{addProperty("type",type);attrs?.let{add("attrs",it)}}
    private fun children(node:org.commonmark.node.Node)=buildList{var child=node.firstChild;while(child!=null){add(child);child=child.next}}
    private fun render(raw:JsonElement,depth:Int):String {if(!raw.isJsonObject)return "";val n=raw.asJsonObject;val type=n["type"]?.asString.orEmpty();val children=n["content"]?.takeIf{it.isJsonArray}?.asJsonArray;val inline=children?.joinToString(""){child->if(child.asJsonObject["type"]?.asString=="text")marked(child.asJsonObject) else render(child,depth+1)}.orEmpty();return when(type){"text"->marked(n);"heading"->"#".repeat(n["attrs"]?.asJsonObject?.get("level")?.asInt?:1)+" "+inline;"paragraph"->inline;"hardBreak"->"\n";"codeBlock"->"```\n$inline\n```";"blockquote"->children?.joinToString("\n"){"> "+render(it,depth+1)}.orEmpty();"bulletList"->children?.joinToString("\n"){"  ".repeat(depth)+"- "+render(it,depth+1)}.orEmpty();"orderedList"->children?.mapIndexed{i,e->"  ".repeat(depth)+"${i+1}. "+render(e,depth+1)}?.joinToString("\n").orEmpty();"taskList"->children?.joinToString("\n"){render(it,depth+1)}.orEmpty();"taskItem"->"- [${if(n["attrs"]?.asJsonObject?.get("checked")?.asBoolean==true)"x" else " "}] "+inline;"listItem"->inline;else->inline}}
    private fun marked(n:JsonObject):String {var text=n["text"]?.asString.orEmpty();n["marks"]?.takeIf{it.isJsonArray}?.asJsonArray?.forEach{m->when(m.asJsonObject["type"]?.asString){"bold"->text="**$text**";"italic"->text="_${text}_";"strike"->text="~~$text~~";"code"->text="`$text`";"link"->text="[$text](${m.asJsonObject["attrs"]?.asJsonObject?.get("href")?.asString.orEmpty()})"}};return text}
    private fun JsonObject.addTextNode(text:String){if(text.isNotEmpty())add("content",JsonArray().apply{add(textNode(text,emptyList()))})}
}

private fun JsonObject.string(key:String,default:String="")=get(key)?.takeUnless(JsonElement::isJsonNull)?.asString?:default
private fun JsonObject.optionalString(key:String)=get(key)?.takeUnless(JsonElement::isJsonNull)?.asString
private fun JsonObject.integer(key:String,default:Int=0)=get(key)?.takeUnless(JsonElement::isJsonNull)?.asInt?:default
private fun JsonObject.double(key:String,default:Double=0.0)=get(key)?.takeUnless(JsonElement::isJsonNull)?.asDouble?:default
private fun JsonObject.boolean(key:String,default:Boolean=false)=get(key)?.takeUnless(JsonElement::isJsonNull)?.asBoolean?:default
private fun JsonObject.millis(key:String)=optionalMillis(key)?:System.currentTimeMillis()
private fun JsonObject.optionalMillis(key:String)=optionalString(key)?.let{runCatching{Instant.parse(it).toEpochMilli()}.getOrNull()}
