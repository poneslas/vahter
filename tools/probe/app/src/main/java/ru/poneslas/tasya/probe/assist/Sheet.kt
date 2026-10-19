package ru.poneslas.tasya.probe.assist

import android.os.Bundle
import android.os.SystemClock
import androidx.compose.runtime.Composable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Одно открытие листа. Времена — SystemClock.uptimeMillis(); null — точка не наступила.
 * [tCall] — когда пробник сам вызвал showSession (для серий без человека).
 */
data class OpenRecord(
    val seq: Int,
    val flags: Int,
    val args: Bundle,
    val tPrepare: Long?,
    val tCreate: Long?,
    val tContent: Long?,
    val tShow: Long,
    val tFrame: Long,
    val freshProcess: Boolean,
    val newSession: Boolean,
    val locked: Boolean,
    val tCall: Long? = null,
    /** Время нажатия от системы (args TIME / invocation_time_ms), приведённое к uptime. */
    val tInvoke: Long? = null,
    val wallTime: Long = System.currentTimeMillis(),
) {
    val mode: String? get() = args.getString("mode")
    /** Самая ранняя точка в нашем коде → первый кадр. */
    val prepareToFrame get() = tFrame - (tPrepare ?: tCreate ?: tShow)
    val showToFrame get() = tFrame - tShow
    val callToFrame get() = tCall?.let { tFrame - it }
    /** Нажатие → первый кадр: полная цена кнопки, включая систему. */
    val invokeToFrame get() = tInvoke?.let { tFrame - it }

    fun describe() = buildString {
        append("#$seq flags=0x${Integer.toHexString(flags)} [${showFlags(flags)}]")
        mode?.let { append(" mode=$it") }
        append(" prepare→кадр ${prepareToFrame} мс, onShow→кадр ${showToFrame} мс")
        callToFrame?.let { append(", вызов→кадр $it мс") }
        invokeToFrame?.let { append(", нажатие→кадр $it мс") }
        if (freshProcess) append(", процесс новый")
        if (newSession) append(", сессия новая")
        if (locked) append(", блокировка")
    }
}

fun showFlags(f: Int): String {
    val names = listOf(
        1 to "WITH_ASSIST", 2 to "WITH_SCREENSHOT", 4 to "SOURCE_ASSIST_GESTURE",
        8 to "SOURCE_APPLICATION", 16 to "SOURCE_ACTIVITY", 32 to "SOURCE_PUSH_TO_TALK",
        64 to "SOURCE_NOTIFICATION", 128 to "SOURCE_AUTOMOTIVE_SYSTEM_UI",
    )
    return names.filter { f and it.first != 0 }.joinToString("|") { it.second }.ifEmpty { "0" }
}

/** Кнопка листа. Группа — пакет проверки; [run] выполняется в App.scope. */
class SheetAction(val group: String, val label: String, val run: suspend (ProbeSession) -> Unit)

/** Что показывает лист: строка статуса и необязательная панель проверки (видоискатель, список, поле). */
data class SheetUi(
    val status: String = "",
    val panel: (@Composable (ProbeSession) -> Unit)? = null,
    val groups: Set<String>? = null,
    /** Панель на всё окно листа (камера: решение 18.10 — видоискатель на весь экран). */
    val fullscreen: Boolean = false,
)

/** Связка «сервис помощника — сессия — проверки». Всё живёт в одном процессе. */
object Sheet {
    @Volatile var service: ProbeAssistService? = null
    val serviceReady = MutableStateFlow(false)
    val session = MutableStateFlow<ProbeSession?>(null)
    val visible = MutableStateFlow(false)
    val last = MutableStateFlow<OpenRecord?>(null)
    val ui = MutableStateFlow(SheetUi())

    val opens = MutableSharedFlow<OpenRecord>(extraBufferCapacity = 64)
    val hides = MutableSharedFlow<Long>(extraBufferCapacity = 64)

    /** Ставится в onPrepareToShowSession, снимается при onShow. */
    @Volatile var pendingPrepare: Long? = null
    /** Вызываются в onPrepareToShowSession — самая ранняя точка открытия (П4: ранний старт микрофона). */
    val prepareHooks = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    /** Ставится перед showSession из пробника — так открытие помечается как «вызвал пробник». */
    @Volatile var pendingCall: Long? = null
    var seq = 0
    var lastOpenProcessCreatedAt = 0L

    fun setStatus(s: String) = ui.update { it.copy(status = s) }
    fun setPanel(p: (@Composable (ProbeSession) -> Unit)?) = ui.update { it.copy(panel = p, fullscreen = false) }
    fun setFullPanel(p: @Composable (ProbeSession) -> Unit) = ui.update { it.copy(panel = p, fullscreen = true) }
    fun showGroups(g: Set<String>?) = ui.update { it.copy(groups = g) }

    suspend fun awaitService(timeoutMs: Long = 3000): ProbeAssistService? {
        service?.let { return it }
        return withTimeoutOrNull(timeoutMs) { serviceReady.first { it }; service }
    }

    /** Открыть лист самим пробником и дождаться первого кадра. null — не открылся за [timeoutMs]. */
    suspend fun open(args: Bundle = Bundle(), flags: Int = 0, timeoutMs: Long = 4000): OpenRecord? = coroutineScope {
        val svc = awaitService() ?: return@coroutineScope null
        val wait = async { withTimeoutOrNull(timeoutMs) { opens.first() } }
        pendingCall = SystemClock.uptimeMillis()
        svc.showSession(args, flags)
        wait.await().also { if (it == null) pendingCall = null }
    }

    /** Закрыть лист и дождаться onHide. */
    suspend fun close(timeoutMs: Long = 3000): Boolean = coroutineScope {
        val s = session.value ?: return@coroutineScope true
        if (!visible.value) return@coroutineScope true
        val wait = async { withTimeoutOrNull(timeoutMs) { hides.first() } }
        s.hide()
        wait.await() != null
    }

    /** Серия открытий/закрытий без человека: возвращает записи удачных открытий. */
    suspend fun series(n: Int, args: Bundle = Bundle(), pauseMs: Long = 600, onEach: suspend (Int, OpenRecord?) -> Unit = { _, _ -> }): List<OpenRecord> {
        val out = mutableListOf<OpenRecord>()
        repeat(n) { i ->
            val r = open(Bundle(args))
            onEach(i, r)
            if (r != null) out += r
            delay(300)
            close()
            delay(pauseMs)
        }
        return out
    }
}
