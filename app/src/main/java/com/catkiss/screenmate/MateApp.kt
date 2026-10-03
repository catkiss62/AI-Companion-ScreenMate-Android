package com.catkiss.screenmate

import android.app.Application
import android.content.Context
import androidx.work.*
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

class MateApp : Application() {
    lateinit var config: Config; private set
    lateinit var store: Store; private set
    lateinit var models: Models; private set
    var gateway: ModelGateway? = null
        internal set
    override fun onCreate() {
        super.onCreate()
        java.io.File(cacheDir,"video-segments").deleteRecursively()
        java.io.File(cacheDir,"video-preview").deleteRecursively()
        java.io.File(cacheDir,"video-playback.mp4").delete()
        config = Config(this); store = Store(this); models = Models(config,store)
        store.recover().forEach { archive(it) }
    }
    fun archive(id: Long) {
        val request = OneTimeWorkRequestBuilder<ArchiveWorker>().setInputData(workDataOf("session" to id))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.SECONDS).build()
        // Appending prevents an end request being lost while a rolling summary finishes.
        WorkManager.getInstance(this).enqueueUniqueWork("archive:$id",ExistingWorkPolicy.APPEND_OR_REPLACE,request)
    }
    fun deleteSession(id: Long) {
        WorkManager.getInstance(this).cancelUniqueWork("archive:$id")
        store.delete(id) // In-flight summaries only UPDATE an existing row; never resurrect deleted memory.
    }
}

class ArchiveWorker(context: Context, params: WorkerParameters): CoroutineWorker(context,params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as MateApp
        val id = inputData.getLong("session",-1)
        try {
            repeat(20) {
                val session = app.store.session(id) ?: return Result.success()
                var budget=24000
                val rows = app.store.entries(id,session.cursor,30).takeWhile { row ->
                    val fits=budget>0 && (budget>=row.body.length || budget==24000); budget-=row.body.length; fits
                }
                if(rows.isEmpty()) { app.store.markArchivedIfCaughtUp(id); return Result.success() }
                if(app.config.secret("deep").isBlank()) return Result.retry()
                val summary = app.models.summarize(session.summary,rows)
                app.store.summary(id,summary,rows.last().id)
            }
            return Result.retry()
        } catch(e: CancellationException) { throw e }
        catch(_: Exception) { return Result.retry() }
    }
}
