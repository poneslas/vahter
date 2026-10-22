package ru.poneslas.vahter.assistant

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableStateOf
import ru.poneslas.vahter.App
import ru.poneslas.vahter.R
import ru.poneslas.vahter.app.MainActivity
import ru.poneslas.vahter.tr
import ru.poneslas.vahter.ui.VahterTheme
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.graphics.Color
import android.widget.Toast
import androidx.compose.runtime.mutableIntStateOf
import ru.poneslas.vahter.core.Modes

/**
 * Лист — прозрачный экран своей задачи, как AssistantOverlayActivity у Claude (Архитектура 3). Сессия помощника только
 * принимает вызов (кнопка, жест, плитка, уведомление) и сразу открывает этот экран через startAssistantActivity, а сама прячется.
 * Почему не окно сессии: оно не задача — свайп по полоске жестов листал приложение под листом, система прятала лист когда
 * вздумается, а системное окно разрешения уходило под лист (приходилось разворачивать приложение). Экран — задача: свайп
 * уводит с листа и возвращает на него, запрос разрешения ложится поверх листа.
 *
 * Когда закрывается: ✕, свайп вниз, «Назад», погас экран. Ушли в другое приложение — лист остаётся в своей задаче,
 * вернуться — свайпом или кнопкой (повторный вызов приходит в [onNewIntent]).
 *
 * С 0.9.0 лист и приложение — один экран: лист — это MainActivity, развёрнутый — показывает её [Screen] (разговор, настройки,
 * режим, мозг) в этом же окне. Разворот — лист дорастает до полного экрана и подменяется приложением; «Назад» с первой
 * страницы и ⌄ — обратно (лист садится с полного экрана). Развёрнутый — не над блокировкой и не закрывается, когда гаснет экран.
 */
class SheetActivity : MainActivity() {
    /** Развёрнут в приложение. */
    val expanded = mutableStateOf(false)
    /** Просьба развернуться: SheetScreen доращивает лист до полного экрана и зовёт [showApp]. */
    val expandAsk = mutableStateOf(false)
    /** Высота листа (px) — чтобы при сворачивании он сел с полного экрана сразу на свою высоту, без пустого кадра. */
    var sheetPx = 0
    private var pending: (() -> Unit)? = null
    override val appShown get() = expanded.value
    /** Запрошено закрытие: лист уезжает вниз и только потом [finish] (SheetScreen). */
    val closing = mutableStateOf(false)
    /** Растёт при каждом вызове (новый экран или повторный вызов): лист выезжает снизу. Возврат свайпом — без выезда. */
    val shows = mutableIntStateOf(1)

