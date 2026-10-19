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
import ru.poneslas.tasya.pisar.model.ModelDownload
import ru.poneslas.tasya.voice.Voice

/** Один процесс: сервис помощника, лист и приложение делят один разговор. */
class App : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    lateinit var conversation: Conversation
        private set
    lateinit var secrets: Secrets
        private set
    lateinit var voice: Voice
        private set

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
        voice = Voice(this, scope)
        // Запись останавливаем сами: после hide() система микрофон не выключает.
        SheetEvents.onHide = { voice.cancel() }
        voice.warmUp()
    }

    fun voiceStatus(): String = when (val s = ModelDownload.state.value) {
        is ModelDownload.State.Running -> "Загрузка модели: ${s.percent} %"
        is ModelDownload.State.Failed -> "Не скачалась — попробуйте ещё раз"
        else -> if (voice.installed.value) "GigaAM на телефоне · работает без интернета" else "Не скачано · 225 МБ"
    }

    companion object {
        lateinit var instance: App
            private set
    }
}
