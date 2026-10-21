package ru.poneslas.vahter

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Только отладочная сборка: отправить фразу в текущий режим, как с клавиатуры (кириллицу adb input не набирает).
 *   adb shell am broadcast -n ru.poneslas.vahter.dev/DebugSay --es text 'таймер на минуту'
 * Сменить мозг текущего режима (проверка Hermes через туннель): --es brain hermes
 */
class DebugSay : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val conv = App.instance.conversation
        intent.getStringExtra("brain")?.let { conv.setBrain(conv.state.value.mode, it) }
        val text = intent.getStringExtra("text") ?: return
        conv.send(text, "клавиатура")
    }
}
