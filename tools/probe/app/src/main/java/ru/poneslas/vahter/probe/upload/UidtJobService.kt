package ru.poneslas.vahter.probe.upload

import android.app.job.JobParameters
import android.app.job.JobService
import android.util.Log
import ru.poneslas.vahter.probe.TAG
import kotlin.concurrent.thread

/** Задача пользовательской передачи данных (UIDT, Android 14+) для П9. */
class UidtJobService : JobService() {
    @Volatile private var stop = false

    override fun onStartJob(params: JobParameters): Boolean {
        val id = params.extras.getString("id") ?: "uidt"
        Log.i(TAG, "UIDT onStartJob $id")
        // Уведомление обязательно, и сразу — иначе система остановит задачу.
        setNotification(params, NOTIFICATION_ID, Uploads.notification(this, "UIDT: $id"), JOB_END_NOTIFICATION_POLICY_REMOVE)
        stop = false
        thread(name = "uidt-$id") {
            val ok = Uploads.uploadBlocking(id) { stop }
            jobFinished(params, false)
            Log.i(TAG, "UIDT jobFinished $id ok=$ok")
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        val id = params.extras.getString("id") ?: "uidt"
        val reason = "stopReason=${params.stopReason}"
        Log.w(TAG, "UIDT onStopJob $id $reason")
        Uploads.stopped(id, reason)
        stop = true
        return false
    }

    companion object {
        const val NOTIFICATION_ID = 901
    }
}
