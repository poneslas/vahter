package ru.poneslas.vahter.app

import ru.poneslas.vahter.R
import ru.poneslas.vahter.tr
import android.Manifest
import ru.poneslas.vahter.ui.BaseText
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.painterResource
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.FlowRow
import ru.poneslas.vahter.ui.TryVoice
import ru.poneslas.vahter.ui.OursIcon
import ru.poneslas.vahter.ui.Ic
import androidx.compose.ui.unit.em
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import ru.poneslas.vahter.core.Primary
import ru.poneslas.vahter.core.Modes
import ru.poneslas.vahter.core.Mode
import kotlinx.coroutines.launch
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import ru.poneslas.vahter.App
import ru.poneslas.vahter.core.Brains
import ru.poneslas.vahter.pisar.model.ModelDownload
import ru.poneslas.vahter.ui.Ink
import android.app.role.RoleManager

/** Распознавание: скачать (≈240 МБ, с докачкой), прогрев, удалить. */
@Composable
fun VoicePage(onBack: () -> Unit) {
    val app = App.instance
    TopBar(tr(R.string.set_voice_title), tr(R.string.voice_subtitle), onBack = onBack)
    ScrollPage { VoiceBlock() }
}

/** Скачивание голоса — как шаг «Модель» у Гига Писаря для Android: одна полоса, «Попробуйте здесь» в конце. */
@Composable
fun VoiceBlock() {
    val app = App.instance
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val state by ModelDownload.state.collectAsState()
    val installed by app.voice.installed.collectAsState()
    val running = state as? ModelDownload.State.Running
    val error = (state as? ModelDownload.State.Failed)?.reason
    Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        when {
            installed -> {
                Text(tr(R.string.voice_ready_h), fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Ink.ink)
                Text(tr(R.string.voice_ready_p), fontSize = 15.sp, color = Ink.soft)
                ru.poneslas.vahter.ui.TryVoice()
            }
            running != null -> {
                Text(tr(R.string.voice_about), fontSize = 15.sp, color = Ink.soft)
                Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(Ink.line)) {
                    Box(Modifier.fillMaxWidth(running.percent / 100f).height(8.dp).clip(RoundedCornerShape(4.dp)).background(Ink.ink))
                }
                Text(tr(R.string.app_voice_loading, running.percent), fontSize = 14.sp, color = Ink.ink)
            }
            else -> {
                Text(tr(R.string.voice_about), fontSize = 15.sp, color = Ink.soft)
                PrimaryButton(if (error == null) tr(R.string.voice_download) else tr(R.string.voice_retry)) { ModelDownload.start(ctx) }
                if (error != null) Text(tr(R.string.voice_error, error), fontSize = 14.sp, color = Ink.danger)
            }
        }
    }
}

