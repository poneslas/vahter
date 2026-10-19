package ru.poneslas.tasya

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import ru.poneslas.tasya.brain.FakeBrain
import ru.poneslas.tasya.core.Conversation
import ru.poneslas.tasya.voice.NoopRecognizer
import ru.poneslas.tasya.voice.Recognizer

/** Один процесс: сервис помощника, лист и приложение делят один разговор. */
class App : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    lateinit var conversation: Conversation
        private set
    /** Веха 2 — sherpa-onnx; пока заглушка. */
    val recognizer: Recognizer = NoopRecognizer()

    override fun onCreate() {
        super.onCreate()
        instance = this
        val fake = FakeBrain()
        conversation = Conversation(this, scope) { fake }
    }

    companion object {
        lateinit var instance: App
            private set
    }
}
