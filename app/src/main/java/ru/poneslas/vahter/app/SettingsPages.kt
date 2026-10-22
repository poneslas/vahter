package ru.poneslas.vahter.app

import androidx.compose.foundation.layout.size
import ru.poneslas.vahter.ui.PageSection
import ru.poneslas.vahter.ui.StatusItem
import ru.poneslas.vahter.ui.CapItem
import ru.poneslas.vahter.pisar.model.ModelDownload
import ru.poneslas.vahter.actions.Registry
import ru.poneslas.vahter.core.Mode
import ru.poneslas.vahter.core.Primary
import ru.poneslas.vahter.actions.Phone
import androidx.compose.foundation.layout.FlowRow
import ru.poneslas.vahter.ui.MNote
import androidx.compose.foundation.layout.Spacer
import ru.poneslas.vahter.R
import ru.poneslas.vahter.core.Modes
import ru.poneslas.vahter.ui.Item
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import ru.poneslas.vahter.tr
import ru.poneslas.vahter.core.ReturnToAssistant
import ru.poneslas.vahter.App
import ru.poneslas.vahter.ui.ItemWithSwitch
import ru.poneslas.vahter.ui.Note
import ru.poneslas.vahter.ui.MChip
import androidx.compose.material3.Text
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import android.app.role.RoleManager
import ru.poneslas.vahter.ui.Ink
import ru.poneslas.vahter.ui.Divider
import ru.poneslas.vahter.ui.Sw
import androidx.compose.foundation.layout.Row
import ru.poneslas.vahter.ui.Ic
import androidx.compose.foundation.layout.Arrangement
import ru.poneslas.vahter.core.Brains
import androidx.compose.runtime.mutableStateOf
import ru.poneslas.vahter.ui.Section
import androidx.compose.ui.text.font.FontWeight
import ru.poneslas.vahter.ui.Banner
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import ru.poneslas.vahter.core.Policy
import ru.poneslas.vahter.core.LockScope
import androidx.compose.runtime.collectAsState
import ru.poneslas.vahter.ui.ScrollPage
import ru.poneslas.vahter.core.BrainDesc
import ru.poneslas.vahter.ui.SetCard
import ru.poneslas.vahter.ui.FormPage
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.clickable
import ru.poneslas.vahter.ui.TopBar
import ru.poneslas.vahter.ui.Note
import android.provider.Settings as AndroidSettings
import ru.poneslas.vahter.brain.HermesBrain
import ru.poneslas.vahter.brain.HermesLink
import ru.poneslas.vahter.ui.H2
import ru.poneslas.vahter.ui.BaseText
import ru.poneslas.vahter.ui.IconBtn
import ru.poneslas.vahter.ui.PrimaryButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.draw.clip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.shape.CircleShape
import kotlinx.coroutines.CoroutineScope
import ru.poneslas.vahter.assistant.Sheet
import ru.poneslas.vahter.assistant.VahterTile
import ru.poneslas.vahter.notify.Notifier
import ru.poneslas.vahter.ui.OursIcon
import ru.poneslas.vahter.ui.Progress
import ru.poneslas.vahter.ui.TextButton
import ru.poneslas.vahter.update.Updates

/** Страницы за шестерёнкой (ui.js: settings, brainPage, actionsPage, hermesPage). */

