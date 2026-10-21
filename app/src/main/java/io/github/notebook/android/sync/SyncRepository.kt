package io.github.notebook.android.sync

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

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
import io.github.notebook.android.NotebookReference
import io.github.notebook.android.NoteSearchQuery
import io.github.notebook.android.rankSearchHits
import io.github.notebook.android.NotebookReferenceSyntax
import io.github.notebook.android.WikiLinkCandidate
import io.github.notebook.android.reminder.Reminders
import io.github.notebook.android.diagnostics.DiagnosticEvent
import io.github.notebook.android.diagnostics.DiagnosticLevel
import io.github.notebook.android.diagnostics.DiagnosticLogger
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
import java.io.File
import java.security.MessageDigest
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.UUID
import java.io.InputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import android.util.Base64
import android.util.Log
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

data class SshSettings(val host:String,val port:Int,val username:String,val password:String,val path:String,val fingerprint:String="",val privateKeyPath:String="",val privateKeyPassphrase:String="")
enum class SyncBackend { API, SSH, WEBDAV }
class HostKeyChangedException(val expected:String,val actual:String):Exception("服务器身份已变化，请确认新的主机指纹")
class HostKeyConfirmationRequiredException(val actual:String):Exception("首次连接需要确认服务器主机指纹")
enum class NoteSaveState { Pending, Saving, Saved, Failed }
data class NoteRevisionSummary(val id:String,val createdAt:Long,val reason:String,val markdown:String,val pageIcon:String?)

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
    fun notePath(root:String,noteID:String,index:JsonObject?):String {
        RepositoryIdentityContract.requireNoteID(noteID)
        return "$root/notes/$noteID.json${if(enabled(index))".gz" else ""}"
    }
    fun encode(text:String):ByteArray=ByteArrayOutputStream().also{output->GZIPOutputStream(output).use{it.write(text.toByteArray(StandardCharsets.UTF_8))}}.toByteArray()
    fun decode(bytes:ByteArray,maxOutputBytes:Long=RemoteTransferLimits.MAX_FILE_BYTES):String=
        GZIPInputStream(ByteArrayInputStream(bytes)).use{input->
            val output=ByteArrayOutputStream()
            val buffer=ByteArray(64*1024)
            var total=0L
            while(true){
                val count=input.read(buffer)
                if(count<0)break
                total+=count
                require(total<=maxOutputBytes){"压缩笔记解压后超过 ${RemoteTransferLimits.MAX_FILE_MIB} MiB，已停止同步"}
                output.write(buffer,0,count)
            }
            output.toString(StandardCharsets.UTF_8.name())
        }
}

internal object RepositoryIdentityContract {
    // Keep this aligned with Electron. UUID versions 6–8 are valid identifiers
    // too; the variant bits and exact length still prevent path injection.
    private val UUID_PATTERN=Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$",RegexOption.IGNORE_CASE)
    fun isValidNoteID(noteID:String)=UUID_PATTERN.matches(noteID)
    fun requireNoteID(noteID:String):String {
        require(isValidNoteID(noteID)){"远端索引包含非法笔记 ID，已停止同步"}
        return noteID
    }
}

/** Privacy cleanup rules for the legacy SSH assets manifest. */
internal object SshAssetManifestContract {
    /**
     * Old manifests use the source relative path, so an owned private asset can
     * be identified and removed. Hash-addressed v3 entries intentionally stay:
     * their path has no owner and may be shared with a non-private asset.
     */
    fun removeIdentifiablePrivateEntries(entries: MutableMap<String, JsonObject>, privateAssets: List<AssetEntity>) {
        privateAssets.mapNotNull { asset ->
            runCatching { legacyRelativePath(asset.relativePath) }.getOrNull()
        }.forEach(entries::remove)
    }

    fun isPrivateAsset(asset:AssetEntity, encryptedNoteIds:Set<String>) = asset.noteId in encryptedNoteIds

    fun uploadableAssets(dirtyAssets:List<AssetEntity>, encryptedNoteIds:Set<String>) =
        dirtyAssets.filterNot { isPrivateAsset(it, encryptedNoteIds) }

    fun stripPrivateEnvelopeAttachments(envelope:JsonObject):JsonObject{
        val copy=envelope.deepCopy()
        copy.add("assets",JsonArray())
        copy["metadata"]?.takeIf{it.isJsonObject}?.asJsonObject?.apply{addProperty("assetCount",0)}
        return copy
    }

    private fun legacyRelativePath(path:String):String {
        val normalized=path.replace('\\','/')
        require(normalized.isNotBlank()&&!normalized.startsWith('/')&&!normalized.startsWith('~')&&normalized.none{it.isISOControl()}&&!normalized.split('/').any{it==".."||it=="."||it.isBlank()}){"非法附件路径"}
        return normalized
    }
}

/** SSH is compatibility-only. Old repository formats cannot protect private
 * note payloads, so they must remain entirely local rather than being partly
 * serialized in plaintext. */
internal object SshPrivacyCompatibilityContract {
    fun canPublishPrivateNote(repositoryVersion:Int) = repositoryVersion == CompressedRepositoryContract.VERSION
}

internal object RemoteTransferLimits {
    const val MAX_FILE_MIB=100L
    const val MAX_REPOSITORY_MIB=512L
    const val MAX_FILE_BYTES=MAX_FILE_MIB*1024*1024
    const val MAX_REPOSITORY_BYTES=MAX_REPOSITORY_MIB*1024*1024

    class Budget {
        var consumedBytes=0L
            private set
        fun consume(fileBytes:Long,totalBytes:Long) {
            require(fileBytes<=MAX_FILE_BYTES){"远端单个文件超过 $MAX_FILE_MIB MiB，已停止同步"}
            require(consumedBytes+totalBytes<=MAX_REPOSITORY_BYTES){"本次远端读取超过 $MAX_REPOSITORY_MIB MiB，已停止同步"}
            consumedBytes+=totalBytes
        }
    }

    fun requireUploadSize(size:Long){
        require(size<=MAX_FILE_BYTES){"单个上传文件超过 $MAX_FILE_MIB MiB，已停止同步"}
    }
    fun requireDownloadSize(size:Long){
        require(size<=MAX_FILE_BYTES){"单个下载文件超过 $MAX_FILE_MIB MiB，已停止同步"}
    }
}

