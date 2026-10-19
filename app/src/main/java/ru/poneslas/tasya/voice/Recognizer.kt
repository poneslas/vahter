package ru.poneslas.tasya.voice

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Распознавание речи на телефоне (Архитектура, 4): потоковый черновик + чистовик GigaAM.
 * Веха 2 — sherpa-onnx. Пока — заглушка: запись идёт, текста нет.
 */
interface Recognizer {
    /** Черновик «караоке», пока человек говорит. */
    val partial: StateFlow<String>
    fun start()
    /** Остановить и вернуть чистовик. */
    suspend fun stop(): String
    fun cancel()
    val available: Boolean
}

class NoopRecognizer : Recognizer {
    override val partial = MutableStateFlow("")
    override val available = false
    override fun start() { partial.value = "" }
    override suspend fun stop(): String = ""
    override fun cancel() { partial.value = "" }
}