@Composable
fun SettingsPage(a: MainActivity) {
    val app = App.instance
    val state by app.conversation.state.collectAsState()
    @Suppress("UNUSED_VARIABLE") val r = a.resumed.intValue // баг №1: перечитать после возврата из системных настроек
    val roleHeld = a.getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_ASSISTANT)
    val mic = a.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    val notif = a.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    val installed by app.voice.installed.collectAsState()
    val dl by ModelDownload.state.collectAsState()
    // Перестановка режимов: долгое нажатие на режим — переключатели становятся ручками, в шапке «Готово» (ModesList).
    var reorder by remember { mutableStateOf(false) }
    BackHandler(reorder) { reorder = false }
    TopBar(tr(if (reorder) R.string.set_modes_order else R.string.ui_settings), onBack = if (reorder) ({ reorder = false }) else a::back) {
        if (reorder) TextButton(tr(R.string.ui_done)) { reorder = false }
    }
    ScrollPage {
        // Режимы — главное, первым разделом (Продукт 5.11).
        Section(tr(R.string.set_modes))
        ModesList(state.modes, reorder, ::modeSummary, onReorder = { reorder = true }, onMove = app.conversation::moveMode,
            onToggle = { m -> app.conversation.editMode(m.id) { it.copy(enabled = !it.enabled) } }, onOpen = { m -> a.go("mode:${m.id}") })
        if (!reorder) Item(tr(R.string.feed_new_mode), tr(R.string.set_modes_sub), icon = Ic.plus) { a.go("mode:" + app.conversation.addMode()) }
        if (!reorder && !state.settings.modesReordered) MNote(tr(R.string.set_modes_hold), Modifier.padding(horizontal = 22.dp))
        // Как позвать: одна строка-статус вместо «Цифровой помощник» + «Боковая кнопка» — встраивание в систему настраивают один раз,
        // показывать стоит, только когда что-то не так. Подробности — на странице «Как позвать Вахтёра» (CallPage).
        Section(tr(R.string.set_call))
        val call = callStatus(a, roleHeld, state.settings.sideProbe)
        Item(call.first, call.second, warn = call.third, icon = Ic.btn) { a.go("call") }
        Item(tr(R.string.set_listen_on_call), tr(if (state.settings.listenOnCall) R.string.set_listen_on_call_on else R.string.set_listen_on_call_off),
            icon = Ic.mic, end = { Sw(state.settings.listenOnCall) }) { app.conversation.updateSettings { it.copy(listenOnCall = !it.listenOnCall) } }
        Item(tr(R.string.set_return), returnShort(state.settings.returnToAssistant), icon = Ic.hist) {
            a.choose(MainActivity.ChoiceDialog(tr(R.string.set_return), ReturnToAssistant.entries.map { it.name to returnLabel(it) }, state.settings.returnToAssistant.name) { v ->
                app.conversation.updateSettings { it.copy(returnToAssistant = ReturnToAssistant.valueOf(v)) }
            })
        }
        Section(tr(R.string.set_voice))
        val pct = (dl as? ModelDownload.State.Running)?.percent
        Item(tr(R.string.set_voice_title), when { installed -> tr(R.string.set_voice_ready); pct != null -> tr(R.string.set_voice_loading, pct); else -> tr(R.string.app_voice_none) },
            warn = !installed && pct == null, icon = Ic.wave) { a.go("voice") }
        // Микрофон — строкой, только пока доступа нет (с доступом строке нечего делать).
        val micOff = !mic && state.settings.micAsked && !a.shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)
        if (!mic) Item(tr(R.string.set_mic), tr(if (micOff) R.string.set_mic_denied else R.string.set_mic_ask), warn = micOff, icon = Ic.mic) { a.askMic() }
        Section(tr(R.string.set_brains))
        Brains.all.forEach { b ->
            val api = b.kind == BrainDesc.Kind.API
            val has = app.hasKey(b.id)
            val hermes = if (api) null else app.hermesLink()
            Item(b.title, if (api) (if (has) tr(R.string.set_brain_has_key, b.model) else tr(R.string.set_brain_no_key))
                else if (hermes != null) tr(R.string.set_hermes_on, HermesBrain.host(hermes.url)) else tr(R.string.set_hermes_sub),
                warn = api && !has, icon = if (api) Ic.spark else Ic.srv) { a.go(if (api) "brain:${b.id}" else "hermes") }
        }
        Section(tr(R.string.set_notif))
        // Уведомления: нет разрешения — спросить; Android больше не спрашивает — наше окно → настройки; выключены в системе
        // (разрешение есть, но приложение или канал выключены) — в настройки; всё включено — переключатель — своя настройка «сообщать».
        val sysOn = notif && Notifier.enabled(a)
        val notifOff = !notif && state.settings.notifAsked && !a.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)
        val on = sysOn && state.settings.notifyAnswers
        Item(tr(R.string.set_notif), tr(when {
            !notif && notifOff -> R.string.set_notif_denied
            !notif -> R.string.set_notif_ask
            !sysOn -> R.string.set_notif_system
            on -> R.string.set_notif_on
            else -> R.string.set_notif_off
        }), warn = notifOff, icon = Ic.bell, end = { Sw(on) }) {
            when {
                !notif && notifOff -> a.showSheet(MainActivity.PermSheet(tr(R.string.notif_off_h), tr(R.string.notif_off_p), tr(R.string.mic_off_go), icon = Ic.bell) { a.notifSettings() })
                !notif -> { app.conversation.updateSettings { it.copy(notifAsked = true, notifyAnswers = true) }; a.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2) }
                !sysOn -> a.notifSettings()
                else -> app.conversation.updateSettings { it.copy(notifyAnswers = !it.notifyAnswers) }
            }
        }
        Section(tr(R.string.set_actions_section))
        val pol = Registry.all.map { Registry.policy(state.settings, it.id) }
        val blockedN = Registry.all.count { Registry.policy(state.settings, it.id) != Policy.OFF && it.special != null && Registry.missing(a, it).isNotEmpty() }
        Item(tr(R.string.set_actions), if (blockedN > 0) tr(R.string.set_actions_no_access, blockedN) else tr(R.string.set_actions_counts, pol.count { it == Policy.AUTO }, pol.count { it == Policy.ASK }, pol.count { it == Policy.OFF }),
            warn = blockedN > 0, icon = Ic.shield) { a.go("actions") }
        Divider()
        val upd by Updates.state.collectAsState()
        val newVer = (upd as? Updates.State.Available)?.r?.version
        Item(tr(R.string.set_about), if (newVer != null) tr(R.string.set_about_new, newVer) else tr(R.string.set_about_version, a.packageManager.getPackageInfo(a.packageName, 0).versionName ?: ""),
            icon = Ic.info, end = if (newVer != null) ({ Box(Modifier.size(8.dp).clip(CircleShape).background(Ink.local)) }) else null) { a.go("about") }
    }
}