internal object RepositoryIndexContract {
    private fun stableContent(index:JsonObject)=index.deepCopy().apply {
        remove("generatedAt")
        remove("deviceID")
    }
    fun hasSameContent(remote:JsonObject?,next:JsonObject)=remote!=null&&stableContent(remote)==stableContent(next)
    fun snapshotHash(bytes:ByteArray?)=bytes?.let{MessageDigest.getInstance("SHA-256").digest(it).joinToString(""){byte->"%02x".format(byte)}}?:"missing"
    fun hasSameSnapshot(expected:ByteArray?,current:ByteArray?)=snapshotHash(expected)==snapshotHash(current)
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

/** Ranked search hits with body snippets and the words to highlight. */
data class NoteSearchResult(val ids:List<String>,val snippets:Map<String,String>,val terms:List<String>)

/** Compatible with the macOS SSH JSON store. Swift JSONEncoder dates are seconds since 2001-01-01. */
const val INBOX_NAME="待处理"

class SyncRepository(context:Context, private val dao:NotebookDao,preferences:android.content.SharedPreferences?=null) {
    private val appContext=context.applicationContext
    private val gson=GsonBuilder().disableHtmlEscaping().create()
    private val appScope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val saveMutex=Mutex()
    private val syncMutex=Mutex()
    private var remoteReadBudget:RemoteTransferLimits.Budget?=null
    private val privacyIterations=310_000
    private val pendingDrafts=ConcurrentHashMap<String,NoteEntity>()
    private val draftJobs=ConcurrentHashMap<String,Job>()
    private val lastDraftWriteAt=ConcurrentHashMap<String,Long>()
    private val trashedNoteIds=ConcurrentHashMap.newKeySet<String>()
    private val permanentlyDeletedNoteIds=ConcurrentHashMap.newKeySet<String>()
    private val syncRequestLock=Any()
    private var syncRequestJob:Job?=null
    private val _saveError=MutableStateFlow<String?>(null)
    val saveError=_saveError.asStateFlow()
    private val _noteSaveStates=MutableStateFlow<Map<String,NoteSaveState>>(emptyMap())
    val noteSaveStates=_noteSaveStates.asStateFlow()
    private data class RemoteRepositoryWriteLock(val path:String,val ownerID:String){val ownerPath="$path/${RemoteRepositoryLockContract.OWNER_FILE_NAME}"}
    private fun setSaveState(noteId:String,state:NoteSaveState){_noteSaveStates.update{it+mapOf(noteId to state)}}
    suspend fun hasStoredNote(noteId:String)=withContext(Dispatchers.IO){dao.getNoteHeader(noteId)!=null}
    fun discardUnsavedMarker(noteId:String){_noteSaveStates.update{it-noteId}}
    fun markUnsaved(noteId:String){setSaveState(noteId,NoteSaveState.Pending)}
    fun acknowledgeSaved(noteId:String){_noteSaveStates.update{states->if(states[noteId]==NoteSaveState.Saved)states-noteId else states}}
    fun clearSaveError(){_saveError.value=null}
    private val prefs=preferences?:encryptedPrefs(context)
    private val privacy=PrivateNoteIndex(prefs,gson)
    private val apiSync=ApiSyncClient(appContext,dao)
    private val webdavSync=WebdavSyncClient(appContext,dao,prefs,apiSync,privacy=privacy)
    /** Desktop root page id per library id; see [libraryRootSectionId]. */
    val libraryRootPages=dao.observeApiPagesWithLibraryRootMarker().map{rows->rows.mapNotNull{row->libraryRootSectionId(row.payloadJson)?.let{it to row.pageId}}.toMap()}.distinctUntilChanged()
    val notes=combine(dao.observeNoteSummaries(),libraryRootPages){list,roots->withoutLibraryRoots(list,roots)}
    val folders=dao.observeFolders()
    val tags=dao.observeTags()
    fun backlinks(noteId:String)=dao.observeBacklinks(noteId)
    fun settings()=SshSettings(prefs.getString("host","")!!,prefs.getInt("port",22),prefs.getString("user","")!!,prefs.getString("password","")!!,prefs.getString("path","~/notebook_backup")!!,prefs.getString("fingerprint","")!!,prefs.getString("privateKeyPath","")!!,prefs.getString("privateKeyPassphrase","")!!)
    fun apiSettings()=ApiSyncSettings(prefs.getString("apiBaseUrl","")!!,prefs.getString("apiWorkspaceId","00000000-0000-4000-8000-000000000001")!!,prefs.getString("apiToken","")!!)
    /** AI assistant settings; the API key stays in the encrypted preferences of this device only. */
    fun aiSettings():io.github.notebook.android.ai.AiSettings{val provider=runCatching{io.github.notebook.android.ai.AiProvider.valueOf(prefs.getString("aiProvider","DeepSeek")!!)}.getOrDefault(io.github.notebook.android.ai.AiProvider.DeepSeek);return io.github.notebook.android.ai.AiSettings(provider,prefs.getString("aiBaseUrl",provider.baseUrl)!!,prefs.getString("aiModel",provider.model)!!,prefs.getString("aiApiKey","")!!)}
    fun saveAiSettings(s:io.github.notebook.android.ai.AiSettings){check(prefs.edit().putString("aiProvider",s.provider.name).putString("aiBaseUrl",s.baseUrl.trim()).putString("aiModel",s.model.trim()).putString("aiApiKey",s.apiKey.trim()).commit()){"AI 设置保存失败，请重试"}}
    /** Remote text-to-speech settings; the API key stays in this device's encrypted preferences. */
    fun ttsSettings():io.github.notebook.android.tts.TtsSettings{
        val api=runCatching{io.github.notebook.android.tts.TtsApi.valueOf(prefs.getString("ttsApi","Qwen3Flash")!!)}.getOrDefault(io.github.notebook.android.tts.TtsApi.Qwen3Flash)
        return io.github.notebook.android.tts.TtsSettings(api,prefs.getString("ttsEndpoint",api.defaultEndpoint)!!,prefs.getString("ttsApiKey","")!!,prefs.getString("ttsModel",api.defaultModel)!!,prefs.getString("ttsVoice",api.defaultVoice)!!,prefs.getString("ttsLanguage","Auto")!!,prefs.getString("ttsInstruction","")!!,prefs.getFloat("ttsSpeed",io.github.notebook.android.tts.TtsSettings.DEFAULT_SPEED).coerceIn(.5f,io.github.notebook.android.tts.TtsSettings.MAX_SPEED),prefs.getFloat("ttsUnitPrice",io.github.notebook.android.tts.TtsCost.DEFAULT_UNIT_PRICE),prefs.getFloat("ttsBalance",0f))
    }
    fun saveTtsSettings(s:io.github.notebook.android.tts.TtsSettings){check(prefs.edit().putString("ttsApi",s.api.name).putString("ttsEndpoint",s.endpoint.trim()).putString("ttsApiKey",s.apiKey.trim()).putString("ttsModel",s.model.trim()).putString("ttsVoice",s.voice.trim()).putString("ttsLanguage",s.language).putString("ttsInstruction",s.instruction.trim()).putFloat("ttsSpeed",s.speed.coerceIn(.5f,io.github.notebook.android.tts.TtsSettings.MAX_SPEED)).putFloat("ttsUnitPrice",s.unitPrice.coerceAtLeast(0f)).putFloat("ttsBalance",s.balance.coerceAtLeast(0f)).commit()){"朗读设置保存失败，请重试"}}
    /** Characters the service has billed this phone since the balance was last set. */
    fun ttsUsedChars():Long=prefs.getLong("ttsUsedChars",0L)
    @Synchronized fun addTtsUsedChars(count:Long){prefs.edit().putLong("ttsUsedChars",ttsUsedChars()+count.coerceAtLeast(0)).apply()}
    fun resetTtsUsage(){check(prefs.edit().putLong("ttsUsedChars",0L).commit()){"用量清零失败，请重试"}}
    fun webdavSettings()=WebdavSettings(prefs.getString("webdavBaseUrl","https://dav.jianguoyun.com/dav/")!!,prefs.getString("webdavUser","")!!,prefs.getString("webdavAppPassword","")!!,prefs.getString("webdavPath","notebook_backup")!!,prefs.getString("webdavSyncPassword","")!!)
    fun saveWebdavSettings(s:WebdavSettings){check(prefs.edit().putString("syncBackend",SyncBackend.WEBDAV.name).putString("webdavBaseUrl",s.baseUrl.trim()).putString("webdavUser",s.username.trim()).putString("webdavAppPassword",s.appPassword).putString("webdavPath",s.remotePath.trim()).putString("webdavSyncPassword",s.syncPassword).commit()){ "同步配置保存失败，请重试" }}
    suspend fun testWebdav():WebdavTestResult=webdavSync.test(webdavSettings())
    suspend fun syncCompletionMessage():String {
        val notices=buildList{
            if(syncBackend()==SyncBackend.WEBDAV){
                val copies=webdavSync.lastResolved.count{it.copyNoteId!=null}
                if(copies>0)add("$copies 篇笔记在多台设备上同时被修改：已保留本机版本，另一版本另存为“冲突副本”")
                if(webdavSync.lastRepublished>0)add("云端缺少本机记录，已自动重新上传全部内容")
                val issues=webdavSync.issues(webdavSettings())
                if(issues.quarantined.isNotEmpty())add("${issues.quarantined.size} 条云端记录暂时无法应用，详见同步状态")
                if(issues.blocked.isNotEmpty())add("${issues.blocked.size} 台设备的云端日志异常，详见同步状态")
            }
        }
        val conflicts=dao.allNotes().count{it.conflict}
        if(conflicts>0)return (notices+"同步仍有 $conflicts 条冲突待处理").joinToString("；")
        val pending=dao.apiOutboxCount(syncWorkspaceId())
        if(pending>0)return (notices+"本批同步完成，仍有 $pending 条变更等待上传").joinToString("；")
        return (notices.ifEmpty{listOf("同步完成")}).joinToString("；")
    }
    /** Set-aside remote records and devices whose journal could not be trusted (WebDAV). */
    internal suspend fun syncIssues():WebdavSyncIssues=withContext(Dispatchers.IO){webdavSync.issues(webdavSettings())}
    internal fun ownDeviceId():String=webdavDeviceId(prefs)
    /** Repair: keep a copy of this device's damaged journal, remove it and queue everything for publication again. */
    internal suspend fun rebuildOwnJournal():RebuildOutcome=syncMutex.withLock{webdavSync.rebuildOwnJournal(webdavSettings())}
    /** Try the set-aside records again; returns how many remain. */
    suspend fun retrySyncIssues():Int=syncMutex.withLock{webdavSync.retryQuarantine(webdavSettings())}
    /** Drop the set-aside records for good (the user confirmed). */
    suspend fun dismissSyncIssues(){syncMutex.withLock{val s=webdavSettings();webdavSync.dismissQuarantine(s,webdavSync.issues(s).quarantined.map{it.key})}}
    fun lastSyncSuccessAt():Long?=prefs.getLong("lastSyncSuccessAt",0L).takeIf{it>0}
    /** True when a sync succeeded moments ago and nothing local is waiting to upload. */
    suspend fun shouldSkipRedundantSync():Boolean=withContext(Dispatchers.IO){
        val last=lastSyncSuccessAt()?:return@withContext false
        val age=System.currentTimeMillis()-last
        age in 0..20_000&&pendingUploadCount()==0&&dao.dirtyNoteCount()==0&&dao.dirtyAssetCount()==0&&pendingDrafts.isEmpty()
    }
    suspend fun pendingUploadCount():Int=withContext(Dispatchers.IO){if(syncBackend()==SyncBackend.SSH)dao.dirtyNoteCount() else dao.apiOutboxCount(syncWorkspaceId())}
    suspend fun conflictCopyCount():Int=withContext(Dispatchers.IO){dao.allNotes().count{it.deletedAt==null&&it.title.contains("（冲突副本")}}
    /** Repair: re-read every remote journal from the start (idempotent). */
    suspend fun resetSyncReadPositions(){syncMutex.withLock{webdavSync.resetReadPositions(webdavSettings())}}
    /** Repair: queue the whole library for publication again. */
    suspend fun republishAll():Int=syncMutex.withLock{webdavSync.queueFullRepublish(webdavSettings())}
    /** Content-free local report for comparing restored entity ids and hashes. */
    suspend fun exportSyncDiagnostics():File=withContext(Dispatchers.IO) {
        val notes=dao.allNotes()
        val root=JsonObject().apply{
            addProperty("version",1)
            addProperty("outbox",dao.apiOutboxCount(syncWorkspaceId()))
            add("notes",JsonArray().apply{notes.forEach{note->add(JsonObject().apply{
                addProperty("id",note.id);addProperty("private",isEncrypted(note));addProperty("deleted",note.deletedAt!=null)
                addProperty("kind",note.itemType);addProperty("bodyHash",sha(note.body));addProperty("dirty",note.dirty);addProperty("conflict",note.conflict)
            })}})
            add("assets",JsonArray().apply{for(note in notes)for(asset in dao.assets(note.id))add(JsonObject().apply{
                addProperty("id",asset.id);addProperty("pageId",asset.noteId);addProperty("hash",asset.contentHash)
                addProperty("present",asset.localPath?.let{File(it).isFile}==true)
            })})
            add("revisions",JsonArray().apply{for(note in notes)for(revision in dao.remoteRevisions(note.id))add(JsonObject().apply{
                addProperty("id",revision.id);addProperty("pageId",note.id)
            })})
            add("folders",JsonArray().apply{dao.allFolders().forEach{folder->add(JsonObject().apply{addProperty("id",folder.id);addProperty("private",folder.type=="encryptedFolder")})}})
            add("knownSeqs",JsonParser.parseString(prefs.getString("webdav-seqs:${webdavSync.fingerprint(webdavSettings())}","{}")))
        }
        File(appContext.getExternalFilesDir("sync-diagnostics"),"status.json").also{it.parentFile?.mkdirs();it.writeText(gson.toJson(root))}
    }
    fun syncBackend()=runCatching{SyncBackend.valueOf(prefs.getString("syncBackend",SyncBackend.WEBDAV.name)!!)}.getOrDefault(SyncBackend.WEBDAV)
    /** Assign a fresh WebDAV device id and drop read cursors so the next sync replays every journal from scratch. */
    fun rotateWebdavDeviceId(){
        prefs.edit().remove("device").apply()
        prefs.all.keys.filter { it.startsWith("webdav-seqs") }.forEach { prefs.edit().remove(it).apply() }
    }
    fun saveSettings(s:SshSettings){prefs.edit().putString("syncBackend",SyncBackend.SSH.name).putString("host",s.host).putInt("port",s.port).putString("user",s.username).putString("password",s.password).putString("path",s.path).putString("fingerprint",s.fingerprint.trim()).putString("privateKeyPath",s.privateKeyPath.trim()).putString("privateKeyPassphrase",s.privateKeyPassphrase).apply()}
    fun saveApiSettings(s:ApiSyncSettings){prefs.edit().putString("syncBackend",SyncBackend.API.name).putString("apiBaseUrl",s.baseUrl.trim()).putString("apiWorkspaceId",s.workspaceId.trim()).putString("apiToken",s.token.trim()).apply()}
    fun setSyncBackend(backend:SyncBackend){prefs.edit().putString("syncBackend",backend.name).apply()}
    /** Outbox workspace key for the current non-SSH backend. */
    private fun syncWorkspaceId()=if(syncBackend()==SyncBackend.WEBDAV)WebdavJournalProtocol.DEFAULT_WORKSPACE_ID else apiSettings().workspaceId
    fun hasRemoteConfiguration()=when(syncBackend()){SyncBackend.API->apiSettings().let{it.baseUrl.isNotBlank()&&it.token.isNotBlank()};SyncBackend.WEBDAV->webdavSettings().let{it.username.isNotBlank()&&it.appPassword.isNotBlank()&&it.baseUrl.isNotBlank()};SyncBackend.SSH->settings().let{it.host.isNotBlank()&&it.username.isNotBlank()&&it.path.isNotBlank()}}
    fun trustHostKey(fingerprint:String){saveSettings(settings().copy(fingerprint=fingerprint))}
    // Cached: called for every note on every recomposition (list rows, editor titles).
    fun isEncrypted(note:NoteEntity)=privacy.snapshot().isPrivate(note.id)
    fun encryptedFolderId(note:NoteEntity)=privacy.snapshot().folderOf(note.id,note.folderId)
    fun isEncrypted(note:NoteSummary)=privacy.snapshot().isPrivate(note.id)
    fun encryptedFolderId(note:NoteSummary)=privacy.snapshot().folderOf(note.id,note.folderId)
    suspend fun loadNote(id:String)=withContext(Dispatchers.IO){loadEditable(id)}
    internal suspend fun resolveReference(reference:NotebookReference):NoteEntity?=withContext(Dispatchers.IO){loadEditable(reference.pageId)}
    suspend fun revisions(noteId:String):List<NoteRevisionSummary> = withContext(Dispatchers.IO){
        val remote=dao.remoteRevisions(noteId).map{NoteRevisionSummary(it.id,it.createdAt,it.reason,it.markdown,it.pageIcon)}
        val raw=loadSnapshot(noteId)?:return@withContext remote
        val envelope=runCatching{JsonParser.parseString(raw).asJsonObject}.getOrNull()?:return@withContext emptyList()
        if(envelope["schemaVersion"]?.asInt==3){
            val objects=envelope["contentObjects"]?.takeIf{it.isJsonObject}?.asJsonObject?:return@withContext emptyList()
            envelope["history"]?.takeIf{it.isJsonArray}?.asJsonArray?.mapNotNull{item->
                val entry=item.asJsonObject
                val hash=entry["contentHash"]?.asString?:return@mapNotNull null
                val markdown=objects[hash]?.takeUnless{it.isJsonNull}?.asString?:return@mapNotNull null
                NoteRevisionSummary(
                    id=entry["id"]?.asString?:hash,
                    createdAt=entry.optIsoMs("createdAt")?:entry.optIsoMs("updatedAt")?:0,
                    reason=entry["reason"]?.asString?:"autosave",
                    markdown=markdown,
                    pageIcon=entry.optStr("pageIcon")
                )
            }.orEmpty().plus(remote).distinctBy{it.id}.sortedByDescending{it.createdAt}
        }else{
            envelope["history"]?.takeIf{it.isJsonArray}?.asJsonArray?.mapNotNull{item->
                val entry=item.asJsonObject
                val snapshot=entry["snapshot"]?.takeIf{it.isJsonObject}?.asJsonObject?:return@mapNotNull null
                val blocks=snapshot["document"]?.asJsonObject?.get("blocks")?.takeIf{it.isJsonArray}?.asJsonArray?:return@mapNotNull null
                NoteRevisionSummary(entry["id"]?.asString?:UUID.randomUUID().toString(),entry.optDateMs("updatedAt")?:0,"import",BlockDocumentCodec.decodeMarkdown(blocks),snapshot["metadata"]?.asJsonObject?.optStr("icon"))
            }.orEmpty().plus(remote).distinctBy{it.id}.sortedByDescending{it.createdAt}
        }
    }
    suspend fun restoreRevision(noteId:String,revisionId:String):NoteEntity{
        val revision=revisions(noteId).firstOrNull{it.id==revisionId}?:error("历史版本不存在")
        val current=loadEditable(noteId)?:error("笔记不存在")
        return save(current.copy(body=revision.markdown,icon=revision.pageIcon))
    }
    suspend fun searchNoteIds(query:String)=withContext(Dispatchers.IO){dao.searchNoteIds(query).toSet()}
    /**
     * Multi-keyword search over title, body, library and tag names.
     * Every keyword must match somewhere; "phrases" stay together, -word
     * excludes and #tag filters by tag. Results are ranked (title hits first,
     * then newest) and carry a body snippet around the first keyword.
     */
    suspend fun searchNotes(raw:String,candidates:List<NoteSummary>,tags:List<TagEntity>):NoteSearchResult=withContext(Dispatchers.IO){
        val query=NoteSearchQuery.parse(raw)
        if(query.isEmpty)return@withContext NoteSearchResult(emptyList(),emptyMap(),emptyList())
        val byId=candidates.associateBy{it.id}
        val tagNames=tags.associate{it.id to it.name}
        fun metadataMatches(note:NoteSummary,term:String)=note.folderName.contains(term,true)||note.tagIds.split(',').any{id->tagNames[id]?.contains(term,true)==true}||io.github.notebook.android.PinyinMatcher.matches(note.title,term)
        var matched:Set<String> =byId.keys
        for(term in query.terms){
            val hits=dao.searchNoteIds(term).toHashSet()
            matched=matched.filterTo(HashSet()){id->id in hits||byId[id]?.let{metadataMatches(it,term)}==true}
            if(matched.isEmpty())break
        }
        for(tag in query.tags){
            val ids=tags.filter{it.name.equals(tag,true)||it.name.contains(tag,true)}.map{it.id}.toSet()
            matched=matched.filterTo(HashSet()){id->byId[id]?.tagIds?.split(',')?.any{it in ids}==true}
        }
        for(term in query.excluded){
            val hits=dao.searchNoteIds(term).toHashSet()
            matched=matched.filterTo(HashSet()){id->id !in hits&&byId[id]?.let{metadataMatches(it,term)}!=true}
        }
        val ranked=rankSearchHits(matched,byId,query)
        val snippetTerm=query.terms.firstOrNull()
        val snippets=if(snippetTerm==null)emptyMap() else ranked.take(80).mapNotNull{id->
            dao.searchSnippet(id,snippetTerm)?.takeIf{it.contains(snippetTerm,true)}?.let{id to it}
        }.toMap()
        NoteSearchResult(ranked,snippets,query.highlights)
    }
    private val navigationPrefs by lazy{appContext.getSharedPreferences("note-navigation",Context.MODE_PRIVATE)}
    fun recentSearches():List<String> = runCatching{gson.fromJson(navigationPrefs.getString("searches","[]"),Array<String>::class.java).toList()}.getOrDefault(emptyList())
    fun rememberSearch(query:String){val value=query.trim();if(value.length<1)return;navigationPrefs.edit().putString("searches",gson.toJson((listOf(value)+recentSearches().filter{it!=value}).take(12))).apply()}
    fun clearSearchHistory(){navigationPrefs.edit().remove("searches").apply()}
    fun markEncrypted(noteId:String,folderId:String){privacy.update{it.copy(noteIds=it.noteIds+noteId,mappings=it.mappings+(noteId to folderId))}}
    fun moveEncrypted(noteId:String,folderId:String)=markEncrypted(noteId,folderId)
    private fun encryptedNoteIds():Set<String> = privacy.snapshot().noteIds
    private fun encryptedMappings():Map<String,String> = privacy.snapshot().mappings
    suspend fun save(n:NoteEntity,restoring:Boolean=false):NoteEntity{setSaveState(n.id,NoteSaveState.Saving);return try{dao.putDraft(DraftEntity(n.id,gson.toJson(n)));persistDraft(n,restoring).also{setSaveState(n.id,NoteSaveState.Saved)}}catch(error:Throwable){setSaveState(n.id,NoteSaveState.Failed);throw error}}
    /**
     * Called on every keystroke. Serializing the whole note into the durable
     * draft table per keystroke caused visible input lag on long notes, so the
     * crash-safety snapshot is written after a 250 ms pause, or at least every
     * 1.5 s during continuous typing. Lifecycle stops still flush immediately.
     */
    fun queueDraft(n:NoteEntity){if(permanentlyDeletedNoteIds.contains(n.id)||(n.deletedAt==null&&trashedNoteIds.contains(n.id)))return;if(_noteSaveStates.value[n.id]!=NoteSaveState.Pending)setSaveState(n.id,NoteSaveState.Pending);pendingDrafts[n.id]=n;draftJobs.remove(n.id)?.cancel();val job=appScope.launch{try{if(System.currentTimeMillis()-(lastDraftWriteAt[n.id]?:0L)<1_500)delay(250);val draft=pendingDrafts[n.id]?:return@launch;dao.putDraft(DraftEntity(draft.id,gson.toJson(draft)));lastDraftWriteAt[n.id]=System.currentTimeMillis();delay(350);val latest=pendingDrafts[n.id]?:return@launch;setSaveState(n.id,NoteSaveState.Saving);persistDraft(latest);if(pendingDrafts.remove(n.id,latest))setSaveState(n.id,NoteSaveState.Saved)}catch(error:Throwable){if(error is CancellationException)throw error;setSaveState(n.id,NoteSaveState.Failed);DiagnosticLogger.log(DiagnosticLevel.ERROR,DiagnosticEvent.DRAFT_FLUSH_FAILURE,operationId=DiagnosticLogger.operationId(),resourceId=n.id,error=error);_saveError.value="自动保存失败：${error.localizedMessage}"}};draftJobs[n.id]=job;job.invokeOnCompletion{draftJobs.remove(n.id,job)}}
    fun flushDraft(n:NoteEntity):Job{if(permanentlyDeletedNoteIds.contains(n.id)||(n.deletedAt==null&&trashedNoteIds.contains(n.id)))return appScope.launch{};val operationId=DiagnosticLogger.operationId();val startedAt=System.currentTimeMillis();setSaveState(n.id,NoteSaveState.Saving);pendingDrafts[n.id]=n;draftJobs.remove(n.id)?.cancel();val job=appScope.launch{try{dao.putDraft(DraftEntity(n.id,gson.toJson(n)));val latest=pendingDrafts[n.id]?:n;persistDraft(latest);if(pendingDrafts.remove(n.id,latest))setSaveState(n.id,NoteSaveState.Saved)}catch(error:Throwable){if(error is CancellationException)throw error;setSaveState(n.id,NoteSaveState.Failed);DiagnosticLogger.log(DiagnosticLevel.ERROR,DiagnosticEvent.DRAFT_FLUSH_FAILURE,operationId=operationId,resourceId=n.id,error=error,durationMs=System.currentTimeMillis()-startedAt);_saveError.value="保存失败，草稿仍保留：${error.localizedMessage}"}};draftJobs[n.id]=job;job.invokeOnCompletion{draftJobs.remove(n.id,job)};return job}
    fun flushAllAsync(){pendingDrafts.values.toList().forEach(::flushDraft)}
    suspend fun flushAll(){val drafts=pendingDrafts.values.toList();drafts.forEach{draftJobs.remove(it.id)?.cancel()};drafts.forEach{draft->val operationId=DiagnosticLogger.operationId();val startedAt=System.currentTimeMillis();setSaveState(draft.id,NoteSaveState.Saving);try{dao.putDraft(DraftEntity(draft.id,gson.toJson(draft)));persistDraft(draft);if(pendingDrafts.remove(draft.id,draft))setSaveState(draft.id,NoteSaveState.Saved)}catch(error:Throwable){setSaveState(draft.id,NoteSaveState.Failed);DiagnosticLogger.log(DiagnosticLevel.ERROR,DiagnosticEvent.DRAFT_FLUSH_FAILURE,operationId=operationId,resourceId=draft.id,error=error,durationMs=System.currentTimeMillis()-startedAt);throw error}}}
    suspend fun recoverDrafts():Int{var recovered=0;dao.draftNoteIds().forEach{id->val payload=readLargeText(dao.draftPayloadLength(id)){start,length->dao.draftPayloadChunk(id,start,length)}?:return@forEach;val draft=runCatching{gson.fromJson(payload,NoteEntity::class.java)}.onFailure{DiagnosticLogger.log(DiagnosticLevel.ERROR,DiagnosticEvent.DRAFT_RECOVERY_FAILURE,operationId=DiagnosticLogger.operationId(),resourceId=id,error=it)}.getOrNull()?:return@forEach;persistDraft(draft);recovered++};return recovered}
    fun recoverDraftsAsync(){val operationId=DiagnosticLogger.operationId();val startedAt=System.currentTimeMillis();DiagnosticLogger.log(DiagnosticLevel.INFO,DiagnosticEvent.DRAFT_RECOVERY_START,operationId=operationId);appScope.launch{runCatching{recoverDrafts()}.onSuccess{DiagnosticLogger.log(DiagnosticLevel.INFO,DiagnosticEvent.DRAFT_RECOVERY_SUCCESS,operationId=operationId,durationMs=System.currentTimeMillis()-startedAt,counts=mapOf("recovered" to it.toLong()))}.onFailure{DiagnosticLogger.log(DiagnosticLevel.ERROR,DiagnosticEvent.DRAFT_RECOVERY_FAILURE,operationId=operationId,error=it,durationMs=System.currentTimeMillis()-startedAt);_saveError.value="恢复本地草稿失败：${it.localizedMessage}"}}}
    fun rebuildReferenceIndexAsync(){appScope.launch{runCatching{dao.allNoteIds().forEach{id->loadEditable(id)?.let{indexReferences(it)}}}.onFailure{_saveError.value="重建双链索引失败：${it.localizedMessage}"}}}

