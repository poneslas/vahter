package ru.poneslas.tasya.assistant

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import android.view.View
import android.view.WindowManager
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.flow.MutableStateFlow
import ru.poneslas.tasya.app.MainActivity

/** Сервис помощника. Живёт, пока Тася выбрана цифровым помощником. */
class AssistService : VoiceInteractionService() {
    override fun onReady() {
        super.onReady()
        Sheet.service = this
    }

    override fun onShutdown() {
        Sheet.service = null
        super.onShutdown()
    }

    /** Самая ранняя точка открытия (на 10–60 мс раньше onShow): здесь — ранний старт микрофона (веха 2). */
    override fun onPrepareToShowSession(args: Bundle, showFlags: Int) {
        Sheet.prepareHooks.forEach { runCatching(it) }
    }

    override fun onLaunchVoiceAssistFromKeyguard() {
        showSession(Bundle().apply { putString("src", "keyguard") }, 0)
    }
}

class SessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = AssistSession(this)
}

/** Пустой RecognitionService: без него роль помощника сбрасывается при обновлении (проверено пробником). */
class StubRecognitionService : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {
        listener?.error(SpeechRecognizer.ERROR_CLIENT)
    }
    override fun onCancel(listener: Callback?) {}
    override fun onStopListening(listener: Callback?) {}
}

/** Связь сервиса, сессии и приложения — всё в одном процессе. */
object Sheet {
    @Volatile var service: AssistService? = null
    val visible = MutableStateFlow(false)
    @Volatile var session: AssistSession? = null
    val prepareHooks = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    /** Свернуть приложение обратно в лист (Архитектура 3.1: finish + showSession). */
    fun show(args: Bundle = Bundle()) = service?.showSession(args, 0)
}

/**
 * Окно листа. Сессия сама — LifecycleOwner/SavedStateRegistryOwner/ViewModelStoreOwner, владельцы
 * ставятся на decorView окна (иначе Compose падает — пробник, Архитектура 3).
 */
class AssistSession(context: Context) : VoiceInteractionSession(context),
    LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {

    private val registry = LifecycleRegistry(this)
    private val savedState = SavedStateRegistryController.create(this)
    private val store = ViewModelStore()
    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry
    override val viewModelStore: ViewModelStore get() = store

    override fun onCreate() {
        super.onCreate()
        savedState.performAttach()
        savedState.performRestore(null)
        registry.currentState = Lifecycle.State.CREATED
        window.window?.let { w ->
            w.decorView.setViewTreeLifecycleOwner(this)
            w.decorView.setViewTreeSavedStateRegistryOwner(this)
            w.decorView.setViewTreeViewModelStoreOwner(this)
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        Sheet.session = this
    }

    override fun onCreateContentView(): View = ComposeView(context).apply {
        setViewTreeLifecycleOwner(this@AssistSession)
        setViewTreeSavedStateRegistryOwner(this@AssistSession)
        setViewTreeViewModelStoreOwner(this@AssistSession)
        setContent {
            SheetScreen(
                onClose = { hide() },
                onExpand = { expand() },
                onSettings = { openApp(settings = true) },
            )
        }
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        registry.currentState = Lifecycle.State.RESUMED
        Sheet.visible.value = true
    }

    override fun onHide() {
        // Запись останавливаем сами: система микрофон после hide() не выключает (Архитектура 3).
        SheetEvents.onHide?.invoke()
        if (registry.currentState.isAtLeast(Lifecycle.State.CREATED)) registry.currentState = Lifecycle.State.CREATED
        Sheet.visible.value = false
        super.onHide()
    }

    override fun onDestroy() {
        registry.currentState = Lifecycle.State.DESTROYED
        store.clear()
        if (Sheet.session === this) Sheet.session = null
        Sheet.visible.value = false
        super.onDestroy()
    }

    override fun onComputeInsets(outInsets: Insets) {
        super.onComputeInsets(outInsets)
        // Затемнение над листом перехватывает касания — лист не закрывается случайно.
        outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_FRAME
    }

    /** Развернуть лист в приложение: сначала запуск активности, потом hide (на скрытой сессии запуск не работает). */
    private fun expand() = openApp(settings = false)

    private fun openApp(settings: Boolean) {
        val intent = Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_FROM_SHEET, true)
            .putExtra(MainActivity.EXTRA_SETTINGS, settings)
        runCatching { startAssistantActivity(intent) }
        hide()
    }
}

object SheetEvents {
    @Volatile var onHide: (() -> Unit)? = null
}
