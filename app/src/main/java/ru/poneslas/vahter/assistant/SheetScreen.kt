package ru.poneslas.vahter.assistant

import ru.poneslas.vahter.R
import ru.poneslas.vahter.tr
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.height
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.drawscope.rotate
import kotlinx.coroutines.launch
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import ru.poneslas.vahter.ui.Ic
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.poneslas.vahter.App
import ru.poneslas.vahter.ui.Composer
import ru.poneslas.vahter.ui.Feed
import ru.poneslas.vahter.ui.Ink
import ru.poneslas.vahter.ui.LiveLine
import ru.poneslas.vahter.ui.VahterTheme
import ru.poneslas.vahter.core.Primary
import ru.poneslas.vahter.media.CameraPanel
import ru.poneslas.vahter.core.Brains
import ru.poneslas.vahter.ui.RecUi
import ru.poneslas.vahter.core.FeedItem
import ru.poneslas.vahter.ui.ModeTabs
import ru.poneslas.vahter.ui.PromptStrip
import ru.poneslas.vahter.core.Modes
import ru.poneslas.vahter.core.BrainDesc
import ru.poneslas.vahter.ui.RecPill
import ru.poneslas.vahter.ui.UpLine
import ru.poneslas.vahter.media.PickerActivity
import ru.poneslas.vahter.core.Conversation
import ru.poneslas.vahter.pisar.model.ModelDownload
import ru.poneslas.vahter.app.SideKey
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.ui.res.stringResource
import androidx.compose.material3.Icon
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.ui.unit.Density
import ru.poneslas.vahter.ui.TopBar
import ru.poneslas.vahter.ui.IconBtn
import ru.poneslas.vahter.ui.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import ru.poneslas.vahter.ui.seconds

/** Что сейчас делает лист (ui.js, sheetSt): ждёт, слушает, распознаёт, делает сам (янтарь — телефон), звонит наверх (синий — мозг). */
enum class SheetSt { IDLE, REC, RECOG, THINK, UP }

/**
 * Лист поверх любого приложения (ui.js, overlay; Продукт 5.1). Шапки нет: ряд — слева маленький знак Вахтёра (и строка связи,
 * только если что-то не так), по центру ручка, справа круглые ⚙ ⤢ ✕. Пустой лист — сцена: знак по центру и «Вам чего?».
 * Снизу за строкой ввода — свечение по состоянию. Развернуть — лист растёт до полного экрана, сверху проявляется шапка
 * приложения, потом startAssistantActivity; свернуть из приложения — то же в обратную сторону ([Sheet.shrinkOnShow]).
 */
