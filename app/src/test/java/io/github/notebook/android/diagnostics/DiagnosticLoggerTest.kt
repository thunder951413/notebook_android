package io.github.notebook.android.diagnostics

import androidx.test.core.app.ApplicationProvider
import com.google.gson.JsonParser
import io.github.notebook.android.NotebookApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.zip.ZipFile

@RunWith(RobolectricTestRunner::class)
class DiagnosticLoggerTest {
    @Test fun redactsResourceAndExceptionText(){
        val directory=Files.createTempDirectory("diagnostic-redaction").toFile()
        val secret="https://user:token@example.test/private?body=secret-note"
        val error=IllegalStateException("password=$secret")
        error.stackTrace=arrayOf(StackTraceElement("io.github.notebook.Sync","run","Sync.kt",42))
        DiagnosticStore(directory,"session","1.2.3","7",now={1_700_000_000_000}).append(
            DiagnosticLevel.ERROR,DiagnosticEvent.SYNC_FAILURE,"operation-1",secret,error,25,mapOf("outbox" to 3),
        )

        val line=File(directory,"diagnostics.jsonl").readText()
        val json=JsonParser.parseString(line).asJsonObject
        assertFalse(line.contains(secret))
        assertFalse(line.contains("password="))
        assertFalse(json.has("operationId"))
        assertEquals("java.lang.IllegalStateException",json["errorClass"].asString)
        assertTrue(json["resource"].asString.startsWith("r:"))
        assertEquals("io.github.notebook.Sync#run:42",json["stack"].asJsonArray[0].asString)
        assertTrue(line.toByteArray().size<=DiagnosticLogger.ENTRY_BYTES)
    }

    @Test fun rotationRetainsAtMostTwoMiBAcrossFourJsonlFiles(){
        val directory=Files.createTempDirectory("diagnostic-rotation").toFile()
        var clock=1_700_000_000_000L
        val store=DiagnosticStore(directory,"session","1.2.3","7",now={clock++})
        repeat(12_000){index->store.append(DiagnosticLevel.ERROR,DiagnosticEvent.DRAFT_FLUSH_FAILURE,"op-$index","note-$index",IllegalStateException(),index.toLong())}

        val files=store.diagnosticFiles().filter(File::exists)
        assertTrue(files.size<=1+DiagnosticLogger.ARCHIVE_COUNT)
        assertTrue(files.all{it.length()<=DiagnosticLogger.ACTIVE_BYTES})
        assertTrue(files.sumOf(File::length)<=DiagnosticLogger.ACTIVE_BYTES*(1+DiagnosticLogger.ARCHIVE_COUNT))
        files.forEach{file->file.forEachLine{line->JsonParser.parseString(line)}}
    }

    @Test fun failedRotationDropsEntryInsteadOfExceedingCap(){
        val directory=Files.createTempDirectory("diagnostic-rotation-failure").toFile()
        val active=File(directory,"diagnostics.jsonl")
        RandomAccessFile(active,"rw").use{it.setLength(DiagnosticLogger.ACTIVE_BYTES)}
        File(directory,"diagnostics.3.jsonl").apply{mkdirs();File(this,"blocker").writeText("x")}
        val before=active.length()

        DiagnosticStore(directory,"session","1.2.3","7").append(DiagnosticLevel.ERROR,DiagnosticEvent.SYNC_FAILURE,error=IllegalStateException())

        assertEquals(before,active.length())
        assertTrue(active.length()<=DiagnosticLogger.ACTIVE_BYTES)
    }

    @Test fun repeatedNoiseIsCollapsedWithSuppressionCount(){
        val directory=Files.createTempDirectory("diagnostic-repeat").toFile()
        var clock=1_700_000_000_000L
        val store=DiagnosticStore(directory,"session","1.2.3","7",now={clock++})
        repeat(3){store.append(DiagnosticLevel.ERROR,DiagnosticEvent.SYNC_FAILURE,error=IllegalStateException())}
        store.append(DiagnosticLevel.INFO,DiagnosticEvent.SYNC_SUCCESS)

        val records=File(directory,"diagnostics.jsonl").readLines().map{JsonParser.parseString(it).asJsonObject}
        assertEquals(listOf("sync.failure","diagnostic.repeated","sync.success"),records.map{it["event"].asString})
        assertEquals(2,records[1]["counts"].asJsonObject["suppressed"].asInt)
    }

    @Test fun exportFlushesPendingRepeatedCount(){
        val directory=Files.createTempDirectory("diagnostic-export-repeat").toFile()
        val store=DiagnosticStore(directory,"session","1.2.3","7")
        repeat(4){store.append(DiagnosticLevel.ERROR,DiagnosticEvent.SYNC_FAILURE,error=IllegalStateException())}
        val archive=File(directory,"export.zip")

        store.exportZip(archive)

        ZipFile(archive).use{zip->
            val text=zip.entries().asSequence().joinToString("\n"){zip.getInputStream(it).bufferedReader().readText()}
            assertTrue(text.contains("\"suppressed\":3"))
        }
    }

    @Test fun applicationStartupWritesAndExportsShareableArchive(){
        val app=ApplicationProvider.getApplicationContext<NotebookApp>()
        assertTrue(DiagnosticLogger.flushForTest())
        val archive=DiagnosticLogger.exportArchive(app)
        assertTrue(archive.isFile)
        ZipFile(archive).use{zip->
            val names=zip.entries().asSequence().map{it.name}.toList()
            assertTrue(names.any{it.endsWith(".jsonl")})
            val text=zip.entries().asSequence().filter{it.name.endsWith(".jsonl")}.joinToString("\n"){zip.getInputStream(it).bufferedReader().readText()}
            assertTrue(text.contains("\"event\":\"app.start\""))
        }
    }
}
