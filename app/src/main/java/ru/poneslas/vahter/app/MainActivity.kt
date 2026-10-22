package ru.poneslas.vahter.app

import ru.poneslas.vahter.R
import ru.poneslas.vahter.tr
import android.content.Intent
import ru.poneslas.vahter.brain.HermesBrain
import ru.poneslas.vahter.brain.HermesLink
import ru.poneslas.vahter.core.Brains
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import ru.poneslas.vahter.App
import ru.poneslas.vahter.assistant.Sheet
import ru.poneslas.vahter.ui.Ink
import ru.poneslas.vahter.ui.Ic
import ru.poneslas.vahter.core.Modes
import ru.poneslas.vahter.ui.VahterTheme
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/**
 * Приложение — тот же разговор, что в листе, на весь экран; плюс настройки (Продукт 5.1, 5.11).
 * С 0.9.0 лист ([ru.poneslas.vahter.assistant.SheetActivity]) — тоже MainActivity: развёрнутый лист показывает этот же [Screen],
 * «Назад» и ⌄ сворачивают его обратно в лист в том же окне. Сама MainActivity — вход с иконки, из уведомлений и ссылок.
 */
open class MainActivity : ComponentActivity() {
    var fromSheet by mutableStateOf(false)
        protected set
    protected val stack = mutableStateListOf("chat")
    private var dialog by mutableStateOf<ChoiceDialog?>(null)

