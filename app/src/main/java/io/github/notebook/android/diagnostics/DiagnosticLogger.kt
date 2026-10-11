package io.github.notebook.android.diagnostics

import android.content.Context
import io.github.notebook.android.BuildConfig
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.system.exitProcess

enum class DiagnosticEvent(val wireName:String){
    APP_START("app.start"),
    APP_CRASH("app.crash"),
    APP_INIT_FAILURE("app.init_failure"),
    DRAFT_FLUSH_FAILURE("draft.flush_failure"),
    DRAFT_RECOVERY_START("draft.recovery_start"),
    DRAFT_RECOVERY_SUCCESS("draft.recovery_success"),
    DRAFT_RECOVERY_FAILURE("draft.recovery_failure"),
    SYNC_START("sync.start"),
    SYNC_SUCCESS("sync.success"),
    SYNC_FAILURE("sync.failure"),
    REPEATED("diagnostic.repeated"),
}

enum class DiagnosticLevel { INFO, WARN, ERROR }

/**
 * Privacy-safe, bounded diagnostics. Callers can only select fixed event names;
 * exception messages and arbitrary text are deliberately absent from the API.
 */
object DiagnosticLogger {
    const val ACTIVE_BYTES=512*1024L
    const val ARCHIVE_COUNT=3
    const val ENTRY_BYTES=8*1024
    const val QUEUE_CAPACITY=256
    private val dropped=AtomicInteger()
    @Volatile private var state:State?=null

    private data class State(
        val store:DiagnosticStore,
        val executor:ThreadPoolExecutor,
    )

    fun initialize(context:Context){
        if(state!=null)return
        synchronized(this){
            if(state!=null)return
            runCatching{
                val store=DiagnosticStore(
                    File(context.filesDir,"diagnostics"),
                    UUID.randomUUID().toString(),
                    BuildConfig.VERSION_NAME,
                    BuildConfig.VERSION_CODE.toString(),
                )
                val executor=ThreadPoolExecutor(
                    1,1,0,TimeUnit.MILLISECONDS,ArrayBlockingQueue(QUEUE_CAPACITY),
                    { task->Thread(task,"notebook-diagnostics").apply{isDaemon=true} },
                    { _,_->throw RejectedExecutionException("diagnostic queue full") },
                )
                val previous=Thread.getDefaultUncaughtExceptionHandler()
                val next=State(store,executor)
                state=next
                Thread.setDefaultUncaughtExceptionHandler{thread,error->
                    try{
                        executor.submit{store.append(DiagnosticLevel.ERROR,DiagnosticEvent.APP_CRASH,error=error,counts=mapOf("thread" to thread.id))}.get(200,TimeUnit.MILLISECONDS)
                    }catch(_:Throwable){/* bounded best effort only */}
                    finally{
                        if(previous!=null)previous.uncaughtException(thread,error)
                        else{
                            android.os.Process.killProcess(android.os.Process.myPid())
                            exitProcess(10)
                        }
                    }
                }
                log(DiagnosticLevel.INFO,DiagnosticEvent.APP_START)
            }
        }
    }

    fun operationId():String=UUID.randomUUID().toString()

    fun log(
        level:DiagnosticLevel,
        event:DiagnosticEvent,
        operationId:String?=null,
        resourceId:String?=null,
        error:Throwable?=null,
        durationMs:Long?=null,
        counts:Map<String,Long> = emptyMap(),
    ){
        val current=state?:return
        try{
            current.executor.execute{
                runCatching{
                    val queueDropped=dropped.getAndSet(0)
                    current.store.append(level,event,operationId,resourceId,error,durationMs,counts,queueDropped)
                }
            }
        }catch(_:RejectedExecutionException){dropped.incrementAndGet()}catch(_:Throwable){/* diagnostics are fail-open */}
    }

    /** Creates a bounded snapshot for the user-initiated Android share sheet. */
    fun exportArchive(context:Context):File{
        val current=state?:error("Diagnostics are not initialized")
        val future=current.executor.submit<File>{
            val outputDir=File(context.cacheDir,"diagnostics-export").apply{mkdirs()}
            outputDir.listFiles()?.forEach{runCatching{it.delete()}}
            val output=File(outputDir,"notebook-diagnostics-${System.currentTimeMillis()}.zip")
            current.store.exportZip(output)
            output
        }
        return future.get(10,TimeUnit.SECONDS)
    }

    internal fun flushForTest(timeoutMs:Long=5_000):Boolean{
        val current=state?:return false
        return runCatching{
            val future=current.executor.submit<Boolean>{true}
            future.get(timeoutMs,TimeUnit.MILLISECONDS)
        }.getOrDefault(false)
    }
}