@Composable
fun SheetScreen(onClose: () -> Unit, onExpand: () -> Unit, onSettings: () -> Unit, onNeedPerm: (String) -> Unit, session: AssistSession, onAddMode: () -> Unit = onSettings, onEditMode: (String) -> Unit = { onSettings() }) {
    val app = App.instance
    val state by app.conversation.state.collectAsState()
    var live by remember { mutableStateOf<String?>(null) }
    val camera by Sheet.camera.collectAsState()
    fun openCamera() {
        if (session.context.checkSelfPermission(android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) onNeedPerm(android.Manifest.permission.CAMERA)
        else Sheet.camera.value = true
    }
    var camFull by remember { mutableStateOf(false) }
    LaunchedEffect(camera) { if (!camera) camFull = false }
    // Смена вкладки: камера остаётся только если новый режим — фото-режим (там камера — главное действие).
    var lastMode by remember { mutableStateOf(state.mode) }
    LaunchedEffect(state.mode) {
        if (state.mode != lastMode) {
            lastMode = state.mode
            val c = state.current
            Sheet.camera.value = Sheet.camera.value && c.primary == Primary.PHOTO && c.accepts.photo
        }
    }
    val cameraPanel: @Composable (Modifier, Boolean) -> Unit = { m, full ->
        CameraPanel(session, state.current, Brains.byId(state.current.brain).maxImages, full,
            onClose = { Sheet.camera.value = false }, onCollapse = { camFull = false },
            onSend = { files, question -> Sheet.camera.value = false; app.conversation.attach(files, if (files.size > 1) tr(R.string.sheet_via_album, files.size) else tr(R.string.sheet_via_photo)); if (question.isNotBlank()) app.conversation.send(question, tr(R.string.sheet_via_voice)) },
            modifier = m)
    }
    VahterTheme {
        if (camera && camFull) { cameraPanel(Modifier.fillMaxSize(), true); return@VahterTheme }
        // Баг №16. Окно помощника система при клавиатуре сдвигает вверх (прокрутка к полю) — отключить нельзя.
        // Поэтому отступ снизу = высота клавиатуры минус уже сделанный сдвиг; без этого лист уезжал выше клавиатуры.
        val d = LocalDensity.current
        val view = LocalView.current
        val imePx = imeBottom(d)
        var pan by remember { mutableStateOf(0) }
        LaunchedEffect(imePx) { val loc = IntArray(2); view.getLocationOnScreen(loc); pan = (-loc[1]).coerceAtLeast(0) }
        val navPx = navBottom(d)
        val bottomPad = with(d) { (if (imePx > 0) (imePx - pan).coerceAtLeast(0) else navPx).toDp() }
        val brain = Brains.byId(state.current.brain)
        val keyV by app.keyVersion.collectAsState()
        val connected = remember(keyV, brain.id) { app.hasKey(brain.id) }
        val from by Sheet.from.collectAsState()
        val start = (from[state.mode] ?: 0).coerceAtMost(state.feed.size)
        val empty = state.feed.size <= start
        val phase by RecUi.phase.collectAsState()
        val st = when {
            phase == RecUi.Phase.LISTENING -> SheetSt.REC
            phase == RecUi.Phase.PROCESSING -> SheetSt.RECOG
            state.busy && state.feed.lastOrNull() !is FeedItem.Ai -> SheetSt.UP
            state.busy -> SheetSt.THINK
            else -> SheetSt.IDLE
        }
        // Часы свечения: читаются в drawBehind — перерисовка каждый кадр без перекомпоновки.
        val glowT = seconds()
        // Разворот в приложение и обратно: [grow] 0 — лист, 1 — полный экран.
        val scope = androidx.compose.runtime.rememberCoroutineScope()
        val grow = remember { Animatable(if (Sheet.shrinkOnShow) 1f else 0f) }
        var natural by remember { mutableStateOf(0) }
        val easing = CubicBezierEasing(.3f, .8f, .3f, 1f)
        fun expandNow() {
            if (grow.isRunning || grow.value > 0f) return
            scope.launch { grow.animateTo(1f, tween(380, easing = easing)); onExpand() }
        }
        LaunchedEffect(natural > 0) {
            if (natural > 0 && Sheet.shrinkOnShow) { Sheet.shrinkOnShow = false; grow.animateTo(0f, tween(380, easing = easing)) }
        }
        // Окно листа живёт между показами: при каждом показе — снова лист; свернули из приложения — садится с полного экрана.
        val shown by Sheet.visible.collectAsState()
        LaunchedEffect(shown) {
            if (!shown) return@LaunchedEffect
            if (Sheet.shrinkOnShow && natural > 0) { Sheet.shrinkOnShow = false; grow.snapTo(1f); grow.animateTo(0f, tween(380, easing = easing)) }
            else if (!Sheet.shrinkOnShow) grow.snapTo(0f)
        }

        // .ov .dim — затемнение rgba(14,26,54,.32), лист не закрывает; при развороте гаснет
        BoxWithConstraints(Modifier.fillMaxSize().background(Color(0x520E1A36).copy(alpha = .32f * (1f - grow.value))).clickable(
            interactionSource = remember { MutableInteractionSource() }, indication = null) { },
            contentAlignment = Alignment.BottomCenter,
        ) {
            val fullPx = constraints.maxHeight
            val cue by Sheet.sideCue.collectAsState()
            if (cue) SideCue(Modifier.align(Alignment.TopEnd))
            val g = grow.value
            val corner = 30.dp
            // .sheet: на всю ширину, скруглены верхние углы, бумага до низа экрана; отступ под жесты и клавиатуру — внутри листа.
            Column(
                Modifier.fillMaxWidth()
                    .then(when {
                        camera -> Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top)).padding(top = 80.dp).fillMaxHeight()
                        g > 0f && natural > 0 -> Modifier.height(with(d) { (natural + (fullPx - natural) * g).toInt().toDp() })
                        else -> Modifier
                    })
                    .onGloballyPositioned { if (grow.value == 0f && !grow.isRunning) natural = it.size.height }
                    .graphicsLayer { alpha = if (Sheet.shrinkOnShow && natural == 0) 0f else 1f }
                    .shadow(24.dp, RoundedCornerShape(topStart = corner, topEnd = corner), ambientColor = Color(0x660E1A36), spotColor = Color(0x660E1A36))
                    .clip(RoundedCornerShape(topStart = corner, topEnd = corner)).background(Ink.paper)
                    .then(if (camera) Modifier else Modifier.drawBehind { glow(st, 1f - g, glowT) })
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                    .padding(bottom = bottomPad),
            ) {
                // Шапка приложения проявляется сверху, пока лист растёт: к подмене он уже выглядит как экран приложения.
                if (g > 0f) Box(Modifier.fillMaxWidth().height(with(d) { (92.dp.toPx() * g).toDp() }).graphicsLayer { alpha = g }) {
                    TopBar(state.current.title, tr(R.string.set_link_status, brain.title, tr(if (connected) R.string.set_link_on else R.string.set_link_off)),
                        onBack = {}, backIcon = Ic.down, dot = if (connected) Ink.server else Ink.local) {
                        IconBtn(Ic.gear, tr(R.string.ui_settings)) {}
                        IconBtn(Ic.more, tr(R.string.restart_more)) {}
                    }
                }
                // Ряд листа: потянуть вниз — закрыть, вверх — развернуть в приложение (баг №5).
                val density = LocalDensity.current
                var drag by remember { mutableStateOf(0f) }
                val dragArea = Modifier.pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onDragStart = { drag = 0f },
                        onVerticalDrag = { ch, dy -> ch.consume(); drag += dy },
                        onDragEnd = {
                            val px = with(density) { 48.dp.toPx() }
                            if (drag > px) onClose() else if (drag < -px) expandNow()
                            drag = 0f
                        },
                        onDragCancel = { drag = 0f },
                    )
                }
                Row(dragArea.fillMaxWidth().height(52.dp * (1f - g)).graphicsLayer { alpha = 1f - g; translationY = drag.coerceAtLeast(0f) * 0.4f }
                    .padding(start = 16.dp, end = 10.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    // слева: маленький знак и строка связи — только если что-то не так; в пустом листе — ничего
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        if (!empty) {
                            VMark(st, 18.dp)
                            if (!connected) {
                                Spacer(Modifier.width(7.dp))
                                Text(tr(R.string.sheet_no_key).trim().removePrefix("·").trim(), fontSize = 12.sp, color = Ink.soft, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.pointerInput(state.mode) { detectTapGestures(onLongPress = { app.conversation.askRestart() }) })
                            }
                        }
                    }
                    Box(Modifier.height(24.dp).width(56.dp).clickable(onClickLabel = tr(R.string.sheet_expand)) { expandNow() }, contentAlignment = Alignment.Center) {
                        Box(Modifier.size(width = 36.dp, height = 4.dp).clip(RoundedCornerShape(2.dp)).background(Ink.grab))
                    }
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End)) {
                        if (camera) {
                            CBtn(Ic.expand, stringResource(R.string.cam_fullscreen)) { camFull = true }
                            CBtn(Ic.x, stringResource(R.string.cam_close)) { Sheet.camera.value = false }
                        } else {
                            CBtn(Ic.gear, tr(R.string.ui_settings), onSettings)
                            CBtn(Ic.expand, tr(R.string.sheet_expand_app)) { expandNow() }
                            CBtn(Ic.x, tr(R.string.ui_close), onClose)
                        }
                    }
                }
                if (camera) {
                    cameraPanel(Modifier.weight(1f).padding(top = 6.dp), false)
                    ModeTabs(state, app.conversation::selectMode, onAddMode)
                } else {
                    PromptStrip(state.current, onEditMode)
                    SheetStrip(state.mode == Modes.MAIN && !connected && brain.kind == BrainDesc.Kind.API) { session.openApp(settings = false, brain = true) }
                    val first by Sheet.firstSheet.collectAsState()
                    if (g > 0f) Spacer(Modifier.weight(1f))
                    // Пустой лист — не меньше ~54% экрана (ui.js, .s2.isempty): сцене достаётся всё, кроме ряда, строки ввода и вкладок.
                    if (empty) Box(Modifier.fillMaxWidth().heightIn(min = with(d) { (fullPx * .54f).toDp() } - 236.dp), contentAlignment = Alignment.Center) {
                        Scene(st, state, first && st == SheetSt.IDLE, connected, start,
                            onSay = { app.conversation.send(it, tr(R.string.sheet_via_tap)) }, onPrev = { expandNow() })
                    } else {
                        // Волна записи — поверх низа ленты, а не отдельной строкой: высота листа при записи не меняется.
                        Box(Modifier.fillMaxWidth()) {
                            Feed(state, onAct = { a, undo -> app.conversation.onActButton(a, undo) }, onCard = { c, a -> app.conversation.onCardButton(c, a) }, onCancelAttach = app.conversation::cancelAttach,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp, max = 420.dp), from = start)
                            RecPill(Modifier.align(Alignment.BottomCenter))
                        }
                        UpLine(state)
                    }
                    if (!(empty && st != SheetSt.IDLE)) LiveLine(live, pill = false)
                    Composer(
                        state = state,
                        voice = app.voice,
                        // В листе «Вам чего?» — заголовком сцены; в поле — подсказка режима.
                        placeholder = if (!connected) tr(R.string.cmp_hint_no_brain) else null,
                        onDraft = app.conversation::setDraft,
                        onSend = { text, via -> app.conversation.send(text, via) },
                        onSelectMode = app.conversation::selectMode,
                        onAddMode = onAddMode,
                        onLive = { live = it },
                        autoListen = Sheet.autoListen,
                        onNeedMic = { onNeedPerm(android.Manifest.permission.RECORD_AUDIO) },
                        onCamera = ::openCamera,
                        onGallery = { PickerActivity.launchFromSheet(session) },
                    )
                }
            }
        }
    }
}

