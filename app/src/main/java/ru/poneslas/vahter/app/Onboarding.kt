package ru.poneslas.vahter.app

import ru.poneslas.vahter.assistant.Sheet
import ru.poneslas.vahter.ui.WizScene
import ru.poneslas.vahter.ui.WizArt
import ru.poneslas.vahter.ui.VoiceArt
import ru.poneslas.vahter.assistant.VahterTile
import ru.poneslas.vahter.ui.RecUi
import ru.poneslas.vahter.ui.VoiceRow
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.foundation.layout.Spacer
import ru.poneslas.vahter.ui.PrimaryButton
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.LaunchedEffect
import ru.poneslas.vahter.R
import ru.poneslas.vahter.ui.Item
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.rotate
import ru.poneslas.vahter.tr
import androidx.compose.ui.graphics.Color
import ru.poneslas.vahter.App
import androidx.compose.ui.res.painterResource
import ru.poneslas.vahter.ui.Note
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.material3.Text
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.StrokeCap
import android.app.role.RoleManager
import androidx.compose.ui.text.style.TextOverflow
import ru.poneslas.vahter.ui.Ink
import androidx.compose.foundation.layout.Row
import ru.poneslas.vahter.ui.Ic
import androidx.compose.foundation.layout.Arrangement
import ru.poneslas.vahter.core.Brains
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material3.Icon
import ru.poneslas.vahter.ui.SetCard
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.em
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.collectAsState
import ru.poneslas.vahter.ui.Progress
import androidx.compose.foundation.layout.padding
import ru.poneslas.vahter.pisar.model.ModelDownload
import android.Manifest
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.background
import ru.poneslas.vahter.ui.TextButton
import ru.poneslas.vahter.ui.Note
import android.provider.Settings
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.getValue
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.runtime.setValue
import ru.poneslas.vahter.ui.seconds
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import kotlin.math.sin
import ru.poneslas.vahter.assistant.SheetSt
import ru.poneslas.vahter.assistant.VMark

/**
 * Первый запуск — один мастер (ui.js, onb; Продукт 5.13): каждый экран сначала объясняет живой сценой (ui/Intro.kt),
 * а под ней сразу настраивает то, о чём рассказал. Семь шагов: приветствие → голос → кнопка (пока качается модель) →
 * проба голоса → «Знает, как у вас заведено» → «Привычки знает наизусть» → мозг. Финал — лист (MainActivity.finishOnboarding).
 * Ничего не блокирует, но кнопку не пропустить, не заметив: пока не настроено — «Настроить», «Позже» — через предупреждение.
 */