internal class DiagnosticStore(
    private val directory:File,
    private val sessionId:String,
    private val versionName:String,
    private val versionCode:String,
    private val now:()->Long={System.currentTimeMillis()},
){
    private val gson=GsonBuilder().disableHtmlEscaping().create()
    private var repeatedKey:String?=null
    private var repeatedCount=0L
    private var repeatedSince=0L
    private val active get()=File(directory,"diagnostics.jsonl")

    @Synchronized fun append(
        level:DiagnosticLevel,
        event:DiagnosticEvent,
        operationId:String?=null,
        resourceId:String?=null,
        error:Throwable?=null,
        durationMs:Long?=null,
        counts:Map<String,Long> = emptyMap(),
        queueDropped:Int=0,
    ){
        runCatching{
            directory.mkdirs()
            val key=listOf(level.name,event.wireName,error?.javaClass?.name.orEmpty(),resourceId.orEmpty()).joinToString("|")
            val timestamp=now()
            if(key==repeatedKey&&timestamp-repeatedSince<=30_000){repeatedCount++;return}
            if(repeatedCount>0)write(record(DiagnosticLevel.INFO,DiagnosticEvent.REPEATED,null,null,null,null,mapOf("suppressed" to repeatedCount)))
            repeatedKey=key;repeatedCount=0;repeatedSince=timestamp
            write(record(level,event,operationId,resourceId,error,durationMs,counts,queueDropped))
        }
    }

    @Synchronized fun exportZip(output:File){
        directory.mkdirs()
        if(repeatedCount>0){
            write(record(DiagnosticLevel.INFO,DiagnosticEvent.REPEATED,null,null,null,null,mapOf("suppressed" to repeatedCount)))
            repeatedCount=0
        }
        ZipOutputStream(FileOutputStream(output)).use{zip->
            diagnosticFiles().filter(File::isFile).forEach{file->
                zip.putNextEntry(ZipEntry(file.name));file.inputStream().use{it.copyTo(zip)};zip.closeEntry()
            }
        }
    }

    internal fun diagnosticFiles():List<File> = listOf(active)+(1..DiagnosticLogger.ARCHIVE_COUNT).map{File(directory,"diagnostics.$it.jsonl")}

    private fun record(level:DiagnosticLevel,event:DiagnosticEvent,operationId:String?,resourceId:String?,error:Throwable?,durationMs:Long?,counts:Map<String,Long>,queueDropped:Int=0):ByteArray{
        val root=JsonObject().apply{
            addProperty("timestamp",Instant.ofEpochMilli(now()).toString())
            addProperty("session",sessionId.take(64))
            addProperty("appVersion",versionName.take(64))
            addProperty("versionCode",versionCode.take(24))
            addProperty("level",level.name.lowercase())
            addProperty("event",event.wireName)
            operationId?.let(::uuidOrNull)?.let{addProperty("operationId",it)}
            resourceId?.let{addProperty("resource",pseudoRef(it))}
            durationMs?.let{addProperty("durationMs",it.coerceAtLeast(0))}
            if(counts.isNotEmpty()||queueDropped>0)add("counts",JsonObject().apply{
                counts.entries.sortedBy{it.key}.take(16).forEach{(key,value)->if(key.matches(Regex("[A-Za-z][A-Za-z0-9_]{0,31}")))addProperty(key,value.coerceAtLeast(0))}
                if(queueDropped>0)addProperty("queueDropped",queueDropped)
            })
            error?.let{throwable->
                addProperty("errorClass",throwable.javaClass.name.take(160))
                add("stack",JsonArray().apply{throwable.stackTrace.take(6).forEach{frame->add("${frame.className.take(120)}#${frame.methodName.take(80)}:${frame.lineNumber}")}})
            }
        }
        var bytes=(gson.toJson(root)+"\n").toByteArray(Charsets.UTF_8)
        if(bytes.size>DiagnosticLogger.ENTRY_BYTES){root.remove("stack");bytes=(gson.toJson(root)+"\n").toByteArray(Charsets.UTF_8)}
        return bytes.take(DiagnosticLogger.ENTRY_BYTES).toByteArray()
    }

    private fun pseudoRef(value:String):String{
        val digest=MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return "r:"+digest.take(8).joinToString(""){"%02x".format(it)}
    }

    private fun uuidOrNull(value:String):String?=runCatching{UUID.fromString(value).toString()}.getOrNull()

    private fun write(bytes:ByteArray){
        if(active.length()+bytes.size>DiagnosticLogger.ACTIVE_BYTES)rotate()
        FileOutputStream(active,true).use{it.write(bytes)}
    }

    private fun rotate(){
        val oldest=File(directory,"diagnostics.${DiagnosticLogger.ARCHIVE_COUNT}.jsonl")
        check(!oldest.exists()||oldest.delete()){ "Unable to remove oldest diagnostic archive" }
        for(index in DiagnosticLogger.ARCHIVE_COUNT-1 downTo 1){
            val source=File(directory,"diagnostics.$index.jsonl")
            check(!source.exists()||source.renameTo(File(directory,"diagnostics.${index+1}.jsonl"))){ "Unable to rotate diagnostic archive" }
        }
        check(!active.exists()||active.renameTo(File(directory,"diagnostics.1.jsonl"))){ "Unable to rotate active diagnostics" }
    }
}
