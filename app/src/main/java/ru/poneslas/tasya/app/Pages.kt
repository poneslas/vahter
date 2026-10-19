package ru.poneslas.tasya.app

import android.Manifest
import ru.poneslas.tasya.ui.BaseText
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.painterResource
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.FlowRow
import ru.poneslas.tasya.ui.TryVoice
import ru.poneslas.tasya.ui.OursIcon
import ru.poneslas.tasya.ui.Ic
import androidx.compose.ui.unit.em
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import ru.poneslas.tasya.core.Primary
import ru.poneslas.tasya.core.Modes
import ru.poneslas.tasya.core.Mode
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
import ru.poneslas.tasya.App
import ru.poneslas.tasya.core.Brains
import ru.poneslas.tasya.pisar.model.ModelDownload
import ru.poneslas.tasya.ui.Ink
import android.app.role.RoleManager

/** Распознавание: скачать (≈240 МБ, с докачкой), прогрев, удалить. */
@Composable
fun VoicePage(onBack: () -> Unit) {
    val app = App.instance
    TopBar("Распознавание речи", "GigaAM v3 на телефоне — голос не уходит наружу", onBack = onBack)
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
                Text("Всё готово", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Ink.ink)
                Text("Зажмите микрофон, скажите что-нибудь и отпустите.", fontSize = 15.sp, color = Ink.soft)
                ru.poneslas.tasya.ui.TryVoice()
            }
            running != null -> {
                Text("GigaAM, 225 МБ. Скачивается один раз, потом голос распознаётся без интернета. Лучше по Wi-Fi.", fontSize = 15.sp, color = Ink.soft)
                Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(Ink.line)) {
                    Box(Modifier.fillMaxWidth(running.percent / 100f).height(8.dp).clip(RoundedCornerShape(4.dp)).background(Ink.ink))
                }
                Text("Загрузка модели: ${running.percent} %", fontSize = 14.sp, color = Ink.ink)
            }
            else -> {
                Text("GigaAM, 225 МБ. Скачивается один раз, потом голос распознаётся без интернета. Лучше по Wi-Fi.", fontSize = 15.sp, color = Ink.soft)
                PrimaryButton(if (error == null) "Скачать" else "Попробовать ещё раз") { ModelDownload.start(ctx) }
                if (error != null) Text("Ошибка загрузки: $error", fontSize = 14.sp, color = Ink.danger)
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
    TopBar(m.title, if (m.builtin) "встроенный режим" else "свой режим · id ${m.id}, не меняется", onBack = activity::back, titleIcon = Ic.mode(m.icon))
    FormPage {
        if (ro) Note("Встроенный режим: его можно включить или выключить в «Настройках» и начать разговор заново. Чтобы сделать по-своему — создайте свой режим «+».")
        if (!ro) {
            Field("Название", m.title, "Как назвать вкладку") { v -> ed { it.copy(title = v) } }
            Column {
                Lh("Значок")
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
            Lh("Что делать")
            if (main) MNote("Основной режим: без своего промпта, все действия телефона.")
            else if (ro) Text(m.prompt, fontSize = 13.5.sp, color = Ink.ink)
            else {
                Area(m.prompt, "Например: переводи всё на китайский и добавляй пиньинь") { v -> ed { it.copy(prompt = v) } }
                Dictate { text -> ed { it.copy(prompt = (it.prompt.trim() + " " + text).trim()) } }
            }
        }
        if (!ro) {
            Field("Подсказка в пустом разговоре", m.hint, "Что сюда присылать") { v -> ed { it.copy(hint = v) } }
            Field("Подсказка в поле ввода", m.placeholder, "Вопрос к фото") { v -> ed { it.copy(placeholder = v) } }
        }
        Column {
            Lh("Принимает")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                MChip("Голос", m.accepts.voice, Ic.mic, onClick = if (ro) null else ({ ed { it.copy(accepts = it.accepts.copy(voice = !it.accepts.voice)) } }))
                MChip("Текст", true, Ic.kbd)
                MChip("Фото", m.accepts.photo, Ic.cam, onClick = if (ro) null else ({ ed { it.copy(accepts = it.accepts.copy(photo = !it.accepts.photo), primary = if (it.accepts.photo) Primary.VOICE else it.primary) } }))
            }
        }
        if (m.accepts.photo && !main) Column {
            Lh("Главное")
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Ink.chipBg).padding(3.dp)) {
                listOf(Primary.VOICE to ("Голос" to Ic.mic), Primary.PHOTO to ("Фото" to Ic.cam)).forEach { (v, tl) ->
                    val on = m.primary == v
                    Row(Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(if (on) Color.White else Color.Transparent)
                        .clickable(enabled = !ro) { ed { it.copy(primary = v) } }.padding(9.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        Icon(painterResource(tl.second), null, Modifier.size(16.dp), tint = Ink.ink)
                        Spacer(Modifier.width(6.dp))
                        Text(tl.first, fontSize = 14.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal)
                    }
                }
            }
            MNote(if (m.primary == Primary.PHOTO) "Большая кнопка — камера. С боковой кнопки — сразу видоискатель. Спросить голосом — микрофон в поле."
                else "Большая кнопка — микрофон. С боковой кнопки — сразу слушает.")
        }
        SetCard {
            if (main) Item("Действия телефона", "Все — будильник, таймер, календарь, звонки…", icon = Ic.shield)
            else if (ro) Item("Действия телефона", if (m.tools) "Включены" else "Выключены", icon = Ic.shield)
            else Item("Действия телефона", if (m.tools) "Включены — таймер, будильник" else "Выключены — меньше инструментов, ответ быстрее",
                icon = Ic.shield, end = { Sw(m.tools) }) { ed { it.copy(tools = !it.tools) } }
            Item("Язык речи", "Русский (GigaAM). Другие языки — позже", icon = Ic.wave)
        }
        Column {
            val n = state.saved.feeds[m.id].orEmpty().size
            SetCard {
                Item("Мозг", Brains.byId(m.brain).title + if (m.accepts.photo) " · нужен со зрением" else "", icon = Ic.spark)
                Item("Начать заново", if (n > 0) "Стереть разговор режима — $n сообщ." else "Разговор пуст", icon = Ic.hist) { if (n > 0) conv.restart(m.id) }
            }
            MNote("История режима хранится на телефоне и целиком уходит мозгу с каждой репликой. Смена мозга начинает разговор заново.")
        }
        if (!m.builtin) {
            var ask by remember { mutableStateOf(false) }
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color.White).border(1.dp, Color(0xFFE7C2BE), RoundedCornerShape(12.dp))
                .clickable { if (ask) { activity.back(); conv.deleteMode(m.id) } else ask = true }.padding(12.dp),
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(Ic.del), null, Modifier.size(18.dp), tint = Ink.danger)
                Spacer(Modifier.width(8.dp))
                Text(if (ask) "Точно удалить? Нажмите ещё раз" else "Удалить режим", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Ink.danger)
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
        ru.poneslas.tasya.ui.VoiceRow(
            voice = App.instance.voice,
            field = { Text(msg ?: "Зажмите и надиктуйте — допишется в конец. Можно поправить руками.", fontSize = 13.sp, color = Ink.soft) },
            trailing = null,
            onText = { msg = null; onText(it) },
            onMessage = { msg = it },
            onNeedMic = { (ctx as? android.app.Activity)?.requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1) },
        )
    }
}