/** model.js, modeSummary(). */
/** Подпись режима — «для чего» (поле hint): та же строка, что под названием в пустом листе. Про голос и фото здесь не пишем (Продукт 5.17). */
private fun modeSummary(m: Mode): String = m.hint.ifBlank { if (m.builtin) "" else tr(R.string.set_mode_no_purpose) }

private fun returnLabel(r: ReturnToAssistant) = when (r) {
    ReturnToAssistant.MIN30 -> tr(R.string.set_return_30m)
    ReturnToAssistant.NEVER -> tr(R.string.set_return_never)
    ReturnToAssistant.ALWAYS -> tr(R.string.set_return_always)
}

/** «Запоминать режим» в строке настроек — коротко: «На 30 минут» / «Всегда» / «Нет» (ui.js, RETURN_OPTS). Ключи прежние: NEVER = помнить всегда. */
private fun returnShort(r: ReturnToAssistant) = when (r) {
    ReturnToAssistant.MIN30 -> tr(R.string.set_return_30m_short)
    ReturnToAssistant.NEVER -> tr(R.string.set_return_never_short)
    ReturnToAssistant.ALWAYS -> tr(R.string.set_return_always_short)
}

private fun lockLabel(s: LockScope) = when (s) {
    LockScope.OFF -> tr(R.string.set_lock_off)
    LockScope.SAFE -> tr(R.string.set_lock_safe)
    LockScope.ON -> tr(R.string.set_lock_on)
}

/** brainPage(): карточка «Адрес / Модель», ключ, «Умеет», пояснение. */
/**
 * DeepSeek по канону (ui.js, brainPage): «Подключение» (статус проверяется сам при входе, адрес, модель, ключ), «Умеет», «Режимы»,
 * «Отключить». Ключ — «Изменить» → поле и «Подключить» (проверить и сохранить). Ушли с непроверенным ключом — не теряем молча:
 * «Подключить / Не сохранять».
 */