@Composable
fun Onboarding(activity: MainActivity, start: Int = 0, onDone: () -> Unit) {
    var step by rememberSaveable(start) { mutableIntStateOf(start) }
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
    // результат пробы — в настройках: строка «Как позвать Вахтёра» знает его и после мастера
    val probe = app.conversation.state.collectAsState().value.settings.sideProbe
    fun setProbe(v: Boolean?) = app.conversation.updateSettings { it.copy(sideProbe = v) }
    var tileAdded by remember { mutableStateOf(false) }
    val sheetUp by Sheet.visible.collectAsState()
    LaunchedEffect(sheetUp) { if (sheetUp && step == 2) setProbe(true) }   // лист открылся, пока идёт проба, — значит, кнопка работает
    val key = rememberKeyCtl(Brains.deepseek)
    val scope = rememberCoroutineScope()

    var skip: Pair<String, () -> Unit>? = tr(R.string.ui_later) to next
    var primary: Pair<String, () -> Unit>? = tr(R.string.ui_next) to next

    // Назад по мастеру: системный «Назад» и свайп вправо; свайп влево — вперёд, только если на экране уже можно «Далее».
    // На пробе голоса свайпа нет: у кнопки микрофона жест «влево — отмена». Голос не качали — проба пропускается и назад.
    val back: () -> Unit = { if (step > 0) { step -= if (step == 4 && !installed && running == null) 2 else 1; key.status = null } }
    BackHandler(step > 0) { back() }
    val canNext = rememberUpdatedState { primary?.second === next }
    val swipeBack = rememberUpdatedState(back)
    val swipeNext = rememberUpdatedState(next)
    val swipe = if (step == 3) Modifier else Modifier.pointerInput(step) {
        var dx = 0f
        detectHorizontalDragGestures(onDragStart = { dx = 0f }, onHorizontalDrag = { _, d -> dx += d }, onDragEnd = {
            val px = 60.dp.toPx()
            if (dx > px) swipeBack.value() else if (dx < -px && canNext.value()) swipeNext.value()
        })
    }
    Column(Modifier.fillMaxSize().then(swipe).statusBarsPadding().navigationBarsPadding().padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 22.dp)) {
        // Сцена — во всём свободном месте над текстом, текст — у кнопок (как было). Внутри шага, где по ходу что-то появляется
        // (полоска загрузки, «Готово», микрофон, строки кнопки), под текстом — резерв [Slot] на самое высокое состояние: не прыгает.
        @Composable fun Art(k: WizScene, minH: Dp = 300.dp) =
            Box(Modifier.weight(1f).fillMaxWidth().heightIn(min = minH), contentAlignment = Alignment.Center) { WizArt(k) }
        // Шаблон шагов с действием (onboarding.js, .onbact/.onbstat): карточка действия — всегда прямо над точками, место под одну
        // строку занято заранее; под ней — строка фона (загрузка голоса), тоже заранее. Ничего не прыгает, карточки на одной высоте.
        @Composable fun Action(content: @Composable ColumnScope.() -> Unit) =
            Column(Modifier.fillMaxWidth().heightIn(min = 76.dp).padding(top = 4.dp), verticalArrangement = Arrangement.Bottom, content = content)
        @Composable fun Status(content: @Composable ColumnScope.() -> Unit) =
            Column(Modifier.fillMaxWidth().heightIn(min = 34.dp).padding(start = 4.dp, top = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically), content = content)
        @Composable fun Head(h: String, p: String) {
            Text(h, fontSize = 24.sp, lineHeight = 1.15.em, fontWeight = FontWeight.SemiBold, color = Ink.ink, modifier = Modifier.padding(top = 8.dp, bottom = 8.dp))
            Text(p, fontSize = 15.sp, color = Ink.soft, modifier = Modifier.padding(bottom = 12.dp))
        }
        when (step) {
            0 -> { Art(WizScene.WELCOME); Head(tr(R.string.intro_0_h), tr(R.string.intro_0_p)); skip = null; primary = tr(R.string.ui_start) to next }
            1 -> {
                // Голос — первым: модель качается, пока настраивают кнопку.
                Box(Modifier.weight(1f).fillMaxWidth().heightIn(min = 120.dp), contentAlignment = Alignment.Center) { VoiceArt() }
                Head(tr(R.string.wiz_voice_h), tr(R.string.wiz_voice_p, "225"))
                val error = (dl as? ModelDownload.State.Failed)?.reason
                Action {
                    if (!mic && (installed || running != null)) SetCard { Item(tr(R.string.set_mic), tr(R.string.wiz_voice_mic_sub), icon = Ic.mic) { activity.askMic() } }
                }
                Status {
                    when {
                        installed -> Text(tr(R.string.wiz_voice_done), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Ink.ok)
                        running != null -> { Progress(running.percent); Text(tr(R.string.wiz_voice_progress, running.percent), fontSize = 13.sp, color = Ink.soft) }
                        error != null -> Text(tr(R.string.voice_error, error), fontSize = 13.sp, color = Ink.danger)
                    }
                }
                if (!installed && running == null) {
                    // «Скачать» — и сразу системный запрос микрофона, пока качается модель (Продукт 5.13).
                    primary = (if (error == null) tr(R.string.voice_download) else tr(R.string.wiz_voice_retry)) to {
                        ModelDownload.start(ctx); if (!mic && !app.conversation.state.value.settings.micAsked) activity.askMic(); Unit
                    }
                }
                if (installed || running != null) skip = null
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
                // Гонка чуть меньше, чем во вступлении: под текстом — воздух (onboarding.js, .onb .art .sc1).
                Box(Modifier.weight(1f).fillMaxWidth().heightIn(min = 200.dp), contentAlignment = Alignment.Center) { Box(Modifier.scale(.86f)) { WizArt(WizScene.QUEUE) } }
                Head(tr(R.string.intro_1_h), tr(when {
                    !role -> R.string.sidekey_p_todo_one
                    known && !sideOk -> R.string.sidekey_p_todo
                    known -> R.string.sidekey_p_done
                    probe == true -> R.string.sidekey_p_done_probe
                    probe == false -> R.string.sidekey_p_probe_no
                    else -> R.string.sidekey_p_done_nokey
                }))
                // Строка боковой кнопки — только если с ней есть что делать: уже открывает помощника (One UI 7+) — строки нет.
                Action {
                    SetCard {
                        Item(tr(R.string.set_assistant_role), if (role) tr(R.string.app_name) else tr(R.string.sidekey_other_assistant), warn = !role, icon = Ic.spark,
                            end = if (role) check else null) { activity.startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)) }
                        when {
                            known && !sideOk -> Item(tr(R.string.set_side_key), SideKey.now(activity), warn = true, icon = Ic.btn) { SideKey.open(activity) }
                            known || !role || probe == true -> {}
                            probe == false -> {
                                Item(tr(R.string.set_call_way), tr(R.string.sidekey_call_home), icon = Ic.btn) { activity.startActivity(Intent(Settings.ACTION_SETTINGS)) }
                                if (tileAdded) Item(tr(R.string.sidekey_tile), tr(R.string.sidekey_tile_done), icon = Ic.list, end = check)
                                else Item(tr(R.string.sidekey_tile), tr(R.string.sidekey_tile_sub), icon = Ic.list) { VahterTile.request(activity) { tileAdded = it } }
                            }
                            else -> Column(Modifier.fillMaxWidth().padding(start = 22.dp, end = 12.dp, top = 12.dp, bottom = 4.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(painterResource(Ic.btn), null, Modifier.size(24.dp).padding(2.dp), tint = Ink.soft)
                                    Spacer(Modifier.width(16.dp))
                                    Text(tr(R.string.sidekey_probe), fontSize = 15.sp, lineHeight = 1.35.em, color = Ink.ink)
                                }
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End)) {
                                    TextButton(tr(R.string.ui_no)) { setProbe(false) }
                                    TextButton(tr(R.string.ui_yes)) { setProbe(true) }
                                }
                            }
                        }
                    }
                }
                Status { running?.let { Text(tr(R.string.wiz_dl_progress, it.percent), fontSize = 13.sp, color = Ink.soft) } }
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
            4 -> { Art(WizScene.CHAT); Head(tr(R.string.wiz_rules_h), tr(R.string.wiz_rules_p)); skip = null }
            5 -> { Art(WizScene.KEYS); Head(tr(R.string.intro_3_h), tr(R.string.wiz_quick_p)); skip = null }
            else -> {
                // Мозг — последним. «Подключить» — главная кнопка внизу; подключили — «Открыть» ведёт в лист.
                // Уведомления — здесь же, пока экран приложения открыт: из листа системное окно показать нечем.
                // Как остальные шаги: сцена — в растягиваемой области (сжимается, если внизу стало больше), заголовок и ключ — внизу.
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(bottom = 10.dp), contentAlignment = Alignment.Center) {
                    val k = (maxHeight / 300.dp).coerceIn(.4f, 1f)
                    Box(Modifier.scale(k)) { WizArt(WizScene.BRAIN) }
                }
                Head(tr(R.string.wiz_brain_h), tr(R.string.wiz_brain_p))
                KeyEditor(Brains.deepseek, onboarding = true, ctl = key)
                val notif = activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                if (hasKey && !notif) SetCard(Modifier.padding(top = 8.dp)) {
                    Item(tr(R.string.set_notif), tr(R.string.wiz_notif_sub), icon = Ic.bell) { app.conversation.updateSettings { it.copy(notifAsked = true) }; activity.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2) }
                }
                if (hasKey) { skip = null; primary = tr(R.string.ui_open) to onDone }
                else primary = (if (key.checking) tr(R.string.key_connecting) else tr(R.string.ui_connect)) to { key.connect(scope) }
            }
        }
        // Точки — своей строкой над кнопками, по центру: широкая «Подключить» и крупный шрифт их не задевают (onboarding.js, navBar).
        StepDots(step, steps, Modifier.align(Alignment.CenterHorizontally).padding(top = 14.dp))
        // .nav: «Позже» или пусто · кнопка; кнопка справа берёт сколько нужно и не ломает слово при крупном шрифте.
        Box(Modifier.fillMaxWidth().padding(top = 10.dp)) {
            Box(Modifier.align(Alignment.CenterStart)) { skip?.let { (t, a) -> TextButton(t, onClick = a) } }
            Box(Modifier.align(Alignment.CenterEnd)) {
                primary?.let { (t, a) -> PrimaryButton(t, enabled = !(step == steps - 1 && !hasKey && !key.canCheck), onClick = a) }
            }
        }
    }
}

