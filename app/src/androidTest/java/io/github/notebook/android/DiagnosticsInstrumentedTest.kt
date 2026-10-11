package io.github.notebook.android

import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.notebook.android.diagnostics.DiagnosticEvent
import io.github.notebook.android.diagnostics.DiagnosticLevel
import io.github.notebook.android.diagnostics.DiagnosticLogger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.zip.ZipFile

@RunWith(AndroidJUnit4::class)
class DiagnosticsInstrumentedTest {
    @Test fun appPrivateLogCanBeSharedWithoutSensitiveValues(){
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val secret="https://user:token@example.test/private"
        DiagnosticLogger.log(DiagnosticLevel.ERROR,DiagnosticEvent.SYNC_FAILURE,resourceId=secret,error=IllegalStateException("password=$secret"))
        val archive=DiagnosticLogger.exportArchive(context)

        assertTrue(archive.isFile)
        val uri=FileProvider.getUriForFile(context,"${context.packageName}.files",archive)
        assertEquals("content",uri.scheme)
        ZipFile(archive).use{zip->
            val text=zip.entries().asSequence().filter{it.name.endsWith(".jsonl")}.joinToString("\n"){zip.getInputStream(it).bufferedReader().readText()}
            assertTrue(text.contains("\"event\":\"sync.failure\""))
            assertFalse(text.contains(secret))
            assertFalse(text.contains("password="))
        }
    }
}
