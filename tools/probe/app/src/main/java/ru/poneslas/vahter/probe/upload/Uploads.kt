package ru.poneslas.vahter.probe.upload

import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import ru.poneslas.vahter.probe.TAG
import ru.poneslas.vahter.probe.core.Config
import java.net.HttpURLConnection
import java.net.URL
import kotlin.random.Random

/** Состояние одной загрузки; живёт в памяти процесса (после `am kill` — смотреть /status сервера). */
data class Progress(
    val id: String,
    val variant: String,
    val total: Long,
    val bytes: Long = 0,
    val tCall: Long = SystemClock.uptimeMillis(),
    val tFirstByte: Long? = null,
    val tEnd: Long? = null,
    val ok: Boolean = false,
    val error: String? = null,
    val stopReason: String? = null,
) {
    val finished get() = tEnd != null
}

object Uploads {
    const val SIZE = 20L * 1024 * 1024
    const val CHANNEL = "upload"

    val all = MutableStateFlow<Map<String, Progress>>(emptyMap())

    fun start(id: String, variant: String, total: Long = SIZE) {
        all.update { it + (id to Progress(id, variant, total)) }
    }

    fun fail(id: String, error: String) {
        Log.w(TAG, "upload $id: $error")
        all.update { m -> m[id]?.let { m + (id to it.copy(error = error, tEnd = SystemClock.uptimeMillis())) } ?: m }
    }

    fun stopped(id: String, reason: String) {
        all.update { m -> m[id]?.let { m + (id to it.copy(stopReason = reason)) } ?: m }
    }

    private fun set(id: String, f: (Progress) -> Progress) =
        all.update { m -> m[id]?.let { m + (id to f(it)) } ?: m }

    /**
     * Блокирующая загрузка [total] случайных байт PUT-ом на `<сервер>/upload?id=…`.
     * Вызывать из фонового потока. [cancelled] проверяется между кусками.
     */
    fun uploadBlocking(id: String, total: Long = SIZE, cancelled: () -> Boolean = { false }): Boolean {
        val conn = URL("${Config.server}/upload?id=$id").openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "PUT"
            conn.doOutput = true
            conn.connectTimeout = 10_000
            conn.readTimeout = 60_000
            conn.setFixedLengthStreamingMode(total)
            conn.setRequestProperty("Content-Type", "application/octet-stream")
            val buf = ByteArray(64 * 1024)
            var sent = 0L
            conn.outputStream.use { out ->
                while (sent < total) {
                    if (cancelled()) throw InterruptedException("отменено (onStopJob/stopSelf)")
                    Random.nextBytes(buf)
                    val n = minOf(buf.size.toLong(), total - sent).toInt()
                    out.write(buf, 0, n)
                    sent += n
                    val s = sent
                    set(id) { it.copy(bytes = s, tFirstByte = it.tFirstByte ?: SystemClock.uptimeMillis()) }
                }
            }
            val code = conn.responseCode
            val ok = code == 200
            set(id) { it.copy(ok = ok, tEnd = SystemClock.uptimeMillis(), error = if (ok) null else "HTTP $code") }
            Log.i(TAG, "upload $id: HTTP $code, $sent байт")
            ok
        } catch (t: Throwable) {
            fail(id, "${t.javaClass.simpleName}: ${t.message}")
            false
        } finally {
            conn.disconnect()
        }
    }

    fun importance(): Int {
        val info = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(info)
        return info.importance
    }

    fun notification(ctx: Context, text: String): android.app.Notification {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Загрузки пробника", NotificationManager.IMPORTANCE_LOW))
        }
        return NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Пробник: загрузка")
            .setContentText(text)
            .setOngoing(true)
            .build()
    }
}
