package ru.poneslas.vahter.assistant

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
import ru.poneslas.vahter.app.MainActivity

/** Сервис помощника. Живёт, пока Вахтёр выбран цифровым помощником. */
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
    /** Счётчик «начать запись без рук» — меняется при вызове кнопкой. */
    val autoListen = MutableStateFlow(0)
    /** Камера в листе открыта. */
    val camera = MutableStateFlow(false)
    /** Что делает «назад», пока открыта камера (ставит экран камеры). */
    @Volatile var cameraBack: (() -> Unit)? = null

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
            // Баги №16, №18: клавиатуру, полоску навигации, строку состояния и вырез считает только Compose (imePadding, safeDrawing).
            // Рамка сессии (fitsSystemWindows в разметке помощника) сдвигала лист сама и не на всю высоту клавиатуры — отключаем её ниже.
            androidx.core.view.WindowCompat.setDecorFitsSystemWindows(w, false)
            w.attributes = w.attributes.apply { layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS }
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING) // без сдвига окна: система его сдвигала (pan) на 688 px поверх imePadding
        }
        Sheet.session = this
        context.registerReceiver(screenOff, android.content.IntentFilter(Intent.ACTION_SCREEN_OFF), Context.RECEIVER_NOT_EXPORTED)
    }

    override fun onCreateContentView(): View = ComposeView(context).apply {
        // Рамки сессии над нашим видом не должны сами отступать от клавиатуры и полос — это делает Compose (баг №16).
        addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                var p = v.parent
                while (p is android.view.ViewGroup) {
                    p.fitsSystemWindows = false
                    p.setPadding(0, 0, 0, 0)
                    p.setOnApplyWindowInsetsListener { _, insets -> insets }
                    p = p.parent
                }
                v.requestApplyInsets()
            }
            override fun onViewDetachedFromWindow(v: View) {}
        })
        setViewTreeLifecycleOwner(this@AssistSession)
        setViewTreeSavedStateRegistryOwner(this@AssistSession)
        setViewTreeViewModelStoreOwner(this@AssistSession)
        setContent {
            SheetScreen(
                onClose = { hide() },
                onExpand = { expand() },
                onSettings = { openApp(settings = true) },
                onAddMode = { openApp(settings = true, newMode = true) },
                onEditMode = { id -> openApp(settings = false, editMode = id) },
                onNeedPerm = { perm -> openApp(settings = false, askPerm = perm) },
                session = this@AssistSession,
            )
        }
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        // Сессия сама выставляет режим клавиатуры при показе — перекрываем, иначе окно сдвигается поверх imePadding (баг №16).
        window.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
        val app = ru.poneslas.vahter.App.instance
        val conv = app.conversation
        val settings = conv.state.value.settings
        val src = args?.getString("src")
        // На заблокированном экране по умолчанию ничего не открывается: сначала разблокировка (Продукт 5.7).
        val locked = context.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked
        if (locked && settings.lockScope == ru.poneslas.vahter.core.LockScope.OFF) {
            runCatching { startAssistantActivity(Intent(context, UnlockActivity::class.java)) }
            hide()
            return
        }
        // Вызов помощника: боковая кнопка, жест, экран блокировки. Уведомление, приложение, камера, выбор фото — не вызов.
        val call = src == null || src == "unlock" || src == "keyguard"
        // Режим запоминается; после 30 минут без дела кнопка снова открывает Ассистента (Продукт 5.17).
        if (call && src != "unlock") {
            val idle = System.currentTimeMillis() - conv.state.value.saved.lastUse
            val back = when (settings.returnToAssistant) {
                ru.poneslas.vahter.core.ReturnToAssistant.ALWAYS -> true
                ru.poneslas.vahter.core.ReturnToAssistant.NEVER -> false
                ru.poneslas.vahter.core.ReturnToAssistant.MIN30 -> idle > 30 * 60_000
            }
            if (back) conv.selectMode(ru.poneslas.vahter.core.Modes.MAIN)
        }
        registry.currentState = Lifecycle.State.RESUMED
        Sheet.visible.value = true
        // Вызов кнопкой (не из уведомления и не из приложения): голосовой режим сразу слушает без рук.
        val cur = conv.state.value.current
        if (call) {
            // Главное действие режима при вызове: фото — сразу камера; голос — сразу слушает, если включено «Слушать сразу при вызове».
            if (cur.primary == ru.poneslas.vahter.core.Primary.PHOTO && cur.accepts.photo &&
                context.checkSelfPermission(android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED) Sheet.camera.value = true
            else if (cur.primary == ru.poneslas.vahter.core.Primary.VOICE && settings.listenOnCall) Sheet.autoListen.value++
        }
    }

    override fun onHide() {
        // Запись останавливаем сами: система микрофон после hide() не выключает (Архитектура 3).
        SheetEvents.onHide?.invoke()
        ru.poneslas.vahter.App.instance.conversation.touch() // «30 минут без дела» считаем от последнего закрытия листа
        Sheet.camera.value = false
        if (registry.currentState.isAtLeast(Lifecycle.State.CREATED)) registry.currentState = Lifecycle.State.CREATED
        Sheet.visible.value = false
        super.onHide()
    }

    override fun onDestroy() {
        runCatching { context.unregisterReceiver(screenOff) }
        registry.currentState = Lifecycle.State.DESTROYED
        store.clear()
        if (Sheet.session === this) Sheet.session = null
        Sheet.visible.value = false
        super.onDestroy()
    }

    /** Экран погас — лист закрываем, запись останавливаем (иначе микрофон пишет в кармане). */
    private val screenOff = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { hide() }
    }

    override fun onLockscreenShown() { hide() }

    /** Системный «назад»: в камере — как ✕ (заморожен кадр — переснять, иначе — в лист), иначе как обычно (баг №9). */
    override fun onBackPressed() {
        if (Sheet.camera.value) { Sheet.cameraBack?.invoke() ?: run { Sheet.camera.value = false } } else super.onBackPressed()
    }

    override fun onComputeInsets(outInsets: Insets) {
        super.onComputeInsets(outInsets)
        // Затемнение над листом перехватывает касания — лист не закрывается случайно.
        outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_FRAME
    }

    /** Развернуть лист в приложение: сначала запуск активности, потом hide (на скрытой сессии запуск не работает). */
    private fun expand() = openApp(settings = false)

    fun openApp(settings: Boolean, askPerm: String? = null, newMode: Boolean = false, editMode: String? = null) {
        val intent = Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_FROM_SHEET, true)
            .putExtra(MainActivity.EXTRA_SETTINGS, settings)
            .putExtra(MainActivity.EXTRA_NEW_MODE, newMode)
            .putExtra(MainActivity.EXTRA_ASK_PERM, askPerm)
            .putExtra(MainActivity.EXTRA_EDIT_MODE, editMode)
        runCatching { startAssistantActivity(intent) }
        hide()
    }
}

object SheetEvents {
    @Volatile var onHide: (() -> Unit)? = null
}
