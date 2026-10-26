package ru.poneslas.vahter.assistant

import android.os.Build
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.animation.core.snap
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import ru.poneslas.vahter.App
import ru.poneslas.vahter.R
import ru.poneslas.vahter.core.Conversation
import ru.poneslas.vahter.core.Primary
import ru.poneslas.vahter.tr
import ru.poneslas.vahter.ui.BaseText
import ru.poneslas.vahter.ui.Ic
import ru.poneslas.vahter.ui.Ink
import ru.poneslas.vahter.ui.ModeTabs
import ru.poneslas.vahter.ui.RecUi
import ru.poneslas.vahter.voice.Voice
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sin

/*
 * Лист «солнышко» (прототип 25.10, docs/design/prototype — ui.js, orbHtml; ветка proto/orb-sheet).
 * Главная кнопка листа — солнышко по центру: держать — говорить (или снять, если в режиме главное — камера). Слева скрепка,
 * справа второе (камера или микрофон). Поле ввода в листе — только когда нужно: правка надиктованного (✎), вложение с текстом, нет голоса.
 * Держишь: ← отмена, → ✎ правка (замка нет); короткое нажатие — без рук: ✕ · солнышко-«Отправить» · ✎.
 * Вложение со скрепки ждёт над солнышком: смахнуть солнышко вверх — уходит сразу (солнышко улетает), нажать или держать — сказать к нему.
 * Анимация одна и живая: ядро, кольца и волны солнышка идут за громкостью микрофона (LevelMeter), свечение листа — вместе с ними.
 */

/** Ждущее вложение листа: выбрано скрепкой, ещё не ушло. Файл — только для Hermes (как у «Файл» в меню). */
data class Staged(val modeId: String, val paths: List<String>, val via: String, val file: File? = null, val name: String? = null)

object OrbSheet {
    /** Лист «солнышко» включён (прототипная ветка). */
    const val ON = true
    val staged = MutableStateFlow<Staged?>(null)
    /** Режимы, где поле ввода открыто вручную (✎, «написать», правка надиктованного). У каждого режима своё; после отправки — закрыто. */
    val field = MutableStateFlow<Set<String>>(emptySet())
    /** Солнышко только что улетело — следующее появление с «возвратом». */
    @Volatile var back = false

    fun stagedFor(mode: String) = staged.value?.takeIf { it.modeId == mode }
    fun openField(mode: String) { field.value = field.value + mode }
    fun closeField(mode: String) { field.value = field.value - mode }

    /** Вложение уходит: фото — пузырём с вопросом одной репликой (Conversation.attach + send), файл — Hermes (sendFile). */
    fun sendStaged(text: String, via: String) {
        val s = staged.value ?: return
        staged.value = null
        val conv = App.instance.conversation
        if (s.file != null) { conv.setDraft(text); conv.sendFile(s.file, s.name ?: "file") }
        else { conv.attach(s.paths, if (text.isBlank()) s.via else "$via · ${s.via}"); conv.send(text, via) }
        closeField(s.modeId)
    }

    /** Отправить из листа: с ждущим вложением — вместе с ним; после отправки поле режима закрывается — снова солнышко. */
    fun send(text: String, via: String) {
        val conv = App.instance.conversation
        val mode = conv.state.value.mode
        if (stagedFor(mode) != null) sendStaged(text, via) else conv.send(text, via)
        closeField(mode)
    }

    /** Распознанное в листе: «отправить» — с тем, что уже в поле; «править» — в поле, поле открыто. */
    val sink = object : CaptureSink {
        override val editable = true
        override fun text(text: String, how: How) {
            val conv = App.instance.conversation
            val st = conv.state.value
            val full = (st.draft + " " + text).trim()
            if (how == How.EDIT) { conv.setDraft(full); openField(st.mode) }
            else send(full, tr(R.string.sheet_via_voice))
        }
    }
}

/** Последний вызов кнопкой, отработанный солнышком (как autoSeen у строки ввода). */
private var orbAutoSeen = 0

/** Мягкая «ведущая» кривая: разгон к улёту, как у отправки в мессенджерах. */
private val FlyEase = CubicBezierEasing(.55f, 0f, .75f, .2f)
private val SoftEase = CubicBezierEasing(.3f, .8f, .3f, 1f)

/**
 * Нижняя часть листа «солнышко»: ждущее вложение, ряд [скрепка · солнышко · второе], под ним вкладки режимов (в записи — время).
 * [st] — что делает лист (солнышко показывает его само: слушает, распознаёт, звонит наверх, думает). Запись — [Capture].
 */
