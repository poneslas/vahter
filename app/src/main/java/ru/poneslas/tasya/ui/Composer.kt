package ru.poneslas.tasya.ui

import ru.poneslas.tasya.R
import ru.poneslas.tasya.tr
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.res.painterResource
import androidx.compose.material3.Icon
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

/** Последний отработанный вызов кнопкой (баг №8: после камеры строка ввода создаётся заново и не должна слушать снова). */
private var autoSeen = 0

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
        if (!voice.installed.value) { onMessageNow(tr(R.string.cmp_golos_eshche_ne_skachan_nastroyki)); return false }
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
                Voice.Result.Empty -> onMessageNow(tr(R.string.cmp_nichego_ne_rasslyshal_pustaya_zapis))
                is Voice.Result.Failed -> onMessageNow(tr(R.string.cmp_ne_raspoznal_1_s, r.message))
            }
        }
    }

    fun startHandsFree() {
        if (voice.recording.value || !ready()) return
        if (!voice.start()) { onMessageNow(tr(R.string.cmp_mikrofon_ne_zavelsya_zvuk_ne)); return }
        button.setState(RecordingButton.State.RECORDING)
        button.handsFree = true
        showChrome(handsFree = true)
    }

    DisposableEffect(button) {
        button.onRecordingStart = {
            when {
                !ready() -> button.setState(RecordingButton.State.IDLE)
                !voice.start() -> { button.setState(RecordingButton.State.IDLE); onMessageNow(tr(R.string.cmp_mikrofon_ne_zavelsya_zvuk_ne)) }
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
    LaunchedEffect(outerAuto) { if (outerAuto > autoSeen) { autoSeen = outerAuto; autoFlow.value++ } }
    LaunchedEffect(handsFreeTick) { if (handsFreeTick > 0) autoFlow.value++ }

    fun flash(text: String) { scope.launch { onLive(text); delay(2800); onLive(null) } }

    Column(Modifier.background(Ink.paper)) {
        // .mpicker: меню скрепки над полем
        if (menu) Column(Modifier.padding(start = 16.dp, bottom = 4.dp).widthIn(min = 230.dp)
            .shadow(16.dp, RoundedCornerShape(18.dp), ambientColor = Color(0x730E1A36), spotColor = Color(0x730E1A36))
            .clip(RoundedCornerShape(18.dp)).background(Ink.surf).border(1.dp, Ink.line, RoundedCornerShape(18.dp)).padding(6.dp)) {
            if (onCamera != null && !photoMain) PickItem(Ic.cam, tr(R.string.cmp_kamera), tr(R.string.cmp_snyat_i_otpravit)) { menu = false; onCamera() }
            if (onGallery != null) PickItem(Ic.image, tr(R.string.cmp_galereya), tr(R.string.cmp_sistemnyy_vybor_foto)) { menu = false; onGallery() }
        }
        Box(Modifier.padding(start = 16.dp, end = 6.dp, top = 2.dp, bottom = 2.dp)) {
            VoiceRow(
                voice = voice,
                field = {
                    // .fld: белое поле, рамка, скругление 24, мин. высота 48; слева скрепка, справа микрофон фото-режима
                    val canAttach = state.current.accepts.photo && (onGallery != null || (onCamera != null && !photoMain))
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(24.dp)).background(Ink.surf)
                            .border(1.dp, Ink.line, RoundedCornerShape(24.dp)).padding(start = if (canAttach) 4.dp else 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (canAttach) InTool(Ic.clip, tr(R.string.cmp_prikrepit)) { menu = !menu }
                        Box(Modifier.weight(1f)) {
                            if (state.draft.isEmpty()) Text(state.current.placeholder, color = Ink.faint, fontSize = 14.5.sp, maxLines = 1)
                            BasicTextField(state.draft, onDraft, textStyle = BaseText.copy(fontSize = 14.5.sp),
                                cursorBrush = SolidColor(Ink.ink), modifier = Modifier.fillMaxWidth())
                        }
                        if (photoMain && !hasText && state.current.accepts.voice) InTool(Ic.mic, tr(R.string.cmp_sprosit_golosom_bez_ruk)) { handsFreeTick++ }
                    }
                },
                trailing = when {
                    hasText -> { { RoundAction(Ic.send, tr(R.string.cmp_otpravit)) { onSend(state.draft, tr(R.string.cmp_klaviatura)) } } }
                    photoMain -> { { RoundAction(Ic.cam, tr(R.string.cmp_kamera)) { onCamera?.invoke() } } }
                    else -> null
                },
                onText = { text -> onSend((state.draft + " " + text).trim(), tr(R.string.sheet_golos)) },
                onMessage = ::flash,
                onNeedMic = { if (onNeedMic != null) onNeedMic() else flash(tr(R.string.cmp_nuzhen_dostup_k_mikrofonu)) },
                autoListen = autoFlow,
                cancelKey = state.mode,
            )
        }
        ModeTabs(state, onSelectMode, onAddMode)
    }
}

/** .micbtn 48: тёмный круг — «Отправить» или «Камера». */
@Composable
private fun RoundAction(icon: Int, label: String, onClick: () -> Unit) {
    Box(Modifier.size(48.dp).clip(CircleShape).background(Ink.ink).clickable(onClickLabel = label, onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(painterResource(icon), label, Modifier.size(20.dp), tint = Color.White)
    }
}

/** .intool 34: приглушённая иконка в поле. */
@Composable
private fun InTool(icon: Int, label: String, onClick: () -> Unit) {
    Box(Modifier.size(34.dp).clip(CircleShape).clickable(onClickLabel = label, onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(painterResource(icon), label, Modifier.size(20.dp), tint = Ink.soft)
    }
}

/** .mpicker button: иконка, заголовок 14/600, пояснение 12. */
@Composable
private fun PickItem(icon: Int, title: String, sub: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(icon), null, Modifier.size(20.dp), tint = Ink.ink)
        Spacer(Modifier.width(12.dp))
        Column { Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold); Text(sub, fontSize = 12.sp, color = Ink.soft) }
    }
}

/** Волна Писаря, пока идёт запись или распознавание. */
@Composable
fun RecPill(modifier: Modifier = Modifier) {
    val phase by RecUi.phase.collectAsState()
    if (phase == RecUi.Phase.IDLE) return
    val ctx = LocalContext.current
    val voice = App.instance.voice
    val pill = remember { RecordingPill.PillView(ctx) }
    LaunchedEffect(phase) { if (phase == RecUi.Phase.LISTENING) pill.listen { voice.level } else pill.process() }
    DisposableEffect(Unit) { onDispose { pill.stop() } }
    Box(modifier.padding(vertical = 6.dp), contentAlignment = Alignment.Center) { AndroidView({ pill }) }
}

/** Над полем: волна записи ([pill]; в листе она поверх ленты) или короткое сообщение. */
@Composable
fun LiveLine(live: String?, pill: Boolean = true) {
    val phase by RecUi.phase.collectAsState()
    if (phase != RecUi.Phase.IDLE) { if (pill) RecPill(Modifier.fillMaxWidth()); return }
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
                    if (text.isEmpty()) Text(tr(R.string.cmp_poprobuyte_zdes), color = Ink.faint, fontSize = 15.sp)
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