@Composable
fun BrainPage(a: MainActivity, id: String) {
    val app = App.instance
    val b = Brains.byId(id)
    val state by app.conversation.state.collectAsState()
    val scope = rememberCoroutineScope()
    val keyV by app.keyVersion.collectAsState()
    val has = remember(keyV) { app.hasKey(b.id) }
    val ctl = rememberKeyCtl(b)
    var editing by remember { mutableStateOf(false) }
    var auto by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var autoRun by remember { mutableStateOf(false) }
    LaunchedEffect(keyV) {
        if (!app.hasKey(b.id)) { auto = null; return@LaunchedEffect }
        autoRun = true
        val r = withContext(Dispatchers.IO) { app.apiBrain(b).check(app.storedKey(b.id).orEmpty()) }
        auto = r.ok to r.text; autoRun = false
    }
    LaunchedEffect(ctl.status) { if (ctl.status?.first == true) editing = false }
    val dirty = ctl.key.isNotBlank() && !ctl.checking
    fun leave() {
        if (!dirty) return a.back()
        a.showSheet(MainActivity.PermSheet(tr(R.string.key_unsaved_h), tr(R.string.key_unsaved_p), tr(R.string.hermes_connect), icon = Ic.lock,
            later = tr(R.string.key_unsaved_no), onLater = { ctl.key = ""; a.hideSheet(); a.back() }) { a.hideSheet(); ctl.connect(scope) })
    }
    BackHandler(dirty) { leave() }
    TopBar(b.title, tr(R.string.set_brain_api), onBack = ::leave)
    FormPage {
        PageSection(tr(R.string.conn_section)) {
            when {
                ctl.checking || autoRun -> StatusItem(null, tr(R.string.conn_checking))
                !has -> StatusItem(false, tr(R.string.conn_off))
                auto?.first == false -> StatusItem(false, tr(R.string.conn_bad, auto!!.second))
                else -> StatusItem(true, tr(R.string.conn_ok, auto?.second.orEmpty()))
            }
            Item(tr(R.string.set_brain_url), b.url, icon = Ic.srv)
            Item(tr(R.string.set_brain_model), b.model, icon = Ic.spark)
            if (has && !editing) Item(tr(R.string.key_row), tr(R.string.key_row_sub, app.storedKey(b.id).orEmpty().takeLast(4)), trailing = tr(R.string.key_change), trailingColor = Ink.serverD, icon = Ic.lock) { editing = true }
            else KeyInline(ctl, onCancel = if (has) ({ editing = false; ctl.key = ""; ctl.status = null }) else null) { ctl.connect(scope) }
        }
        PageSection(tr(R.string.set_brain_caps)) {
            CapItem(Ic.cam, tr(R.string.cap_photo), tr(R.string.cap_photo_sub), b.vision)
            CapItem(Ic.shield, tr(R.string.set_cap_tools), tr(R.string.cap_tools_sub), b.tools)
            CapItem(Ic.lens, tr(R.string.hermes_will_search), tr(if (b.search) R.string.cap_search_sub else R.string.cap_search_soon), b.search)
            CapItem(Ic.hist, tr(R.string.hermes_will_memory), tr(R.string.cap_memory_no), false)
        }
        PageSection(tr(R.string.set_modes)) {
            Item(state.modes.filter { it.brain == b.id }.joinToString(", ") { it.title }.ifEmpty { tr(R.string.set_none) }, tr(R.string.brain_modes_sub), icon = Ic.list)
        }
        if (has) PageSection(null) {
            Item(tr(R.string.hermes_disconnect), tr(R.string.brain_off_sub), icon = Ic.del) { app.setKey(b.id, null) }
        }
    }
}

/** Ключ в карточке «Подключение» (ui.js, .kedit): поле, «Вставить», где взять, ошибка — строкой; «Отмена» и «Подключить». */
@Composable
private fun KeyInline(ctl: KeyCtl, onCancel: (() -> Unit)?, onConnect: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    val bad = ctl.status?.first == false
    Column(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 12.dp)) {
        Field(ctl.key, tr(R.string.key_ph), bad = bad, paste = { clipboard.getText()?.text?.trim()?.let { ctl.key = it } }) { ctl.key = it.trim() }
        Text(if (bad) ctl.status!!.second else tr(R.string.key_where), fontSize = 12.5.sp, color = if (bad) Ink.danger else Ink.soft, modifier = Modifier.padding(start = 2.dp, top = 6.dp))
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
            onCancel?.let { TextButton(tr(R.string.ui_cancel), onClick = it) }
            PrimaryButton(if (ctl.checking) tr(R.string.key_connecting) else tr(R.string.hermes_connect), enabled = ctl.canCheck, onClick = onConnect)
        }
    }
}