@Composable
fun OrbBar(
    state: Conversation.State,
    voice: Voice,
    st: SheetSt,
    onWrite: () -> Unit,
    onSelectMode: (String) -> Unit,
    onAddMode: () -> Unit,
    onCamera: (() -> Unit)?,
    onGallery: (() -> Unit)?,
    onFile: (() -> Unit)?,
    autoListen: StateFlow<Int>,
) {
    val scope = rememberCoroutineScope()
    val d = LocalDensity.current
    val mode = state.current
    val photoMain = mode.primary == Primary.PHOTO && mode.accepts.photo && onCamera != null
    val stagedAll by OrbSheet.staged.collectAsState()
    val staged = stagedAll?.takeIf { it.modeId == state.mode }
    val cap = Capture.state

    // Вызов боковой кнопкой: сразу слушает без рук (в голосовом режиме).
    val auto by autoListen.collectAsState()
    LaunchedEffect(auto) { if (auto > orbAutoSeen) { orbAutoSeen = auto; if (!photoMain) Capture.send(Ev.Free(Capture.editable)) } }

    // Солнышко за пальцем: пока держат — сразу, отпустили — пружинкой на место.
    val dx by animateFloatAsState(cap.x, if (cap.pressing) snap() else spring(.6f, Spring.StiffnessMediumLow), label = "dx")
    val lift = remember { Animatable(0f) }
    var pre by remember { mutableStateOf(false) }   // палец на солнышке с вложением, жест ещё не понят

    // Улёт (смахнули вверх с вложением): 0..1 — солнышко сжимается и улетает вместе с карточкой; потом возвращается с пружинкой.
    val fly = remember { Animatable(0f) }
    var flown by remember { mutableStateOf(false) }
    val back = remember { Animatable(if (OrbSheet.back) 0f else 1f) }
    LaunchedEffect(Unit) { if (OrbSheet.back) { OrbSheet.back = false; back.animateTo(1f, spring(.5f, 380f)) } }
    val view = LocalView.current
    suspend fun flyAway() {
        view.performHapticFeedback(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY)
        fly.animateTo(1f, tween(480, easing = FlyEase))
        flown = true
        lift.snapTo(0f)
        OrbSheet.sendStaged("", "")
        back.snapTo(0f); fly.snapTo(0f)
        delay(90)
        back.animateTo(1f, spring(.5f, 380f))
        flown = false
    }

    val rec = cap.rec
    val recording = rec == Rec.HOLD || rec == Rec.FREE
    Column(Modifier.fillMaxWidth()) {
        // карточка помнит последнее вложение — на выходе ей есть что показать; улетевшая не мигает обратно (alpha 0, место сжимается)
        var lastStaged by remember { mutableStateOf(staged) }
        if (staged != null) lastStaged = staged
        AnimatedVisibility(staged != null,
            enter = fadeIn(tween(180)) + expandVertically(tween(260, easing = SoftEase), Alignment.Bottom) + slideInVertically(tween(260, easing = SoftEase)) { it / 2 },
            exit = fadeOut(tween(140)) + shrinkVertically(tween(220, easing = SoftEase), Alignment.Bottom)) {
            lastStaged?.let { s ->
                StagedCard(s, hint = tr(R.string.orb_swipe_hint), onRemove = { OrbSheet.staged.value = null },
                    modifier = Modifier.graphicsLayer {
                        val p = fly.value
                        translationY = -lift.value * .6f - 170.dp.toPx() * p
                        scaleX = 1f - .4f * p; scaleY = 1f - .4f * p
                        alpha = if (flown) 0f else 1f - p
                    })
            }
        }
        Box(Modifier.fillMaxWidth().height(124.dp), contentAlignment = Alignment.Center) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 36.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                // слева: скрепка → в записи «✕» (держат — цель, без рук — кнопка)
                val left = when {
                    rec == Rec.HOLD -> "xcue"; rec == Rec.FREE -> "x"
                    mode.accepts.photo && onGallery != null || onFile != null -> "clip"; else -> ""
                }
                Slot(left) { k ->
                    when (k) {
                        "xcue", "x" -> SideBtn(Ic.x, tr(R.string.orb_cancel), cue = k == "xcue", near = cap.nearCancel) { Capture.send(Ev.TapCancel) }
                        "clip" -> AttachBtn(onGallery, onFile)
                    }
                }
                // солнышко
                Box(Modifier.size(104.dp), contentAlignment = Alignment.Center) {
                    val icon = when {
                        rec == Rec.FREE -> Ic.send
                        recording -> Ic.mic
                        photoMain && staged == null -> Ic.cam
                        else -> Ic.mic
                    }
                    Sun(st, 92.dp, icon, pressed = cap.pressing || pre, active = recording,
                        label = when { rec == Rec.FREE -> tr(R.string.ui_send); staged != null -> tr(R.string.orb_talk_staged); photoMain -> tr(R.string.cmp_camera); else -> tr(R.string.orb_talk) },
                        dim = !photoMain && !voice.installed.collectAsState().value,
                        modifier = Modifier
                            .graphicsLayer {
                                val p = fly.value
                                val squash = (p / .2f).coerceAtMost(1f)
                                val q = ((p - .2f) / .8f).coerceIn(0f, 1f)
                                translationX = dx * density
                                translationY = -lift.value + 6.dp.toPx() * squash - 320.dp.toPx() * q
                                val s = (1f - .14f * squash - .7f * q) * (.3f + .7f * back.value)
                                scaleX = s; scaleY = s * (1f + .08f * q)   // в полёте чуть вытягивается
                                alpha = (1f - q * q) * back.value.coerceIn(0f, 1f)
                            }
                            .orbGestures(staged = staged != null, photoMain = photoMain, onCamera = onCamera, lift = lift, onPre = { pre = it },
                                onFly = { scope.launch { flyAway() } }, flyPx = with(d) { 56.dp.toPx() }, cs = scope),
                        trail = fly.value)
                }
                // справа: камера (или микрофон фото-режима — та же кнопка записи), с вложением — ✎; в записи — «✎»
                val right = when {
                    rec == Rec.HOLD -> "ecue"; rec == Rec.FREE -> "e"
                    staged != null -> "write"
                    photoMain && mode.accepts.voice -> "mic"
                    !photoMain && mode.accepts.photo && onCamera != null -> "cam"; else -> ""
                }
                Slot(right) { k ->
                    when (k) {
                        "ecue", "e" -> SideBtn(Ic.edit, tr(R.string.orb_edit), cue = k == "ecue", near = cap.nearEdit) { Capture.send(Ev.TapEdit) }
                        "write" -> SideBtn(Ic.edit, tr(R.string.orb_write), onClick = onWrite)
                        "mic" -> SideBtn(Ic.mic, tr(R.string.orb_talk), tap = false, modifier = Modifier.captureButton())
                        "cam" -> SideBtn(Ic.cam, tr(R.string.cmp_camera)) { onCamera?.invoke() }
                    }
                }
            }
            androidx.compose.animation.AnimatedVisibility(visible = staged != null && !recording && rec != Rec.RECOG && !flown, Modifier.align(Alignment.TopCenter),
                enter = fadeIn(tween(200, 150)), exit = fadeOut(tween(100))) {
                SwipeHint(Modifier.graphicsLayer { translationY = -lift.value })
            }
        }
        // вкладки режимов; в записи на их месте — время (режим в записи не переключают)
        AnimatedContent(recording, transitionSpec = { fadeIn(tween(160)) togetherWith fadeOut(tween(120)) }, label = "tabs") { r ->
            if (r) RecTime(Capture.t0) else ModeTabs(state, onSelectMode, onAddMode)
        }
    }
}

