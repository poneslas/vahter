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
    TopBar(tr(R.string.set_raspoznavanie_rechi), tr(R.string.pg_gigaam_v3_na_telefone_golos), onBack = onBack)
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
                Text(tr(R.string.pg_vse_gotovo), fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Ink.ink)
                Text(tr(R.string.pg_zazhmite_mikrofon_skazhite_chto_nibud), fontSize = 15.sp, color = Ink.soft)
                ru.poneslas.vahter.ui.TryVoice()
            }
            running != null -> {
                Text(tr(R.string.pg_gigaam_225_mb_skachivaetsya_odin), fontSize = 15.sp, color = Ink.soft)
                Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(Ink.line)) {
                    Box(Modifier.fillMaxWidth(running.percent / 100f).height(8.dp).clip(RoundedCornerShape(4.dp)).background(Ink.ink))
                }
                Text(tr(R.string.app_zagruzka_modeli_1_s, running.percent), fontSize = 14.sp, color = Ink.ink)
            }
            else -> {
                Text(tr(R.string.pg_gigaam_225_mb_skachivaetsya_odin), fontSize = 15.sp, color = Ink.soft)
                PrimaryButton(if (error == null) tr(R.string.pg_skachat) else tr(R.string.pg_poprobovat_eshche_raz)) { ModelDownload.start(ctx) }
                if (error != null) Text(tr(R.string.pg_oshibka_zagruzki_1_s, error), fontSize = 14.sp, color = Ink.danger)
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
    TopBar(m.title, if (m.builtin) tr(R.string.pg_vstroennyy_rezhim) else tr(R.string.pg_svoy_rezhim_id_1_s, m.id), onBack = activity::back, titleIcon = Ic.mode(m.icon))
    FormPage {
        if (ro) Note(tr(R.string.pg_vstroennyy_rezhim_ego_mozhno_vklyuchit))
        if (!ro) {
            Field(tr(R.string.pg_nazvanie), m.title, tr(R.string.pg_kak_nazvat_vkladku)) { v -> ed { it.copy(title = v) } }
            Column {
                Lh(tr(R.string.pg_znachok))
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
            Lh(tr(if (main) R.string.pg_svoi_pravila else R.string.pg_chto_delat))
            if (main) MNote(tr(R.string.pg_svoi_pravila_note))
            Area(m.prompt, tr(if (main) R.string.pg_svoi_pravila_hint else R.string.pg_naprimer_perevodi_vse_na_kitayskiy)) { v -> ed { it.copy(prompt = v, promptEdited = true) } }
            Dictate { text -> ed { it.copy(prompt = (it.prompt.trim() + " " + text).trim(), promptEdited = true) } }
            val def = Modes.builtin.firstOrNull { it.id == m.id }?.prompt
            if (ro && !main && def != null && m.prompt != def) Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                TextButton(tr(R.string.pg_vernut_kak_bylo)) { ed { it.copy(prompt = def, promptEdited = false) } }
            }
        }
        if (!ro) {
            Field(tr(R.string.pg_podskazka_v_pustom_razgovore), m.hint, tr(R.string.pg_chto_syuda_prisylat)) { v -> ed { it.copy(hint = v) } }
            Field(tr(R.string.pg_podskazka_v_pole_vvoda), m.placeholder, tr(R.string.mode_vopros_k_foto)) { v -> ed { it.copy(placeholder = v) } }
        }
        Column {
            Lh(tr(R.string.pg_prinimaet))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                MChip(tr(R.string.set_golos), m.accepts.voice, Ic.mic, onClick = if (ro) null else ({ ed { it.copy(accepts = it.accepts.copy(voice = !it.accepts.voice)) } }))
                MChip(tr(R.string.pg_tekst), true, Ic.kbd)
                MChip(tr(R.string.set_foto), m.accepts.photo, Ic.cam, onClick = if (ro) null else ({ ed { it.copy(accepts = it.accepts.copy(photo = !it.accepts.photo), primary = if (it.accepts.photo) Primary.VOICE else it.primary) } }))
            }
        }
        if (m.accepts.photo && !main) Column {
            Lh(tr(R.string.pg_glavnoe))
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Ink.chipBg).padding(3.dp)) {
                listOf(Primary.VOICE to (tr(R.string.set_golos) to Ic.mic), Primary.PHOTO to (tr(R.string.set_foto) to Ic.cam)).forEach { (v, tl) ->
                    val on = m.primary == v
                    Row(Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(if (on) Color.White else Color.Transparent)
                        .clickable(enabled = !ro) { ed { it.copy(primary = v) } }.padding(9.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        Icon(painterResource(tl.second), null, Modifier.size(16.dp), tint = Ink.ink)
                        Spacer(Modifier.width(6.dp))
                        Text(tl.first, fontSize = 14.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal)
                    }
                }
            }
            MNote(if (m.primary == Primary.PHOTO) tr(R.string.pg_bolshaya_knopka_kamera_s_bokovoy)
                else tr(R.string.pg_bolshaya_knopka_mikrofon_s_bokovoy))
        }
        SetCard {
            if (main) Item(tr(R.string.set_deystviya_telefona), tr(R.string.pg_vse_budilnik_taymer_kalendar_zvonki), icon = Ic.shield)
            else if (ro) Item(tr(R.string.set_deystviya_telefona), if (m.tools) tr(R.string.pg_vklyucheny) else tr(R.string.set_vyklyucheny), icon = Ic.shield)
            else Item(tr(R.string.set_deystviya_telefona), if (m.tools) tr(R.string.pg_vklyucheny_taymer_budilnik) else tr(R.string.pg_vyklyucheny_menshe_instrumentov_otvet_by),
                icon = Ic.shield, end = { Sw(m.tools) }) { ed { it.copy(tools = !it.tools) } }
            Item(tr(R.string.pg_yazyk_rechi), tr(R.string.pg_russkiy_gigaam_drugie_yazyki_pozzhe), icon = Ic.wave)
        }
        Column {
            SetCard {
                Item(tr(R.string.kit_mozg), Brains.byId(m.brain).title + if (m.accepts.photo) tr(R.string.pg_nuzhen_so_zreniem) else "", icon = Ic.spark)
            }
            MNote(tr(R.string.pg_istoriya_rezhima_khranitsya_na_telefone))
        }
        if (!m.builtin) {
            var ask by remember { mutableStateOf(false) }
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color.White).border(1.dp, Color(0xFFE7C2BE), RoundedCornerShape(12.dp))
                .clickable { if (ask) { activity.back(); conv.deleteMode(m.id) } else ask = true }.padding(12.dp),
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(Ic.del), null, Modifier.size(18.dp), tint = Ink.danger)
                Spacer(Modifier.width(8.dp))
                Text(if (ask) tr(R.string.pg_tochno_udalit_nazhmite_eshche_raz) else tr(R.string.pg_udalit_rezhim), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Ink.danger)
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
            field = { Text(msg ?: tr(R.string.pg_zazhmite_i_nadiktuyte_dopishetsya_v), fontSize = 13.sp, color = Ink.soft) },
            trailing = null,
            onText = { msg = null; onText(it) },
            onMessage = { msg = it },
            onNeedMic = { (ctx as? android.app.Activity)?.requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1) },
        )
    }
}