/** actionsPage(): баннер «нет доступа», группы с политикой справа и состоянием доступа, экран блокировки. */
@Composable
fun ActionsPage(a: MainActivity) {
    val app = App.instance
    val state by app.conversation.state.collectAsState()
    @Suppress("UNUSED_VARIABLE") val r = a.resumed.intValue // перечитать доступы после системных окон
    TopBar(tr(R.string.set_actions), tr(R.string.actions_subtitle), onBack = a::back)
    ScrollPage {
        val blocked = Registry.all.filter { Registry.policy(state.settings, it.id) != Policy.OFF && it.special != null && Registry.missing(a, it).isNotEmpty() }
        if (blocked.isNotEmpty()) {
            val t = blocked.first()
            Banner(tr(R.string.actions_blocked, t.title, Registry.specialTitle(t.special!!)),
                actions = listOf(tr(R.string.actions_fix) to { Phone.openDndAccess(a); Unit }))
        }
        Registry.groups.forEach { g ->
            Section(g)
            Registry.all.filter { it.group == g }.forEach { t ->
                val p = Registry.policy(state.settings, t.id)
                val miss = Registry.missing(a, t)
                // подпись — одной строкой: что входит в действие; нет доступа — что сделать
                val sub = when {
                    p == Policy.OFF || miss.isEmpty() -> t.sub
                    t.special != null -> tr(R.string.actions_row_denied)
                    else -> tr(R.string.actions_row_later)
                }
                Item(t.title, sub, trailing = policyWord(p), trailingColor = when (p) { Policy.AUTO -> Ink.soft; Policy.ASK -> Ink.local; Policy.OFF -> Ink.danger },
                    icon = t.icon, minHeight = 52.dp) {
                    val opts = (if (t.alwaysAsk) emptyList() else listOf("AUTO" to tr(R.string.actions_policy_auto))) + listOf("ASK" to tr(R.string.actions_policy_ask), "OFF" to tr(R.string.actions_policy_off))
                    a.choose(MainActivity.ChoiceDialog(t.title, opts, p.name) { v ->
                        app.conversation.updateSettings { it.copy(policy = it.policy + (t.id to Policy.valueOf(v))) }
                    })
                }
            }
        }
        Section(tr(R.string.actions_lock_h))
        Item(tr(R.string.actions_lock_sub), lockLabel(state.settings.lockScope), icon = Ic.lock) {
            a.choose(MainActivity.ChoiceDialog(tr(R.string.actions_lock_sub), LockScope.entries.map { it.name to lockLabel(it) }, state.settings.lockScope.name) { v ->
                app.conversation.updateSettings { it.copy(lockScope = LockScope.valueOf(v)) }
            })
        }
        MNote(tr(R.string.actions_note),
            Modifier.padding(start = 22.dp, end = 22.dp, top = 4.dp, bottom = 20.dp))
    }
}

/** Состояние страницы Hermes: адрес, ключ, проверка, итог. Снаружи Composable — чтобы «Подключить» из приглашения и из поля шли одним путём. */
class HermesCtl {
    var url by mutableStateOf("")
    var token by mutableStateOf("")
    var manual by mutableStateOf(false)
    var checking by mutableStateOf(false)
    var status by mutableStateOf<Pair<Boolean, String>?>(null)
    val canConnect get() = !checking && url.length > 8 && token.length > 10

    fun connect(scope: CoroutineScope, link: HermesLink, save: Boolean, then: () -> Unit = {}) {
        val app = App.instance
        checking = true; status = null
        scope.launch {
            val r = withContext(Dispatchers.IO) { app.hermesBrain.check(link) }
            checking = false
            status = r.ok to r.text
            if (r.ok && save) { app.setHermes(link); token = ""; url = ""; manual = false; then() }
        }
    }
}

/**
 * Hermes (ui.js, hermesPage): подключение к своему серверу через ссылку-приглашение или адрес и ключ телефона (Архитектура 9.8);
 * подключён — адрес, ключ, «Умеет», режимы на нём, «Отключить». Честно: действий телефона у Hermes нет (9.0).
 */