/**
 * Жесты большого солнышка. Камера в центре: нажатие — снять. С вложением сначала понять жест: вверх — отправить сразу,
 * иначе (220 мс или сдвиг вбок) — запись, дальше как у любой кнопки записи ([trackCapture]). Без вложения — сразу запись.
 */
private fun Modifier.orbGestures(
    staged: Boolean, photoMain: Boolean, onCamera: (() -> Unit)?, lift: Animatable<Float, *>, onPre: (Boolean) -> Unit, onFly: () -> Unit, flyPx: Float, cs: CoroutineScope,
) = pointerInput(staged, photoMain) {
    val slop = viewConfiguration.touchSlop
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false).consume()
        val at = SystemClock.uptimeMillis()
        val rec = Capture.state.rec
        // без рук или камера в центре: своё
        if (rec == Rec.FREE) { trackCapture(at); return@awaitEachGesture }
        if (rec != Rec.IDLE) return@awaitEachGesture
        if (photoMain && !staged) {
            while (true) { val c = awaitPointerEvent().changes.first(); if (!c.pressed) break }
            onCamera?.invoke(); return@awaitEachGesture
        }
        if (!staged) { trackCapture(at); return@awaitEachGesture }
        onPre(true)
        try {
            var x = 0f; var y = 0f; var axis = 0   // 1 — вбок, 2 — вверх
            while (true) {
                val wait = (220 - (SystemClock.uptimeMillis() - at)).coerceAtLeast(1)
                val ev = withTimeoutOrNull(wait) { awaitPointerEvent() }
                if (ev == null) { if (axis != 2) { onPre(false); trackCapture(at, startX = x); return@awaitEachGesture }; continue }
                val c = ev.changes.first()
                if (!c.pressed) break
                val p = c.positionChange(); x += p.x; y += p.y; c.consume()
                if (axis == 0 && hypot(x, y) > slop) axis = if (-y > abs(x)) 2 else 1
                if (axis == 2) {
                    val up = (-y).coerceAtLeast(0f)
                    cs.launch { lift.snapTo(up * .85f) }
                    if (up > flyPx) { onFly(); return@awaitEachGesture }
                } else if (axis == 1) { onPre(false); trackCapture(at, startX = x); return@awaitEachGesture }
            }
            // отпустили, жест не начался: лёгкое нажатие — слушаю без рук; недотянули вверх — солнышко назад
            if (axis == 2) cs.launch { lift.animateTo(0f, spring(.5f, 500f)) }
            else Capture.send(Ev.Free(Capture.editable))
        } finally { onPre(false) }
    }
}