/** Страница режима (ui.js, modePage): название, значок, промпт с диктовкой, подсказки, что принимает, главное, действия, мозг. */
@Composable
fun ModePage(activity: MainActivity, id: String) {
    val app = App.instance
    val conv = app.conversation
    val state by conv.state.collectAsState()
    val m = state.modes.firstOrNull { it.id == id } ?: return
    val main = m.id == Modes.MAIN
    // Встроенные режимы только включаются и выключаются (Бэклог №14): остальное — для просмотра.
    val ro = m.builtin
    val ed: ((Mode) -> Mode) -> Unit = { f -> conv.editMode(m.id, f) }
    TopBar(m.title, if (m.builtin) tr(R.string.mode_builtin) else tr(R.string.mode_custom_id, m.id), onBack = activity::back, titleIcon = Ic.mode(m.icon))
    FormPage {
        if (ro) Note(tr(R.string.mode_builtin_note))
        if (!ro) {
            Field(tr(R.string.mode_title), m.title, tr(R.string.mode_title_hint)) { v -> ed { it.copy(title = v) } }
            Column {
                Lh(tr(R.string.mode_icon))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    MODE_ICONS.forEach { k ->
                        val on = m.icon == k
                        Box(Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)).background(if (on) Ink.ink else Color.White).border(1.dp, if (on) Ink.ink else Ink.line, RoundedCornerShape(12.dp))
                            .clickable { ed { it.copy(icon = k) } }, contentAlignment = Alignment.Center) {
                            Icon(painterResource(Ic.mode(k)), k, Modifier.size(20.dp), tint = if (on) Color.White else Ink.soft)
                        }
                    }
                }
            }
        }
        Column {
            // Промпт меняется у всех режимов. У встроенных — с «Вернуть как было»; у Ассистента — «свои правила» поверх служебных.
            Lh(tr(if (main) R.string.mode_rules else R.string.mode_prompt))
            if (main) MNote(tr(R.string.mode_rules_note))
            Area(m.prompt, tr(if (main) R.string.mode_rules_hint else R.string.mode_prompt_hint)) { v -> ed { it.copy(prompt = v, promptEdited = true) } }
            Dictate { text -> ed { it.copy(prompt = (it.prompt.trim() + " " + text).trim(), promptEdited = true) } }
            val def = Modes.builtin.firstOrNull { it.id == m.id }?.prompt
            if (ro && !main && def != null && m.prompt != def) Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                TextButton(tr(R.string.mode_prompt_reset)) { ed { it.copy(prompt = def, promptEdited = false) } }
            }
        }
        if (!ro) {
            Field(tr(R.string.mode_empty_label), m.hint, tr(R.string.mode_empty_hint)) { v -> ed { it.copy(hint = v) } }
            Field(tr(R.string.mode_ph_label), m.placeholder, tr(R.string.mode_whatsthis_ph)) { v -> ed { it.copy(placeholder = v) } }
        }
        Column {
            Lh(tr(R.string.mode_accepts))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                MChip(tr(R.string.set_voice), m.accepts.voice, Ic.mic, onClick = if (ro) null else ({ ed { it.copy(accepts = it.accepts.copy(voice = !it.accepts.voice)) } }))
                MChip(tr(R.string.mode_accepts_text), true, Ic.kbd)
                MChip(tr(R.string.set_cap_vision), m.accepts.photo, Ic.cam, onClick = if (ro) null else ({ ed { it.copy(accepts = it.accepts.copy(photo = !it.accepts.photo), primary = if (it.accepts.photo) Primary.VOICE else it.primary) } }))
            }
        }
        if (m.accepts.photo && !main) Column {
            Lh(tr(R.string.mode_primary))
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Ink.chipBg).padding(3.dp)) {
                listOf(Primary.VOICE to (tr(R.string.set_voice) to Ic.mic), Primary.PHOTO to (tr(R.string.set_cap_vision) to Ic.cam)).forEach { (v, tl) ->
                    val on = m.primary == v
                    Row(Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(if (on) Color.White else Color.Transparent)
                        .clickable(enabled = !ro) { ed { it.copy(primary = v) } }.padding(9.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        Icon(painterResource(tl.second), null, Modifier.size(16.dp), tint = Ink.ink)
                        Spacer(Modifier.width(6.dp))
                        Text(tl.first, fontSize = 14.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal)
                    }
                }
            }
            MNote(if (m.primary == Primary.PHOTO) tr(R.string.mode_primary_photo_note)
                else tr(R.string.mode_primary_voice_note))
        }
        SetCard {
            if (main) Item(tr(R.string.set_cap_tools), tr(R.string.mode_tools_all), icon = Ic.shield)
            else if (ro) Item(tr(R.string.set_cap_tools), if (m.tools) tr(R.string.mode_tools_on) else tr(R.string.set_notif_off), icon = Ic.shield)
            else Item(tr(R.string.set_cap_tools), if (m.tools) tr(R.string.mode_tools_on) else tr(R.string.mode_tools_off),
                icon = Ic.shield, end = { Sw(m.tools) }) { ed { it.copy(tools = !it.tools) } }
            Item(tr(R.string.mode_lang), tr(R.string.mode_lang_ru), icon = Ic.wave)
        }
        Column {
            SetCard {
                Item(tr(R.string.key_h), Brains.byId(m.brain).title + if (m.accepts.photo) tr(R.string.mode_brain_needs_vision) else "", icon = Ic.spark)
            }
            MNote(tr(R.string.mode_brain_note))
        }
        if (!m.builtin) {
            var ask by remember { mutableStateOf(false) }
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color.White).border(1.dp, Color(0xFFE7C2BE), RoundedCornerShape(12.dp))
                .clickable { if (ask) { activity.back(); conv.deleteMode(m.id) } else ask = true }.padding(12.dp),
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(Ic.del), null, Modifier.size(18.dp), tint = Ink.danger)
                Spacer(Modifier.width(8.dp))
                Text(if (ask) tr(R.string.mode_delete_confirm) else tr(R.string.mode_delete), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Ink.danger)
            }
        }
    }
}

val MODE_ICONS = listOf("spark", "transl", "lens", "food", "cart", "flight", "book", "star", "edit")

