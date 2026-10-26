package ru.poneslas.vahter.assistant

import android.os.Build
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.runtime.remember
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import ru.poneslas.vahter.App
import ru.poneslas.vahter.R
import ru.poneslas.vahter.tr
import ru.poneslas.vahter.ui.RecUi
import ru.poneslas.vahter.voice.Voice
import kotlin.math.abs

/*
 * Схема состояний низа листа «солнышко» (26.10). Четыре независимые части:
 *   ввод     — солнышко или поле ([SurfaceRules]); своё поле — у каждого режима отдельно;
 *   запись   — нет · держу · без рук · распознаю ([Rec], [Machine]); одна на всех: солнышко, поле, микрофон фото-режима, камера;
 *   вложение — нет или ждёт в своём режиме (OrbSheet.staged);
 *   камера   — выключена · живой кадр · снятый кадр (CameraPanel).
 * [Machine] — чистая логика переходов (тесты: SheetMachineTest), [Capture] — исполнитель: микрофон, распознавание, отдача.
 * Правило (26.10, Продукт 5.18): скрытые жесты — только ускорители, никогда не единственный путь.
 *   Голосовые режимы — полный набор: держишь — говоришь, короткое нажатие — без рук; ✕ и ✎ кнопками; вбок — ускорители (влево отмена, вправо правка).
 *   Фото-режимы и видоискатель — простой микрофон ([RecState.simple]): ткнул — без рук, держишь — с руками, закончил — ушло;
 *   без рук видна только ✕; ни правки, ни жестов вбок.
 */

enum class Rec { IDLE, HOLD, FREE, RECOG }

/** Чем закончилась запись. */
enum class How { SEND, EDIT, CANCEL }

/** Пороги жестов (dp, мс). */
object Gesture {
    const val TAP_MS = 260L
    const val NEAR_DP = 38f
    const val CANCEL_DP = 70f
    const val EDIT_DP = 70f
}

/** Ввод листа: поле или солнышко. Одно правило на все режимы и переходы — без «сначала поле, потом передумал». */
object SurfaceRules {
    fun field(draft: String, opened: Boolean, voiceReady: Boolean, acceptsVoice: Boolean, photoMain: Boolean): Boolean =
        draft.isNotBlank() || opened || (!photoMain && !(acceptsVoice && voiceReady))
}

/** Состояние записи. [editable] — есть ли куда «править» (в камере поля нет); [x] — сдвиг пальца, dp. */
data class RecState(
    val rec: Rec = Rec.IDLE,
    val editable: Boolean = true,
    /** Сколько тянуть вправо до правки, dp: у кнопки у края экрана места меньше 70 — порог ближе (микрофон фото-режима). */
    val editAt: Float = Gesture.EDIT_DP,
    /** Простой микрофон (фото-режимы, видоискатель): без правки и без жестов вбок, отмена — только кнопкой ✕. */
    val simple: Boolean = false,
    val pressing: Boolean = false,
    val x: Float = 0f,
    val nearCancel: Boolean = false,
    val nearEdit: Boolean = false,
)

/** Что сделать с микрофоном после перехода. */
sealed interface Effect {
    data class Start(val free: Boolean) : Effect
    data class Stop(val how: How) : Effect   // SEND / EDIT — распознать и отдать; CANCEL — выбросить
}

/** События записи: палец на кнопке, сдвиг, отпустили, кнопки ✕ / ✎ / «слушать без рук», смена режима, готово. */
sealed interface Ev {
    data class Down(val editable: Boolean, val editAt: Float = Gesture.EDIT_DP, val simple: Boolean = false) : Ev
    data class Drag(val xDp: Float) : Ev
    data class Up(val heldMs: Long, val moved: Boolean) : Ev
    data class Free(val editable: Boolean, val simple: Boolean = false) : Ev
    data object TapCancel : Ev
    data object TapEdit : Ev
    data object ModeChanged : Ev
    data object Timeout : Ev     // предел записи (5 минут) — отдать, что есть
    data object Done : Ev        // распознавание закончилось
    data object Lost : Ev        // микрофон остановили снаружи / не стартовал
}