@Composable
fun HermesPage(a: MainActivity) {
    val app = App.instance
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val state by app.conversation.state.collectAsState()
    val keyV by app.keyVersion.collectAsState()
    val link = remember(keyV, state.settings.hermesUrl) { app.hermesLink() }
    val ctl = remember { HermesCtl() }
    TopBar("Hermes", tr(if (link != null) R.string.hermes_subtitle_on else R.string.hermes_subtitle), onBack = a::back)
    FormPage {
        // Ссылку vahter://connect может открыть любая страница в браузере: приглашение только показываем — адрес виден,
        // подключаем (или заменяем текущее подключение) лишь по нажатию.
        a.invite?.let { inv -> InviteOffer(inv, link) { a.invite = null } }
        if (link == null) {
            H2(tr(R.string.hermes_h))
            Text(tr(R.string.hermes_about_1) + tr(R.string.hermes_about_2), color = Ink.soft)
            Column {
                Text(tr(R.string.hermes_pair_h), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Ink.serverD, modifier = Modifier.padding(vertical = 4.dp))
                SetCard {
                    Item(tr(R.string.hermes_pair_tg), tr(R.string.hermes_pair_tg_sub), icon = Ic.chat)
                    Item(tr(R.string.hermes_paste_invite), tr(R.string.hermes_paste_invite_sub), icon = Ic.paste) {
                        val inv = HermesBrain.parseInvite(clipboard.getText()?.text.orEmpty())
                        if (inv != null) ctl.connect(scope, inv, save = true) else ctl.status = false to tr(R.string.hermes_paste_bad)
                    }
                    Item(tr(R.string.hermes_pair_addr), tr(R.string.hermes_pair_addr_sub), icon = Ic.pen) { ctl.manual = true }
                }
            }
            if (ctl.manual) Column {
                Text(tr(R.string.hermes_addr_h), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Ink.serverD, modifier = Modifier.padding(vertical = 4.dp))
                Field(ctl.url, tr(R.string.hermes_addr_ph), bad = ctl.status?.first == false && ctl.url.isNotEmpty() && !ctl.url.startsWith("https://")) { ctl.url = it.trim() }
                Spacer(Modifier.height(8.dp))
                Field(ctl.token, tr(R.string.hermes_token_ph), paste = { clipboard.getText()?.text?.trim()?.let { t -> HermesBrain.parseInvite(t)?.let { ctl.url = it.url; ctl.token = it.token } ?: run { ctl.token = t } } }) { ctl.token = it.trim() }
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                    PrimaryButton(if (ctl.checking) tr(R.string.key_checking) else tr(R.string.hermes_connect), enabled = ctl.canConnect) {
                        ctl.connect(scope, HermesLink(ctl.url.trimEnd('/'), ctl.token), save = true)
                    }
                }
            }
            StatusLine(ctl.checking, ctl.status, tr(R.string.key_ok, "Hermes"))
        } else {
            // тот же шаблон, что у DeepSeek: «Подключение» (статус сам при входе), «Умеет», «Режимы», «Отключить»
            LaunchedEffect(link) { ctl.connect(scope, link, save = false) }
            PageSection(tr(R.string.conn_section)) {
                when (val st = ctl.status) {
                    null -> StatusItem(null, tr(R.string.conn_checking))
                    else -> if (ctl.checking) StatusItem(null, tr(R.string.conn_checking)) else StatusItem(st.first, tr(if (st.first) R.string.conn_ok else R.string.conn_bad, st.second))
                }
                Item(tr(R.string.set_brain_url), HermesBrain.host(link.url), icon = Ic.srv)
                Item(tr(R.string.hermes_token_stored), tr(R.string.hermes_token_stored_sub), icon = Ic.lock)
            }
        }
        if (link != null) {
            PageSection(tr(R.string.hermes_will_h)) {
                CapItem(Ic.hist, tr(R.string.hermes_will_memory), tr(R.string.hermes_will_memory_sub), true)
                CapItem(Ic.lens, tr(R.string.hermes_will_search), tr(R.string.hermes_will_search_sub), true)
                CapItem(Ic.srv, tr(R.string.hermes_will_server), tr(R.string.hermes_will_server_sub), true)
                CapItem(Ic.cam, tr(R.string.cap_photo), tr(R.string.cap_photo_hermes), true)
                CapItem(Ic.shield, tr(R.string.set_cap_tools), tr(R.string.hermes_will_phone), false)
            }
            PageSection(tr(R.string.set_modes)) {
                Item(state.modes.filter { it.brain == Brains.hermes.id }.joinToString(", ") { it.title }.ifEmpty { tr(R.string.set_none) }, tr(R.string.brain_modes_sub), icon = Ic.list)
            }
            PageSection(null) { Item(tr(R.string.hermes_disconnect), tr(R.string.hermes_disconnect_sub), icon = Ic.del) { app.setHermes(null); ctl.status = null } }
        } else Column {
            Text(tr(R.string.hermes_will_h), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Ink.serverD, modifier = Modifier.padding(vertical = 4.dp))
            SetCard {
                Item(tr(R.string.hermes_will_memory), tr(R.string.hermes_will_memory_sub), icon = Ic.hist)
                Item(tr(R.string.hermes_will_search), tr(R.string.hermes_will_search_sub), icon = Ic.lens)
                Item(tr(R.string.hermes_will_server), tr(R.string.hermes_will_server_sub), icon = Ic.srv)
                Item(tr(R.string.set_cap_tools), tr(R.string.hermes_will_phone), icon = Ic.shield)
            }
        }
    }
}