/** .mform .lh */
@Composable
private fun Lh(t: String) = Text(t, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Ink.serverD, modifier = Modifier.padding(vertical = 4.dp))

/** .fl: подпись 12.5/600 синим, поле .fin — рамка 1.5, скругление 12; в фокусе рамка тёмная. */
@Composable
private fun Field(label: String, value: String, placeholder: String, onChange: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = Ink.brain)
        Input(value, placeholder, 14.sp, minHeight = 0.dp, onChange = onChange)
    }
}

/** .fieldarea: многострочное поле промпта. */
@Composable
private fun Area(value: String, placeholder: String, onChange: (String) -> Unit) = Input(value, placeholder, 13.5.sp, minHeight = 60.dp, onChange = onChange)

@Composable
private fun Input(value: String, placeholder: String, size: androidx.compose.ui.unit.TextUnit, minHeight: androidx.compose.ui.unit.Dp, onChange: (String) -> Unit) {
    var focus by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth().heightIn(min = minHeight).clip(RoundedCornerShape(12.dp)).background(Color.White)
        .border(1.5.dp, if (focus) Ink.ink else Ink.line, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 10.dp)) {
        if (value.isEmpty()) Text(placeholder, color = Ink.faint, fontSize = size)
        BasicTextField(value, onChange, textStyle = BaseText.copy(fontSize = size), cursorBrush = SolidColor(Ink.ink),
            modifier = Modifier.fillMaxWidth().onFocusChanged { focus = it.isFocused })
    }
}

@Composable
private fun Dictate(onText: (String) -> Unit) {
    // Та же кнопка Писаря, что в листе: зажать и говорить, вверх — без рук; текст дописывается в конец.
    var msg by remember { mutableStateOf<String?>(null) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    Column(Modifier.padding(start = 16.dp, end = 4.dp)) {
        ru.poneslas.vahter.ui.VoiceRow(
            voice = App.instance.voice,
            field = { Text(msg ?: tr(R.string.mode_dictate_hint), fontSize = 13.sp, color = Ink.soft) },
            trailing = null,
            onText = { msg = null; onText(it) },
            onMessage = { msg = it },
            onNeedMic = { (ctx as? android.app.Activity)?.requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1) },
        )
    }
}

/**
 * Первый запуск — один мастер (ui.js, onb; Продукт 5.13): каждый экран сначала объясняет живой сценой (ui/Intro.kt),
 * а под ней сразу настраивает то, о чём рассказал. Семь шагов: приветствие → голос → кнопка (пока качается модель) →
 * проба голоса → «Своих знает в лицо» → «Знает, кто зачем пришёл» → мозг. Финал — лист (MainActivity.finishOnboarding).
 * Ничего не блокирует, но кнопку не пропустить, не заметив: пока не настроено — «Настроить», «Позже» — через предупреждение.
 */