/**
 * Первый запуск (ui.js, onb): приветствие → голос → боковая кнопка (пока качается модель) → проба голоса → мозг (последним).
 * Ничего не блокирует, но кнопку не пропустить, не заметив: пока не настроено — «Настроить», «Позже» — через предупреждение.
 */
@Composable
fun Onboarding(activity: MainActivity, start: Int = 0, onDone: () -> Unit) {
    var step by remember(start) { mutableIntStateOf(start) }
    val app = App.instance
    val ctx = activity
    val keyV by app.keyVersion.collectAsState()
    val installed by app.voice.installed.collectAsState()
    val dl by ModelDownload.state.collectAsState()
    @Suppress("UNUSED_VARIABLE") val r = activity.resumed.intValue // баг №1: перечитать после возврата из системных окон
    val steps = 5
    val running = dl as? ModelDownload.State.Running
    // Голос не качали — пробовать нечем: с кнопки сразу к мозгу.
    val next: () -> Unit = { if (step >= steps - 1) onDone() else step += if (step == 2 && !installed && running == null) 2 else 1 }
    val mic = activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    val role = activity.getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_ASSISTANT)
    val hasKey = remember(keyV) { app.hasKey(Brains.deepseek.id) }
    var btnWarned by remember { mutableStateOf(false) }

    var skip: Pair<String, () -> Unit>? = tr(R.string.pg_pozzhe) to next
    var primary: Pair<String, () -> Unit>? = tr(R.string.pg_dalee) to next

    // Шаг 0 — приветствие: девиз и 4 карточки с живыми сценами (ui/Intro.kt).
    if (step == 0) { ru.poneslas.vahter.ui.Intro { step = 1 }; return }
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(start = 24.dp, end = 24.dp, top = 22.dp, bottom = 22.dp)) {
        when (step) {
            4 -> {
                // Мозг — последним: «Открыть» ведёт в разговор. Уведомления — здесь же, пока экран приложения открыт.
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(top = 14.dp)) {
                    KeyEditor(Brains.deepseek, onboarding = true)
                    val notif = activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                    if (hasKey && !notif) SetCard(Modifier.padding(top = 12.dp)) {
                        Item(tr(R.string.set_uvedomleniya), tr(R.string.pg_soobshchit_ob_otvete_esli_list), icon = Ic.bell) { activity.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2) }
                    }
                }
                if (hasKey) { skip = null; primary = tr(R.string.pg_otkryt) to onDone } else primary = null
            }
            3 -> { TryStep(activity, role, running?.percent, installed, mic); skip = null }
            2 -> {
                // Боковая кнопка: на незнакомом телефоне её может не быть — тогда требуем только помощника.
                val side = SideKey.read(activity)
                val known = side != SideKey.State.UNKNOWN
                val ok = role && (!known || side == SideKey.State.ASSISTANT)
                Box(Modifier.weight(1f).fillMaxWidth().heightIn(min = 120.dp), contentAlignment = Alignment.Center) {
                    if (ok) Box(Modifier.size(84.dp).clip(CircleShape).background(Ink.ok), contentAlignment = Alignment.Center) { Text("✓", fontSize = 44.sp, color = Color.White) }
                    else Icon(painterResource(Ic.power), null, Modifier.size(64.dp), tint = Ink.ink)
                }
                Text(tr(R.string.set_bokovaya_knopka), fontSize = 24.sp, lineHeight = 1.15.em, fontWeight = FontWeight.SemiBold, color = Ink.ink, modifier = Modifier.padding(bottom = 10.dp))
                Text(tr(if (!ok) (if (known) R.string.btn_p_todo else R.string.btn_p_todo_one) else if (known) R.string.btn_p_done else R.string.btn_p_done_nokey), fontSize = 15.sp, color = Ink.soft, modifier = Modifier.padding(bottom = 12.dp))
                SetCard {
                    Item(tr(R.string.set_tsifrovoy_pomoshchnik), if (role) tr(R.string.set_vahter) else tr(R.string.btn_not_set, tr(R.string.btn_other_assistant)), warn = !role, icon = Ic.spark,
                        end = if (role) ({ Text("✓", color = Ink.ok, fontWeight = FontWeight.Bold) }) else null) { activity.startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)) }
                    if (known) {
                        val sideOk = side == SideKey.State.ASSISTANT
                        val d = SideKey.describe(activity)
                        Item(tr(R.string.set_bokovaya_knopka), if (sideOk) d else tr(R.string.btn_not_set, d.replaceFirstChar { it.lowercase() }), warn = !sideOk, icon = Ic.btn,
                            end = if (sideOk) ({ Text("✓", color = Ink.ok, fontWeight = FontWeight.Bold) }) else null) { SideKey.open(activity) }
                    } else Item(tr(R.string.btn_call), tr(R.string.btn_call_sub), icon = Ic.btn)
                }
                running?.let { Text(tr(R.string.bg_dl, it.percent) + if (ok) "" else tr(R.string.bg_dl_hint), fontSize = 13.sp, color = Ink.soft, modifier = Modifier.padding(top = 10.dp)) }
                if (!ok) {
                    // «Настроить» открывает по очереди то, что ещё не настроено.
                    primary = tr(R.string.btn_setup) to { if (!role) activity.startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)) else SideKey.open(activity) }
                    skip = tr(R.string.pg_pozzhe) to {
                        if (btnWarned) next() else {
                            btnWarned = true
                            activity.showSheet(MainActivity.PermSheet(tr(R.string.btn_warn_h), tr(R.string.btn_warn_p), tr(R.string.btn_setup), icon = Ic.power,
                                later = tr(R.string.btn_warn_later), onLater = next) { if (!role) activity.startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)) else SideKey.open(activity) })
                        }
                    }
                } else skip = null
            }
            else -> {
                // Голос — первым: модель качается, пока настраивают кнопку.
                Box(Modifier.weight(1f).fillMaxWidth().heightIn(min = 120.dp), contentAlignment = Alignment.Center) { ru.poneslas.vahter.ui.VoiceArt() }
                Text(tr(R.string.pg_golos_na_telefone), fontSize = 24.sp, lineHeight = 1.15.em, fontWeight = FontWeight.SemiBold, color = Ink.ink, modifier = Modifier.padding(bottom = 10.dp))
                Text(tr(R.string.onb_voice_p, "225"), fontSize = 15.sp, color = Ink.soft, modifier = Modifier.padding(bottom = 12.dp))
                val error = (dl as? ModelDownload.State.Failed)?.reason
                when {
                    installed -> Note(tr(R.string.pg_gotovo), ok = true)
                    running != null -> {
                        Progress(running.percent)
                        Text(tr(R.string.pg_zagruzka_modeli_1_s_mozhno, running.percent), fontSize = 13.sp, color = Ink.soft, modifier = Modifier.padding(top = 8.dp))
                    }
                    else -> {
                        if (error != null) Note(tr(R.string.pg_oshibka_zagruzki_1_s, error), ok = false)
                        // «Скачать» — и сразу системный запрос микрофона, пока качается модель (Продукт 5.13).
                        primary = (if (error == null) tr(R.string.pg_skachat) else tr(R.string.pg_eshche_raz)) to {
                            ModelDownload.start(ctx); if (!mic && !app.conversation.state.value.settings.micAsked) activity.askMic(); Unit
                        }
                    }
                }
                if (!mic && (installed || running != null)) SetCard(Modifier.padding(top = 12.dp)) {
                    Item(tr(R.string.set_mikrofon), tr(R.string.pg_nuzhen_dlya_golosovogo_vvoda), icon = Ic.mic) { activity.askMic() }
                }
            }
        }
        // .nav: «Позже» или пусто · точки · кнопка
        Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { skip?.let { (t, a) -> TextButton(t, onClick = a) } }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                repeat(steps) { i -> Box(Modifier.size(if (i == step) 20.dp else 7.dp, 7.dp).clip(RoundedCornerShape(4.dp)).background(if (i == step) Ink.ink else Ink.swOff)) }
            }
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) { primary?.let { (t, a) -> PrimaryButton(t, onClick = a) } }
        }
    }
}