/** Место у солнышка: содержимое меняется «из точки» — скрепка становится целью «отмена», камера — «правкой». */
@Composable private fun Slot(key: String, content: @Composable (String) -> Unit) =
    AnimatedContent(key, Modifier.size(56.dp), transitionSpec = { (fadeIn(tween(160)) + scaleIn(tween(220), .6f)) togetherWith (fadeOut(tween(100)) + scaleOut(tween(140), .6f)) },
        contentAlignment = Alignment.Center, label = "slot") { Box(contentAlignment = Alignment.Center) { content(it) } }   // Box: кнопка остаётся 52, не растягивается до места

/** Боковая кнопка ряда: 52, белая. [cue] — цель жеста (без нажатия), [near] — палец дотянул: тёмная и чуть больше. */
@Composable
internal fun SideBtn(icon: Int, label: String, cue: Boolean = false, near: Boolean = false, modifier: Modifier = Modifier, tap: Boolean = true, onClick: () -> Unit = {}) {
    val k by animateFloatAsState(if (near) 1.14f else 1f, spring(.45f, 600f), label = "near")
    val bg = if (near) Ink.ink else Ink.surf
    Box(modifier.size(52.dp).graphicsLayer { scaleX = k; scaleY = k }.clip(CircleShape).background(bg)
        .then(if (cue) Modifier else Modifier.border(1.dp, Ink.line, CircleShape).then(if (tap) Modifier.clickable(onClickLabel = label, onClick = onClick) else Modifier))
        .semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        Icon(painterResource(icon), null, Modifier.size(21.dp), tint = if (near) Color.White else if (cue) Ink.soft else Ink.ink)
    }
}

/** Скрепка: меню раскрывается от неё (Material 3): галерея, файл. Камера — своей кнопкой справа. */
@Composable
private fun AttachBtn(onGallery: (() -> Unit)?, onFile: (() -> Unit)?) {
    var open by remember { mutableStateOf(false) }
    Box {
        SideBtn(Ic.clip, tr(R.string.cmp_attach)) { if (onFile == null && onGallery != null) onGallery() else open = !open }
        androidx.compose.material3.DropdownMenu(expanded = open, onDismissRequest = { open = false }, shape = RoundedCornerShape(18.dp), containerColor = Ink.surf) {
            if (onGallery != null) MenuItem(Ic.image, tr(R.string.cmp_gallery), tr(R.string.cmp_gallery_sub)) { open = false; onGallery() }
            if (onFile != null) MenuItem(Ic.doc, tr(R.string.cmp_file), tr(R.string.cmp_file_sub)) { open = false; onFile() }
        }
    }
}

@Composable
private fun MenuItem(icon: Int, title: String, sub: String, onClick: () -> Unit) =
    androidx.compose.material3.DropdownMenuItem(
        text = { Column { Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Ink.ink); Text(sub, fontSize = 12.sp, color = Ink.soft) } },
        leadingIcon = { Icon(painterResource(icon), null, Modifier.size(20.dp), tint = Ink.ink) },
        onClick = onClick)

