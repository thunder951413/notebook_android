package io.github.notebook.android

import android.app.Activity
import android.os.Bundle
import io.github.notebook.android.sync.ApiSyncSettings
import io.github.notebook.android.sync.HostKeyChangedException
import io.github.notebook.android.sync.SshSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.security.MessageDigest

/** ADB-only acceptance entry point. This source set and activity never ship in release builds. */
class DebugSyncActivity:Activity() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    override fun onCreate(state:Bundle?){super.onCreate(state);if(intent.getBooleanExtra("enqueueBackground",false)){
        io.github.notebook.android.sync.SyncWorker.enqueueNow(this)
        getSharedPreferences("debug-sync-result",MODE_PRIVATE).edit().putString("result","ENQUEUED").apply();finish();return
    };scope.launch{
        val app=application as NotebookApp
        val result=runCatching{
            val sshHost=intent.getStringExtra("sshHost").orEmpty()
            if(sshHost.isNotBlank()){
                app.repository.saveSettings(SshSettings(
                    host=sshHost,
                    port=intent.getIntExtra("sshPort",22),
                    username=intent.getStringExtra("sshUsername").orEmpty(),
                    password=intent.getStringExtra("sshPasswordHex")?.chunked(2)?.map{
                        it.toInt(16).toByte()
                    }?.toByteArray()?.let{
                        String(it,Charsets.UTF_8)
                    }?:intent.getStringExtra("sshPasswordBase64")?.let{
                        String(android.util.Base64.decode(it,android.util.Base64.NO_WRAP),Charsets.UTF_8)
                    }?:intent.getStringExtra("sshPassword").orEmpty(),
                    path=intent.getStringExtra("sshPath").orEmpty(),
                    fingerprint=intent.getStringExtra("sshFingerprint").orEmpty(),
                ))
            }else{
                app.repository.saveApiSettings(ApiSyncSettings(
                    intent.getStringExtra("baseUrl").orEmpty(),
                    intent.getStringExtra("workspaceId").orEmpty(),
                    intent.getStringExtra("token").orEmpty()
                ))
            }
            app.repository.sync()
            var note=app.database.dao().get(intent.getStringExtra("pageId").orEmpty())
            intent.getStringExtra("editTitle")?.let{title->
                val replacement=intent.getStringExtra("bodyBase64")?.let{String(android.util.Base64.decode(it,android.util.Base64.NO_WRAP),Charsets.UTF_8)}
                note=note?.let{app.repository.save(it.copy(title=title,body=replacement?:it.body+intent.getStringExtra("appendBody").orEmpty()))}
                app.repository.sync()
                note=app.database.dao().get(intent.getStringExtra("pageId").orEmpty())
            }
            val asset=note?.let{app.database.dao().assets(it.id).firstOrNull()}
            listOf(note?.title.orEmpty(),note?.body.orEmpty(),asset?.localPath.orEmpty()).joinToString("\n---\n")
        }
        val credential=app.repository.settings().password.toByteArray(Charsets.UTF_8)
        val credentialSummary="credentialLength=${credential.size}, credentialSha256=${MessageDigest.getInstance("SHA-256").digest(credential).joinToString(""){"%02x".format(it)}.take(12)}"
        getSharedPreferences("debug-sync-result",MODE_PRIVATE).edit().putString("result",result.fold({"OK\n$it"},{error->
            val hostKey=if(error is HostKeyChangedException)"\nexpected=${error.expected}\nactual=${error.actual}" else ""
            "ERROR\n$credentialSummary$hostKey\n${error.stackTraceToString()}"
        })).commit()
        finish()
    }}
}
