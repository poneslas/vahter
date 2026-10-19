package ru.poneslas.tasya.ui

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import ru.poneslas.tasya.App
import ru.poneslas.tasya.core.Conversation
import ru.poneslas.tasya.core.Primary
import ru.poneslas.tasya.pisar.overlay.LockBadge
import ru.poneslas.tasya.pisar.overlay.RecordingBar
import ru.poneslas.tasya.pisar.overlay.RecordingButton
import ru.poneslas.tasya.pisar.overlay.RecordingPill
import ru.poneslas.tasya.voice.Voice
import kotlin.math.roundToInt

/** Что сейчас с голосом — для волны над полем. */
object RecUi {
    enum class Phase { IDLE, LISTENING, PROCESSING }
    val phase = MutableStateFlow(Phase.IDLE)
}

/** Масштаб кнопки Писаря в строке ввода: круг 86 dp × 0,6 ≈ 52 dp, место под кольца — 72 dp. */
private const val SCALE = 0.6f

/**
 * Кнопка записи Гига Писаря для Android, встроенная в строку ввода (пакет `pisar/overlay`):
 * касание — запись сразу; отпустил — распознаётся и уходит; вверх — замочек, дальше без пальца,
 * тап — отправить; к середине экрана — отмена. Полоса «● 0:07 ‹ ‹ Отмена» выезжает на место поля.
 */
@Composable
fun VoiceRow(
    voice: Voice,
    field: @Composable () -> Unit,
    trailing: (@Composable () -> Unit)?,
    onText: (String) -> Unit,
    onMessage: (String) -> Unit,
    onNeedMic: () -> Unit,
    autoListen: StateFlow<Int>? = null,
    cancelKey: Any? = null,
) {
    val ctx = LocalContext.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val onTextNow by rememberUpdatedState(onText)
    val onMessageNow by rememberUpdatedState(onMessage)
    val onNeedMicNow by rememberUpdatedState(onNeedMic)

    var chrome by remember { mutableStateOf(false) }
    var inward by remember { mutableFloatStateOf(0f) }
    var rise by remember { mutableFloatStateOf(0f) }

    val button = remember { RecordingButton(ctx).apply { visibility = android.view.View.VISIBLE; alpha = 1f; scaleX = 1f; scaleY = 1f; movable = false; sizeScale = SCALE; inwardSign = -1f; lockSign = -1f; level = { voice.level } } }
    val bar = remember { RecordingBar(ctx).apply { side = -1 } }
    val lock = remember { LockBadge(ctx) }

    fun buzz() { button.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK) }

    fun ready(): Boolean {
        if (!voice.hasMic()) { onNeedMicNow(); return false }
        if (!voice.installed.value) { onMessageNow("Голос ещё не скачан: Настройки → Голос"); return false }
        return true
    }

    fun showChrome(handsFree: Boolean) {
        chrome = true; inward = 0f; rise = 0f
        bar.restart(); bar.handsFree = handsFree
        lock.locked = handsFree; lock.progress = 0f; lock.pointsDown = false
        button.barSide = -1
        RecUi.phase.value = RecUi.Phase.LISTENING
        buzz()
    }

    fun hideChrome() { chrome = false; inward = 0f; rise = 0f; bar.handsFree = false; button.barSide = 0 }

    fun finish(send: Boolean) {
        if (!voice.recording.value) { hideChrome(); button.setState(RecordingButton.State.IDLE); return }
        hideChrome()
        if (!send) {
            voice.cancel(); button.setState(RecordingButton.State.IDLE); RecUi.phase.value = RecUi.Phase.IDLE; buzz(); return
        }
        button.setState(RecordingButton.State.PROCESSING); RecUi.phase.value = RecUi.Phase.PROCESSING; buzz()
        scope.launch {
            val r = voice.stop()
            button.setState(RecordingButton.State.IDLE); RecUi.phase.value = RecUi.Phase.IDLE
            when (r) {
                is Voice.Result.Text -> onTextNow(r.text)
                Voice.Result.TooShort -> Unit
                Voice.Result.Empty -> onMessageNow("Ничего не расслышал — пустая запись")
                is Voice.Result.Failed -> onMessageNow("Не распознал: ${r.message}")
            }
        }
    }

    fun startHandsFree() {
        if (voice.recording.value || !ready()) return
        if (!voice.start()) { onMessageNow("Микрофон не завёлся — звук не идёт"); return }
        button.setState(RecordingButton.State.RECORDING)
        button.handsFree = true
        showChrome(handsFree = true)
    }

    DisposableEffect(button) {
        button.onRecordingStart = {
            when {
                !ready() -> button.setState(RecordingButton.State.IDLE)
                !voice.start() -> { button.setState(RecordingButton.State.IDLE); onMessageNow("Микрофон не завёлся — звук не идёт") }
                else -> showChrome(handsFree = false)
            }
        }
        button.onRecordingStop = { finish(send = true) }
        button.onLock = { bar.handsFree = true; lock.locked = true; lock.progress = 0f; inward = 0f; rise = 0f; buzz() }
        button.onCancel = { finish(send = false) }
        bar.onCancel = { finish(send = false) }
        button.onSwipe = { i, up ->
            inward = i
            rise = minOf(up, 24 * density.density)
            lock.progress = up / (80 * density.density * SCALE)
            bar.cancelProgress = i / (110 * density.density * SCALE)
            button.barSide = if (rise != 0f) 0 else -1
        }
        voice.onTimeout = { finish(send = true) }
        onDispose { if (voice.recording.value) voice.cancel(); RecUi.phase.value = RecUi.Phase.IDLE; voice.onTimeout = null }
    }

    // Микрофон остановили снаружи (лист спрятан, экран погас) — сбросить полосу и кнопку.
    val micOn by voice.recording.collectAsState()
    LaunchedEffect(micOn) { if (!micOn && chrome) { hideChrome(); button.setState(RecordingButton.State.IDLE); RecUi.phase.value = RecUi.Phase.IDLE } }
    // Смена режима выбрасывает запись — ничего не уходит.
    LaunchedEffect(cancelKey) { if (voice.recording.value) finish(send = false) }
    // Вызов боковой кнопкой в голосовом режиме: сразу слушает без рук (замочек закрыт).
    val auto = autoListen?.collectAsState()?.value ?: 0
    LaunchedEffect(auto) { if (auto > 0) startHandsFree() }

    val btn = (120 * SCALE).dp
    BoxWithConstraints(Modifier.fillMaxWidth().height(btn)) {
        val maxPx = with(density) { maxWidth.toPx() }
        val btnPx = with(density) { btn.toPx() }
        Row(Modifier.fillMaxWidth().height(btn), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f).alpha(if (chrome) 0f else 1f)) { field() }
            Spacer(Modifier.width(btn))
        }
        if (chrome) {
            val barH = (RecordingBar.HEIGHT_DP + 2 * RecordingBar.SHADOW_DP).dp
            val w = with(density) { (maxPx - btnPx - inward).coerceAtLeast(1f).toDp() }
            AndroidView({ bar }, Modifier.align(Alignment.CenterStart).width(w).height(barH))
            val lw = (LockBadge.WIDTH_DP + 2 * LockBadge.SHADOW_DP).dp
            val lh = (LockBadge.HEIGHT_DP + 2 * LockBadge.SHADOW_DP).dp
            val gap = (43 * SCALE + 14).dp
            AndroidView({ lock }, Modifier.align(Alignment.CenterEnd).size(lw, lh)
                .offset { IntOffset(-((btnPx - lw.toPx()) / 2).roundToInt(), -(gap.toPx() + lh.toPx() / 2).roundToInt()) })
        }
        if (trailing != null && !chrome) Box(Modifier.align(Alignment.CenterEnd).size(btn), contentAlignment = Alignment.Center) { trailing() }
        else AndroidView({ button }, Modifier.align(Alignment.CenterEnd).size(btn)
            .offset { IntOffset(-inward.roundToInt(), -rise.roundToInt()) })
    }
}