/** Приглашение из ссылки: адрес сервера, что будет с текущим подключением; «Отмена» и «Подключить» / «Заменить». */
@Composable
private fun InviteOffer(inv: HermesLink, current: HermesLink?, done: () -> Unit) {
    val scope = rememberCoroutineScope()
    val ctl = remember(inv) { HermesCtl() }
    val replaces = current != null && current != inv
    PageSection(tr(R.string.hermes_invite_h)) {
        Item(HermesBrain.host(inv.url), if (replaces) tr(R.string.hermes_invite_replace, HermesBrain.host(current!!.url)) else tr(R.string.hermes_invite_sub), icon = Ic.srv)
        Column(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 12.dp)) {
            StatusLine(ctl.checking, ctl.status?.takeIf { !it.first }, "")
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                TextButton(tr(R.string.ui_cancel), onClick = done)
                PrimaryButton(tr(if (replaces) R.string.hermes_replace else R.string.hermes_connect), enabled = !ctl.checking) { ctl.connect(scope, inv, save = true, then = done) }
            }
        }
    }
}

/** Поле ввода в стиле ключа (KeyEditor): рамка, подсказка, необязательная кнопка «Вставить». */
@Composable
private fun Field(value: String, hint: String, bad: Boolean = false, paste: (() -> Unit)? = null, onChange: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color.White).border(1.5.dp, if (bad) Ink.danger else Ink.ink, RoundedCornerShape(12.dp))
        .padding(start = 12.dp, end = if (paste == null) 12.dp else 0.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f).padding(vertical = 12.dp)) {
            if (value.isEmpty()) Text(hint, color = Ink.faint)
            BasicTextField(value, onChange, singleLine = true, textStyle = BaseText, cursorBrush = SolidColor(Ink.ink), modifier = Modifier.fillMaxWidth())
        }
        if (paste != null) IconBtn(Ic.paste, tr(R.string.key_paste), tint = Ink.soft, onClick = paste)
    }
}


private fun policyWord(p: Policy) = when (p) { Policy.AUTO -> tr(R.string.actions_policy_auto); Policy.ASK -> tr(R.string.actions_policy_ask); Policy.OFF -> tr(R.string.actions_policy_off) }

/** Строка «Как позвать Вахтёра»: заголовок, подпись, предупреждение (ui.js, callStatus). */
private fun callStatus(a: MainActivity, role: Boolean, probe: Boolean?): Triple<String, String, Boolean> {
    val side = SideKey.read(a)
    return when {
        !role -> Triple(tr(R.string.set_assistant_role), tr(R.string.call_role_busy), true)
        side == SideKey.State.ASSISTANT -> Triple(tr(R.string.set_side_key), tr(R.string.call_ok), false)
        side != SideKey.State.UNKNOWN -> Triple(tr(R.string.set_side_key), tr(R.string.call_busy, SideKey.now(a)), true)
        probe == true -> Triple(tr(R.string.call_key), tr(R.string.call_ok), false)
        probe == false -> Triple(tr(R.string.call_gesture), tr(R.string.call_gesture_sub), false)
        else -> Triple(tr(R.string.call_key), tr(R.string.call_check), false)
    }
}