    suspend fun readingPosition(noteId:String)=withContext(Dispatchers.IO){dao.readingPosition(noteId)}
    fun recordReadingPosition(noteId:String,anchorUtf16Offset:Int,viewportOffsetFraction:Double){appScope.launch{
        val anchor=anchorUtf16Offset.coerceAtLeast(0);val fraction=viewportOffsetFraction.coerceIn(-1.0,1.0);val current=dao.readingPosition(noteId)
        if(current?.anchorUtf16Offset==anchor&&kotlin.math.abs(current.viewportOffsetFraction-fraction)<0.001)return@launch
        // Scrolling is a local high-frequency event. Persist only the newest
        // location; a lifecycle/sync boundary later coalesces it into outbox.
        dao.putReadingPositionIfNoteExists(ReadingPositionEntity(noteId,anchor,fraction,System.currentTimeMillis(),deviceId(),dirty=true))
    }}

    /** Each real foreground transition is a pull-first normal sync boundary. */
    fun requestForegroundSync(){
        if(!hasRemoteConfiguration())return
        // Returning within a couple of minutes (switching apps, the share
        // sheet, a permission dialog) does not need another download.
        if(!SyncPacing.foregroundPullDue(System.currentTimeMillis(),lastSyncSuccessAt()))return
        SyncWorker.enqueueNow(appContext)
    }

    /** There is no reliable Android exit callback, so queue a durable position
     * snapshot before scheduling an immediate catch-up worker. */
    fun onAppBackgrounded(){
        cancelDelayedSyncRequest()
        appScope.launch{
            val flushError=runCatching{flushAll()}.exceptionOrNull()
            // Position state was already committed by scrolling; queue it even
            // if an unrelated draft could not be flushed in this lifecycle.
            val positionError=runCatching{queueDirtyReadingPositions()}.exceptionOrNull()
            (flushError?:positionError)?.let{_saveError.value="后台保存失败：${it.localizedMessage}"}
            requestBoundarySync()
        }
    }

    private suspend fun queueDirtyReadingPositions(){
        if(syncBackend()==SyncBackend.SSH)return
        val workspace=syncWorkspaceId()
        dao.dirtyReadingPositions().forEach{position->
            val note=dao.get(position.noteId)?:return@forEach
            if(!isEncrypted(note))apiSync.queueReadingPosition(workspace,position)
        }
    }

    private suspend fun persistDraft(draft:NoteEntity,restoring:Boolean=false):NoteEntity=saveMutex.withLock{
        // Disposal and restart recovery can deliver a stale trashed draft after
        // its row was removed. A durable tombstone always wins over that draft.
        if(dao.tombstone("note|${draft.id}")!=null){dao.deleteDraft(draft.id);return@withLock draft}
        val current=loadEditable(draft.id)
        if(!restoring&&current?.deletedAt!=null&&draft.deletedAt==null){
            trashedNoteIds.add(draft.id)
            dao.deleteDraft(draft.id)
            return@withLock current
        }
        // A reader/editor draft may predate a completed move. Its text remains
        // editable, but the newer structural timestamp owns the tree position.
        val structuralDraft=if(current!=null&&current.treeUpdatedAt>draft.treeUpdatedAt)draft.copy(folderId=current.folderId,folderName=current.folderName,parentPageId=current.parentPageId,sortOrder=current.sortOrder,treeUpdatedAt=current.treeUpdatedAt)else draft
        if(current!=null&&sameEditableContent(current,structuralDraft)){dao.deleteDraft(draft.id);return@withLock current}
        val base=current?:draft;val saved=base.copy(title=draft.title,body=draft.body,previewText=previewText(draft.body),folderId=structuralDraft.folderId,folderName=structuralDraft.folderName,icon=draft.icon,parentPageId=structuralDraft.parentPageId,sortOrder=structuralDraft.sortOrder,treeUpdatedAt=structuralDraft.treeUpdatedAt,reminderAt=draft.reminderAt,recurrence=draft.recurrence,tagIds=draft.tagIds,deletedAt=draft.deletedAt,itemType=draft.itemType,dueAt=draft.dueAt,completedAt=draft.completedAt,important=draft.important,viewMode=draft.viewMode,updatedAt=System.currentTimeMillis(),version=(current?.version?:draft.version)+1,dirty=true)
        if(current==null)dao.put(saved)else dao.updateEditable(saved.editableUpdate());indexReferences(saved);if(current==null||current.title!=saved.title)reindexTitleLinksAsync();dao.deleteDraft(draft.id);if(syncBackend()!=SyncBackend.SSH){val workspace=syncWorkspaceId();apiSync.queueNote(workspace,saved);val oldTags=current?.tagIds?.split(',')?.filter(String::isNotBlank)?.toSet().orEmpty();val newTags=saved.tagIds.split(',').filter(String::isNotBlank).toSet();(oldTags-newTags).forEach{apiSync.queueDelete(workspace,"page_tag","${saved.id}:$it")}};if(saved.deletedAt!=null||saved.completedAt!=null||saved.reminderAt==null)Reminders.cancel(appContext,saved.id)else Reminders.schedule(appContext,saved.id,saved.title,saved.reminderAt,saved.recurrence);requestSyncIfConfigured();saved
    }
    private fun sameEditableContent(a:NoteEntity,b:NoteEntity)=a.title==b.title&&a.body==b.body&&a.folderId==b.folderId&&a.folderName==b.folderName&&a.icon==b.icon&&a.parentPageId==b.parentPageId&&a.sortOrder==b.sortOrder&&a.treeUpdatedAt==b.treeUpdatedAt&&a.reminderAt==b.reminderAt&&a.recurrence==b.recurrence&&a.tagIds==b.tagIds&&a.deletedAt==b.deletedAt&&a.itemType==b.itemType&&a.dueAt==b.dueAt&&a.completedAt==b.completedAt&&a.important==b.important&&a.viewMode==b.viewMode
    private suspend fun subtree(id:String):List<NoteEntity>{
        val notes=dao.observeNoteSummaries().first();val result=mutableListOf<NoteEntity>();val queue=ArrayDeque<String>().apply{add(id)};val seen=mutableSetOf<String>()
        while(queue.isNotEmpty()){val current=queue.removeFirst();if(!seen.add(current))continue;loadEditable(current)?.let(result::add);notes.filter{it.parentPageId==current}.forEach{queue.add(it.id)}}
        return result
    }
    suspend fun moveToTrash(id:String){
        // Pages already in the trash keep their own deletion time, so restoring
        // this page later does not bring them back too.
        val targets=subtree(id).filter{it.id==id||it.deletedAt==null};targets.forEach{trashedNoteIds.add(it.id)}
        try{
            val deletedAt=System.currentTimeMillis()
            targets.forEach{target->val pending=pendingDrafts.remove(target.id);draftJobs.remove(target.id)?.cancelAndJoin();save((pending?:target).copy(deletedAt=deletedAt))}
        }catch(error:Throwable){targets.forEach{trashedNoteIds.remove(it.id)};throw error}
    }
    /**
     * Restores a page and the pages that were moved to the trash together with
     * it (same deletion time). Pages deleted earlier on their own stay in the
     * trash instead of reappearing as stray (often empty) notes. A page keeps
     * its parent link even while the parent is in the trash, so restoring the
     * parent later puts it back in place.
     */
    suspend fun restore(id:String){
        val root=loadEditable(id)?:return
        val batch=root.deletedAt
        // Breadth-first, so a parent is decided before its children.
        val restoring=mutableSetOf<String>()
        subtree(id).forEach{note->
            val sameBatch=note.id==id||batch==null||note.deletedAt==null||note.deletedAt==batch
            if(!sameBatch||(note.id!=id&&note.parentPageId !in restoring))return@forEach
            restoring+=note.id
            draftJobs.remove(note.id)?.cancelAndJoin();pendingDrafts.remove(note.id)
            trashedNoteIds.remove(note.id)
            save(note.copy(deletedAt=null),restoring=true)
        }
    }
    suspend fun movePage(id:String,parentId:String?):NoteEntity=withContext(NonCancellable){syncMutex.withLock{
        flushAll()
        val page=loadEditable(id)?:error("页面不存在")
        require(parentId!=id){"不能把页面移动到自身下面"}
        val all=dao.observeNoteSummaries().first()
        val descendants=mutableSetOf(id)
        var changed=true
        while(changed){changed=false;all.filter{it.parentPageId in descendants}.forEach{if(descendants.add(it.id))changed=true}}
        require(parentId !in descendants){"不能把页面移动到自己的子页面下面"}
        val parent=parentId?.let{target->all.firstOrNull{it.id==target&&it.deletedAt==null&&it.itemType=="note"}?:error("目标页面不存在")}
        require(parent==null||isEncrypted(parent)==isEncrypted(page)){"私密页面与普通页面不能互相移动"}
        val targetFolder=if(parent==null)page.folderId else parent.folderId
        val targetName=if(parent==null)page.folderName else parent.folderName
        val privateFolder=if(parent==null)encryptedFolderId(page) else encryptedFolderId(parent)
        val moveTargets=subtree(id)
        val previousTreeTime=moveTargets.maxOfOrNull{it.treeUpdatedAt}?:page.treeUpdatedAt
        val now=maxOf(System.currentTimeMillis(),previousTreeTime.coerceAtMost(Long.MAX_VALUE-1)+1)
        val siblings=all.filter{it.parentPageId==parentId&&it.id!=id&&it.deletedAt==null}
        val result=save(page.copy(parentPageId=parentId,folderId=targetFolder,folderName=targetName,sortOrder=(siblings.maxOfOrNull{it.sortOrder}?:0.0)+1000.0,treeUpdatedAt=now))
        moveTargets.forEach{note->
            if(privateFolder!=null)moveEncrypted(note.id,privateFolder)
            if(note.id!=id)save(note.copy(folderId=targetFolder,folderName=targetName,treeUpdatedAt=now))
        }
        result
    }}
    suspend fun deletePermanently(id:String)=withContext(NonCancellable){syncMutex.withLock{
        val targets=subtree(id)
        targets.forEach{permanentlyDeletedNoteIds.add(it.id);pendingDrafts.remove(it.id)}
        targets.forEach{draftJobs.remove(it.id)?.cancelAndJoin()}
        try{
            saveMutex.withLock{
                targets.forEach{note->
                    val assets=dao.assets(note.id)
                    val operations=if(syncBackend()==SyncBackend.SSH)emptyList()else{
                        val keys=buildList<Pair<String,String>>{
                            assets.forEach{add("asset" to it.id)}
                            dao.steps(note.id).forEach{add("task_step" to it.id)}
                            dao.pageLinks(note.id).forEach{add("page_link" to it.id)}
                            dao.remoteRevisionHeaders(note.id).forEach{add("revision" to it.id)}
                            note.tagIds.split(',').filter(String::isNotBlank).forEach{add("page_tag" to "${note.id}:$it")}
                            add("reading_position" to note.id);add("document" to note.id);add("page" to note.id)
                        }
                        keys.map{(type,key)->apiSync.noteDeletionOperation(syncWorkspaceId(),type,key,note.id)}
                    }
                    dao.permanentlyDeleteNote(TombstoneEntity("note|${note.id}",note.id,"note",System.currentTimeMillis(),deviceId()),operations)
                    Reminders.cancel(appContext,note.id)
                    assets.forEach{asset->asset.localPath?.let(::File)?.let{AttachmentStorageContract.deleteIfContained(appContext,it)}}
                    discardUnsavedMarker(note.id)
                }
            }
        }catch(error:Throwable){targets.forEach{if(dao.tombstone("note|${it.id}")==null)permanentlyDeletedNoteIds.remove(it.id)};throw error}
        requestSyncIfConfigured()
    }}
    suspend fun keepLocal(id:String){loadEditable(id)?.let{local->if(syncBackend()!=SyncBackend.SSH){val kept=local.copy(updatedAt=System.currentTimeMillis(),dirty=true,conflict=false,conflictSnapshotJson=null);dao.put(kept);apiSync.queueNote(syncWorkspaceId(),kept);requestSyncIfConfigured()}else{val remoteVersion=loadConflictSnapshot(id)?.let{payload->runCatching{val remote=JsonParser.parseString(payload).asJsonObject;if(remote["schemaVersion"]?.asInt==3)remote["metadata"].asJsonObject["legacyVersion"]?.asLong?:remote["metadata"].asJsonObject.isoMs("updatedAt") else remote["metadata"].asJsonObject["version"].asLong}.getOrNull()}?:0;dao.put(local.copy(snapshotJson=loadSnapshot(id),version=maxOf(local.version,remoteVersion)+1,updatedAt=System.currentTimeMillis(),dirty=true,conflict=false,conflictSnapshotJson=null))}}}
    suspend fun acceptRemote(id:String){val local=loadEditable(id)?:return;val snapshot=loadConflictSnapshot(id)?.let{runCatching{JsonParser.parseString(it).asJsonObject}.getOrNull()}?:return;if(syncBackend()!=SyncBackend.SSH&&snapshot.has("apiConflicts")){snapshot["apiConflicts"].asJsonObject.entrySet().forEach{(type,payload)->apiSync.acceptConflict(syncWorkspaceId(),type,id,payload.asJsonObject)}}else if(syncBackend()!=SyncBackend.SSH&&snapshot.has("apiEntityType")){apiSync.acceptConflict(syncWorkspaceId(),snapshot["apiEntityType"].asString,id,snapshot["payload"].asJsonObject)}else{val remote=if(snapshot["schemaVersion"]?.asInt==3)fromMarkdownEnvelope(snapshot)else fromEnvelope(JsonObject().apply{addProperty("noteID",id);add("currentSnapshot",snapshot)});val accepted=remote.copy(version=maxOf(local.version,remote.version)+1,dirty=true,conflict=false,conflictSnapshotJson=null,lastSyncedVersion=local.lastSyncedVersion);dao.put(accepted);indexReferences(accepted)}}
    suspend fun saveFolder(name:String,type:String="noteFolder",id:String=UUID.randomUUID().toString(),color:String?=null){
        require(name.isNotBlank()) { "笔记库名称不能为空" }
        val existing=dao.getFolder(id)
        val folder=(existing?:FolderEntity(id,name,dao.allFolders().size,type,notebookId=if(type=="noteFolder")notebookForNewLibrary(dao.allFolders(),dao.apiNotebooks()) else "personal")).copy(name=name.trim(),color=color?:existing?.color,updatedAt=System.currentTimeMillis())
        dao.putFolder(folder)
        if(syncBackend()!=SyncBackend.SSH)apiSync.queueFolder(syncWorkspaceId(),folder)
        requestSyncIfConfigured()
    }
    val allAssets=dao.observeAllAssets()
    val linkNoteIds=dao.observeLinkNoteIds()
    suspend fun searchSnippet(id:String,query:String):String=dao.searchSnippet(id,query).orEmpty()

