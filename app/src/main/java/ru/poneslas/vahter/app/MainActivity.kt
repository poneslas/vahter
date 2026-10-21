package ru.poneslas.vahter.app

import ru.poneslas.vahter.R
import ru.poneslas.vahter.tr
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
import ru.poneslas.vahter.App
import ru.poneslas.vahter.assistant.Sheet
import ru.poneslas.vahter.core.BrainDesc
import ru.poneslas.vahter.core.Brains
import ru.poneslas.vahter.core.LockScope
import ru.poneslas.vahter.core.ReturnToAssistant
import ru.poneslas.vahter.core.Policy
import ru.poneslas.vahter.ui.Composer
import ru.poneslas.vahter.ui.Feed
import ru.poneslas.vahter.ui.PromptStrip
import ru.poneslas.vahter.ui.Ink
import ru.poneslas.vahter.ui.Ic
import ru.poneslas.vahter.core.Modes
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import ru.poneslas.vahter.ui.LiveLine
import ru.poneslas.vahter.ui.VahterTheme

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
        setContent { VahterTheme { Screen() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        readIntent(intent)
    }

    private var askMicThenSheet = false

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
        if (requestCode == REQ_MIC && askMicThenSheet) { askMicThenSheet = false; collapse() }
    }

    /** Растёт при каждом возврате в приложение: статусы системных настроек перечитываются от него (баг №1). */
    val resumed = androidx.compose.runtime.mutableIntStateOf(0)

    override fun onResume() { super.onResume(); App.instance.appVisible.value = true; resumed.intValue++ }
    override fun onPause() { super.onPause(); App.instance.appVisible.value = false }

    /** Пришли из листа: лист закрываем, когда приложение нарисовало первый кадр, — подмена незаметна (Архитектура 3.1). */
    private fun hideSheetAfterFirstFrame() {
        val v = window.decorView
        val l = object : android.view.ViewTreeObserver.OnDrawListener {
            override fun onDraw() { v.post { v.viewTreeObserver.removeOnDrawListener(this); Sheet.session?.hide() } }
        }
        v.viewTreeObserver.addOnDrawListener(l)
    }

    private fun readIntent(i: Intent) {
        fromSheet = i.getBooleanExtra(EXTRA_FROM_SHEET, false)
        if (fromSheet) hideSheetAfterFirstFrame()
        sheetReturn = null
        // Разрешение микрофона спрашиваем из приложения: из листа системное окно на S24 уходит под лист.
        // Из листа — после ответа вернуть лист; из приложения — остаться.
        i.getStringExtra(EXTRA_ASK_PERM)?.let { perms -> askMicThenSheet = fromSheet; if (perms == android.Manifest.permission.RECORD_AUDIO) askMic() else requestPermissions(perms.split(",").toTypedArray(), REQ_MIC) }
        // Шестерёнка в системном «Цифровом помощнике» открывает приложение через псевдоним SettingsEntry — сразу настройки.
        if (i.component?.className?.endsWith(".SettingsEntry") == true) { stack.clear(); stack.add("chat"); stack.add("settings") }
        if (i.getBooleanExtra(EXTRA_SETTINGS, false) && stack.last() != "settings") stack.add("settings")
        i.getStringExtra(EXTRA_PAGE)?.let { p -> stack.clear(); stack.add("chat"); if (p != "chat") { if (p != "settings" && !p.startsWith("onb:")) stack.add("settings"); stack.add(p) } }
        // Полоска промпта в листе → страница режима; «Назад» с неё возвращает лист, а не настройки.
        i.getStringExtra(EXTRA_EDIT_MODE)?.let { id -> stack.clear(); stack.add("chat"); stack.add("mode:$id"); sheetReturn = "mode:$id" }
        // Строка «Мозг не подключён · Подключить» или карточка «Наверх не дозвониться» в листе → экран мозга; «Назад» — снова лист.
        if (i.getBooleanExtra(EXTRA_BRAIN, false)) { stack.clear(); stack.add("chat"); stack.add("brain:deepseek"); sheetReturn = "brain:deepseek" }
        if (i.getBooleanExtra(EXTRA_NEW_MODE, false)) { if (stack.last() != "settings") stack.add("settings"); stack.add("mode:" + App.instance.conversation.addMode()) }
    }

    fun go(page: String) { stack.add(page) }
    /** Страница, с которой «Назад» сворачивает обратно в лист (пришли на неё из листа). */
    private var sheetReturn: String? by mutableStateOf(null)
    fun back() {
        if (sheetReturn != null && stack.last() == sheetReturn) { sheetReturn = null; collapse(); return }
        if (stack.size > 1) stack.removeAt(stack.lastIndex) else finish()
    }
    fun choose(d: ChoiceDialog) { dialog = d }

    /** Свернуть обратно в лист: finish + showSession (Архитектура 3.1). */
    private fun collapse() { Sheet.shrinkOnShow = true; finish(); Sheet.show(Bundle().apply { putString("src", "app") }) }

    /**
     * Финал мастера — лист, а не приложение (Продукт 5.13): он интереснее полноэкранного разговора, и так Вахтёр выглядит каждый день.
     * Пустой лист с «Вам чего?» и чипами, один раз — подсказка у боковой кнопки, при первом закрытии — «Вахтёр на месте».
     * Без роли помощника листа нет (showSession может только активный помощник) — остаётся разговор в приложении и баннер.
     */
    private fun finishOnboarding() {
        App.instance.conversation.updateSettings { it.copy(onboarded = true) }
        if (Sheet.service == null) return
        App.instance.conversation.selectMode(ru.poneslas.vahter.core.Modes.MAIN)
        Sheet.firstSheet.value = true; Sheet.sideCue.value = true; Sheet.firstClose = true
        finish(); Sheet.show(Bundle().apply { putString("src", "app") })
    }

    @Composable
    private fun Screen() {
        val app = App.instance
        val state by app.conversation.state.collectAsState()
        BackHandler(stack.size > 1) { back() }
        Box(Modifier.fillMaxSize().background(Ink.paper)) {
            Column(Modifier.fillMaxSize().imePadding()) {
                val page = stack.last()
                // Пришли из листа на экран (мозг, режим) до конца мастера — показываем экран, «Назад» вернёт лист.
                if ((!state.settings.onboarded && sheetReturn == null) || page.startsWith("onb:")) {
                    Onboarding(this@MainActivity, page.removePrefix("onb:").toIntOrNull() ?: 0) {
                        if (page.startsWith("onb:")) { app.conversation.updateSettings { it.copy(onboarded = true) }; back() } else finishOnboarding()
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
            permSheet?.let { d -> PermSheetUi(d) { permSheet = null; if (askMicThenSheet) { askMicThenSheet = false; collapse() } } }
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
        TopBar(state.current.title, tr(R.string.set_link_status, brain.title, tr(if (connected) R.string.set_link_on else R.string.set_link_off)),
            onBack = if (fromSheet) ({ collapse() }) else null, backIcon = Ic.down, dot = if (connected) Ink.server else Ink.local) {
            IconBtn(Ic.gear, tr(R.string.ui_settings)) { go("settings") }
            // ⋮ — меню разговора (ui.js, chatMenu): изменить режим, журнал действий, начать заново (неактивно, пока разговор пуст).
            Box {
                var menu by remember { mutableStateOf(false) }
                IconBtn(Ic.more, tr(R.string.restart_more)) { menu = true }
                androidx.compose.material3.DropdownMenu(menu, { menu = false }, containerColor = Ink.surf,
                    shape = RoundedCornerShape(16.dp)) {
                    @Composable fun row(icon: Int, label: String, enabled: Boolean = true, onClick: () -> Unit) = androidx.compose.material3.DropdownMenuItem(
                        text = { Text(label, fontSize = 15.sp, color = if (enabled) Ink.ink else Ink.faint) },
                        leadingIcon = { androidx.compose.material3.Icon(androidx.compose.ui.res.painterResource(icon), null, Modifier.size(20.dp), tint = if (enabled) Ink.soft else Ink.faint) },
                        enabled = enabled, onClick = { menu = false; onClick() })
                    row(Ic.edit, tr(R.string.mode_edit)) { go("mode:${state.mode}") }
                    row(Ic.list, tr(R.string.set_journal)) { go("journal") }
                    row(Ic.hist, tr(R.string.ui_restart), enabled = state.feed.isNotEmpty()) { app.conversation.askRestart() }
                }
            }
        }
        if (!connected) Banner(tr(R.string.set_no_brain_banner),
            actions = listOf(tr(R.string.ui_connect) to { go("brain:${brain.id}") }))
        PromptStrip(state.current, onEdit = { go("mode:$it") })
        Feed(state, onAct = { a, undo -> app.conversation.onActButton(a, undo) }, onCard = { c, a -> app.conversation.onCardButton(c, a) }, onCancelAttach = app.conversation::cancelAttach,
            modifier = Modifier.weight(1f).fillMaxWidth(), inSheet = false, onChip = { app.conversation.send(it, tr(R.string.cmp_via_keyboard)) },
            from = Sheet.from.collectAsState().value[state.mode] ?: 0)
        ru.poneslas.vahter.ui.UpLine(state)
        LiveLine(live)
        Composer(
            state = state, voice = app.voice,
            placeholder = ru.poneslas.vahter.ui.composerPlaceholder(state, connected),
            onDraft = app.conversation::setDraft,
            onSend = { t, v -> app.conversation.send(t, v) },
            onSelectMode = app.conversation::selectMode,
            onAddMode = { go("mode:" + app.conversation.addMode()) },
            onLive = { live = it },
            onNeedMic = ::askMic,
            onGallery = { ru.poneslas.vahter.media.PickerActivity.launchFromApp(this@MainActivity) },
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
        val dl by ru.poneslas.vahter.pisar.model.ModelDownload.state.collectAsState()
        TopBar(tr(R.string.ui_settings), onBack = ::back)
        ScrollPage {
            Section(tr(R.string.set_call))
            Item(tr(R.string.set_assistant_role), if (roleHeld) tr(R.string.app_name) else tr(R.string.set_assistant_role_off), warn = !roleHeld, icon = Ic.spark) {
                startActivity(Intent(AndroidSettings.ACTION_VOICE_INPUT_SETTINGS))
            }
            Item(tr(R.string.set_side_key), SideKey.describe(this@MainActivity), icon = Ic.btn) { SideKey.open(this@MainActivity) }
            Item(tr(R.string.set_listen_on_call), tr(if (state.settings.listenOnCall) R.string.set_listen_on_call_on else R.string.set_listen_on_call_off),
                icon = Ic.mic, end = { Sw(state.settings.listenOnCall) }) { app.conversation.updateSettings { it.copy(listenOnCall = !it.listenOnCall) } }
            Item(tr(R.string.set_return), returnLabel(state.settings.returnToAssistant), icon = Ic.hist) {
                dialog = ChoiceDialog(tr(R.string.set_return), ReturnToAssistant.entries.map { it.name to returnLabel(it) }, state.settings.returnToAssistant.name) { v ->
                    app.conversation.updateSettings { it.copy(returnToAssistant = ReturnToAssistant.valueOf(v)) }
                }
            }
            Section(tr(R.string.set_modes))
            state.modes.forEach { m ->
                if (m.id == Modes.MAIN) Item(m.title, modeSummary(m), icon = Ic.mode(m.icon)) { go("mode:${m.id}") }
                else ItemWithSwitch(m.title, modeSummary(m), Ic.mode(m.icon), m.enabled,
                    onToggle = { app.conversation.editMode(m.id) { it.copy(enabled = !it.enabled) } }) { go("mode:${m.id}") }
            }
            Item(tr(R.string.feed_new_mode), tr(R.string.set_modes_sub), icon = Ic.plus) { go("mode:" + app.conversation.addMode()) }
            Section(tr(R.string.set_voice))
            val pct = (dl as? ru.poneslas.vahter.pisar.model.ModelDownload.State.Running)?.percent
            Item(tr(R.string.set_voice_title), when { installed -> tr(R.string.set_voice_ready); pct != null -> tr(R.string.set_voice_loading, pct); else -> tr(R.string.app_voice_none) },
                warn = !installed && pct == null, icon = Ic.wave) { go("voice") }
            val micOff = !mic && state.settings.micAsked && !shouldShowRequestPermissionRationale(android.Manifest.permission.RECORD_AUDIO)
            Item(tr(R.string.set_mic), tr(when { mic -> R.string.set_mic_on; micOff -> R.string.set_mic_denied; else -> R.string.set_mic_ask }), warn = micOff, icon = Ic.mic) {
                if (!mic) askMic()
            }
            Section(tr(R.string.set_brains))
            Brains.all.forEach { b ->
                val api = b.kind == BrainDesc.Kind.API
                val has = app.hasKey(b.id)
                Item(b.title, if (api) (if (has) tr(R.string.set_brain_has_key, b.model) else tr(R.string.set_brain_no_key)) else tr(R.string.set_hermes_sub),
                    warn = api && !has, icon = if (api) Ic.spark else Ic.srv) { go(if (api) "brain:${b.id}" else "hermes") }
            }
            Section(tr(R.string.set_notif))
            Item(tr(R.string.set_notif), if (notif) tr(R.string.set_notif_on) else tr(R.string.set_notif_off), icon = Ic.bell, end = { Sw(notif) }) {
                if (!notif) requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 2)
                else startActivity(Intent(AndroidSettings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(AndroidSettings.EXTRA_APP_PACKAGE, packageName))
            }
            Section(tr(R.string.set_actions_section))
            val REG = ru.poneslas.vahter.actions.Registry
            val pol = REG.all.map { REG.policy(state.settings, it.id) }
            val blockedN = REG.all.count { REG.policy(state.settings, it.id) != Policy.OFF && it.special != null && REG.missing(this@MainActivity, it).isNotEmpty() }
            Item(tr(R.string.set_actions), if (blockedN > 0) tr(R.string.set_actions_no_access, blockedN) else tr(R.string.set_actions_counts, pol.count { it == Policy.AUTO }, pol.count { it == Policy.ASK }, pol.count { it == Policy.OFF }),
                warn = blockedN > 0, icon = Ic.shield) { go("actions") }
            Item(tr(R.string.set_journal), tr(R.string.set_journal_today, state.saved.journal.count { android.text.format.DateUtils.isToday(it.at) }), icon = Ic.list) { go("journal") }
            Divider()
            Item(tr(R.string.set_about), tr(R.string.set_about_version, packageManager.getPackageInfo(packageName, 0).versionName ?: ""), icon = Ic.info)
        }
    }

    /** model.js, modeSummary(). */
    private fun modeSummary(m: ru.poneslas.vahter.core.Mode): String {
        if (m.id == Modes.MAIN) return tr(R.string.set_mode_main_sub)
        val ins = listOfNotNull(tr(R.string.sheet_via_photo).takeIf { m.accepts.photo }, tr(R.string.set_mode_file).takeIf { m.accepts.file })
        return listOfNotNull(if (m.builtin) null else tr(R.string.set_mode_custom), if (m.primary == ru.poneslas.vahter.core.Primary.PHOTO) tr(R.string.set_mode_photo_first) else tr(R.string.sheet_via_voice),
            ins.joinToString(", ").ifEmpty { null }).joinToString(" · ")
    }

    private fun returnLabel(r: ReturnToAssistant) = when (r) {
        ReturnToAssistant.MIN30 -> tr(R.string.set_return_30m)
        ReturnToAssistant.NEVER -> tr(R.string.set_return_never)
        ReturnToAssistant.ALWAYS -> tr(R.string.set_return_always)
    }

    private fun lockLabel(s: LockScope) = when (s) {
        LockScope.OFF -> tr(R.string.set_lock_off)
        LockScope.SAFE -> tr(R.string.set_lock_safe)
        LockScope.ON -> tr(R.string.set_lock_on)
    }

    /** brainPage(): карточка «Адрес / Модель», ключ, «Умеет», пояснение. */
    @Composable
    private fun BrainPage(id: String) {
        val app = App.instance
        val b = Brains.byId(id)
        val state by app.conversation.state.collectAsState()
        TopBar(b.title, tr(R.string.set_brain_api), onBack = ::back)
        FormPage {
            SetCard { Item(tr(R.string.set_brain_url), b.url, icon = Ic.srv); Item(tr(R.string.set_brain_model), b.model, icon = Ic.spark) }
            KeyEditor(b)
            Column {
                Text(tr(R.string.set_brain_caps), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Ink.serverD, modifier = Modifier.padding(vertical = 4.dp))
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    MChip(tr(R.string.set_cap_vision), b.vision); MChip(tr(R.string.set_cap_tools), b.tools); MChip(tr(R.string.set_cap_memory), b.memory); MChip(tr(R.string.set_cap_server), b.server)
                }
            }
            val users = state.modes.filter { it.brain == b.id }.joinToString(", ") { it.title }.ifEmpty { tr(R.string.set_none) }
            MNote(tr(R.string.set_brain_modes_note, users))
        }
    }

    /** toolsPage(): баннер «нет доступа», группы с политикой справа и состоянием доступа, экран блокировки, журнал. */
    @Composable
    private fun ActionsPage() {
        val app = App.instance
        val state by app.conversation.state.collectAsState()
        @Suppress("UNUSED_VARIABLE") val r = resumed.intValue // перечитать доступы после системных окон
        val REG = ru.poneslas.vahter.actions.Registry
        TopBar(tr(R.string.set_actions), tr(R.string.actions_subtitle), onBack = ::back)
        ScrollPage {
            val blocked = REG.all.filter { REG.policy(state.settings, it.id) != Policy.OFF && it.special != null && REG.missing(this@MainActivity, it).isNotEmpty() }
            if (blocked.isNotEmpty()) {
                val t = blocked.first()
                Banner(tr(R.string.actions_blocked, t.title, REG.specialTitle(t.special!!)),
                    actions = listOf(tr(R.string.actions_fix) to { ru.poneslas.vahter.actions.Phone.openDndAccess(this@MainActivity); Unit }))
            }
            REG.groups.forEach { g ->
                Section(g)
                REG.all.filter { it.group == g }.forEach { t ->
                    val p = REG.policy(state.settings, t.id)
                    val miss = REG.missing(this@MainActivity, t)
                    val sub = when {
                        p == Policy.OFF || miss.isEmpty() -> null
                        t.special != null -> tr(R.string.actions_no_access, miss.joinToString(", "))
                        else -> tr(R.string.actions_will_ask, miss.joinToString(", "))
                    }
                    Item(t.title, sub, trailing = policyWord(p), trailingColor = when (p) { Policy.AUTO -> Ink.soft; Policy.ASK -> Ink.local; Policy.OFF -> Ink.danger },
                        minHeight = 52.dp) {
                        val opts = (if (t.alwaysAsk) emptyList() else listOf("AUTO" to tr(R.string.actions_policy_auto))) + listOf("ASK" to tr(R.string.actions_policy_ask), "OFF" to tr(R.string.actions_policy_off))
                        dialog = ChoiceDialog(t.title, opts, p.name) { v ->
                            app.conversation.updateSettings { it.copy(policy = it.policy + (t.id to Policy.valueOf(v))) }
                        }
                    }
                }
            }
            Section(tr(R.string.actions_lock_h))
            Item(tr(R.string.actions_lock_sub), lockLabel(state.settings.lockScope), icon = Ic.lock) {
                dialog = ChoiceDialog(tr(R.string.actions_lock_sub), LockScope.entries.map { it.name to lockLabel(it) }, state.settings.lockScope.name) { v ->
                    app.conversation.updateSettings { it.copy(lockScope = LockScope.valueOf(v)) }
                }
            }
            Item(tr(R.string.set_journal), tr(R.string.set_journal_today, state.saved.journal.count { android.text.format.DateUtils.isToday(it.at) }), icon = Ic.list) { go("journal") }
            MNote(tr(R.string.actions_note),
                Modifier.padding(start = 22.dp, end = 22.dp, top = 4.dp, bottom = 20.dp))
        }
    }

    /** Hermes: ещё не подключается — честно говорим и описываем, что будет (бэклог; Продукт 2, 4). */
    @Composable
    private fun HermesPage() {
        TopBar("Hermes", tr(R.string.hermes_subtitle), onBack = ::back)
        FormPage {
            Note(tr(R.string.hermes_not_yet))
            Text(tr(R.string.hermes_about_1) +
                tr(R.string.hermes_about_2), color = Ink.soft)
            Column {
                Text(tr(R.string.hermes_will_h), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Ink.serverD, modifier = Modifier.padding(vertical = 4.dp))
                SetCard {
                    Item(tr(R.string.hermes_will_memory), tr(R.string.hermes_will_memory_sub), icon = Ic.hist)
                    Item(tr(R.string.hermes_will_search), tr(R.string.hermes_will_search_sub), icon = Ic.lens)
                    Item(tr(R.string.hermes_will_server), tr(R.string.hermes_will_server_sub), icon = Ic.srv)
                    Item(tr(R.string.hermes_will_schedule), tr(R.string.hermes_will_schedule_sub), icon = Ic.bell)
                    Item(tr(R.string.hermes_will_plan), tr(R.string.hermes_will_plan_sub), icon = Ic.list)
                    Item(tr(R.string.hermes_will_files), tr(R.string.hermes_will_files_sub), icon = Ic.doc)
                    Item(tr(R.string.hermes_will_telegram), tr(R.string.hermes_will_telegram_sub), icon = Ic.chat)
                    Item(tr(R.string.set_cap_tools), tr(R.string.hermes_will_phone), icon = Ic.shield)
                }
            }
            Column {
                Text(tr(R.string.hermes_pair_h), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Ink.serverD, modifier = Modifier.padding(vertical = 4.dp))
                SetCard {
                    Item(tr(R.string.hermes_pair_tg), tr(R.string.hermes_pair_tg_sub), icon = Ic.chat, trailing = tr(R.string.hermes_soon), trailingColor = Ink.faint)
                    Item(tr(R.string.hermes_pair_qr), tr(R.string.hermes_pair_qr_sub), icon = Ic.qr, trailing = tr(R.string.hermes_soon), trailingColor = Ink.faint)
                    Item(tr(R.string.hermes_pair_addr), tr(R.string.hermes_pair_addr_sub), icon = Ic.pen, trailing = tr(R.string.hermes_soon), trailingColor = Ink.faint)
                }
                MNote(tr(R.string.hermes_per_mode))
            }
        }
    }

    /** journalPage(): по дням, время · что · кто; по нажатию — фраза, действие, итог, экран. */
    @Composable
    private fun JournalPage() {
        val app = App.instance
        val state by app.conversation.state.collectAsState()
        var open by remember { mutableStateOf<Long?>(null) }
        TopBar(tr(R.string.set_journal), tr(R.string.journal_subtitle), onBack = ::back)
        ScrollPage {
            val list = state.saved.journal.reversed()
            if (list.isEmpty()) MNote(tr(R.string.journal_empty), Modifier.padding(horizontal = 22.dp))
            list.groupBy { java.text.SimpleDateFormat("d MMMM, EEEE", java.util.Locale("ru")).format(java.util.Date(it.at)) }.forEach { (day, items) ->
                Section(if (android.text.format.DateUtils.isToday(items.first().at)) tr(R.string.journal_today) else day)
                items.forEach { j ->
                    Row(Modifier.fillMaxWidth().clickable { open = if (open == j.id) null else j.id }.padding(horizontal = 22.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(java.text.SimpleDateFormat("H:mm", java.util.Locale.ROOT).format(java.util.Date(j.at)), fontSize = 13.sp, color = Ink.soft, modifier = Modifier.width(40.dp))
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(j.title, fontSize = 14.5.sp, fontWeight = FontWeight.Medium)
                            Text("${j.who} · ${j.status}", fontSize = 12.5.sp, color = if (j.status == tr(R.string.conv_done)) Ink.soft else Ink.danger)
                        }
                    }
                    if (open == j.id) Column(Modifier.padding(start = 76.dp, end = 22.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf(tr(R.string.journal_phrase) to "«${j.phrase}»", tr(R.string.journal_action) to "${j.tool} ${j.params}", tr(R.string.journal_screen) to if (j.locked) tr(R.string.journal_screen_locked) else tr(R.string.journal_screen_open)).forEach { (k, v) ->
                            Row { Text(k, fontSize = 12.5.sp, color = Ink.faint, modifier = Modifier.width(80.dp)); Text(v, fontSize = 12.5.sp) }
                        }
                    }
                }
            }
        }
    }

    private fun policyWord(p: Policy) = when (p) { Policy.AUTO -> tr(R.string.actions_policy_auto); Policy.ASK -> tr(R.string.actions_policy_ask); Policy.OFF -> tr(R.string.actions_policy_off) }

    /** Наше окно-лист (app.css, .osheet): ручка, значок, заголовок, текст; справа действие, слева от него «Не сейчас». */
    @Composable
    private fun PermSheetUi(d: PermSheet, close: () -> Unit) {
        Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color(0x730A0E18)).clickable(onClick = close), contentAlignment = Alignment.BottomCenter) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)).background(Ink.paper).clickable(enabled = false) {}
                .navigationBarsPadding().padding(start = 22.dp, end = 22.dp, top = 10.dp, bottom = 22.dp)) {
                Box(Modifier.fillMaxWidth().padding(bottom = 14.dp), contentAlignment = Alignment.Center) { Box(Modifier.size(32.dp, 4.dp).clip(RoundedCornerShape(2.dp)).background(Ink.grab)) }
                Box(Modifier.size(44.dp).clip(androidx.compose.foundation.shape.CircleShape).background(Ink.localT), contentAlignment = Alignment.Center) {
                    androidx.compose.material3.Icon(androidx.compose.ui.res.painterResource(d.icon), null, Modifier.size(22.dp), tint = Ink.localD)
                }
                Text(d.title, fontSize = 21.sp, fontWeight = FontWeight.SemiBold, color = Ink.ink, modifier = Modifier.padding(top = 12.dp, bottom = 8.dp))
                Text(d.text, fontSize = 14.5.sp, color = Ink.soft, modifier = Modifier.padding(bottom = 18.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(d.later ?: tr(R.string.not_now)) { val f = d.onLater; if (f != null) { permSheet = null; f() } else close() }
                    PrimaryButton(d.go) { permSheet = null; d.onGo() }
                }
            }
        }
    }

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
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.End) { TextButton(tr(R.string.ui_cancel), color = Ink.brain, onClick = close) }
            }
        }
    }

    companion object {
        const val EXTRA_FROM_SHEET = "from_sheet"
        const val EXTRA_SETTINGS = "settings"
        const val EXTRA_PAGE = "page"
        const val EXTRA_EDIT_MODE = "editMode"
        const val EXTRA_NEW_MODE = "new_mode"
        const val EXTRA_BRAIN = "brain"
        const val EXTRA_ASK_PERM = "ask_perm"
        const val REQ_MIC = 11
    }
}
