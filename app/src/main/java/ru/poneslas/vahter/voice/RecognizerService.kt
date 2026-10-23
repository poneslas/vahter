package ru.poneslas.vahter.voice

import android.content.ContextParams
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.poneslas.vahter.App
import ru.poneslas.vahter.pisar.audio.AudioRecorder

/**
 * Системный распознаватель. Без него роль помощника слетает при обновлении (Архитектура 3).
 * Распознавание по умолчанию на S24 остаётся у Google — сюда приходят те, кто зовёт службу по имени.
 * Распознаёт тот же GigaAM, что в листе: на телефоне, только русский, текст целиком
 * в конце фразы (промежуточного нет). Клавиатуры Samsung и Gboard сюда не ходят — у них своё
 * распознавание (пробник `tools/ime-probe`, 23.10.2026).
 */
class RecognizerService : RecognitionService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var recorder: AudioRecorder? = null
    /** Вызвавшее приложение сказало «хватит» — распознать, что есть. */
    private var stopNow: CompletableDeferred<Unit>? = null

    override fun onStartListening(intent: Intent?, listener: Callback) {
        val voice = (application as App).voice
        val lang = intent?.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE)
        val refusal = when {
            job?.isActive == true || voice.recording.value -> SpeechRecognizer.ERROR_RECOGNIZER_BUSY
            lang != null && !lang.startsWith("ru") -> SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED
            !voice.installed.value -> SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE
            else -> null
        }
        if (refusal != null) return listener.send { error(refusal) }

        // Пишем от имени вызвавшего приложения: оно на экране, Вахтёр — в фоне.
        val rec = AudioRecorder(context = createContext(
            ContextParams.Builder().setNextAttributionSource(listener.callingAttributionSource).build()))
        if (!rec.start()) return listener.send { error(SpeechRecognizer.ERROR_AUDIO) }
        recorder = rec
        val stop = CompletableDeferred<Unit>().also { stopNow = it }
        val silenceMs = intent?.getLongExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 0L)
            ?.takeIf { it > 0 } ?: SILENCE_MS
        listener.send { readyForSpeech(Bundle()) }

        job = scope.launch {
            val started = SystemClock.elapsedRealtime()
            var spoke = false
            var lastVoice = started
            while (!stop.isCompleted) {
                delay(TICK_MS)
                val level = rec.level
                val now = SystemClock.elapsedRealtime()
                listener.send { rmsChanged(-2f + level * 12f) }
                if (level > SPEECH_LEVEL) {
                    if (!spoke) listener.send { beginningOfSpeech() }
                    spoke = true
                    lastVoice = now
                }
                if (!spoke && now - started > NO_SPEECH_MS) {
                    rec.cancel()
                    listener.send { error(SpeechRecognizer.ERROR_SPEECH_TIMEOUT) }
                    return@launch
                }
                if (spoke && now - lastVoice > silenceMs || now - started > MAX_MS) break
            }
            listener.send { endOfSpeech() }
            val text = withContext(Dispatchers.IO) {
                val audio = rec.stop()
                if (audio.size < AudioRecorder.SAMPLE_RATE / 5) "" else runCatching { voice.transcribe(audio).trim() }.getOrNull()
            }
            when {
                text == null -> listener.send { error(SpeechRecognizer.ERROR_CLIENT) }
                text.isEmpty() -> listener.send { error(SpeechRecognizer.ERROR_NO_MATCH) }
                else -> listener.send {
                    results(Bundle().apply {
                        putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(text))
                        putFloatArray(SpeechRecognizer.CONFIDENCE_SCORES, floatArrayOf(1f))
                    })
                }
            }
        }.also { it.invokeOnCompletion { recorder = null; stopNow = null } }
    }

    override fun onStopListening(listener: Callback?) {
        stopNow?.complete(Unit)
    }

    override fun onCancel(listener: Callback?) {
        job?.cancel()
        recorder?.cancel()
    }

    override fun onDestroy() {
        recorder?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    /** Вызвавшее приложение могло уже закрыться — тогда слушать дальше незачем. */
    private inline fun Callback.send(block: Callback.() -> Unit) {
        try { block() } catch (_: android.os.RemoteException) { onCancel(this) }
    }

    private companion object {
        const val TICK_MS = 50L
        /** Громкость 0..1 ([ru.poneslas.vahter.pisar.audio.LevelMeter]), выше — уже речь. */
        const val SPEECH_LEVEL = 0.3f
        /** Пауза после речи — конец фразы (если вызвавший не задал свою). */
        const val SILENCE_MS = 1500L
        /** Молчат с начала — отказ, как у Google. */
        const val NO_SPEECH_MS = 6000L
        const val MAX_MS = 60_000L
    }
}
