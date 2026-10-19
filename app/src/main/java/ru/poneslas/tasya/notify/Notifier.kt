package ru.poneslas.tasya.notify

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import ru.poneslas.tasya.App
import ru.poneslas.tasya.app.MainActivity
import ru.poneslas.tasya.assistant.Sheet
import ru.poneslas.tasya.core.LockScope
import ru.poneslas.tasya.core.Mode

/**
 * «Задал и ушёл»: запрос дожимается в процессе (проверено на S24 — 90/90 после закрытия листа),
 * ответ — уведомлением телефона; нажатие открывает лист в нужном режиме. Своего пуш-сервера нет.
 * На заблокированном экране (если не «Открывать») — без текста: «Ответ готов».
 */
object Notifier {
    private const val CHANNEL = "answers"

    fun init(ctx: Context) {
        ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Ответы", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Ответ пришёл, пока лист был закрыт"
            },
        )
    }

    fun answer(ctx: Context, mode: Mode, text: String) {
        if (Sheet.visible.value || App.instance.appVisible.value) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (!nm.areNotificationsEnabled()) return
        val locked = ctx.getSystemService(KeyguardManager::class.java).isKeyguardLocked
        val hide = locked && App.instance.conversation.state.value.settings.lockScope != LockScope.ON
        val open = PendingIntent.getBroadcast(
            ctx, mode.id.hashCode(),
            Intent(ctx, OpenSheetReceiver::class.java).putExtra("mode", mode.id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val public = Notification.Builder(ctx, CHANNEL).setSmallIcon(android.R.drawable.sym_action_chat)
            .setContentTitle(mode.title).setContentText("Ответ готов").build()
        val n = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.sym_action_chat)
            .setContentTitle(mode.title)
            .setContentText(if (hide) "Ответ готов" else text.lineSequence().take(2).joinToString(" ").take(200))
            .setStyle(if (hide) null else Notification.BigTextStyle().bigText(text.take(1000)))
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        nm.notify(mode.id.hashCode(), n)
    }
}

/** Нажатие на уведомление: showSession из процесса помощника разрешён (AOSP; П12/П23 на S24). */
class OpenSheetReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        intent.getStringExtra("mode")?.let { App.instance.conversation.selectMode(it) }
        // Баг №10: из уведомления — на разговор с ответом, без камеры и без записи.
        if (Sheet.show(Bundle().apply { putString("src", "notification") }) == null) {
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
