package ru.poneslas.tasya

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import ru.poneslas.tasya.brain.ApiBrain
import ru.poneslas.tasya.brain.Brain
import ru.poneslas.tasya.brain.FakeBrain
import ru.poneslas.tasya.core.BrainDesc
import ru.poneslas.tasya.core.Brains
import ru.poneslas.tasya.core.Conversation
import ru.poneslas.tasya.core.Mode
import ru.poneslas.tasya.core.Secrets
import ru.poneslas.tasya.core.Store
import ru.poneslas.tasya.notify.Notifier
import kotlinx.coroutines.launch
import ru.poneslas.tasya.assistant.Sheet
import ru.poneslas.tasya.assistant.SheetEvents
import ru.poneslas.tasya.voice.Models
import ru.poneslas.tasya.voice.Recognizer
import ru.poneslas.tasya.voice.SherpaRecognizer

/** Один процесс: сервис помощника, лист и приложение делят один разговор. */
class App : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    lateinit var conversation: Conversation
        private set
    lateinit var secrets: Secrets
        private set
    lateinit var models: Models
        private set
    lateinit var sherpa: SherpaRecognizer
        private set
    val recognizer: Recognizer get() = sherpa

    /** Приложение на экране — уведомление об ответе не нужно. */
    val appVisible = MutableStateFlow(false)
    /** Ключ мозга есть — меняется при вставке ключа. */
    val keyVersion = MutableStateFlow(0)

    private val fake = FakeBrain()
    private val apiBrains = mutableMapOf<String, ApiBrain>()

    fun apiBrain(d: BrainDesc) = apiBrains.getOrPut(d.id) { ApiBrain(d, { secrets.get("key:${d.id}") }) }

    fun hasKey(brainId: String) = secrets.has("key:$brainId")

    fun setKey(brainId: String, key: String?) {
        secrets.put("key:$brainId", key)
        keyVersion.value++
    }

    private fun brainFor(mode: Mode): Brain {
        val d = Brains.byId(mode.brain)
        return if (d.kind == BrainDesc.Kind.API && hasKey(d.id)) apiBrain(d) else fake
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        secrets = Secrets(this)
        Notifier.init(this)
        conversation = Conversation(this, scope, Store(this), ::brainFor) { mode, text -> Notifier.answer(this, mode, text) }
        models = Models(this, scope)
        sherpa = SherpaRecognizer(this, models, scope)
        // Ранний старт: AudioRecord создаём в onPrepareToShowSession, запускаем только на запись (Архитектура 3).
        Sheet.prepareHooks += { sherpa.prepare() }
        // Запись останавливаем сами: после hide() система микрофон не выключает.
        SheetEvents.onHide = { sherpa.cancel() }
        warmUp()
    }

    /** Прогрев распознавания, когда модели на месте (после загрузки — тоже). */
    fun warmUp() { scope.launch { runCatching { sherpa.warmUp() } } }

    fun voiceStatus(): String = when (val s = models.status.value) {
        Models.Status.Ready -> if (sherpa.available) "На телефоне · ${ru.poneslas.tasya.voice.ModelFiles.totalBytes / 1_048_576} МБ · готово" else "На телефоне · прогревается"
        is Models.Status.Downloading -> if (s.waitingNetwork) "Ждёт сеть: ${s.percent} %" else "Качается: ${s.percent} %"
        Models.Status.Verifying -> "Проверяю файл…"
        is Models.Status.Failed -> "Ошибка: ${s.message}"
        Models.Status.None -> "Не скачано · ${ru.poneslas.tasya.voice.ModelFiles.totalBytes / 1_048_576} МБ"
    }

    companion object {
        lateinit var instance: App
            private set
    }
}
