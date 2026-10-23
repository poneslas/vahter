package ru.poneslas.vahter.imeprobe

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

object Probe {
    const val VERSION = "0.3"
    const val MARKER = "Вахтёр на связи ✓"
    const val KEY_HOST = "ime_host"
    const val KEY_TIME = "ime_time"
    const val KEY_BACK = "ime_back"
    const val KEY_VARIANT = "ime_variant"
    const val VOICE_VARIANT = "голосовой"
    const val GBOARD = "com.google.android.inputmethod.latin"
    /** «Голосовой ввод Google» — отдельная голосовая клавиатура в пакете Google TTS. */
    const val GOOGLE_VOICE = "com.google.android.tts"
    fun prefs(c: Context): SharedPreferences = c.getSharedPreferences("probe", Context.MODE_PRIVATE)
}

/**
 * 0.3 — один вопрос: открывает ли микрофон Gboard голосового Вахтёра, когда «Голосовой ввод Google» выключен.
 * (0.1–0.2 проверяли Samsung и переключение клавиатуры: Samsung закрыт, переключение — только вариант «как клавиатура».)
 * Ведущий прогон: один шаг на экране, одна главная кнопка, запасные выходы — контурные.
 */
class MainActivity : Activity() {

    private enum class Step { VOICE_ON, GOOGLE_OFF, PICK_GBOARD, MIC, GOOGLE_ON, DONE }