/**
 * Точки шагов как в Instagram (onboarding.js, onbDots): на виду не больше пяти, окно едет за текущим шагом; крайняя точка
 * мельче, если за ней есть ещё. Ширина постоянная — 4 × 7 + 20 + 4 × 6.
 */
@Composable
private fun StepDots(step: Int, steps: Int, modifier: Modifier = Modifier) {
    val k = minOf(steps, 5)
    val at = (step - 2).coerceIn(0, steps - k)
    Row(modifier.width(72.dp), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
        repeat(k) { j ->
            val i = at + j
            val edge = (j == 0 && i > 0) || (j == k - 1 && i < steps - 1)
            val w by animateDpAsState(if (i == step) 20.dp else if (edge) 4.dp else 7.dp, label = "dotW")
            val h by animateDpAsState(if (edge && i != step) 4.dp else 7.dp, label = "dotH")
            Box(Modifier.size(w, h).clip(RoundedCornerShape(4.dp)).background(if (i == step) Ink.ink else Ink.swOff))
        }
    }
}

/** Одна диктовка на пробе: текст и сколько ждали распознавания после отпускания. */
private data class Shot(val text: String, val ms: Long)

/**
 * Проба голоса (ui.js, tryStep). Герой экрана — знак Вахтёра. Пока распознавание качается, ядро серое, внешнее кольцо — шкала
 * загрузки; микрофон не работает, справа «Пропустить». Докачалось — знак загорается с прыжком, кнопка один раз пульсирует. Дальше фраза — крупно прямо на фоне, без поля и подложки,
 * «⚡ Распознано на телефоне за 0,7 с»; прошлые бледнеют выше. Внизу — одна тихая строка жестов у той же кнопки, что в листе
 * (кнопка справа: при записи появляется полоса «‹ ‹ Отмена», кнопка не должна прыгать из-под пальца). Мозгу ничего не уходит.
 */