/** Круглая кнопка ряда листа: 36, фон #ECEEE8 (ui.js, .cbtn). */
@Composable
private fun CBtn(icon: Int, label: String, onClick: () -> Unit) =
    Box(Modifier.size(36.dp).clip(CircleShape).background(Ink.chipBg).clickable(onClickLabel = label, onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(painterResource(icon), label, Modifier.size(18.dp), tint = Ink.soft)
    }

/** Свечение снизу за строкой ввода (ui.js, .s2 .glow): ждёт — еле дышит; слушает — янтарь, сильно, пульс; распознаёт и делает сам — янтарь; звонит наверх — синий. */
private fun DrawScope.glow(st: SheetSt, k: Float, t: Float) {
    if (k <= 0f) return
    val (alpha, hDp, period) = when (st) {
        SheetSt.REC -> Triple(1f, 320f, 1.1f)
        SheetSt.RECOG, SheetSt.THINK -> Triple(.85f, 260f, 1.8f)
        SheetSt.UP -> Triple(.9f, 260f, 1.8f)
        SheetSt.IDLE -> Triple(.4f + .25f * (.5f + .5f * kotlin.math.sin(t / 4f * 2 * Math.PI.toFloat())), 260f, 0f)
    }
    val pulse = if (period > 0f) 1f + .12f * (.5f + .5f * kotlin.math.sin(t / period * 2 * Math.PI.toFloat())) else 1f
    val c = if (st == SheetSt.UP) Ink.server else Ink.local
    val h = hDp * density * pulse
    val center = Offset(size.width / 2, size.height)
    // эллипс 130% ширины × высота свечения: круг радиусом h, растянутый по горизонтали
    withTransform({ scale(1.3f * size.width / h, 1f, center) }) {
        drawCircle(Brush.radialGradient(0f to c.copy(alpha = .30f), .45f to c.copy(alpha = .10f), .75f to c.copy(alpha = 0f), 1f to c.copy(alpha = 0f), center = center, radius = h),
            radius = h, center = center, alpha = alpha * k)
    }
}

/** Знак Вахтёра — «лампа на вахте» (ui.js, vMark): ядро и два кольца; состояние листа задаёт цвет и движение. */
@Composable
fun VMark(st: SheetSt, size: Dp) {
    val t = seconds()
    val c = if (st == SheetSt.UP) Ink.server else Ink.local
    val reduce = android.provider.Settings.Global.getFloat(LocalContext.current.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    Canvas(Modifier.size(size)) {
        val sec = if (reduce) 0f else t
        val w = this.size.minDimension
        val mid = Offset(w / 2, w / 2)
        val small = w < 30.dp.toPx()
        fun wave(per: Float, off: Float = 0f) = (((sec + off) / per) % 1f)
        // ядро «дышит»: в записи — часто
        val br = if (st == SheetSt.REC) .55f else 3.2f
        val core = .9f + .14f * (.5f + .5f * kotlin.math.sin(wave(br) * 2 * Math.PI.toFloat()))
        val r0 = w * .19f * core
        drawCircle(Brush.radialGradient(listOf(c.copy(alpha = .45f), c.copy(alpha = 0f)), center = mid, radius = r0 + w * .35f), radius = r0 + w * .35f, center = mid)
        drawCircle(c, r0, mid)
        val r1 = w * (.5f - .17f); val r2 = w * .5f
        val s1 = (if (small) 1.5f else 2f).dp.toPx(); val s2 = (if (small) 1f else 1.5f).dp.toPx()
        when (st) {
            SheetSt.REC -> listOf(0f, .35f).forEachIndexed { i, off ->
                val p = wave(1.1f, off)
                val base = if (i == 0) r1 else r2
                drawCircle(c.copy(alpha = .7f * (1 - p)), base * (.85f + .6f * p), mid, style = Stroke(if (i == 0) s1 else s2))
            }
            SheetSt.RECOG, SheetSt.THINK, SheetSt.UP -> {
                rotate(360f * wave(1.6f), mid) {
                    drawCircle(c.copy(alpha = .8f), r1 - s1 / 2, mid, style = Stroke(s1,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))))
                }
                drawCircle(c.copy(alpha = .2f), r2 - s2 / 2, mid, style = Stroke(s2))
            }
            SheetSt.IDLE -> {
                drawCircle(c.copy(alpha = .45f), r1 - s1 / 2, mid, style = Stroke(s1))
                drawCircle(c.copy(alpha = .2f), r2 - s2 / 2, mid, style = Stroke(s2))
            }
        }
    }
}