/**
 * Строка ввода: поле (скрепка, текст, микрофон фото-режима), справа кнопка Писаря;
 * есть текст — на её месте «Отправить», фото-режим — «Камера». Под ней вкладки режимов.
 */
@Composable
fun Composer(
    state: Conversation.State,
    voice: Voice,
    onDraft: (String) -> Unit,
    onSend: (String, String) -> Unit,
    onSelectMode: (String) -> Unit,
    onAddMode: () -> Unit,
    onLive: (String?) -> Unit,
    autoListen: StateFlow<Int>? = null,
    onNeedMic: (() -> Unit)? = null,
    onCamera: (() -> Unit)? = null,
    onGallery: (() -> Unit)? = null,
) {
    var menu by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val photoMain = state.current.primary == Primary.PHOTO && state.current.accepts.photo && onCamera != null
    val hasText = state.draft.isNotBlank()
    var handsFreeTick by remember { mutableStateOf(0) }
    val autoFlow = remember { MutableStateFlow(0) }
    val outerAuto = autoListen?.collectAsState()?.value ?: 0
    LaunchedEffect(outerAuto) { if (outerAuto > 0) autoFlow.value++ }
    LaunchedEffect(handsFreeTick) { if (handsFreeTick > 0) autoFlow.value++ }

    fun flash(text: String) { scope.launch { onLive(text); delay(2800); onLive(null) } }

    Column(Modifier.background(Ink.paper)) {
        if (menu) Column(Modifier.padding(start = 12.dp).clip(RoundedCornerShape(16.dp)).background(Ink.surf).border(1.dp, Ink.line, RoundedCornerShape(16.dp)).padding(6.dp)) {
            if (onCamera != null && !photoMain) Text("📷  Камера", fontSize = 15.sp, color = Ink.ink, modifier = Modifier.clickable { menu = false; onCamera() }.padding(horizontal = 12.dp, vertical = 10.dp))
            if (onGallery != null) Text("🖼  Галерея", fontSize = 15.sp, color = Ink.ink, modifier = Modifier.clickable { menu = false; onGallery() }.padding(horizontal = 12.dp, vertical = 10.dp))
        }
        Box(Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)) {
            VoiceRow(
                voice = voice,
                field = {
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(24.dp)).background(Ink.surf)
                            .border(1.dp, Ink.line, RoundedCornerShape(24.dp)).padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val canAttach = state.current.accepts.photo && (onGallery != null || (onCamera != null && !photoMain))
                        if (canAttach) Text("📎", fontSize = 18.sp, modifier = Modifier.clickable { menu = !menu }.padding(end = 10.dp))
                        Box(Modifier.weight(1f)) {
                            if (state.draft.isEmpty()) Text(state.current.placeholder, color = Ink.faint, fontSize = 15.sp)
                            BasicTextField(state.draft, onDraft, textStyle = TextStyle(fontSize = 15.sp, color = Ink.ink),
                                cursorBrush = SolidColor(Ink.ink), modifier = Modifier.fillMaxWidth())
                        }
                        if (photoMain && !hasText && state.current.accepts.voice)
                            Text("🎙", fontSize = 18.sp, modifier = Modifier.clickable { handsFreeTick++ }.padding(start = 10.dp))
                    }
                },
                trailing = when {
                    hasText -> { { RoundAction("↑") { onSend(state.draft, "клавиатура") } } }
                    photoMain -> { { RoundAction("📷") { onCamera?.invoke() } } }
                    else -> null
                },
                onText = { text -> onSend((state.draft + " " + text).trim(), "голос") },
                onMessage = ::flash,
                onNeedMic = { if (onNeedMic != null) onNeedMic() else flash("Нужен доступ к микрофону") },
                autoListen = autoFlow,
                cancelKey = state.mode,
            )
        }
        ModeTabs(state, onSelectMode, onAddMode)
    }
}

