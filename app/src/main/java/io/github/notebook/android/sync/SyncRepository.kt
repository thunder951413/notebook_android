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
import java.io.InputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

data class SshSettings(val host:String,val port:Int,val username:String,val password:String,val path:String,val fingerprint:String="")
enum class SyncBackend { API, SSH }
class HostKeyChangedException(val expected:String,val actual:String):Exception("服务器身份已变化，请确认新的主机指纹")
enum class NoteSaveState { Pending, Saving, Saved, Failed }

internal object RemoteRepositoryLockContract {
    const val DIRECTORY_NAME=".index-write.lock"
    const val OWNER_FILE_NAME="owner"
    const val MAXIMUM_ATTEMPTS=30
    const val RETRY_DELAY_MILLISECONDS=1_000L
    const val STALE_AFTER_SECONDS=1_800L
    fun lockPath(repositoryPath:String)="$repositoryPath/notes/$DIRECTORY_NAME"
}

internal object CompressedRepositoryContract {
    const val VERSION=3
    fun version(index:JsonObject?)=index?.get("repositoryFormat")?.takeIf{it.isJsonObject}?.asJsonObject?.get("version")?.asInt?:1
    fun enabled(index:JsonObject?)=index?.get("repositoryFormat")?.takeIf{it.isJsonObject}?.asJsonObject?.let{
        it["version"]?.asInt in setOf(2,VERSION)&&it["noteEncoding"]?.asString=="gzip"
    }==true
    fun notePath(root:String,noteID:String,index:JsonObject?)="$root/notes/$noteID.json${if(enabled(index))".gz" else ""}"
    fun encode(text:String):ByteArray=ByteArrayOutputStream().also{output->GZIPOutputStream(output).use{it.write(text.toByteArray(StandardCharsets.UTF_8))}}.toByteArray()
    fun decode(bytes:ByteArray):String=GZIPInputStream(ByteArrayInputStream(bytes)).use{String(it.readBytes(),StandardCharsets.UTF_8)}
}

/** Keeps the shared macOS/Web note history intact when Android publishes an edit. */
internal object LegacyHistoryContract {
    fun preserveAndAppend(
        previousEnvelope:JsonObject?,
        noteID:String,
        newVersion:Long,
        currentDeviceID:String,
        recordedAt:Double,
        contentHash:(String)->String,
    ):JsonArray {
        val result=JsonArray()
        previousEnvelope?.get("history")?.takeIf{it.isJsonArray}?.asJsonArray?.forEach{result.add(it.deepCopy())}
        val previousSnapshot=previousEnvelope?.get("currentSnapshot")?.takeIf{it.isJsonObject}?.asJsonObject
        val previousVersion=previousEnvelope?.get("currentVersion")?.takeIf{it.isJsonPrimitive}?.asLong
            ?:previousSnapshot?.get("metadata")?.asJsonObject?.get("version")?.asLong
        if(previousSnapshot!=null&&previousVersion!=null&&previousVersion<newVersion){
            val hash=contentHash(previousSnapshot.toString())
            val alreadyRecorded=result.any{entry->
                val value=entry.asJsonObject
                value["version"]?.asLong==previousVersion&&value["contentHash"]?.asString==hash
            }
            if(!alreadyRecorded){
                val metadata=previousSnapshot["metadata"]?.asJsonObject
                result.add(JsonObject().apply{
                    addProperty("id",UUID.randomUUID().toString())
                    addProperty("noteID",noteID)
                    addProperty("version",previousVersion)
                    addProperty("recordedAt",recordedAt)
                    add("updatedAt",metadata?.get("updatedAt")?.deepCopy()?:JsonPrimitive(recordedAt))
                    addProperty("title",metadata?.get("title")?.asString.orEmpty())
                    addProperty("authorDeviceID",previousEnvelope["deviceID"]?.asString?:currentDeviceID)
                    addProperty("contentHash",hash)
                    add("snapshot",previousSnapshot.deepCopy())
                })
            }
        }
        return result
    }
}