@Composable
fun Onboarding(activity: MainActivity, start: Int = 0, onDone: () -> Unit) {
    var step by androidx.compose.runtime.saveable.rememberSaveable(start) { mutableIntStateOf(start) }
    val app = App.instance
    val ctx = activity
    val keyV by app.keyVersion.collectAsState()
    val installed by app.voice.installed.collectAsState()
    val dl by ModelDownload.state.collectAsState()
    @Suppress("UNUSED_VARIABLE") val r = activity.resumed.intValue // баг №1: перечитать после возврата из системных окон
    val steps = 7
    val running = dl as? ModelDownload.State.Running
    // Голос не качали — пробовать нечем: с кнопки сразу к режимам.
    val next: () -> Unit = { if (step >= steps - 1) onDone() else step += if (step == 2 && !installed && running == null) 2 else 1 }
    val mic = activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    val role = activity.getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_ASSISTANT)
    val hasKey = remember(keyV) { app.hasKey(Brains.deepseek.id) }
    var btnWarned by remember { mutableStateOf(false) }
    // Незнакомый телефон: проба «зажмите кнопку» — null (не пробовали), true (лист открылся), false (нет).
    var probe by remember { mutableStateOf<Boolean?>(null) }
    var tileAdded by remember { mutableStateOf(false) }
    val sheetUp by ru.poneslas.vahter.assistant.Sheet.visible.collectAsState()
    LaunchedEffect(sheetUp) { if (sheetUp && step == 2) probe = true }   // лист открылся, пока идёт проба, — значит, кнопка работает
    val key = rememberKeyCtl(Brains.deepseek)
    val scope = rememberCoroutineScope()

    var skip: Pair<String, () -> Unit>? = tr(R.string.ui_later) to next
    var primary: Pair<String, () -> Unit>? = tr(R.string.ui_next) to next

    // Назад по мастеру: системный «Назад» и свайп вправо; свайп влево — вперёд, только если на экране уже можно «Далее».
    // На пробе голоса свайпа нет: у кнопки микрофона жест «влево — отмена». Голос не качали — проба пропускается и назад.
    val back: () -> Unit = { if (step > 0) { step -= if (step == 4 && !installed && running == null) 2 else 1; key.status = null } }
    androidx.activity.compose.BackHandler(step > 0) { back() }
    val canNext = androidx.compose.runtime.rememberUpdatedState { primary?.second === next }
    val swipeBack = androidx.compose.runtime.rememberUpdatedState(back)
    val swipeNext = androidx.compose.runtime.rememberUpdatedState(next)
    val swipe = if (step == 3) Modifier else Modifier.pointerInput(step) {
        var dx = 0f
        detectHorizontalDragGestures(onDragStart = { dx = 0f }, onHorizontalDrag = { _, d -> dx += d }, onDragEnd = {
            val px = 60.dp.toPx()
            if (dx > px) swipeBack.value() else if (dx < -px && canNext.value()) swipeNext.value()
        })
    }
    Column(Modifier.fillMaxSize().then(swipe).statusBarsPadding().navigationBarsPadding().padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 22.dp)) {
        @Composable fun Art(k: ru.poneslas.vahter.ui.WizScene, minH: androidx.compose.ui.unit.Dp = 300.dp) =
            Box(Modifier.weight(1f).fillMaxWidth().heightIn(min = minH), contentAlignment = Alignment.Center) { ru.poneslas.vahter.ui.WizArt(k) }
        @Composable fun Head(h: String, p: String) {
            Text(h, fontSize = 24.sp, lineHeight = 1.15.em, fontWeight = FontWeight.SemiBold, color = Ink.ink, modifier = Modifier.padding(top = 8.dp, bottom = 8.dp))
            Text(p, fontSize = 15.sp, color = Ink.soft, modifier = Modifier.padding(bottom = 12.dp))
        }
        when (step) {
            0 -> { Art(ru.poneslas.vahter.ui.WizScene.WELCOME); Head(tr(R.string.intro_0_h), tr(R.string.intro_0_p)); skip = null; primary = tr(R.string.ui_start) to next }
            1 -> {
                // Голос — первым: модель качается, пока настраивают кнопку.
                Box(Modifier.weight(1f).fillMaxWidth().heightIn(min = 120.dp), contentAlignment = Alignment.Center) { ru.poneslas.vahter.ui.VoiceArt() }
                Head(tr(R.string.wiz_voice_h), tr(R.string.wiz_voice_p, "225"))
                val error = (dl as? ModelDownload.State.Failed)?.reason
                when {
                    installed -> Note(tr(R.string.ui_done), ok = true)
                    running != null -> {
                        Progress(running.percent)
                        Text(tr(R.string.wiz_voice_progress, running.percent), fontSize = 13.sp, color = Ink.soft, modifier = Modifier.padding(top = 8.dp))
                    }
                    else -> {
                        if (error != null) Note(tr(R.string.voice_error, error), ok = false)
                        // «Скачать» — и сразу системный запрос микрофона, пока качается модель (Продукт 5.13).
                        primary = (if (error == null) tr(R.string.voice_download) else tr(R.string.wiz_voice_retry)) to {
                            ModelDownload.start(ctx); if (!mic && !app.conversation.state.value.settings.micAsked) activity.askMic(); Unit
                        }
                    }
                }
                if (installed || running != null) skip = null
                if (!mic && (installed || running != null)) SetCard(Modifier.padding(top = 12.dp)) {
                    Item(tr(R.string.set_mic), tr(R.string.wiz_voice_mic_sub), icon = Ic.mic) { activity.askMic() }
                }
            }
            2 -> {
                // Кнопка — один экран на все телефоны. Обязательна только роль «Цифровой помощник» (есть везде с Android 10).
                // Пункт Samsung «Цифровой помощник» открывает того, кто выбран помощником: если удержание уже на нём (по умолчанию
                // в One UI 7+), выбрали Вахтёра — кнопка заработала сама, вторая строка сразу с ✓. Иначе — «Настроить».
                // Незнакомый телефон (действие кнопки не прочитать): проба «зажмите кнопку», не вышло — «Домой» / жест и плитка в шторке.
                val side = SideKey.read(activity)
                val known = side != SideKey.State.UNKNOWN
                val sideOk = side == SideKey.State.ASSISTANT
                val ok = role && (!known || sideOk)
                val check: @Composable () -> Unit = { Text("✓", color = Ink.ok, fontWeight = FontWeight.Bold) }
                Art(ru.poneslas.vahter.ui.WizScene.QUEUE, 220.dp)
                Head(tr(R.string.intro_1_h), tr(when {
                    ok && known -> R.string.sidekey_p_done
                    ok && probe == true -> R.string.sidekey_p_done_probe
                    ok -> R.string.sidekey_p_done_nokey
                    sideOk -> R.string.sidekey_p_ready
                    known -> R.string.sidekey_p_todo
                    else -> R.string.sidekey_p_todo_one
                }))
                SetCard {
                    Item(tr(R.string.set_assistant_role), if (role) tr(R.string.app_name) else tr(R.string.sidekey_not_set, tr(R.string.sidekey_other_assistant)), warn = !role, icon = Ic.spark,
                        end = if (role) check else null) { activity.startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)) }
                    when {
                        known && sideOk -> Item(tr(R.string.set_side_key), tr(R.string.sidekey_ready), icon = Ic.btn, end = check) { SideKey.open(activity) }
                        known -> Item(tr(R.string.set_side_key), tr(R.string.sidekey_not_set, SideKey.now(activity)), warn = true, icon = Ic.btn) { SideKey.open(activity) }
                        !role -> Item(tr(R.string.set_call), tr(R.string.sidekey_call_any), icon = Ic.btn)
                        probe == true -> Item(tr(R.string.set_call), tr(R.string.sidekey_probe_ok), icon = Ic.btn, end = check)
                        probe == false -> {
                            Item(tr(R.string.set_call), tr(R.string.sidekey_call_home), icon = Ic.btn) { activity.startActivity(Intent(Settings.ACTION_SETTINGS)) }
                            if (tileAdded) Item(tr(R.string.sidekey_tile), tr(R.string.sidekey_tile_done), icon = Ic.list, end = check)
                            else Item(tr(R.string.sidekey_tile), tr(R.string.sidekey_tile_sub), icon = Ic.list) { ru.poneslas.vahter.assistant.VahterTile.request(activity) { tileAdded = it } }
                        }
                        else -> Column(Modifier.fillMaxWidth().padding(start = 22.dp, end = 12.dp, top = 12.dp, bottom = 4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(painterResource(Ic.btn), null, Modifier.size(24.dp).padding(2.dp), tint = Ink.soft)
                                Spacer(Modifier.width(16.dp))
                                Text(tr(R.string.sidekey_probe), fontSize = 15.sp, lineHeight = 1.35.em, color = Ink.ink)
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End)) {
                                TextButton(tr(R.string.ui_no)) { probe = false }
                                TextButton(tr(R.string.ui_yes)) { probe = true }
                            }
                        }
                    }
                }
                running?.let { Text(tr(R.string.wiz_dl_progress, it.percent) + if (ok) "" else tr(R.string.wiz_dl_hint), fontSize = 13.sp, color = Ink.soft, modifier = Modifier.padding(top = 10.dp)) }
                if (!ok) {
                    // «Настроить» открывает по очереди то, что ещё не настроено.
                    val setup = { if (!role) activity.startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)) else SideKey.open(activity) }
                    primary = tr(R.string.ui_setup) to setup
                    skip = tr(R.string.ui_later) to {
                        if (btnWarned) next() else {
                            btnWarned = true
                            activity.showSheet(MainActivity.PermSheet(tr(R.string.sidekey_warn_h), tr(R.string.sidekey_warn_p), tr(R.string.ui_setup), icon = Ic.power,
                                later = tr(R.string.sidekey_warn_later), onLater = next, onGo = setup))
                        }
                    }
                } else skip = null
            }
            3 -> { TryStep(activity, running?.percent, installed, mic); skip = null; if (!installed && running != null) primary = tr(R.string.ui_skip) to next }
            4 -> { Art(ru.poneslas.vahter.ui.WizScene.CHAT); Head(tr(R.string.wiz_rules_h), tr(R.string.wiz_rules_p)); skip = null }
            5 -> { Art(ru.poneslas.vahter.ui.WizScene.KEYS); Head(tr(R.string.intro_3_h), tr(R.string.wiz_quick_p)); skip = null }
            else -> {
                // Мозг — последним. «Подключить» — главная кнопка внизу; подключили — «Открыть» ведёт в лист.
                // Уведомления — здесь же, пока экран приложения открыт: из листа системное окно показать нечем.
                // Как остальные шаги: сцена — в растягиваемой области (сжимается, если внизу стало больше), заголовок и ключ — внизу.
                androidx.compose.foundation.layout.BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(bottom = 10.dp), contentAlignment = Alignment.Center) {
                    val k = (maxHeight / 300.dp).coerceIn(.4f, 1f)
                    Box(Modifier.scale(k)) { ru.poneslas.vahter.ui.WizArt(ru.poneslas.vahter.ui.WizScene.BRAIN) }
                }
                Head(tr(R.string.wiz_brain_h), tr(R.string.wiz_brain_p))
                KeyEditor(Brains.deepseek, onboarding = true, ctl = key)
                val notif = activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                if (hasKey && !notif) SetCard(Modifier.padding(top = 8.dp)) {
                    Item(tr(R.string.set_notif), tr(R.string.wiz_notif_sub), icon = Ic.bell) { activity.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2) }
                }
                if (hasKey) { skip = null; primary = tr(R.string.ui_open) to onDone }
                else primary = (if (key.checking) tr(R.string.key_connecting) else tr(R.string.ui_connect)) to { key.connect(scope) }
            }
        }
        // .nav: «Позже» или пусто · точки · кнопка. Точки — строго по центру экрана, как в прототипе; кнопка справа берёт
        // сколько нужно и не ломает слово при крупном шрифте.
        Box(Modifier.fillMaxWidth().padding(top = 14.dp)) {
            Box(Modifier.align(Alignment.CenterStart)) { skip?.let { (t, a) -> TextButton(t, onClick = a) } }
            Row(Modifier.align(Alignment.Center), horizontalArrangement = Arrangement.spacedBy(if (androidx.compose.ui.platform.LocalDensity.current.fontScale > 1.15f) 4.dp else 6.dp)) {
                repeat(steps) { i -> Box(Modifier.size(if (i == step) 20.dp else 7.dp, 7.dp).clip(RoundedCornerShape(4.dp)).background(if (i == step) Ink.ink else Ink.swOff)) }
            }
            Box(Modifier.align(Alignment.CenterEnd)) {
                primary?.let { (t, a) -> PrimaryButton(t, enabled = !(step == steps - 1 && !hasKey && !key.canCheck), onClick = a) }
            }
        }
    }
}