    /** Moving a page carries its descendants, retaining the document tree inside one library. */
    suspend fun moveToLibrary(id:String,folder:FolderEntity?){
        val root=loadEditable(id)?:error("笔记不存在")
        require(folder==null||folder.type==if(isEncrypted(root))"encryptedFolder" else if(root.itemType=="todo")"todoList" else "noteFolder") { "目标笔记库类型不匹配" }
        val isPrivate=isEncrypted(root)
        require(!isPrivate || folder?.type=="encryptedFolder") { "私密笔记必须留在私密笔记库中" }
        flushAll()
        subtree(id).forEach{note->if(isPrivate)moveEncrypted(note.id,folder!!.id);save(note.copy(folderId=folder?.id,folderName=folder?.name?:"未分类",parentPageId=if(note.id==id)null else note.parentPageId,treeUpdatedAt=System.currentTimeMillis()))}
    }
    suspend fun dissolveLibrary(id:String,deleteNotes:Boolean){
        val folder=dao.getFolder(id)?:return
        require(folder.type=="noteFolder") { "请在原有管理界面处理此文件夹" }
        flushAll()
        val targets=dao.observeNoteSummaries().first().filter{it.folderId==id}
        if(deleteNotes)targets.filter{it.deletedAt==null}.forEach{moveToTrash(it.id)}
        targets.forEach{target->loadEditable(target.id)?.let{save(it.copy(folderId=null,folderName="未分类",treeUpdatedAt=System.currentTimeMillis()))}}
        deleteFolder(id)
    }
    suspend fun duplicateNote(id:String):NoteEntity=syncMutex.withLock{withContext(Dispatchers.IO){
        flushAll()
        val source=(loadEditable(id)?:error("笔记不存在")).copy(snapshotJson=loadSnapshot(id))
        require(source.deletedAt==null) { "请先恢复笔记" }
        val copyId=UUID.randomUUID().toString()
        val assetCopies=dao.assets(id).map{asset->
            val file=asset.localPath?.let{java.io.File(it)}
            require(file?.isFile==true) { "附件尚未下载，请同步后再创建副本" }
            val assetId=UUID.randomUUID().toString()
            val relative="$copyId/$assetId-${asset.filename}"
            val target=java.io.File(appContext.filesDir,"attachments/$relative")
            target.parentFile?.mkdirs();file!!.copyTo(target)
            asset to asset.copy(id=assetId,noteId=copyId,relativePath=relative,localPath=target.absolutePath,dirty=true)
        }
        val assetIds=assetCopies.associate{(old,new)->old.id to new.id}
        fun remapUris(text:String)=Regex("notebook-asset:([A-Za-z0-9._:-]+)").replace(text){match->assetIds[match.groupValues[1]]?.let{"notebook-asset:$it"}?:match.value}
        val body=remapUris(source.body)
        val now=System.currentTimeMillis()
        if(isEncrypted(source))encryptedFolderId(source)?.let{markEncrypted(copyId,it)}
        fun remapAssets(json:String?):String?{
            if(json==null||assetIds.keys.none{json.contains(it)})return json
            val descriptiveKeys=setOf("filename","title","caption","contenthash","sha256","hash","mimetype")
            fun walk(value:JsonElement,key:String=""):JsonElement=when{
                key.lowercase() in descriptiveKeys->value.deepCopy()
                value.isJsonObject->JsonObject().apply{value.asJsonObject.entrySet().forEach{(name,child)->add(assetIds[name]?:name,walk(child,name))}}
                value.isJsonArray->JsonArray().apply{value.asJsonArray.forEach{add(walk(it))}}
                value.isJsonPrimitive&&value.asJsonPrimitive.isString->JsonPrimitive(assetIds[value.asString]?:remapUris(value.asString))
                else->value.deepCopy()
            }
            return walk(JsonParser.parseString(json)).toString()
        }
        dao.apiDocument(id)?.let{dao.putApiDocument(it.copy(pageId=copyId,tiptapJson=remapAssets(it.tiptapJson)!!,updatedAt=now))}
        dao.apiPage(id)?.let{dao.putApiPage(it.copy(pageId=copyId,updatedAt=now))}
        val copy=save(source.copy(snapshotJson=remapAssets(source.snapshotJson),id=copyId,title="副本 ${source.title}",body=body,createdAt=now,updatedAt=now,parentPageId=null,treeUpdatedAt=now,reminderAt=null,recurrence="none",version=1,lastSyncedVersion=0,conflict=false,conflictSnapshotJson=null,viewMode="preview"))
        assetCopies.forEach{(_,asset)->dao.putAssets(listOf(asset));if(syncBackend()!=SyncBackend.SSH)apiSync.queueAsset(syncWorkspaceId(),asset)}
        dao.steps(id).forEach{saveStep(it.copy(id=UUID.randomUUID().toString(),noteId=copyId))}
        requestSyncIfConfigured()
        copy
    }}
    suspend fun deleteFolder(id:String){if(syncBackend()!=SyncBackend.SSH)apiSync.queueDelete(syncWorkspaceId(),"section",id);dao.deleteFolder(id);dao.putTombstone(TombstoneEntity("folder|$id",id,"folder",System.currentTimeMillis(),deviceId()));requestSyncIfConfigured()}
    suspend fun saveTag(name:String,color:String="gray",id:String=UUID.randomUUID().toString()){val tag=TagEntity(id,name,color);dao.putTag(tag);if(syncBackend()!=SyncBackend.SSH)apiSync.queueTag(syncWorkspaceId(),tag);requestSyncIfConfigured()}
    suspend fun deleteTag(id:String){if(syncBackend()!=SyncBackend.SSH)apiSync.queueDelete(syncWorkspaceId(),"tag",id);dao.deleteTag(id);dao.putTombstone(TombstoneEntity("tag|$id",id,"tag",System.currentTimeMillis(),deviceId()));requestSyncIfConfigured()}
    fun steps(noteId:String)=dao.observeSteps(noteId)
    fun assets(noteId:String)=dao.observeAssets(noteId)
    suspend fun assetList(noteId:String)=dao.assets(noteId)
    suspend fun saveStep(step:TodoStepEntity){dao.putStep(step);if(syncBackend()!=SyncBackend.SSH)apiSync.queueStep(syncWorkspaceId(),step);loadEditable(step.noteId)?.let{save(it)};requestSyncIfConfigured()}
    private val stepsLock=Mutex()
    private val pendingStepLists=java.util.concurrent.ConcurrentHashMap<String,Pair<List<TodoStepEntity>,NoteEntity>>()
    /**
     * Makes [steps] the todo's complete step list: blank steps are dropped and the order is renumbered.
     * Only changed or removed steps are queued for sync, and the todo is republished once from [note]
     * (the editor's latest draft) so a pending title edit is not overwritten by the stored copy.
     * Calls are serialized; when several are waiting, only the latest list for a todo is written.
     */
    fun saveStepsAsync(noteId:String,steps:List<TodoStepEntity>,note:NoteEntity):Job{
        pendingStepLists[noteId]=steps to note
        return appScope.launch{stepsLock.withLock{
            val (latest,latestNote)=pendingStepLists.remove(noteId)?:return@withLock
            runCatching{saveSteps(noteId,latest,latestNote)}.onFailure{_saveError.value="保存步骤失败：${it.localizedMessage?:"请重试"}"}
        }}
    }
    private suspend fun saveSteps(noteId:String,steps:List<TodoStepEntity>,note:NoteEntity){
        val next=steps.filter{it.text.isNotBlank()}.mapIndexed{index,step->step.copy(noteId=noteId,sortOrder=index)}
        val previous=dao.steps(noteId);val previousById=previous.associateBy{it.id};val nextIds=next.mapTo(HashSet()){it.id}
        val changed=next.filter{previousById[it.id]!=it};val removed=previous.filter{it.id !in nextIds}
        if(changed.isEmpty()&&removed.isEmpty())return
        val workspace=if(syncBackend()!=SyncBackend.SSH)syncWorkspaceId()else null
        changed.forEach{dao.putStep(it);if(workspace!=null)apiSync.queueStep(workspace,it)}
        removed.forEach{if(workspace!=null)apiSync.queueDelete(workspace,"task_step",it.id);dao.deleteStep(it.id)}
        save(note);requestSyncIfConfigured()
    }
    suspend fun deleteStep(id:String){val noteId=dao.getStep(id)?.noteId;if(syncBackend()!=SyncBackend.SSH)apiSync.queueDelete(syncWorkspaceId(),"task_step",id);dao.deleteStep(id);noteId?.let{loadEditable(it)?.let{n->save(n)}}}
    suspend fun attach(noteId:String,uri:android.net.Uri):AssetEntity=withContext(Dispatchers.IO){
        val resolver=appContext.contentResolver;val id=UUID.randomUUID().toString();val mime=resolver.getType(uri)?:"application/octet-stream"
        var name="attachment";resolver.query(uri,arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),null,null,null)?.use{if(it.moveToFirst())name=it.getString(0)?:name}
        name=name.replace(Regex("[^A-Za-z0-9._\\-\\u4e00-\\u9fff]"),"_");val relative="$noteId/$id-$name";val target=java.io.File(appContext.filesDir,"attachments/$relative");target.parentFile?.mkdirs();resolver.openInputStream(uri).use{input->requireNotNull(input){"无法读取附件"}.copyTo(target.outputStream())}
        val kind=when{mime.startsWith("image/")->"image";mime.startsWith("audio/")->"audio";else->"file"};AssetEntity(id,noteId,kind,name,mime,relative,target.absolutePath,target.inputStream().use(::sha),target.length(),true).also{dao.putAssets(listOf(it));if(syncBackend()!=SyncBackend.SSH)apiSync.queueAsset(syncWorkspaceId(),it);loadEditable(noteId)?.let{n->save(n)};requestSyncIfConfigured()}
    }
    fun commitRecordedAudioAsync(noteId:String,file:java.io.File):Job=appScope.launch{
        runCatching{addRecordedAudio(noteId,file)}.onFailure{_saveError.value="保存录音失败，录音文件仍保留：${it.localizedMessage}"}
    }
    fun recordingFile(noteId:String)=java.io.File(appContext.filesDir,"recordings/$noteId/${System.currentTimeMillis()}.m4a")
    suspend fun addRecordedAudio(noteId:String,file:java.io.File):AssetEntity=withContext(Dispatchers.IO){require(file.isFile&&file.length()>0){"录音文件为空"};val id=UUID.randomUUID().toString();val relative="$noteId/$id-${file.name}";val target=java.io.File(appContext.filesDir,"attachments/$relative");target.parentFile?.mkdirs();file.copyTo(target,true);file.delete();AssetEntity(id,noteId,"audio",target.name,"audio/mp4",relative,target.absolutePath,target.inputStream().use(::sha),target.length(),true).also{dao.putAssets(listOf(it));if(syncBackend()!=SyncBackend.SSH)apiSync.queueAsset(syncWorkspaceId(),it);loadEditable(noteId)?.let{n->save(n)};requestSyncIfConfigured()}}
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

