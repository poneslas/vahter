package ru.poneslas.tasya.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.poneslas.tasya.pisar.audio.AudioRecorder
import ru.poneslas.tasya.pisar.model.ModelDownload
import ru.poneslas.tasya.pisar.model.ModelManager
import ru.poneslas.tasya.pisar.speech.GigaAmOnnxRecognizer

/**
 * Голос — как в Гига Писаре для Android (пакет `pisar`): запись с первого касания, GigaAM v3 e2e
 * с пунктуацией целиком после отпускания, модель — один файл с двух зеркал. Здесь только склейка с листом.
 */
class Voice(private val context: Context, private val scope: CoroutineScope) {
    sealed interface Result {
        data class Text(val text: String) : Result
        /** Короче 0,2 с — случайное касание, молча назад (как у Писаря). */
        data object TooShort : Result
        data object Empty : Result
        data class Failed(val message: String) : Result
    }

    val model = ModelManager(context)
    private val recognizer = GigaAmOnnxRecognizer(context, model)

    /** Сработал предел записи (5 минут): интерфейс отправляет то, что есть. */
    var onTimeout: (() -> Unit)? = null
    private val recorder = AudioRecorder(onTimeout = { scope.launch { onTimeout?.invoke() } })

    private val _installed = MutableStateFlow(model.isInstalled())
    val installed: StateFlow<Boolean> = _installed
    private val _recording = MutableStateFlow(false)
    val recording: StateFlow<Boolean> = _recording

    init {
        scope.launch {
            ModelDownload.state.collect {
                if (it is ModelDownload.State.Done) { _installed.value = model.isInstalled(); warmUp() }
            }
        }
    }

    /** Громкость 0..1, подстроенная под микрофон ([ru.poneslas.tasya.pisar.audio.LevelMeter]). */
    val level: Float get() = recorder.level

    fun hasMic() = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun start(): Boolean {
        if (_recording.value) return true
        if (!recorder.start()) return false
        _recording.value = true
        return true
    }

    suspend fun stop(): Result {
        if (!_recording.value) return Result.Empty
        _recording.value = false
        return withContext(Dispatchers.IO) {
            val audio = recorder.stop()
            if (audio.size < AudioRecorder.SAMPLE_RATE / 5) return@withContext Result.TooShort
            runCatching { recognizer.transcribe(audio).trim() }.fold(
                { if (it.isBlank()) Result.Empty else Result.Text(it) },
                { Result.Failed(it.message ?: it.javaClass.simpleName) },
            )
        }
    }

    fun cancel() {
        if (!_recording.value) return
        _recording.value = false
        recorder.cancel()
    }

    /** Первое распознавание грузит модель: делаем его заранее, на секунде тишины. */
    fun warmUp() {
        if (!_installed.value) return
        scope.launch(Dispatchers.IO) { runCatching { recognizer.transcribe(ShortArray(AudioRecorder.SAMPLE_RATE)) } }
    }

    fun transcribeForTest(pcm: ShortArray): String = recognizer.transcribe(pcm)
}
