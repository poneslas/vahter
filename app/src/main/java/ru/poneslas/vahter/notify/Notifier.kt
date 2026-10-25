package ru.poneslas.vahter.notify

import ru.poneslas.vahter.R
import ru.poneslas.vahter.tr
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import ru.poneslas.vahter.App
import ru.poneslas.vahter.assistant.TileOpen
import ru.poneslas.vahter.assistant.Sheet
import ru.poneslas.vahter.core.LockScope
import ru.poneslas.vahter.core.Mode
import ru.poneslas.vahter.ui.mdBlocks
import ru.poneslas.vahter.ui.mdInline

/**
 * «Задал и ушёл»: запрос дожимается в процессе (проверено на S24 — 90/90 после закрытия листа),
 * ответ — уведомлением телефона; нажатие открывает лист в нужном режиме ([TileOpen]). Своего пуш-сервера нет.
 * На заблокированном экране (если не «Открывать») — без текста: «Ответ готов».
 */
object Notifier {
    private const val CHANNEL = "answers"

    /** Уведомления на деле показываются: приложение не выключено в системе и канал ответов не выключен. */
    fun enabled(ctx: Context): Boolean {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        return nm.areNotificationsEnabled() && (nm.getNotificationChannel(CHANNEL)?.importance ?: NotificationManager.IMPORTANCE_HIGH) != NotificationManager.IMPORTANCE_NONE
    }

    fun init(ctx: Context) {
        ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, tr(R.string.ntf_channel), NotificationManager.IMPORTANCE_HIGH).apply {
                description = tr(R.string.ntf_answer_while_closed)
            },
        )
    }

    fun answer(ctx: Context, mode: Mode, text: String) {
        if (Sheet.visible.value || App.instance.appVisible.value) return
        if (!App.instance.conversation.state.value.settings.notifyAnswers) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (!nm.areNotificationsEnabled()) return
        val locked = ctx.getSystemService(KeyguardManager::class.java).isKeyguardLocked
        // Ответ — разметка: в уведомлении без «**» и «#», без пустых строк. Ответ Hermes часто начинается с пустых строк —
        // раньше первые две строки были пустыми и уведомление показывало одно название режима.
        val plain = mdBlocks(text).joinToString("\n") { (kind, t) -> if (kind == 'l') "• " + mdInline(t).text else mdInline(t).text }
            .ifBlank { tr(R.string.ntf_answer_ready) }
        val hide = locked && App.instance.conversation.state.value.settings.lockScope != LockScope.ON
        // Сразу экран, не приёмник: с Android 12 экран из приёмника уведомления не запускается («трамплин»), и если процесс
        // к нажатию уже выгружен (лист не поднять из сервиса помощника), ничего не открывалось.
        val open = PendingIntent.getActivity(
            ctx, mode.id.hashCode(),
            Intent(ctx, TileOpen::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(TileOpen.EXTRA_MODE, mode.id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val public = Notification.Builder(ctx, CHANNEL).setSmallIcon(android.R.drawable.sym_action_chat)
            .setContentTitle(mode.title).setContentText(tr(R.string.ntf_answer_ready)).build()
        val n = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.sym_action_chat)
            .setContentTitle(mode.title)
            .setContentText(if (hide) tr(R.string.ntf_answer_ready) else plain.replace('\n', ' ').take(200))
            .setStyle(if (hide) null else Notification.BigTextStyle().bigText(plain.take(1000)))
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        nm.notify(mode.id.hashCode(), n)
    }
}