    data class ChoiceDialog(val title: String, val options: List<Pair<String, String>>, val current: String, val onPick: (String) -> Unit)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        start()
    }

    /** Окно и содержимое; лист ставит своё (SheetActivity). */
    protected open fun start() {
        enableEdgeToEdge()
        readIntent(intent)
        setContent { VahterTheme { Screen() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        readIntent(intent)
    }

    protected var askMicThenSheet = false

    /** Наше окно-лист (не системное): объяснение перед повторным запросом микрофона или переход в настройки. */
    data class PermSheet(val title: String, val text: String, val go: String, val icon: Int = Ic.mic, val later: String? = null, val onLater: (() -> Unit)? = null, val onGo: () -> Unit)
    private var permSheet by mutableStateOf<PermSheet?>(null)
    fun showSheet(s: PermSheet) { permSheet = s }

    /**
     * Микрофон, как в Android 11+ (Продукт 5.13): первый раз — системное окно; после одного отказа — наше объяснение
     * и снова системное; после второго система больше не спрашивает — наше окно ведёт в настройки разрешений.
     */
    fun askMic() {
        val mic = android.Manifest.permission.RECORD_AUDIO
        if (checkSelfPermission(mic) == android.content.pm.PackageManager.PERMISSION_GRANTED) { if (askMicThenSheet) { askMicThenSheet = false; collapse() }; return }
        when {
            shouldShowRequestPermissionRationale(mic) -> permSheet = PermSheet(tr(R.string.mic_why_h), tr(R.string.mic_why_p), tr(R.string.mic_why_go)) { requestMic() }
            App.instance.conversation.state.value.settings.micAsked -> permSheet = PermSheet(tr(R.string.mic_off_h), tr(R.string.mic_off_p), tr(R.string.mic_off_go)) {
                askMicThenSheet = false
                startActivity(Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", packageName, null)))
            }
            else -> requestMic()
        }
    }

    private fun requestMic() {
        App.instance.conversation.updateSettings { it.copy(micAsked = true) }
        requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), REQ_MIC)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray, deviceId: Int) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults, deviceId)
        resumed.intValue++   // ответ на системное окно (микрофон, уведомления) — перечитать состояние строк
        if (requestCode == REQ_MIC && askMicThenSheet) {
            // камеру просили из листа и разрешили — лист вернётся уже с камерой
            val cam = permissions.indexOf(android.Manifest.permission.CAMERA)
            if (cam >= 0 && grantResults.getOrNull(cam) == android.content.pm.PackageManager.PERMISSION_GRANTED) Sheet.cameraAfterShow = true
            askMicThenSheet = false; collapse()
        }
    }

    /** Растёт при каждом возврате в приложение: статусы системных настроек перечитываются от него (баг №1). */
    val resumed = mutableIntStateOf(0)

    /** Приложение на экране (для уведомлений и дочитывания хода); у листа — только развёрнутого. */
    protected open val appShown get() = true
    override fun onResume() { App.instance.conversation.freshen(); ru.poneslas.vahter.update.Updates.onAppResume(this); super.onResume(); App.instance.appVisible.value = appShown; resumed.intValue++ }
    override fun onPause() { super.onPause(); App.instance.appVisible.value = false }

    protected open fun readIntent(i: Intent) {
        sheetReturn = null
        // Разрешение микрофона спрашиваем из приложения: из листа системное окно на S24 уходит под лист.
        // Из листа — после ответа вернуть лист; из приложения — остаться.
        i.getStringExtra(EXTRA_ASK_PERM)?.let { perms -> askMicThenSheet = fromSheet; if (perms == android.Manifest.permission.RECORD_AUDIO) askMic() else requestPermissions(perms.split(",").toTypedArray(), REQ_MIC) }
        route(i)
    }

    /** Какую страницу открыть по намерению (стопка страниц). */
    protected fun route(i: Intent) {
        // Шестерёнка в системном «Цифровом помощнике» открывает приложение через псевдоним SettingsEntry — сразу настройки.
        if (i.component?.className?.endsWith(".SettingsEntry") == true) { stack.clear(); stack.add("chat"); stack.add("settings") }
        if (i.getBooleanExtra(EXTRA_SETTINGS, false) && stack.last() != "settings") stack.add("settings")
        i.getStringExtra(EXTRA_PAGE)?.let { p -> stack.clear(); stack.add("chat"); if (p != "chat") { if (p != "settings" && !p.startsWith("onb:")) stack.add("settings"); stack.add(p) } }
        // Полоска промпта в листе → страница режима; «Назад» с неё возвращает лист, а не настройки.
        i.getStringExtra(EXTRA_EDIT_MODE)?.let { id -> stack.clear(); stack.add("chat"); stack.add("mode:$id"); sheetReturn = "mode:$id" }
        // Строка «Мозг не подключён · Подключить» или карточка «Наверх не дозвониться» в листе → экран мозга; «Назад» — снова лист.
        if (i.getBooleanExtra(EXTRA_BRAIN, false)) { val p = brainPage(); stack.clear(); stack.add("chat"); stack.add(p); sheetReturn = p }
        // Ссылка-приглашение Hermes (vahter://connect?url=…&token=…): страница Hermes подключается сама.
        if (i.action == Intent.ACTION_VIEW && i.data?.scheme == "vahter") {
            invite = HermesBrain.parseInvite(i.dataString.orEmpty())
            stack.clear(); stack.add("chat"); stack.add("settings"); stack.add("hermes")
        }
        if (i.getBooleanExtra(EXTRA_NEW_MODE, false)) { if (stack.last() != "settings") stack.add("settings"); stack.add("mode:" + App.instance.conversation.addMode()) }
    }

    fun go(page: String) { stack.add(page) }
    /** Приглашение из ссылки — страница Hermes забирает его один раз. */
    var invite by mutableStateOf<HermesLink?>(null)
    /** Страница, с которой «Назад» сворачивает обратно в лист (пришли на неё из листа). */
    protected var sheetReturn: String? by mutableStateOf(null)
    fun back() {
        if (sheetReturn != null && stack.last() == sheetReturn) { sheetReturn = null; collapse(); return }
        if (stack.size > 1) stack.removeAt(stack.lastIndex) else leave()
    }
    /** «Назад» с первой страницы: приложение закрывается, лист сворачивается. */
    protected open fun leave() = finish()
    fun choose(d: ChoiceDialog) { dialog = d }
    fun hideSheet() { permSheet = null }
    /** Системные настройки уведомлений приложения. */
    fun notifSettings() = startActivity(Intent(AndroidSettings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(AndroidSettings.EXTRA_APP_PACKAGE, packageName))
    /** Свернули в лист — открытые окна выбора и разрешений не оставлять. */
    protected fun dialogsOff() { dialog = null; permSheet = null }

    /** Свернуть обратно в лист: finish + showSession (Архитектура 3.1). */
    open fun collapse() { Sheet.shrinkOnShow = true; finish(); Sheet.show(Bundle().apply { putString("src", "app") }) }

    /**
     * Финал мастера — лист, а не приложение (Продукт 5.13): он интереснее полноэкранного разговора, и так Вахтёр выглядит каждый день.
     * Пустой лист с «Вам чего?» и чипами, один раз — подсказка у боковой кнопки, при первом закрытии — «Вахтёр на месте».
     * Без роли помощника листа нет (showSession может только активный помощник) — остаётся разговор в приложении и баннер.
     */
    protected open fun finishOnboarding() {
        App.instance.conversation.updateSettings { it.copy(onboarded = true) }
        if (Sheet.service == null) return
        App.instance.conversation.selectMode(Modes.MAIN)
        Sheet.firstSheet.value = true; Sheet.sideCue.value = true; Sheet.firstClose = true
        finish(); Sheet.show(Bundle().apply { putString("src", "app") })
    }

    @Composable
    protected fun Screen() {
        val app = App.instance
        val state by app.conversation.state.collectAsState()
        // Пришли с листа: системный «Назад» на первой странице — свернуться обратно в лист, а не закрыть приложение.
        BackHandler(stack.size > 1 || (fromSheet && state.settings.onboarded)) { if (stack.size > 1) back() else collapse() }
        Box(Modifier.fillMaxSize().background(Ink.paper)) {
            Column(Modifier.fillMaxSize().imePadding()) {
                val page = stack.last()
                // Пришли из листа на экран (мозг, режим) до конца мастера — показываем экран, «Назад» вернёт лист.
                if ((!state.settings.onboarded && sheetReturn == null) || page.startsWith("onb:")) {
                    Onboarding(this@MainActivity, page.removePrefix("onb:").toIntOrNull() ?: 0) {
                        if (page.startsWith("onb:")) { app.conversation.updateSettings { it.copy(onboarded = true) }; back() } else finishOnboarding()
                    }
                } else {
                    // Каждая страница стопки помнит своё состояние (прокрутку): «Назад» в настройки — туда же, где был, а не в начало.
                    // Ушла из стопки — забываем, чтобы при новом заходе страница открылась сначала.
                    val holder = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
                    val kept = androidx.compose.runtime.remember { mutableSetOf<String>() }
                    androidx.compose.runtime.SideEffect { (kept - stack.toSet()).forEach { holder.removeState(it); kept.remove(it) }; kept += stack }
                    holder.SaveableStateProvider(page) { when {
                        page == "chat" -> Chat(this@MainActivity)
                        page == "settings" -> SettingsPage(this@MainActivity)
                        page.startsWith("brain:") -> BrainPage(this@MainActivity, page.removePrefix("brain:"))
                        page == "actions" -> ActionsPage(this@MainActivity)
                        page == "hermes" -> HermesPage(this@MainActivity)
                        page == "voice" -> VoicePage(onBack = ::back)
                        page == "call" -> CallPage(this@MainActivity)
                        page == "about" -> AboutPage(this@MainActivity)
                        page.startsWith("mode:") -> ModePage(this@MainActivity, page.removePrefix("mode:"))
                    } }
                }
            }
            dialog?.let { d -> Choice(d) { dialog = null } }
            permSheet?.let { d -> PermSheetUi(this@MainActivity, d) { permSheet = null; if (askMicThenSheet) { askMicThenSheet = false; collapse() } } }
        }
    }

    companion object {
        const val EXTRA_SETTINGS = "settings"
        const val EXTRA_PAGE = "page"
        const val EXTRA_EDIT_MODE = "editMode"
        const val EXTRA_NEW_MODE = "new_mode"
        const val EXTRA_BRAIN = "brain"
        /** Страница мозга текущего режима: DeepSeek — ключ, Hermes — подключение. */
        fun brainPage(): String = if (App.instance.conversation.state.value.current.brain == Brains.hermes.id) "hermes" else "brain:deepseek"
        const val EXTRA_ASK_PERM = "ask_perm"
        const val REQ_MIC = 11
    }
}