@Composable
private fun ColumnScope.TryStep(activity: MainActivity, pct: Int?, installed: Boolean, mic: Boolean) {
    val app = App.instance
    val shots = remember { mutableStateListOf<Shot>() }
    var msg by remember { mutableStateOf<String?>(null) }
    val phase by RecUi.phase.collectAsState()
    // Время от отпускания (начало распознавания) до текста — его и показываем.
    var t0 by remember { mutableStateOf(0L) }
    LaunchedEffect(phase) { if (phase == RecUi.Phase.PROCESSING) t0 = SystemClock.uptimeMillis() }
    // Докачалось, пока человек на этом экране, — шов лопается (один раз).
    val freed = remember { Animatable(1f) }
    var wasLoading by remember { mutableStateOf(!installed) }
    LaunchedEffect(installed) {
        if (installed && wasLoading) { freed.snapTo(0f); freed.animateTo(1f, tween(1600)) }
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
    val rec = phase == RecUi.Phase.LISTENING
    val proc = phase == RecUi.Phase.PROCESSING
    // Шаблон мастера: сцена (знак, прошлые фразы, распознанное) — в свободном месте сверху; заголовок и текст — внизу, над полем
    Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        val old = if (rec || proc || loading) emptyList() else shots.dropLast(1).takeLast(3)
        if (old.isNotEmpty()) {
            Column(Modifier.fillMaxWidth().weight(1f, fill = false).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                old.forEachIndexed { i, sh -> Text(sh.text, fontSize = 14.sp, lineHeight = 1.35.em, color = if (i == old.lastIndex) Ink.soft else Color(0xFFB4BAC4), maxLines = 2) }
            }
        }
        // Знак Вахтёра — тот же, что в листе (ui.js, .tsun): качается — ядро серое, внешнее кольцо — шкала; докачалось — загорается
        // с прыжком; слушает — живой от громкости; распознаёт — пунктирное кольцо.
        val pop = if (freed.value < 1f) { val f = (freed.value / .55f).coerceIn(0f, 1f); .8f + .2f * f + .12f * sin(f * Math.PI.toFloat()) } else 1f
        Box(Modifier.height(132.dp).graphicsLayer { scaleX = pop; scaleY = pop }, contentAlignment = Alignment.Center) {
            VMark(when { rec -> SheetSt.REC; proc -> SheetSt.RECOG; else -> SheetSt.IDLE },
                118.dp, sleep = if (!installed) (pct ?: 0) / 100f else null)
        }
        // строка процентов — всегда в резерве: докачалось — текст ниже не прыгает
        Text("${pct ?: 0}\u00A0%", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Ink.localD, modifier = Modifier.padding(top = 8.dp).alpha(if (loading) 1f else 0f))
        Column(Modifier.fillMaxWidth().heightIn(min = 96.dp).padding(top = 14.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val last = shots.lastOrNull()
            val center = TextAlign.Center
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
    Text(tr(R.string.try_h), fontSize = 24.sp, lineHeight = 1.15.em, fontWeight = FontWeight.SemiBold, color = Ink.ink, modifier = Modifier.padding(top = 8.dp, bottom = 8.dp))
    Text(when { loading -> tr(R.string.try_p_loading); !installed -> tr(R.string.try_p_none); else -> tr(R.string.try_p_ready) }, fontSize = 15.sp, color = Ink.soft, modifier = Modifier.padding(bottom = 8.dp))
    Box(Modifier.padding(top = 6.dp)) {
        VoiceRow(
            voice = app.voice,
            // Поля нет: одна тихая строка жестов у кнопки.
            field = { Text(tr(R.string.try_tips), fontSize = 13.sp, color = Ink.faint, maxLines = 1, overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.End, modifier = Modifier.fillMaxWidth().padding(end = 14.dp)) },
            trailing = null,
            onText = { t -> shots += Shot(t, (SystemClock.uptimeMillis() - t0).coerceAtLeast(1)); msg = null },
            onMessage = { msg = it },
            onNeedMic = activity::askMic,
        )
        // докачалось — кнопка микрофона один раз пульсирует: «можно»
        if (freed.value < 1f) Box(Modifier.align(Alignment.CenterEnd).padding(end = 10.dp).size(52.dp).drawBehind {
            val k = freed.value
            drawCircle(Ink.local.copy(alpha = .65f * (1 - k)), radius = size.minDimension / 2 + 22.dp.toPx() * k, style = Stroke(3.dp.toPx()))
        })
    }
}

