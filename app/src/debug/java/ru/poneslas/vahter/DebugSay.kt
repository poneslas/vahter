package ru.poneslas.vahter

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Только отладочная сборка: отправить фразу в текущий режим, как с клавиатуры (кириллицу adb input не набирает).
 *   adb shell am broadcast -n ru.poneslas.vahter.dev/DebugSay --es text 'таймер на минуту'
 */
class DebugSay : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val text = intent.getStringExtra("text") ?: return
        App.instance.conversation.send(text, "клавиатура")
    }
}
