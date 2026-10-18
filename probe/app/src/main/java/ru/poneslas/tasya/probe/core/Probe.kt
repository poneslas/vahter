package ru.poneslas.tasya.probe.core

import android.content.Context
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import ru.poneslas.tasya.probe.App
import ru.poneslas.tasya.probe.Registry
import ru.poneslas.tasya.probe.TAG

/**
 * Одна проверка. [run] возвращает итоговый статус; цифры, события и ошибки пишутся через [ProbeScope].
 * [needsHuman] — проверка не входит в «Запустить всё автоматическое».
 */
abstract class Probe(
    val id: String,
    val title: String,
    val what: String,
    val needsHuman: Boolean,
    val steps: List<String> = emptyList(),
    val implemented: Boolean = true,
) {
    abstract suspend fun ProbeScope.run(): Status
}

/** Заглушка для проверок, которые ещё не написаны: честно «не сделано». */
class NotImplementedProbe(id: String, title: String, what: String, private val why: String) :
    Probe(id, title, what, needsHuman = true, implemented = false) {
    override suspend fun ProbeScope.run(): Status {
        summary(why)
        return Status.NOT_IMPL
    }
}

class ProbeScope(val ctx: Context, val id: String) {
    private val t0 = SystemClock.uptimeMillis()

    fun log(tag: String, text: String = "") {
        Log.i(TAG, "[$id] $tag $text")
        val t = SystemClock.uptimeMillis() - t0
        Results.update(id) { it.copy(events = (it.events + Event(t, tag, text)).takeLast(600)) }
    }

    /** Добавляет значение в ряд [name]; медиана и p90 считаются в отчёте. */
    fun metric(name: String, v: Number) {
        Log.i(TAG, "[$id] metric $name=$v")
        Results.update(id) { r -> r.copy(metrics = r.metrics + (name to ((r.metrics[name] ?: emptyList()) + v.toDouble()))) }
    }

    fun error(t: Throwable, where: String = "") {
        Log.w(TAG, "[$id] error $where", t)
        Results.update(id) {
            it.copy(errors = it.errors + Err(t.javaClass.simpleName, (if (where.isNotEmpty()) "$where: " else "") + (t.message ?: ""), t.stackTraceToString().take(4000)))
        }
    }

    fun summary(s: String) {
        Log.i(TAG, "[$id] summary $s")
        Results.update(id) { it.copy(summary = s) }
    }

    /** Вопрос человеку: показывается на экране проверки и в листе. Возвращает выбранный вариант. */
    suspend fun ask(question: String, options: List<String> = listOf("Да", "Нет")): String {
        log("ask", question)
        val a = Human.ask(id, question, options)
        log("answer", a)
        return a
    }

    /** Инструкция человеку с одной кнопкой «Готово». */
    suspend fun tell(text: String, button: String = "Готово") { ask(text, listOf(button)) }
}

data class Prompt(val probeId: String, val text: String, val options: List<String>, val answer: CompletableDeferred<String>)

object Human {
    val prompt = MutableStateFlow<Prompt?>(null)

    suspend fun ask(probeId: String, text: String, options: List<String>): String {
        val p = Prompt(probeId, text, options, CompletableDeferred())
        prompt.value = p
        try {
            return p.answer.await()
        } finally {
            if (prompt.value === p) prompt.value = null
        }
    }

    fun answer(option: String) {
        prompt.value?.answer?.complete(option)
    }
}

object Runner {
    val running = MutableStateFlow<String?>(null)
    private var job: Job? = null

    fun start(ctx: Context, probe: Probe) {
        if (running.value != null) return
        job = App.scope.launch { run(ctx, probe) }
    }

    fun startAllAutomatic(ctx: Context) {
        if (running.value != null) return
        job = App.scope.launch {
            Registry.probes.filter { !it.needsHuman && it.implemented }.forEach { run(ctx, it) }
        }
    }

    fun cancel() {
        job?.cancel()
    }

    /** Прогнать проверку и дождаться итога (ведущий прогон запускает проверки по очереди). */
    suspend fun runAwait(ctx: Context, probe: Probe) = run(ctx, probe)

    private suspend fun run(ctx: Context, probe: Probe) {
        running.value = probe.id
        val note = Results.get(probe.id).note
        Results.update(probe.id) { ProbeResult(probe.id, Status.RUNNING, note = note, startedAt = System.currentTimeMillis()) }
        val scope = ProbeScope(ctx, probe.id)
        scope.log("start", "${probe.title}; ${Header.short(ctx)}")
        val status = try {
            with(probe) { scope.run() }
        } catch (e: CancellationException) {
            scope.log("cancel", "прервано человеком")
            Status.PARTIAL
        } catch (t: Throwable) {
            scope.error(t, "необработанное исключение")
            Status.FAIL
        } finally {
            Human.prompt.value?.takeIf { it.probeId == probe.id }?.answer?.cancel()
        }
        Results.update(probe.id) { it.copy(status = status, finishedAt = System.currentTimeMillis()) }
        scope.log("end", status.label)
        Results.saveNow()
        running.value = null
    }
}