/** Одна диктовка на пробе: текст и сколько ждали распознавания после отпускания. */
private data class Shot(val text: String, val ms: Long)

/**
 * Проба голоса (ui.js, tryStep). Герой экрана — эквалайзер Вахтёра ([TryEq]). Пока распознавание качается, его столбики и есть
 * полоска загрузки: готовые шевелятся, остальные — точки, поперёк — шов («рот зашит»); микрофон не работает, справа «Пропустить».
 * Докачалось — шов лопается, столбики оживают, кнопка один раз пульсирует. Дальше фраза — крупно прямо на фоне, без поля и подложки,
 * «⚡ Распознано на телефоне за 0,7 с»; прошлые бледнеют выше. Внизу — одна тихая строка жестов у той же кнопки, что в листе
 * (кнопка справа: при записи появляется полоса «‹ ‹ Отмена», кнопка не должна прыгать из-под пальца). Мозгу ничего не уходит.
 */
@Composable
private fun androidx.compose.foundation.layout.ColumnScope.TryStep(activity: MainActivity, pct: Int?, installed: Boolean, mic: Boolean) {
    val app = App.instance
    val shots = remember { androidx.compose.runtime.mutableStateListOf<Shot>() }
    var msg by remember { mutableStateOf<String?>(null) }
    val phase by ru.poneslas.vahter.ui.RecUi.phase.collectAsState()
    // Время от отпускания (начало распознавания) до текста — его и показываем.
    var t0 by remember { mutableStateOf(0L) }
    LaunchedEffect(phase) { if (phase == ru.poneslas.vahter.ui.RecUi.Phase.PROCESSING) t0 = android.os.SystemClock.uptimeMillis() }
    // Докачалось, пока человек на этом экране, — шов лопается (один раз).
    val freed = remember { androidx.compose.animation.core.Animatable(1f) }
    var wasLoading by remember { mutableStateOf(!installed) }
    LaunchedEffect(installed) {
        if (installed && wasLoading) { freed.snapTo(0f); freed.animateTo(1f, androidx.compose.animation.core.tween(1600)) }
        wasLoading = !installed
    }
    val micAsked = app.conversation.state.collectAsState().value.settings.micAsked
    val loading = !installed && pct != null
    val why = msg ?: when {
        !installed && pct == null -> tr(R.string.try_none)
        !mic && micAsked && !activity.shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) -> tr(R.string.try_denied)
        !mic -> tr(R.string.try_ask)
        else -> null
    }
    val rec = phase == ru.poneslas.vahter.ui.RecUi.Phase.LISTENING
    val proc = phase == ru.poneslas.vahter.ui.RecUi.Phase.PROCESSING
    Text(tr(R.string.try_h), fontSize = 24.sp, lineHeight = 1.15.em, fontWeight = FontWeight.SemiBold, color = Ink.ink, modifier = Modifier.padding(top = 26.dp, bottom = 8.dp))
    Text(when { loading -> tr(R.string.try_p_loading); !installed -> tr(R.string.try_p_none); else -> tr(R.string.try_p_ready) }, fontSize = 15.sp, color = Ink.soft, modifier = Modifier.padding(bottom = 8.dp))
    // сцена без подложки: прошлые фразы — сверху, бледно; эквалайзер; фраза крупно
    Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        val old = if (rec || proc || loading) emptyList() else shots.dropLast(1).takeLast(3)
        if (old.isNotEmpty()) {
            Column(Modifier.fillMaxWidth().weight(1f, fill = false).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                old.forEachIndexed { i, sh -> Text(sh.text, fontSize = 14.sp, lineHeight = 1.35.em, color = if (i == old.lastIndex) Ink.soft else Color(0xFFB4BAC4), maxLines = 2) }
            }
        }
        TryEq(loading = !installed, pct = pct ?: 0, active = rec, calm = proc, freed = freed.value)   // голоса нет — точки со швом
        if (loading) Text("${pct}\u00A0%", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Ink.localD, modifier = Modifier.padding(top = 8.dp))
        Column(Modifier.fillMaxWidth().heightIn(min = 96.dp).padding(top = 14.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val last = shots.lastOrNull()
            val center = androidx.compose.ui.text.style.TextAlign.Center
            when {
                loading -> Text(tr(R.string.try_learning), fontSize = 15.sp, color = Ink.soft, textAlign = center)
                rec -> Text(tr(R.string.ui_listening), fontSize = 15.sp, color = Ink.soft, textAlign = center)
                proc -> Text(tr(R.string.try_recog), fontSize = 15.sp, color = Ink.soft, textAlign = center)
                last != null -> {
                    Text(last.text, fontSize = 25.sp, lineHeight = 1.25.em, fontWeight = FontWeight.Medium, color = Ink.ink, textAlign = center)
                    Text(tr(R.string.try_fast, "%.1f".format(last.ms / 1000f).replace('.', ',')), fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = Ink.localD,
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(Ink.localT).padding(horizontal = 11.dp, vertical = 4.dp))
                }
                why != null -> Text(why, fontSize = 16.sp, lineHeight = 1.35.em, color = Ink.soft, textAlign = center)
                else -> Text(tr(R.string.try_say), fontSize = 22.sp, color = Color(0xFFB4BAC4), textAlign = center)
            }
        }
    }
    Box(Modifier.padding(top = 6.dp)) {
        ru.poneslas.vahter.ui.VoiceRow(
            voice = app.voice,
            // Поля нет: одна тихая строка жестов у кнопки.
            field = { Text(tr(R.string.try_tips), fontSize = 13.sp, color = Ink.faint, maxLines = 1, overflow = TextOverflow.Ellipsis,
                textAlign = androidx.compose.ui.text.style.TextAlign.End, modifier = Modifier.fillMaxWidth().padding(end = 14.dp)) },
            trailing = null,
            onText = { t -> shots += Shot(t, (android.os.SystemClock.uptimeMillis() - t0).coerceAtLeast(1)); msg = null },
            onMessage = { msg = it },
            onNeedMic = activity::askMic,
        )
        // докачалось — кнопка микрофона один раз пульсирует: «можно»
        if (freed.value < 1f) Box(Modifier.align(Alignment.CenterEnd).padding(end = 10.dp).size(52.dp).drawBehind {
            val k = freed.value
            drawCircle(Ink.local.copy(alpha = .65f * (1 - k)), radius = size.minDimension / 2 + 22.dp.toPx() * k, style = androidx.compose.ui.graphics.drawscope.Stroke(3.dp.toPx()))
        })
    }
}

