package ru.poneslas.tasya.app

import android.app.role.RoleManager
import android.content.Intent
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.poneslas.tasya.App
import ru.poneslas.tasya.assistant.Sheet
import ru.poneslas.tasya.core.BrainDesc
import ru.poneslas.tasya.core.Brains
import ru.poneslas.tasya.core.LockScope
import ru.poneslas.tasya.core.ReturnToAssistant
import ru.poneslas.tasya.core.Policy
import ru.poneslas.tasya.ui.Composer
import ru.poneslas.tasya.ui.Feed
import ru.poneslas.tasya.ui.Ink
import ru.poneslas.tasya.ui.LiveLine
import ru.poneslas.tasya.ui.TasyaTheme

/** Приложение — тот же разговор, что в листе, на весь экран; плюс настройки (Продукт 5.1, 5.11). */
class MainActivity : ComponentActivity() {
    private var fromSheet by mutableStateOf(false)
    private val stack = mutableStateListOf("chat")
    private var dialog by mutableStateOf<ChoiceDialog?>(null)

    data class ChoiceDialog(val title: String, val options: List<Pair<String, String>>, val current: String, val onPick: (String) -> Unit)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        readIntent(intent)
        setContent { TasyaTheme { Screen() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        readIntent(intent)
    }

    private var askMicThenSheet = false

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray, deviceId: Int) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults, deviceId)
        if (requestCode == REQ_MIC && askMicThenSheet) { askMicThenSheet = false; collapse() }
    }

    override fun onResume() { super.onResume(); App.instance.appVisible.value = true }
    override fun onPause() { super.onPause(); App.instance.appVisible.value = false }

    private fun readIntent(i: Intent) {
        fromSheet = i.getBooleanExtra(EXTRA_FROM_SHEET, false)
        // Разрешение микрофона спрашиваем из приложения: из листа системное окно на S24 уходит под лист.
        i.getStringExtra(EXTRA_ASK_PERM)?.let { perm -> askMicThenSheet = true; requestPermissions(arrayOf(perm), REQ_MIC) }
        if (i.getBooleanExtra(EXTRA_SETTINGS, false) && stack.last() != "settings") stack.add("settings")
        if (i.getBooleanExtra(EXTRA_NEW_MODE, false)) { if (stack.last() != "settings") stack.add("settings"); stack.add("mode:" + App.instance.conversation.addMode()) }
    }

    fun go(page: String) { stack.add(page) }
    fun back() { if (stack.size > 1) stack.removeAt(stack.lastIndex) else finish() }
    fun choose(d: ChoiceDialog) { dialog = d }

    /** Свернуть обратно в лист: finish + showSession (Архитектура 3.1). */
    private fun collapse() { finish(); Sheet.show() }

    @Composable
    private fun Screen() {
        val app = App.instance
        val state by app.conversation.state.collectAsState()
        BackHandler(stack.size > 1) { back() }
        Box(Modifier.fillMaxSize().background(Ink.paper)) {
            Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
                if (!state.settings.onboarded) {
                    Onboarding(this@MainActivity) { app.conversation.updateSettings { it.copy(onboarded = true) } }
                } else {
                    val page = stack.last()
                    when {
                        page == "chat" -> Chat()
                        page == "settings" -> SettingsPage()
                        page.startsWith("brain:") -> BrainPage(page.removePrefix("brain:"))
                        page == "actions" -> ActionsPage()
                        page == "voice" -> VoicePage(onBack = ::back)
                        page.startsWith("mode:") -> ModePage(this@MainActivity, page.removePrefix("mode:"))
                    }
                }
            }
            dialog?.let { d -> Choice(d) { dialog = null } }
        }
    }

    @Composable
    private fun ColumnScope.Chat() {
        val app = App.instance
        val state by app.conversation.state.collectAsState()
        var live by remember { mutableStateOf<String?>(null) }
        val brain = Brains.byId(state.current.brain)
        val keyV by app.keyVersion.collectAsState()
        val connected = remember(keyV, brain.id) { app.hasKey(brain.id) }
        Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (fromSheet) IconBtn("⌄", "Свернуть в лист") { collapse() }
            Column(Modifier.padding(start = 10.dp).weight(1f)) {
                Text(state.current.title, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = Ink.ink)
                Text("${brain.title} · ${if (connected) "на связи" else "нет ключа"}", fontSize = 12.sp, color = if (connected) Ink.soft else Ink.danger)
            }
            IconBtn("⚙", "Настройки") { go("settings") }
        }
        if (!connected) Banner("Мозг не подключён — работают только команды телефона.", "Подключить") { go("brain:${brain.id}") }
        Feed(state, onAct = { app.conversation.onActButton(it) }, onCard = { c, a -> app.conversation.onCardButton(c, a) }, onCancelAttach = app.conversation::cancelAttach, modifier = Modifier.weight(1f).fillMaxWidth())
        LiveLine(live)
        Composer(
            state = state, recognizer = app.recognizer,
            onDraft = app.conversation::setDraft,
            onSend = { t, v -> app.conversation.send(t, v) },
            onSelectMode = app.conversation::selectMode,
            onAddMode = { go("mode:" + app.conversation.addMode()) },
            onLive = { live = it },
            onGallery = { ru.poneslas.tasya.media.PickerActivity.launchFromApp(this@MainActivity) },
        )
    }

    @Composable
    private fun SettingsPage() {
        val app = App.instance
        val state by app.conversation.state.collectAsState()
        val roleHeld = getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_ASSISTANT)
        TopBar("Настройки", onBack = ::back)
        ScrollPage {
            Section("Вызов")
            Item("Цифровой помощник", if (roleHeld) "Тася" else "Не выбран — боковая кнопка откроет другого помощника", warn = !roleHeld) {
                startActivity(Intent(AndroidSettings.ACTION_VOICE_INPUT_SETTINGS))
            }
            Item("Боковая кнопка", SideKey.describe(this@MainActivity)) { SideKey.open(this@MainActivity) }
            Section("Режимы")
            state.modes.forEach { m ->
                Item(m.title, when { m.id == "main" -> "Основной разговор · действия телефона"; !m.enabled -> "Выключен"
                    m.prompt.isBlank() -> "Промпт не задан"; else -> m.prompt.take(60) + "…" }) { go("mode:${m.id}") }
            }
            Item("Новый режим", "Свой промпт, вкладка в листе") { go("mode:" + app.conversation.addMode()) }
            Item("Возврат к Ассистенту", returnLabel(state.settings.returnToAssistant)) {
                dialog = ChoiceDialog("Возврат к Ассистенту", ReturnToAssistant.entries.map { it.name to returnLabel(it) }, state.settings.returnToAssistant.name) { v ->
                    app.conversation.updateSettings { it.copy(returnToAssistant = ReturnToAssistant.valueOf(v)) }
                }
            }
            Section("Голос")
            Item("Распознавание речи", app.voiceStatus()) { go("voice") }
            Section("Мозги")
            Brains.all.forEach { b ->
                val api = b.kind == BrainDesc.Kind.API
                val has = app.hasKey(b.id)
                Item(b.title, if (api) (if (has) "${b.model} · ключ есть" else "Нет ключа") else "Свой сервер: память, поиск, Telegram — следующий этап",
                    warn = api && !has) { if (api) go("brain:${b.id}") }
            }
            Section("Действия")
            Item("Действия на телефоне", "что выполняется сразу, а что — после вопроса") { go("actions") }
            Section("Экран блокировки")
            Item("На заблокированном экране", lockLabel(state.settings.lockScope)) {
                dialog = ChoiceDialog("На заблокированном экране", LockScope.entries.map { it.name to lockLabel(it) }, state.settings.lockScope.name) { v ->
                    app.conversation.updateSettings { it.copy(lockScope = LockScope.valueOf(v)) }
                }
            }
            Section("О программе")
            Item("Версия", packageManager.getPackageInfo(packageName, 0).versionName ?: "")
        }
    }

    private fun returnLabel(r: ReturnToAssistant) = when (r) {
        ReturnToAssistant.MIN30 -> "Через 30 минут без использования"
        ReturnToAssistant.ALWAYS -> "Всегда открывать Ассистента"
        ReturnToAssistant.NEVER -> "Оставаться в последнем режиме"
    }

    private fun lockLabel(s: LockScope) = when (s) {
        LockScope.OFF -> "Не открывать"
        LockScope.SAFE -> "Только будильник, таймер, фонарик"
        LockScope.ON -> "Открывать"
    }

    @Composable
    private fun BrainPage(id: String) {
        val b = Brains.byId(id)
        TopBar(b.title, "OpenAI-совместимый API", onBack = ::back)
        ScrollPage {
            Item("Адрес", b.url)
            Item("Модель", b.model)
            Section("Ключ")
            KeyEditor(b)
            Section("Умеет")
            Item("Фото, действия телефона", "Памяти между днями и поиска нет — это умеет Hermes")
            Text("История каждого режима хранится на телефоне и целиком уходит с каждой репликой; одинаковое начало провайдер берёт из кэша.",
                fontSize = 13.sp, color = Ink.soft, modifier = Modifier.padding(22.dp))
        }
    }

    @Composable
    private fun ActionsPage() {
        val app = App.instance
        val state by app.conversation.state.collectAsState()
        TopBar("Действия на телефоне", "что выполняется сразу, а что — после вопроса", onBack = ::back)
        ScrollPage {
            Section("Часы")
            listOf("alarm.set" to "Будильник", "timer.set" to "Таймер").forEach { (id, title) ->
                val p = state.settings.policy[id] ?: Policy.ASK
                Item(title, trailing = policyWord(p), trailingColor = when (p) { Policy.AUTO -> Ink.soft; Policy.ASK -> Ink.local; Policy.OFF -> Ink.danger }) {
                    dialog = ChoiceDialog(title, listOf("AUTO" to "Разрешить", "ASK" to "Спрашивать каждый раз", "OFF" to "Не разрешать"), p.name) { v ->
                        app.conversation.updateSettings { it.copy(policy = it.policy + (id to Policy.valueOf(v))) }
                    }
                }
            }
            Text("«Разрешено» — выполняется сразу, в разговоре видно, что сделано. «Спрашивать» — сначала показывается и ждёт «Да».",
                fontSize = 13.sp, color = Ink.soft, modifier = Modifier.padding(22.dp))
        }
    }

    private fun policyWord(p: Policy) = when (p) { Policy.AUTO -> "Разрешено"; Policy.ASK -> "Спрашивать"; Policy.OFF -> "Не разрешено" }

    @Composable
    private fun Banner(text: String, action: String, onClick: () -> Unit) {
        Row(Modifier.fillMaxWidth().padding(12.dp).clip(RoundedCornerShape(14.dp)).background(Ink.surf).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, fontSize = 14.sp, color = Ink.ink, modifier = Modifier.weight(1f))
            TextButton(action, onClick)
        }
    }

    @Composable
    private fun Choice(d: ChoiceDialog, close: () -> Unit) {
        Box(Modifier.fillMaxSize().background(Color(0x88000000)).clickable { close() }, contentAlignment = Alignment.Center) {
            Column(Modifier.padding(24.dp).clip(RoundedCornerShape(24.dp)).background(Ink.surf).clickable(enabled = false) {}.padding(20.dp)) {
                Text(d.title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Ink.ink, modifier = Modifier.padding(bottom = 8.dp))
                d.options.forEach { (v, label) ->
                    Row(Modifier.fillMaxWidth().clickable { d.onPick(v); close() }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(20.dp).clip(CircleShape).background(if (v == d.current) Ink.server else Ink.line))
                        Text(label, fontSize = 15.sp, color = Ink.ink, modifier = Modifier.padding(start = 14.dp))
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { TextButton("Отмена", close) }
            }
        }
    }

    companion object {
        const val EXTRA_FROM_SHEET = "from_sheet"
        const val EXTRA_SETTINGS = "settings"
        const val EXTRA_NEW_MODE = "new_mode"
        const val EXTRA_ASK_PERM = "ask_perm"
        const val REQ_MIC = 11
    }
}
