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
import okhttp3.RequestBody.Companion.asRequestBody
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

/**
 * IDs arrive from a remote, potentially untrusted journal/API.  They are not
 * filesystem paths: keep the deliberately broad legacy identifier grammar,
 * but reject every path separator, control character and malformed composite
 * id before an entry can affect storage or advance a sync cursor.
 */
internal object SyncEntityIdentityContract {
    private val atom = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,255}$")
    private val safeOpaque = Regex("^[A-Za-z0-9][A-Za-z0-9._:-]{0,1023}$")
    private val atomicTypes = setOf("notebook", "section", "tag", "page", "document", "task_step", "asset", "revision", "page_link")
    private val compositeTypes = setOf("page_tag", "reading_position")

    fun requireChange(type:String, id:String, payload:JsonObject): String {
        require(type in atomicTypes || type in compositeTypes) { "远端同步包含不支持的实体类型：$type" }
        when (type) {
            "page_link" -> requireOpaque(id, "实体 ID")
            in atomicTypes -> requireAtom(id, "实体 ID")
            "page_tag" -> requireComposite(id, "实体 ID")
            "reading_position" -> requireReadingPositionId(id)
        }
        requirePayloadReferences(type, id, payload)
        return id
    }

    fun requireEntityId(type:String, id:String): String {
        require(type in atomicTypes || type in compositeTypes) { "远端同步包含不支持的实体类型：$type" }
        if (type == "page_link") requireOpaque(id)
        else if (type in atomicTypes) requireAtom(id)
        else if (type == "reading_position") requireReadingPositionId(id)
        else requireComposite(id, "实体 ID")
        return id
    }

    fun requireAtom(value:String, label:String="实体 ID"): String {
        require(atom.matches(value)) { "$label 格式不正确" }
        return value
    }

    private fun requireOpaque(value:String,label:String="实体 ID"):String {
        require(safeOpaque.matches(value)&&!value.contains("..")) { "$label 格式不正确" }
        return value
    }

    private fun requireComposite(value:String, label:String) {
        val parts = value.split(':')
        require(parts.size == 2) { "$label 格式不正确" }
        parts.forEach { requireAtom(it, label) }
    }

    /** Desktop writes page-only IDs; older Android journals used page:device. */
    private fun requireReadingPositionId(value:String) {
        if (atom.matches(value)) return
        requireComposite(value, "实体 ID")
    }

    private fun requirePayloadReferences(type:String, id:String, payload:JsonObject) {
        fun optional(key:String):String? = payload[key]
            ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
            ?.asString
        fun checkOptional(key:String) { optional(key)?.let { requireAtom(it, key) } }
        when (type) {
            "page" -> {
                optional("id")?.let { require(it == id) { "页面 ID 与实体 ID 不一致" }; requireAtom(it, "页面 ID") }
                checkOptional("sectionId"); checkOptional("parentPageId")
            }
            "document" -> optional("pageId")?.let { require(it == id) { "文档 pageId 与实体 ID 不一致" }; requireAtom(it, "pageId") }
            "section" -> { checkOptional("notebookId"); checkOptional("parentSectionId") }
            "task_step", "asset" -> checkOptional("pageId")
            "page_link" -> { checkOptional("sourcePageId"); checkOptional("targetPageId") }
            "revision" -> checkOptional("pageId")
            "page_tag" -> {
                val pageId = optional("pageId")?.also { requireAtom(it, "pageId") }
                val tagId = optional("tagId")?.also { requireAtom(it, "tagId") }
                require((pageId == null && tagId == null) || (pageId != null && tagId != null && id == "$pageId:$tagId")) { "page_tag ID 与内容不一致" }
            }
            "reading_position" -> {
                val pageId = optional("pageId")?.also { requireAtom(it, "pageId") }
                val deviceId = optional("deviceId")
                if (deviceId != null) {
                    requireAtom(deviceId, "deviceId")
                    require(pageId != null && (id == pageId || id == "$pageId:$deviceId")) { "reading_position ID 与内容不一致" }
                }
            }
        }
    }
}

/** Centralized canonical containment check for every remote attachment write. */
internal object AttachmentStorageContract {
    fun file(context:Context, relative:String):File {
        require(relative.isNotBlank() && !relative.startsWith('/') && !relative.startsWith('~')) { "非法附件路径" }
        require(!relative.contains('\\') && relative.none { it.isISOControl() }) { "非法附件路径" }
        require(relative.split('/').none { it.isBlank() || it == "." || it == ".." }) { "非法附件路径" }
        val root = File(context.filesDir, "attachments").canonicalFile
        val target = File(root, relative).canonicalFile
        require(target.toPath().startsWith(root.toPath())) { "附件路径越界" }
        return target
    }