/** Compatible with the macOS SSH JSON store. Swift JSONEncoder dates are seconds since 2001-01-01. */
class SyncRepository(context:Context, private val dao:NotebookDao) {
    private val appContext=context.applicationContext
    private val gson=GsonBuilder().disableHtmlEscaping().create()
    private val appScope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val saveMutex=Mutex()
    private val syncMutex=Mutex()
    private val pendingDrafts=ConcurrentHashMap<String,NoteEntity>()
    private val draftJobs=ConcurrentHashMap<String,Job>()
    private val trashedNoteIds=ConcurrentHashMap.newKeySet<String>()
    private val syncRequestLock=Any()
    private var syncRequestJob:Job?=null
    private val _saveError=MutableStateFlow<String?>(null)
    val saveError=_saveError.asStateFlow()
    private val _noteSaveStates=MutableStateFlow<Map<String,NoteSaveState>>(emptyMap())
    val noteSaveStates=_noteSaveStates.asStateFlow()
    private data class RemoteRepositoryWriteLock(val path:String,val ownerID:String){val ownerPath="$path/${RemoteRepositoryLockContract.OWNER_FILE_NAME}"}
    private fun setSaveState(noteId:String,state:NoteSaveState){_noteSaveStates.update{it+mapOf(noteId to state)}}
    fun markUnsaved(noteId:String){setSaveState(noteId,NoteSaveState.Pending)}
    fun acknowledgeSaved(noteId:String){_noteSaveStates.update{states->if(states[noteId]==NoteSaveState.Saved)states-noteId else states}}
    fun clearSaveError(){_saveError.value=null}
    private val prefs=encryptedPrefs(context)
    private val apiSync=ApiSyncClient(appContext,dao)
    val notes=dao.observeNoteSummaries()
    val folders=dao.observeFolders()
    val tags=dao.observeTags()
    fun settings()=SshSettings(prefs.getString("host","")!!,prefs.getInt("port",22),prefs.getString("user","")!!,prefs.getString("password","")!!,prefs.getString("path","~/notebook_backup")!!,prefs.getString("fingerprint","")!!)
    fun apiSettings()=ApiSyncSettings(prefs.getString("apiBaseUrl","")!!,prefs.getString("apiWorkspaceId","00000000-0000-4000-8000-000000000001")!!,prefs.getString("apiToken","")!!)
    /** SSH/SFTP is the only supported cross-device repository protocol. */
    fun syncBackend()=SyncBackend.SSH
    fun saveSettings(s:SshSettings){prefs.edit().putString("syncBackend",SyncBackend.SSH.name).putString("host",s.host).putInt("port",s.port).putString("user",s.username).putString("password",s.password).putString("path",s.path).putString("fingerprint",s.fingerprint.trim()).apply()}
    fun saveApiSettings(s:ApiSyncSettings){prefs.edit().putString("syncBackend",SyncBackend.API.name).putString("apiBaseUrl",s.baseUrl.trim()).putString("apiWorkspaceId",s.workspaceId.trim()).putString("apiToken",s.token.trim()).apply()}
    fun setSyncBackend(backend:SyncBackend){prefs.edit().putString("syncBackend",backend.name).apply()}
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
    fun queueDraft(n:NoteEntity){if(n.deletedAt==null&&trashedNoteIds.contains(n.id))return;setSaveState(n.id,NoteSaveState.Pending);pendingDrafts[n.id]=n;draftJobs.remove(n.id)?.cancel();val job=appScope.launch{try{dao.putDraft(DraftEntity(n.id,gson.toJson(n)));delay(600);val latest=pendingDrafts[n.id]?:return@launch;setSaveState(n.id,NoteSaveState.Saving);persistDraft(latest);if(pendingDrafts.remove(n.id,latest))setSaveState(n.id,NoteSaveState.Saved)}catch(error:Throwable){if(error is CancellationException)throw error;setSaveState(n.id,NoteSaveState.Failed);_saveError.value="自动保存失败：${error.localizedMessage}"}};draftJobs[n.id]=job;job.invokeOnCompletion{draftJobs.remove(n.id,job)}}
    fun flushDraft(n:NoteEntity):Job{if(n.deletedAt==null&&trashedNoteIds.contains(n.id))return appScope.launch{};setSaveState(n.id,NoteSaveState.Saving);pendingDrafts[n.id]=n;draftJobs.remove(n.id)?.cancel();val job=appScope.launch{try{dao.putDraft(DraftEntity(n.id,gson.toJson(n)));val latest=pendingDrafts[n.id]?:n;persistDraft(latest);if(pendingDrafts.remove(n.id,latest))setSaveState(n.id,NoteSaveState.Saved)}catch(error:Throwable){if(error is CancellationException)throw error;setSaveState(n.id,NoteSaveState.Failed);_saveError.value="保存失败，草稿仍保留：${error.localizedMessage}"}};draftJobs[n.id]=job;job.invokeOnCompletion{draftJobs.remove(n.id,job)};return job}
    fun flushAllAsync(){pendingDrafts.values.toList().forEach(::flushDraft)}
    suspend fun flushAll(){val drafts=pendingDrafts.values.toList();drafts.forEach{draftJobs.remove(it.id)?.cancel()};drafts.forEach{draft->setSaveState(draft.id,NoteSaveState.Saving);try{dao.putDraft(DraftEntity(draft.id,gson.toJson(draft)));persistDraft(draft);if(pendingDrafts.remove(draft.id,draft))setSaveState(draft.id,NoteSaveState.Saved)}catch(error:Throwable){setSaveState(draft.id,NoteSaveState.Failed);throw error}}}
    suspend fun recoverDrafts(){dao.draftNoteIds().forEach{id->readLargeText(dao.draftPayloadLength(id)){start,length->dao.draftPayloadChunk(id,start,length)}?.let{payload->runCatching{gson.fromJson(payload,NoteEntity::class.java)}.getOrNull()?.let{persistDraft(it)}}}}
    fun recoverDraftsAsync(){appScope.launch{runCatching{recoverDrafts()}.onFailure{_saveError.value="恢复本地草稿失败：${it.localizedMessage}"}}}

    suspend fun readingPosition(noteId:String)=withContext(Dispatchers.IO){dao.readingPosition(noteId)}
    fun recordReadingPosition(noteId:String,anchorUtf16Offset:Int,viewportOffsetFraction:Double){appScope.launch{
        val anchor=anchorUtf16Offset.coerceAtLeast(0);val fraction=viewportOffsetFraction.coerceIn(-1.0,1.0);val current=dao.readingPosition(noteId)
        if(current?.anchorUtf16Offset==anchor&&kotlin.math.abs(current.viewportOffsetFraction-fraction)<0.001)return@launch
        dao.putReadingPosition(ReadingPositionEntity(noteId,anchor,fraction,System.currentTimeMillis(),deviceId()))
        if(syncBackend()==SyncBackend.API)apiSync.queueReadingPosition(apiSettings().workspaceId,dao.readingPosition(noteId)!!)
        requestSyncIfConfigured(2)
    }}

