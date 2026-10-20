package ru.poneslas.vahter.probe.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.util.Log
import ru.poneslas.vahter.probe.TAG
import ru.poneslas.vahter.probe.push.NotifyTrampolineActivity

/**
 * Уведомление «ответ готов» в v1 без пуша: процесс помощника сам показывает его, дочитав поток.
 * Нажатие → батут из push/ → showSession (как 12а); кнопка «Ответить» — RemoteInput → [ReplyReceiver].
 */
object AnswerNotifier {
    const val CHANNEL = "probe_answer"
    const val ID_ANSWER = 2301
    const val ID_REPLY = 2302
    const val ID_VIS = 2303
    const val KEY_REPLY = "reply"
    const val SRC = "answer_notify"

    private fun channel(ctx: Context) {
        ctx.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, "Пробник: ответы", NotificationManager.IMPORTANCE_HIGH))
    }

    /** true — notify() не бросил и уведомления разрешены. */
    fun postAnswer(ctx: Context, text: String): Boolean = post(ctx, ID_ANSWER, "Вахтёр ответил", text, withReply = true)

    fun post(ctx: Context, id: Int, title: String, text: String, withReply: Boolean = false): Boolean = try {
        channel(ctx)
        val open = PendingIntent.getActivity(
            ctx, id,
            Intent(ctx, NotifyTrampolineActivity::class.java)
                .putExtra("via", "service").putExtra("src", SRC).putExtra("mode", "assistant")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val b = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setAutoCancel(true)
        if (withReply) {
            // RemoteInput требует изменяемый PendingIntent: система дописывает в него текст.
            val reply = PendingIntent.getBroadcast(
                ctx, id + 100, Intent(ctx, ReplyReceiver::class.java),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val ri = RemoteInput.Builder(KEY_REPLY).setLabel("Ответить Вахтёру").build()
            b.addAction(Notification.Action.Builder(null, "Ответить", reply).addRemoteInput(ri).build())
        }
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.notify(id, b.build())
        nm.areNotificationsEnabled()
    } catch (t: Throwable) {
        Log.w(TAG, "уведомление $id не показано", t)
        false
    }
}
