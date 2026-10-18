package ru.poneslas.tasya.probe.guide

import android.content.Context
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import ru.poneslas.tasya.probe.App
import ru.poneslas.tasya.probe.Registry
import ru.poneslas.tasya.probe.core.Results
import ru.poneslas.tasya.probe.core.Runner
import ru.poneslas.tasya.probe.core.Status

/** Шаг прогона: проверка и как её называть человеку. */
data class Step(val id: String, val title: String)

data class Part(val n: Int, val title: String, val note: String, val minutes: Int, val steps: List<Step>)

/**
 * Ведущий прогон: две части. Первая идёт без человека подряд, вторая — по шагу с короткой
 * инструкцией. Состояние — в Results, так что прогон переживает перезапуск и обновление.
 */
object Guide {
    val parts = listOf(
        Part(
            1, "Телефон сам", "Положите телефон экраном вверх и не трогайте.", 8,
            listOf(Step("П4", "Микрофон"), Step("П6", "Камера"), Step("П23", "Ответ после закрытия листа"), Step("П25", "Ключ API")),
        ),
        Part(
            2, "С вами", "По одному действию за раз.", 15,
            listOf(
                Step("Г1", "Боковая кнопка"), Step("Г2", "Камера с кнопки"), Step("Г3", "Заблокированный экран"),
                Step("Г4", "Фото и файл из листа"), Step("Г5", "Ответ при выключенном экране"),
                Step("Г6", "Видно ли настройку кнопки"), Step("Г7", "После обновления"),
            ),
        ),
    )

    val allSteps get() = parts.flatMap { it.steps }

    private val _current = MutableStateFlow<Step?>(null)
    /** Шаг, который идёт сейчас. */
    val current: StateFlow<Step?> = _current
    private var job: Job? = null

    fun isGuideStep(id: String?) = id != null && allSteps.any { it.id == id }
    fun partOf(id: String) = parts.first { p -> p.steps.any { it.id == id } }
    fun indexIn(id: String) = partOf(id).steps.indexOfFirst { it.id == id }

    fun status(id: String): Status = Results.get(id).status
    private fun done(id: String) = status(id) in setOf(Status.PASS, Status.FAIL, Status.PARTIAL) &&
        !(id == "Г7" && Results.get(id).summary.startsWith("Ждём"))

    /** Первый незаконченный шаг части (или null — часть пройдена). */
    fun nextIn(part: Part) = part.steps.firstOrNull { !done(it.id) }

    fun partDone(part: Part) = nextIn(part) == null

    /** Запустить часть с первого незаконченного шага; [only] — один шаг (повтор). */
    fun start(ctx: Context, part: Part, only: Step? = null) {
        if (job?.isActive == true || Runner.running.value != null) return
        job = App.scope.launch {
            try {
                val steps = only?.let { listOf(it) } ?: part.steps.filter { !done(it.id) }
                for (s in steps) {
                    _current.value = s
                    Runner.runAwait(ctx, Registry.probe(s.id))
                    // Г7 ждёт установки новой версии — дальше идти некуда.
                    if (s.id == "Г7" && !done(s.id)) break
                }
            } finally {
                _current.value = null
            }
        }
    }

    fun stop() {
        job?.cancel()
        Runner.cancel()
    }

    /** После обновления пробника — закончить шаг «После обновления» без касаний. */
    fun resumeAfterUpdate(ctx: Context) {
        if (UpdateStep.pending(ctx)) start(ctx, parts[1], parts[1].steps.first { it.id == "Г7" })
    }
}
