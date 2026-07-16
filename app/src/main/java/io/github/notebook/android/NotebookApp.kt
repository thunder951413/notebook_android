package io.github.notebook.android
import android.app.Application
import io.github.notebook.android.data.NotebookDatabase
import io.github.notebook.android.sync.SyncRepository
import io.github.notebook.android.sync.SyncWorker

class NotebookApp:Application(){
    val database by lazy { NotebookDatabase.create(this) }
    val repository by lazy { SyncRepository(this,database.dao()) }
    override fun onCreate(){super.onCreate();runCatching{repository.recoverDraftsAsync();SyncWorker.schedule(this)}}
}
