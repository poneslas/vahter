package ru.poneslas.vahter.probe.upload

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.util.Log
import ru.poneslas.vahter.probe.TAG
import kotlin.concurrent.thread

/** FGS типа dataSync для П9. Исключение запуска пишется в Uploads как ошибка варианта. */
class DataSyncService : Service() {
    @Volatile private var stop = false

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getStringExtra("id") ?: "fgs"
        try {
            startForeground(NOTIFICATION_ID, Uploads.notification(this, "FGS dataSync: $id"), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } catch (t: Throwable) {
            Uploads.fail(id, "startForeground: ${t.javaClass.simpleName}: ${t.message}")
            stopSelf(startId)
            return START_NOT_STICKY
        }
        Log.i(TAG, "FGS dataSync started $id")
        thread(name = "fgs-$id") {
            Uploads.uploadBlocking(id) { stop }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    /** Android 15+: лимит dataSync 6 ч в сутки — система зовёт onTimeout. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(TAG, "FGS dataSync onTimeout type=$fgsType")
        stop = true
        stopSelf(startId)
    }

    override fun onDestroy() {
        stop = true
        super.onDestroy()
    }

    companion object {
        const val NOTIFICATION_ID = 902
    }
}
