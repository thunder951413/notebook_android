package io.github.notebook.android.sync

import android.content.Context
import androidx.work.*
import io.github.notebook.android.NotebookApp
import java.util.concurrent.TimeUnit

class SyncWorker(context:Context,params:WorkerParameters):CoroutineWorker(context,params){
    override suspend fun doWork():Result=runCatching{(applicationContext as NotebookApp).repository.sync()}.fold({Result.success()},{if(runAttemptCount<5)Result.retry() else Result.failure()})
    companion object{
        private fun constraints()=Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        fun schedule(context:Context){
            val request=PeriodicWorkRequestBuilder<SyncWorker>(15,TimeUnit.MINUTES).setConstraints(constraints()).setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.SECONDS).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("notebook-server-sync",ExistingPeriodicWorkPolicy.UPDATE,request)
        }
        fun enqueueNow(context:Context,delaySeconds:Long=0){
            val request=OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(constraints()).setInitialDelay(delaySeconds,TimeUnit.SECONDS).setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.SECONDS).build()
            WorkManager.getInstance(context).enqueueUniqueWork("notebook-server-sync-now",ExistingWorkPolicy.APPEND_OR_REPLACE,request)
        }
    }
}