    private suspend fun persistDraft(draft:NoteEntity):NoteEntity=saveMutex.withLock{
        val current=loadEditable(draft.id)
        if(current!=null&&sameEditableContent(current,draft)){dao.deleteDraft(draft.id);return@withLock current}
        val base=current?:draft;val saved=base.copy(title=draft.title,body=draft.body,previewText=previewText(draft.body),folderId=draft.folderId,folderName=draft.folderName,reminderAt=draft.reminderAt,recurrence=draft.recurrence,tagIds=draft.tagIds,deletedAt=draft.deletedAt,itemType=draft.itemType,dueAt=draft.dueAt,completedAt=draft.completedAt,important=draft.important,viewMode=draft.viewMode,updatedAt=System.currentTimeMillis(),version=(current?.version?:draft.version)+1,dirty=true)
        if(current==null)dao.put(saved)else dao.updateEditable(saved.editableUpdate());dao.deleteDraft(draft.id);if(syncBackend()==SyncBackend.API){val workspace=apiSettings().workspaceId;apiSync.queueNote(workspace,saved);val oldTags=current?.tagIds?.split(',')?.filter(String::isNotBlank)?.toSet().orEmpty();val newTags=saved.tagIds.split(',').filter(String::isNotBlank).toSet();(oldTags-newTags).forEach{apiSync.queueDelete(workspace,"page_tag","${saved.id}:$it")}};if(saved.deletedAt!=null||saved.completedAt!=null||saved.reminderAt==null)Reminders.cancel(appContext,saved.id)else Reminders.schedule(appContext,saved.id,saved.title,saved.reminderAt,saved.recurrence);requestSyncIfConfigured();saved
    }
    private fun sameEditableContent(a:NoteEntity,b:NoteEntity)=a.title==b.title&&a.body==b.body&&a.folderId==b.folderId&&a.folderName==b.folderName&&a.reminderAt==b.reminderAt&&a.recurrence==b.recurrence&&a.tagIds==b.tagIds&&a.deletedAt==b.deletedAt&&a.itemType==b.itemType&&a.dueAt==b.dueAt&&a.completedAt==b.completedAt&&a.important==b.important&&a.viewMode==b.viewMode
    suspend fun moveToTrash(id:String){
        trashedNoteIds.add(id)
        try{
            val pending=pendingDrafts.remove(id)
            draftJobs.remove(id)?.cancelAndJoin()
            val current=pending?:loadEditable(id)?:run{trashedNoteIds.remove(id);return}
            save(current.copy(deletedAt=System.currentTimeMillis()))
        }catch(error:Throwable){trashedNoteIds.remove(id);throw error}
    }
    suspend fun restore(id:String){trashedNoteIds.remove(id);loadEditable(id)?.let{save(it.copy(deletedAt=null))}}
    suspend fun deletePermanently(id:String){Reminders.cancel(appContext,id);dao.putTombstone(TombstoneEntity("note|$id",id,"note",System.currentTimeMillis(),deviceId()));if(syncBackend()==SyncBackend.API){val w=apiSettings().workspaceId;apiSync.queueDelete(w,"document",id);apiSync.queueDelete(w,"page",id)};dao.deleteReadingPosition(id);dao.deleteNotePermanently(id);requestSyncIfConfigured()}
    suspend fun keepLocal(id:String){loadEditable(id)?.let{local->if(syncBackend()==SyncBackend.API){val kept=local.copy(updatedAt=System.currentTimeMillis(),dirty=true,conflict=false,conflictSnapshotJson=null);dao.put(kept);apiSync.queueNote(apiSettings().workspaceId,kept);requestSyncIfConfigured()}else{val remoteVersion=loadConflictSnapshot(id)?.let{payload->runCatching{val remote=JsonParser.parseString(payload).asJsonObject;if(remote["schemaVersion"]?.asInt==3)remote["metadata"].asJsonObject["legacyVersion"]?.asLong?:remote["metadata"].asJsonObject.isoMs("updatedAt") else remote["metadata"].asJsonObject["version"].asLong}.getOrNull()}?:0;dao.put(local.copy(snapshotJson=loadSnapshot(id),version=maxOf(local.version,remoteVersion)+1,updatedAt=System.currentTimeMillis(),dirty=true,conflict=false,conflictSnapshotJson=null))}}}
    suspend fun acceptRemote(id:String){val local=loadEditable(id)?:return;val snapshot=loadConflictSnapshot(id)?.let{runCatching{JsonParser.parseString(it).asJsonObject}.getOrNull()}?:return;if(syncBackend()==SyncBackend.API&&snapshot.has("apiConflicts")){snapshot["apiConflicts"].asJsonObject.entrySet().forEach{(type,payload)->apiSync.acceptConflict(apiSettings().workspaceId,type,id,payload.asJsonObject)}}else if(syncBackend()==SyncBackend.API&&snapshot.has("apiEntityType")){apiSync.acceptConflict(apiSettings().workspaceId,snapshot["apiEntityType"].asString,id,snapshot["payload"].asJsonObject)}else{val remote=if(snapshot["schemaVersion"]?.asInt==3)fromMarkdownEnvelope(snapshot)else fromEnvelope(JsonObject().apply{addProperty("noteID",id);add("currentSnapshot",snapshot)});dao.put(remote.copy(version=maxOf(local.version,remote.version)+1,dirty=true,conflict=false,conflictSnapshotJson=null,lastSyncedVersion=local.lastSyncedVersion))}}
    suspend fun saveFolder(name:String,type:String="noteFolder",id:String=UUID.randomUUID().toString()){val folder=FolderEntity(id,name,dao.allFolders().size,type);dao.putFolder(folder);if(syncBackend()==SyncBackend.API)apiSync.queueFolder(apiSettings().workspaceId,folder);requestSyncIfConfigured()}
    suspend fun deleteFolder(id:String){if(syncBackend()==SyncBackend.API)apiSync.queueDelete(apiSettings().workspaceId,"section",id);dao.deleteFolder(id);dao.putTombstone(TombstoneEntity("folder|$id",id,"folder",System.currentTimeMillis(),deviceId()));requestSyncIfConfigured()}
    suspend fun saveTag(name:String,color:String="gray",id:String=UUID.randomUUID().toString()){val tag=TagEntity(id,name,color);dao.putTag(tag);if(syncBackend()==SyncBackend.API)apiSync.queueTag(apiSettings().workspaceId,tag);requestSyncIfConfigured()}
    suspend fun deleteTag(id:String){if(syncBackend()==SyncBackend.API)apiSync.queueDelete(apiSettings().workspaceId,"tag",id);dao.deleteTag(id);dao.putTombstone(TombstoneEntity("tag|$id",id,"tag",System.currentTimeMillis(),deviceId()));requestSyncIfConfigured()}
    fun steps(noteId:String)=dao.observeSteps(noteId)
    fun assets(noteId:String)=dao.observeAssets(noteId)
    suspend fun saveStep(step:TodoStepEntity){dao.putStep(step);if(syncBackend()==SyncBackend.API)apiSync.queueStep(apiSettings().workspaceId,step);loadEditable(step.noteId)?.let{save(it)};requestSyncIfConfigured()}
    suspend fun deleteStep(id:String){val noteId=dao.getStep(id)?.noteId;if(syncBackend()==SyncBackend.API)apiSync.queueDelete(apiSettings().workspaceId,"task_step",id);dao.deleteStep(id);noteId?.let{loadEditable(it)?.let{n->save(n)}}}
    suspend fun attach(noteId:String,uri:android.net.Uri):AssetEntity=withContext(Dispatchers.IO){
        val resolver=appContext.contentResolver;val id=UUID.randomUUID().toString();val mime=resolver.getType(uri)?:"application/octet-stream"
        var name="attachment";resolver.query(uri,arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),null,null,null)?.use{if(it.moveToFirst())name=it.getString(0)?:name}
        name=name.replace(Regex("[^A-Za-z0-9._\\-\\u4e00-\\u9fff]"),"_");val relative="$noteId/$id-$name";val target=java.io.File(appContext.filesDir,"attachments/$relative");target.parentFile?.mkdirs();resolver.openInputStream(uri).use{input->requireNotNull(input){"无法读取附件"}.copyTo(target.outputStream())}
        val kind=when{mime.startsWith("image/")->"image";mime.startsWith("audio/")->"audio";else->"file"};AssetEntity(id,noteId,kind,name,mime,relative,target.absolutePath,sha(target.readBytes()),target.length(),true).also{dao.putAssets(listOf(it));if(syncBackend()==SyncBackend.API)apiSync.queueAsset(apiSettings().workspaceId,it);loadEditable(noteId)?.let{n->save(n)};requestSyncIfConfigured()}
    }
    fun recordingFile(noteId:String)=java.io.File(appContext.filesDir,"recordings/$noteId/${System.currentTimeMillis()}.m4a")
    suspend fun addRecordedAudio(noteId:String,file:java.io.File):AssetEntity=withContext(Dispatchers.IO){require(file.isFile&&file.length()>0){"录音文件为空"};val id=UUID.randomUUID().toString();val relative="$noteId/$id-${file.name}";val target=java.io.File(appContext.filesDir,"attachments/$relative");target.parentFile?.mkdirs();file.copyTo(target,true);file.delete();AssetEntity(id,noteId,"audio",target.name,"audio/mp4",relative,target.absolutePath,sha(target.readBytes()),target.length(),true).also{dao.putAssets(listOf(it));if(syncBackend()==SyncBackend.API)apiSync.queueAsset(apiSettings().workspaceId,it);loadEditable(noteId)?.let{n->save(n)};requestSyncIfConfigured()}}
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
        if(syncBackend()==SyncBackend.API){apiSync.sync(apiSettings());Reminders.reconcile(appContext,dao.reminders());return@withContext}
        val s=settings(); require(s.host.isNotBlank()&&s.username.isNotBlank()){ "请先配置 SSH 服务器" }
        val verifier=s.fingerprint.takeIf{it.isNotBlank()}?.let{FingerprintHostKeyRepository(s.host,it)}
        val jsch=JSch().apply{verifier?.let{hostKeyRepository=it}}
        val session=jsch.getSession(s.username,s.host,s.port).apply{
            setPassword(s.password)
            // Android runtimes do not consistently expose an Ed25519 provider.
            // Prefer the broadly supported ECDSA host key, then fall back to the
            // remaining modern algorithms. The exact negotiated key is pinned.
            setConfig("server_host_key","ecdsa-sha2-nistp256,ssh-ed25519,rsa-sha2-512,rsa-sha2-256,ssh-rsa")
            setConfig("StrictHostKeyChecking",if(verifier!=null)"yes" else "no")
        }
        try{session.connect(15_000)}catch(error:com.jcraft.jsch.JSchException){verifier?.presentedFingerprint?.takeIf{it!=s.fingerprint}?.let{throw HostKeyChangedException(s.fingerprint,it)};throw error}
        if(s.fingerprint.isBlank())saveSettings(s.copy(fingerprint=sshSha256Fingerprint(java.util.Base64.getDecoder().decode(session.hostKey.key))))
        val ch=(session.openChannel("sftp") as ChannelSftp).apply{connect(15_000)}
        val resolved=s.copy(path=resolveRemoteRepositoryPath(s.path,ch.home))
        try {
            mkdirs(ch,resolved.path);mkdirs(ch,"${resolved.path}/notes");mkdirs(ch,"${resolved.path}/attachments")
            withRemoteRepositoryWriteLock(ch,resolved){lock->
                pullLibrary(ch,resolved);pullNotes(ch,resolved);pullAssets(ch,resolved);syncReadingPositions(ch,resolved)
                refreshRemoteRepositoryWriteLock(ch,lock)
                // Assets and metadata must exist before note JSON and index.json publish references to them.
                pushLibrary(ch,resolved);pushAssets(ch,resolved)
                refreshRemoteRepositoryWriteLock(ch,lock)
                pushNotes(ch,resolved)
                Reminders.reconcile(appContext,dao.reminders())
            }
        } finally { ch.disconnect();session.disconnect() }
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
            val deletion=entry.asJsonObject; val id=deletion.str("noteID"); val deletedAt=deletion.flexibleDateMs("deletedAt")
            val local=loadEditable(id)
            if(local!=null && (!local.dirty || deletedAt>=local.updatedAt)) dao.put(local.copy(deletedAt=deletedAt,dirty=false))
        }
        idx["entries"]?.asJsonArray?.forEach { e ->
            val id=e.asJsonObject.str("noteID");val remoteVersion=e.asJsonObject["version"].asLong;val local=loadEditable(id)
            if(local==null||(!local.dirty&&remoteVersion>local.version)) readNoteJson(c,s,id,idx)?.let{env->dao.put(decodeEnvelope(env));dao.putAssets(decodedAssets(env));decodedSteps(env).forEach{dao.putStep(it)}}
            else if(local.dirty&&remoteVersion>local.lastSyncedVersion) readNoteJson(c,s,id,idx)?.let{remote->
                if(remote["schemaVersion"]?.asInt==3){
                    val remoteHash=remote.str("currentContentHash")
                    val baselineHash=loadSnapshot(id)?.let{snapshot->runCatching{JsonParser.parseString(snapshot).asJsonObject["currentContentHash"]?.asString}.getOrNull()}
                    val storedConflictHash=loadConflictSnapshot(id)?.let{snapshot->runCatching{JsonParser.parseString(snapshot).asJsonObject["currentContentHash"]?.asString}.getOrNull()}
                    val legacyVersion=remote["metadata"].asJsonObject["legacyVersion"]?.asLong
                    val unchangedBaseline=baselineHash==remoteHash||(local.conflict&&legacyVersion==local.lastSyncedVersion&&storedConflictHash==remoteHash)
                    when{
                        remoteHash==sha(local.body)->dao.put(local.copy(version=remoteVersion,dirty=false,conflict=false,conflictSnapshotJson=null,snapshotJson=gson.toJson(remote),lastSyncedVersion=remoteVersion))
                        unchangedBaseline->dao.put(local.copy(version=maxOf(local.version,remoteVersion)+1,conflict=false,conflictSnapshotJson=null))
                        else->dao.put(local.copy(conflict=true,conflictSnapshotJson=gson.toJson(remote)))
                    }
                }else dao.put(local.copy(conflict=true,conflictSnapshotJson=gson.toJson(remote["currentSnapshot"])))
            }
        }
    }
    private suspend fun pullAssets(c:ChannelSftp,s:SshSettings){
        val manifest=readJson(c,"${s.path}/notes/assets_manifest.json")?:return
        val known=dao.allNoteIds().flatMap{dao.assets(it)}.associateBy{it.relativePath}
        manifest["entries"]?.asJsonArray?.forEach{raw->val e=raw.asJsonObject;val relative=safeRelative(e.str("relativePath"));val expected=e.str("contentHash");val asset=known[relative]?:return@forEach;val target=java.io.File(appContext.filesDir,"attachments/$relative");if(!target.exists()||sha(target.readBytes())!=expected){target.parentFile?.mkdirs();val tmp=java.io.File(target.parentFile,"${target.name}.download");val remotePath=if(relative.startsWith("objects/"))"${s.path}/$relative" else "${s.path}/attachments/$relative";c.get(remotePath,tmp.outputStream());require(sha(tmp.readBytes())==expected){"附件校验失败：$relative"};if(!tmp.renameTo(target)){tmp.copyTo(target,true);tmp.delete()};dao.putAssets(listOf(asset.copy(localPath=target.absolutePath,contentHash=expected,size=target.length(),dirty=false)))}}
    }
    private suspend fun pushAssets(c:ChannelSftp,s:SshSettings){
        val remote=readJson(c,"${s.path}/notes/assets_manifest.json");val index=readJson(c,"${s.path}/notes/index.json");val contentAddressed=CompressedRepositoryContract.version(index)==3;val entries=linkedMapOf<String,JsonObject>();remote?.get("entries")?.asJsonArray?.forEach{entries[it.asJsonObject.str("relativePath")]=it.asJsonObject.deepCopy()}
        val confirmed=mutableListOf<AssetEntity>();dao.dirtyAssets().forEach{a->val file=a.localPath?.let{path->java.io.File(path)}?.takeIf{it.isFile}?:error("本地附件文件缺失，已取消远程发布");val hash=file.inputStream().use(::sha);val relative=if(contentAddressed)"objects/${hash.take(2)}/$hash" else safeRelative(a.relativePath);val target=if(contentAddressed)"${s.path}/$relative" else "${s.path}/attachments/$relative";mkdirs(c,target.substringBeforeLast('/'));atomicUpload(c,target,file,hash);entries[relative]=JsonObject().apply{addProperty("relativePath",relative);addProperty("contentHash",hash);addProperty("size",file.length())};confirmed+=a.copy(relativePath=relative,contentHash=hash,size=file.length(),dirty=false)}
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
    private suspend fun <T> withRemoteRepositoryWriteLock(
        c:ChannelSftp,
        s:SshSettings,
        block:suspend (RemoteRepositoryWriteLock)->T
    ):T{
        val lock=acquireRemoteRepositoryWriteLock(c,s)
        return try{
            val result=block(lock)
            releaseRemoteRepositoryWriteLock(c,lock)
            result
        }catch(error:Throwable){
            runCatching{releaseRemoteRepositoryWriteLock(c,lock)}
            throw error
        }
    }
    private suspend fun acquireRemoteRepositoryWriteLock(c:ChannelSftp,s:SshSettings):RemoteRepositoryWriteLock{
        val lock=RemoteRepositoryWriteLock(RemoteRepositoryLockContract.lockPath(s.path),UUID.randomUUID().toString())
        var observedOwner:String?=null
        var observedAgeSeconds:Long?=null
        repeat(RemoteRepositoryLockContract.MAXIMUM_ATTEMPTS){attempt->
            if(runCatching{c.mkdir(lock.path)}.isSuccess){
                try{
                    c.put(ByteArrayInputStream("${lock.ownerID}\n".toByteArray(StandardCharsets.UTF_8)),lock.ownerPath)
                    return lock
                }catch(error:Throwable){
                    runCatching{c.rm(lock.ownerPath)};runCatching{c.rmdir(lock.path)}
                    throw error
                }
            }
            runCatching{
                observedOwner=readRemoteLockOwner(c,lock)
                val modifiedAt=maxOf(c.stat(lock.path).mTime.toLong(),runCatching{c.stat(lock.ownerPath).mTime.toLong()}.getOrDefault(0L))
                val ageSeconds=(System.currentTimeMillis()/1_000-modifiedAt).coerceAtLeast(0)
                observedAgeSeconds=ageSeconds
                if(ageSeconds>RemoteRepositoryLockContract.STALE_AFTER_SECONDS){
                    val quarantine="${lock.path}.stale-${lock.ownerID}"
                    c.rename(lock.path,quarantine)
                    runCatching{c.rm("$quarantine/${RemoteRepositoryLockContract.OWNER_FILE_NAME}")}
                    runCatching{c.rmdir(quarantine)}
                }
            }
            if(attempt<RemoteRepositoryLockContract.MAXIMUM_ATTEMPTS-1)delay(RemoteRepositoryLockContract.RETRY_DELAY_MILLISECONDS)
        }
        val ownerHint=observedOwner?.take(8)?.let{"（锁 $it）"}.orEmpty()
        val duration=observedAgeSeconds?.let{"，已持续约 $it 秒"}.orEmpty()
        error("远端仓库正在由其他设备同步$ownerHint$duration；应用会在锁超过 30 分钟且确认陈旧后自动恢复，请稍后重试")
    }
    private fun refreshRemoteRepositoryWriteLock(c:ChannelSftp,lock:RemoteRepositoryWriteLock){
        check(readRemoteLockOwner(c,lock)==lock.ownerID){"远程发布锁所有权已变化，已停止同步"}
        c.setMtime(lock.path,(System.currentTimeMillis()/1_000).toInt())
    }
    private fun releaseRemoteRepositoryWriteLock(c:ChannelSftp,lock:RemoteRepositoryWriteLock){
        if(readRemoteLockOwner(c,lock)!=lock.ownerID)return
        c.rm(lock.ownerPath)
        c.rmdir(lock.path)
    }
    private fun readRemoteLockOwner(c:ChannelSftp,lock:RemoteRepositoryWriteLock):String?=runCatching{
        val output=ByteArrayOutputStream();c.get(lock.ownerPath,output);String(output.toByteArray(),StandardCharsets.UTF_8).trim()
    }.getOrNull()
    private suspend fun pushNotes(c:ChannelSftp,s:SshSettings){
        val remote=readJson(c,"${s.path}/notes/index.json")
        val compressed=CompressedRepositoryContract.enabled(remote)
        val entries=linkedMapOf<String,JsonObject>();remote?.get("entries")?.asJsonArray?.forEach{entries[it.asJsonObject.str("noteID")]=it.asJsonObject.deepCopy()}
        val deleted=linkedMapOf<String,JsonObject>();remote?.get("deletedEntries")?.asJsonArray?.forEach{deleted[it.asJsonObject.str("noteID")]=it.asJsonObject.deepCopy()}
        dao.tombstones().filter{it.itemType=="note"}.forEach{t->deleted[t.itemId]=JsonObject().apply{addProperty("noteID",t.itemId);addProperty("deletedAt",swiftDate(t.deletedAt));addProperty("deletedByDeviceID",t.deviceId)};entries.remove(t.itemId)}
        val confirmed=mutableListOf<Triple<String,Long,String?>>();dao.dirtyNoteIds().mapNotNull{loadEditable(it)?.copy(snapshotJson=loadSnapshot(it))}.filter{!it.conflict}.forEach{n->
            if(n.deletedAt!=null){deleted[n.id]=JsonObject().apply{addProperty("noteID",n.id);addProperty("deletedAt",swiftDate(n.deletedAt));addProperty("deletedByDeviceID",deviceId())};entries.remove(n.id);confirmed+=Triple(n.id,n.version,null)}
            else {val previousEnvelope=readNoteJson(c,s,n.id,remote);val env=if(CompressedRepositoryContract.version(remote)==3)toMarkdownEnvelope(n,previousEnvelope)else toEnvelope(n,previousEnvelope);val text=gson.toJson(env);val path=CompressedRepositoryContract.notePath(s.path,n.id,remote);if(compressed)atomicWrite(c,path,CompressedRepositoryContract.encode(text))else atomicWrite(c,path,text);val contentHash=env["currentContentHash"]?.asString?:sha(gson.toJson(env["currentSnapshot"]));entries[n.id]=JsonObject().apply{addProperty("noteID",n.id);addProperty("version",n.version);addProperty("contentHash",contentHash);addProperty("historyCount",env["history"]?.asJsonArray?.size()?:0)};deleted.remove(n.id);val snapshot=if(env["schemaVersion"]?.asInt==3)text else gson.toJson(env["currentSnapshot"]);confirmed+=Triple(n.id,n.version,snapshot)}
        }
        val idx=JsonObject().apply{addProperty("generatedAt",swiftDate(System.currentTimeMillis()));addProperty("deviceID",deviceId());add("entries",JsonArray().apply{entries.values.forEach(::add)});add("deletedEntries",JsonArray().apply{deleted.values.forEach(::add)});remote?.get("repositoryFormat")?.takeIf{it.isJsonObject}?.let{add("repositoryFormat",it.deepCopy())}}
        atomicWrite(c,"${s.path}/notes/index.json",gson.toJson(idx))
        confirmed.forEach{(id,version,snapshot)->dao.markSynced(id,version,snapshot)}
    }
    private suspend fun toMarkdownEnvelope(n:NoteEntity,previous:JsonObject?):JsonObject {
        val currentHash=sha(n.body)
        val contents=previous?.get("contentObjects")?.takeIf{it.isJsonObject}?.asJsonObject?.deepCopy()?:JsonObject()
        val history=previous?.get("history")?.takeIf{it.isJsonArray}?.asJsonArray?.deepCopy()?:JsonArray()
        val previousHash=previous?.get("currentContentHash")?.takeUnless{it.isJsonNull}?.asString
        val lastHistoryHash=if(history.size()>0)history[history.size()-1].asJsonObject["contentHash"]?.asString else null
        if(previousHash!=null&&previousHash!=currentHash&&lastHistoryHash!=previousHash) history.add(JsonObject().apply{addProperty("id",UUID.randomUUID().toString());addProperty("createdAt",iso(n.updatedAt));addProperty("updatedAt",iso(n.updatedAt));addProperty("reason","autosave");addProperty("contentHash",previousHash)})
        contents.addProperty(currentHash,n.body)
        val metadata=JsonObject().apply{
            addProperty("id",n.id);addProperty("workspaceId","00000000-0000-4000-8000-000000000001");n.folderId?.let{addProperty("sectionId",it)}
            addProperty("kind",if(n.itemType=="todo")"task" else "document");addProperty("title",n.title);addProperty("preview",n.previewText);addProperty("favorite",n.important)
            addProperty("createdAt",iso(n.createdAt));addProperty("updatedAt",iso(n.updatedAt));addProperty("syncStatus","synced")
            n.reminderAt?.let{addProperty("reminderAt",iso(it))};addProperty("recurrenceRule",n.recurrence);n.dueAt?.let{addProperty("dueAt",iso(it))};n.completedAt?.let{addProperty("completedAt",iso(it))};addProperty("important",n.important);addProperty("legacyVersion",n.version)
        }
        val assets=JsonArray().apply{dao.assets(n.id).forEach{a->add(JsonObject().apply{addProperty("id",a.id);addProperty("pageId",a.noteId);addProperty("kind",a.kind);addProperty("filename",a.filename);addProperty("mimeType",a.mimeType);addProperty("byteSize",a.size);addProperty("checksum",a.contentHash);addProperty("objectHash",a.contentHash);addProperty("localPath",a.relativePath);addProperty("createdAt",iso(n.createdAt))})}}
        val steps=JsonArray().apply{dao.steps(n.id).forEach{step->add(JsonObject().apply{addProperty("id",step.id);addProperty("pageId",step.noteId);addProperty("text",step.text);addProperty("checked",step.checked);addProperty("sortOrder",step.sortOrder);addProperty("createdAt",iso(step.createdAt))})}}
        return JsonObject().apply{addProperty("schemaVersion",3);addProperty("noteID",n.id);add("metadata",metadata);addProperty("currentContentHash",currentHash);add("contentObjects",contents);add("history",history);add("assets",assets);add("taskSteps",steps);add("tagIDs",JsonArray().apply{n.tagIds.split(',').filter{it.isNotBlank()}.forEach(::add)});add("linkedPageIDs",JsonArray())}
    }
    private suspend fun toEnvelope(n:NoteEntity,previousEnvelope:JsonObject?=null):JsonObject { val noteAssets=dao.assets(n.id);val noteSteps=dao.steps(n.id);val textBlocks=BlockDocumentCodec.encodeMarkdown(n.body,"android-${n.id}");val meta=JsonObject().apply{addProperty("id",n.id);addProperty("title",n.title);addProperty("createdAt",swiftDate(n.createdAt));addProperty("updatedAt",swiftDate(n.updatedAt));n.folderId?.let{addProperty("folderID",it)};addProperty("folderName",n.folderName);n.reminderAt?.let{addProperty("reminderAt",swiftDate(it))};addProperty("recurrenceRule",n.recurrence);addProperty("version",n.version);add("tagIDs",JsonArray().apply{n.tagIds.split(',').filter{it.isNotBlank()}.forEach{add(it)}});addProperty("viewMode",n.viewMode.takeIf{it in setOf("text","preview","split")}?:"preview");addProperty("blockCount",textBlocks.size()+noteAssets.size);addProperty("assetCount",noteAssets.size);addProperty("documentPath","Notes/${n.id}/document.json");addProperty("itemType",n.itemType);n.dueAt?.let{addProperty("todoDueAt",swiftDate(it))};n.completedAt?.let{addProperty("todoCompletedAt",swiftDate(it))};addProperty("todoIsImportant",n.important);addProperty("todoOrder",0);add("linkedNoteIDs",JsonArray())}
        // Start from the imported snapshot so block types Android cannot edit (tables, assets,
        // audio and rich span attributes) survive a round trip. Metadata is authoritative locally.
        val snapshot=n.snapshotJson?.let{runCatching{JsonParser.parseString(it).asJsonObject.deepCopy()}.getOrNull()}?:JsonObject()
        snapshot.add("metadata",meta)
        val document=snapshot["document"]?.takeIf{it.isJsonObject}?.asJsonObject?:JsonObject().also{snapshot.add("document",it)}
        document.addProperty("schemaVersion",document.str("schemaVersion","1.0"));document.addProperty("source","android")
        val preserved=document["blocks"]?.asJsonArray?.filter{it.asJsonObject["text"]==null&&it.asJsonObject["asset"]==null}.orEmpty()
        val assetSnapshots=JsonArray();val assetBlocks=mutableListOf<JsonObject>();noteAssets.forEach{a->val ref=JsonObject().apply{addProperty("id",a.id);addProperty("kind",a.kind);addProperty("filename",a.filename);addProperty("mimeType",a.mimeType);add("sidecarFiles",JsonArray())};assetSnapshots.add(JsonObject().apply{add("reference",ref);addProperty("relativeFilePath",a.relativePath);add("sidecarRelativePaths",JsonArray())});assetBlocks+=JsonObject().apply{addProperty("id",UUID.randomUUID().toString());addProperty("type","attachment");add("asset",ref.deepCopy())}}
        document.add("blocks",JsonArray().apply{textBlocks.forEach(::add);preserved.forEach(::add);assetBlocks.forEach(::add)})
        // Android edits the shared legacy blocks. A cached Tiptap tree from Web/Electron
        // now describes the previous body and must not win when those clients import it.
        document.remove("notebookNextTiptap")
        snapshot.add("assets",assetSnapshots)
        snapshot.add("todoSteps",JsonArray().apply{noteSteps.forEach{step->add(JsonObject().apply{addProperty("id",step.id);addProperty("noteID",step.noteId);addProperty("text",step.text);addProperty("checked",step.checked);addProperty("order",step.sortOrder);addProperty("createdAt",swiftDate(step.createdAt))})}})
        val currentDeviceID=deviceId()
        val history=LegacyHistoryContract.preserveAndAppend(previousEnvelope,n.id,n.version,currentDeviceID,swiftDate(System.currentTimeMillis())){sha(it)}
        return JsonObject().apply{addProperty("noteID",n.id);addProperty("deviceID",currentDeviceID);addProperty("exportedAt",swiftDate(System.currentTimeMillis()));addProperty("currentVersion",n.version);add("currentSnapshot",snapshot);add("history",history);add("syncState",JsonObject().apply{addProperty("lastRecordedVersion",n.version);addProperty("lastSyncedVersion",n.version);addProperty("lastCommonVersion",n.version);addProperty("lastRemoteVersion",n.version);addProperty("remoteFingerprint","");addProperty("pendingHistoryCount",0)})}
    }
    private fun decodeEnvelope(o:JsonObject)=if(o["schemaVersion"]?.asInt==3)fromMarkdownEnvelope(o)else fromEnvelope(o)
    private fun fromMarkdownEnvelope(o:JsonObject):NoteEntity{
        val m=o["metadata"].asJsonObject;val hash=o.str("currentContentHash");val body=o["contentObjects"].asJsonObject[hash]?.asString.orEmpty();val version=maxOf(m["legacyVersion"]?.asLong?:0,m.isoMs("updatedAt"))
        return NoteEntity(id=o.str("noteID"),title=m.str("title"),body=body,previewText=m.str("preview",previewText(body)),createdAt=m.isoMs("createdAt"),updatedAt=m.isoMs("updatedAt"),folderId=m.optStr("sectionId"),folderName="",reminderAt=m.optIsoMs("reminderAt"),recurrence=m.str("recurrenceRule","none"),version=version,tagIds=o["tagIDs"]?.asJsonArray?.joinToString(","){it.asString}.orEmpty(),deletedAt=m.optIsoMs("deletedAt"),itemType=if(m.str("kind")=="task")"todo" else "note",dueAt=m.optIsoMs("dueAt"),completedAt=m.optIsoMs("completedAt"),important=m["important"]?.asBoolean?:m["favorite"]?.asBoolean?:false,viewMode="preview",dirty=false,snapshotJson=gson.toJson(o),lastSyncedVersion=version)
    }
    private fun decodedAssets(o:JsonObject):List<AssetEntity>{if(o["schemaVersion"]?.asInt!=3)return assetsFromEnvelope(o);val noteId=o.str("noteID");return o["assets"]?.asJsonArray?.map{raw->val a=raw.asJsonObject;val relative=safeRelative(a.str("localPath"));AssetEntity(a.str("id"),noteId,a.str("kind"),a.str("filename"),a.str("mimeType"),relative,contentHash=a.str("objectHash",a.str("checksum")),size=a["byteSize"]?.asLong?:0)}.orEmpty()}
    private fun decodedSteps(o:JsonObject):List<TodoStepEntity>{if(o["schemaVersion"]?.asInt!=3)return stepsFromEnvelope(o);return o["taskSteps"]?.asJsonArray?.map{raw->val x=raw.asJsonObject;TodoStepEntity(x.str("id"),x.str("pageId"),x.str("text"),x["checked"]?.asBoolean?:false,x.int("sortOrder"),x.isoMs("createdAt"))}.orEmpty()}
    private fun fromEnvelope(o:JsonObject):NoteEntity{val s=o["currentSnapshot"].asJsonObject;val m=s["metadata"].asJsonObject;val body=BlockDocumentCodec.decodeMarkdown(s["document"].asJsonObject["blocks"].asJsonArray);val version=m["version"].asLong;val viewMode=m.str("viewMode","preview").takeIf{it in setOf("text","preview","split")}?:"preview";return NoteEntity(id=m.str("id"),title=m.str("title"),body=body,previewText=previewText(body),createdAt=m.dateMs("createdAt"),updatedAt=m.dateMs("updatedAt"),folderId=m.optStr("folderID"),folderName=m.str("folderName","未分类"),reminderAt=m.optDateMs("reminderAt"),recurrence=m.str("recurrenceRule","none"),version=version,tagIds=m["tagIDs"]?.asJsonArray?.joinToString(","){it.asString}.orEmpty(),deletedAt=m.optDateMs("deletedAt"),itemType=m.str("itemType","note"),dueAt=m.optDateMs("todoDueAt"),completedAt=m.optDateMs("todoCompletedAt"),important=m["todoIsImportant"]?.asBoolean?:false,viewMode=viewMode,dirty=false,snapshotJson=gson.toJson(s),lastSyncedVersion=version)}
    private fun assetsFromEnvelope(o:JsonObject):List<AssetEntity>{val noteId=o.str("noteID");return o["currentSnapshot"].asJsonObject["assets"]?.asJsonArray?.mapNotNull{raw->val a=raw.asJsonObject;val ref=a["reference"]?.asJsonObject?:return@mapNotNull null;val relative=runCatching{safeRelative(a.str("relativeFilePath"))}.getOrNull()?:return@mapNotNull null;AssetEntity(ref.str("id"),noteId,ref.str("kind"),ref.str("filename"),ref.str("mimeType"),relative)}.orEmpty()}
    private fun stepsFromEnvelope(o:JsonObject):List<TodoStepEntity>{val s=o["currentSnapshot"].asJsonObject;return s["todoSteps"]?.takeIf{it.isJsonArray}?.asJsonArray?.map{raw->val x=raw.asJsonObject;TodoStepEntity(x.str("id"),x.str("noteID"),x.str("text"),x["checked"]?.asBoolean?:false,x.int("order"),x.dateMs("createdAt"))}.orEmpty()}
    private fun readJson(c:ChannelSftp,p:String)=try{val out=ByteArrayOutputStream();c.get(p,out);JsonParser.parseString(out.toString("UTF-8")).asJsonObject}catch(error:SftpException){if(error.id==ChannelSftp.SSH_FX_NO_SUCH_FILE)null else throw error}
    private fun readNoteJson(c:ChannelSftp,s:SshSettings,noteID:String,index:JsonObject?)=try{val out=ByteArrayOutputStream();c.get(CompressedRepositoryContract.notePath(s.path,noteID,index),out);val text=if(CompressedRepositoryContract.enabled(index))CompressedRepositoryContract.decode(out.toByteArray())else out.toString("UTF-8");JsonParser.parseString(text).asJsonObject}catch(error:SftpException){if(error.id==ChannelSftp.SSH_FX_NO_SUCH_FILE)null else throw error}
    private fun atomicWrite(c:ChannelSftp,p:String,text:String)=atomicWrite(c,p,text.toByteArray(StandardCharsets.UTF_8))
    private fun atomicWrite(c:ChannelSftp,p:String,bytes:ByteArray){val tmp="$p.tmp.android.${UUID.randomUUID()}";try{c.put(ByteArrayInputStream(bytes),tmp);val remote=ByteArrayOutputStream();c.get(tmp,remote);require(sha(remote.toByteArray())==sha(bytes)){"远端临时文件校验失败：$p"};c.rename(tmp,p)}catch(error:Throwable){runCatching{c.rm(tmp)};throw error}}
    private fun atomicUpload(c:ChannelSftp,p:String,file:java.io.File,expectedHash:String){val tmp="$p.tmp.android.${UUID.randomUUID()}";try{file.inputStream().use{c.put(it,tmp)};val remoteHash=c.get(tmp).use(::sha);require(remoteHash==expectedHash){"远端附件临时文件校验失败"};c.rename(tmp,p)}catch(error:Throwable){runCatching{c.rm(tmp)};throw error}}
    private fun mkdirs(c:ChannelSftp,path:String){var cur="";path.split('/').filter{it.isNotBlank()}.forEach{cur+="/$it";try{c.mkdir(cur)}catch(_:Exception){}}}
    private fun deviceId()=prefs.getString("device",null)?:UUID.randomUUID().toString().also{prefs.edit().putString("device",it).apply()}
    private fun iso(ms:Long)=Instant.ofEpochMilli(ms).toString()
    private fun swiftDate(ms:Long)=SwiftDateCodec.encode(ms)
    private fun sha(s:String)=MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString(""){"%02x".format(it)}
    private fun sha(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
    private fun sha(input:InputStream):String{val digest=MessageDigest.getInstance("SHA-256");val buffer=ByteArray(1024*1024);while(true){val count=input.read(buffer);if(count<0)break;if(count>0)digest.update(buffer,0,count)};return digest.digest().joinToString(""){"%02x".format(it)}}
    private fun safeRelative(path:String):String{val normalized=path.replace('\\','/');require(normalized.isNotBlank()&&!normalized.startsWith('/')&&!normalized.startsWith('~')&&normalized.none{it.isISOControl()}&&!normalized.split('/').any{it==".."||it=="."||it.isBlank()}){"非法附件路径"};return normalized}
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

internal fun resolveRemoteRepositoryPath(configuredPath:String,home:String):String{
    val path=configuredPath.trim().trimEnd('/')
    require(path.isNotEmpty()){"远程目录不能为空"}
    require(path.none{it.isISOControl()}){"远程目录不能包含控制字符"}
    val resolved=when{path=="~"->home;path.startsWith("~/")->home.trimEnd('/')+"/"+path.removePrefix("~/");path.startsWith("/")->path;else->home.trimEnd('/')+"/"+path}
    require(resolved!="/"&&!resolved.split('/').any{it=="."||it==".."}){"远程目录不安全"}
    return resolved
}
private fun JsonObject.str(k:String,d:String="")=get(k)?.takeUnless{it.isJsonNull}?.asString?:d
private fun JsonObject.optStr(k:String)=get(k)?.takeUnless{it.isJsonNull}?.asString
private fun JsonObject.int(k:String)=get(k)?.asInt?:0
private fun JsonObject.dateMs(k:String)=SwiftDateCodec.decode(get(k)?.asDouble?:0.0)
private fun JsonObject.optDateMs(k:String)=get(k)?.takeUnless{it.isJsonNull}?.asDouble?.let(SwiftDateCodec::decode)
private fun JsonObject.isoMs(k:String)=Instant.parse(str(k)).toEpochMilli()
private fun JsonObject.optIsoMs(k:String)=optStr(k)?.let{runCatching{Instant.parse(it).toEpochMilli()}.getOrNull()}
private fun JsonObject.flexibleDateMs(k:String)=get(k)?.let{if(it.isJsonPrimitive&&it.asJsonPrimitive.isString)Instant.parse(it.asString).toEpochMilli() else SwiftDateCodec.decode(it.asDouble)}?:0L
private fun previewText(body:String)=TipTapCodec.plainText(body).take(300)
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