/** ⌃ над солнышком, пока ждёт вложение: тихо «подпрыгивает» — подсказка смахнуть вверх. */
@Composable
private fun SwipeHint(modifier: Modifier) {
    val t = ru.poneslas.vahter.ui.seconds()
    Icon(painterResource(Ic.chev), null, modifier.size(20.dp).rotate(180f).graphicsLayer {   // ic_chev смотрит вниз — разворот: «вверх»
       
        val w = .5f + .5f * sin(t / 1.6f * 2 * PI.toFloat())
        translationY = -5.dp.toPx() * w; alpha = .3f + .6f * w
    }, tint = Ink.soft)
}

/** Время записи вместо вкладок: «● 0:07». */
@Composable
private fun RecTime(t0: Long) {
    var now by remember { mutableLongStateOf(SystemClock.uptimeMillis()) }
    LaunchedEffect(t0) { while (true) { now = SystemClock.uptimeMillis(); delay(250) } }
    val s = ((now - t0) / 1000).coerceAtLeast(0)
    val blink = ru.poneslas.vahter.ui.seconds()
    Row(Modifier.fillMaxWidth().height(42.dp).padding(bottom = 12.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(9.dp).alpha(.45f + .55f * (.5f + .5f * sin(blink * 2 * PI.toFloat()))).clip(CircleShape).background(Color(0xFFE53935)))
        Spacer(Modifier.width(8.dp))
        Text("%d:%02d".format(s / 60, s % 60), fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Ink.ink)
    }
}

/** Карточка ждущего вложения над солнышком (или над полем): миниатюра, что это, подсказка, ✕. */
@Composable
fun StagedCard(s: Staged, hint: String, onRemove: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.padding(start = 16.dp, end = 16.dp, bottom = 4.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Ink.surf)
        .border(1.dp, Ink.line, RoundedCornerShape(16.dp)).padding(start = 8.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).background(Ink.chipBg), contentAlignment = Alignment.Center) {
            val bmp = remember(s) { s.paths.firstOrNull()?.let { runCatching { thumb(it) }.getOrNull() } }
            if (s.file != null || bmp == null) Icon(painterResource(if (s.file != null) Ic.doc else Ic.image), null, Modifier.size(20.dp), tint = Ink.ink)
            else Image(bmp.asImageBitmap(), null, Modifier.size(44.dp), contentScale = ContentScale.Crop)
            if (s.paths.size > 1) Text("${s.paths.size}", fontSize = 10.sp, color = Color.White, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.align(Alignment.BottomEnd).padding(3.dp).clip(RoundedCornerShape(8.dp)).background(Ink.ink).padding(horizontal = 4.dp))
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            val title = when { s.file != null -> s.name ?: "file"; s.paths.size > 1 -> tr(R.string.orb_photos, s.paths.size); else -> tr(R.string.orb_photo) }
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Ink.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(hint, fontSize = 12.sp, color = Ink.soft, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box(Modifier.size(40.dp).clip(CircleShape).clickable(onClickLabel = tr(R.string.orb_remove), onClick = onRemove), contentAlignment = Alignment.Center) {
            Icon(painterResource(Ic.x), tr(R.string.orb_remove), Modifier.size(18.dp), tint = Ink.soft)
        }
    }
}

private fun thumb(path: String): android.graphics.Bitmap? {
    val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    android.graphics.BitmapFactory.decodeFile(path, o)
    var k = 1; while (o.outWidth / (k * 2) >= 132 && o.outHeight / (k * 2) >= 132) k *= 2
    return android.graphics.BitmapFactory.decodeFile(path, android.graphics.BitmapFactory.Options().apply { inSampleSize = k })
}