/** Одна диктовка на пробе: текст и сколько ждали распознавания после отпускания. */
private data class Shot(val text: String, val ms: Long)

/**
 * Проба голоса (ui.js, tryStep) — показать, что пишется быстро: одна настоящая строка ввода листа (кнопка Писаря: зажать,
 * замок вверх, отмена влево), надиктованное — пузырями над ней с отметкой «⚡ 0,4 с · на телефоне». Мозгу ничего не уходит.
 * Чего не хватает — одной строкой посередине; микрофон спрашивает сама кнопка.
 */
@Composable
private fun androidx.compose.foundation.layout.ColumnScope.TryStep(activity: MainActivity, side: Boolean, pct: Int?, installed: Boolean, mic: Boolean) {
    val app = App.instance
    val shots = remember { androidx.compose.runtime.mutableStateListOf<Shot>() }
    var msg by remember { mutableStateOf<String?>(null) }
    val phase by ru.poneslas.vahter.ui.RecUi.phase.collectAsState()
    // Время от отпускания (начало распознавания) до текста — его и показываем.
    var t0 by remember { mutableStateOf(0L) }
    LaunchedEffect(phase) { if (phase == ru.poneslas.vahter.ui.RecUi.Phase.PROCESSING) t0 = android.os.SystemClock.uptimeMillis() }
    val micAsked = app.conversation.state.collectAsState().value.settings.micAsked
    val why = when {
        !installed && pct != null -> tr(R.string.try_loading, pct)
        !installed -> tr(R.string.try_none)
        !mic && micAsked && !activity.shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) -> tr(R.string.try_denied)
        !mic -> tr(R.string.try_ask)
        else -> null
    }
    val rec = phase != ru.poneslas.vahter.ui.RecUi.Phase.IDLE
    Text(tr(R.string.try_h), fontSize = 24.sp, lineHeight = 1.15.em, fontWeight = FontWeight.SemiBold, color = Ink.ink, modifier = Modifier.padding(top = 26.dp, bottom = 8.dp))
    Text(tr(R.string.try_p) + tr(if (side) R.string.try_p_side2 else R.string.try_p_noside2), fontSize = 15.sp, color = Ink.soft, modifier = Modifier.padding(bottom = 8.dp))
    val list = androidx.compose.foundation.lazy.rememberLazyListState()
    LaunchedEffect(shots.size, rec) { if (shots.isNotEmpty() || rec) list.animateScrollToItem(shots.size + (if (rec) 1 else 0)) }
    Box(Modifier.weight(1f).fillMaxWidth()) {
        if (shots.isEmpty() && !rec) Text(why ?: tr(R.string.try_empty), fontSize = 14.5.sp, color = Ink.soft, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.align(Alignment.Center).padding(horizontal = 24.dp))
        else androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxSize(), state = list, verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.Bottom),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp)) {
            items(shots.size) { i ->
                val s = shots[i]
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(s.text, fontSize = 15.sp, color = Ink.ink, modifier = Modifier.widthIn(max = 300.dp).clip(RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp)).background(Ink.me).padding(horizontal = 12.dp, vertical = 8.dp))
                    Text(tr(R.string.try_fast, "%.1f".format(s.ms / 1000f).replace('.', ',')), fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = Ink.localD,
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(Ink.localT).padding(horizontal = 9.dp, vertical = 2.dp))
                }
            }
            if (rec) item { Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { ru.poneslas.vahter.ui.RecPill() } }
        }
    }
    if (!rec) Row(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
        listOf(R.string.try_tip_up, R.string.try_tip_left).forEach { id ->
            Text(tr(id), fontSize = 12.sp, color = Ink.soft, modifier = Modifier.clip(RoundedCornerShape(50)).background(Ink.chipBg).padding(horizontal = 10.dp, vertical = 3.dp))
        }
    }
    Box(Modifier.padding(top = 6.dp)) {
        ru.poneslas.vahter.ui.VoiceRow(
            voice = app.voice,
            field = {
                Box(Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(24.dp)).background(Ink.surf)
                    .border(1.dp, Ink.line, RoundedCornerShape(24.dp)).padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text(msg ?: tr(R.string.try_hint), fontSize = 14.5.sp, color = Ink.soft, maxLines = 1)
                }
            },
            trailing = null,
            onText = { t -> shots += Shot(t, (android.os.SystemClock.uptimeMillis() - t0).coerceAtLeast(1)); msg = null },
            onMessage = { msg = it },
            onNeedMic = activity::askMic,
        )
    }
}