/** «Как позвать Вахтёра» (ui.js, callPage): всё про встраивание в систему — подробно, сюда заходят редко. */
@Composable
fun CallPage(a: MainActivity) {
    val app = App.instance
    val state by app.conversation.state.collectAsState()
    @Suppress("UNUSED_VARIABLE") val r = a.resumed.intValue   // вернулись из системных настроек — перечитать
    val role = a.getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_ASSISTANT)
    val side = SideKey.read(a)
    val known = side != SideKey.State.UNKNOWN
    val probe = state.settings.sideProbe
    var tileAdded by remember { mutableStateOf(false) }
    val sheetUp by Sheet.visible.collectAsState()
    LaunchedEffect(sheetUp) { if (sheetUp && !known) app.conversation.updateSettings { it.copy(sideProbe = true) } }   // лист открылся, пока здесь, — кнопка работает
    TopBar(tr(R.string.set_call), onBack = a::back)
    FormPage {
        Column {
            Text(tr(R.string.call_section), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Ink.serverD, modifier = Modifier.padding(vertical = 4.dp))
            SetCard {
                Item(tr(R.string.set_assistant_role), if (role) tr(R.string.app_name) else tr(R.string.sidekey_other_assistant), warn = !role, icon = Ic.spark) {
                    a.startActivity(Intent(AndroidSettings.ACTION_VOICE_INPUT_SETTINGS))
                }
                if (known) Item(tr(R.string.set_side_key), if (side == SideKey.State.ASSISTANT) tr(R.string.call_ok) else SideKey.now(a), warn = side != SideKey.State.ASSISTANT, icon = Ic.btn) { SideKey.open(a) }
                else Item(tr(R.string.set_call_way), tr(R.string.sidekey_call_any), icon = Ic.btn)
                Item(tr(R.string.sidekey_tile), tr(if (tileAdded) R.string.sidekey_tile_done else R.string.sidekey_tile_sub), icon = Ic.list) {
                    if (!tileAdded) VahterTile.request(a) { tileAdded = it }
                }
            }
            MNote(tr(if (known) R.string.call_note_samsung else R.string.call_note_other))
        }
        if (!known) Column {
            Text(tr(R.string.call_check_h), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Ink.serverD, modifier = Modifier.padding(vertical = 4.dp))
            SetCard {
                Column(Modifier.fillMaxWidth().padding(start = 22.dp, end = 12.dp, top = 12.dp, bottom = 4.dp)) {
                    Text(tr(when (probe) { true -> R.string.call_probe_yes; false -> R.string.call_probe_no; null -> R.string.sidekey_probe }), fontSize = 15.sp, color = Ink.ink)
                    if (probe == null) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End)) {
                        TextButton(tr(R.string.ui_no)) { app.conversation.updateSettings { it.copy(sideProbe = false) } }
                        TextButton(tr(R.string.ui_yes)) { app.conversation.updateSettings { it.copy(sideProbe = true) } }
                    } else Box(Modifier.padding(bottom = 8.dp))
                }
            }
        }
    }
}

/** «О программе» (ui.js, about): значок, версия, обновление — как в Docsy (update/Updates): проверка при входе, «Обновить», процент, установка. */
@Composable
fun AboutPage(a: MainActivity) {
    val ver = a.packageManager.getPackageInfo(a.packageName, 0).versionName ?: ""
    val up by Updates.state.collectAsState()
    LaunchedEffect(Unit) { Updates.checkNow(a) }
    TopBar(tr(R.string.set_about), onBack = a::back)
    FormPage {
        Column(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            OursIcon(64.dp, 20.dp)
            Text(ver, fontSize = 24.sp, fontWeight = FontWeight.Medium, color = Ink.ink, modifier = Modifier.padding(top = 10.dp))
            Text(when (up) {
                is Updates.State.Checking -> tr(R.string.upd_checking)
                is Updates.State.UpToDate -> tr(R.string.upd_latest)
                is Updates.State.CheckFailed -> tr(R.string.upd_failed)
                else -> tr(R.string.about_stable)
            }, fontSize = 13.sp, color = Ink.soft)
        }
        when (val st = up) {
            is Updates.State.Available -> PageSection(tr(R.string.upd_available, st.r.version)) {
                if (st.r.notes.isNotBlank()) Text(st.r.notes, fontSize = 14.sp, color = Ink.ink, modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp))
                Row(Modifier.fillMaxWidth().padding(end = 12.dp, bottom = 8.dp), horizontalArrangement = Arrangement.End) { PrimaryButton(tr(R.string.upd_now)) { Updates.download(a) } }
            }
            is Updates.State.Downloading -> PageSection(tr(R.string.upd_downloading, st.r.version, st.pct)) {
                Column(Modifier.padding(horizontal = 18.dp, vertical = 12.dp)) {
                    Progress(st.pct)
                    Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.End) { TextButton(tr(R.string.ui_cancel)) { Updates.cancel() } }
                }
            }
            is Updates.State.ReadyToInstall -> PageSection(tr(R.string.upd_available, st.r.version), if (st.needsPermission) tr(R.string.upd_need_perm) else null) {
                Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.End) { PrimaryButton(tr(R.string.upd_install)) { Updates.install(a) } }
            }
            is Updates.State.DownloadFailed -> PageSection(tr(R.string.upd_available, st.r.version), tr(if (st.badFile) R.string.upd_bad_file else R.string.upd_net_fail)) {
                Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.End) { PrimaryButton(tr(R.string.upd_retry)) { Updates.download(a) } }
            }
            else -> {}
        }
        PageSection(null) { Item(tr(R.string.upd_check), tr(R.string.upd_check_sub), icon = Ic.dl) { Updates.checkNow(a) } }
    }
}
