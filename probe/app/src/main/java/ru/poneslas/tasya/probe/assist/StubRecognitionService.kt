package ru.poneslas.tasya.probe.assist

import android.content.Intent
import android.speech.RecognitionService
import android.speech.SpeechRecognizer

/** Пустой RecognitionService: без него роль помощника сбрасывается при обновлении (П1). */
class StubRecognitionService : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {
        listener?.error(SpeechRecognizer.ERROR_CLIENT)
    }
    override fun onCancel(listener: Callback?) {}
    override fun onStopListening(listener: Callback?) {}
}
