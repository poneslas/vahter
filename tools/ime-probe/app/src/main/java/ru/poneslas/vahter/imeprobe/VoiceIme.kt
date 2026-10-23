package ru.poneslas.vahter.imeprobe

import android.graphics.Typeface
import android.inputmethodservice.InputMethodService
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Пустая голосовая клавиатура: не слушает, а вставляет в поле проверочную фразу. */
open class VoiceIme : InputMethodService() {

    override fun onCreateInputView(): View {
        val d = resources.displayMetrics.density
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding((24 * d).toInt(), (24 * d).toInt(), (24 * d).toInt(), (16 * d).toInt())
            addView(TextView(context).apply {
                text = "🎙 Вахтёр на связи"
                textSize = 22f
                setTypeface(typeface, Typeface.BOLD)
                gravity = Gravity.CENTER
            })
            addView(TextView(context).apply {
                text = "Это пробник: фраза уже вставлена в поле."
                textSize = 15f
                gravity = Gravity.CENTER
                setPadding(0, (8 * d).toInt(), 0, (16 * d).toInt())
            })
            addView(Button(context).apply {
                text = "Вернуться к клавиатуре"
                setOnClickListener {
                    val back = switchToPreviousInputMethod()
                    Probe.prefs(context).edit().putBoolean(Probe.KEY_BACK, back).apply()
                }
            })
        }
    }

    open val variant = "голосовой"

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        if (restarting) return
        // Сначала запись, потом фраза: пробник по фразе читает, какой вариант открылся.
        Probe.prefs(this).edit()
            .putString(Probe.KEY_HOST, info.packageName)
            .putString(Probe.KEY_VARIANT, variant)
            .putLong(Probe.KEY_TIME, System.currentTimeMillis())
            .apply()
        currentInputConnection?.commitText(Probe.MARKER, 1)
    }
}

/** Та же панель, но объявлена обычной клавиатурой (method_keyboard.xml). */
class VoiceImeKeyboard : VoiceIme() {
    override val variant = "как клавиатура"
}