/** Чистые переходы записи: (состояние, событие) → (новое состояние, что сделать с микрофоном). */
object Machine {
    fun reduce(s: RecState, e: Ev): Pair<RecState, Effect?> = when (e) {
        is Ev.Down -> when (s.rec) {
            Rec.IDLE -> RecState(Rec.HOLD, editable = e.editable && !e.simple, editAt = e.editAt, simple = e.simple, pressing = true) to Effect.Start(free = false)
            Rec.FREE -> s.copy(pressing = true, editAt = e.editAt) to null
            else -> s to null
        }
        is Ev.Free -> if (s.rec == Rec.IDLE) RecState(Rec.FREE, editable = e.editable && !e.simple, simple = e.simple) to Effect.Start(free = true) else s to null
        is Ev.Drag -> if (s.simple || s.rec != Rec.HOLD && !(s.rec == Rec.FREE && s.pressing)) s to null else {
            val x = if (s.editable) e.xDp else e.xDp.coerceAtMost(0f)
            when {
                x < -Gesture.CANCEL_DP -> RecState() to Effect.Stop(How.CANCEL)
                s.editable && x > s.editAt -> RecState(Rec.RECOG, editable = s.editable) to Effect.Stop(How.EDIT)
                else -> s.copy(x = x, nearCancel = x < -Gesture.NEAR_DP, nearEdit = s.editable && x > s.editAt * Gesture.NEAR_DP / Gesture.EDIT_DP) to null
            }
        }
        is Ev.Up -> when {
            s.rec == Rec.HOLD && e.heldMs < Gesture.TAP_MS && !e.moved -> s.copy(rec = Rec.FREE, pressing = false, x = 0f, nearCancel = false, nearEdit = false) to null
            s.rec == Rec.HOLD -> RecState(Rec.RECOG, editable = s.editable, simple = s.simple) to Effect.Stop(How.SEND)
            s.rec == Rec.FREE && s.pressing && (!e.moved || s.simple) -> RecState(Rec.RECOG, editable = s.editable, simple = s.simple) to Effect.Stop(How.SEND)
            s.rec == Rec.FREE -> s.copy(pressing = false, x = 0f, nearCancel = false, nearEdit = false) to null
            else -> s to null
        }
        Ev.TapCancel -> if (s.rec == Rec.HOLD || s.rec == Rec.FREE) RecState() to Effect.Stop(How.CANCEL) else s to null
        Ev.TapEdit -> if ((s.rec == Rec.HOLD || s.rec == Rec.FREE) && s.editable) RecState(Rec.RECOG, editable = true) to Effect.Stop(How.EDIT) else s to null
        Ev.Timeout -> if (s.rec == Rec.HOLD || s.rec == Rec.FREE) RecState(Rec.RECOG, editable = s.editable) to Effect.Stop(How.SEND) else s to null
        Ev.ModeChanged -> if (s.rec == Rec.HOLD || s.rec == Rec.FREE) RecState() to Effect.Stop(How.CANCEL) else s to null
        Ev.Done, Ev.Lost -> RecState() to null
    }
}

/** Куда уходит распознанное: лист (по умолчанию) или камера, пока она открыта. */
interface CaptureSink {
    val editable: Boolean
    /** Простой микрофон: фото-режим или видоискатель. */
    val simple: Boolean get() = false
    fun text(text: String, how: How)
    fun cancelled() {}
}

/**
 * Исполнитель записи — один на приложение: состояние ([state]) читают все кнопки, события шлют через [send].
 * Голос — тот же Voice (Писарь). Ошибки — короткой строкой в [message].
 */
object Capture {
    var state by mutableStateOf(RecState())
        private set
    var t0 by mutableStateOf(0L)
        private set
    /** Короткое сообщение над полем («Ничего не расслышал…»). */
    val message = MutableStateFlow<String?>(null)
    /** Нужно разрешение на микрофон — лист спросит через приложение. */
    var onNeedMic: () -> Unit = {}
    /** Камера, пока открыта, забирает распознанное себе. */
    var camera: CaptureSink? = null
    var sheet: CaptureSink? = null
    var view: View? = null

    private val voice get() = App.instance.voice
    private val sink get() = camera ?: sheet
    val editable get() = sink?.editable ?: true
    val simple get() = sink?.simple ?: false
    val recording get() = state.rec == Rec.HOLD || state.rec == Rec.FREE

    fun send(e: Ev) {
        val before = state
        val (next, eff) = Machine.reduce(state, e)
        state = next
        // отдача: дотянул до цели — щелчок; без рук — подтверждение
        if (next.nearCancel && !before.nearCancel || next.nearEdit && !before.nearEdit) buzz(if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE else HapticFeedbackConstants.CLOCK_TICK)
        if (before.rec == Rec.HOLD && next.rec == Rec.FREE) buzz(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY)
        when (eff) {
            is Effect.Start -> start()
            is Effect.Stop -> stop(eff.how)
            null -> Unit
        }
    }

