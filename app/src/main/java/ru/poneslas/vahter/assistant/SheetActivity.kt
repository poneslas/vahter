package ru.poneslas.vahter.assistant

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
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

/**
 * Лист — прозрачный экран своей задачи, как AssistantOverlayActivity у Claude (Архитектура 3). Сессия помощника только
 * принимает вызов (кнопка, жест, плитка, уведомление) и сразу открывает этот экран через startAssistantActivity, а сама прячется.
 * Почему не окно сессии: оно не задача — свайп по полоске жестов листал приложение под листом, система прятала лист когда
 * вздумается, а системное окно разрешения уходило под лист (приходилось разворачивать приложение). Экран — задача: свайп
 * уводит с листа и возвращает на него, запрос разрешения ложится поверх листа.
 *
 * Когда закрывается: ✕, свайп вниз, «Назад», погас экран. Ушли в другое приложение — лист остаётся в своей задаче,
 * вернуться — свайпом или кнопкой (повторный вызов приходит в [onNewIntent]).
 */
class SheetActivity : ComponentActivity() {
    /** Запрошено закрытие: лист уезжает вниз и только потом [finish] (SheetScreen). */
    val closing = mutableStateOf(false)
    /** Растёт при каждом вызове (новый экран или повторный вызов): лист выезжает снизу. Возврат свайпом — без выезда. */
    val shows = androidx.compose.runtime.mutableIntStateOf(1)

    private var permThen: ((Boolean) -> Unit)? = null
    private val perms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        val then = permThen; permThen = null
        then?.invoke(r.isNotEmpty() && r.values.all { it })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        window.isNavigationBarContrastEnforced = false
        // Над блокировкой — только если сессия пустила (настройка «На заблокированном экране»); погас экран — лист закрыт.
        setShowWhenLocked(true)
        Sheet.activity = this
        registerReceiver(screenOff, android.content.IntentFilter(Intent.ACTION_SCREEN_OFF), RECEIVER_NOT_EXPORTED)
        // Системный «Назад»: в камере — как ✕ камеры (заморожен кадр — переснять), иначе — закрыть лист (баг №9).
        onBackPressedDispatcher.addCallback(this) {
            if (Sheet.camera.value) Sheet.cameraBack?.invoke() ?: run { Sheet.camera.value = false } else close()
        }
        setContent {
            SheetScreen(
                onClose = ::close,
                onExpand = { openApp(settings = false) },
                onSettings = { openApp(settings = true) },
                onAddMode = { openApp(settings = true, newMode = true) },
                onEditMode = { id -> openApp(settings = false, editMode = id) },
                onNeedPerm = { p -> askPermissions(listOf(p)) },
                host = this,
            )
        }
        noAnimation(open = true)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        closing.value = false
        if (!Sheet.visible.value) shows.intValue++
        noAnimation(open = true)
    }

    override fun onResume() {
        super.onResume()
        // Лист на экране — окно сессии больше не нужно (пустое, но держит ввод): прячем.
        window.decorView.post { Sheet.session?.hide() }
    }

    override fun onStart() { super.onStart(); Sheet.visible.value = true }

    override fun onStop() {
        super.onStop()
        // Ушли с листа (другое приложение, «Домой», приложение Вахтёра): то же, что было при скрытии окна сессии.
        SheetEvents.onHide?.invoke()                       // запись останавливаем сами
        App.instance.conversation.touch()                  // «30 минут без дела» — от последнего ухода с листа
        Sheet.camera.value = false
        Sheet.sideCue.value = false
        if (Sheet.firstClose) { Sheet.firstClose = false; android.widget.Toast.makeText(this, tr(R.string.sheet_first_close), android.widget.Toast.LENGTH_LONG).show() }
        Sheet.visible.value = false
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(screenOff) }
        if (Sheet.activity === this) Sheet.activity = null
        super.onDestroy()
    }

    /** Закрыть с анимацией: лист уезжает вниз (SheetScreen), потом [finishNow]. */
    fun close() { if (!isFinishing) closing.value = true }

    fun finishNow() { finish(); noAnimation(open = false) }

    private fun noAnimation(open: Boolean) =
        overrideActivityTransition(if (open) OVERRIDE_TRANSITION_OPEN else OVERRIDE_TRANSITION_CLOSE, 0, 0)

    /** Экран приложения поверх листа: лист закроется, когда приложение нарисует первый кадр (MainActivity). */
    fun openApp(settings: Boolean, askPerm: String? = null, newMode: Boolean = false, editMode: String? = null, brain: Boolean = false) {
        startActivity(Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(MainActivity.EXTRA_FROM_SHEET, true)
            .putExtra(MainActivity.EXTRA_BRAIN, brain)
            .putExtra(MainActivity.EXTRA_SETTINGS, settings)
            .putExtra(MainActivity.EXTRA_NEW_MODE, newMode)
            .putExtra(MainActivity.EXTRA_ASK_PERM, askPerm)
            .putExtra(MainActivity.EXTRA_EDIT_MODE, editMode))
        // Запасной: приложение не поднялось за 1,5 с — лист всё равно убрать.
        window.decorView.postDelayed({ if (!isFinishing) finishNow() }, 1500)
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
    private val screenOff = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { finishNow() }
    }
}