    private var permThen: ((Boolean) -> Unit)? = null
    private val perms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        val then = permThen; permThen = null
        then?.invoke(r.isNotEmpty() && r.values.all { it })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.isNavigationBarContrastEnforced = false
        // Над блокировкой — только если сессия пустила (настройка «На заблокированном экране»); погас экран — лист закрыт.
        setShowWhenLocked(true)
        Sheet.activity = this
        registerReceiver(screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF), RECEIVER_NOT_EXPORTED)
        // Системный «Назад»: в камере — как ✕ камеры (заморожен кадр — переснять), иначе — закрыть лист (баг №9).
        // Развёрнутый: страницы ловят «Назад» сами (Screen), сюда доходит только мастер — свернуть.
        onBackPressedDispatcher.addCallback(this) {
            if (expanded.value) collapse()
            else if (Sheet.camera.value) Sheet.cameraBack?.invoke() ?: run { Sheet.camera.value = false } else close()
        }
        noAnimation(open = true)
    }

    override fun start() {
        enableEdgeToEdge(SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT))
        fromSheet = true
        setContent {
            if (expanded.value) VahterTheme { Screen() } else SheetScreen(
                onClose = ::close,
                onExpand = { openApp(settings = false) },
                onSettings = { openApp(settings = true) },
                onAddMode = { openApp(settings = false, newMode = true) },
                onEditMode = { id -> openApp(settings = false, editMode = id) },
                onNeedPerm = { p -> askPermissions(listOf(p)) },
                host = this,
            )
        }
    }

    /** Намерения листа — только вызов; страницы открывает [openApp]. */
    override fun readIntent(i: Intent) {}

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        closing.value = false
        // Вызов помощника, когда развёрнут: на экране — свернуть; вернулись из другого приложения — сразу лист.
        if (expanded.value) { if (Sheet.visible.value) collapse() else fold() }
        if (!Sheet.visible.value) shows.intValue++
        noAnimation(open = true)
    }

    override fun onResume() {
        super.onResume()
        // Лист на экране — окно сессии больше не нужно (пустое, но держит ввод): прячем.
        window.decorView.post { Sheet.session?.hide() }
    }

    override fun onStart() { App.instance.conversation.freshen(); super.onStart(); Sheet.visible.value = true }

    override fun onStop() {
        super.onStop()
        // Ушли с листа (другое приложение, «Домой», приложение Вахтёра): то же, что было при скрытии окна сессии.
        SheetEvents.onHide?.invoke()                       // запись останавливаем сами
        App.instance.conversation.touch()                  // «30 минут без дела» — от последнего ухода с листа
        Sheet.camera.value = false
        Sheet.sideCue.value = false
        if (Sheet.firstClose && !expanded.value) { Sheet.firstClose = false; Toast.makeText(this, tr(R.string.sheet_first_close), Toast.LENGTH_LONG).show() }
        Sheet.visible.value = false
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(screenOff) }
        if (Sheet.activity === this) Sheet.activity = null
        super.onDestroy()
    }

    /** Закрыть с анимацией: лист уезжает вниз (SheetScreen), потом [finishNow]. */
    fun close() { if (expanded.value) finishNow() else if (!isFinishing) closing.value = true }

    fun finishNow() { finish(); noAnimation(open = false) }

    private fun noAnimation(open: Boolean) =
        overrideActivityTransition(if (open) OVERRIDE_TRANSITION_OPEN else OVERRIDE_TRANSITION_CLOSE, 0, 0)

    /**
     * Развернуть в приложение — в этом же окне. Страница: разговор; настройки (шестерёнка), режим (полоска промпта), мозг —
     * «Назад» с неё сворачивает обратно в лист. Над блокировкой — сначала разблокировать: приложение над ней не показываем.
     */
    fun openApp(settings: Boolean, askPerm: String? = null, newMode: Boolean = false, editMode: String? = null, brain: Boolean = false) {
        pending = {
            stack.clear(); stack.add("chat"); sheetReturn = null
            route(Intent()
                .putExtra(EXTRA_BRAIN, brain)
                .putExtra(EXTRA_SETTINGS, settings)
                .putExtra(EXTRA_NEW_MODE, newMode)
                .putExtra(EXTRA_EDIT_MODE, editMode))
            if (settings && !newMode) sheetReturn = "settings"
            if (newMode) sheetReturn = stack.last()
            if (askPerm != null) { askMicThenSheet = true; askMic() }
        }
        val kg = getSystemService(KeyguardManager::class.java)
        if (kg.isKeyguardLocked) kg.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() { expandAsk.value = true }
        }) else expandAsk.value = true
    }

    /** Лист дорос до полного экрана — подменить приложением (SheetScreen). */
    fun showApp() {
        expandAsk.value = false
        Sheet.camera.value = false
        pending?.invoke(); pending = null
        setShowWhenLocked(false)
        expanded.value = true
        App.instance.appVisible.value = true
    }

    /** Свернуть в лист: лист садится с полного экрана (SheetScreen, [Sheet.shrinkOnShow]). */
    override fun collapse() {
        if (!expanded.value) return
        Sheet.shrinkOnShow = true
        fold()
    }

    private fun fold() {
        expanded.value = false
        dialogsOff()
        setShowWhenLocked(true)
        App.instance.appVisible.value = false
        if (Sheet.cameraAfterShow) { Sheet.cameraAfterShow = false; Sheet.camera.value = true }
    }

    override fun leave() = collapse()

    /** Конец мастера в развёрнутом листе — сам лист с «Вам чего?» и подсказками (MainActivity.finishOnboarding). */
    override fun finishOnboarding() {
        App.instance.conversation.updateSettings { it.copy(onboarded = true) }
        App.instance.conversation.selectMode(Modes.MAIN)
        Sheet.firstSheet.value = true; Sheet.sideCue.value = true; Sheet.firstClose = true
        collapse()
    }

    /**
     * Доступы — прямо из листа: системное окно ложится поверх листа. Камеру разрешили — она сразу открывается.
     * Микрофон, на который система больше не спрашивает (отказали дважды), — в приложение: там наше окно ведёт в настройки.
     */
    fun askPermissions(list: List<String>) {
        val mic = Manifest.permission.RECORD_AUDIO
        val conv = App.instance.conversation
        if (mic in list && checkSelfPermission(mic) != PackageManager.PERMISSION_GRANTED &&
            conv.state.value.settings.micAsked && !shouldShowRequestPermissionRationale(mic)) {
            openApp(settings = false, askPerm = mic); return
        }
        if (mic in list) conv.updateSettings { it.copy(micAsked = true) }
        permThen = { ok -> if (ok && Manifest.permission.CAMERA in list) Sheet.camera.value = true }
        perms.launch(list.toTypedArray())
    }

    /** Экран погас — лист закрываем, запись останавливаем (иначе микрофон пишет в кармане; над блокировкой лист не остаётся). */
    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { if (!expanded.value) finishNow() }
    }
}