    fun temporaryFile(target:File, context:Context):File {
        val root = File(context.filesDir, "attachments").canonicalFile
        val parent = target.parentFile?.canonicalFile ?: error("附件目录无效")
        require(parent.toPath().startsWith(root.toPath())) { "附件临时文件路径越界" }
        return File(parent, "${target.name}.download")
    }

    fun deleteIfContained(context:Context, candidate:File) {
        val attachments = File(context.filesDir, "attachments").canonicalFile
        val target = candidate.canonicalFile
        if (target.toPath().startsWith(attachments.toPath()) && target.isFile) target.delete()
    }
}

/** Versioned HTTP synchronizer shared with Notebook Next web/desktop. */
class ApiSyncClient(
    private val context:Context,
    private val dao:NotebookDao,
    private val allowInsecureHttp:Boolean=BuildConfig.DEBUG
) {
    private val gson:Gson=GsonBuilder().disableHtmlEscaping().create()
    private val http=OkHttpClient.Builder().connectTimeout(15,TimeUnit.SECONDS).readTimeout(60,TimeUnit.SECONDS).writeTimeout(60,TimeUnit.SECONDS).build()

    suspend fun sync(settings:ApiSyncSettings) {
        val normalized=settings.copy(baseUrl=settings.baseUrl.trim().trimEnd('/'),workspaceId=settings.workspaceId.trim(),token=settings.token.trim())
        validate(normalized)
        val downloadBudget=RemoteTransferLimits.Budget()
        // On a freshly connected Android install, receive the web migration first.
        // Clean records from the legacy SSH database may then be safely replaced.
        if((dao.apiCursor(normalized.workspaceId)?:0L)==0L) pullAll(normalized,downloadBudget)
        queueDirtyRecords(normalized.workspaceId)
        push(normalized)
        pullAll(normalized,downloadBudget)
    }

    private fun validate(s:ApiSyncSettings) {
        require(s.baseUrl.isNotBlank()){"请填写 Notebook Next 服务地址"}
        require(s.workspaceId.isNotBlank()){"工作区 ID 不能为空"}
        val uri=runCatching{URI(s.baseUrl)}.getOrElse{throw IllegalArgumentException("服务地址格式不正确")}
        require(uri.scheme=="https"||(allowInsecureHttp&&uri.scheme=="http")){"正式版同步服务必须使用 HTTPS"}
        require(!uri.host.isNullOrBlank()){"服务地址缺少主机名"}
    }

    internal fun versionKey(workspaceId:String,type:String,id:String)="$workspaceId:$type:$id"
    internal suspend fun enqueue(workspaceId:String,type:String,id:String,operation:String,payload:JsonObject) {
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
            if(note.icon!=null)addProperty("icon",note.icon)else remove("icon")
            if(note.parentPageId!=null)addProperty("parentPageId",note.parentPageId)else remove("parentPageId")
            addProperty("sortOrder",note.sortOrder);addProperty("treeUpdatedAt",iso(note.treeUpdatedAt))
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
    suspend fun queueReadingPosition(workspaceId:String,p:ReadingPositionEntity)=enqueue(workspaceId,"reading_position",p.noteId,"upsert",JsonObject().apply{
        addProperty("id",p.noteId);addProperty("pageId",p.noteId);addProperty("anchorUtf16Offset",p.anchorUtf16Offset);addProperty("viewportOffsetFraction",p.viewportOffsetFraction);addProperty("updatedAt",iso(p.updatedAt));addProperty("deviceId",p.deviceId)
    })

    suspend fun acceptConflict(workspaceId:String,entityType:String,entityId:String,payload:JsonObject) {
        when(entityType){
            "page"->applyPage(entityId,payload,dao.apiVersion(versionKey(workspaceId,entityType,entityId))?.version?:0)
            "document"->applyDocument(entityId,payload)
            "task_step"->dao.putStep(TodoStepEntity(entityId,payload.string("pageId"),payload.string("text"),payload.boolean("checked"),payload.integer("sortOrder"),payload.millis("createdAt")))
        }
        pageIdFor(entityType,entityId,payload)?.let{pageId->dao.get(pageId)?.let{dao.put(it.copy(dirty=false,conflict=false,conflictSnapshotJson=null))}}
    }

    internal suspend fun queueDirtyRecords(workspaceId:String) {
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
                RemoteTransferLimits.requireUploadSize(file.length())
                execute(s,Request.Builder().url(url).put(file.asRequestBody(asset.mimeType.toMediaType())).build()).close()
            }
        }
        val requestJson=JsonObject().apply{addProperty("workspace_id",s.workspaceId);add("changes",JsonArray().apply{outgoing.forEach{item->add(JsonObject().apply{
            addProperty("operation_id",item.id);addProperty("entity_type",item.entityType);addProperty("entity_id",item.entityId);addProperty("expected_version",item.expectedVersion);addProperty("operation",item.operation);add("payload",JsonParser.parseString(item.payloadJson))
        })}})}
        val response=execute(s,Request.Builder().url("${s.baseUrl}/v1/sync/push").post(gson.toJson(requestJson).toRequestBody(JSON)).build())
        val root=response.use{JsonParser.parseString(it.body?.string().orEmpty()).asJsonObject}
        val sentById=outgoing.associateBy{it.id}
        root["applied"]?.asJsonArray?.forEach{raw->val item=raw.asJsonObject;val operationId=item.string("operation_id");val type=item.string("entity_type");val id=SyncEntityIdentityContract.requireEntityId(type,item.string("entity_id"));val version=item["version"].asLong;dao.deleteApiOutboxById(operationId);dao.putApiVersion(ApiSyncVersionEntity(versionKey(s.workspaceId,type,id),version));if(type=="page")dao.get(id)?.let{dao.put(it.copy(dirty=false,lastSyncedVersion=version))};if(type=="asset")dao.getAsset(id)?.let{dao.putAssets(listOf(it.copy(dirty=false)))};if(type=="reading_position"){val payload=sentById[operationId]?.payloadJson?.let{runCatching{JsonParser.parseString(it).asJsonObject}.getOrNull()};val noteId=payload?.optionalString("pageId");val updatedAt=payload?.optionalMillis("updatedAt");if(noteId!=null&&updatedAt!=null)dao.markReadingPositionSynced(noteId,updatedAt)}}
        root["conflicts"]?.asJsonArray?.forEach{raw->val item=raw.asJsonObject;val type=item.string("entity_type");val id=SyncEntityIdentityContract.requireEntityId(type,item.string("entity_id"));val currentVersion=item["current_version"].asLong;dao.deleteApiOutboxById(item.string("operation_id"));dao.putApiVersion(ApiSyncVersionEntity(versionKey(s.workspaceId,type,id),currentVersion));pageIdFor(type,id,item["current_payload"]?.asJsonObject)?.let{pageId->recordConflict(pageId,type,item["current_payload"]?.asJsonObject?:JsonObject())}}
    }

    private suspend fun pullAll(s:ApiSyncSettings,downloadBudget:RemoteTransferLimits.Budget) {
        var cursor=dao.apiCursor(s.workspaceId)?:0L
        do {
            val url="${s.baseUrl}/v1/sync/pull?workspace_id=${encoded(s.workspaceId)}&cursor=$cursor&limit=200"
            val root=execute(s,Request.Builder().url(url).get().build()).use{JsonParser.parseString(it.body?.string().orEmpty()).asJsonObject}
            root["changes"].asJsonArray.forEach{applyChange(s,it.asJsonObject,downloadBudget)}
            cursor=root["cursor"].asLong;dao.putApiCursor(ApiSyncCursorEntity(s.workspaceId,cursor))
            val more=root["has_more"].asBoolean
        } while(more)
    }

    private suspend fun applyChange(s:ApiSyncSettings,change:JsonObject,downloadBudget:RemoteTransferLimits.Budget) {
        val type=change.string("entity_type");val id=change.string("entity_id");val version=change["version"].asLong;val operation=change.string("operation");val payload=change["payload"].asJsonObject
        require(operation=="upsert"||operation=="delete"){"远端同步包含非法操作"}
        // Delete rows from older peers may have no payload at all. Their ID is
        // still validated, while upserts validate relation fields as well.
        if(operation=="delete")SyncEntityIdentityContract.requireEntityId(type,id) else SyncEntityIdentityContract.requireChange(type,id,payload)
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
            "asset"->applyAsset(s,id,payload,downloadBudget)
            "reading_position"->dao.putReadingPosition(ReadingPositionEntity(payload.string("pageId"),payload.integer("anchorUtf16Offset"),payload.double("viewportOffsetFraction"),payload.millis("updatedAt"),payload.string("deviceId")))
            "page_link"->applyPageLink(id,payload)
            "revision"->applyRevision(id,payload)
        }
        dao.putApiVersion(ApiSyncVersionEntity(versionKey(s.workspaceId,type,id),version))
    }

    internal suspend fun applyPage(id:String,p:JsonObject,serverVersion:Long) {
        val local=dao.get(id);val folderId=p.optionalString("sectionId");val folderName=folderId?.let{dao.getFolder(it)?.name}?:"未分类"
        val note=NoteEntity(id=id,title=p.string("title"),body=local?.body.orEmpty(),previewText=p.string("preview"),createdAt=p.millis("createdAt"),updatedAt=p.millis("updatedAt"),folderId=folderId,folderName=folderName,icon=p.optionalString("icon"),parentPageId=p.optionalString("parentPageId"),sortOrder=p["sortOrder"]?.asDouble?:local?.sortOrder?:0.0,treeUpdatedAt=p.optionalMillis("treeUpdatedAt")?:local?.treeUpdatedAt?:p.millis("createdAt"),reminderAt=p.optionalMillis("reminderAt"),recurrence=p.string("recurrenceRule","none"),version=p["legacyVersion"]?.asLong?:local?.version?:1,tagIds=local?.tagIds.orEmpty(),deletedAt=p.optionalMillis("deletedAt"),itemType=if(p.string("kind","document")=="task")"todo" else "note",dueAt=p.optionalMillis("dueAt"),completedAt=p.optionalMillis("completedAt"),important=p.boolean("important"),viewMode=local?.viewMode?:"preview",dirty=false,conflict=false,snapshotJson=local?.snapshotJson,conflictSnapshotJson=null,lastSyncedVersion=serverVersion)
        dao.put(note);dao.putApiPage(ApiPageEntity(id,gson.toJson(p),note.updatedAt))
    }

    internal suspend fun applyDocument(id:String,p:JsonObject) {
        val json=p["tiptapJson"]?:JsonObject().apply{addProperty("type","doc");add("content",JsonArray())}
        val updated=p.optionalMillis("updatedAt")?:System.currentTimeMillis();dao.putApiDocument(ApiDocumentEntity(id,gson.toJson(json),p.integer("schemaVersion",1),updated))
        dao.get(id)?.let{note->val markdown=TipTapCodec.decode(json);dao.put(note.copy(body=markdown,previewText=TipTapCodec.plainText(markdown).take(240),updatedAt=maxOf(note.updatedAt,updated),dirty=false))}
    }

    internal suspend fun applyPageTag(p:JsonObject,add:Boolean) {val pageId=p.string("pageId");val tagId=p.string("tagId");dao.get(pageId)?.let{note->val ids=note.tagIds.split(',').filter(String::isNotBlank).toMutableSet();if(add)ids+=tagId else ids-=tagId;dao.put(note.copy(tagIds=ids.joinToString(",")))}}
    internal suspend fun applyPageLink(id:String,p:JsonObject){
        if(dao.get(p.string("sourcePageId"))==null)return
        val kind=p.string("kind","link")
        dao.putPageLinks(listOf(PageLinkEntity(id,p.string("sourcePageId"),p.string("targetPageId"),kind,null,p.optionalString("excerpt"),kind=="embed",0,p.millis("createdAt"))))
    }
    internal suspend fun applyRevision(id:String,p:JsonObject){
        val document=p["document"]?.takeIf{it.isJsonObject}?.asJsonObject?:return
        val markdown=document.optionalString("markdown")?:document["tiptapJson"]?.let(TipTapCodec::decode)?:return
        dao.putRemoteRevision(RemoteRevisionEntity(id,p.string("pageId"),p.millis("createdAt"),p.string("reason","autosave"),markdown,p.optionalString("pageIcon")))
    }
    private suspend fun applyAsset(s:ApiSyncSettings,id:String,p:JsonObject,downloadBudget:RemoteTransferLimits.Budget) {
        val filename=p.string("filename","attachment").replace(Regex("[^A-Za-z0-9._\\-\\u4e00-\\u9fff]"),"_")
        val relative="next/$id/$filename"
        val target=AttachmentStorageContract.file(context,relative)
        target.parentFile?.mkdirs()
        val temp=AttachmentStorageContract.temporaryFile(target,context)
        val digest=MessageDigest.getInstance("SHA-256")
        var fileBytes=0L
        try {
            execute(s,Request.Builder().url(assetUrl(s,id)).get().build()).use{response->
                val body=response.body?:error("附件 $id 下载为空")
                body.contentLength().takeIf{it>=0}?.let{RemoteTransferLimits.requireDownloadSize(it)}
                body.byteStream().use{input->temp.outputStream().use{output->
                    val buffer=ByteArray(1024*1024)
                    while(true){
                        val count=input.read(buffer)
                        if(count<0)break
                        fileBytes+=count
                        downloadBudget.consume(fileBytes,count.toLong())
                        digest.update(buffer,0,count)
                        output.write(buffer,0,count)
                    }
                }}
            }
            val actualHash=digest.digest().joinToString(""){"%02x".format(it)}
            p.optionalString("checksum")?.takeIf{it.isNotBlank()}?.let{expected->
                require(expected.equals(actualHash,ignoreCase=true)){"附件 $id 的 SHA-256 校验失败"}
            }
            if(!temp.renameTo(target)){temp.copyTo(target,true);temp.delete()}
            dao.putAssets(listOf(AssetEntity(id,p.string("pageId"),p.string("kind","file"),filename,p.string("mimeType","application/octet-stream"),relative,target.absolutePath,actualHash,fileBytes,false)))
        } catch(error:Throwable) {
            temp.delete()
            throw error
        }
    }

    internal suspend fun recordConflict(pageId:String,type:String,payload:JsonObject){dao.get(pageId)?.let{note->
        val wrapper=note.conflictSnapshotJson?.let{runCatching{JsonParser.parseString(it).asJsonObject}.getOrNull()}?.takeIf{it.has("apiConflicts")}?:JsonObject().apply{add("apiConflicts",JsonObject())}
        wrapper["apiConflicts"].asJsonObject.add(type,payload.deepCopy());dao.put(note.copy(conflict=true,conflictSnapshotJson=gson.toJson(wrapper)))
    }}

    internal suspend fun applyDelete(type:String,id:String,p:JsonObject){when(type){"notebook"->dao.deleteApiNotebook(id);"section"->dao.deleteFolder(id);"tag"->dao.deleteTag(id);"page"->{dao.deleteApiPage(id);deleteNoteAndAssets(id)};"document"->dao.deleteApiDocument(id);"task_step"->dao.deleteStep(id);"asset"->deleteAssetAndFile(id);"page_link"->dao.deletePageLink(id);"revision"->dao.deleteRemoteRevision(id);"page_tag"->applyPageTag(if(p.size()>0)p else JsonObject().apply{val parts=id.split(':',limit=2);addProperty("pageId",parts.firstOrNull().orEmpty());addProperty("tagId",parts.getOrNull(1).orEmpty())},false);"reading_position"->p.optionalString("pageId")?.let{dao.deleteReadingPosition(it)}}}
    internal suspend fun deleteAssetAndFile(id:String){dao.getAsset(id)?.localPath?.let(::File)?.let{AttachmentStorageContract.deleteIfContained(context,it)};dao.deleteAsset(id)}
    internal suspend fun deleteNoteAndAssets(noteId:String){dao.assets(noteId).forEach{asset->asset.localPath?.let(::File)?.let{AttachmentStorageContract.deleteIfContained(context,it)}};dao.deleteRemoteRevisionsForNote(noteId);dao.deleteNotePermanently(noteId)}

    internal fun stepPayload(s:TodoStepEntity)=JsonObject().apply{addProperty("id",s.id);addProperty("pageId",s.noteId);addProperty("text",s.text);addProperty("checked",s.checked);addProperty("sortOrder",s.sortOrder);addProperty("createdAt",iso(s.createdAt))}
    internal fun assetPayload(a:AssetEntity)=JsonObject().apply{addProperty("id",a.id);addProperty("pageId",a.noteId);addProperty("kind",a.kind);addProperty("filename",a.filename);addProperty("mimeType",a.mimeType);addProperty("byteSize",a.size);addProperty("checksum",a.contentHash);addProperty("createdAt",iso(File(a.localPath?:"").takeIf(File::exists)?.lastModified()?:System.currentTimeMillis()))}
    internal fun pageIdFor(type:String,id:String,p:JsonObject?)=when(type){"page","document"->id;"task_step","asset","page_tag","reading_position","revision"->p?.optionalString("pageId")?:id.substringBefore(':');"page_link"->p?.optionalString("sourcePageId");else->null}
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
