package io.github.notebook.android.sync

import android.content.Context
import androidx.work.*
import io.github.notebook.android.NotebookApp
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

class SyncWorker(context:Context,params:WorkerParameters):CoroutineWorker(context,params){
    override suspend fun doWork():Result = try {
        val repository=(applicationContext as NotebookApp).repository
        // Lifecycle events (start/stop/unlock/rotation) can queue several
        // workers back to back. Without local changes, a sync that succeeded
        // seconds ago already has the remote state; skip the duplicate rounds
        // so the 坚果云 request budget is spent on real work.
        if(!repository.shouldSkipRedundantSync()){
            // Stay inside this device's share of the 坚果云 request budget;
            // the run is postponed, never dropped.
            val wait=repository.automaticSyncDelayMs()
            if(wait>0)enqueueNow(applicationContext,wait/1000)
            else repository.sync()
        }
        Result.success()
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        // Missing bytes need restoration, not a burst of identical retries.
        if (error is MissingAttachmentException || runAttemptCount >= 5) Result.failure() else Result.retry()
    }
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
