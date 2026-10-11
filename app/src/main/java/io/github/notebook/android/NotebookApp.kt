package io.github.notebook.android
import android.app.Application
import android.util.Log
import io.github.notebook.android.data.NotebookDatabase
import io.github.notebook.android.diagnostics.DiagnosticEvent
import io.github.notebook.android.diagnostics.DiagnosticLevel
import io.github.notebook.android.diagnostics.DiagnosticLogger
import io.github.notebook.android.sync.SyncRepository
import io.github.notebook.android.sync.SyncWorker

class NotebookApp:Application(){
    val database by lazy { NotebookDatabase.create(this) }
    val repository by lazy { SyncRepository(this,database.dao()) }
    override fun onCreate(){
        super.onCreate()
        DiagnosticLogger.initialize(this)
        val activeRepository=runCatching{repository}.onFailure{
            // Keep the process alive long enough for Android to report the
            // initialization failure; MainActivity will retry and fail visibly
            // instead of presenting an empty notebook.
            Log.e("NotebookApp","Unable to initialize the notebook database or secure settings",it)
            DiagnosticLogger.log(DiagnosticLevel.ERROR,DiagnosticEvent.APP_INIT_FAILURE,error=it)
        }.getOrNull()?:return
        // Both async recovery jobs surface their own failures through saveError.
        activeRepository.recoverDraftsAsync()
        activeRepository.rebuildReferenceIndexAsync()
        runCatching{SyncWorker.schedule(this)}
            .onFailure{Log.e("NotebookApp","Unable to schedule background sync",it)}
    }
}
