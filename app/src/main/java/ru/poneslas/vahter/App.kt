package ru.poneslas.vahter

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import ru.poneslas.vahter.brain.ApiBrain
import ru.poneslas.vahter.brain.Brain
import ru.poneslas.vahter.brain.FakeBrain
import ru.poneslas.vahter.brain.HermesBrain
import ru.poneslas.vahter.brain.HermesLink
import ru.poneslas.vahter.core.BrainDesc
import ru.poneslas.vahter.core.Brains
import ru.poneslas.vahter.core.Conversation
import ru.poneslas.vahter.core.Mode
import ru.poneslas.vahter.core.Secrets
import ru.poneslas.vahter.core.Store
import ru.poneslas.vahter.notify.Notifier
import ru.poneslas.vahter.assistant.SheetEvents
import ru.poneslas.vahter.voice.Voice

/**
 * Строка интерфейса из ресурсов (мультиязычность, Бэклог №15). Работает и в Compose, и вне его —
 * тексты, которые раньше были зашиты в код, перенесены в res/values/strings.xml (tools/i18n/extract.py).
 */
fun tr(@androidx.annotation.StringRes id: Int, vararg args: Any?): String = App.instance.getString(id, *args)

/** Множественное число из ресурсов: «1 шаг / 2 шага / 5 шагов» — правила языка знает Android. */
fun trp(@androidx.annotation.PluralsRes id: Int, n: Int): String = App.instance.resources.getQuantityString(id, n, n)

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
    private val fakeHermes = FakeBrain { tr(R.string.hermes_no_link) }
    private val apiBrains = mutableMapOf<String, ApiBrain>()

    fun apiBrain(d: BrainDesc) = apiBrains.getOrPut(d.id) { ApiBrain(d, { secrets.get("key:${d.id}") }) }

    /** Hermes: адрес — в настройках, ключ телефона — в Keystore; сессии режимов — в разговоре (Архитектура 9.0). */
    val hermesBrain: HermesBrain by lazy {
        HermesBrain(Brains.hermes, ::hermesLink, { id -> conversation.hermesSession(id) }, { id, sid -> conversation.setHermesSession(id, sid) })
    }

    fun hermesLink(): HermesLink? {
        val url = conversation.state.value.settings.hermesUrl.takeIf { it.isNotBlank() } ?: return null
        val token = secrets.get("key:hermes") ?: return null
        return HermesLink(url, token)
    }

    /** Подключить или отключить (null) Hermes: адрес и ключ телефона вместе; сессии режимов на старом сервере забываем. */
    fun setHermes(link: HermesLink?) {
        secrets.put("key:hermes", link?.token)
        conversation.updateSettings { it.copy(hermesUrl = link?.url.orEmpty()) }
        conversation.clearHermesSessions()
        keyVersion.value++
    }

    fun hasKey(brainId: String) = secrets.has("key:$brainId")

    fun setKey(brainId: String, key: String?) {
        secrets.put("key:$brainId", key)
        keyVersion.value++
    }

    private fun brainFor(mode: Mode): Brain {
        val d = Brains.byId(mode.brain)
        return when {
            d.kind == BrainDesc.Kind.HERMES -> if (hermesLink() != null) hermesBrain else fakeHermes
            hasKey(d.id) -> apiBrain(d)
            else -> fake
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        secrets = Secrets(this)
        HermesBrain.debuggable = (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        Notifier.init(this)
        conversation = Conversation(this, scope, Store(this), ::brainFor, { mode, text -> Notifier.answer(this, mode, text) }, { if (hermesLink() != null) hermesBrain else null })
        // Ходы Hermes переживают закрытие приложения: дочитать при запуске и каждый раз, когда приложение или лист снова на экране.
        conversation.resumePending()
        scope.launch { appVisible.collect { if (it) conversation.resumePending() } }
        scope.launch { ru.poneslas.vahter.assistant.Sheet.visible.collect { if (it) conversation.resumePending() } }
        voice = Voice(this, scope)
        // Запись останавливаем сами: после hide() система микрофон не выключает.
        SheetEvents.onHide = { voice.cancel() }
        voice.warmUp()
    }

    companion object {
        lateinit var instance: App
            private set
    }
}