/**
 * Эквалайзер-герой пробы голоса (intro.css, .teq): 13 столбиков. В загрузке — прогресс: готовые шевелятся, остальные — точки,
 * поперёк шов с крестиками. [freed] 0→1 сразу после докачки: крестики разлетаются, столбики подпрыгивают. Дальше — дышит,
 * при записи — прыгает ([active]), при распознавании — спокойная волна ([calm]).
 */
@Composable
private fun TryEq(loading: Boolean, pct: Int, active: Boolean, calm: Boolean, freed: Float) {
    val n = 13
    val tr = androidx.compose.animation.core.rememberInfiniteTransition(label = "teq")
    val t by tr.animateFloat(0f, 100f, androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(100_000, easing = androidx.compose.animation.core.LinearEasing)), label = "t")
    androidx.compose.foundation.Canvas(Modifier.size(230.dp, 84.dp)) {
        val bw = 9.dp.toPx(); val gap = 7.dp.toPx()
        val total = n * bw + (n - 1) * gap
        val x0 = (size.width - total) / 2; val cy = size.height / 2
        val on = Math.round(pct / 100f * n)
        val pop = if (freed < 1f) { val f = (freed / .4f).coerceIn(0f, 1f); .2f + .8f * f + .25f * kotlin.math.sin(f * Math.PI.toFloat()) } else 1f
        for (i in 0 until n) {
            val x = x0 + i * (bw + gap)
            val h = when {
                loading && i >= on -> bw
                loading -> (12f + 22f * (.5f + .5f * kotlin.math.sin((t / 1.1f - i * .07f / 1.1f) * 2 * Math.PI.toFloat()))).dp.toPx()
                active -> (10f + 50f * (.5f + .5f * kotlin.math.sin((t * 3.1f + i * .37f) * 2 * Math.PI.toFloat())) * (.6f + .4f * kotlin.math.sin((t * 1.7f + i) * 2 * Math.PI.toFloat()).let { it * it })).dp.toPx()
                calm -> (10f + 18f * (.5f + .5f * kotlin.math.sin((t / 1.6f - i * .09f) * 2 * Math.PI.toFloat()))).dp.toPx()
                else -> (10f + 10f * (.5f + .5f * kotlin.math.sin((t / 3.2f - i * .08f) * 2 * Math.PI.toFloat()))).dp.toPx() * pop
            }
            val r = androidx.compose.ui.geometry.CornerRadius(bw / 2)
            // градиент — на каждый столбик свой (янтарь сверху → синий снизу), как в прототипе
            if (loading && i >= on) drawRoundRect(Color(0xFFD5D9D0), Offset(x, cy - h / 2), Size(bw, h), r)
            else drawRoundRect(Brush.verticalGradient(listOf(Ink.local, Ink.server), startY = cy - h / 2, endY = cy + h / 2), Offset(x, cy - h / 2), Size(bw, h), r)
        }
        // шов «рот зашит»: пунктир и крестики; после докачки — разлетаются
        val k = if (loading) 0f else if (freed < 1f) freed else return@Canvas
        val seam = Color(0xFF8A93A6).copy(alpha = 1f - k)
        var x = -6.dp.toPx()
        while (x < size.width + 6.dp.toPx() && k < 1f) { drawLine(seam, Offset(x, cy), Offset(x + 7.dp.toPx(), cy), 2.dp.toPx()); x += 12.dp.toPx() }
        for (j in 0 until 7) {
            val cx = size.width * (j + .5f) / 7
            val dy = (if (j % 2 == 0) -40f else 40f).dp.toPx() * k; val dx = (if (j % 2 == 0) -10f else 10f).dp.toPx() * k
            val rot = (if (j % 2 == 0) -120f else 140f) * k
            rotate(rot, Offset(cx + dx, cy + dy)) {
                val a = 6.dp.toPx()
                drawLine(seam, Offset(cx + dx - a / 1.4f, cy + dy - a / 1.4f), Offset(cx + dx + a / 1.4f, cy + dy + a / 1.4f), 2.dp.toPx(), cap = StrokeCap.Round)
                drawLine(seam, Offset(cx + dx - a / 1.4f, cy + dy + a / 1.4f), Offset(cx + dx + a / 1.4f, cy + dy - a / 1.4f), 2.dp.toPx(), cap = StrokeCap.Round)
            }
        }
    }
}
