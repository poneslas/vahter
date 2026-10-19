package ru.poneslas.tasya.probe.push

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import kotlinx.coroutines.launch
import ru.poneslas.tasya.probe.App
import ru.poneslas.tasya.probe.entry.EntryLauncher

/**
 * 12б: уведомление → PendingIntent.getBroadcast → сюда → showSession. Это не запуск активности,
 * поэтому запрет батутов Android 12 формально не касается [AOSP; на Samsung — ?]. Шторку закрывает лист (collapse).
 */
class NotifyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val t = SystemClock.uptimeMillis()
        val pending = goAsync()
        val args = EntryLauncher.argsFrom(intent.extras).apply { putBoolean("collapse", true) }
        App.scope.launch {
            try {
                EntryLauncher.viaService(intent.getStringExtra("src") ?: "notify_broadcast", args, t)
            } finally {
                pending.finish()
            }
        }
    }
}
