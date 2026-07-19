package io.github.notebook.android.sync

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.gson.*
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.SftpException
import io.github.notebook.android.data.*
import io.github.notebook.android.reminder.Reminders
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.UUID

data class SshSettings(val host:String,val port:Int,val username:String,val password:String,val path:String,val fingerprint:String="")
class HostKeyChangedException(val expected:String,val actual:String):Exception("服务器身份已变化，请确认新的主机指纹")
enum class NoteSaveState { Pending, Saving, Saved, Failed }

/** Compatible with the macOS SSH JSON store. Swift JSONEncoder dates are seconds since 2001-01-01. */
class SyncRepository(context:Context, private val dao:NotebookDao) {
    private val appContext=context.applicationContext
    private val gson=GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create()
    private val appScope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val saveMutex=Mutex()
    private val syncMutex=Mutex()
    private val pendingDrafts=ConcurrentHashMap<String,NoteEntity>()
    private val draftJobs=ConcurrentHashMap<String,Job>()
    private val syncRequestLock=Any()
    private var syncRequestJob:Job?=null
    private val _saveError=MutableStateFlow<String?>(null)
    val saveError=_saveError.asStateFlow()
    private val _noteSaveStates=MutableStateFlow<Map<String,NoteSaveState>>(emptyMap())
    val noteSaveStates=_noteSaveStates.asStateFlow()
    private fun setSaveState(noteId:String,state:NoteSaveState){_noteSaveStates.update{it+mapOf(noteId to state)}}
    fun markUnsaved(noteId:String){setSaveState(noteId,NoteSaveState.Pending)}
    fun acknowledgeSaved(noteId:String){_noteSaveStates.update{states->if(states[noteId]==NoteSaveState.Saved)states-noteId else states}}
    fun clearSaveError(){_saveError.value=null}
    private val prefs=encryptedPrefs(context)
    val notes=dao.observeNoteSummaries()
    val folders=dao.observeFolders()
    val tags=dao.observeTags()
    fun settings()=SshSettings(prefs.getString("host","")!!,prefs.getInt("port",22),prefs.getString("user","")!!,prefs.getString("password","")!!,prefs.getString("path","~/NotebookSync")!!,prefs.getString("fingerprint","")!!)
    fun saveSettings(s:SshSettings){prefs.edit().putString("host",s.host).putInt("port",s.port).putString("user",s.username).putString("password",s.password).putString("path",s.path).putString("fingerprint",s.fingerprint.trim()).apply()}
    fun hasRemoteConfiguration()=settings().let{it.host.isNotBlank()&&it.username.isNotBlank()&&it.path.isNotBlank()}
    fun trustHostKey(fingerprint:String){saveSettings(settings().copy(fingerprint=fingerprint))}
    fun isEncrypted(note:NoteEntity)=encryptedNoteIds().contains(note.id)||encryptedFolderId(note)!=null
    fun encryptedFolderId(note:NoteEntity)=encryptedMappings()[note.id]?:note.folderId?.takeIf{encryptedNoteIds().contains(note.id)}
    fun isEncrypted(note:NoteSummary)=encryptedNoteIds().contains(note.id)||encryptedFolderId(note)!=null
    fun encryptedFolderId(note:NoteSummary)=encryptedMappings()[note.id]?:note.folderId?.takeIf{encryptedNoteIds().contains(note.id)}
    suspend fun loadNote(id:String)=withContext(Dispatchers.IO){loadEditable(id)}
    suspend fun searchNoteIds(query:String)=withContext(Dispatchers.IO){dao.searchNoteIds(query).toSet()}
    fun markEncrypted(noteId:String,folderId:String){val ids=encryptedNoteIds().toMutableSet().apply{add(noteId)};val mappings=encryptedMappings().toMutableMap().apply{put(noteId,folderId)};prefs.edit().putString("encryptedNoteIds",gson.toJson(ids)).putString("encryptedMappings",gson.toJson(mappings)).apply()}
    fun moveEncrypted(noteId:String,folderId:String)=markEncrypted(noteId,folderId)
    private fun encryptedNoteIds():Set<String> = runCatching{gson.fromJson(prefs.getString("encryptedNoteIds","[]"),Array<String>::class.java).toSet()}.getOrDefault(emptySet())
    private fun encryptedMappings():Map<String,String> = runCatching{gson.fromJson(prefs.getString("encryptedMappings","{}"),JsonObject::class.java).entrySet().associate{it.key to it.value.asString}}.getOrDefault(emptyMap())
    suspend fun save(n:NoteEntity):NoteEntity{setSaveState(n.id,NoteSaveState.Saving);return try{dao.putDraft(DraftEntity(n.id,gson.toJson(n)));persistDraft(n).also{setSaveState(n.id,NoteSaveState.Saved)}}catch(error:Throwable){setSaveState(n.id,NoteSaveState.Failed);throw error}}
    fun queueDraft(n:NoteEntity){setSaveState(n.id,NoteSaveState.Pending);pendingDrafts[n.id]=n;draftJobs.remove(n.id)?.cancel();val job=appScope.launch{try{dao.putDraft(DraftEntity(n.id,gson.toJson(n)));delay(600);val latest=pendingDrafts[n.id]?:return@launch;setSaveState(n.id,NoteSaveState.Saving);persistDraft(latest);if(pendingDrafts.remove(n.id,latest))setSaveState(n.id,NoteSaveState.Saved)}catch(error:Throwable){if(error is CancellationException)throw error;setSaveState(n.id,NoteSaveState.Failed);_saveError.value="自动保存失败：${error.localizedMessage}"}};draftJobs[n.id]=job;job.invokeOnCompletion{draftJobs.remove(n.id,job)}}
    fun flushDraft(n:NoteEntity):Job{setSaveState(n.id,NoteSaveState.Saving);pendingDrafts[n.id]=n;draftJobs.remove(n.id)?.cancel();val job=appScope.launch{try{dao.putDraft(DraftEntity(n.id,gson.toJson(n)));val latest=pendingDrafts[n.id]?:n;persistDraft(latest);if(pendingDrafts.remove(n.id,latest))setSaveState(n.id,NoteSaveState.Saved)}catch(error:Throwable){if(error is CancellationException)throw error;setSaveState(n.id,NoteSaveState.Failed);_saveError.value="保存失败，草稿仍保留：${error.localizedMessage}"}};draftJobs[n.id]=job;job.invokeOnCompletion{draftJobs.remove(n.id,job)};return job}
    fun flushAllAsync(){pendingDrafts.values.toList().forEach(::flushDraft)}
    suspend fun flushAll(){val drafts=pendingDrafts.values.toList();drafts.forEach{draftJobs.remove(it.id)?.cancel()};drafts.forEach{draft->setSaveState(draft.id,NoteSaveState.Saving);try{dao.putDraft(DraftEntity(draft.id,gson.toJson(draft)));persistDraft(draft);if(pendingDrafts.remove(draft.id,draft))setSaveState(draft.id,NoteSaveState.Saved)}catch(error:Throwable){setSaveState(draft.id,NoteSaveState.Failed);throw error}}}
    suspend fun recoverDrafts(){dao.draftNoteIds().forEach{id->readLargeText(dao.draftPayloadLength(id)){start,length->dao.draftPayloadChunk(id,start,length)}?.let{payload->runCatching{gson.fromJson(payload,NoteEntity::class.java)}.getOrNull()?.let{persistDraft(it)}}}}
    fun recoverDraftsAsync(){appScope.launch{runCatching{recoverDrafts()}.onFailure{_saveError.value="恢复本地草稿失败：${it.localizedMessage}"}}}