/**
 * Пустой лист — сцена (ui.js, sheetEmpty): знак по центру и одна фраза. В записи говорит знак: «Слушаю» / «Отпустите — и отправлю»,
 * потом «Распознаю на телефоне». Чипы — только в первый раз (финал мастера); после 30 минут без дела — «Прошлый разговор ›».
 */
@Composable
private fun Scene(st: SheetSt, state: Conversation.State, chips: Boolean, brain: Boolean, from: Int, onSay: (String) -> Unit, onPrev: () -> Unit) {
    val main = state.mode == Modes.MAIN
    val (cap, sub) = when (st) {
        SheetSt.REC -> tr(R.string.sheet_listen) to tr(R.string.sheet_listen_sub)
        SheetSt.RECOG -> tr(R.string.sheet_recog) to tr(R.string.sheet_recog_sub)
        else -> (if (main) tr(R.string.sheet_hello) else state.current.title) to (if (main) "" else state.current.hint)
    }
    Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 18.dp, bottom = 22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        VMark(st, 72.dp)
        Spacer(Modifier.height(18.dp))
        Text(cap, fontSize = 23.sp, fontWeight = FontWeight.Medium, color = Ink.ink, textAlign = TextAlign.Center)
        if (sub.isNotEmpty()) Text(sub, fontSize = 13.5.sp, color = Ink.soft, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
        if (chips && main) FlowRow(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            (listOf(tr(R.string.sheet_chip_alarm), tr(R.string.sheet_chip_timer)) + if (brain) listOf(tr(R.string.sheet_chip_brain)) else emptyList()).forEach { c ->
                Text(c, fontSize = 13.5.sp, color = Ink.ink, modifier = Modifier.clip(RoundedCornerShape(50)).background(Ink.surf).border(1.dp, Ink.line, RoundedCornerShape(50))
                    .clickable { onSay(c.replace(Regex("^\\S+ (?=\\p{Lu})"), "")) }.padding(horizontal = 12.dp, vertical = 7.dp))
            }
        }
        if (from > 0 && st == SheetSt.IDLE) {
            val n = state.feed.take(from).count { it is FeedItem.Me || it is FeedItem.Ai }
            Row(Modifier.padding(top = 18.dp).clip(RoundedCornerShape(50)).background(Ink.surf).border(1.dp, Ink.line, RoundedCornerShape(50)).clickable(onClick = onPrev)
                .padding(start = 10.dp, end = 12.dp, top = 7.dp, bottom = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(Ic.hist), null, Modifier.size(17.dp), tint = Ink.soft)
                Spacer(Modifier.width(8.dp))
                Text(tr(R.string.sheet_prev, n), fontSize = 13.sp, color = Ink.soft)
                Spacer(Modifier.width(4.dp))
                Icon(painterResource(Ic.chev), null, Modifier.size(17.dp), tint = Ink.soft)
            }
        }
    }
}

