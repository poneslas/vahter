package ru.poneslas.vahter.imeprobe

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * 0.5 — проверка распознавателя Вахтёра: зовём его службу по имени (`createSpeechRecognizer(ctx, component)`).
 * 0.4 звал службу по умолчанию — на S24 это Google, Вахтёр не отвечал (23.10).
 * Клавиатурный прогон 0.1–0.3 — [MainActivity], из списка приложений убран: опыты закончены.
 */
class RecognizerCheckActivity : Activity() {

    private val d by lazy { resources.displayMetrics.density }
    private lateinit var root: LinearLayout
    private var recognizer: SpeechRecognizer? = null
    private var status = "Нажмите «Говорить» и скажите любую фразу по-русски. Замолчите — распознавание начнётся само."
    private var result: String? = null
    private var listening = false
    private val log = mutableListOf<String>()
    private var t0 = 0L
    private var endAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        setContentView(ScrollView(this).apply {
            addView(root)
            setOnApplyWindowInsetsListener { v, insets ->
                val i = insets.getInsets(WindowInsets.Type.systemBars())
                v.setPadding(i.left + dp(24), i.top + dp(32), i.right + dp(24), i.bottom + dp(24))
                insets
            }
        })
        render()
    }

    override fun onDestroy() {
        recognizer?.destroy()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, grants: IntArray) {
        if (grants.firstOrNull() == PackageManager.PERMISSION_GRANTED) listen() else {
            status = "Без доступа к микрофону проверить нельзя. Нажмите «Говорить» ещё раз и разрешите."
            render()
        }
    }

    private fun listen() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            return
        }
        val service = vahterService() ?: run {
            done("Вахтёр 1.0.6 не найден. Обновите Вахтёра и повторите.", "нет службы Вахтёра")
            return
        }
        recognizer?.destroy()
        log.clear()
        result = null
        t0 = SystemClock.elapsedRealtime()
        endAt = 0L
        recognizer = SpeechRecognizer.createSpeechRecognizer(this, service).apply {
            setRecognitionListener(Listener())
            startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU"))
        }
        listening = true
        status = "Подождите…"
        render()
    }

    private fun done(text: String, short: String) {
        listening = false
        status = text
        result = short
        log += "итог: $short"
        render()
    }

    private inner class Listener : RecognitionListener {
        private var loudest = -100f
        private fun mark(event: String) { log += "${SystemClock.elapsedRealtime() - t0} мс: $event" }

        override fun onReadyForSpeech(params: Bundle?) { mark("готов"); status = "Говорите…"; render() }
        override fun onBeginningOfSpeech() { mark("начало речи") }
        override fun onRmsChanged(rmsdB: Float) { loudest = maxOf(loudest, rmsdB) }
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {
            endAt = SystemClock.elapsedRealtime()
            mark("конец речи (громкость до ${"%.1f".format(loudest)})")
            status = "Распознаю…"
            render()
        }
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onResults(results: Bundle?) {
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            val ms = if (endAt > 0) SystemClock.elapsedRealtime() - endAt else -1
            mark("результат через $ms мс после конца речи")
            done("Распознано ✓\n\n«$text»", "распознано: «$text», $ms мс")
        }

        override fun onError(error: Int) {
            mark("ошибка $error")
            done(errorText(error), "ошибка $error")
        }
    }

    private fun errorText(code: Int) = when (code) {
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Речи не услышал. Нажмите «Говорить» и начните говорить сразу."
        SpeechRecognizer.ERROR_NO_MATCH -> "Запись была, но слов в ней не нашлось. Попробуйте громче."
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "Вахтёр ответил: модель речи не скачана. Скачайте её: Вахтёр → Настройки → Голос, и повторите."
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "Распознаватель не знает русского."
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Распознаватель занят — возможно, открыт лист Вахтёра. Закройте его и повторите."
        SpeechRecognizer.ERROR_AUDIO -> "Распознаватель не смог включить микрофон."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Не хватает разрешения на микрофон."
        SpeechRecognizer.ERROR_CLIENT -> "Распознаватель отказал: сбой распознавания или старый Вахтёр с заглушкой."
        else -> "Ошибка $code."
    }

    // ---- экран ----

    private fun render() = runOnUiThread {
        root.removeAllViews()
        label("Проверка распознавателя")
        head(if (result == null) "Скажите фразу — распознает ли Вахтёр?" else "Готово")
        text(status, 18f)
        button(if (listening) "Слушаю…" else if (result == null) "Говорить" else "Ещё раз") { if (!listening) listen() }
        if (listening) button("Хватит, распознавай") { recognizer?.stopListening() }
        if (result != null) {
            button("Поделиться отчётом") {
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, report().joinToString("\n")), null))
            }
            text(report().drop(1).joinToString("\n"), 13f).apply { typeface = Typeface.MONOSPACE; alpha = 0.7f }
        }
    }

    private fun report(): List<String> = buildList {
        add("Пробник: распознаватель Вахтёра по имени (0.5)")
        add("Служба: ${vahterService()?.flattenToShortString() ?: "не найдена"}")
        add("Итог: $result")
        add("Служба по умолчанию: ${Settings.Secure.getString(contentResolver, "voice_recognition_service") ?: "не прочитать"}")
        add("Помощник: ${Settings.Secure.getString(contentResolver, "assistant") ?: "не прочитать"}")
        add("Телефон: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        addAll(log)
    }

    /** Служба Вахтёра — выпускного или -dev, что установлено. */
    private fun vahterService(): ComponentName? = listOf("ru.poneslas.vahter", "ru.poneslas.vahter.dev")
        .map { ComponentName(it, "ru.poneslas.vahter.voice.RecognizerService") }
        .firstOrNull { runCatching { packageManager.getServiceInfo(it, 0) }.isSuccess }

    private fun dp(v: Int) = (v * d).toInt()

    private fun label(t: String) = text(t, 14f).apply { alpha = 0.6f }

    private fun head(t: String) = text(t, 24f).apply { setTypeface(typeface, Typeface.BOLD) }

    private fun text(t: String, size: Float) = TextView(this).apply {
        text = t
        textSize = size
        setTextIsSelectable(true)
        setPadding(0, 0, 0, dp(16))
        root.addView(this)
    }

    private fun button(t: String, onClick: () -> Unit) = root.addView(Button(this).apply {
        text = t
        isAllCaps = false
        textSize = 17f
        setOnClickListener { onClick() }
    }, LinearLayout.LayoutParams(-1, dp(56)).apply { bottomMargin = dp(8) })
}