    suspend fun readingPosition(noteId:String)=withContext(Dispatchers.IO){dao.readingPosition(noteId)}
    fun recordReadingPosition(noteId:String,anchorUtf16Offset:Int,viewportOffsetFraction:Double){appScope.launch{
        val anchor=anchorUtf16Offset.coerceAtLeast(0);val fraction=viewportOffsetFraction.coerceIn(-1.0,1.0);val current=dao.readingPosition(noteId)
        if(current?.anchorUtf16Offset==anchor&&kotlin.math.abs(current.viewportOffsetFraction-fraction)<0.001)return@launch
        dao.putReadingPosition(ReadingPositionEntity(noteId,anchor,fraction,System.currentTimeMillis(),deviceId()))
        requestSyncIfConfigured(2)
    }}

    private suspend fun persistDraft(draft:NoteEntity):NoteEntity=saveMutex.withLock{
        val current=loadEditable(draft.id)
        if(current!=null&&sameEditableContent(current,draft)){dao.deleteDraft(draft.id);return@withLock current}
        val base=current?:draft;val saved=base.copy(title=draft.title,body=draft.body,previewText=previewText(draft.body),folderId=draft.folderId,folderName=draft.folderName,reminderAt=draft.reminderAt,recurrence=draft.recurrence,tagIds=draft.tagIds,deletedAt=draft.deletedAt,itemType=draft.itemType,dueAt=draft.dueAt,completedAt=draft.completedAt,important=draft.important,viewMode=draft.viewMode,updatedAt=System.currentTimeMillis(),version=(current?.version?:draft.version)+1,dirty=true)
        if(current==null)dao.put(saved)else dao.updateEditable(saved.editableUpdate());dao.deleteDraft(draft.id);if(saved.deletedAt!=null||saved.completedAt!=null||saved.reminderAt==null)Reminders.cancel(appContext,saved.id)else Reminders.schedule(appContext,saved.id,saved.title,saved.reminderAt,saved.recurrence);requestSyncIfConfigured();saved
    }
    private fun sameEditableContent(a:NoteEntity,b:NoteEntity)=a.title==b.title&&a.body==b.body&&a.folderId==b.folderId&&a.folderName==b.folderName&&a.reminderAt==b.reminderAt&&a.recurrence==b.recurrence&&a.tagIds==b.tagIds&&a.deletedAt==b.deletedAt&&a.itemType==b.itemType&&a.dueAt==b.dueAt&&a.completedAt==b.completedAt&&a.important==b.important&&a.viewMode==b.viewMode
    suspend fun restore(id:String){loadEditable(id)?.let{save(it.copy(deletedAt=null))}}
    suspend fun deletePermanently(id:String){Reminders.cancel(appContext,id);dao.deleteReadingPosition(id);dao.deleteNotePermanently(id);requestSyncIfConfigured()}
    suspend fun keepLocal(id:String){loadEditable(id)?.let{local->val remoteVersion=loadConflictSnapshot(id)?.let{runCatching{JsonParser.parseString(it).asJsonObject["metadata"].asJsonObject["version"].asLong}.getOrNull()}?:0;dao.put(local.copy(snapshotJson=loadSnapshot(id),version=maxOf(local.version,remoteVersion)+1,updatedAt=System.currentTimeMillis(),dirty=true,conflict=false,conflictSnapshotJson=null))}}
    suspend fun acceptRemote(id:String){val local=loadEditable(id)?:return;val snapshot=loadConflictSnapshot(id)?.let{runCatching{JsonParser.parseString(it).asJsonObject}.getOrNull()}?:return;val env=JsonObject().apply{addProperty("noteID",id);add("currentSnapshot",snapshot)};val remote=fromEnvelope(env);dao.put(remote.copy(version=maxOf(local.version,remote.version)+1,dirty=true,conflict=false,conflictSnapshotJson=null,lastSyncedVersion=local.lastSyncedVersion))}
    suspend fun saveFolder(name:String,type:String="noteFolder",id:String=UUID.randomUUID().toString())=dao.putFolder(FolderEntity(id,name,dao.allFolders().size,type))
    suspend fun deleteFolder(id:String){dao.deleteFolder(id);dao.putTombstone(TombstoneEntity("folder|$id",id,"folder",System.currentTimeMillis(),deviceId()))}
    suspend fun saveTag(name:String,color:String="gray",id:String=UUID.randomUUID().toString())=dao.putTag(TagEntity(id,name,color))
    suspend fun deleteTag(id:String){dao.deleteTag(id);dao.putTombstone(TombstoneEntity("tag|$id",id,"tag",System.currentTimeMillis(),deviceId()))}
    fun steps(noteId:String)=dao.observeSteps(noteId)
    fun assets(noteId:String)=dao.observeAssets(noteId)
    suspend fun saveStep(step:TodoStepEntity){dao.putStep(step);loadEditable(step.noteId)?.let{save(it)}}
    suspend fun deleteStep(id:String){val noteId=dao.getStep(id)?.noteId;dao.deleteStep(id);noteId?.let{loadEditable(it)?.let{n->save(n)}}}
    suspend fun attach(noteId:String,uri:android.net.Uri):AssetEntity=withContext(Dispatchers.IO){
        val resolver=appContext.contentResolver;val id=UUID.randomUUID().toString();val mime=resolver.getType(uri)?:"application/octet-stream"
        var name="attachment";resolver.query(uri,arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),null,null,null)?.use{if(it.moveToFirst())name=it.getString(0)?:name}
        name=name.replace(Regex("[^A-Za-z0-9._\\-\\u4e00-\\u9fff]"),"_");val relative="$noteId/$id-$name";val target=java.io.File(appContext.filesDir,"attachments/$relative");target.parentFile?.mkdirs();resolver.openInputStream(uri).use{input->requireNotNull(input){"无法读取附件"}.copyTo(target.outputStream())}
        val kind=when{mime.startsWith("image/")->"image";mime.startsWith("audio/")->"audio";else->"file"};AssetEntity(id,noteId,kind,name,mime,relative,target.absolutePath,sha(target.readBytes()),target.length(),true).also{dao.putAssets(listOf(it));loadEditable(noteId)?.let{n->save(n)}}
    }
    fun recordingFile(noteId:String)=java.io.File(appContext.filesDir,"recordings/$noteId/${System.currentTimeMillis()}.m4a")
    suspend fun addRecordedAudio(noteId:String,file:java.io.File):AssetEntity=withContext(Dispatchers.IO){require(file.isFile&&file.length()>0){"录音文件为空"};val id=UUID.randomUUID().toString();val relative="$noteId/$id-${file.name}";val target=java.io.File(appContext.filesDir,"attachments/$relative");target.parentFile?.mkdirs();file.copyTo(target,true);file.delete();AssetEntity(id,noteId,"audio",target.name,"audio/mp4",relative,target.absolutePath,sha(target.readBytes()),target.length(),true).also{dao.putAssets(listOf(it));loadEditable(noteId)?.let{n->save(n)}}}
    suspend fun importText(uri:android.net.Uri,folder:FolderEntity?):NoteEntity=withContext(Dispatchers.IO){
        val resolver=appContext.contentResolver;var displayName="导入笔记";var declaredSize:Long?=null
        resolver.query(uri,arrayOf(android.provider.OpenableColumns.DISPLAY_NAME,android.provider.OpenableColumns.SIZE),null,null,null)?.use{cursor->if(cursor.moveToFirst()){displayName=cursor.getString(0)?:displayName;if(!cursor.isNull(1))declaredSize=cursor.getLong(1)}}
        require(displayName.substringAfterLast('.',"").lowercase() in setOf("txt","md","markdown")){"仅支持 txt/md 文档"}
        val maxBytes=50L*1024*1024;require((declaredSize?:0)<=maxBytes){"文档超过 50 MB，已拒绝导入"}
        val bytes=resolver.openInputStream(uri).use{input->requireNotNull(input){"无法读取文档"};val output=ByteArrayOutputStream();val buffer=ByteArray(64*1024);var total=0L;while(true){val count=input.read(buffer);if(count<0)break;total+=count;require(total<=maxBytes){"文档超过 50 MB，已停止导入"};output.write(buffer,0,count)};output.toByteArray()}
        val body=decodeText(bytes).replace("\r\n","\n").replace('\r','\n').removePrefix("\uFEFF")
        val id=UUID.randomUUID().toString();val encrypted=folder?.type=="encryptedFolder";if(encrypted)markEncrypted(id,folder!!.id)
        save(NoteEntity(id=id,title=displayName.substringBeforeLast('.').ifBlank{"导入笔记"},body=body,folderId=folder?.id,folderName=folder?.name?:"未分类",tagIds=""))
    }

    suspend fun sync():Unit=syncMutex.withLock{withContext(Dispatchers.IO){
        flushAll()
        val s=settings(); require(s.host.isNotBlank()&&s.username.isNotBlank()){ "请先配置 SSH 服务器" }
        val verifier=s.fingerprint.takeIf{it.isNotBlank()}?.let{FingerprintHostKeyRepository(s.host,it)}
        val jsch=JSch().apply{verifier?.let{hostKeyRepository=it}}
        val session=jsch.getSession(s.username,s.host,s.port).apply{setPassword(s.password);setConfig("StrictHostKeyChecking",if(verifier!=null)"yes" else "no")}
        try{session.connect(15_000)}catch(error:com.jcraft.jsch.JSchException){verifier?.presentedFingerprint?.takeIf{it!=s.fingerprint}?.let{throw HostKeyChangedException(s.fingerprint,it)};throw error}
        if(s.fingerprint.isBlank())saveSettings(s.copy(fingerprint=sshSha256Fingerprint(java.util.Base64.getDecoder().decode(session.hostKey.key))))
        val ch=(session.openChannel("sftp") as ChannelSftp).apply{connect(15_000)}
        try { mkdirs(ch,s.path); mkdirs(ch,"${s.path}/notes");mkdirs(ch,"${s.path}/attachments");pullLibrary(ch,s);pullNotes(ch,s);pullAssets(ch,s);syncReadingPositions(ch,s);pushLibrary(ch,s);pushNotes(ch,s);pushAssets(ch,s);Reminders.reconcile(appContext,dao.reminders()) } finally { ch.disconnect();session.disconnect() }
    }}

    private suspend fun pullLibrary(c:ChannelSftp,s:SshSettings){
        val root=readJson(c,"${s.path}/library.json")?:return
        val exportedAt=root.optDateMs("exportedAt")?:System.currentTimeMillis()
        val folders=root["folders"]?.asJsonArray?.map{j->val o=j.asJsonObject;FolderEntity(o.str("id"),o.str("name"),o.int("sortOrder"),o.str("folderType","noteFolder"),o.optDateMs("updatedAt")?:o.optDateMs("createdAt")?:exportedAt)}.orEmpty()
        val encrypted=root["encryptedData"]?.takeIf{it.isJsonObject}?.asJsonObject
        val encryptedFolders=encrypted?.get("encryptedFolders")?.takeIf{it.isJsonArray}?.asJsonArray?.map{j->val o=j.asJsonObject;FolderEntity(o.str("id"),o.str("name"),o.int("order"),"encryptedFolder",exportedAt)}.orEmpty()
        encrypted?.let{data->val ids=data["encryptedNoteIDs"]?.takeIf{it.isJsonArray}?.asJsonArray?.map{it.asString}.orEmpty();val mapping=data["noteToFolderMapping"]?.takeIf{it.isJsonObject}?.asJsonObject?.entrySet()?.associate{it.key to it.value.asString}.orEmpty();prefs.edit().putString("encryptedNoteIds",gson.toJson(ids)).putString("encryptedMappings",gson.toJson(mapping)).apply()}
        val tags=root["tags"]?.asJsonArray?.map{j->val o=j.asJsonObject;TagEntity(o.str("id"),o.str("name"),o.str("color","gray"),o.optDateMs("updatedAt")?:o.optDateMs("createdAt")?:exportedAt)}.orEmpty()
        val tombstones=dao.tombstones().associateBy{it.itemKey}
        folders.filter{remote->val deleted=tombstones["folder|${remote.id}"];deleted==null||deleted.deletedAt<remote.updatedAt}.forEach{remote->if((dao.getFolder(remote.id)?.updatedAt?:0)<=remote.updatedAt)dao.putFolder(remote)}
        encryptedFolders.forEach{dao.putFolder(it)}
        tags.filter{remote->val deleted=tombstones["tag|${remote.id}"];deleted==null||deleted.deletedAt<remote.updatedAt}.forEach{remote->if((dao.getTag(remote.id)?.updatedAt?:0)<=remote.updatedAt)dao.putTag(remote)}
        root["deletedFolders"]?.takeIf{it.isJsonArray}?.asJsonArray?.forEach{e->val x=e.asJsonObject;val id=x.str("id");val at=x.dateMs("deletedAt");if((dao.getFolder(id)?.updatedAt?:0)<=at){dao.deleteFolder(id);dao.putTombstone(TombstoneEntity("folder|$id",id,"folder",at,x.str("deletedByDeviceID")))}}
        root["deletedTags"]?.takeIf{it.isJsonArray}?.asJsonArray?.forEach{e->val x=e.asJsonObject;val id=x.str("id");val at=x.dateMs("deletedAt");if((dao.getTag(id)?.updatedAt?:0)<=at){dao.deleteTag(id);dao.putTombstone(TombstoneEntity("tag|$id",id,"tag",at,x.str("deletedByDeviceID")))}}
    }
    private suspend fun pullNotes(c:ChannelSftp,s:SshSettings){
        val idx=readJson(c,"${s.path}/notes/index.json")?:return
        idx["deletedEntries"]?.asJsonArray?.forEach { entry ->
            val deletion=entry.asJsonObject; val id=deletion.str("noteID"); val deletedAt=deletion.dateMs("deletedAt")
            val local=loadEditable(id)
            if(local!=null && (!local.dirty || deletedAt>=local.updatedAt)) dao.put(local.copy(deletedAt=deletedAt,dirty=false))
        }
        idx["entries"]?.asJsonArray?.forEach { e ->
            val id=e.asJsonObject.str("noteID");val remoteVersion=e.asJsonObject["version"].asLong;val local=loadEditable(id)
            if(local==null||(!local.dirty&&remoteVersion>local.version)) readJson(c,"${s.path}/notes/$id.json")?.let{env->dao.put(fromEnvelope(env));dao.putAssets(assetsFromEnvelope(env));stepsFromEnvelope(env).forEach{dao.putStep(it)}}
            else if(local.dirty&&remoteVersion>local.lastSyncedVersion) readJson(c,"${s.path}/notes/$id.json")?.let{remote->dao.put(local.copy(conflict=true,conflictSnapshotJson=gson.toJson(remote["currentSnapshot"])))}
        }
    }
    private suspend fun pullAssets(c:ChannelSftp,s:SshSettings){
        val manifest=readJson(c,"${s.path}/notes/assets_manifest.json")?:return
        val known=dao.allNoteIds().flatMap{dao.assets(it)}.associateBy{it.relativePath}
        manifest["entries"]?.asJsonArray?.forEach{raw->val e=raw.asJsonObject;val relative=safeRelative(e.str("relativePath"));val expected=e.str("contentHash");val asset=known[relative]?:return@forEach;val target=java.io.File(appContext.filesDir,"attachments/$relative");if(!target.exists()||sha(target.readBytes())!=expected){target.parentFile?.mkdirs();val tmp=java.io.File(target.parentFile,"${target.name}.download");c.get("${s.path}/attachments/$relative",tmp.outputStream());require(sha(tmp.readBytes())==expected){"附件校验失败：$relative"};if(!tmp.renameTo(target)){tmp.copyTo(target,true);tmp.delete()};dao.putAssets(listOf(asset.copy(localPath=target.absolutePath,contentHash=expected,size=target.length(),dirty=false)))}}
    }
    private suspend fun pushAssets(c:ChannelSftp,s:SshSettings){
        val remote=readJson(c,"${s.path}/notes/assets_manifest.json");val entries=linkedMapOf<String,JsonObject>();remote?.get("entries")?.asJsonArray?.forEach{entries[it.asJsonObject.str("relativePath")]=it.asJsonObject.deepCopy()}
        val confirmed=mutableListOf<AssetEntity>();dao.dirtyAssets().forEach{a->val file=a.localPath?.let{path->java.io.File(path)}?.takeIf{it.isFile}?:return@forEach;val relative=safeRelative(a.relativePath);mkdirs(c,"${s.path}/attachments/${relative.substringBeforeLast('/',"")}");val tmp="${s.path}/attachments/$relative.tmp.android";c.put(file.inputStream(),tmp);c.rename(tmp,"${s.path}/attachments/$relative");val hash=sha(file.readBytes());entries[relative]=JsonObject().apply{addProperty("relativePath",relative);addProperty("contentHash",hash);addProperty("size",file.length())};confirmed+=a.copy(contentHash=hash,size=file.length(),dirty=false)}
        val manifest=JsonObject().apply{addProperty("generatedAt",swiftDate(System.currentTimeMillis()));addProperty("deviceID",deviceId());add("entries",JsonArray().apply{entries.values.forEach(::add)})};atomicWrite(c,"${s.path}/notes/assets_manifest.json",gson.toJson(manifest))
        if(confirmed.isNotEmpty())dao.putAssets(confirmed)
    }
    private suspend fun pushLibrary(c:ChannelSftp,s:SshSettings){
        val allFolders=dao.allFolders();val folders=JsonArray().apply{allFolders.filter{it.type!="encryptedFolder"}.forEach{add(JsonObject().apply{addProperty("id",it.id);addProperty("name",it.name);addProperty("sortOrder",it.sortOrder);addProperty("isSystem",false);addProperty("folderType",it.type);addProperty("updatedAt",swiftDate(it.updatedAt))})}}
        val tags=JsonArray().apply{dao.allTags().forEach{add(JsonObject().apply{addProperty("id",it.id);addProperty("name",it.name);addProperty("color",it.color);addProperty("createdAt",swiftDate(it.updatedAt));addProperty("updatedAt",swiftDate(it.updatedAt))})}}
        val tombstones=dao.tombstones();fun deleted(kind:String)=JsonArray().apply{tombstones.filter{it.itemType==kind}.forEach{t->add(JsonObject().apply{addProperty("id",t.itemId);addProperty("deletedAt",swiftDate(t.deletedAt));addProperty("deletedByDeviceID",t.deviceId)})}}
        val encryptedData=JsonObject().apply{add("encryptedFolders",JsonArray().apply{allFolders.filter{it.type=="encryptedFolder"}.forEach{f->add(JsonObject().apply{addProperty("id",f.id);addProperty("name",f.name);addProperty("order",f.sortOrder)})}});add("encryptedNoteIDs",gson.toJsonTree(encryptedNoteIds()));add("noteToFolderMapping",gson.toJsonTree(encryptedMappings()))}
        val o=JsonObject().apply{addProperty("exportedAt",swiftDate(System.currentTimeMillis()));addProperty("deviceID",deviceId());add("folders",folders);add("tags",tags);add("encryptedData",encryptedData);add("deletedFolders",deleted("folder"));add("deletedTags",deleted("tag"))}
        atomicWrite(c,"${s.path}/library.json",gson.toJson(o))
    }
    private suspend fun syncReadingPositions(c:ChannelSftp,s:SshSettings){
        val remote=readJson(c,"${s.path}/notes/reading_positions.json")
        if((remote?.get("schemaVersion")?.asInt?:ReadingPositionProtocol.SCHEMA_VERSION)>ReadingPositionProtocol.SCHEMA_VERSION)return
        val remotePositions=remote?.get("positions")?.takeIf{it.isJsonArray}?.asJsonArray?.mapNotNull{raw->runCatching{ReadingPositionProtocol.decode(raw.asJsonObject)}.getOrNull()}.orEmpty()
        val active=dao.nonDeletedNoteIds().toSet();val local=dao.allReadingPositions()
        local.filter{it.noteId !in active}.forEach{dao.deleteReadingPosition(it.noteId)}
        val merged=ReadingPositionProtocol.merge(local.filter{it.noteId in active},remotePositions.filter{it.noteId in active})
        merged.forEach{dao.putReadingPosition(it)}
        if(merged.isEmpty()&&remote==null)return
        val envelope=JsonObject().apply{addProperty("schemaVersion",ReadingPositionProtocol.SCHEMA_VERSION);addProperty("exportedAt",Instant.now().toString());addProperty("deviceID",deviceId());add("positions",JsonArray().apply{merged.forEach{add(ReadingPositionProtocol.encode(it))}})}
        atomicWrite(c,"${s.path}/notes/reading_positions.json",gson.toJson(envelope))
    }
    private suspend fun pushNotes(c:ChannelSftp,s:SshSettings){
        val lock="${s.path}/notes/.index-write.lock";var acquired=false
        for(attempt in 0 until 30){
            try{c.mkdir(lock);acquired=true;break}catch(_:Exception){runCatching{val stat=c.stat(lock);if(System.currentTimeMillis()/1000-stat.mTime>300)c.rmdir(lock)}}
            delay(1_000)
        }
        check(acquired){"远端索引正在被其他设备写入，请稍后重试"}
        try{pushNotesUnlocked(c,s)}finally{runCatching{c.rmdir(lock)}}
    }
    private suspend fun pushNotesUnlocked(c:ChannelSftp,s:SshSettings){
        val remote=readJson(c,"${s.path}/notes/index.json")
        val entries=linkedMapOf<String,JsonObject>();remote?.get("entries")?.asJsonArray?.forEach{entries[it.asJsonObject.str("noteID")]=it.asJsonObject.deepCopy()}
        val deleted=linkedMapOf<String,JsonObject>();remote?.get("deletedEntries")?.asJsonArray?.forEach{deleted[it.asJsonObject.str("noteID")]=it.asJsonObject.deepCopy()}
        val confirmed=mutableListOf<Pair<String,Long>>();dao.dirtyNoteIds().mapNotNull{loadEditable(it)?.copy(snapshotJson=loadSnapshot(it))}.filter{!it.conflict}.forEach{n->
            if(n.deletedAt!=null){deleted[n.id]=JsonObject().apply{addProperty("noteID",n.id);addProperty("deletedAt",swiftDate(n.deletedAt));addProperty("deletedByDeviceID",deviceId())};entries.remove(n.id);confirmed+=n.id to n.version}
            else {val env=toEnvelope(n);atomicWrite(c,"${s.path}/notes/${n.id}.json",gson.toJson(env));entries[n.id]=JsonObject().apply{addProperty("noteID",n.id);addProperty("version",n.version);addProperty("contentHash",sha(gson.toJson(env["currentSnapshot"])));addProperty("historyCount",env["history"]?.asJsonArray?.size()?:0)};deleted.remove(n.id);confirmed+=n.id to n.version}
        }
        val idx=JsonObject().apply{addProperty("generatedAt",swiftDate(System.currentTimeMillis()));addProperty("deviceID",deviceId());add("entries",JsonArray().apply{entries.values.forEach(::add)});add("deletedEntries",JsonArray().apply{deleted.values.forEach(::add)})}
        atomicWrite(c,"${s.path}/notes/index.json",gson.toJson(idx))
        confirmed.forEach{(id,version)->dao.markSynced(id,version)}
    }
    private suspend fun toEnvelope(n:NoteEntity):JsonObject { val noteAssets=dao.assets(n.id);val noteSteps=dao.steps(n.id);val textBlocks=BlockDocumentCodec.encodeMarkdown(n.body,"android-${n.id}");val meta=JsonObject().apply{addProperty("id",n.id);addProperty("title",n.title);addProperty("createdAt",swiftDate(n.createdAt));addProperty("updatedAt",swiftDate(n.updatedAt));n.folderId?.let{addProperty("folderID",it)};addProperty("folderName",n.folderName);n.reminderAt?.let{addProperty("reminderAt",swiftDate(it))};addProperty("recurrenceRule",n.recurrence);addProperty("version",n.version);add("tagIDs",JsonArray().apply{n.tagIds.split(',').filter{it.isNotBlank()}.forEach{add(it)}});addProperty("viewMode",n.viewMode.takeIf{it in setOf("text","preview","split")}?:"preview");addProperty("blockCount",textBlocks.size()+noteAssets.size);addProperty("assetCount",noteAssets.size);addProperty("documentPath","Notes/${n.id}/document.json");addProperty("itemType",n.itemType);n.dueAt?.let{addProperty("todoDueAt",swiftDate(it))};n.completedAt?.let{addProperty("todoCompletedAt",swiftDate(it))};addProperty("todoIsImportant",n.important);addProperty("todoOrder",0);add("linkedNoteIDs",JsonArray())}
        // Start from the imported snapshot so block types Android cannot edit (tables, assets,
        // audio and rich span attributes) survive a round trip. Metadata is authoritative locally.
        val snapshot=n.snapshotJson?.let{runCatching{JsonParser.parseString(it).asJsonObject.deepCopy()}.getOrNull()}?:JsonObject()
        snapshot.add("metadata",meta)
        val document=snapshot["document"]?.takeIf{it.isJsonObject}?.asJsonObject?:JsonObject().also{snapshot.add("document",it)}
        document.addProperty("schemaVersion",document.str("schemaVersion","1.0"));document.addProperty("source","android")
        val preserved=document["blocks"]?.asJsonArray?.filter{it.asJsonObject["text"]==null&&it.asJsonObject["asset"]==null}.orEmpty()
        val assetSnapshots=JsonArray();val assetBlocks=mutableListOf<JsonObject>();noteAssets.forEach{a->val ref=JsonObject().apply{addProperty("id",a.id);addProperty("kind",a.kind);addProperty("filename",a.filename);addProperty("mimeType",a.mimeType);add("sidecarFiles",JsonArray())};assetSnapshots.add(JsonObject().apply{add("reference",ref);addProperty("relativeFilePath",a.relativePath);add("sidecarRelativePaths",JsonArray())});assetBlocks+=JsonObject().apply{addProperty("id",UUID.randomUUID().toString());addProperty("type","attachment");add("asset",ref.deepCopy())}}
        document.add("blocks",JsonArray().apply{textBlocks.forEach(::add);preserved.forEach(::add);assetBlocks.forEach(::add)})
        snapshot.add("assets",assetSnapshots)
        snapshot.add("todoSteps",JsonArray().apply{noteSteps.forEach{step->add(JsonObject().apply{addProperty("id",step.id);addProperty("noteID",step.noteId);addProperty("text",step.text);addProperty("checked",step.checked);addProperty("order",step.sortOrder);addProperty("createdAt",swiftDate(step.createdAt))})}})
        return JsonObject().apply{addProperty("noteID",n.id);addProperty("deviceID",deviceId());addProperty("exportedAt",swiftDate(System.currentTimeMillis()));addProperty("currentVersion",n.version);add("currentSnapshot",snapshot);add("history",JsonArray());add("syncState",JsonObject().apply{addProperty("lastRecordedVersion",n.version);addProperty("lastSyncedVersion",n.version);addProperty("lastCommonVersion",n.version);addProperty("lastRemoteVersion",n.version);addProperty("remoteFingerprint","");addProperty("pendingHistoryCount",0)})}
    }
    private fun fromEnvelope(o:JsonObject):NoteEntity{val s=o["currentSnapshot"].asJsonObject;val m=s["metadata"].asJsonObject;val body=BlockDocumentCodec.decodeMarkdown(s["document"].asJsonObject["blocks"].asJsonArray);val version=m["version"].asLong;val viewMode=m.str("viewMode","preview").takeIf{it in setOf("text","preview","split")}?:"preview";return NoteEntity(id=m.str("id"),title=m.str("title"),body=body,previewText=previewText(body),createdAt=m.dateMs("createdAt"),updatedAt=m.dateMs("updatedAt"),folderId=m.optStr("folderID"),folderName=m.str("folderName","未分类"),reminderAt=m.optDateMs("reminderAt"),recurrence=m.str("recurrenceRule","none"),version=version,tagIds=m["tagIDs"]?.asJsonArray?.joinToString(","){it.asString}.orEmpty(),deletedAt=m.optDateMs("deletedAt"),itemType=m.str("itemType","note"),dueAt=m.optDateMs("todoDueAt"),completedAt=m.optDateMs("todoCompletedAt"),important=m["todoIsImportant"]?.asBoolean?:false,viewMode=viewMode,dirty=false,snapshotJson=gson.toJson(s),lastSyncedVersion=version)}
    private fun assetsFromEnvelope(o:JsonObject):List<AssetEntity>{val noteId=o.str("noteID");return o["currentSnapshot"].asJsonObject["assets"]?.asJsonArray?.mapNotNull{raw->val a=raw.asJsonObject;val ref=a["reference"]?.asJsonObject?:return@mapNotNull null;val relative=runCatching{safeRelative(a.str("relativeFilePath"))}.getOrNull()?:return@mapNotNull null;AssetEntity(ref.str("id"),noteId,ref.str("kind"),ref.str("filename"),ref.str("mimeType"),relative)}.orEmpty()}
    private fun stepsFromEnvelope(o:JsonObject):List<TodoStepEntity>{val s=o["currentSnapshot"].asJsonObject;return s["todoSteps"]?.takeIf{it.isJsonArray}?.asJsonArray?.map{raw->val x=raw.asJsonObject;TodoStepEntity(x.str("id"),x.str("noteID"),x.str("text"),x["checked"]?.asBoolean?:false,x.int("order"),x.dateMs("createdAt"))}.orEmpty()}
    private fun readJson(c:ChannelSftp,p:String)=try{val out=ByteArrayOutputStream();c.get(p,out);JsonParser.parseString(out.toString("UTF-8")).asJsonObject}catch(error:SftpException){if(error.id==ChannelSftp.SSH_FX_NO_SUCH_FILE)null else throw error}
    private fun atomicWrite(c:ChannelSftp,p:String,text:String){val bytes=text.toByteArray();val tmp="$p.tmp.android.${UUID.randomUUID()}";try{c.put(ByteArrayInputStream(bytes),tmp);val remote=ByteArrayOutputStream();c.get(tmp,remote);require(sha(remote.toByteArray())==sha(bytes)){"远端临时文件校验失败：$p"};c.rename(tmp,p)}catch(error:Throwable){runCatching{c.rm(tmp)};throw error}}
    private fun mkdirs(c:ChannelSftp,path:String){var cur="";path.replace("~",c.home).split('/').filter{it.isNotBlank()}.forEach{cur+="/$it";try{c.mkdir(cur)}catch(_:Exception){}}}
    private fun deviceId()=prefs.getString("device",null)?:UUID.randomUUID().toString().also{prefs.edit().putString("device",it).apply()}
    private fun swiftDate(ms:Long)=SwiftDateCodec.encode(ms)
    private fun sha(s:String)=MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString(""){"%02x".format(it)}
    private fun sha(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
    private fun safeRelative(path:String):String{val normalized=path.replace('\\','/').trimStart('/');require(normalized.isNotBlank()&&!normalized.split('/').any{it==".."||it.isBlank()}){"非法附件路径"};return normalized}
    private fun decodeText(bytes:ByteArray):String{
        val candidates=when{bytes.size>=2&&bytes[0]==0xFF.toByte()&&bytes[1]==0xFE.toByte()->listOf(StandardCharsets.UTF_16LE);bytes.size>=2&&bytes[0]==0xFE.toByte()&&bytes[1]==0xFF.toByte()->listOf(StandardCharsets.UTF_16BE);else->listOf(StandardCharsets.UTF_8,StandardCharsets.UTF_16LE,StandardCharsets.UTF_16BE,StandardCharsets.ISO_8859_1)}
        return candidates.firstNotNullOfOrNull{charset->runCatching{charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()}.getOrNull()}?:error("无法识别文档编码")
    }
    private fun requestSyncIfConfigured(delaySeconds:Long=1){if(!hasRemoteConfiguration())return;synchronized(syncRequestLock){syncRequestJob?.cancel();syncRequestJob=appScope.launch{delay(delaySeconds*1_000);SyncWorker.enqueueNow(appContext);synchronized(syncRequestLock){if(syncRequestJob===coroutineContext[Job])syncRequestJob=null}}}}
    private suspend fun loadEditable(id:String):NoteEntity?{val header=dao.getNoteHeader(id)?:return null;val body=readLargeText(dao.bodyLength(id)){start,length->dao.bodyChunk(id,start,length)}.orEmpty();return header.copy(body=body,previewText=header.previewText.ifBlank{previewText(body)})}
    private suspend fun loadSnapshot(id:String)=readLargeText(dao.snapshotLength(id)){start,length->dao.snapshotChunk(id,start,length)}
    private suspend fun loadConflictSnapshot(id:String)=readLargeText(dao.conflictSnapshotLength(id)){start,length->dao.conflictSnapshotChunk(id,start,length)}
    private suspend fun readLargeText(totalLength:Int?,read:suspend(Int,Int)->String?):String?{if(totalLength==null)return null;if(totalLength==0)return "";val result=StringBuilder(totalLength);var start=1;while(start<=totalLength){val chunk=read(start,minOf(64*1024,totalLength-start+1)).orEmpty();if(chunk.isEmpty())break;result.append(chunk);start+=chunk.length};return result.toString()}
    private fun encryptedPrefs(context:Context)=runCatching{createEncryptedPrefs(context)}.getOrElse{
        context.deleteSharedPreferences("ssh")
        createEncryptedPrefs(context)
    }
    private fun createEncryptedPrefs(context:Context)=EncryptedSharedPreferences.create(context,"ssh",MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
}
private fun JsonObject.str(k:String,d:String="")=get(k)?.takeUnless{it.isJsonNull}?.asString?:d
private fun JsonObject.optStr(k:String)=get(k)?.takeUnless{it.isJsonNull}?.asString
private fun JsonObject.int(k:String)=get(k)?.asInt?:0
private fun JsonObject.dateMs(k:String)=SwiftDateCodec.decode(get(k)?.asDouble?:0.0)
private fun JsonObject.optDateMs(k:String)=get(k)?.takeUnless{it.isJsonNull}?.asDouble?.let(SwiftDateCodec::decode)
private fun previewText(body:String)=body.trim().lineSequence().take(2).joinToString(" ").take(300)
private fun NoteEntity.editableUpdate()=NoteEditableUpdate(id,title,body,previewText,createdAt,updatedAt,folderId,folderName,reminderAt,recurrence,version,tagIds,deletedAt,itemType,dueAt,completedAt,important,viewMode,dirty,conflict,lastSyncedVersion)

private class FingerprintHostKeyRepository(private val expectedHost:String,private val expectedFingerprint:String):HostKeyRepository{
    var presentedFingerprint:String?=null;private set
    override fun check(host:String,key:ByteArray):Int{presentedFingerprint=sshSha256Fingerprint(key);return if(host.removePrefix("[").substringBefore("]").substringBefore(":")==expectedHost&&presentedFingerprint==expectedFingerprint)HostKeyRepository.OK else HostKeyRepository.CHANGED}
    override fun add(hostkey:HostKey?,ui:com.jcraft.jsch.UserInfo?)=Unit
    override fun remove(host:String?,type:String?)=Unit
    override fun remove(host:String?,type:String?,key:ByteArray?)=Unit
    override fun getKnownHostsRepositoryID()="Pinned SHA-256 fingerprint"
    override fun getHostKey()=emptyArray<HostKey>()
    override fun getHostKey(host:String?,type:String?)=emptyArray<HostKey>()
}