/**
 * Первый запуск (ui.js, onb): картинка по центру, заголовок 24, текст, дополнительный блок; внизу «Позже» · точки · кнопка.
 * Ничего не блокирует: пропущенное потом напомнит баннер.
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
    val next: () -> Unit = { if (step < steps - 1) step++ else onDone() }
    val mic = activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    val role = activity.getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_ASSISTANT)
    val hasKey = remember(keyV) { app.hasKey(Brains.deepseek.id) }

    var skip: Pair<String, () -> Unit>? = "Позже" to next
    var primary: Pair<String, () -> Unit>? = "Далее" to next

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(start = 24.dp, end = 24.dp, top = 22.dp, bottom = 22.dp)) {
        if (step == 3) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(top = 14.dp)) { KeyEditor(Brains.deepseek, onboarding = true) }
            if (hasKey) skip = null else primary = null
        } else {
            val title = when (step) { 0 -> "Зажал кнопку — сказал — готово"; 1 -> "Боковая кнопка"; 2 -> "Голос на телефоне"; else -> "Готово" }
            val text = when (step) {
                0 -> "Таймеры и будильники телефон ставит сам, даже без интернета. Остальное отвечает модель, которую выберете вы."
                1 -> "Android разрешает выбрать помощника только в настройках. Я открою нужные экраны."
                2 -> "Один раз скачать 225 МБ. Голос не уходит с телефона и работает без интернета."
                else -> if (role) "Зажмите боковую кнопку в любом приложении и скажите «Таймер на минуту»." else "Лист можно открыть отсюда. Боковую кнопку настроите потом — шестерёнка, «Вызов»."
            }
            Box(Modifier.weight(1f).fillMaxWidth().heightIn(min = 120.dp), contentAlignment = Alignment.Center) {
                when (step) {
                    0 -> OursIcon(96.dp, 30.dp)
                    1 -> Icon(painterResource(Ic.power), null, Modifier.size(64.dp), tint = Ink.ink)
                    2 -> Text("▂▅▇▅▂", fontSize = 64.sp, letterSpacing = (-4).sp, color = Ink.ink)
                    else -> Text("✓", fontSize = 64.sp, color = Ink.ink)
                }
            }
            Text(title, fontSize = 24.sp, lineHeight = 1.15.em, fontWeight = FontWeight.SemiBold, color = Ink.ink, modifier = Modifier.padding(bottom = 10.dp))
            Text(text, fontSize = 15.sp, color = Ink.soft, modifier = Modifier.padding(bottom = 12.dp))
            when (step) {
                0 -> { skip = null; primary = "Начать" to next }
                1 -> {
                    SetCard {
                        Item("Цифровой помощник", if (role) "Тася" else "Выберите «Тася»", icon = Ic.spark, end = if (role) ({ Text("✓", color = Ink.ok, fontWeight = FontWeight.Bold) }) else null) {
                            activity.startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))
                        }
                        Item("Боковая кнопка → цифровой помощник", SideKey.describe(activity), icon = Ic.btn) { SideKey.open(activity) }
                    }
                }
                2 -> {
                    val running = dl as? ModelDownload.State.Running
                    val error = (dl as? ModelDownload.State.Failed)?.reason
                    when {
                        installed -> { Note("Готово — зажмите микрофон и скажите что-нибудь", ok = true); Spacer(Modifier.height(12.dp)); TryVoice(); skip = null }
                        running != null -> {
                            Progress(running.percent)
                            Text("Загрузка модели: ${running.percent} % — можно идти дальше, докачается само.", fontSize = 13.sp, color = Ink.soft, modifier = Modifier.padding(top = 8.dp))
                        }
                        else -> {
                            if (error != null) Note("Ошибка загрузки: $error", ok = false)
                            primary = (if (error == null) "Скачать" else "Ещё раз") to { ModelDownload.start(ctx); Unit }
                        }
                    }
                    if (!installed && running == null && !mic) Unit
                    if (!mic && (installed || running != null)) SetCard(Modifier.padding(top = 12.dp)) {
                        Item("Микрофон", "Нужен для голосового ввода", icon = Ic.mic) { activity.requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1) }
                    }
                }
                else -> {
                    skip = null; primary = "Открыть" to onDone
                    val notif = activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                    if (!notif) SetCard { Item("Уведомления", "Сообщить об ответе, если лист закрыт раньше", icon = Ic.bell) { activity.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2) } }
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
