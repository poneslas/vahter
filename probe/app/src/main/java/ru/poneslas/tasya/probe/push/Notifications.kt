package ru.poneslas.tasya.probe.push

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/** Три варианта нажатия на уведомление (П12): а — активность → сервис, б — приёмник → сервис, в — активность → showAssist. */
object Notifications {
    const val CHANNEL = "probe_push"

    data class Variant(val key: String, val src: String, val id: Int, val title: String)

    val variants = listOf(
        Variant("а", "notify_activity", 1201, "а: активность → сервис"),
        Variant("б", "notify_broadcast", 1202, "б: приёмник → сервис"),
        Variant("в", "notify_showAssist", 1203, "в: активность → showAssist"),
    )

    fun ensureChannel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Пробник: пуши", NotificationManager.IMPORTANCE_HIGH))
    }

    fun post(ctx: Context, v: Variant, text: String) {
        ensureChannel(ctx)
        val pi = when (v.key) {
            "б" -> PendingIntent.getBroadcast(
                ctx, v.id,
                Intent(ctx, NotifyReceiver::class.java).putExtra("src", v.src).putExtra("mode", "assistant"),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            else -> PendingIntent.getActivity(
                ctx, v.id,
                Intent(ctx, NotifyTrampolineActivity::class.java)
                    .putExtra("via", if (v.key == "в") "showAssist" else "service")
                    .putExtra("src", v.src).putExtra("mode", "assistant")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }
        val n = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Тася ответила · ${v.title}")
            .setContentText(text)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(v.id, n)
    }

    fun postAll(ctx: Context, text: String) = variants.forEach { post(ctx, it, text) }

    fun enabled(ctx: Context) = ctx.getSystemService(NotificationManager::class.java).areNotificationsEnabled()
}
