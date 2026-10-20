package ru.poneslas.vahter.probe.assist

import android.app.KeyguardManager
import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import android.service.voice.VoiceInteractionSession
import android.util.Log
import android.view.View
import android.view.ViewTreeObserver
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
import ru.poneslas.vahter.probe.App
import ru.poneslas.vahter.probe.TAG

/**
 * Окно листа. Сессия сама — LifecycleOwner/SavedStateRegistryOwner/ViewModelStoreOwner, чтобы
 * в окне TYPE_VOICE_INTERACTION жил Compose и CameraX (П3, П6.1).
 */
class ProbeSession(context: Context) : VoiceInteractionSession(context),
    LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {

    private val registry = LifecycleRegistry(this)
    private val savedState = SavedStateRegistryController.create(this)
    private val store = ViewModelStore()
    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry
    override val viewModelStore: ViewModelStore get() = store

    private var tCreate: Long? = null
    private var tContent: Long? = null
    private var shownBefore = false
    private var root: View? = null

    /** Пока > now, onCloseSystemDialogs не закрывает лист (П11.3: сами закрываем шторку). */
    var ignoreCloseDialogsUntil = 0L

    var lastArgs: Bundle = Bundle()
        private set

    override fun onCreate() {
        tCreate = SystemClock.uptimeMillis()
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
        Sheet.session.value = this
        Log.i(TAG, "session onCreate")
    }

    override fun onCreateContentView(): View {
        tContent = SystemClock.uptimeMillis()
        val v = ComposeView(context).apply {
            setViewTreeLifecycleOwner(this@ProbeSession)
            setViewTreeSavedStateRegistryOwner(this@ProbeSession)
            setViewTreeViewModelStoreOwner(this@ProbeSession)
            setContent { SheetContent(this@ProbeSession) }
        }
        root = v
        Log.i(TAG, "session onCreateContentView")
        return v
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        val tShow = SystemClock.uptimeMillis()
        super.onShow(args, showFlags)
        lastArgs = args ?: Bundle()
        val tPrepare = Sheet.pendingPrepare
        Sheet.pendingPrepare = null
        val tCall = Sheet.pendingCall
        Sheet.pendingCall = null
        val fresh = App.createdAt != Sheet.lastOpenProcessCreatedAt
        Sheet.lastOpenProcessCreatedAt = App.createdAt
        val newSession = !shownBefore
        shownBefore = true
        val locked = context.getSystemService(KeyguardManager::class.java).isKeyguardLocked
        registry.currentState = Lifecycle.State.RESUMED
        Sheet.visible.value = true
        Log.i(TAG, "session onShow invocation_time_ms=${lastArgs.getLong("invocation_time_ms", -1)} TIME=${lastArgs.getLong("android.intent.extra.TIME", -1)} uptime=$tShow elapsed=${SystemClock.elapsedRealtime()} wall=${System.currentTimeMillis()}")
        Log.i(TAG, "session onShow flags=0x${Integer.toHexString(showFlags)} [${showFlags(showFlags)}] args=${lastArgs.keySet()} locked=$locked")

        val view = root ?: return
        val listener = object : ViewTreeObserver.OnDrawListener {
            var done = false
            override fun onDraw() {
                if (done) return
                done = true
                val tFrame = SystemClock.uptimeMillis()
                view.post { view.viewTreeObserver.removeOnDrawListener(this) }
                val rec = OpenRecord(
                    seq = ++Sheet.seq, flags = showFlags, args = lastArgs,
                    tPrepare = tPrepare, tCreate = tCreate.takeIf { newSession }, tContent = tContent.takeIf { newSession },
                    tShow = tShow, tFrame = tFrame, freshProcess = fresh, newSession = newSession, locked = locked,
                    tCall = tCall, tInvoke = invokeTime(lastArgs, tShow),
                )
                OpenLog.add(view.context, rec)
                // Маркер для scripts/series.sh: ищет строку «FRAME» в logcat.
                Log.i(TAG, "FRAME ${rec.describe()}")
                Sheet.last.value = rec
                Sheet.opens.tryEmit(rec)
            }
        }
        view.viewTreeObserver.addOnDrawListener(listener)
        view.invalidate()
    }

    override fun onHide() {
        Log.i(TAG, "session onHide")
        if (registry.currentState.isAtLeast(Lifecycle.State.CREATED)) registry.currentState = Lifecycle.State.CREATED
        Sheet.visible.value = false
        Sheet.hides.tryEmit(SystemClock.uptimeMillis())
        super.onHide()
    }

    override fun onDestroy() {
        Log.i(TAG, "session onDestroy")
        registry.currentState = Lifecycle.State.DESTROYED
        store.clear()
        if (Sheet.session.value === this) Sheet.session.value = null
        Sheet.visible.value = false
        super.onDestroy()
    }

    override fun onLockscreenShown() {
        Log.i(TAG, "session onLockscreenShown")
        LockEvents.lockscreenShown.tryEmit(SystemClock.uptimeMillis())
        super.onLockscreenShown()
    }

    override fun onCloseSystemDialogs() {
        if (SystemClock.uptimeMillis() < ignoreCloseDialogsUntil) {
            Log.i(TAG, "session onCloseSystemDialogs — пропущено")
            return
        }
        Log.i(TAG, "session onCloseSystemDialogs")
        super.onCloseSystemDialogs()
    }

    override fun onTaskStarted(intent: android.content.Intent?, taskId: Int) {
        Log.i(TAG, "session onTaskStarted ${intent?.component?.shortClassName} task=$taskId")
    }

    override fun onTaskFinished(intent: android.content.Intent?, taskId: Int) {
        Log.i(TAG, "session onTaskFinished ${intent?.component?.shortClassName} task=$taskId")
    }

    override fun onComputeInsets(outInsets: Insets) {
        super.onComputeInsets(outInsets)
        // Всё окно принимает касания: затемнение над листом перехватывает нажатия (П3.5).
        outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_FRAME
    }
}

/**
 * Система кладёт в args время нажатия: android.intent.extra.TIME и invocation_time_ms.
 * Часы не задокументированы [?]: на эмуляторе это uptime. Если значение «в будущем» по uptime —
 * считаем, что это elapsedRealtime, и переводим. Сырые значения пишутся в лог onShow.
 */
private fun invokeTime(args: Bundle, tShow: Long): Long? {
    val raw = args.getLong("android.intent.extra.TIME", -1).takeIf { it > 0 }
        ?: args.getLong("invocation_time_ms", -1).takeIf { it > 0 }
        ?: return null
    if (raw <= tShow) return raw
    val asUptime = raw - (SystemClock.elapsedRealtime() - SystemClock.uptimeMillis())
    return asUptime.takeIf { it in 1..tShow }
}

object LockEvents {
    val lockscreenShown = kotlinx.coroutines.flow.MutableSharedFlow<Long>(extraBufferCapacity = 8)
}