    data class ImportReport(val imported:List<NoteEntity>,val skipped:List<io.github.notebook.android.data.TextImport.Skip>){val message get()=io.github.notebook.android.data.TextImport.summary(imported.size,skipped)}

    /**
     * Imports Markdown / plain-text / HTML files as notes into [folder], under
     * [parentPageId] when given. Unsupported or unreadable files are reported,
     * not fatal.
     */
    suspend fun importFiles(uris:List<android.net.Uri>,folder:FolderEntity?,parentPageId:String?=null):ImportReport=withContext(Dispatchers.IO){
        val resolver=appContext.contentResolver
        val imported=mutableListOf<NoteEntity>();val skipped=mutableListOf<io.github.notebook.android.data.TextImport.Skip>()
        val siblings=notes.first().filter{it.folderId==folder?.id&&it.parentPageId==parentPageId&&it.deletedAt==null}
        var sortOrder=(siblings.maxOfOrNull{it.sortOrder}?:0.0)+1000.0
        val parent=parentPageId?.let{dao.get(it)}
        val encryptedFolderId=if(folder?.type=="encryptedFolder")folder.id else parent?.takeIf{isEncrypted(it)}?.let{encryptedFolderId(it)}
        for(uri in uris){
            var name="导入笔记";var declaredSize:Long?=null
            runCatching{resolver.query(uri,arrayOf(android.provider.OpenableColumns.DISPLAY_NAME,android.provider.OpenableColumns.SIZE),null,null,null)?.use{cursor->if(cursor.moveToFirst()){name=cursor.getString(0)?:name;if(!cursor.isNull(1))declaredSize=cursor.getLong(1)}}}
            val kind=io.github.notebook.android.data.TextImport.kind(name)
            if(kind==null){skipped+=io.github.notebook.android.data.TextImport.Skip(name,"不支持的格式");continue}
            if((declaredSize?:0)>io.github.notebook.android.data.TextImport.MAX_BYTES){skipped+=io.github.notebook.android.data.TextImport.Skip(name,"超过 5 MB");continue}
            try{
                val bytes=resolver.openInputStream(uri).use{input->requireNotNull(input){"无法读取"};val output=ByteArrayOutputStream();val buffer=ByteArray(64*1024);var total=0L;while(true){val count=input.read(buffer);if(count<0)break;total+=count;require(total<=io.github.notebook.android.data.TextImport.MAX_BYTES){"超过 5 MB"};output.write(buffer,0,count)};output.toByteArray()}
                val source=io.github.notebook.android.data.TextImport.decode(bytes)
                if(source.isBlank()){skipped+=io.github.notebook.android.data.TextImport.Skip(name,"空文件");continue}
                val title=io.github.notebook.android.data.TextImport.title(name,source,kind)
                val body=io.github.notebook.android.data.TextImport.toMarkdown(kind,source,title)
                val id=UUID.randomUUID().toString()
                if(encryptedFolderId!=null)markEncrypted(id,encryptedFolderId)
                imported+=save(NoteEntity(id=id,title=title,body=body,folderId=folder?.id,folderName=folder?.name?:"未分类",parentPageId=parentPageId,sortOrder=sortOrder,tagIds=""))
                sortOrder+=1000.0
            }catch(error:Throwable){skipped+=io.github.notebook.android.data.TextImport.Skip(name,(error.message?:"无法读取").take(40))}
        }
        ImportReport(imported,skipped)
    }

    /** The "待处理" library that collects files opened or shared into Notebook; created on first use. */
    suspend fun inboxFolder():FolderEntity=withContext(Dispatchers.IO){
        dao.allFolders().firstOrNull{it.name==INBOX_NAME&&it.type=="noteFolder"}?:run{saveFolder(INBOX_NAME);dao.allFolders().first{it.name==INBOX_NAME&&it.type=="noteFolder"}}
    }

