package ru.poneslas.tasya.probe.upload

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.poneslas.tasya.probe.TAG

/** Ускоренная задача WorkManager для П9. На Android 12+ getForegroundInfo не зовётся [?]. */
class UploadWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getString("id") ?: "work"
        Log.i(TAG, "UploadWorker start $id")
        val ok = withContext(Dispatchers.IO) { Uploads.uploadBlocking(id) { isStopped } }
        if (isStopped) Uploads.stopped(id, "stopReason=$stopReason")
        return if (ok) Result.success() else Result.failure()
    }
}
