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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
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
import ru.poneslas.tasya.ui.Ic
import ru.poneslas.tasya.core.Modes
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
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

    /** Растёт при каждом возврате в приложение: статусы системных настроек перечитываются от него (баг №1). */
    val resumed = androidx.compose.runtime.mutableIntStateOf(0)

    override fun onResume() { super.onResume(); App.instance.appVisible.value = true; resumed.intValue++ }
    override fun onPause() { super.onPause(); App.instance.appVisible.value = false }

    private fun readIntent(i: Intent) {
        fromSheet = i.getBooleanExtra(EXTRA_FROM_SHEET, false)
        // Разрешение микрофона спрашиваем из приложения: из листа системное окно на S24 уходит под лист.
        // Из листа — после ответа вернуть лист; из приложения — остаться.
        i.getStringExtra(EXTRA_ASK_PERM)?.let { perms -> askMicThenSheet = fromSheet; requestPermissions(perms.split(",").toTypedArray(), REQ_MIC) }
        if (i.getBooleanExtra(EXTRA_SETTINGS, false) && stack.last() != "settings") stack.add("settings")
        i.getStringExtra(EXTRA_PAGE)?.let { p -> stack.clear(); stack.add("chat"); if (p != "chat") { if (p != "settings" && !p.startsWith("onb:")) stack.add("settings"); stack.add(p) } }
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
            Column(Modifier.fillMaxSize().imePadding()) {
                val page = stack.last()
                if (!state.settings.onboarded || page.startsWith("onb:")) {
                    Onboarding(this@MainActivity, page.removePrefix("onb:").toIntOrNull() ?: 0) {
                        app.conversation.updateSettings { it.copy(onboarded = true) }; if (page.startsWith("onb:")) back()
                    }
                } else {
                    when {
                        page == "chat" -> Chat()
                        page == "settings" -> SettingsPage()
                        page.startsWith("brain:") -> BrainPage(page.removePrefix("brain:"))
                        page == "actions" -> ActionsPage()
                        page == "journal" -> JournalPage()
                        page == "hermes" -> HermesPage()
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
        // chat(): [свернуть] заголовок режима, точка связи · «DeepSeek · на связи», шестерёнка
        TopBar(state.current.title, "${brain.title} · ${if (connected) "на связи" else "нет ключа · только команды телефона"}",
            onBack = if (fromSheet) ({ collapse() }) else null, backIcon = Ic.down, dot = if (connected) Ink.server else Ink.local) {
            IconBtn(Ic.gear, "Настройки") { go("settings") }
        }
        if (!connected) Banner("Мозг не подключён — работают только команды телефона.",
            actions = listOf("Подключить" to { go("brain:${brain.id}") }))
        Feed(state, onAct = { a, undo -> app.conversation.onActButton(a, undo) }, onCard = { c, a -> app.conversation.onCardButton(c, a) }, onCancelAttach = app.conversation::cancelAttach,
            modifier = Modifier.weight(1f).fillMaxWidth(), inSheet = false, onChip = { app.conversation.send(it, "клавиатура") })
        LiveLine(live)
        Composer(
            state = state, voice = app.voice,
            onDraft = app.conversation::setDraft,
            onSend = { t, v -> app.conversation.send(t, v) },
            onSelectMode = app.conversation::selectMode,
            onAddMode = { go("mode:" + app.conversation.addMode()) },
            onLive = { live = it },
            onNeedMic = { requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 1) },
            onGallery = { ru.poneslas.tasya.media.PickerActivity.launchFromApp(this@MainActivity) },
        )
        androidx.compose.foundation.layout.Spacer(Modifier.navigationBarsPadding())
    }

    @Composable
    private fun SettingsPage() {
        val app = App.instance
        val state by app.conversation.state.collectAsState()
        @Suppress("UNUSED_VARIABLE") val r = resumed.intValue // баг №1: перечитать после возврата из системных настроек
        val roleHeld = getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_ASSISTANT)
        val mic = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val notif = checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val installed by app.voice.installed.collectAsState()
        val dl by ru.poneslas.tasya.pisar.model.ModelDownload.state.collectAsState()
        TopBar("Настройки", onBack = ::back)
        ScrollPage {
            Section("Вызов")
            Item("Цифровой помощник", if (roleHeld) "Тася" else "Не выбран — боковая кнопка откроет другого помощника", warn = !roleHeld, icon = Ic.spark) {
                startActivity(Intent(AndroidSettings.ACTION_VOICE_INPUT_SETTINGS))
            }
            Item("Боковая кнопка", SideKey.describe(this@MainActivity), icon = Ic.btn) { SideKey.open(this@MainActivity) }
            Item("Возвращаться к Ассистенту", returnLabel(state.settings.returnToAssistant), icon = Ic.hist) {
                dialog = ChoiceDialog("Возвращаться к Ассистенту", ReturnToAssistant.entries.map { it.name to returnLabel(it) }, state.settings.returnToAssistant.name) { v ->
                    app.conversation.updateSettings { it.copy(returnToAssistant = ReturnToAssistant.valueOf(v)) }
                }
            }
            Section("Режимы")
            state.modes.forEach { m ->
                if (m.id == Modes.MAIN) Item(m.title, modeSummary(m), icon = Ic.mode(m.icon)) { go("mode:${m.id}") }
                else ItemWithSwitch(m.title, modeSummary(m), Ic.mode(m.icon), m.enabled,
                    onToggle = { app.conversation.editMode(m.id) { it.copy(enabled = !it.enabled) } }) { go("mode:${m.id}") }
            }
            Item("Новый режим", "Название, промпт, что принимает", icon = Ic.plus) { go("mode:" + app.conversation.addMode()) }
            Section("Голос")
            val pct = (dl as? ru.poneslas.tasya.pisar.model.ModelDownload.State.Running)?.percent
            Item("Распознавание речи", when { installed -> "На телефоне · 225 МБ"; pct != null -> "Качается: $pct %"; else -> "Не скачано · 225 МБ" },
                warn = !installed && pct == null, icon = Ic.wave) { go("voice") }
            Item("Микрофон", if (mic) "Только пока идёт запись" else "Спросим, когда понадобится", icon = Ic.mic) {
                if (!mic) requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 1)
            }
            Section("Мозги")
            Brains.all.forEach { b ->
                val api = b.kind == BrainDesc.Kind.API
                val has = app.hasKey(b.id)
                Item(b.title, if (api) (if (has) "${b.model} · ключ есть" else "Нет ключа") else "Свой сервер: память, поиск, Telegram · скоро",
                    warn = api && !has, icon = if (api) Ic.spark else Ic.srv) { go(if (api) "brain:${b.id}" else "hermes") }
            }
            Section("Уведомления")
            Item("Уведомления", if (notif) "Когда пришёл ответ, а лист закрыт" else "Выключены", icon = Ic.bell, end = { Sw(notif) }) {
                if (!notif) requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 2)
                else startActivity(Intent(AndroidSettings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(AndroidSettings.EXTRA_APP_PACKAGE, packageName))
            }
            Section("Действия")
            val R = ru.poneslas.tasya.actions.Registry
            val pol = R.all.map { R.policy(state.settings, it.id) }
            val blockedN = R.all.count { R.policy(state.settings, it.id) != Policy.OFF && it.special != null && R.missing(this@MainActivity, it).isNotEmpty() }
            Item("Действия на телефоне", if (blockedN > 0) "Нет доступа — $blockedN" else "Разрешено ${pol.count { it == Policy.AUTO }}, спрашивать ${pol.count { it == Policy.ASK }}, не разрешено ${pol.count { it == Policy.OFF }}",
                warn = blockedN > 0, icon = Ic.shield) { go("actions") }
            Item("Журнал действий", "Сегодня — ${state.saved.journal.count { android.text.format.DateUtils.isToday(it.at) }}", icon = Ic.list) { go("journal") }
            Divider()
            Item("О программе", "Версия ${packageManager.getPackageInfo(packageName, 0).versionName ?: ""}", icon = Ic.info)
        }
    }

    /** model.js, modeSummary(). */
    private fun modeSummary(m: ru.poneslas.tasya.core.Mode): String {
        if (m.id == Modes.MAIN) return "Основной разговор · все действия телефона"
        val ins = listOfNotNull("фото".takeIf { m.accepts.photo }, "файл".takeIf { m.accepts.file })
        return listOfNotNull(if (m.builtin) null else "Свой", if (m.primary == ru.poneslas.tasya.core.Primary.PHOTO) "сразу камера" else "голос",
            ins.joinToString(", ").ifEmpty { null }).joinToString(" · ")
    }

    private fun returnLabel(r: ReturnToAssistant) = when (r) {
        ReturnToAssistant.MIN30 -> "Через 30 минут без дела"
        ReturnToAssistant.NEVER -> "Никогда — открывать последний режим"
        ReturnToAssistant.ALWAYS -> "Всегда — боковая кнопка открывает Ассистента"
    }

    private fun lockLabel(s: LockScope) = when (s) {
        LockScope.OFF -> "Не открывать"
        LockScope.SAFE -> "Только будильник, таймер, фонарик"
        LockScope.ON -> "Открывать"
    }

    /** brainPage(): карточка «Адрес / Модель», ключ, «Умеет», пояснение. */
    @Composable
    private fun BrainPage(id: String) {
        val app = App.instance
        val b = Brains.byId(id)
        val state by app.conversation.state.collectAsState()
        TopBar(b.title, "OpenAI-совместимый API", onBack = ::back)
        FormPage {
            SetCard { Item("Адрес", b.url, icon = Ic.srv); Item("Модель", b.model, icon = Ic.spark) }
            KeyEditor(b)
            Column {
                Text("Умеет", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Ink.serverD, modifier = Modifier.padding(vertical = 4.dp))
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    MChip("Фото", b.vision); MChip("Действия телефона", b.tools); MChip("Память", b.memory); MChip("Сервер, поиск", b.server)
                }
            }
            val users = state.modes.filter { it.brain == b.id }.joinToString(", ") { it.title }.ifEmpty { "нет" }
            MNote("Режимы на этом мозге: $users. История каждого хранится на телефоне и целиком уходит с каждой репликой; одинаковое начало разговора провайдер берёт из кэша — дешевле и быстрее.")
        }
    }

    /** toolsPage(): баннер «нет доступа», группы с политикой справа и состоянием доступа, экран блокировки, журнал. */
    @Composable
    private fun ActionsPage() {
        val app = App.instance
        val state by app.conversation.state.collectAsState()
        @Suppress("UNUSED_VARIABLE") val r = resumed.intValue // перечитать доступы после системных окон
        val R = ru.poneslas.tasya.actions.Registry
        TopBar("Действия на телефоне", "что выполняется сразу, а что — после вопроса", onBack = ::back)
        ScrollPage {
            val blocked = R.all.filter { R.policy(state.settings, it.id) != Policy.OFF && it.special != null && R.missing(this@MainActivity, it).isNotEmpty() }
            if (blocked.isNotEmpty()) {
                val t = blocked.first()
                Banner("«${t.title}» не работает: нет доступа ${R.specialTitle(t.special!!)}. Его включают один раз в настройках телефона.",
                    actions = listOf("Исправить" to { ru.poneslas.tasya.actions.Phone.openDndAccess(this@MainActivity); Unit }))
            }
            R.groups.forEach { g ->
                Section(g)
                R.all.filter { it.group == g }.forEach { t ->
                    val p = R.policy(state.settings, t.id)
                    val miss = R.missing(this@MainActivity, t)
                    val sub = when {
                        p == Policy.OFF || miss.isEmpty() -> null
                        t.special != null -> "нет доступа: ${miss.joinToString(", ")}"
                        else -> "доступ «${miss.joinToString(", ")}» спросим при первом разе"
                    }
                    Item(t.title, sub, trailing = policyWord(p), trailingColor = when (p) { Policy.AUTO -> Ink.soft; Policy.ASK -> Ink.local; Policy.OFF -> Ink.danger },
                        minHeight = 52.dp) {
                        val opts = (if (t.alwaysAsk) emptyList() else listOf("AUTO" to "Разрешено")) + listOf("ASK" to "Спрашивать", "OFF" to "Не разрешено")
                        dialog = ChoiceDialog(t.title, opts, p.name) { v ->
                            app.conversation.updateSettings { it.copy(policy = it.policy + (t.id to Policy.valueOf(v))) }
                        }
                    }
                }
            }
            Section("Экран блокировки")
            Item("На заблокированном экране", lockLabel(state.settings.lockScope), icon = Ic.lock) {
                dialog = ChoiceDialog("На заблокированном экране", LockScope.entries.map { it.name to lockLabel(it) }, state.settings.lockScope.name) { v ->
                    app.conversation.updateSettings { it.copy(lockScope = LockScope.valueOf(v)) }
                }
            }
            Item("Журнал действий", "Сегодня — ${state.saved.journal.count { android.text.format.DateUtils.isToday(it.at) }}", icon = Ic.list) { go("journal") }
            MNote("«Разрешено» — выполняется сразу, в разговоре видно, что сделано. «Спрашивать» — сначала показывается и ждёт «Да». Сообщения только готовятся — отправляете вы.",
                Modifier.padding(start = 22.dp, end = 22.dp, top = 4.dp, bottom = 20.dp))
        }
    }

    /** Hermes: ещё не подключается — честно говорим и описываем, что будет (бэклог; Продукт 2, 4). */
    @Composable
    private fun HermesPage() {
        TopBar("Hermes", "свой сервер · скоро", onBack = ::back)
        FormPage {
            Note("Ещё не работает. Подключение Hermes появится в следующей версии — сейчас все режимы отвечают через DeepSeek.")
            Text("Hermes — личный агент на вашем сервере или компьютере (открытый проект Nous Research). Отвечает на всё, что сложнее команд телефона, " +
                "а разговоры, память и расписание хранятся у вас.", color = Ink.soft)
            Column {
                Text("Что будет уметь", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Ink.serverD, modifier = Modifier.padding(vertical = 4.dp))
                SetCard {
                    Item("Память между днями", "Помнит, о чём говорили вчера и неделю назад", icon = Ic.hist)
                    Item("Поиск в интернете", "Погода, новости, цены, расписания — со ссылками на источники", icon = Ic.lens)
                    Item("Команды на сервере", "Проверить бэкапы, посмотреть логи; опасное — только после вашего «Да»", icon = Ic.srv)
                    Item("Расписание", "Напоминания и отчёты в нужное время — приходят сообщением в разговор", icon = Ic.bell)
                    Item("План длинных задач", "Видно, какой шаг сейчас и что уже сделано", icon = Ic.list)
                    Item("Файлы и картинки", "Присылает PDF, таблицы, картинки — открыть или поделиться", icon = Ic.doc)
                    Item("Тот же разговор в Telegram", "Начали голосом в листе — продолжили в Telegram", icon = Ic.chat)
                    Item("Действия телефона", "Будильник, календарь, звонки — как у DeepSeek", icon = Ic.shield)
                }
            }
            Column {
                Text("Как будет подключаться", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Ink.serverD, modifier = Modifier.padding(vertical = 4.dp))
                SetCard {
                    Item("Ссылка из Telegram", "Боту Hermes — команда /tasya_pair", icon = Ic.chat, trailing = "скоро", trailingColor = Ink.faint)
                    Item("QR с экрана компьютера", "Та же команда на сервере", icon = Ic.qr, trailing = "скоро", trailingColor = Ink.faint)
                    Item("Ввести адрес", "Владелец сервера подтвердит код", icon = Ic.pen, trailing = "скоро", trailingColor = Ink.faint)
                }
                MNote("Каждый режим можно будет перевести на Hermes отдельно: «Ассистент» — на сервер, «Переводчик» — оставить на DeepSeek.")
            }
        }
    }

    /** journalPage(): по дням, время · что · кто; по нажатию — фраза, действие, итог, экран. */
    @Composable
    private fun JournalPage() {
        val app = App.instance
        val state by app.conversation.state.collectAsState()
        var open by remember { mutableStateOf<Long?>(null) }
        TopBar("Журнал действий", "только на телефоне", onBack = ::back)
        ScrollPage {
            val list = state.saved.journal.reversed()
            if (list.isEmpty()) MNote("Пока пусто. Здесь будет всё, что делалось на телефоне: будильники, таймеры, календарь, звонки.", Modifier.padding(horizontal = 22.dp))
            list.groupBy { java.text.SimpleDateFormat("d MMMM, EEEE", java.util.Locale("ru")).format(java.util.Date(it.at)) }.forEach { (day, items) ->
                Section(if (android.text.format.DateUtils.isToday(items.first().at)) "Сегодня" else day)
                items.forEach { j ->
                    Row(Modifier.fillMaxWidth().clickable { open = if (open == j.id) null else j.id }.padding(horizontal = 22.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(java.text.SimpleDateFormat("H:mm", java.util.Locale.ROOT).format(java.util.Date(j.at)), fontSize = 13.sp, color = Ink.soft, modifier = Modifier.width(40.dp))
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(j.title, fontSize = 14.5.sp, fontWeight = FontWeight.Medium)
                            Text("${j.who} · ${j.status}", fontSize = 12.5.sp, color = if (j.status == "выполнено") Ink.soft else Ink.danger)
                        }
                    }
                    if (open == j.id) Column(Modifier.padding(start = 76.dp, end = 22.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf("Фраза" to "«${j.phrase}»", "Действие" to "${j.tool} ${j.params}", "Экран" to if (j.locked) "заблокирован" else "открыт").forEach { (k, v) ->
                            Row { Text(k, fontSize = 12.5.sp, color = Ink.faint, modifier = Modifier.width(80.dp)); Text(v, fontSize = 12.5.sp) }
                        }
                    }
                }
            }
        }
    }

    private fun policyWord(p: Policy) = when (p) { Policy.AUTO -> "Разрешено"; Policy.ASK -> "Спрашивать"; Policy.OFF -> "Не разрешено" }

    /** dialog() с radio: .scrim прижат к низу, .dlg.rdlg — скругление 30, пункты .ropt, внизу «Отмена». */
    @Composable
    private fun Choice(d: ChoiceDialog, close: () -> Unit) {
        Box(Modifier.fillMaxSize().background(Color(0x730A0E18)).clickable(interactionSource = null, indication = null) { close() }
            .navigationBarsPadding().padding(14.dp), contentAlignment = Alignment.BottomCenter) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(30.dp)).background(Ink.dlg).clickable(interactionSource = null, indication = null) {}
                .padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 14.dp)) {
                Text(d.title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF14171F), modifier = Modifier.padding(bottom = 8.dp))
                d.options.forEach { (v, label) ->
                    Row(Modifier.fillMaxWidth().clickable { d.onPick(v); close() }.padding(horizontal = 4.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        val on = v == d.current
                        Box(Modifier.size(20.dp).clip(CircleShape).border(if (on) 6.dp else 2.dp, if (on) Color(0xFF3E6FD8) else Color(0xFF9AA0AA), CircleShape))
                        Text(label, fontSize = 14.5.sp, color = Color(0xFF14171F), modifier = Modifier.padding(start = 14.dp))
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.End) { TextButton("Отмена", color = Ink.brain, onClick = close) }
            }
        }
    }

    companion object {
        const val EXTRA_FROM_SHEET = "from_sheet"
        const val EXTRA_SETTINGS = "settings"
        const val EXTRA_PAGE = "page"
        const val EXTRA_NEW_MODE = "new_mode"
        const val EXTRA_ASK_PERM = "ask_perm"
        const val REQ_MIC = 11
    }
}