/**
 * Строка над лентой: голоса ещё нет (качается, не скачан, не скачалось) или мозг не подключён (без шутки; «Подключить» — экран мозга,
 * «Назад» — снова лист). Пока голоса нет, строка не пропадает: лист могли открыть раньше, чем докачалось, или загрузка оборвалась.
 * «Скачать» / «Повторить» запускают загрузку прямо отсюда — дальше та же строка показывает проценты.
 */
@Composable
private fun SheetStrip(noBrain: Boolean, onConnect: () -> Unit) {
    val ctx = LocalContext.current
    val dl by ModelDownload.state.collectAsState()
    val installed by App.instance.voice.installed.collectAsState()
    val pct = (dl as? ModelDownload.State.Running)?.percent
    val failed = dl is ModelDownload.State.Failed
    val noVoice = !installed
    if (!noVoice && !noBrain) return
    val (icon, text, act) = when {
        pct != null -> Triple("⬇", tr(R.string.sheet_strip_dl, pct), null)
        noVoice && failed -> Triple("⬇", tr(R.string.sheet_strip_dl_failed), tr(R.string.sheet_strip_retry) to { ModelDownload.start(ctx) })
        noVoice -> Triple("⬇", tr(R.string.sheet_strip_no_voice), tr(R.string.voice_download) to { ModelDownload.start(ctx) })
        else -> Triple("🧠", tr(R.string.sheet_strip_no_brain), tr(R.string.ui_connect) to onConnect)
    }
    Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 2.dp).clip(RoundedCornerShape(12.dp)).background(Ink.chipBg)
        .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(icon, fontSize = 13.sp)
        Spacer(Modifier.width(8.dp))
        Text(text, fontSize = 13.sp, color = Ink.soft, modifier = Modifier.weight(1f).padding(vertical = 6.dp))
        if (act != null) TextButton(act.first, onClick = act.second)
    }
}

/** Один раз после мастера — у боковой кнопки корпуса: откуда берётся лист (на незнакомом телефоне — «Домой»). */
@Composable
private fun SideCue(modifier: Modifier) {
    val known = SideKey.read(LocalContext.current) != SideKey.State.UNKNOWN
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(6000); Sheet.sideCue.value = false }
    Row(modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top)).padding(top = 150.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(tr(if (known) R.string.sheet_cue_side else R.string.sheet_cue_home), fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Color.White,
            modifier = Modifier.shadow(10.dp, RoundedCornerShape(12.dp)).clip(RoundedCornerShape(12.dp)).background(Ink.ink).padding(horizontal = 12.dp, vertical = 8.dp))
        Text("▶", fontSize = 12.sp, color = Ink.ink, modifier = Modifier.padding(start = 0.dp))
    }
}

@Composable private fun imeBottom(d: Density) = WindowInsets.ime.getBottom(d)
@Composable private fun navBottom(d: Density) = WindowInsets.navigationBars.getBottom(d)
