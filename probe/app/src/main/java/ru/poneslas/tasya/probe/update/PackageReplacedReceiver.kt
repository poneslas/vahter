package ru.poneslas.tasya.probe.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import ru.poneslas.tasya.probe.App
import ru.poneslas.tasya.probe.TAG
import ru.poneslas.tasya.probe.core.Header
import ru.poneslas.tasya.probe.core.ReplaceLog

/** После каждого обновления пишет, осталась ли роль (П1, П14): сразу и через 3 с. */
class PackageReplacedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        App.scope.launch {
            try {
                ReplaceLog.add(context, "сразу", Header.roleHeld(context), Header.visActive(context))
                delay(3000)
                ReplaceLog.add(context, "через 3 с", Header.roleHeld(context), Header.visActive(context))
            } finally {
                pending.finish()
            }
        }
        Log.i(TAG, "MY_PACKAGE_REPLACED")
    }
}