/**
 * Поле листа «солнышко» (правка надиктованного, текст к вложению, нет голоса): поле со скрепкой и справа то же солнышко, маленькое —
 * пустое поле: держать — говорить (отпустил — уходит вместе с текстом), короткое нажатие — без рук; есть текст — нажатие отправляет.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OrbField(
    state: Conversation.State,
    st: SheetSt,
    onDraft: (String) -> Unit,
    onSelectMode: (String) -> Unit,
    onAddMode: () -> Unit,
    onGallery: (() -> Unit)?,
    onFile: (() -> Unit)?,
    focus: Boolean,
    placeholder: String?,
) {
    val stagedAll by OrbSheet.staged.collectAsState()
    val staged = stagedAll?.takeIf { it.modeId == state.mode }
    val hasText = state.draft.isNotBlank()
    val draftNow by androidx.compose.runtime.rememberUpdatedState(state.draft)
    val modeNow by androidx.compose.runtime.rememberUpdatedState(state.mode)
    val fr = remember { FocusRequester() }
    // «Назад» прячет клавиатуру, а фокус остаётся — смотрим на саму клавиатуру: была и спрятали при пустом поле — к солнышку
    val ime = WindowInsets.isImeVisible
    var imeSeen by remember { mutableStateOf(false) }
    LaunchedEffect(ime) { if (ime) imeSeen = true else if (imeSeen && draftNow.isBlank()) { imeSeen = false; delay(120); OrbSheet.closeField(modeNow) } }
    LaunchedEffect(focus) { if (focus) runCatching { delay(120); fr.requestFocus() } }
    val cap = Capture.state
    val recording = cap.rec == Rec.HOLD || cap.rec == Rec.FREE
    val dx by animateFloatAsState(cap.x.coerceAtMost(0f), if (cap.pressing) snap() else spring(.6f, Spring.StiffnessMediumLow), label = "fdx")
    var pre by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        if (staged != null) StagedCard(staged, tr(R.string.orb_write_hint), onRemove = { OrbSheet.staged.value = null })
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 10.dp, top = 4.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).heightIn(min = 48.dp).clip(RoundedCornerShape(24.dp)).background(Ink.surf).border(1.dp, Ink.line, RoundedCornerShape(24.dp))
                .padding(start = 4.dp, end = 12.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                AnimatedContent(recording, transitionSpec = { fadeIn(tween(140)) togetherWith fadeOut(tween(100)) }, label = "fld", modifier = Modifier.weight(1f)) { r ->
                    if (r) Row(Modifier.fillMaxWidth().height(40.dp).padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        RecTimeInline(Capture.t0)
                        Spacer(Modifier.weight(1f))
                        // как полоса Писаря: «‹ ‹ Отмена»; без рук — нажимается
                        Text(tr(R.string.orb_slide_cancel), fontSize = 14.sp, color = if (cap.nearCancel) Ink.ink else Ink.faint,
                            modifier = if (cap.rec == Rec.FREE) Modifier.clip(RoundedCornerShape(12.dp)).clickable { Capture.send(Ev.TapCancel) }.padding(horizontal = 8.dp, vertical = 6.dp) else Modifier)
                        Spacer(Modifier.weight(1f))
                    } else Row(verticalAlignment = Alignment.CenterVertically) {
                        if (onGallery != null || onFile != null) {
                            var open by remember { mutableStateOf(false) }
                            Box {
                                Box(Modifier.size(36.dp).clip(CircleShape).clickable(onClickLabel = tr(R.string.cmp_attach)) { if (onFile == null && onGallery != null) onGallery() else open = true }, contentAlignment = Alignment.Center) {
                                    Icon(painterResource(Ic.clip), null, Modifier.size(20.dp), tint = Ink.soft)
                                }
                                androidx.compose.material3.DropdownMenu(expanded = open, onDismissRequest = { open = false }, shape = RoundedCornerShape(18.dp), containerColor = Ink.surf) {
                                    if (onGallery != null) MenuItem(Ic.image, tr(R.string.cmp_gallery), tr(R.string.cmp_gallery_sub)) { open = false; onGallery() }
                                    if (onFile != null) MenuItem(Ic.doc, tr(R.string.cmp_file), tr(R.string.cmp_file_sub)) { open = false; onFile() }
                                }
                            }
                        } else Spacer(Modifier.width(10.dp))
                        Box(Modifier.weight(1f).padding(vertical = 8.dp)) {
                            val ph = placeholder ?: if (staged != null) tr(if (staged.file != null) R.string.orb_comment_file else R.string.orb_comment_photo) else state.current.placeholder
                            if (state.draft.isEmpty()) Text(ph, color = Ink.faint, fontSize = 14.5.sp, maxLines = 1)
                            BasicTextField(state.draft, onDraft, textStyle = BaseText.copy(fontSize = 14.5.sp), cursorBrush = SolidColor(Ink.ink),
                                maxLines = 5, modifier = Modifier.fillMaxWidth().focusRequester(fr))
                        }
                    }
                }
            }
            Spacer(Modifier.width(6.dp))
            val canSend = hasText || staged != null
            Sun(st, 60.dp, if (canSend && !recording || cap.rec == Rec.FREE) Ic.send else Ic.mic, pressed = cap.pressing || pre, active = recording,
                label = if (canSend) tr(R.string.ui_send) else tr(R.string.orb_talk), dim = false,
                modifier = Modifier.graphicsLayer { translationX = dx * density }
                    .fieldGestures(canSend = { draftNow.isNotBlank() || OrbSheet.stagedFor(modeNow) != null },
                        onSendText = { OrbSheet.send(draftNow, tr(R.string.cmp_via_keyboard)) }, onPre = { pre = it }))
        }
        AnimatedContent(recording, transitionSpec = { fadeIn(tween(160)) togetherWith fadeOut(tween(120)) }, label = "tabs2") { r ->
            if (r) Spacer(Modifier.height(42.dp)) else ModeTabs(state, onSelectMode, onAddMode)
        }
    }
}

@Composable
private fun RecTimeInline(t0: Long) {
    var now by remember { mutableLongStateOf(SystemClock.uptimeMillis()) }
    LaunchedEffect(t0) { while (true) { now = SystemClock.uptimeMillis(); delay(250) } }
    val s = ((now - t0) / 1000).coerceAtLeast(0)
    Box(Modifier.size(9.dp).clip(CircleShape).background(Color(0xFFE53935)))
    Spacer(Modifier.width(8.dp))
    Text("%d:%02d".format(s / 60, s % 60), fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Ink.ink)
}

/** Солнышко у поля: есть что отправить — нажатие отправляет, держать дольше 260 мс — сказать ещё и отправить вместе; пусто — запись сразу. */
private fun Modifier.fieldGestures(canSend: () -> Boolean, onSendText: () -> Unit, onPre: (Boolean) -> Unit) = pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false).consume()
        val at = SystemClock.uptimeMillis()
        if (Capture.state.rec != Rec.IDLE || !canSend()) { trackCapture(at); return@awaitEachGesture }
        onPre(true)
        try {
            val up = withTimeoutOrNull(Gesture.TAP_MS) { waitForUpOrCancellation() }
            if (up != null) { onSendText(); return@awaitEachGesture }
            onPre(false)
            // таймаут — держат: запись, отпустил — уходит вместе с текстом
            trackCapture(at)
        } finally { onPre(false) }
    }
}