    private fun start() {
        val ok = when {
            !voice.hasMic() -> { onNeedMic(); false }
            !voice.installed.value -> { say(tr(R.string.cmp_no_voice)); false }
            !voice.start() -> { say(tr(R.string.cmp_mic_failed)); false }
            else -> true
        }
        if (!ok) { state = RecState(); return }
        t0 = SystemClock.uptimeMillis()
        RecUi.phase.value = RecUi.Phase.LISTENING
        voice.onTimeout = { send(Ev.Timeout) }
        buzz(HapticFeedbackConstants.VIRTUAL_KEY)
    }

    private fun stop(how: How) {
        val to = sink
        if (how == How.CANCEL) {
            voice.cancel(); RecUi.phase.value = RecUi.Phase.IDLE
            buzz(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS)
            to?.cancelled(); return
        }
        RecUi.phase.value = RecUi.Phase.PROCESSING
        buzz(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY)
        App.instance.scope.launch {
            val r = voice.stop()
            RecUi.phase.value = RecUi.Phase.IDLE
            send(Ev.Done)
            when (r) {
                is Voice.Result.Text -> to?.text(r.text, how)
                Voice.Result.TooShort -> to?.cancelled()
                Voice.Result.Empty -> { say(tr(R.string.cmp_empty_record)); to?.cancelled() }
                is Voice.Result.Failed -> { say(tr(R.string.cmp_not_recognized, r.message)); to?.cancelled() }
            }
        }
    }

    /** Микрофон остановили снаружи (лист спрятан, экран погас) — без отдачи. */
    fun lost() { if (recording) { send(Ev.Lost); RecUi.phase.value = RecUi.Phase.IDLE } }

    /** Лист закрыт: запись выбросить. */
    fun reset() { if (recording) send(Ev.TapCancel) }

    private fun say(t: String) { message.value = t }
    private fun buzz(c: Int) { view?.performHapticFeedback(c) }
}

/**
 * Кнопка записи — солнышко, микрофон фото-режима, солнышко у поля, микрофон камеры: одни жесты.
 * [before] — что сделать до записи (камера замораживает кадр). [density] — пиксели в dp.
 */
fun Modifier.captureButton(before: () -> Unit = {}) = composed {
    // место справа от кнопки до края экрана (dp): порог правки не дальше него
    val room = remember { floatArrayOf(Gesture.EDIT_DP) }
    val sw = LocalConfiguration.current.screenWidthDp
    val dens = androidx.compose.ui.platform.LocalDensity.current.density
    onGloballyPositioned { c -> room[0] = editRoom(sw - (c.positionInRoot().x + c.size.width / 2f) / dens) }
        .pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false).consume()
                trackCapture(SystemClock.uptimeMillis(), before, editAt = room[0])
            }
        }
}

/** Порог правки по месту справа: не дальше края (минус палец), не ближе 36 dp. */
fun editRoom(rightDp: Float) = (rightDp - 14f).coerceIn(36f, Gesture.EDIT_DP)

/**
 * Палец уже на кнопке (с момента [at]): начать запись (или продолжить «без рук») и вести жест до отпускания.
 * Общий хвост для всех кнопок записи — и для тех, что сначала решают сами (солнышко с вложением, камера, поле с текстом).
 */
suspend fun androidx.compose.ui.input.pointer.AwaitPointerEventScope.trackCapture(at: Long, before: () -> Unit = {}, startX: Float = 0f, editAt: Float = Gesture.EDIT_DP) {
    if (Capture.state.rec == Rec.RECOG) return
    if (Capture.state.rec == Rec.IDLE) before()
    Capture.send(Ev.Down(Capture.editable, editAt, Capture.simple))
    if (Capture.state.rec == Rec.IDLE) return   // не стартовал (нет микрофона, голоса)
    val slop = viewConfiguration.touchSlop
    var x = startX; var moved = abs(x) > slop
    while (true) {
        val c = awaitPointerEvent().changes.first()
        if (!c.pressed) break
        x += c.positionChange().x; c.consume()
        if (abs(x) > slop) moved = true
        Capture.send(Ev.Drag(x / density))
        if (!Capture.recording) return   // ушло в отмену или правку
    }
    Capture.send(Ev.Up(SystemClock.uptimeMillis() - at, moved))
}
