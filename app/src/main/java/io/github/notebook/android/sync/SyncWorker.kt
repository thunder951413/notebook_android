package io.github.notebook.android.sync

import android.content.Context
import androidx.work.*
import io.github.notebook.android.NotebookApp
import java.util.concurrent.TimeUnit

class SyncWorker(context:Context,params:WorkerParameters):CoroutineWorker(context,params){
    override suspend fun doWork():Result=runCatching{(applicationContext as NotebookApp).repository.sync()}.fold({Result.success()},{if(runAttemptCount<5)Result.retry() else Result.failure()})
    companion object{
        fun schedule(context:Context){
            val constraints=Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            val request=PeriodicWorkRequestBuilder<SyncWorker>(15,TimeUnit.MINUTES).setConstraints(constraints).setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.SECONDS).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("notebook-server-sync",ExistingPeriodicWorkPolicy.UPDATE,request)
        }
    }
}