    /** Name and first lines of each incoming file, for the "how to handle" sheet. */
    suspend fun previewIncoming(uris:List<android.net.Uri>):List<Pair<String,String>> =withContext(Dispatchers.IO){
        uris.map{uri->
            var name=uri.lastPathSegment?.substringAfterLast('/')?:"文件"
            runCatching{appContext.contentResolver.query(uri,arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),null,null,null)?.use{c->if(c.moveToFirst())c.getString(0)?.let{name=it}}}
            val snippet=if(io.github.notebook.android.data.TextImport.kind(name)==null)"不支持的格式" else runCatching{
                appContext.contentResolver.openInputStream(uri)?.use{input->val buffer=ByteArray(4096);val count=input.read(buffer).coerceAtLeast(0);io.github.notebook.android.data.TextImport.decode(buffer.copyOf(count))}
                    ?.lineSequence()?.map(String::trim)?.filter(String::isNotEmpty)?.take(3)?.joinToString(" · ")?.take(160).orEmpty()
            }.getOrElse{"无法读取：${it.message}"}
            name to snippet
        }
    }

    /** Text shared from another app becomes one note. */
    suspend fun importSharedText(text:String,subject:String?,folder:FolderEntity?,parentPageId:String?=null):NoteEntity=withContext(Dispatchers.IO){
        val title=subject?.trim()?.takeIf(String::isNotEmpty)?.take(80)?:text.lineSequence().map{it.replace(Regex("^#+\\s*"),"").trim()}.firstOrNull(String::isNotEmpty)?.take(40)?:"分享的内容"
        val siblings=notes.first().filter{it.folderId==folder?.id&&it.parentPageId==parentPageId&&it.deletedAt==null}
        val id=UUID.randomUUID().toString()
        val parent=parentPageId?.let{dao.get(it)}
        (if(folder?.type=="encryptedFolder")folder.id else parent?.takeIf{isEncrypted(it)}?.let{encryptedFolderId(it)})?.let{markEncrypted(id,it)}
        save(NoteEntity(id=id,title=title,body=text.replace("\r\n","\n").trim(),folderId=folder?.id,folderName=folder?.name?:"未分类",parentPageId=parentPageId,sortOrder=(siblings.maxOfOrNull{it.sortOrder}?:0.0)+1000.0,tagIds=""))
    }

    /** Copy a user-selected private key into app-private storage and persist its path. */
    suspend fun importPrivateKey(uri:android.net.Uri,passphrase:String=""):String=withContext(Dispatchers.IO){
        val resolver=appContext.contentResolver
        var declaredSize:Long?=null
        resolver.query(uri,arrayOf(android.provider.OpenableColumns.SIZE),null,null,null)?.use{cursor->if(cursor.moveToFirst()&&!cursor.isNull(0))declaredSize=cursor.getLong(0)}
        require((declaredSize?:0)<=1_048_576){"私钥文件超过 1 MB，已拒绝导入"}
        val id=UUID.randomUUID().toString()
        val target=java.io.File(appContext.filesDir,"ssh/id_$id");target.parentFile?.mkdirs()
        resolver.openInputStream(uri).use{input->requireNotNull(input){"无法读取私钥文件"}.copyTo(target.outputStream())}
        require(target.length()<=1_048_576){"私钥文件超过 1 MB，已拒绝导入"}
        target.setReadable(false,false);target.setReadable(true,true)
        saveSettings(settings().copy(privateKeyPath=target.absolutePath,privateKeyPassphrase=passphrase))
        target.absolutePath
    }
    fun clearPrivateKey(){saveSettings(settings().copy(privateKeyPath="",privateKeyPassphrase=""))}

    suspend fun sync(onProgress:(String)->Unit={}){
        val operationId=DiagnosticLogger.operationId()
        val startedAt=System.currentTimeMillis()
        DiagnosticLogger.log(DiagnosticLevel.INFO,DiagnosticEvent.SYNC_START,operationId=operationId,counts=diagnosticSyncCounts())
        try{
            performSync(onProgress)
            prefs.edit().putLong("lastSyncSuccessAt",System.currentTimeMillis()).apply()
            DiagnosticLogger.log(DiagnosticLevel.INFO,DiagnosticEvent.SYNC_SUCCESS,operationId=operationId,durationMs=System.currentTimeMillis()-startedAt,counts=diagnosticSyncCounts())
        }catch(error:Throwable){
            if(error is CancellationException)throw error
            DiagnosticLogger.log(DiagnosticLevel.ERROR,DiagnosticEvent.SYNC_FAILURE,operationId=operationId,error=error,durationMs=System.currentTimeMillis()-startedAt,counts=diagnosticSyncCounts())
            throw error
        }
    }

    private suspend fun diagnosticSyncCounts():Map<String,Long> = runCatching{
        val workspace=if(syncBackend()==SyncBackend.SSH)null else syncWorkspaceId()
        mapOf("outbox" to (workspace?.let{dao.apiOutboxCount(it)}?:0).toLong(),"dirtyNotes" to dao.dirtyNoteCount().toLong(),"dirtyAssets" to dao.dirtyAssetCount().toLong())
    }.getOrDefault(emptyMap())

    private suspend fun performSync(onProgress:(String)->Unit):Unit {
        onProgress("正在等待其他同步任务…")
        syncMutex.withLock{withContext(Dispatchers.IO){
        onProgress("正在保存本地更改…")
        flushAll()
        queueDirtyReadingPositions()
        val backend=syncBackend()
        val workspace=if(backend==SyncBackend.SSH)null else syncWorkspaceId()
        // Both API and WebDAV publish at most 200 outbox rows per pass. Capture
        // the pre-sync size so malformed rows do not cause a busy retry loop,
        // while a genuine backlog always receives its next durable worker.
        val outboxBefore=workspace?.let{dao.apiOutboxCount(it)}?:0
        when(backend){SyncBackend.API->{apiSync.sync(apiSettings());Reminders.reconcile(appContext,dao.reminders());workspace?.let{scheduleNextOutboxBatch(it,outboxBefore)};return@withContext};SyncBackend.WEBDAV->{webdavSync.sync(webdavSettings(),onProgress);Reminders.reconcile(appContext,dao.reminders());workspace?.let{scheduleNextOutboxBatch(it,outboxBefore)};return@withContext};SyncBackend.SSH->Unit}
        val s=settings(); require(s.host.isNotBlank()&&s.username.isNotBlank()){ "请先配置 SSH 服务器" }
        require(s.password.isNotBlank()||s.privateKeyPath.isNotBlank()){ "请配置 SSH 私钥文件路径或密码" }
        val verifier=FingerprintHostKeyRepository(s.host,s.fingerprint)
        val jsch=JSch().apply{hostKeyRepository=verifier}
        // Private key auth takes precedence when configured; password is only
        // used when no key is provided (the server may have disabled password auth).
        if(s.privateKeyPath.isNotBlank())jsch.addIdentity(s.privateKeyPath,s.privateKeyPassphrase)
        val session=jsch.getSession(s.username,s.host,s.port).apply{
            if(s.privateKeyPath.isBlank())setPassword(s.password)
            setConfig("PreferredAuthentications",if(s.privateKeyPath.isNotBlank())"publickey"else"password")
            // Android runtimes do not consistently expose an Ed25519 provider.
            // Prefer the broadly supported ECDSA host key, then fall back to the
            // remaining modern algorithms. The exact negotiated key is pinned.
            setConfig("server_host_key","ecdsa-sha2-nistp256,ssh-ed25519,rsa-sha2-512,rsa-sha2-256")
            setConfig("StrictHostKeyChecking","yes")
            setServerAliveInterval(15_000)
            setServerAliveCountMax(3)
            setTimeout(30_000)
        }
        try{session.connect(15_000)}catch(error:com.jcraft.jsch.JSchException){
            verifier.presentedFingerprint?.takeIf{it!=s.fingerprint}?.let{
                if(s.fingerprint.isBlank())throw HostKeyConfirmationRequiredException(it)
                throw HostKeyChangedException(s.fingerprint,it)
            }
            throw error
        }
        val ch=(session.openChannel("sftp") as ChannelSftp).apply{connect(15_000)}
        val resolved=s.copy(path=resolveRemoteRepositoryPath(s.path,ch.home))
        remoteReadBudget=RemoteTransferLimits.Budget()
        try {
            mkdirs(ch,resolved.path);mkdirs(ch,"${resolved.path}/notes");mkdirs(ch,"${resolved.path}/attachments")
            withRemoteRepositoryWriteLock(ch,resolved){lock->
                verifyPrivacyKeyCheck(ch,resolved)
                val indexPath="${resolved.path}/notes/index.json"
                val initialIndexBytes=readRemoteBytesOrNull(ch,indexPath)
                val initialIndex=initialIndexBytes?.let(::parseJsonObject)
                pullLibrary(ch,resolved);pullNotes(ch,resolved,initialIndex);pullAssets(ch,resolved);syncReadingPositions(ch,resolved)
                refreshRemoteRepositoryWriteLock(ch,lock)
                // Assets and metadata must exist before note JSON and index.json publish references to them.
                pushLibrary(ch,resolved);pushAssets(ch,resolved,initialIndex)
                refreshRemoteRepositoryWriteLock(ch,lock)
                ensurePrivacyKeyCheck(ch,resolved)
                val currentIndexBytes=readRemoteBytesOrNull(ch,indexPath)
                require(RepositoryIndexContract.hasSameSnapshot(initialIndexBytes,currentIndexBytes)){"远端索引在同步期间已变化，请重新同步"}
                pushNotes(ch,resolved,currentIndexBytes?.let(::parseJsonObject))
                Reminders.reconcile(appContext,dao.reminders())
            }
        } finally { remoteReadBudget=null;ch.disconnect();session.disconnect() }
    }}
    }

    private suspend fun pullLibrary(c:ChannelSftp,s:SshSettings){
        val root=readJson(c,"${s.path}/library.json")?:return
        val exportedAt=root.optDateMs("exportedAt")?:System.currentTimeMillis()
        val folders=root["folders"]?.asJsonArray?.map{j->val o=j.asJsonObject;FolderEntity(o.str("id"),o.str("name"),o.int("sortOrder"),o.str("folderType","noteFolder"),o.optDateMs("updatedAt")?:o.optDateMs("createdAt")?:exportedAt)}.orEmpty()
        val encrypted=root["encryptedData"]?.takeIf{it.isJsonObject}?.asJsonObject
        val encryptedFolders=encrypted?.get("encryptedFolders")?.takeIf{it.isJsonArray}?.asJsonArray?.map{j->val o=j.asJsonObject;FolderEntity(o.str("id"),o.str("name"),o.int("order"),"encryptedFolder",exportedAt)}.orEmpty()
        encrypted?.let{data->val ids=data["encryptedNoteIDs"]?.takeIf{it.isJsonArray}?.asJsonArray?.map{it.asString}.orEmpty();val mapping=data["noteToFolderMapping"]?.takeIf{it.isJsonObject}?.asJsonObject?.entrySet()?.associate{it.key to it.value.asString}.orEmpty();privacy.update{PrivateNoteIndex.Snapshot(ids.toSet(),mapping)}}
        val tags=root["tags"]?.asJsonArray?.map{j->val o=j.asJsonObject;TagEntity(o.str("id"),o.str("name"),o.str("color","gray"),o.optDateMs("updatedAt")?:o.optDateMs("createdAt")?:exportedAt)}.orEmpty()
        val tombstones=dao.tombstones().associateBy{it.itemKey}
        folders.filter{remote->val deleted=tombstones["folder|${remote.id}"];deleted==null||deleted.deletedAt<remote.updatedAt}.forEach{remote->if((dao.getFolder(remote.id)?.updatedAt?:0)<=remote.updatedAt)dao.putFolder(remote)}
        encryptedFolders.forEach{dao.putFolder(it)}
        tags.filter{remote->val deleted=tombstones["tag|${remote.id}"];deleted==null||deleted.deletedAt<remote.updatedAt}.forEach{remote->if((dao.getTag(remote.id)?.updatedAt?:0)<=remote.updatedAt)dao.putTag(remote)}
        root["deletedFolders"]?.takeIf{it.isJsonArray}?.asJsonArray?.forEach{e->val x=e.asJsonObject;val id=x.str("id");val at=x.dateMs("deletedAt");if((dao.getFolder(id)?.updatedAt?:0)<=at){dao.deleteFolder(id);dao.putTombstone(TombstoneEntity("folder|$id",id,"folder",at,x.str("deletedByDeviceID")))}}
        root["deletedTags"]?.takeIf{it.isJsonArray}?.asJsonArray?.forEach{e->val x=e.asJsonObject;val id=x.str("id");val at=x.dateMs("deletedAt");if((dao.getTag(id)?.updatedAt?:0)<=at){dao.deleteTag(id);dao.putTombstone(TombstoneEntity("tag|$id",id,"tag",at,x.str("deletedByDeviceID")))}}
    }
    private suspend fun pullNotes(c:ChannelSftp,s:SshSettings,idx:JsonObject?){
        if(idx==null)return
        logInvalidRemoteIndexIDs(idx)
        val deletions=RepositoryIndexSanitizer.deletions(idx)
        val entries=normalizedIndexEntries(idx)
        val permanentlyDeleted=dao.tombstones().filter{it.itemType=="note"}.map{it.itemId}.toSet()
        deletions.forEach { deletion ->
            val id=deletion.str("noteID"); val deletedAt=deletion.flexibleDateMs("deletedAt")
            val local=loadEditable(id)
            if(local!=null && (!local.dirty || deletedAt>=local.updatedAt)){val deleted=local.copy(deletedAt=deletedAt,dirty=false);dao.put(deleted);indexReferences(deleted)}
        }
        entries.forEach { entry ->
            val e=entry;val id=e.str("noteID");val remoteVersion=e["version"].asLong;val local=loadEditable(id)
            if(id in permanentlyDeleted)return@forEach
            if(local==null||(!local.dirty&&remoteVersion>local.version)) readNoteJson(c,s,id,idx)?.let{env->validateMarkdownEnvelope(env,e.str("contentHash"));val decoded=decodeEnvelope(env);dao.put(decoded);indexReferences(decoded);dao.putAssets(decodedAssets(env));decodedSteps(env).forEach{dao.putStep(it)}}
            else if(local.dirty&&remoteVersion>local.lastSyncedVersion) readNoteJson(c,s,id,idx)?.let{remote->
                if(remote["schemaVersion"]?.asInt==3){
                    validateMarkdownEnvelope(remote,e.str("contentHash"))
                    val remoteHash=remote.str("currentContentHash")
                    val remoteNote=fromMarkdownEnvelope(remote)
                    val localWithNewestTree=mergeNewestTree(local,remoteNote)
                    val baselineHash=loadSnapshot(id)?.let{snapshot->runCatching{JsonParser.parseString(snapshot).asJsonObject["currentContentHash"]?.asString}.getOrNull()}
                    val storedConflictHash=loadConflictSnapshot(id)?.let{snapshot->runCatching{JsonParser.parseString(snapshot).asJsonObject["currentContentHash"]?.asString}.getOrNull()}
                    val legacyVersion=remote["metadata"].asJsonObject["legacyVersion"]?.asLong
                    val unchangedBaseline=baselineHash==remoteHash||(local.conflict&&legacyVersion==local.lastSyncedVersion&&storedConflictHash==remoteHash)
                    when{
                        remoteHash==sha(local.body)->localWithNewestTree.copy(version=remoteVersion,dirty=false,conflict=false,conflictSnapshotJson=null,snapshotJson=gson.toJson(remote),lastSyncedVersion=remoteVersion).also{dao.put(it);indexReferences(it)}
                        unchangedBaseline->localWithNewestTree.copy(version=maxOf(local.version,remoteVersion)+1,conflict=false,conflictSnapshotJson=null,snapshotJson=gson.toJson(remote)).also{dao.put(it);indexReferences(it)}
                        else->dao.put(localWithNewestTree.copy(conflict=true,conflictSnapshotJson=gson.toJson(remote)))
                    }
                }else dao.put(local.copy(conflict=true,conflictSnapshotJson=gson.toJson(remote["currentSnapshot"])))
            }
        }
    }
    private suspend fun pullAssets(c:ChannelSftp,s:SshSettings){
        val manifest=readJson(c,"${s.path}/notes/assets_manifest.json")?:return
        val known=dao.allNoteIds().flatMap{dao.assets(it)}.associateBy{it.relativePath}
        manifest["entries"]?.asJsonArray?.forEach{raw->val e=raw.asJsonObject;val relative=safeRelative(e.str("relativePath"));val expected=e.str("contentHash");val asset=known[relative]?:return@forEach;val target=java.io.File(appContext.filesDir,"attachments/$relative");if(!target.exists()||target.inputStream().use(::sha)!=expected){target.parentFile?.mkdirs();val tmp=java.io.File(target.parentFile,"${target.name}.download");val remotePath=if(relative.startsWith("objects/"))"${s.path}/$relative" else "${s.path}/attachments/$relative";try{downloadRemoteFile(c,remotePath,tmp);require(tmp.inputStream().use(::sha)==expected){"附件校验失败：$relative"};if(!tmp.renameTo(target)){tmp.copyTo(target,true);tmp.delete()}}catch(error:Throwable){tmp.delete();throw error};dao.putAssets(listOf(asset.copy(localPath=target.absolutePath,contentHash=expected,size=target.length(),dirty=false)))}}
    }
    private suspend fun pushAssets(c:ChannelSftp,s:SshSettings,index:JsonObject?){
        val remote=readJson(c,"${s.path}/notes/assets_manifest.json");val contentAddressed=CompressedRepositoryContract.version(index)==3;val entries=linkedMapOf<String,JsonObject>();remote?.get("entries")?.asJsonArray?.forEach{entries[it.asJsonObject.str("relativePath")]=it.asJsonObject.deepCopy()}
        // SSH predates the WebDAV/API privacy boundary.  Its privacy envelope
        // never encrypted attachment bytes, so a private asset must stay local
        // (and dirty) until a future encrypted attachment protocol exists.
        // Legacy manifests encode their original relative path, which lets us
        // remove a previously leaked private reference safely. Content-addressed
        // object names carry no owner information and are deliberately retained:
        // pruning by hash could hide an ordinary asset with identical bytes.
        val encryptedNotes=encryptedNoteIds()
        val privateAssets=encryptedNotes.flatMap{dao.assets(it)}
        SshAssetManifestContract.removeIdentifiablePrivateEntries(entries,privateAssets)
        val confirmed=mutableListOf<AssetEntity>();SshAssetManifestContract.uploadableAssets(dao.dirtyAssets(),encryptedNotes).forEach{a->
            val file=a.localPath?.let{path->java.io.File(path)}?.takeIf{it.isFile}?:error("本地附件文件缺失，已取消远程发布");val hash=file.inputStream().use(::sha);val relative=if(contentAddressed)"objects/${hash.take(2)}/$hash" else safeRelative(a.relativePath);val target=if(contentAddressed)"${s.path}/$relative" else "${s.path}/attachments/$relative";mkdirs(c,target.substringBeforeLast('/'));atomicUpload(c,target,file,hash);entries[relative]=JsonObject().apply{addProperty("relativePath",relative);addProperty("contentHash",hash);addProperty("size",file.length())};confirmed+=a.copy(relativePath=relative,contentHash=hash,size=file.length(),dirty=false)}
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
        val bytes=readRemoteBytesOrNull(c,lock.ownerPath)?:return@runCatching null
        require(bytes.size<=4*1024){"远端同步锁文件异常"}
        String(bytes,StandardCharsets.UTF_8).trim()
    }.getOrNull()
    private suspend fun pushNotes(c:ChannelSftp,s:SshSettings,remote:JsonObject?){
        val compressed=CompressedRepositoryContract.enabled(remote)
        val entries=linkedMapOf<String,JsonObject>();normalizedIndexEntries(remote).forEach{entries[it.str("noteID")]=it.deepCopy()}
        val deleted=linkedMapOf<String,JsonObject>();RepositoryIndexSanitizer.deletions(remote).forEach{deleted[it.str("noteID")]=it.deepCopy()}
        dao.tombstones().filter{it.itemType=="note"&&RepositoryIdentityContract.isValidNoteID(it.itemId)}.forEach{t->deleted[t.itemId]=JsonObject().apply{addProperty("noteID",t.itemId);addProperty("deletedAt",swiftDate(t.deletedAt));addProperty("deletedByDeviceID",t.deviceId)};entries.remove(t.itemId)}
        val confirmed=mutableListOf<Triple<String,Long,String?>>();dao.dirtyNoteIds().mapNotNull{loadEditable(it)?.copy(snapshotJson=loadSnapshot(it))}.filter{!it.conflict}.forEach{n->
            if(n.deletedAt!=null){deleted[n.id]=JsonObject().apply{addProperty("noteID",n.id);addProperty("deletedAt",swiftDate(n.deletedAt));addProperty("deletedByDeviceID",deviceId())};entries.remove(n.id);confirmed+=Triple(n.id,n.version,null)}
            else {val repositoryVersion=CompressedRepositoryContract.version(remote);val privateNote=n.id in encryptedNoteIds();if(privateNote&&!SshPrivacyCompatibilityContract.canPublishPrivateNote(repositoryVersion))return@forEach;val previousEnvelope=readNoteJson(c,s,n.id,remote);val env=if(repositoryVersion==3)toMarkdownEnvelope(n,previousEnvelope)else toEnvelope(n,previousEnvelope);if(privateNote)require(s.password.isNotBlank()){"隐私笔记 ${n.title} 无法安全同步：私钥认证未配置 SSH 密码"};val stored=if(privateNote)encryptPrivacyEnvelope(stripPrivateAttachments(env),s.password)else env;val text=gson.toJson(stored);val path=CompressedRepositoryContract.notePath(s.path,n.id,remote);if(compressed)atomicWrite(c,path,CompressedRepositoryContract.encode(text))else atomicWrite(c,path,text);val contentHash=env["currentContentHash"]?.asString?:sha(gson.toJson(env["currentSnapshot"]));entries[n.id]=JsonObject().apply{addProperty("noteID",n.id);addProperty("version",n.version);addProperty("contentHash",contentHash);addProperty("historyCount",env["history"]?.asJsonArray?.size()?:0)};deleted.remove(n.id);val snapshot=if(env["schemaVersion"]?.asInt==3)gson.toJson(env) else gson.toJson(env["currentSnapshot"]);confirmed+=Triple(n.id,n.version,snapshot)}
        }
        val idx=JsonObject().apply{addProperty("generatedAt",swiftDate(System.currentTimeMillis()));addProperty("deviceID",deviceId());add("entries",JsonArray().apply{entries.values.forEach(::add)});add("deletedEntries",JsonArray().apply{deleted.values.forEach(::add)});remote?.get("repositoryFormat")?.takeIf{it.isJsonObject}?.let{add("repositoryFormat",it.deepCopy())}}
        // A freshly configured device often has no local changes after pulling.
        // Do not rewrite an otherwise identical index merely to change its
        // timestamp/device ID: that creates avoidable optimistic-lock retries
        // for Electron clients syncing at the same time.
        if(!RepositoryIndexContract.hasSameContent(remote,idx))atomicWrite(c,"${s.path}/notes/index.json",gson.toJson(idx))
        confirmed.forEach{(id,version,snapshot)->dao.markSynced(id,version,snapshot)}
    }
    private suspend fun toMarkdownEnvelope(n:NoteEntity,previous:JsonObject?):JsonObject {
        val currentHash=sha(n.body)
        val contents=previous?.get("contentObjects")?.takeIf{it.isJsonObject}?.asJsonObject?.deepCopy()?:JsonObject()
        val history=previous?.get("history")?.takeIf{it.isJsonArray}?.asJsonArray?.deepCopy()?:JsonArray()
        val previousHash=previous?.get("currentContentHash")?.takeUnless{it.isJsonNull}?.asString
        val lastHistoryHash=if(history.size()>0)history[history.size()-1].asJsonObject["contentHash"]?.asString else null
        val previousIcon=previous?.get("metadata")?.takeIf{it.isJsonObject}?.asJsonObject?.get("icon")?.takeUnless{it.isJsonNull}?.asString
        val lastHistoryIcon=if(history.size()>0)history[history.size()-1].asJsonObject["pageIcon"]?.takeUnless{it.isJsonNull}?.asString else null
        if(previousHash!=null&&(previousHash!=currentHash||previousIcon!=n.icon)&&(lastHistoryHash!=previousHash||lastHistoryIcon!=previousIcon)) history.add(JsonObject().apply{addProperty("id",UUID.randomUUID().toString());addProperty("createdAt",iso(n.updatedAt));addProperty("updatedAt",iso(n.updatedAt));addProperty("reason","autosave");addProperty("contentHash",previousHash);add("pageIcon",previous?.get("metadata")?.takeIf{it.isJsonObject}?.asJsonObject?.get("icon")?:com.google.gson.JsonNull.INSTANCE)})
        contents.addProperty(currentHash,n.body)
        // Preserve metadata written by newer clients. Android only overwrites fields it owns,
        // so a round trip cannot silently erase hierarchy or future extension properties.
        val metadata=previous?.get("metadata")?.takeIf{it.isJsonObject}?.asJsonObject?.deepCopy()?:JsonObject()
        metadata.apply{
            addProperty("id",n.id);addProperty("workspaceId","00000000-0000-4000-8000-000000000001");n.folderId?.let{addProperty("sectionId",it)}
            if(n.folderId==null)remove("sectionId")
            n.icon?.let{addProperty("icon",it)}?:remove("icon")
            n.parentPageId?.let{addProperty("parentPageId",it)}?:remove("parentPageId")
            addProperty("sortOrder",n.sortOrder);addProperty("treeUpdatedAt",iso(n.treeUpdatedAt))
            addProperty("kind",if(n.itemType=="todo")"task" else "document");addProperty("title",n.title);addProperty("preview",n.previewText);addProperty("favorite",n.important)
            addProperty("createdAt",iso(n.createdAt));addProperty("updatedAt",iso(n.updatedAt));addProperty("syncStatus","synced")
            n.reminderAt?.let{addProperty("reminderAt",iso(it))};addProperty("recurrenceRule",n.recurrence);n.dueAt?.let{addProperty("dueAt",iso(it))};n.completedAt?.let{addProperty("completedAt",iso(it))};addProperty("important",n.important);addProperty("legacyVersion",n.version)
        }
        val assets=JsonArray().apply{dao.assets(n.id).forEach{a->add(JsonObject().apply{addProperty("id",a.id);addProperty("pageId",a.noteId);addProperty("kind",a.kind);addProperty("filename",a.filename);addProperty("mimeType",a.mimeType);addProperty("byteSize",a.size);addProperty("checksum",a.contentHash);addProperty("objectHash",a.contentHash);addProperty("localPath",a.relativePath);addProperty("createdAt",iso(n.createdAt));a.width?.let{addProperty("width",it)};a.height?.let{addProperty("height",it)};a.durationMs?.let{addProperty("durationMs",it)};a.caption?.let{addProperty("caption",it)};a.displayWidth?.let{addProperty("displayWidth",it)};a.alignment?.let{addProperty("alignment",it)}})}}
        val steps=JsonArray().apply{dao.steps(n.id).forEach{step->add(JsonObject().apply{addProperty("id",step.id);addProperty("pageId",step.noteId);addProperty("text",step.text);addProperty("checked",step.checked);addProperty("sortOrder",step.sortOrder);addProperty("createdAt",iso(step.createdAt))})}}
        return JsonObject().apply{addProperty("schemaVersion",3);addProperty("noteID",n.id);add("metadata",metadata);addProperty("currentContentHash",currentHash);add("contentObjects",contents);add("history",history);add("assets",assets);add("taskSteps",steps);add("tagIDs",JsonArray().apply{n.tagIds.split(',').filter{it.isNotBlank()}.forEach(::add)});add("linkedPageIDs",JsonArray())}
    }
    private suspend fun toEnvelope(n:NoteEntity,previousEnvelope:JsonObject?=null):JsonObject { val noteAssets=dao.assets(n.id);val noteSteps=dao.steps(n.id);val textBlocks=BlockDocumentCodec.encodeMarkdown(n.body,"android-${n.id}");val meta=JsonObject().apply{addProperty("id",n.id);addProperty("title",n.title);n.icon?.let{addProperty("icon",it)};addProperty("createdAt",swiftDate(n.createdAt));addProperty("updatedAt",swiftDate(n.updatedAt));n.folderId?.let{addProperty("folderID",it)};addProperty("folderName",n.folderName);n.reminderAt?.let{addProperty("reminderAt",swiftDate(it))};addProperty("recurrenceRule",n.recurrence);addProperty("version",n.version);add("tagIDs",JsonArray().apply{n.tagIds.split(',').filter{it.isNotBlank()}.forEach{add(it)}});addProperty("viewMode",n.viewMode.takeIf{it in setOf("text","preview","split")}?:"preview");addProperty("blockCount",textBlocks.size()+noteAssets.size);addProperty("assetCount",noteAssets.size);addProperty("documentPath","Notes/${n.id}/document.json");addProperty("itemType",n.itemType);n.dueAt?.let{addProperty("todoDueAt",swiftDate(it))};n.completedAt?.let{addProperty("todoCompletedAt",swiftDate(it))};addProperty("todoIsImportant",n.important);addProperty("todoOrder",0);add("linkedNoteIDs",JsonArray())}
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
        validateMarkdownEnvelope(o);val m=o["metadata"].asJsonObject;val hash=o.str("currentContentHash");val body=o["contentObjects"].asJsonObject[hash].asString;val version=maxOf(m["legacyVersion"]?.asLong?:0,m.isoMs("updatedAt"))
        return NoteEntity(id=o.str("noteID"),title=m.str("title"),body=body,previewText=m.str("preview",previewText(body)),createdAt=m.isoMs("createdAt"),updatedAt=m.isoMs("updatedAt"),folderId=m.optStr("sectionId"),folderName="",icon=m.optStr("icon"),parentPageId=m.optStr("parentPageId"),sortOrder=m["sortOrder"]?.asDouble?:0.0,treeUpdatedAt=m.optIsoMs("treeUpdatedAt")?:m.isoMs("createdAt"),reminderAt=m.optIsoMs("reminderAt"),recurrence=m.str("recurrenceRule","none"),version=version,tagIds=o["tagIDs"]?.asJsonArray?.joinToString(","){it.asString}.orEmpty(),deletedAt=m.optIsoMs("deletedAt"),itemType=if(m.str("kind")=="task")"todo" else "note",dueAt=m.optIsoMs("dueAt"),completedAt=m.optIsoMs("completedAt"),important=m["important"]?.asBoolean?:m["favorite"]?.asBoolean?:false,viewMode="preview",dirty=false,snapshotJson=gson.toJson(o),lastSyncedVersion=version)
    }
    private fun decodedAssets(o:JsonObject):List<AssetEntity>{if(o["schemaVersion"]?.asInt!=3)return assetsFromEnvelope(o);val noteId=o.str("noteID");return o["assets"]?.asJsonArray?.map{raw->val a=raw.asJsonObject;val relative=safeRelative(a.str("localPath"));AssetEntity(a.str("id"),noteId,a.str("kind"),a.str("filename"),a.str("mimeType"),relative,contentHash=a.str("objectHash",a.str("checksum")),size=a["byteSize"]?.asLong?:0,width=a["width"]?.takeUnless{it.isJsonNull}?.asInt,height=a["height"]?.takeUnless{it.isJsonNull}?.asInt,durationMs=a["durationMs"]?.takeUnless{it.isJsonNull}?.asLong,caption=a.optStr("caption"),displayWidth=a["displayWidth"]?.takeUnless{it.isJsonNull}?.asInt,alignment=a.optStr("alignment"))}.orEmpty()}
    private fun decodedSteps(o:JsonObject):List<TodoStepEntity>{if(o["schemaVersion"]?.asInt!=3)return stepsFromEnvelope(o);return o["taskSteps"]?.asJsonArray?.map{raw->val x=raw.asJsonObject;TodoStepEntity(x.str("id"),x.str("pageId"),x.str("text"),x["checked"]?.asBoolean?:false,x.int("sortOrder"),x.isoMs("createdAt"))}.orEmpty()}
    private fun fromEnvelope(o:JsonObject):NoteEntity{val s=o["currentSnapshot"].asJsonObject;val m=s["metadata"].asJsonObject;val body=BlockDocumentCodec.decodeMarkdown(s["document"].asJsonObject["blocks"].asJsonArray);val version=m["version"].asLong;val viewMode=m.str("viewMode","preview").takeIf{it in setOf("text","preview","split")}?:"preview";return NoteEntity(id=m.str("id"),title=m.str("title"),body=body,previewText=previewText(body),createdAt=m.dateMs("createdAt"),updatedAt=m.dateMs("updatedAt"),folderId=m.optStr("folderID"),folderName=m.str("folderName","未分类"),icon=m.optStr("icon"),reminderAt=m.optDateMs("reminderAt"),recurrence=m.str("recurrenceRule","none"),version=version,tagIds=m["tagIDs"]?.asJsonArray?.joinToString(","){it.asString}.orEmpty(),deletedAt=m.optDateMs("deletedAt"),itemType=m.str("itemType","note"),dueAt=m.optDateMs("todoDueAt"),completedAt=m.optDateMs("todoCompletedAt"),important=m["todoIsImportant"]?.asBoolean?:false,viewMode=viewMode,dirty=false,snapshotJson=gson.toJson(s),lastSyncedVersion=version)}
    private fun assetsFromEnvelope(o:JsonObject):List<AssetEntity>{val noteId=o.str("noteID");return o["currentSnapshot"].asJsonObject["assets"]?.asJsonArray?.mapNotNull{raw->val a=raw.asJsonObject;val ref=a["reference"]?.asJsonObject?:return@mapNotNull null;val relative=runCatching{safeRelative(a.str("relativeFilePath"))}.getOrNull()?:return@mapNotNull null;AssetEntity(ref.str("id"),noteId,ref.str("kind"),ref.str("filename"),ref.str("mimeType"),relative)}.orEmpty()}
    private fun stepsFromEnvelope(o:JsonObject):List<TodoStepEntity>{val s=o["currentSnapshot"].asJsonObject;return s["todoSteps"]?.takeIf{it.isJsonArray}?.asJsonArray?.map{raw->val x=raw.asJsonObject;TodoStepEntity(x.str("id"),x.str("noteID"),x.str("text"),x["checked"]?.asBoolean?:false,x.int("order"),x.dateMs("createdAt"))}.orEmpty()}
    private fun parseJsonObject(bytes:ByteArray)=JsonParser.parseString(String(bytes,StandardCharsets.UTF_8)).asJsonObject
    private fun readJson(c:ChannelSftp,p:String)=readRemoteBytesOrNull(c,p)?.let(::parseJsonObject)
    private fun readNoteJson(c:ChannelSftp,s:SshSettings,noteID:String,index:JsonObject?)=readRemoteBytesOrNull(c,CompressedRepositoryContract.notePath(s.path,noteID,index))?.let{bytes->
        val text=if(CompressedRepositoryContract.enabled(index))CompressedRepositoryContract.decode(bytes)else String(bytes,StandardCharsets.UTF_8)
        decryptPrivacyEnvelope(JsonParser.parseString(text).asJsonObject,s.password).also{decoded->
            RepositoryIdentityContract.requireNoteID(decoded.str("noteID"))
            require(decoded.str("noteID")==noteID){"远端笔记内容与索引 ID 不一致，已停止同步"}
            val metadataID=if(decoded["schemaVersion"]?.asInt==3)decoded["metadata"]?.asJsonObject?.str("id")
                else decoded["currentSnapshot"]?.asJsonObject?.get("metadata")?.asJsonObject?.str("id")
            require(metadataID==noteID){"远端笔记元数据与索引 ID 不一致，已停止同步"}
        }
    }
    private fun readRemoteBytesOrNull(c:ChannelSftp,p:String):ByteArray?=try{
        c.get(p).use{input->
            val output=ByteArrayOutputStream()
            val buffer=ByteArray(64*1024)
            var fileBytes=0L
            while(true){
                val count=input.read(buffer)
                if(count<0)break
                fileBytes+=count
                remoteReadBudget?.consume(fileBytes,count.toLong())
                if(remoteReadBudget==null)require(fileBytes<=RemoteTransferLimits.MAX_FILE_BYTES){"远端单个文件超过 ${RemoteTransferLimits.MAX_FILE_MIB} MiB，已停止同步"}
                output.write(buffer,0,count)
            }
            output.toByteArray()
        }
    }catch(error:SftpException){if(error.id==ChannelSftp.SSH_FX_NO_SUCH_FILE)null else throw error}
    private fun downloadRemoteFile(c:ChannelSftp,p:String,target:java.io.File){
        c.get(p).use{input->target.outputStream().use{output->
            val buffer=ByteArray(1024*1024)
            var fileBytes=0L
            while(true){
                val count=input.read(buffer)
                if(count<0)break
                fileBytes+=count
                remoteReadBudget?.consume(fileBytes,count.toLong())
                if(remoteReadBudget==null)require(fileBytes<=RemoteTransferLimits.MAX_FILE_BYTES){"远端单个文件超过 ${RemoteTransferLimits.MAX_FILE_MIB} MiB，已停止同步"}
                output.write(buffer,0,count)
            }
        }}
    }
    private fun atomicWrite(c:ChannelSftp,p:String,text:String)=atomicWrite(c,p,text.toByteArray(StandardCharsets.UTF_8))
    private fun atomicWrite(c:ChannelSftp,p:String,bytes:ByteArray){RemoteTransferLimits.requireUploadSize(bytes.size.toLong());val tmp="$p.tmp.android.${UUID.randomUUID()}";try{c.put(ByteArrayInputStream(bytes),tmp);val remote=readRemoteBytesOrNull(c,tmp)?:error("远端临时文件不存在：$p");require(sha(remote)==sha(bytes)){"远端临时文件校验失败：$p"};replaceRemoteFile(c,tmp,p)}catch(error:Throwable){runCatching{c.rm(tmp)};throw error}}
    private fun atomicUpload(c:ChannelSftp,p:String,file:java.io.File,expectedHash:String){RemoteTransferLimits.requireUploadSize(file.length());val tmp="$p.tmp.android.${UUID.randomUUID()}";try{file.inputStream().use{c.put(it,tmp)};val remoteHash=c.get(tmp).use(::sha);require(remoteHash==expectedHash){"远端附件临时文件校验失败"};replaceRemoteFile(c,tmp,p)}catch(error:Throwable){runCatching{c.rm(tmp)};throw error}}
    private fun replaceRemoteFile(c:ChannelSftp,tmp:String,target:String){
        try{c.rename(tmp,target);return}catch(first:Throwable){
            val backup="$target.backup.android.${UUID.randomUUID()}"
            val hadTarget=runCatching{c.rename(target,backup)}.isSuccess
            try{
                c.rename(tmp,target)
                if(hadTarget)runCatching{c.rm(backup)}
            }catch(second:Throwable){
                if(hadTarget)runCatching{c.rename(backup,target)}
                second.addSuppressed(first)
                throw second
            }
        }
    }
    private fun mkdirs(c:ChannelSftp,path:String){var cur="";path.split('/').filter{it.isNotBlank()}.forEach{cur+="/$it";try{c.mkdir(cur)}catch(_:Exception){}}}
    private fun deviceId()=prefs.getString("device",null)?:UUID.randomUUID().toString().also{prefs.edit().putString("device",it).apply()}
    private fun iso(ms:Long)=Instant.ofEpochMilli(ms).toString()
    private fun swiftDate(ms:Long)=SwiftDateCodec.encode(ms)
    private fun sha(s:String)=MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString(""){"%02x".format(it)}
    private fun sha(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
    private fun sha(input:InputStream):String{val digest=MessageDigest.getInstance("SHA-256");val buffer=ByteArray(1024*1024);while(true){val count=input.read(buffer);if(count<0)break;if(count>0)digest.update(buffer,0,count)};return digest.digest().joinToString(""){"%02x".format(it)}}
    private fun validateMarkdownEnvelope(o:JsonObject,expectedHash:String=""){
        if(o["schemaVersion"]?.asInt!=3)return
        val id=o.str("noteID");val currentHash=o.str("currentContentHash")
        require(expectedHash.isBlank()||expectedHash==currentHash){"笔记 $id 与索引中的内容哈希不一致"}
        val objects=o["contentObjects"]?.takeIf{it.isJsonObject}?.asJsonObject?:error("笔记 $id 缺少正文对象表")
        val current=objects[currentHash]?.takeUnless{it.isJsonNull}?.asString?:error("笔记 $id 缺少当前正文对象")
        require(sha(current)==currentHash){"笔记 $id 的当前正文 SHA-256 校验失败"}
        o["history"]?.takeIf{it.isJsonArray}?.asJsonArray?.forEach{raw->val entry=raw.asJsonObject;val hash=entry.str("contentHash");val text=objects[hash]?.takeUnless{it.isJsonNull}?.asString?:error("笔记 $id 的历史版本缺少正文对象");require(sha(text)==hash){"笔记 $id 的历史版本 SHA-256 校验失败"}}
    }
    private fun normalizedIndexEntries(index:JsonObject?):List<JsonObject>{
        return RepositoryIndexSanitizer.entries(index)
    }
    private fun logInvalidRemoteIndexIDs(index:JsonObject?){
        val ids=RepositoryIndexSanitizer.invalidNoteIDs(index)
        if(ids.isNotEmpty())Log.w("NotebookSync","Ignoring ${ids.size} invalid legacy remote index IDs: ${ids.take(10).joinToString()}")
    }
    private fun privacyKey(secret:String,salt:ByteArray,iterations:Int)=SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec(secret.toCharArray(),salt,iterations,256)).encoded,"AES")
    private val privacyCheckText="notebook-next-private-notes-v1"
    private fun verifyPrivacyKeyCheck(c:ChannelSftp,s:SshSettings){
        // Private-key auth may leave no SSH password configured. The remote
        // privacy check is password-derived, so skip it without a password;
        // encrypted notes still fail loudly in decryptPrivacyEnvelope.
        if(s.password.isBlank())return
        val check=readJson(c,"${s.path}/notes/privacy.json")?:return
        try{val iterations=check["iterations"].asInt;require(check["schemaVersion"].asInt==1&&check.str("algorithm")=="AES-256-GCM"&&check.str("kdf")=="PBKDF2-SHA256"&&iterations==privacyIterations);val salt=Base64.decode(check.str("salt"),Base64.NO_WRAP);val iv=Base64.decode(check.str("iv"),Base64.NO_WRAP);val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,privacyKey(s.password,salt,iterations),GCMParameterSpec(128,iv));cipher.updateAAD(privacyCheckText.toByteArray(StandardCharsets.UTF_8));require(String(cipher.doFinal(Base64.decode(check.str("ciphertext"),Base64.NO_WRAP)),StandardCharsets.UTF_8)==privacyCheckText)}catch(error:Throwable){throw IllegalStateException("隐私笔记密钥验证失败：SSH 密码与远端仓库不一致，已停止同步且未修改任何笔记",error)}
    }
    private fun ensurePrivacyKeyCheck(c:ChannelSftp,s:SshSettings){
        // Never publish a privacy check derived from an empty password: the
        // note bodies themselves fail loudly in encryptPrivacyEnvelope.
        if(s.password.isBlank()||encryptedNoteIds().isEmpty()||readJson(c,"${s.path}/notes/privacy.json")!=null)return
        val salt=ByteArray(16).also(SecureRandom()::nextBytes);val iv=ByteArray(12).also(SecureRandom()::nextBytes);val iterations=privacyIterations;val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,privacyKey(s.password,salt,iterations),GCMParameterSpec(128,iv));cipher.updateAAD(privacyCheckText.toByteArray(StandardCharsets.UTF_8));val encrypted=cipher.doFinal(privacyCheckText.toByteArray(StandardCharsets.UTF_8))
        val check=JsonObject().apply{addProperty("schemaVersion",1);addProperty("algorithm","AES-256-GCM");addProperty("kdf","PBKDF2-SHA256");addProperty("iterations",iterations);addProperty("salt",Base64.encodeToString(salt,Base64.NO_WRAP));addProperty("iv",Base64.encodeToString(iv,Base64.NO_WRAP));addProperty("ciphertext",Base64.encodeToString(encrypted,Base64.NO_WRAP))}
        atomicWrite(c,"${s.path}/notes/privacy.json",gson.toJson(check))
    }
    private fun encryptPrivacyEnvelope(note:JsonObject,secret:String):JsonObject{
        val salt=ByteArray(16).also(SecureRandom()::nextBytes);val iv=ByteArray(12).also(SecureRandom()::nextBytes);val iterations=privacyIterations
        val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,privacyKey(secret,salt,iterations),GCMParameterSpec(128,iv));cipher.updateAAD(note.str("noteID").toByteArray(StandardCharsets.UTF_8));val encrypted=cipher.doFinal(gson.toJson(note).toByteArray(StandardCharsets.UTF_8))
        val redacted=note["metadata"].asJsonObject.deepCopy().apply{addProperty("title","隐私笔记");addProperty("preview","");addProperty("locked",true);remove("legacyMetadata")}
        return JsonObject().apply{addProperty("schemaVersion",3);addProperty("noteID",note.str("noteID"));add("metadata",redacted);addProperty("currentContentHash",note.str("currentContentHash"));add("contentObjects",JsonObject());add("history",JsonArray());add("assets",JsonArray());add("taskSteps",JsonArray());add("tagIDs",JsonArray());add("linkedPageIDs",JsonArray());add("privacyEncryption",JsonObject().apply{addProperty("version",1);addProperty("algorithm","AES-256-GCM");addProperty("kdf","PBKDF2-SHA256");addProperty("iterations",iterations);addProperty("salt",Base64.encodeToString(salt,Base64.NO_WRAP));addProperty("iv",Base64.encodeToString(iv,Base64.NO_WRAP));addProperty("ciphertext",Base64.encodeToString(encrypted,Base64.NO_WRAP))})}
    }
    /** Private SSH note bodies are encrypted, but legacy asset objects are not.
     *  Remove every attachment reference before encryption so another device
     *  never tries to fetch an intentionally local-only object. */
    private fun stripPrivateAttachments(envelope:JsonObject)=SshAssetManifestContract.stripPrivateEnvelopeAttachments(envelope)
    private fun decryptPrivacyEnvelope(stored:JsonObject,secret:String):JsonObject{
        val encryption=stored["privacyEncryption"]?.takeIf{it.isJsonObject}?.asJsonObject?:return stored
        try{val iterations=encryption["iterations"].asInt;require(encryption.str("algorithm")=="AES-256-GCM"&&encryption.str("kdf")=="PBKDF2-SHA256"&&iterations==privacyIterations);val salt=Base64.decode(encryption.str("salt"),Base64.NO_WRAP);val iv=Base64.decode(encryption.str("iv"),Base64.NO_WRAP);val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,privacyKey(secret,salt,iterations),GCMParameterSpec(128,iv));cipher.updateAAD(stored.str("noteID").toByteArray(StandardCharsets.UTF_8));val decoded=JsonParser.parseString(String(cipher.doFinal(Base64.decode(encryption.str("ciphertext"),Base64.NO_WRAP)),StandardCharsets.UTF_8)).asJsonObject;require(decoded.str("noteID")==stored.str("noteID"));return decoded}catch(error:Throwable){throw IllegalStateException("隐私笔记 ${stored.str("noteID")} 解密失败：SSH 密码不一致或远端数据已损坏",error)}
    }
    private fun safeRelative(path:String):String{val normalized=path.replace('\\','/');require(normalized.isNotBlank()&&!normalized.startsWith('/')&&!normalized.startsWith('~')&&normalized.none{it.isISOControl()}&&!normalized.split('/').any{it==".."||it=="."||it.isBlank()}){"非法附件路径"};return normalized}
    private fun decodeText(bytes:ByteArray):String{
        val candidates=when{bytes.size>=2&&bytes[0]==0xFF.toByte()&&bytes[1]==0xFE.toByte()->listOf(StandardCharsets.UTF_16LE);bytes.size>=2&&bytes[0]==0xFE.toByte()&&bytes[1]==0xFF.toByte()->listOf(StandardCharsets.UTF_16BE);else->listOf(StandardCharsets.UTF_8,StandardCharsets.UTF_16LE,StandardCharsets.UTF_16BE,StandardCharsets.ISO_8859_1)}
        return candidates.firstNotNullOfOrNull{charset->runCatching{charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()}.getOrNull()}?:error("无法识别文档编码")
    }
    /** Local writes are durable immediately; remote publication is deliberately
     * quiet so bursts of edits share one WorkManager run. */
    /** A durable edit: upload within [SyncPacing.EDIT_MAX_WAIT_MS] at the latest; later edits do not postpone it. Leaving the note or the app uploads sooner. */
    private fun requestSyncIfConfigured(){if(!hasRemoteConfiguration())return;synchronized(syncRequestLock){if(syncRequestJob?.isActive==true)return;syncRequestJob=appScope.launch{delay(SyncPacing.EDIT_MAX_WAIT_MS);SyncWorker.enqueueNow(appContext);synchronized(syncRequestLock){if(syncRequestJob===coroutineContext[Job])syncRequestJob=null}}}}
    private suspend fun hasLocalChanges():Boolean=pendingUploadCount()>0||dao.dirtyNoteCount()>0||dao.dirtyAssetCount()>0||pendingDrafts.isNotEmpty()
    /** A natural boundary (note closed, app left): upload pending edits, keeping automatic runs a minute apart. */
    fun requestBoundarySync(){
        if(!hasRemoteConfiguration())return
        appScope.launch{
            if(!hasLocalChanges())return@launch
            cancelDelayedSyncRequest()
            val delayMs=SyncPacing.boundaryDelay(System.currentTimeMillis(),prefs.getLong("lastAutoSyncAt",0L))
            SyncWorker.enqueueNow(appContext,(delayMs+999)/1000)
        }
    }
    /** Called when the editor closes: save the last keystrokes, then upload at this boundary. */
    fun closeNote(changed:NoteEntity?){appScope.launch{changed?.let{flushDraft(it).join()};requestBoundarySync()}}
    /** Worker gate: 0 to run now, else how long the request budget needs. Records the run. */
    fun automaticSyncDelayMs():Long{
        val now=System.currentTimeMillis()
        val wait=SyncPacing.budgetDelay(now,WebdavRequestLog.snapshot())
        if(wait>0){DiagnosticLogger.log(DiagnosticLevel.WARN,DiagnosticEvent.SYNC_FAILURE,operationId="request-budget-deferred",counts=mapOf("requests30m" to WebdavRequestLog.recentCount(now).toLong(),"deferSeconds" to wait/1000));return wait}
        prefs.edit().putLong("lastAutoSyncAt",now).apply()
        return 0
    }
    private fun cancelDelayedSyncRequest(){synchronized(syncRequestLock){syncRequestJob?.cancel();syncRequestJob=null}}
    private suspend fun scheduleNextOutboxBatch(workspace:String,outboxBefore:Int){
        if(outboxBefore>=200&&dao.apiOutboxCount(workspace)>0)SyncWorker.enqueueNow(appContext)
    }
    /** Re-resolves title links in every note after a title appeared or changed. */
    private fun reindexTitleLinksAsync(){appScope.launch{runCatching{dao.noteIdsWithLinks().forEach{id->loadEditable(id)?.let{indexReferences(it)}}}}}
    private suspend fun indexReferences(note:NoteEntity){
        val titleLinks:List<PageLinkEntity> = if(note.deletedAt!=null)emptyList() else NotebookReferenceSyntax.wikiLinks(note.body).let{links->
            if(links.isEmpty())return@let emptyList<PageLinkEntity>()
            val resolve=NotebookReferenceSyntax.titleResolver(dao.linkCandidates().map{WikiLinkCandidate(it.id,it.title,it.itemType=="todo",it.updatedAt)})
            links.mapIndexedNotNull{index,link->resolve(link.title)?.takeIf{it!=note.id}?.let{target->PageLinkEntity(
                id="${note.id}:title:$index:$target",sourceNoteId=note.id,targetNoteId=target,targetKind="page",targetId=null,
                alias=link.alias,embed=false,sourceOffset=link.start,updatedAt=note.updatedAt)}}.distinctBy{it.targetNoteId}
        }
        val links=if(note.deletedAt!=null)emptyList() else NotebookReferenceSyntax.parse(note.body).mapIndexed{index,reference->
            PageLinkEntity(
                id="${note.id}:$index:${reference.pageId}:${reference.kind}:${reference.targetId.orEmpty()}:${reference.embed}",
                sourceNoteId=note.id,
                targetNoteId=reference.pageId,
                targetKind=reference.kind,
                targetId=reference.targetId,
                alias=reference.alias,
                embed=reference.embed,
                sourceOffset=reference.start,
                updatedAt=note.updatedAt
            )
        }
        dao.replacePageLinks(note.id,links+titleLinks)
    }
    private suspend fun loadEditable(id:String):NoteEntity?{val header=dao.getNoteHeader(id)?:return null;val body=readLargeText(dao.bodyLength(id)){start,length->dao.bodyChunk(id,start,length)}.orEmpty();return header.copy(body=body,previewText=header.previewText.ifBlank{previewText(body)})}
    private suspend fun loadSnapshot(id:String)=readLargeText(dao.snapshotLength(id)){start,length->dao.snapshotChunk(id,start,length)}
    private suspend fun loadConflictSnapshot(id:String)=readLargeText(dao.conflictSnapshotLength(id)){start,length->dao.conflictSnapshotChunk(id,start,length)}
    private suspend fun readLargeText(totalLength:Int?,read:suspend(Int,Int)->String?):String?{
        if(totalLength==null)return null
        val result=StringBuilder(totalLength);var start=1
        while(start<=totalLength){
            val count=minOf(64*1024,totalLength-start+1)
            result.append(read(start,count)?:error("大文本记录读取失败"))
            // SQLite substr/length count Unicode code points, not Kotlin UTF-16 units.
            start+=count
        }
        return result.toString()
    }
    // Never delete encrypted settings automatically. A transient Keystore or
    // device-unlock failure must not erase credentials, cursors or privacy maps.
    private fun encryptedPrefs(context:Context)=createEncryptedPrefs(context)
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
private fun NoteEntity.editableUpdate()=NoteEditableUpdate(id,title,body,previewText,createdAt,updatedAt,folderId,folderName,icon,parentPageId,sortOrder,treeUpdatedAt,reminderAt,recurrence,version,tagIds,deletedAt,itemType,dueAt,completedAt,important,viewMode,dirty,conflict,lastSyncedVersion)
internal fun mergeNewestTree(local:NoteEntity,remote:NoteEntity):NoteEntity {
    val icon=if(remote.updatedAt>local.updatedAt)remote.icon else local.icon
    return if(remote.treeUpdatedAt>local.treeUpdatedAt)local.copy(folderId=remote.folderId,folderName=remote.folderName,icon=icon,parentPageId=remote.parentPageId,sortOrder=remote.sortOrder,treeUpdatedAt=remote.treeUpdatedAt)else local.copy(icon=icon)
}

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