@Composable
private fun RoundAction(glyph: String, onClick: () -> Unit) {
    Box(Modifier.size(52.dp).clip(CircleShape).background(Ink.ink).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Text(glyph, color = Color.White, fontSize = 20.sp)
    }
}

/** Над полем: волна Писаря, пока идёт запись или распознавание; иначе — короткое сообщение. */
@Composable
fun LiveLine(live: String?) {
    val phase by RecUi.phase.collectAsState()
    val ctx = LocalContext.current
    val voice = App.instance.voice
    if (phase != RecUi.Phase.IDLE) {
        val pill = remember { RecordingPill.PillView(ctx) }
        LaunchedEffect(phase) { if (phase == RecUi.Phase.LISTENING) pill.listen { voice.level } else pill.process() }
        DisposableEffect(Unit) { onDispose { pill.stop() } }
        Box(Modifier.fillMaxWidth().padding(vertical = 6.dp), contentAlignment = Alignment.Center) { AndroidView({ pill }) }
        return
    }
    if (live == null) return
    val (status, text) = live.split("|", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(status, fontSize = 13.sp, color = Color(0xFF9A6A00), fontWeight = FontWeight.Medium)
        if (text.isNotEmpty()) Text(text, fontSize = 15.sp, color = Ink.ink)
    }
}

/** «Попробуйте здесь…» после скачивания — как у Писаря: зажать, сказать, отпустить, текст в поле. */
@Composable
fun TryVoice() {
    val voice = App.instance.voice
    var text by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf<String?>(null) }
    val ctx = LocalContext.current
    Column {
        VoiceRow(
            voice = voice,
            field = {
                Box(Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(24.dp)).background(Ink.surf)
                    .border(1.dp, Ink.line, RoundedCornerShape(24.dp)).padding(horizontal = 16.dp, vertical = 12.dp)) {
                    if (text.isEmpty()) Text("Попробуйте здесь…", color = Ink.faint, fontSize = 15.sp)
                    BasicTextField(text, { text = it }, textStyle = TextStyle(fontSize = 15.sp, color = Ink.ink),
                        cursorBrush = SolidColor(Ink.ink), modifier = Modifier.fillMaxWidth())
                }
            },
            trailing = null,
            onText = { t -> text = (text + " " + t).trim(); msg = null },
            onMessage = { msg = it },
            onNeedMic = { (ctx as? android.app.Activity)?.requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 1) },
        )
        msg?.let { Text(it, fontSize = 13.sp, color = Ink.soft, modifier = Modifier.padding(top = 6.dp)) }
    }
}