/**
 * Солнышко-кнопка: знак Вахтёра с иконкой внутри. Ждёт — дышит; слушает — ядро, кольца и волны идут за громкостью микрофона
 * (волна рождается на всплеске голоса и расходится); распознаёт и работает — пунктирное кольцо бежит; нажали — чуть проседает.
 * [trail] — шлейф при улёте (0..1).
 */
@Composable
fun Sun(st: SheetSt, size: Dp, icon: Int, pressed: Boolean, label: String, dim: Boolean, modifier: Modifier = Modifier, trail: Float = 0f, active: Boolean = false) {
    val reduce = android.provider.Settings.Global.getFloat(LocalContext.current.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    // часы кадров и громкость: вверх сразу, вниз плавно (как у знака); волны — моменты всплесков
    var t by remember { mutableFloatStateOf(0f) }
    var lvl by remember { mutableFloatStateOf(0f) }
    val waves = remember { mutableStateListOf<Float>() }
    val rec = st == SheetSt.REC
    val recNow by androidx.compose.runtime.rememberUpdatedState(rec)
    LaunchedEffect(Unit) {
        val start = withFrameNanos { it }
        var lastWave = 0f; var avg = 0f
        while (true) {
            withFrameNanos { now ->
                val nt = (now - start) / 1e9f
                val l = if (recNow) App.instance.voice.level else 0f
                lvl = if (l > lvl) lvl + (l - lvl) * .55f else lvl * .9f + l * .1f
                avg = avg * .96f + lvl * .04f
                if (recNow && !reduce && lvl > .3f && lvl > avg * 1.35f && nt - lastWave > .26f) { waves.add(nt); lastWave = nt }
                waves.removeAll { nt - it > 1.1f }
                t = nt
            }
        }
    }
    // нажали — проседает; слушает — чуть крупнее, «подалось навстречу»
    val press by animateFloatAsState(if (active) 1.06f else if (pressed) .92f else 1f, spring(.5f, 600f), label = "press")
    val sweep by animateFloatAsState(if (st == SheetSt.RECOG || st == SheetSt.THINK || st == SheetSt.UP) 1f else 0f, tween(260), label = "spin")
    Box(modifier.size(size).semantics { contentDescription = label }.graphicsLayer { scaleX = press; scaleY = press }
        .drawBehind { sun(t, lvl, rec, sweep, waves, dim, trail, reduce) }, contentAlignment = Alignment.Center) {
        AnimatedContent(icon, transitionSpec = { (fadeIn(tween(160)) + scaleIn(tween(220), .6f)) togetherWith (fadeOut(tween(100)) + scaleOut(tween(140), .6f)) }, label = "icon") { ic ->
            Icon(painterResource(ic), null, Modifier.size(size * .26f), tint = Color.White)
        }
    }
}

private val Hot = Color(0xFFFFC94A)
private val Deep = Color(0xFFD48A00)

private fun DrawScope.sun(t: Float, lv: Float, rec: Boolean, spin: Float, waves: List<Float>, dim: Boolean, trail: Float, reduce: Boolean) {
    val w = size.minDimension
    val mid = Offset(size.width / 2, size.height / 2)
    val c = if (dim) Color(0xFFC9CEC4) else Ink.local
    val sec = if (reduce) 0f else t
    // шлейф улёта: янтарная полоса вниз от солнышка, гаснет
    if (trail > .2f) {
        val q = ((trail - .2f) / .8f).coerceIn(0f, 1f)
        drawRoundRect(Brush.verticalGradient(listOf(c.copy(alpha = .0f), c.copy(alpha = .35f * (1f - q))), startY = mid.y, endY = mid.y + w * (.4f + 1.6f * q)),
            topLeft = Offset(mid.x - w * .12f, mid.y), size = Size(w * .24f, w * (.4f + 1.6f * q)), cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * .12f))
    }
    val breathe = .5f + .5f * sin(sec / 3.2f * 2 * PI.toFloat())
    val core = if (rec) .9f + .5f * lv else .94f + .08f * breathe
    val r0 = w * .3f * core
    // ореол
    val haloR = r0 + w * (if (rec) .28f + .22f * lv else .24f)
    drawCircle(Brush.radialGradient(listOf(c.copy(alpha = if (rec) .34f + .3f * lv else .3f), c.copy(alpha = 0f)), center = mid, radius = haloR), haloR, mid)
    // волны голоса: рождаются на всплеске и расходятся за кольца
    waves.forEach { born ->
        val a = ((t - born) / 1.1f).coerceIn(0f, 1f)
        val r = w * (.36f + .42f * a)
        drawCircle(c.copy(alpha = .5f * (1f - a) * (1f - a)), r, mid, style = Stroke((2.2f - 1.4f * a).dp.toPx()))
    }
    // ядро: светлее в центре — объём
    drawCircle(Brush.radialGradient(0f to Hot, .65f to c, 1f to (if (dim) c else Deep), center = Offset(mid.x - r0 * .25f, mid.y - r0 * .3f), radius = r0 * 1.35f), r0, mid)
    // кольца
    val r1 = w * .39f; val r2 = w * .5f
    val s1 = 2.dp.toPx(); val s2 = 1.5.dp.toPx()
    if (rec) {
        drawCircle(c.copy(alpha = .45f + .45f * lv), (r1 - s1 / 2) * (1f + .22f * lv), mid, style = Stroke(s1))
        drawCircle(c.copy(alpha = .2f + .4f * lv), (r2 - s2 / 2) * (1f + .3f * lv), mid, style = Stroke(s2))
    } else {
        drawCircle(c.copy(alpha = .45f * (1f - spin)), r1 - s1 / 2, mid, style = Stroke(s1))
        if (spin > 0f) rotate(360f * ((sec / 1.6f) % 1f), mid) {
            drawCircle(c.copy(alpha = .85f * spin), r1 - s1 / 2, mid, style = Stroke(s1, pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx()))))
        }
        drawCircle(c.copy(alpha = .2f), r2 - s2 / 2, mid, style = Stroke(s2))
        // ждёт: по внешнему кольцу медленно бежит искра — «на вахте»
        if (!dim && spin == 0f && !reduce) rotate(360f * ((sec / 7f) % 1f), mid) {
            // хвост искры: от прозрачного к яркой голове на 70°
            drawArc(Brush.sweepGradient(0f to c.copy(alpha = 0f), (70f / 360f) to c.copy(alpha = .6f), (70.5f / 360f) to c.copy(alpha = 0f), center = mid), 0f, 70f, false,
                topLeft = Offset(mid.x - r2 + s2, mid.y - r2 + s2), size = Size((r2 - s2) * 2, (r2 - s2) * 2), style = Stroke(s2 * 1.6f, cap = StrokeCap.Round))
        }
    }
}