    private val prefs by lazy { Probe.prefs(this) }
    private val imm by lazy { getSystemService(InputMethodManager::class.java) }
    private val d by lazy { resources.displayMetrics.density }
    private lateinit var root: LinearLayout
    private var field: EditText? = null
    private var step: Step
        get() = Step.valueOf(prefs.getString("step", Step.VOICE_ON.name)!!)
        set(v) { prefs.edit().putString("step", v.name).apply() }
    /** Шаг «Микрофон» пройден — ждём «Дальше». */
    private var passed = false
    /** В поле открылся не голосовой Вахтёр из Gboard, а другая клавиатура — подсказка на шаге. */
    private var wrongIme = false
    private var showDetails = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Поставили поверх 0.2 — начать с чистого листа.
        if (prefs.getString("ver", null) != Probe.VERSION) prefs.edit().clear().putString("ver", Probe.VERSION).apply()
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(root)
            setOnApplyWindowInsetsListener { v, insets ->
                val i = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                v.setPadding(i.left + dp(24), i.top + dp(32), i.right + dp(24), i.bottom + dp(24))
                insets
            }
        }
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        autoAdvance()
    }

    /** Список клавиатур закрылся — проверить, выбран ли Gboard. */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || !currentIsGboard()) return
        if (step == Step.PICK_GBOARD) go(Step.MIC)
        else if (step == Step.MIC && wrongIme) { wrongIme = false; render() }
    }

    // ---- шаги ----

    /** Шаги, которые проверяются сами: включили / выключили / выбрали — сразу дальше. */
    private fun autoAdvance() {
        when {
            step == Step.VOICE_ON && voiceOn() -> go(Step.GOOGLE_OFF)
            step == Step.GOOGLE_OFF && !googleOn() -> {
                prefs.edit().putString("shortcut_off", shortcut()).apply()
                result(Step.GOOGLE_OFF, "выключен")
                go(Step.PICK_GBOARD)
            }
            step == Step.PICK_GBOARD && currentIsGboard() -> go(Step.MIC)
            step == Step.GOOGLE_ON && googleOn() -> { result(Step.GOOGLE_ON, "включён обратно"); go(Step.DONE) }
            else -> render()
        }
    }

    private fun go(s: Step) {
        field?.let { imm.hideSoftInputFromWindow(it.windowToken, 0) }
        passed = false
        wrongIme = false
        step = s
        autoAdvance()
    }

    private fun answer(s: Step, text: String, next: Step) {
        result(s, text)
        go(next)
    }

    /** После микрофона — вернуть Google, если его выключали. */
    private fun afterMic() = if (result(Step.GOOGLE_OFF) == "выключен" && !googleOn()) Step.GOOGLE_ON else Step.DONE

    private fun render() {
        root.removeAllViews()
        field = null
        when (step) {
            Step.VOICE_ON -> {
                label("Подготовка")
                head("Включите «Вахтёр — голосовой ввод» в списке клавиатур")
                hint("Кнопка ниже откроет список клавиатур. Найдите там «Вахтёр — голосовой ввод», включите переключатель и вернитесь сюда — пробник продолжит сам.")
                main("Открыть список клавиатур") { openImeList() }
            }
            Step.GOOGLE_OFF -> {
                label("Шаг 1 из 4")
                head("Выключите «Голосовой ввод Google»")
                hint("Кнопка ниже откроет список клавиатур. Выключите там переключатель «Голосовой ввод Google» и вернитесь сюда — пробник продолжит сам.\n\nНа шаге 4 пробник попросит включить его обратно.")
                main("Открыть список клавиатур") { openImeList() }
            }
            Step.PICK_GBOARD -> {
                label("Шаг 2 из 4")
                head("Выберите клавиатуру Gboard")
                hint("Нажмите кнопку ниже — появится список клавиатур. Выберите в нём Gboard. Пробник продолжит сам.")
                val f = field()
                main("Выбрать клавиатуру") { pick(f) }
            }
            Step.MIC -> if (passed) {
                label("Шаг 3 из 4")
                head("Получилось ✓ Микрофон Gboard открыл Вахтёра")
                hint("Теперь на панели Вахтёра внизу нажмите «Вернуться к клавиатуре» — должна вернуться Gboard. Потом нажмите «Дальше».")
                main("Дальше") {
                    result(Step.MIC, "Вахтёр открылся")
                    prefs.edit().putString("after_back", currentIme()).apply()
                    go(afterMic())
                }
            } else {
                label("Шаг 3 из 4")
                head("Нажмите на поле, потом на микрофон в Gboard")
                hint(if (wrongIme)
                    "В поле открылся не Gboard. Нажмите «Выбрать клавиатуру», выберите Gboard и снова нажмите на поле."
                    else "Микрофон — в верхнем ряду клавиатуры Gboard, справа. Если откроется Вахтёр, пробник увидит это сам. Если откроется что-то другое — выберите ниже, что именно.")
                val f = field()
                if (wrongIme) main("Выбрать клавиатуру") { pick(f) }
                outline("Открылся голосовой ввод Google") { answer(Step.MIC, "открылся Google", afterMic()) }
                outline("Gboard просит включить голосовой ввод") { answer(Step.MIC, "Gboard просит включить", afterMic()) }
                outline("Микрофона нет") { answer(Step.MIC, "микрофона нет", afterMic()) }
                outline("Что-то другое") { answer(Step.MIC, "другое", afterMic()) }
            }
            Step.GOOGLE_ON -> {
                label("Шаг 4 из 4")
                head("Включите обратно «Голосовой ввод Google»")
                hint("Кнопка ниже откроет список клавиатур. Включите там «Голосовой ввод Google» и вернитесь сюда — пробник продолжит сам.")
                main("Открыть список клавиатур") { openImeList() }
                outline("Потом сам") { answer(Step.GOOGLE_ON, "оставлен выключенным", Step.DONE) }
            }
            Step.DONE -> report()
        }
    }

    /** Фраза пришла в поле: засчитываем, только если её вставил голосовой Вахтёр (его зовёт микрофон Gboard). */
    private fun onMarker(f: EditText) {
        if (step != Step.MIC || passed) { f.text.clear(); return }
        if (prefs.getString(Probe.KEY_VARIANT, null) == Probe.VOICE_VARIANT) passed = true
        else wrongIme = true // Вахтёр «как клавиатура» остался выбранным — это не ответ Gboard
        root.post { render() }
    }

    // ---- итог ----

    private fun report() {
        label("Готово")
        head("Отправьте отчёт в чат")
        for (line in summary()) hint("• $line")
        main("Поделиться отчётом") {
            val text = (listOf("Пробник клавиатуры ${Probe.VERSION}") + summary() + "" + details()).joinToString("\n")
            startActivity(Intent.createChooser(
                Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null))
        }
        outline(if (showDetails) "Скрыть подробности" else "Подробности") { showDetails = !showDetails; render() }
        if (showDetails) root.addView(TextView(this).apply {
            text = details().joinToString("\n")
            textSize = 13f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(0, dp(8), 0, dp(8))
        })
        outline("Пройти заново") {
            prefs.edit().clear().putString("ver", Probe.VERSION).apply()
            go(Step.VOICE_ON)
        }
    }

    private fun summary(): List<String> = buildList {
        if (result(Step.GOOGLE_OFF) != "выключен") add("«Голосовой ввод Google» не выключали — проверка не состоялась.")
        add(when (result(Step.MIC)) {
            "Вахтёр открылся" -> "С выключенным «Голосовым вводом Google» микрофон Gboard открывает Вахтёра."
            "открылся Google" -> "Микрофон Gboard всё равно открывает голосовой ввод Google."
            "Gboard просит включить" -> "Без «Голосового ввода Google» Gboard просит его включить — Вахтёра не берёт."
            "микрофона нет" -> "Без «Голосового ввода Google» в Gboard пропадает микрофон."
            "другое" -> "Микрофон Gboard открыл что-то другое."
            else -> "До микрофона не дошли."
        })
        prefs.getString("after_back", null)?.let {
            add(if (it.startsWith(Probe.GBOARD)) "«Вернуться к клавиатуре» вернул Gboard." else "После «Вернуться к клавиатуре» стоит не Gboard: $it")
        }
        when (result(Step.GOOGLE_ON)) {
            "включён обратно" -> add("«Голосовой ввод Google» включён обратно.")
            "оставлен выключенным" -> add("«Голосовой ввод Google» остался выключенным — включите его в списке клавиатур.")
        }
    }

    private fun details(): List<String> = buildList {
        add("Телефон: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.DISPLAY}")
        add("Gboard: ${versionOf(Probe.GBOARD)}; Google TTS: ${versionOf(Probe.GOOGLE_VOICE)}")
        add("Ответы: " + Step.entries.filter { it != Step.DONE }.joinToString { "${it.name}=${result(it) ?: "—"}" })
        add("Текущая клавиатура: ${currentIme()}")
        add("Последний вызов Вахтёра: вариант «${prefs.getString(Probe.KEY_VARIANT, "—")}» из ${prefs.getString(Probe.KEY_HOST, "—")}; «Вернуться» сработало: ${if (prefs.contains(Probe.KEY_BACK)) prefs.getBoolean(Probe.KEY_BACK, false) else "—"}")
        add("Быстрый голосовой ввод (shortcut IME): ${shortcut()}")
        prefs.getString("shortcut_off", null)?.let { add("… когда Google был выключен: $it") }
    }

    // ---- помощники ----

    private fun voiceOn() = imm.enabledInputMethodList.any { it.packageName == packageName && it.serviceName.endsWith(".VoiceIme") }
    private fun googleOn() = imm.enabledInputMethodList.any { it.packageName == Probe.GOOGLE_VOICE }
    private fun currentIme(): String = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD) ?: "—"
    private fun currentIsGboard() = currentIme().startsWith(Probe.GBOARD)
    private fun versionOf(pkg: String) = try {
        packageManager.getPackageInfo(pkg, 0).versionName ?: "?"
    } catch (_: PackageManager.NameNotFoundException) { "нет" }

    private fun result(s: Step): String? = prefs.getString("r_${s.name}", null)
    private fun result(s: Step, v: String) { prefs.edit().putString("r_${s.name}", v).apply() }

    private fun shortcut() = imm.shortcutInputMethodsAndSubtypes.entries
        .joinToString { (i, subs) -> "${i.id} ${subs.map { it.languageTag }}" }.ifEmpty { "нет" }

    private fun openImeList() = startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))

    private fun pick(f: EditText) {
        f.requestFocus()
        imm.showSoftInput(f, 0)
        f.postDelayed({ imm.showInputMethodPicker() }, 300)
    }

    private fun dp(v: Int) = (v * d).toInt()

    private fun label(t: String) = root.addView(TextView(this).apply {
        text = t
        textSize = 14f
        alpha = 0.6f
        setPadding(0, 0, 0, dp(4))
    })

    private fun head(t: String) = root.addView(TextView(this).apply {
        text = t
        textSize = 24f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, 0, 0, dp(12))
    })

    private fun hint(t: String) = root.addView(TextView(this).apply {
        text = t
        textSize = 16f
        alpha = 0.8f
        setPadding(0, 0, 0, dp(16))
    })

    private fun field(): EditText = EditText(this).apply {
        hint = "Поле для проверки"
        minHeight = dp(56)
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { if (s?.contains(Probe.MARKER) == true) onMarker(this@apply) }
        })
        field = this
        root.addView(this, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) })
    }

    private fun main(t: String, onClick: () -> Unit) = root.addView(Button(this).apply {
        text = t
        isAllCaps = false
        textSize = 17f
        setOnClickListener { onClick() }
    }, LinearLayout.LayoutParams(-1, dp(56)).apply { bottomMargin = dp(8) })

    private fun outline(t: String, onClick: () -> Unit) = root.addView(Button(this, null, android.R.attr.borderlessButtonStyle).apply {
        text = t
        isAllCaps = false
        textSize = 16f
        background = GradientDrawable().apply {
            cornerRadius = dp(28).toFloat()
            setStroke(dp(1), currentTextColor)
        }
        setOnClickListener { onClick() }
    }, LinearLayout.LayoutParams(-1, dp(52)).apply { bottomMargin = dp(8) })
}
