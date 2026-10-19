package ru.poneslas.tasya.probe

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.launch
import ru.poneslas.tasya.probe.assist.Sheet
import ru.poneslas.tasya.probe.core.Human

/**
 * Отладка с Mac, в том числе на заблокированном экране (туда не попасть ни скриншотом, ни касанием вслепую):
 *   adb shell am broadcast -n ru.poneslas.tasya.probe/.DebugReceiver --es tap "Разблокировать"
 *   adb shell am broadcast -n ru.poneslas.tasya.probe/.DebugReceiver --es answer "Готово"
 * Приёмник закрыт разрешением DUMP — его есть только у shell.
 */
class DebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        intent.getStringExtra("answer")?.let { Human.answer(it) }
        intent.getStringExtra("tap")?.let { label ->
            val s = Sheet.session.value
            val a = Registry.sheetActions.firstOrNull { it.label == label }
            Log.i(TAG, "debug tap «$label»: действие=${a != null}, лист=${s != null}")
            if (a != null && s != null) App.scope.launch { a.run(s) }
        }
    }
}
